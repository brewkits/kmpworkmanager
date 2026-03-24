package dev.brewkits.kmpworkmanager.background.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import dev.brewkits.kmpworkmanager.R
import dev.brewkits.kmpworkmanager.KmpWorkManagerKoin
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger

/**
 * A generic CoroutineWorker that delegates to user-provided AndroidWorker implementations.
 *
 * This worker acts as the entry point for all deferrable tasks and:
 * - Retrieves the worker class name from input data
 * - Uses the injected [AndroidWorkerFactory] to create the worker instance
 * - Delegates execution to the worker's doWork() method
 * - Emits events to [dev.brewkits.kmpworkmanager.background.domain.TaskEventBus] for UI updates
 *
 * **Preferred path:** [KmpWorkerFactory] creates this class with [workerFactory] injected
 * directly — no Service Locator. See [KmpWorkerFactory] for setup instructions.
 *
 * **Fallback path:** When WorkManager initializes this class without [KmpWorkerFactory]
 * (e.g. the host app manages WorkManager init without adding KmpWorkerFactory), the
 * 2-arg constructor falls back to [KmpWorkManagerKoin] for backward compatibility.
 */
class KmpWorker : CoroutineWorker {

    private val workerFactory: AndroidWorkerFactory

    /**
     * Preferred constructor — called by [KmpWorkerFactory].
     * Receives [AndroidWorkerFactory] directly without a Service Locator lookup.
     */
    constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        workerFactory: AndroidWorkerFactory
    ) : super(appContext, workerParams) {
        this.workerFactory = workerFactory
    }

    /**
     * Fallback constructor — used by WorkManager's default reflective factory when
     * [KmpWorkerFactory] is not registered. Falls back to [KmpWorkManagerKoin].
     *
     * Prefer registering [KmpWorkerFactory] to eliminate this Koin dependency.
     */
    constructor(
        appContext: Context,
        workerParams: WorkerParameters
    ) : super(appContext, workerParams) {
        this.workerFactory = KmpWorkManagerKoin.getKoin().get()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "kmp_worker_tasks"
        private const val NOTIFICATION_ID = 0x4B4D5000.toInt()

        /**
         * Optional notification title override set by [KmpWorkManagerKoin.initialize] from
         * [dev.brewkits.kmpworkmanager.KmpWorkManagerConfig.androidForegroundNotificationTitle].
         * Takes precedence over the `kmp_worker_notification_title` string resource.
         */
        @Volatile
        internal var configNotificationTitle: String? = null
    }

    /**
     * Required override for WorkManager 2.10.0+.
     *
     * WorkManager 2.10.0+ calls `getForegroundInfoAsync()` in the worker execution path
     * even for non-foreground workers. Without this override, the default CoroutineWorker
     * implementation throws `IllegalStateException: "Not implemented"`.
     *
     * [KmpWorker] does not run as a foreground service — this notification is a fallback
     * shown only if WorkManager explicitly promotes the task to a foreground service
     * (e.g. on low-memory devices or API 31+). It is configured to be as unobtrusive as
     * possible: `PRIORITY_MIN`, silent, and non-ongoing.
     *
     * **Title resolution order (highest priority first):**
     * 1. `KmpWorkManagerConfig.androidForegroundNotificationTitle` — set programmatically at init
     * 2. String resource `kmp_worker_notification_title` — override per locale in
     *    `res/values-xx/strings.xml`
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val title = configNotificationTitle
            ?: applicationContext.getString(R.string.kmp_worker_notification_title)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channelName = applicationContext.getString(R.string.kmp_worker_notification_channel_name)
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        channelName,
                        NotificationManager.IMPORTANCE_MIN
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }

    /**
     * Resolves the worker's input JSON from the [WorkerParameters].
     *
     * Checks for an overflow file path ([NativeTaskScheduler.KEY_INPUT_JSON_FILE]) first.
     * When found, reads the file and deletes it (one-time use), then falls back to the
     * inline `inputJson` key. This transparently handles inputs > 8 KB that were spilled
     * to `cacheDir` at enqueue time to stay within WorkManager's 10 KB `Data` limit.
     */
    private fun resolveInputJson(): String? {
        val overflowPath = inputData.getString(NativeTaskScheduler.KEY_INPUT_JSON_FILE)
        if (overflowPath != null) {
            val file = java.io.File(overflowPath)
            return try {
                if (file.exists()) {
                    val content = file.readText()
                    file.delete()
                    content
                } else {
                    Logger.w(LogTags.WORKER, "Overflow input file missing: $overflowPath")
                    null
                }
            } catch (e: Exception) {
                Logger.e(LogTags.WORKER, "Failed to read overflow input file: $overflowPath", e)
                null
            }
        }
        return inputData.getString("inputJson")
    }

    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()
        val inputJson = resolveInputJson()

        Logger.i(LogTags.WORKER, "KmpWorker executing: $workerClassName")

        return try {
            val worker = workerFactory.createWorker(workerClassName)
                ?: return Result.failure()
            val result = worker.doWork(inputJson)

            when (result) {
                is WorkerResult.Success -> {
                    val message = result.message ?: "Worker completed successfully"
                    Logger.i(LogTags.WORKER, "Worker success: $workerClassName - $message")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = true,
                            message = message,
                            outputData = result.data
                        )
                    )
                    Result.success()
                }
                is WorkerResult.Failure -> {
                    Logger.w(LogTags.WORKER, "Worker failure: $workerClassName - ${result.message}")

                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = workerClassName,
                            success = false,
                            message = result.message,
                            outputData = null
                        )
                    )

                    if (result.shouldRetry) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            // Worker class not registered in WorkerFactory — fail fast and visibly.
            Logger.e(LogTags.WORKER, "Worker not registered: $workerClassName — ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName,
                    success = false,
                    message = "Worker not registered: $workerClassName"
                )
            )
            Result.failure()
        } catch (e: CancellationException) {
            // CancellationException MUST be rethrown — swallowing it breaks the coroutine
            // cancellation protocol and prevents WorkManager from correctly cancelling the task.
            Logger.w(LogTags.WORKER, "Worker cancelled by coroutine scope: $workerClassName")
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "Worker execution failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = workerClassName,
                    success = false,
                    message = "Failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }
}