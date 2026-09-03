@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.workers.builtins

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.workers.config.IosBackgroundUploadConfig
import dev.brewkits.kmpworkmanager.workers.utils.SecurityValidator
import platform.Foundation.NSFileManager

/**
 * iOS-only worker that hands the upload off to a background `NSURLSession` via
 * [IosBackgroundUrlSessionManager]. The upload counterpart of [IosBackgroundDownloadWorker] —
 * same semantics, same host-integration requirement. See that class's KDoc for the shared
 * background-URLSession lifecycle explanation.
 *
 * **Semantics**
 * - The worker submits the upload and returns `Success` immediately (or `Failure` if
 *   submission is rejected). The actual transfer runs in the system daemon; completion is
 *   reported via [dev.brewkits.kmpworkmanager.background.domain.TaskEventManager] later —
 *   possibly after the app is force-killed and relaunched.
 * - Callers that need to *wait* on completion should subscribe to `TaskEventBus` and filter
 *   on `taskName == "IosBackgroundUploadWorker"`.
 *
 * **Host integration** required — see `docs/IOS_BACKGROUND_URL_SESSION.md`.
 */
class IosBackgroundUploadWorker : Worker {

    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        if (input == null) return WorkerResult.Failure("Input is null")

        val config = try {
            KmpWorkManagerRuntime.json.decodeFromString<IosBackgroundUploadConfig>(input)
        } catch (e: kotlinx.serialization.SerializationException) {
            return WorkerResult.Failure("Invalid IosBackgroundUploadConfig JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WorkerResult.Failure("Invalid IosBackgroundUploadConfig: ${e.message}")
        }

        if (!SecurityValidator.validateURL(config.url)) {
            return WorkerResult.Failure("Invalid or unsafe URL")
        }
        if (!SecurityValidator.validateFilePath(config.filePath)) {
            return WorkerResult.Failure("Invalid or unsafe file path")
        }
        if (!NSFileManager.defaultManager.fileExistsAtPath(config.filePath)) {
            return WorkerResult.Failure("File does not exist at path: ${config.filePath}")
        }

        return try {
            val task = IosBackgroundUrlSessionManager.enqueueUpload(
                workerName = "IosBackgroundUploadWorker",
                config = config
            )
            Logger.i(
                "IosBackgroundUploadWorker",
                "Submitted background upload (id=${task.taskIdentifier}) — completion is asynchronous, " +
                    "listen on TaskEventBus for the result."
            )
            // The worker's "success" here means "the OS accepted the task", not "the upload
            // finished". The chain executor moves on; later, when the daemon completes the
            // upload, IosBackgroundUrlSessionManager emits a TaskCompletionEvent.
            WorkerResult.Success(
                message = "Background upload queued (taskId=${task.taskIdentifier}). " +
                    "Completion reported via TaskEventBus."
            )
        } catch (e: Exception) {
            Logger.e("IosBackgroundUploadWorker", "Failed to submit background upload", e)
            WorkerResult.Retry(
                reason = "submit failed: ${e.message ?: e::class.simpleName ?: "unknown"}",
                delayMs = 10_000L
            )
        }
    }
}
