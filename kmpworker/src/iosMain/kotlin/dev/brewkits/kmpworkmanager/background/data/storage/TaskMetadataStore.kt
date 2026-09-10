@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.data.decodeFromPathComponent
import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSOrderedAscending
import platform.Foundation.compare
import platform.Foundation.NSURL
import platform.Foundation.dateByAddingTimeInterval

/**
 * Owns task metadata: the per-task JSON sidecar files under the one-time and periodic
 * metadata directories, the scans that answer "which tasks exist" and "which tasks carry
 * this tag", and the stale-file reaper.
 *
 * Stage 2 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 * `IosFileStorage` keeps its metadata methods and delegates each one here.
 *
 * A verbatim move on top of the Stage 0b seam: file I/O and coordination go through [io],
 * and the two metadata directories arrive as lambdas so the façade's `by lazy` directory
 * creation still happens on first touch rather than at construction.
 *
 * The plan proposed switching `NativeTaskScheduler` and `IosBackgroundTaskHandler` to an
 * injected store and deleting the façade methods in this same stage. That is deliberately
 * *not* done here: it would mix a code move with a call-site migration in one diff, and the
 * whole reason this split is staged is to keep those two apart. The façade delegations
 * retire when the call sites move, not before.
 *
 * @param io the shared file-I/O + coordination primitives.
 * @param persistenceJson tolerant reader for persisted data (`ignoreUnknownKeys`). Note the
 * asymmetry, preserved from the original: reads go through this, writes use plain [Json].
 * @param tasksDir resolves the one-time task metadata directory.
 * @param periodicDir resolves the periodic task metadata directory.
 */
