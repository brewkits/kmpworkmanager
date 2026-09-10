@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.data.decodeFromPathComponent
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDirectoryEnumerationSkipsSubdirectoryDescendants
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileManagerItemReplacementWithoutDeletingBackupItem
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.URLByAppendingPathComponent
import platform.Foundation.URLByAppendingPathExtension
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Safe URL path component appending.
 *
 * Replaces `URLByAppendingPathComponent(x)!!` — throws with context instead of NPE.
 * In practice `URLByAppendingPathComponent` only returns null for empty components or
 * file-reference URLs; this provides a clearer crash message when that invariant breaks.
 */
internal fun NSURL.safeAppend(component: String): NSURL =
    URLByAppendingPathComponent(component)
        ?: error("Failed to construct URL: base='$path' component='$component'")

/**
 * The file-I/O and coordination primitives shared by every iOS storage component.
 *
 * Stage 0b of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * The plan's Stage 1 assumed the per-responsibility stores could simply be lifted out of
 * `IosFileStorage`. They cannot: measuring the chain-progress unit showed it reaching into
 * eleven private members of the god-class, seven of which are these primitives. Extracting
 * them once, here, is what lets each later stage be a pure move instead of a copy — without
 * this seam every store would carry its own duplicate of atomic-write and coordination logic,
 * which is exactly how `safeAppend` ended up with three copies in this source set.
 *
 * This class is deliberately **behaviour-preserving**: every function below was moved
 * verbatim from `IosFileStorage`, including its logging, its fallbacks and its test-mode
 * shortcuts. It adds nothing and decides nothing.
 *
 * @param isTestMode the storage instance's resolved test-mode flag — the config override if
 * the host set one, otherwise auto-detection. Governs whether writes are atomic and whether
 * directories get a file-protection attribute.
 * @param coordinatorTestMode the *raw* `config.isTestMode`, defaulted to `false` rather than
 * to auto-detection. This is intentionally not [isTestMode]: `IosFileCoordinator` does its
 * own test-environment detection, so passing auto-detection in here would double-apply it.
 * @param coordinationTimeoutMs how long a coordinated block may wait for the coordinator
 * (0 disables the timeout).
 */
