@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.IosBackgroundDownloadConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator

/**
 * iOS-only worker that hands the download off to a background `NSURLSession` via
 * [IosBackgroundUrlSessionManager].
 *
 * **Semantics**
 * - The worker submits the download and returns `Success` immediately (or `Failure`
 *   if submission is rejected). The actual transfer runs in the system daemon and
 *   completion is reported via [dev.brewkits.kmpworkmanager.background.domain.TaskEventManager]
 *   later — possibly after the app is force-killed and relaunched.
 * - Callers that need to *wait* on completion should subscribe to `TaskEventBus` and
 *   filter on `taskName == "IosBackgroundDownloadWorker"`.
 *
 * **Why this is different from [HttpDownloadWorker]**
 *
 * `HttpDownloadWorker` runs inside the chain executor's coroutine — bounded by the
 * BGTaskScheduler budget (~30s). `IosBackgroundDownloadWorker` instead delegates to the
 * iOS background URL session daemon, which can run for hours, survives full app
 * termination, and is automatically resumed on network change. Use this when:
 *
 * - File size is large (>10 MB).
 * - The download must complete even if the user force-quits the app.
 * - The user is on a flaky network (the daemon handles reconnects).
 *
 * Use [HttpDownloadWorker] for small / immediate downloads where chain step semantics
 * matter (next step depends on this file being on disk before continuing).
 *
 * **Host integration** required — see `docs/IOS_BACKGROUND_URL_SESSION.md`.
 */
class IosBackgroundDownloadWorker : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        val config = try {
            KmpWorkManagerRuntime.json.decodeFromString<IosBackgroundDownloadConfig>(input)
        } catch (e: kotlinx.serialization.SerializationException) {
            return WorkerResult.Failure("Invalid IosBackgroundDownloadConfig JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WorkerResult.Failure("Invalid IosBackgroundDownloadConfig: ${e.message}")
        }

        if (!SecurityValidator.validateURL(config.url)) {
            return WorkerResult.Failure("Invalid or unsafe URL")
        }
        if (!SecurityValidator.validateFilePath(config.savePath)) {
            return WorkerResult.Failure("Invalid or unsafe save path")
        }

        return try {
            val task = IosBackgroundUrlSessionManager.enqueueDownload(
                workerName = "IosBackgroundDownloadWorker",
                config = config
            )
            Logger.i(
                "IosBackgroundDownloadWorker",
                "Submitted background download (id=${task.taskIdentifier}) — completion is asynchronous, " +
                    "listen on TaskEventBus for the result."
            )
            // The worker's "success" here means "the OS accepted the task", not
            // "the file is on disk". The chain executor moves on; later, when the
            // daemon completes the download, IosBackgroundUrlSessionManager emits
            // a TaskCompletionEvent.
            WorkerResult.Success(
                message = "Background download queued (taskId=${task.taskIdentifier}). " +
                    "Completion reported via TaskEventBus."
            )
        } catch (e: Exception) {
            Logger.e("IosBackgroundDownloadWorker", "Failed to submit background download", e)
            WorkerResult.Retry(
                reason = "submit failed: ${e.message ?: e::class.simpleName ?: "unknown"}",
                delayMs = 10_000L
            )
        }
    }
}