internal class TaskMetadataStore(
    private val io: StorageFileIo,
    private val persistenceJson: Json,
    private val tasksDir: () -> NSURL,
    private val periodicDir: () -> NSURL
) {

    /**
     * Save task metadata
     */
    fun saveTaskMetadata(id: String, metadata: Map<String, String>, periodic: Boolean) {
        val dir = if (periodic) periodicDir() else tasksDir()
        val metaFile = dir.safeAppend("${id.encodeAsPathComponent()}.json")
        val json = Json.encodeToString(metadata)

        io.coordinated(metaFile, write = true) { safeUrl ->
            io.writeStringToFile(safeUrl, json)
        }

        Logger.d(LogTags.SCHEDULER, "Saved ${if (periodic) "periodic" else "task"} metadata for $id")
    }

    /**
     * Load task metadata with self-healing for corrupt data
     */
    // Verbatim move: the finding was baselined at the old location, and a detekt baseline
    // keys on declaration text. catch(Exception) is deliberate — any decode failure at all
    // means the file is unusable, and the recovery (delete it, return null, let the task be
    // rescheduled) is the same whichever exception kotlinx.serialization chose to throw.
    @Suppress("TooGenericExceptionCaught")
    fun loadTaskMetadata(id: String, periodic: Boolean): Map<String, String>? {
        val dir = if (periodic) periodicDir() else tasksDir()
        val metaFile = dir.safeAppend("${id.encodeAsPathComponent()}.json")

        return io.coordinated(metaFile, write = false) { safeUrl ->
            val json = io.readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                persistenceJson.decodeFromString<Map<String, String>>(json)
            } catch (e: Exception) {
                val metadataType = if (periodic) "periodic" else "task"
                Logger.e(
                    LogTags.SCHEDULER,
                    "🩹 Self-healing: Corrupt $metadataType metadata detected for $id. " +
                        "Deleting corrupt file...",
                    e
                )

                // Delete corrupt metadata file
                io.deleteFile(metaFile)

                Logger.w(
                    LogTags.SCHEDULER,
                    "Corrupt $metadataType metadata for $id has been removed. " +
                        "Task will need to be rescheduled."
                )
                null
            }
        }
    }

    /**
     * List all non-periodic task IDs that have saved metadata.
     * Used by the catch-up executor to find missed exact-alarm tasks.
     *
     * Returns a lazy [Sequence] over task IDs so callers can stream each entry
     * without materialising the full list in memory. On a device with 50 000 task
     * files the old List<String> approach allocates ~4 MB just for ID strings; a
     * Sequence allocates O(1) — one NSURL at a time from the NSDirectoryEnumerator.
     *
     * The enumerator is depth-1 (shallow) so subdirectories are never traversed.
     *
     * **Consumption**: The returned Sequence is single-use (backed by a stateful
     * OS enumerator). Do not iterate it more than once.
     */
    fun listTaskIds(): Sequence<String> = io.listJsonFileIds(tasksDir(), decode = false)

    /**
     * List all one-time task IDs that have saved metadata, **decoded** back to the original
     * id (unlike [listTaskIds], which returns the raw on-disk filename for historical
     * compatibility with its existing caller). Used by [computeIosTaskState]/`queryTasks`,
     * which need the real id to report back to the caller, not its on-disk encoding.
     */
    internal fun listOneTimeTaskIdsDecoded(): Sequence<String> = io.listJsonFileIds(tasksDir(), decode = true)

    /**
     * List all periodic task IDs that have saved metadata, decoded. See
     * [listOneTimeTaskIdsDecoded] for why this is a separate function from [listTaskIds]
     * rather than a `periodic` parameter on it.
     */
    internal fun listPeriodicTaskIds(): Sequence<String> = io.listJsonFileIds(periodicDir(), decode = true)

    /**
     * Delete task metadata
     */
    fun deleteTaskMetadata(id: String, periodic: Boolean) {
        val dir = if (periodic) periodicDir() else tasksDir()
        val metaFile = dir.safeAppend("${id.encodeAsPathComponent()}.json")
        io.deleteFile(metaFile)
        Logger.d(LogTags.SCHEDULER, "Deleted ${if (periodic) "periodic" else "task"} metadata for $id")
    }

    /**
     * Finds standalone (non-chain) task IDs whose stored metadata matches [workerClassName]
     * or carries [tag], scanning both the one-time and periodic metadata directories.
     *
     * The chain-side counterpart is [findChainIdsByWorkerOrTag]; both are needed because a
     * task can be scheduled either way and `cancelByTag` must reach both. Returns pairs of
     * `(taskId, isPeriodic)` so the caller can delete the right metadata file — the two
     * directories can legitimately hold the same id.
     *
     * Either parameter may be null; passing both as null returns an empty list.
     */
    fun findTaskIdsByWorkerOrTag(
        workerClassName: String? = null,
        tag: String? = null
    ): List<Pair<String, Boolean>> {
        if (workerClassName == null && tag == null) return emptyList()
        val matches = mutableListOf<Pair<String, Boolean>>()

        listOf(tasksDir() to false, periodicDir() to true).forEach { (dir, isPeriodic) ->
            val path = dir.path ?: return@forEach
            val files = io.fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return@forEach

            files.forEach fileLoop@{ fileName ->
                val name = fileName as? String ?: return@fileLoop
                if (!name.endsWith(".json")) return@fileLoop
                val taskId = name.removeSuffix(".json").decodeFromPathComponent()
                val meta = loadTaskMetadata(taskId, periodic = isPeriodic) ?: return@fileLoop

                val workerMatches = workerClassName != null &&
                    meta["workerClassName"] == workerClassName
                val tagMatches = tag != null &&
                    meta[DynamicTaskDispatcher.META_TAGS]
                        ?.split(',')
                        ?.any { it == tag } == true

                if (workerMatches || tagMatches) matches += taskId to isPeriodic
            }
        }
        return matches
    }

    /**
     * Cleanup stale metadata older than specified days
     */
    fun cleanupStaleMetadata(olderThanDays: Int = 7) {
        val cutoffDate = NSDate().dateByAddingTimeInterval(-olderThanDays.toDouble() * 86400)

        listOf(tasksDir(), periodicDir()).forEach { dir ->
            val path = dir.path ?: return@forEach
            val files = io.fileManager.contentsOfDirectoryAtPath(path, null) as? List<*> ?: return@forEach

            files.forEach { fileName ->
                val filePath = "$path/$fileName"
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val attrs = io.fileManager.attributesOfItemAtPath(filePath, errorPtr.ptr)

                    val modDate = attrs?.get(NSFileModificationDate) as? NSDate
                    if (modDate != null && modDate.compare(cutoffDate) == NSOrderedAscending) {
                        io.fileManager.removeItemAtPath(filePath, null)
                        Logger.d(LogTags.SCHEDULER, "Cleaned up stale metadata: $fileName")
                    }
                }
            }
        }
    }
}
