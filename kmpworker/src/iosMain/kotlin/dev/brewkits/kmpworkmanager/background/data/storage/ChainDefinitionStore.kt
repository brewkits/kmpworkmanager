@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.decodeFromPathComponent
import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

/**
 * Owns chain definitions — the serialised step lists on disk — and the "deleted" markers that
 * make the REPLACE policy safe across processes.
 *
 * Stage 4 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 * `IosFileStorage` keeps its methods and delegates each one here.
 *
 * The two groups live together because the marker exists only to say something about a chain
 * definition: `markChainAsDeleted` is what stops a replaced chain from executing after its
 * definition has been swapped, and `ChainExecutor` reads the marker in the same breath as the
 * definition. Splitting them would put two halves of one decision in two files.
 *
 * A verbatim move on top of the Stage 0b seam, with the collaborators the code already used
 * arriving as constructor parameters instead of being reached for on the façade.
 *
 * @param io the shared file-I/O + coordination primitives.
 * @param maintenance supplies the disk-space guard that gates `saveChainDefinition`.
 * @param persistenceJson tolerant reader for persisted data (`ignoreUnknownKeys`). Note the
 * asymmetry, preserved from the original: reads go through this, writes use plain [Json].
 * @param chainsDir resolves the chain-definition directory.
 * @param deletedChainsDir resolves the deleted-marker directory.
 * @param maxChainSizeBytes refuse to persist a definition larger than this.
 * @param deletedMarkerMaxAgeMs how long a deleted marker survives before the reaper takes it.
 * @param deleteProgress evicts a chain's progress. Needed by `loadChainDefinition`'s
 * self-healing path: a definition that fails to decode takes its progress with it, or the
 * chain resumes against a definition that no longer exists. Passed in rather than depending
 * on `ChainProgressStore` directly, so the two stores stay independent.
 */
