@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * Disk-backed map of in-flight background `NSURLSession` downloads.
 *
 * **Why this exists** — v2.5.0 QA review caught a critical bug:
 * `IosBackgroundUrlSessionManager` kept `savePaths` and `taskNames` only in
 * memory (`MutableMap<Long, String>`). The whole point of
 * `URLSessionConfiguration.background` is that the download survives the app
 * being killed; iOS then cold-launches the app to deliver the completion
 * callback. The in-memory maps are empty after cold-launch, so the delegate
 * had no way to find the `savePath`/`workerName` and the downloaded file was
 * silently orphaned in NSTemporaryDirectory.
 *
 * This store persists the mapping to a small JSON file in Application Support
 * so the data survives every cold-launch scenario. Specifically:
 *
 *  1. User starts an upload → `IosBackgroundUrlSessionManager.enqueueDownload`
 *     adds entry to this store + tells `nsurlsessiond` to start.
 *  2. User force-quits or OS evicts the process for RAM.
 *  3. Download finishes ~10 minutes later; iOS cold-launches the app.
 *  4. `AppDelegate.application(_:handleEventsForBackgroundURLSession:_:)` is
 *     called; library re-registers the session; delegate callback fires.
 *  5. Delegate looks up entry by `(sessionIdentifier, taskIdentifier)` — finds
 *     it on disk, moves the file to `savePath`, emits `TaskCompletionEvent`.
 *  6. Entry removed from store.
 *
 * ### Key design
 *
 * Key is `(sessionIdentifier, taskIdentifier)` because iOS allocates task IDs
 * per-session and they can collide across sessions. The serialised key is
 * `"sessionId:taskId"` — `sessionIdentifier` is a developer-chosen string that
 * must not contain `:`. We don't validate (cheap & rare), we just warn.
 *
 * ### Concurrency
 *
 * Writes are mutex-protected. Reads from disk are also protected so a read
 * mid-write doesn't observe a half-written file. The cost is a few ms per
 * delegate call, which is fine — delegate calls are infrequent (one per
 * download completion).
 *
 * ### Atomic writes
 *
 * `writeToURL(atomically = true)` uses Foundation's atomic-write primitive
 * (write to temp, then rename). Power loss mid-write leaves the previous
 * file intact rather than a half-written one.
 *
 * ### Schema evolution
 *
 * `Json { ignoreUnknownKeys = true }` so a future v2.6 can add fields
 * without breaking v2.5 readers. `Entry` is a flat data class — keep it that
 * way; nested objects make migration harder. Add `schemaVersion: Int = 1` if
 * a breaking change ever happens.
 */
internal object BackgroundDownloadStateStore {

    @Serializable
    internal data class Entry(
        val sessionIdentifier: String,
        val taskIdentifier: Long,
        val savePath: String,
        val workerName: String,
        val createdAtMs: Long,
    ) {
        /** Stable composite key — see class KDoc. */
        val key: String get() = "$sessionIdentifier:$taskIdentifier"
    }

    @Serializable
    private data class StateFile(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val entries: Map<String, Entry> = emptyMap(),
    )

    /** Bump on breaking change; additive changes don't need a bump. */
    private const val CURRENT_SCHEMA_VERSION = 1
    private const val STATE_FILENAME = "bg_url_session_state.json"
    private const val BASE_DIR_NAME = "dev.brewkits.kmpworkmanager"

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // In-memory cache mirroring the on-disk state. Populated on first read; kept in sync
    // by every write. The cache exists because the NSURLSession delegate methods are NOT
    // suspend functions, but iOS deletes the temporary download file the moment the
    // delegate returns — we have no time to suspend on a mutex. Reads via [getSync] hit
    // the cache; if the cache is empty (cold-launch), we synchronously load from disk
    // once and then serve from cache for the rest of the process lifetime.
    //
    // Concurrency model: reads from delegate threads (URLSession's NSOperationQueue) are
    // serialised by iOS — delegate methods fire one at a time per session. Writes from
    // suspending callers acquire [mutex]. The cache is a plain MutableMap protected by a
    // platform monitor (synchronized block) rather than the coroutine Mutex so the
    // delegate can read it without suspending.
    @kotlin.concurrent.Volatile
    private var cache: Map<String, Entry>? = null

