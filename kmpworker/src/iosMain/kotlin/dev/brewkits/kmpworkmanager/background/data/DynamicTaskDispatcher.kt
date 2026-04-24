@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class
)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.*
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.ObjCObjectVar

/**
 * Internal dispatcher that processes the queue of dynamic tasks on iOS.
 *
 * Since iOS requires static identifiers in Info.plist, this dispatcher handles
 * all tasks that don't have a dedicated identifier. It runs them as a batch
 * under a single static identifier (kmp_master_dispatcher_task).
 */
public class DynamicTaskDispatcher(
    private val singleTaskExecutor: SingleTaskExecutor,
    private val fileStorage: IosFileStorage = IosFileStorage()
) {
    private val isShuttingDown = AtomicInt(0)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    internal companion object {
        // BGProcessingTask gets "several minutes" from iOS — 3 minutes is a conservative
        // proactive soft limit, leaving the OS ample time to call expirationHandler cleanly.
        const val DEFAULT_BUDGET_MS = 3 * 60 * 1000L
    }

    /**
     * Signal to stop processing the queue. Called from the iOS expiration handler.
     */
    fun requestShutdownSync() {
        isShuttingDown.value = 1
        job.cancel()
    }

    /**
     * Resets the shutdown state before starting a new batch.
     */
    fun resetShutdownState() {
        isShuttingDown.value = 0
    }

    /**
     * Processes pending tasks from the internal queue.
     *
     * @param scheduler Required to reschedule periodic tasks after execution.
     * @param budgetMs Soft time budget in milliseconds. Stops before starting a new task if the
     *   remaining budget cannot cover [SingleTaskExecutor.DEFAULT_TIMEOUT_MS] plus a 5-second
     *   safety margin. The hard stop remains iOS calling [requestShutdownSync] on expiration.
     * @return Number of tasks processed in this batch.
     */
    suspend fun executePendingTasks(
        scheduler: BackgroundTaskScheduler,
        budgetMs: Long = DEFAULT_BUDGET_MS
    ): Int {
        var processedCount = 0
        val batchStartMs = currentTimeMs()

        // Snapshot the current queue depth before the loop.
        // Without this bound, periodic tasks re-enqueue themselves (via reschedulePeriodicTask)
        // and the while loop picks them up immediately — creating an infinite execution loop
        // within the same BGTask invocation until SIGKILL.
        val tasksToProcess = fileStorage.getTasksQueueSize()
        var remaining = tasksToProcess

        while (isShuttingDown.value == 0 && remaining > 0) {
            // Proactive budget guard: abort before starting a task we cannot finish in time.
            // Reserves DEFAULT_TIMEOUT_MS + 5s so a max-duration worker doesn't overrun
            // the budget and risk iOS calling the expiration handler mid-execution.
            val budgetLeft = budgetMs - (currentTimeMs() - batchStartMs)
            if (budgetLeft < SingleTaskExecutor.DEFAULT_TIMEOUT_MS + 5_000L) {
                Logger.w(LogTags.SCHEDULER,
                    "DynamicTaskDispatcher: budget almost exhausted (${budgetLeft}ms left), " +
                    "deferring $remaining remaining task(s) to next invocation")
                break
            }

            val taskId = fileStorage.dequeueTask() ?: break
            remaining--

            val meta = IosBackgroundTaskHandler.resolveTaskMetadata(taskId, fileStorage)
            if (meta == null) {
                Logger.e(LogTags.SCHEDULER, "No metadata found for dynamic task '$taskId' - skipping")
                continue
            }

            Logger.i(LogTags.SCHEDULER, "DynamicTaskDispatcher: Executing '$taskId'")

            try {
                val result = singleTaskExecutor.executeTask(meta.workerClassName, meta.inputJson)
                val success = result is WorkerResult.Success

                if (meta.isPeriodic) {
                    IosBackgroundTaskHandler.reschedulePeriodicTask(
                        taskId = taskId,
                        workerClassName = meta.workerClassName,
                        inputJson = meta.inputJson,
                        rawMeta = meta.rawMeta,
                        scheduler = scheduler
                    )
                }

                Logger.i(LogTags.SCHEDULER, "Dynamic task '$taskId' finished (success=$success)")
                processedCount++
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "Dynamic task '$taskId' threw exception", e)
            }
        }

        // If there are still tasks in the queue and we didn't shut down,
        // reschedule the master dispatcher to continue later.
        if (isShuttingDown.value == 0) {
            val remainingInQueue = fileStorage.getTasksQueueSize()
            if (remainingInQueue > 0) {
                Logger.i(LogTags.SCHEDULER, "$remainingInQueue dynamic task(s) remaining - rescheduling master dispatcher")
                rescheduleMasterDispatcher()
            }
        }

        return processedCount
    }

    private fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun rescheduleMasterDispatcher() {
        if (NSBundle.mainBundle.bundleIdentifier == null) return

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val request = BGProcessingTaskRequest("kmp_master_dispatcher_task")
            request.earliestBeginDate = NSDate()
            // Individual task network constraints are checked by each worker.
            // false here allows non-network tasks to run opportunistically even without
            // connectivity; workers that need network return Failure and remain in the queue.
            request.requiresNetworkConnectivity = false

            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)
            if (!ok) {
                val err = errorPtr.value
                Logger.e(LogTags.SCHEDULER, "Failed to reschedule master dispatcher: ${err?.localizedDescription}")
            }
        }
    }
}