internal class ChainDefinitionStore(
    private val io: StorageFileIo,
    private val maintenance: StorageMaintenance,
    private val persistenceJson: Json,
    private val chainsDir: () -> NSURL,
    private val deletedChainsDir: () -> NSURL,
    private val maxChainSizeBytes: Long,
    private val deletedMarkerMaxAgeMs: Long,
    private val deleteProgress: suspend (String) -> Unit
) {

    /**
     * Test-only hook: widens the `loadChainDefinition` prologue window so a regression test
     * can cancel between dequeue and step execution. No-op in production.
     */
    internal var testLoadChainDefinitionDelayMs: Long = 0L

    /**
     * Save chain definition to file
     */
    fun saveChainDefinition(id: String, steps: List<List<TaskRequest>>) {
        val chainFile = chainsDir().safeAppend("${id.encodeAsPathComponent()}.json")
        val json = Json.encodeToString(steps)

        // Use actual UTF-8 byte count, not String.length (UTF-16 char count).
        // For CJK / emoji content, UTF-8 bytes can be 3–4× the char count — a 5M-char
        // CJK string passes the old check but writes ~15MB to disk.
        val sizeBytes = json.encodeToByteArray().size.toLong()
        if (sizeBytes > maxChainSizeBytes) {
            Logger.e(
                LogTags.CHAIN,
                "Chain $id exceeds size limit: $sizeBytes bytes (max: $maxChainSizeBytes)"
            )
            error("Chain size exceeds limit")
        }

        maintenance.checkDiskSpace(sizeBytes)

        io.coordinated(chainFile, write = true) { safeUrl ->
            io.writeStringToFile(safeUrl, json)
        }

        Logger.d(LogTags.CHAIN, "Saved chain definition $id ($sizeBytes bytes)")
    }

    /**
     * Load chain definition from file with self-healing for corrupt data.
     *
     * `suspend` because the self-healing path calls [deleteChainProgress] which is
     * now `suspend` (must acquire `progressMutex` to evict the buffer entry).
     */
    // Verbatim move; the finding was baselined at the old location and a detekt baseline
    // keys on declaration text. catch(Exception) is deliberate: any decode failure means the
    // definition is unusable, and the recovery is the same whichever exception was thrown.
    @Suppress("TooGenericExceptionCaught")
    suspend fun loadChainDefinition(id: String): List<List<TaskRequest>>? {
        // Test-only hook: widens the prologue window so a regression test can cancel between
        // dequeue and step execution. No-op in production.
        if (testLoadChainDefinitionDelayMs > 0L) {
            delay(testLoadChainDefinitionDelayMs)
        }
        val chainFile = chainsDir().safeAppend("${id.encodeAsPathComponent()}.json")

        // The coordination callback is a non-suspend block, so the suspend-only
        // `deleteProgress(id)` call has to happen AFTER coordination returns.
        // We surface "needs self-heal" via a flag captured in the return tuple.
        var needsSelfHealProgress = false
        // coordinatedSuspend, not coordinated: this function is `suspend` and is called from
        // ChainExecutor on Dispatchers.Default. The blocking variant parks that thread inside
        // dispatch_semaphore_wait for the whole coordination — the exact violation of Key
        // Invariant #1 in CLAUDE.md, and with MAX_PARALLEL_TASKS = 4 chains loading
        // definitions concurrently it can starve the shared Default pool outright.
        // The block itself is non-suspend in both variants, so this is a drop-in swap.
        val result = io.coordinatedSuspend(chainFile, write = false) { safeUrl ->
            val json = io.readStringFromFile(safeUrl) ?: return@coordinatedSuspend null

            try {
                persistenceJson.decodeFromString<List<List<TaskRequest>>>(json)
            } catch (e: Exception) {
                Logger.e(
                    LogTags.CHAIN,
                    "🩹 Self-healing: Corrupt chain definition detected for $id. " +
                        "Deleting corrupt file...",
                    e
                )

                // Delete corrupt chain definition (file deletion is non-suspend, safe here)
                io.deleteFile(chainFile)
                // Defer the suspend-only progress cleanup until after we exit coordination.
                needsSelfHealProgress = true

                Logger.w(
                    LogTags.CHAIN,
                    "Corrupt chain $id has been removed. " +
                        "It will need to be re-enqueued if still needed."
                )
                null
            }
        }

        if (needsSelfHealProgress) {
            // Now that we're outside the coordination block, the suspend call is legal.
            deleteProgress(id)
        }
        return result
    }

    /**
     * Delete chain definition
     */
    fun deleteChainDefinition(id: String) {
        val chainFile = chainsDir().safeAppend("${id.encodeAsPathComponent()}.json")
        io.deleteFile(chainFile)
        Logger.d(LogTags.CHAIN, "Deleted chain definition $id")
    }

    /**
     * Check if chain definition exists
     */
    fun chainExists(id: String): Boolean {
        val chainFile = chainsDir().safeAppend("${id.encodeAsPathComponent()}.json")
        val path = chainFile.path ?: return false
        return io.fileManager.fileExistsAtPath(path)
    }

    /**
     * Mark a chain as deleted to prevent duplicate execution during REPLACE policy.
     * The marker contains a timestamp for cleanup purposes.
     *
     */
    fun markChainAsDeleted(chainId: String) {
        val markerFile = deletedChainsDir().safeAppend("${chainId.encodeAsPathComponent()}.marker")
        val timestamp = NSDate().timeIntervalSince1970.toLong()

        io.coordinated(markerFile, write = true) { safeUrl ->
            io.writeStringToFile(safeUrl, timestamp.toString())
        }

        Logger.d(LogTags.CHAIN, "Marked chain $chainId as deleted (REPLACE policy)")
    }

    /**
     * Check if a chain has been marked as deleted.
     * Used by ChainExecutor to skip execution of replaced chains.
     *
     */
    fun isChainDeleted(chainId: String): Boolean {
        val markerFile = deletedChainsDir().safeAppend("${chainId.encodeAsPathComponent()}.marker")
        val path = markerFile.path ?: return false
        return io.fileManager.fileExistsAtPath(path)
    }

    /**
     * Clear the deleted marker for a chain.
     * Called after skipping execution of a deleted chain.
     *
     */
    fun clearDeletedMarker(chainId: String) {
        val markerFile = deletedChainsDir().safeAppend("${chainId.encodeAsPathComponent()}.marker")
        io.deleteFile(markerFile)
        Logger.d(LogTags.CHAIN, "Cleared deleted marker for chain $chainId")
    }

    /**
     * Remove deleted markers older than [deletedMarkerMaxAgeMs] (default 7 days).
     * Prevents disk space leaks from accumulated markers.
     */
    fun cleanupStaleDeletedMarkers() {
        val path = deletedChainsDir().path ?: return
        val files = io.fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return

        val now = NSDate().timeIntervalSince1970.toLong() * 1000 // Convert to milliseconds
        var cleanedCount = 0

        files.forEach { fileName ->
            if (fileName !is String) return@forEach
            if (!fileName.endsWith(".marker")) return@forEach

            val markerFile = deletedChainsDir().safeAppend(fileName)
            val markerPath = markerFile.path ?: return@forEach

            // Read marker via coordinated() to match the write path in
            // markChainAsDeleted(). Without coordination, a concurrent write from the
            // App Extension could produce a partial/stale read, yielding timestamp=0
            // and causing the marker to be deleted prematurely (treated as 54+ years old).
            val timestampStr = io.coordinated(markerFile, write = false) { safeUrl ->
                io.readStringFromFile(safeUrl)
            }
            val timestamp = timestampStr?.toLongOrNull() ?: 0L

            val ageMs = now - (timestamp * 1000) // timestamp is in seconds
            if (ageMs > deletedMarkerMaxAgeMs) {
                io.fileManager.removeItemAtPath(markerPath, null)
                cleanedCount++
                Logger.d(
                    LogTags.CHAIN,
                    "Cleaned up stale deleted marker: $fileName " +
                        "(age: ${ageMs / 86400000}days)"
                )
            }
        }

        if (cleanedCount > 0) {
            Logger.i(LogTags.CHAIN, "Cleaned up $cleanedCount stale deleted markers")
        }
    }

    /**
     * List all chain IDs that have a saved chain **definition** on disk — a broader set than
     * the execution queue's contents: a chain that has been dequeued for execution still has
     * its definition on disk right up until it completes, so this is the set
     * `computeIosTaskState` needs to catch a currently-EXECUTING chain, not just a queued one.
     *
     * The chains directory also holds `<encodedId>_progress.json` files (a different
     * artifact) — the `_progress` suffix is stripped from the still-**encoded** filename
     * before decoding, since decoding first could (in principle) produce a false match
     * against a chain id that legitimately ends in the literal text `_progress`.
     *
     * **Consumption**: single-use Sequence (backed by a stateful OS enumerator).
     */
    fun listChainDefinitionIds(): Sequence<String> =
        io.listJsonFileIds(chainsDir(), decode = false)
            .filterNot { it.endsWith("_progress") }
            .map { it.decodeFromPathComponent() }

}