    private val stateFileURL: NSURL by lazy {
        val fm = NSFileManager.defaultManager
        val urls = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask) as List<*>
        val appSupport = urls.firstOrNull() as? NSURL
            ?: error("Application Support directory unavailable")
        val baseDir = appSupport.URLByAppendingPathComponent(BASE_DIR_NAME)
            ?: error("Cannot build base directory URL")
        fm.createDirectoryAtURL(baseDir, withIntermediateDirectories = true, attributes = null, error = null)
        baseDir.URLByAppendingPathComponent(STATE_FILENAME) ?: error("Cannot build state file URL")
    }

    /**
     * Persist a new entry. Idempotent: a second `put` with the same `(session,
     * task)` key overwrites the previous value — which is the right behaviour
     * if a caller re-enqueues the same task identifier (rare but possible).
     */
    suspend fun put(entry: Entry) = mutex.withLock {
        val state = readUnlocked()
        val updated = state.copy(entries = state.entries + (entry.key to entry))
        writeUnlocked(updated)
        cache = updated.entries
    }

    /**
     * Look up by composite key. Returns null if no entry was recorded (e.g.
     * if the state file was wiped by user "Reset Settings", or if a stranger
     * task ID arrives that we never persisted).
     */
    suspend fun get(sessionIdentifier: String, taskIdentifier: Long): Entry? = mutex.withLock {
        ensureCacheLoaded()
        cache?.get("$sessionIdentifier:$taskIdentifier")
    }

    /**
     * Synchronous lookup for use from `NSURLSessionDelegate` callbacks, which are not
     * suspending functions and must complete before returning to iOS (the temp file iOS
     * provides in `didFinishDownloadingToURL` is deleted as soon as the delegate returns).
     *
     * On the first call after process launch (cold-launch path), this reads the JSON file
     * from disk into the cache; subsequent calls are pure in-memory map lookups. The disk
     * read itself is short (<1 ms for typical sub-KB state files) and atomic file
     * writes guarantee we never observe a half-written file.
     */
    fun getSync(sessionIdentifier: String, taskIdentifier: Long): Entry? {
        // Reading [cache] via @Volatile gives us happens-before relative to any prior
        // suspend put/remove that did `cache = …` after writing to disk. On cold-launch,
        // [cache] is null until the first delegate callback or first suspend get(); the
        // call below loads from disk and publishes via the volatile write.
        //
        // We don't synchronize because K/N has no `synchronized` keyword and the worst
        // case (two delegate threads both observing null and both loading from disk) is
        // benign — they read the same file and end up with the same Map. iOS in practice
        // serialises delegate callbacks per session, so concurrent loads are unlikely.
        val snapshot = cache ?: readUnlocked().entries.also { cache = it }
        return snapshot["$sessionIdentifier:$taskIdentifier"]
    }

    /**
     * Remove an entry after the delegate has finished processing the
     * completion. Safe to call when the key is absent (no-op).
     */
    suspend fun remove(sessionIdentifier: String, taskIdentifier: Long) = mutex.withLock {
        val state = readUnlocked()
        val key = "$sessionIdentifier:$taskIdentifier"
        if (key !in state.entries) return@withLock
        val updated = state.copy(entries = state.entries - key)
        writeUnlocked(updated)
        cache = updated.entries
    }

    /**
     * Sweep entries older than [maxAgeMs]. Call from cold-launch path so
     * abandoned entries (user cancelled the download by uninstalling +
     * reinstalling the app, or the file was deleted out from under us)
     * don't accumulate forever.
     */
    suspend fun sweepStale(maxAgeMs: Long, nowMs: Long) = mutex.withLock {
        val state = readUnlocked()
        val cutoff = nowMs - maxAgeMs
        val survivors = state.entries.filter { it.value.createdAtMs >= cutoff }
        if (survivors.size == state.entries.size) return@withLock
        Logger.i(
            LogTags.WORKER,
            "BackgroundDownloadStateStore: swept ${state.entries.size - survivors.size} stale entries " +
                "(older than ${maxAgeMs}ms)"
        )
        val updated = state.copy(entries = survivors)
        writeUnlocked(updated)
        cache = updated.entries
    }

    /**
     * Test-only: clear the store. Production callers should use [remove] for
     * specific entries; truncating wholesale would lose in-flight downloads.
     */
    suspend fun clearForTest() = mutex.withLock {
        writeUnlocked(StateFile())
        cache = emptyMap()
    }

    /**
     * Test-only: simulate the cold-launch scenario by dropping the in-memory cache.
     * The next [getSync] call must re-read the JSON file from disk. Production code
     * should never call this — the cache is automatically invalidated on every write.
     */
    fun invalidateInMemoryCacheForTest() {
        cache = null
    }

    private fun ensureCacheLoaded() {
        if (cache == null) {
            cache = readUnlocked().entries
        }
    }

    // ── internals (must be called under [mutex]) ──────────────────────────

    private fun readUnlocked(): StateFile {
        val fm = NSFileManager.defaultManager
        val path = stateFileURL.path ?: return StateFile()
        if (!fm.fileExistsAtPath(path)) return StateFile()
        return try {
            val raw = NSString.stringWithContentsOfURL(
                stateFileURL,
                encoding = NSUTF8StringEncoding,
                error = null,
            ) ?: return StateFile()
            val parsed: StateFile = json.decodeFromString(raw as String)
            if (parsed.schemaVersion > CURRENT_SCHEMA_VERSION) {
                Logger.w(
                    LogTags.WORKER,
                    "BackgroundDownloadStateStore: file schemaVersion=${parsed.schemaVersion} > " +
                        "current=$CURRENT_SCHEMA_VERSION — running on a downgrade. Reading entries " +
                        "anyway via ignoreUnknownKeys=true; unknown fields are dropped."
                )
            }
            parsed
        } catch (e: Exception) {
            // Self-heal: a corrupt file (truncated write, disk error) should not block all
            // future downloads. Surface as warning, reset to empty. In-flight downloads
            // whose entry was in the corrupt file will be orphaned — that's the price of
            // self-healing; logging makes it observable.
            Logger.w(
                LogTags.WORKER,
                "BackgroundDownloadStateStore: corrupt state file — resetting to empty. " +
                    "Any in-flight downloads will not be reattached. Error: ${e.message}"
            )
            StateFile()
        }
    }

    private fun writeUnlocked(state: StateFile) {
        val encoded = json.encodeToString(StateFile.serializer(), state)
        val ns = NSString.create(string = encoded)
        val data = ns.dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Could not encode state file as UTF-8")
        // writeToURL with atomically=true writes via a temp file + rename, which is what
        // we want — power loss mid-write keeps the previous version.
        val ok = data.writeToURL(stateFileURL, atomically = true)
        if (!ok) {
            Logger.e(
                LogTags.WORKER,
                "BackgroundDownloadStateStore: writeToURL returned false — state not persisted. " +
                    "Subsequent cold-launch completions may orphan their downloaded files."
            )
        }
    }
}
