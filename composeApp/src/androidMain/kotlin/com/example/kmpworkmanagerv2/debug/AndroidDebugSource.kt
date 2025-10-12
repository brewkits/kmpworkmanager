package com.example.kmpworkmanagerv2.debug

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.kmpworkmanagerv2.background.data.NativeTaskScheduler
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.await

/**
 * Android-specific implementation of DebugSource that queries WorkManager to get task information.
 */
class AndroidDebugSource(private val context: Context) : DebugSource {

    private val workManager = WorkManager.getInstance(context)

    override suspend fun getTasks(): List<DebugTaskInfo> {
        // Query WorkManager for all tasks with our common tag
        val future: ListenableFuture<List<WorkInfo>> = workManager.getWorkInfosByTag(NativeTaskScheduler.TAG_KMP_TASK)
        val workInfos = future.await() // Use await from kotlinx-coroutines-guava

        return workInfos.map { workInfo ->
            // Extract metadata from tags and data
            val id = workInfo.tags.firstOrNull { it.startsWith("id-") }?.substringAfter("id-") ?: workInfo.id.toString()
            val type = workInfo.tags.firstOrNull { it.startsWith("type-") }?.substringAfter("type-") ?: "Unknown"
            val workerClassName = workInfo.tags.firstOrNull { it.startsWith("worker-") }?.substringAfter("worker-") ?: "N/A"

            DebugTaskInfo(
                id = id,
                type = type.replaceFirstChar { it.uppercase() },
                status = workInfo.state.name,
                workerClassName = workerClassName.split('.').last(), // Show simple name
                isPeriodic = workInfo.tags.contains("type-periodic"),
                isChain = workInfo.tags.contains("type-chain-member")
            )
        }
    }
}