internal class StorageFileIo(
    private val isTestMode: Boolean,
    private val coordinatorTestMode: Boolean,
    private val coordinationTimeoutMs: Long
) {

    /**
     * Exposed rather than private because several moved directory scans
     * (`cleanupStaleMetadata`, `findTaskIdsByWorkerOrTag`) use `NSFileManager` shapes that
     * have no wrapper here yet. One owner of the handle beats each store reaching for
     * `NSFileManager.defaultManager` on its own.
     */
    internal val fileManager = NSFileManager.defaultManager

    /**
     * Ensure directory exists, create if not.
     */
    fun ensureDirectoryExists(url: NSURL) {
        val path = url.path ?: return
        if (fileManager.fileExistsAtPath(path)) return

        // In test mode (CI simulator pre-first-unlock),
        // NSFileProtectionCompleteUntilFirstUserAuthentication blocks atomic writes
        // (NSString.writeToFile atomically:YES needs a temp file in the same directory).
        // Skip the protection attribute entirely in test environments.
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val ok = if (isTestMode) {
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = errorPtr.ptr
                )
            } else {
                // NSFileProtectionCompleteUntilFirstUserAuthentication: files remain encrypted
                // at rest but are accessible to background tasks after the first unlock
                // post-boot. NSFileProtectionComplete (the OS default) locks files when the
                // screen is off, making them unreadable by BGTasks — which defeats the purpose
                // of this library.
                val attributes = mapOf<Any?, Any?>(
                    NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication
                )
                fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = attributes,
                    error = errorPtr.ptr
                )
            }

            if (!ok) {
                val fallbackOk = fileManager.createDirectoryAtURL(
                    url,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
                if (!fallbackOk) {
                    throw IllegalStateException(
                        "Failed to create directory: " +
                            (errorPtr.value?.localizedDescription ?: "Unknown error")
                    )
                }
            }
        }
    }

    /**
     * Read string from file.
     */
    fun readStringFromFile(url: NSURL): String? {
        val path = url.path ?: return null

        if (!fileManager.fileExistsAtPath(path)) {
            return null
        }

        return memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val result = NSString.stringWithContentsOfFile(
                path,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
            // Check NSError so callers can distinguish "file not found"
            // from "file exists but unreadable" (iCloud placeholder, permissions, etc.)
            errorPtr.value?.let { error ->
                Logger.e(
                    LogTags.CHAIN,
                    "Failed to read file ${url.lastPathComponent}: ${error.localizedDescription}"
                )
            }
            result
        }
    }

    /**
     * Write string to file atomically.
     *
     * When [url] already has content on disk, uses [NSFileManager.replaceItemAtURL] — a
     * true filesystem-atomic swap — per this project's own established invariant (see
     * `AppendOnlyQueue`'s compaction fix): `NSString.writeToFile(atomically:)` is an older
     * API with known reliability gaps under some `NSFileProtection` classes and disk
     * conditions, the exact class of bug this codebase already fixed for the queue file.
     * Every caller of this function (task metadata, chain definitions, chain progress,
     * the transaction log) carries the same risk, so the fix applies here unconditionally
     * rather than only to the queue.
     *
     * Falls back to a direct (non-atomic) write if the target does not exist yet — there
     * is nothing to atomically replace on a first write — or in test mode, matching this
     * function's prior `atomically = !isTestMode` behavior (tests intentionally trade
     * atomicity for speed).
     */
    fun writeStringToFile(url: NSURL, content: String) {
        // Log instead of silent return — caller assumes write succeeded
        val path = url.path ?: run {
            Logger.e(
                LogTags.CHAIN,
                "writeStringToFile: url.path is null for ${url.absoluteString} — write skipped"
            )
            return
        }

        val nsString = content as NSString

        if (isTestMode || !fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val success = nsString.writeToFile(
                    path,
                    atomically = !isTestMode,
                    encoding = NSUTF8StringEncoding,
                    error = errorPtr.ptr
                )
                if (!success) {
                    error("Failed to write file: ${errorPtr.value?.localizedDescription}")
                }
            }
            return
        }

        replaceExisting(url, path, nsString)
    }

    /** Target exists — atomically replace via a temp file + `replaceItemAtURL`. */
    private fun replaceExisting(url: NSURL, path: String, nsString: NSString) {
        val tempURL = url.URLByAppendingPathExtension("tmp-${NSUUID().UUIDString()}")
            ?: error("Failed to construct temp URL for atomic write: $path")
        val tempPath = tempURL.path
            ?: error("Temp URL has no path for atomic write: $path")

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val wroteTemp = nsString.writeToFile(
                tempPath,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = errorPtr.ptr
            )
            if (!wroteTemp) {
                error(
                    "Failed to write temp file for atomic replace: " +
                        "${errorPtr.value?.localizedDescription}"
                )
            }

            val replaced = fileManager.replaceItemAtURL(
                originalItemURL = url,
                withItemAtURL = tempURL,
                backupItemName = null,
                options = NSFileManagerItemReplacementWithoutDeletingBackupItem,
                resultingItemURL = null,
                error = errorPtr.ptr
            )

            if (!replaced) {
                val error = errorPtr.value
                Logger.w(
                    LogTags.CHAIN,
                    "replaceItemAtURL failed for $path (${error?.localizedDescription}) — " +
                        "falling back to direct write"
                )
                // replaceItemAtURL consumes the temp file on success; on failure it may or may
                // not still be there depending on how far it got — clean up defensively.
                try {
                    fileManager.removeItemAtPath(tempPath, null)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Logger.w(
                        LogTags.CHAIN,
                        "Best-effort temp file cleanup failed (ignored): ${e.message}"
                    )
                }
                val fallbackOk = nsString.writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = errorPtr.ptr
                )
                if (!fallbackOk) {
                    error(
                        "Failed to write file (fallback after replaceItemAtURL failure): " +
                            "${errorPtr.value?.localizedDescription}"
                    )
                }
            }
        }
    }

    /**
     * Delete file if exists.
     */
    fun deleteFile(url: NSURL) {
        val path = url.path ?: return

        if (fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.removeItemAtPath(path, errorPtr.ptr)

                if (errorPtr.value != null) {
                    Logger.w(
                        LogTags.SCHEDULER,
                        "Failed to delete file: ${errorPtr.value?.localizedDescription}"
                    )
                }
            }
        }
    }

    /**
     * Synchronous file coordination bridge for non-suspend callers.
     *
     * Blocks the calling thread via runBlocking — safe only from threads that are NOT
     * Dispatchers.Default coroutine threads (e.g. the GCD high-priority queue, init blocks,
     * or Swift-called functions). Never call this from inside a suspend function; use
     * [coordinatedSuspend] instead to avoid blocking a Dispatchers.Default thread.
     */
    fun <T> coordinated(url: NSURL, write: Boolean, block: (NSURL) -> T): T {
        return runBlocking {
            IosFileCoordinator.coordinate(
                url = url,
                write = write,
                isTestMode = coordinatorTestMode,
                timeoutMs = coordinationTimeoutMs,
                block = block
            )
        }
    }

    /**
     * Suspend-native file coordination for use inside coroutines.
     *
     * Calls [IosFileCoordinator.coordinate] directly without a runBlocking bridge.
     * This ensures the calling Dispatchers.Default thread is released while
     * NSFileCoordinator waits on IosDispatchers.IO — preventing thread starvation
     * when multiple chains flush progress concurrently.
     *
     * Only call from suspend functions. Non-suspend callers must use [coordinated].
     */
    suspend fun <T> coordinatedSuspend(url: NSURL, write: Boolean, block: (NSURL) -> T): T {
        return IosFileCoordinator.coordinate(
            url = url,
            write = write,
            isTestMode = coordinatorTestMode,
            timeoutMs = coordinationTimeoutMs,
            block = block
        )
    }

    /**
     * Lazily lists the `.json` file names (extension stripped) directly inside [dir].
     *
     * Returns a [Sequence] so callers can stream each entry without materialising the full
     * list: on a device with 50 000 task files a `List<String>` allocates ~4 MB just for id
     * strings, while a Sequence allocates O(1) — one `NSURL` at a time from the
     * `NSDirectoryEnumerator`. The enumerator is depth-1 (shallow), so subdirectories are
     * never traversed.
     *
     * @param decode whether to reverse `encodeAsPathComponent` on each name before returning
     *   it — `false` when the caller must do its own suffix-stripping on the still-encoded
     *   form first (chain definitions strip `_progress` that way).
     *
     * **Consumption**: the returned Sequence is single-use (backed by a stateful OS
     * enumerator). Do not iterate it more than once.
     */
    fun listJsonFileIds(dir: NSURL, decode: Boolean): Sequence<String> {
        // NSDirectoryEnumerationSkipsHiddenFiles is deliberately NOT set. It used to be, and
        // it made every task or chain whose id starts with '.' invisible to this listing:
        // the encoder leaves a leading dot alone, so id ".internal.sync" is stored as
        // ".internal.sync.json" and an id like "../escape" as "..%2Fescape.json" — both of
        // which the OS classifies as hidden. The records were on disk and readable by id,
        // but absent from queryTasks, from computeIosTaskState, and from the catch-up scan
        // for missed exact alarms, while findTaskIdsByWorkerOrTag (which enumerates through
        // contentsOfDirectoryAtPath instead) still saw them — so cancelByTag and queryTasks
        // disagreed about which tasks existed. There are no user "hidden files" here to
        // respect: this directory holds only this library's own records, and the `.json`
        // suffix filter below already excludes strays like .DS_Store.
        val enumerator = fileManager.enumeratorAtURL(
            dir,
            includingPropertiesForKeys = null,
            options = NSDirectoryEnumerationSkipsSubdirectoryDescendants,
            errorHandler = null
        ) ?: return emptySequence()

        return generateSequence {
            while (true) {
                val next = enumerator.nextObject() as? NSURL ?: return@generateSequence null
                val name = next.lastPathComponent ?: continue
                if (name.endsWith(".json")) {
                    val stripped = name.removeSuffix(".json")
                    return@generateSequence if (decode) stripped.decodeFromPathComponent() else stripped
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }
}
