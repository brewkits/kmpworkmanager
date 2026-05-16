@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class
)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.IosBackgroundDownloadConfig
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Singleton that owns one or more background `NSURLSession`s so downloads survive app
 * termination on iOS.
 *
 * **Lifecycle**
 *
 * 1. Host AppDelegate calls [registerSession] (or it's auto-registered the first time
 *    a worker is enqueued with a given `sessionIdentifier`).
 * 2. Worker calls [enqueueDownload], which (a) **persists** `(sessionId, taskId,
 *    savePath, workerName)` to [BackgroundDownloadStateStore] *before* `task.resume()`,
 *    and (b) submits a `URLSessionDownloadTask` to the system daemon. The library is
 *    NOT keeping the worker alive in memory — the OS will relaunch the app when the
 *    download finishes.
 * 3. App may be force-quit or evicted by the OS for RAM. The download keeps running
 *    inside `nsurlsessiond` because we used `URLSessionConfiguration.background`.
 * 4. On completion (success or failure), iOS cold-launches the app and reconnects to
 *    the saved session. Delegate callbacks fire — the **synchronous** lookup in
 *    [BackgroundDownloadStateStore.getSync] retrieves the `savePath`/`workerName` from
 *    disk (the in-memory map of the dead process is gone), the temp file is moved to
 *    `savePath` **before the delegate returns** (iOS deletes the temp file on return),
 *    and a regular [TaskCompletionEvent] is emitted.
 * 5. Host AppDelegate calls [handleBackgroundEvents] from
 *    `application(_:handleEventsForBackgroundURLSession:completionHandler:)` so iOS
 *    knows we're done processing events and can release the relaunch budget.
 *
 * **v2.5.0 fix — kill-then-relaunch survival** (issue raised in QA review). Prior to
 * v2.5.0 the manager kept `savePaths` and `taskNames` only in `MutableMap`s in process
 * memory. App kill + cold-launch wiped them, so the delegate had nothing to look up
 * and orphaned the downloaded file in `NSTemporaryDirectory` while never emitting
 * a completion event. v2.5.0 backs the maps with a JSON file in Application Support.
 *
 * **Why this is "experimental"** in v2.5: the worker's `WorkerResult` cannot be awaited
 * synchronously (the daemon is the one running the download, not us). Callers must
 * subscribe to [TaskEventManager] to learn the outcome. Real-world camera apps already
 * use this pattern; documenting it explicitly is the v2.5 step.
 */
object IosBackgroundUrlSessionManager {

    internal val sessionsMutex = Mutex()
    internal val sessions: MutableMap<String, NSURLSession> = mutableMapOf()
    internal val completionHandlers: MutableMap<String, () -> Unit> = mutableMapOf()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Maximum age of a persisted [BackgroundDownloadStateStore.Entry] before
     * [sweepStaleStateOnLaunch] discards it. 7 days covers the longest realistic
     * background-download window iOS will keep alive (typical limit is hours, not
     * days, but the buffer accounts for users who turn off their device for a
     * week). Tune via your host app if you need shorter retention.
     */
    private const val MAX_ENTRY_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /**
     * Register a background session for [sessionIdentifier]. Idempotent — calling twice
     * returns the existing session.
     *
     * Safe to call from any thread; the underlying `URLSession` is thread-safe and the
     * mutex protects the cache map.
     */
    suspend fun registerSession(sessionIdentifier: String): NSURLSession = sessionsMutex.withLock {
        sessions.getOrPut(sessionIdentifier) {
            val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(sessionIdentifier)
            config.setSessionSendsLaunchEvents(true)
            // Default: opportunistic — host can override via `isDiscretionary` per download.
            config.setDiscretionary(false)
            NSURLSession.sessionWithConfiguration(
                configuration = config,
                delegate = IosBackgroundUrlSessionManagerDelegate(),
                delegateQueue = NSOperationQueue()
            )
        }
    }

    /**
     * Sweep persisted state entries older than [MAX_ENTRY_AGE_MS]. Call from host
     * `AppDelegate.application(_:didFinishLaunchingWithOptions:)` to clean up entries
     * for downloads that iOS gave up on (cancellation, prolonged retry exhaustion,
     * device unreachable for a week, etc.) so the state file does not grow unbounded.
     *
     * Safe to call concurrently with [enqueueDownload] — the store's internal mutex
     * serialises access.
     */
    suspend fun sweepStaleStateOnLaunch() {
        val nowMs = (NSDate().timeIntervalSince1970() * 1000.0).toLong()
        BackgroundDownloadStateStore.sweepStale(MAX_ENTRY_AGE_MS, nowMs)
    }

    /**
     * Submit a download to the background daemon. Returns immediately after the daemon
     * acknowledges the request — the actual download runs asynchronously and outlives
     * the calling process. Listen on [TaskEventManager] for completion.
     *
     * **Critical contract** — the persistence write must complete **before** `task.resume()`.
     * If the order were reversed and the app crashed between `resume()` and the persistence
     * write, the daemon would already own the download but we'd have no record of where to
     * save it on completion. The mutex inside the store ensures the write is durable before
     * we hand off to the OS.
     */
    suspend fun enqueueDownload(
        workerName: String,
        config: IosBackgroundDownloadConfig
    ): NSURLSessionDownloadTask {
        val session = registerSession(config.sessionIdentifier)
        val request = NSMutableURLRequest.requestWithURL(NSURL(string = config.url))
        config.headers?.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
        request.setAllowsCellularAccess(config.allowsCellularAccess)
        request.setTimeoutInterval((config.timeoutMs / 1000.0))
        val task = session.downloadTaskWithRequest(request)
        val taskId = task.taskIdentifier.toLong()

        // Persist BEFORE resume — see KDoc Critical contract.
        BackgroundDownloadStateStore.put(
            BackgroundDownloadStateStore.Entry(
                sessionIdentifier = config.sessionIdentifier,
                taskIdentifier = taskId,
                savePath = config.savePath,
                workerName = workerName,
                createdAtMs = (NSDate().timeIntervalSince1970() * 1000.0).toLong(),
            )
        )

        task.resume()
        Logger.i(
            LogTags.WORKER,
            "Background URLSession download queued: id=$taskId url=${config.url} session=${config.sessionIdentifier}"
        )
        return task
    }

