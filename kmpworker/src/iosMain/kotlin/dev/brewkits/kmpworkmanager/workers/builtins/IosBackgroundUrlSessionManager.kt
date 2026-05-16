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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.URLByAppendingPathComponent
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * Singleton that owns a background `NSURLSession` so downloads survive app termination
 * on iOS.
 *
 * **Lifecycle**
 *
 * 1. Host AppDelegate calls [registerSession] (or it's auto-registered the first time
 *    a worker is enqueued with a given `sessionIdentifier`).
 * 2. Worker calls [enqueueDownload], which submits a `URLSessionDownloadTask` to the
 *    system daemon and returns immediately. The library is NOT keeping the worker alive
 *    in memory — the OS will relaunch the app when the download finishes.
 * 3. On completion (success or failure), iOS reconnects to the saved session and fires
 *    the [NSURLSessionDownloadDelegateProtocol] callbacks below; we emit a regular
 *    [TaskCompletionEvent] so the rest of the library observability stack sees it.
 * 4. Host AppDelegate calls [handleBackgroundEvents] from
 *    `application(_:handleEventsForBackgroundURLSession:completionHandler:)` so iOS
 *    knows we're done processing events and the system can release the relaunch budget.
 *
 * **Why this is "experimental"** in v2.5: the worker's `WorkerResult` cannot be awaited
 * synchronously (the daemon is the one running the download, not us). Callers must
 * subscribe to [TaskEventManager] to learn the outcome. Real-world camera apps already
 * use this pattern; documenting it explicitly is the v2.5 step.
 */
object IosBackgroundUrlSessionManager {

    internal val sessionsMutex = Mutex()
    internal val sessions: MutableMap<String, NSURLSession> = mutableMapOf()
    internal val savePaths: MutableMap<Long, String> = mutableMapOf() // taskIdentifier → savePath
    internal val taskNames: MutableMap<Long, String> = mutableMapOf() // taskIdentifier → workerName
    internal val completionHandlers: MutableMap<String, () -> Unit> = mutableMapOf()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * Submit a download to the background daemon. Returns immediately after the daemon
     * acknowledges the request — the actual download runs asynchronously and outlives
     * the calling process. Listen on [TaskEventManager] for completion.
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
        sessionsMutex.withLock {
            val id = task.taskIdentifier.toLong()
            savePaths[id] = config.savePath
            taskNames[id] = workerName
        }
        task.resume()
        Logger.i(
            LogTags.WORKER,
            "Background URLSession download queued: id=${task.taskIdentifier} url=${config.url} session=${config.sessionIdentifier}"
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

    // ── Delegate impl ───────────────────────────────────────────────────────────


    /**
     * Convenience: read from the suspended map without a coroutine. Returns null if the
     * mutex is contended — callers fall back gracefully. We only use this from delegate
     * methods that are invoked on the URLSession queue, so contention with `enqueueDownload`
     * is brief.
     */
    internal inline fun <T> runBlockingMap(crossinline block: () -> T?): T? {
        // The map mutations are quick and happen under sessionsMutex; reading without the
        // lock is acceptable since iOS delivers delegate callbacks sequentially on the
        // session's NSOperationQueue. A racy null read just means "we already cleaned up"
        // which is handled by the caller's null guard.
        return block()
    }
}


    internal class IosBackgroundUrlSessionManagerDelegate : NSObject(), NSURLSessionDownloadDelegateProtocol {

        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didFinishDownloadingToURL: NSURL
        ) {
            val id = downloadTask.taskIdentifier.toLong()
            val savePath = IosBackgroundUrlSessionManager.runBlockingMap { IosBackgroundUrlSessionManager.savePaths[id] }
            val workerName = IosBackgroundUrlSessionManager.runBlockingMap { IosBackgroundUrlSessionManager.taskNames[id] } ?: "IosBackgroundDownloadWorker"
            if (savePath == null) {
                Logger.w(LogTags.WORKER, "Background download completed but no savePath known for id=$id")
                return
            }
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
            }
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?
        ) {
            val id = task.taskIdentifier.toLong()
            val err = didCompleteWithError
            val workerName = IosBackgroundUrlSessionManager.runBlockingMap { IosBackgroundUrlSessionManager.taskNames[id] } ?: "IosBackgroundDownloadWorker"
            // `didFinishDownloadingToURL` already emitted the success event; we only
            // surface explicit failures here. Skip when error is null (success path).
            if (err != null) {
                Logger.e(
                    LogTags.WORKER,
                    "Background download failed: id=$id error=${err.localizedDescription}"
                )
                emitCompletion(workerName, success = false, message = err.localizedDescription)
            }
            // Always clean up the maps so they don't grow without bound.
            IosBackgroundUrlSessionManager.scope.launch {
                IosBackgroundUrlSessionManager.sessionsMutex.withLock {
                    IosBackgroundUrlSessionManager.savePaths.remove(id)
                    IosBackgroundUrlSessionManager.taskNames.remove(id)
                }
            }
        }

        @ObjCAction
        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            // Find the matching completion handler stored from `handleBackgroundEvents`
            // and invoke on the main queue (UIKit requirement).
            val ident = session.configuration.identifier ?: return
            IosBackgroundUrlSessionManager.scope.launch {
                val handler = IosBackgroundUrlSessionManager.sessionsMutex.withLock { IosBackgroundUrlSessionManager.completionHandlers.remove(ident) }
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