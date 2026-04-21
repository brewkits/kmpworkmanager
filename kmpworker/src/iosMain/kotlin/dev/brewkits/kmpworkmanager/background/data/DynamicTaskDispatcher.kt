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
import platform.Foundation.NSDate
import platform.Foundation.NSError
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.ObjCObjectVar
import platform.Foundation.NSBundle

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
     * @return Number of tasks processed in this batch.
     */
    suspend fun executePendingTasks(scheduler: BackgroundTaskScheduler): Int {
        var processedCount = 0
        
        while (isShuttingDown.value == 0) {
            val taskId = fileStorage.dequeueTask() ?: break
            
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
            val remaining = fileStorage.getTasksQueueSize()
            if (remaining > 0) {
                Logger.i(LogTags.SCHEDULER, "$remaining dynamic tasks remaining - rescheduling master dispatcher")
                rescheduleMasterDispatcher()
            }
        }
        
        return processedCount
    }

    private fun rescheduleMasterDispatcher() {
        // In test environment (no app bundle), BGTaskScheduler is unavailable
        if (NSBundle.mainBundle.bundleIdentifier == null) return

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val request = BGProcessingTaskRequest("kmp_master_dispatcher_task")
            request.earliestBeginDate = NSDate()
            request.requiresNetworkConnectivity = false
            
            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorPtr.ptr)
            if (!ok) {
                val err = errorPtr.value
                Logger.e(LogTags.SCHEDULER, "Failed to reschedule master dispatcher: ${err?.localizedDescription}")
            }
        }
    }
}
