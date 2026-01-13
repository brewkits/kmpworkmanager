package io.brewkits.kmpworkmanager.sample.debug

import io.brewkits.kmpworkmanager.sample.background.data.NativeTaskScheduler
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSDictionary
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGTaskRequest
import kotlin.coroutines.suspendCoroutine

/**
 * iOS-specific implementation of DebugSource that queries BGTaskScheduler and UserDefaults.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDebugSource : DebugSource {

    private val userDefaults = NSUserDefaults.standardUserDefaults

    private fun Map<*, *>?.toStringMap(): Map<String, String>? {
        return (this as? Map<*, *>)?.filter { (key, value) ->
            key is String && value is String
        }?.mapKeys { it.key as String }
        ?.mapValues { it.value as String }
    }

    override suspend fun getTasks(): List<DebugTaskInfo> {
        val pendingTasks = getPendingBGTasks()
        val taskInfos = mutableListOf<DebugTaskInfo>()

        // 1. Process tasks found in the BGTaskScheduler
        for (taskRequest in pendingTasks) {
            val id = taskRequest.identifier
            var type = "OneTime"
            var status = "ENQUEUED"
            var workerClassName = "N/A"

            // Check for periodic metadata
            val periodicMeta = userDefaults.dictionaryForKey("kmp_periodic_meta_$id").toStringMap()
            if (periodicMeta != null && periodicMeta["isPeriodic"] == "true") {
                type = "Periodic"
                workerClassName = periodicMeta["workerClassName"] ?: "N/A"
            } else {
                // Check for one-time task metadata
                val taskMeta = userDefaults.dictionaryForKey("kmp_task_meta_$id").toStringMap()
                if (taskMeta != null) {
                    workerClassName = taskMeta["workerClassName"] ?: "N/A"
                }
            }

            // Check if it's a chain executor
            if (id.startsWith("kmp_chain_executor_")) {
                type = "Chain"
                workerClassName = "ChainExecutor"
                status = "PENDING_EXECUTION"
            }

            taskInfos.add(
                DebugTaskInfo(
                    id = id,
                    type = type,
                    status = status,
                    workerClassName = workerClassName.split('.').last(),
                    isPeriodic = type == "Periodic",
                    isChain = type == "Chain"
                )
            )
        }

        // 2. Add chains that are in the queue but not yet submitted to BGTaskScheduler
        val chainQueue = (userDefaults.arrayForKey("kmp_chain_queue")?.filterIsInstance<String>() ?: emptyList())
        for (chainId in chainQueue) {
            // Avoid adding duplicates if the executor task is already pending
            if (taskInfos.none { it.id.contains(chainId) }) {
                taskInfos.add(
                    DebugTaskInfo(
                        id = chainId,
                        type = "Chain",
                        status = "QUEUED",
                        workerClassName = "ChainExecutor",
                        isChain = true
                    )
                )
            }
        }

        return taskInfos
    }

    private suspend fun getPendingBGTasks(): List<BGTaskRequest> = suspendCoroutine {
        continuation ->
            BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { requests ->
                continuation.resume(requests?.filterIsInstance<BGTaskRequest>() ?: emptyList())
        }
    }
}
