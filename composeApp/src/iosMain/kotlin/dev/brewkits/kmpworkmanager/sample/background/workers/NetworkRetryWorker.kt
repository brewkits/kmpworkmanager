package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates exponential backoff retry logic for flaky network operations
 */
class NetworkRetryWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "NetworkRetryWorker started - demonstrating retry with exponential backoff")

        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            Logger.i(LogTags.WORKER, "Network request attempt $attempt/$maxAttempts")

            try {
                delay(1.seconds) // Simulate network request

                // Simulate flaky network: fail first 2 attempts, succeed on 3rd
                if (attempt < 3) {
                    throw Exception("Network timeout (simulated)")
                }

                Logger.i(LogTags.WORKER, "NetworkRetryWorker succeeded on attempt $attempt")

                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = "NetworkRetry",
                        success = true,
                        message = "🌐 Network request succeeded on attempt $attempt"
                    )
                )

                return true
            } catch (e: Exception) {
                Logger.w(LogTags.WORKER, "Attempt $attempt failed: ${e.message}")

                if (attempt < maxAttempts) {
                    // Exponential backoff: 2s, 4s, 8s
                    val backoffDelay = (2.seconds * (1 shl (attempt - 1)))
                    Logger.i(LogTags.WORKER, "Retrying in ${backoffDelay}...")
                    delay(backoffDelay)
                } else {
                    Logger.e(LogTags.WORKER, "NetworkRetryWorker failed after $maxAttempts attempts", e)
                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = "NetworkRetry",
                            success = false,
                            message = "❌ Network request failed after $maxAttempts attempts"
                        )
                    )
                    return false
                }
            }
        }

        return false
    }
}