    /**
     * Call from `AppDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
     *
     * The library captures [completionHandler] and invokes it once iOS reports that all
     * pending events for the session have been delivered (via the
     * `URLSessionDidFinishEventsForBackgroundURLSession` delegate callback).
     */
    fun handleBackgroundEvents(sessionIdentifier: String, completionHandler: () -> Unit) {
        scope.launch {
            sessionsMutex.withLock {
                completionHandlers[sessionIdentifier] = completionHandler
            }
            // Side effect: ensure the session exists so the delegate callbacks fire.
            registerSession(sessionIdentifier)
        }
    }
}

/**
 * Delegate is a separate top-level class (not a nested `object`) so each
 * `NSURLSession` gets its own instance — required by Foundation. Delegate methods are
 * not suspending; iOS deletes the temporary download file the moment a delegate
 * returns, so [didFinishDownloadingToURL] must do the file move synchronously before
 * returning. We use [BackgroundDownloadStateStore.getSync] for the lookup; cleanup
 * writes (which can suspend) run on a background scope after the move is done.
 */
internal class IosBackgroundUrlSessionManagerDelegate : NSObject(), NSURLSessionDownloadDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL
    ) {
        val id = downloadTask.taskIdentifier.toLong()
        val sessionId = session.configuration.identifier
        if (sessionId == null) {
            Logger.w(LogTags.WORKER, "Background download completed but session has no identifier (id=$id)")
            return
        }
        // Synchronous disk read — see BackgroundDownloadStateStore.getSync KDoc for why
        // this is safe (atomic writes + serial delegate callbacks).
        val entry = BackgroundDownloadStateStore.getSync(sessionId, id)
        if (entry == null) {
            Logger.w(
                LogTags.WORKER,
                "Background download completed but no persisted state for sessionId=$sessionId id=$id. " +
                    "The file at ${didFinishDownloadingToURL.path} will be orphaned. This usually means " +
                    "the state file was wiped (e.g. user reinstalled the app while the download was in flight)."
            )
            return
        }

        val savePath = entry.savePath
        val workerName = entry.workerName
        try {
            val fm = NSFileManager.defaultManager
            val destURL = NSURL.fileURLWithPath(savePath)
            // Atomic-ish move: remove any existing file at destination first.
            if (fm.fileExistsAtPath(savePath)) {
                fm.removeItemAtPath(savePath, error = null)
            }
            val moved = fm.moveItemAtURL(didFinishDownloadingToURL, toURL = destURL, error = null)
            if (moved) {
                emitCompletion(workerName, success = true, message = "Background download saved to $savePath")
            } else {
                emitCompletion(workerName, success = false, message = "moveItemAtURL failed for id=$id")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Background download finalize failed for id=$id", e)
            emitCompletion(workerName, success = false, message = "Finalize failed: ${e.message}")
        } finally {
            // Async cleanup — safe to remove now because the file move (success or
            // failure) is already done. Suspending here is fine since iOS has all the
            // data it needs and only cares about us returning quickly.
            IosBackgroundUrlSessionManager.scope.launch {
                BackgroundDownloadStateStore.remove(sessionId, id)
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        val id = task.taskIdentifier.toLong()
        val sessionId = session.configuration.identifier ?: return
        val err = didCompleteWithError
        // `didFinishDownloadingToURL` already emitted the success event AND already
        // removed the state entry; we only surface explicit failures here.
        if (err != null) {
            val workerName = BackgroundDownloadStateStore.getSync(sessionId, id)?.workerName
                ?: "IosBackgroundDownloadWorker"
            Logger.e(
                LogTags.WORKER,
                "Background download failed: id=$id error=${err.localizedDescription}"
            )
            emitCompletion(workerName, success = false, message = err.localizedDescription)
            // Failure path: clean up state ourselves since didFinishDownloadingToURL won't fire.
            IosBackgroundUrlSessionManager.scope.launch {
                BackgroundDownloadStateStore.remove(sessionId, id)
            }
        }
    }

    @ObjCAction
    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        // Find the matching completion handler stored from `handleBackgroundEvents`
        // and invoke on the main queue (UIKit requirement).
        val ident = session.configuration.identifier ?: return
        IosBackgroundUrlSessionManager.scope.launch {
            val handler = IosBackgroundUrlSessionManager.sessionsMutex.withLock {
                IosBackgroundUrlSessionManager.completionHandlers.remove(ident)
            }
            if (handler != null) {
                platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                    handler.invoke()
                }
            }
        }
    }

    private fun emitCompletion(workerName: String, success: Boolean, message: String) {
        IosBackgroundUrlSessionManager.scope.launch {
            TaskEventManager.emit(
                TaskCompletionEvent(
                    taskName = workerName,
                    success = success,
                    message = message,
                    outputData = null
                )
            )
        }
    }
}
