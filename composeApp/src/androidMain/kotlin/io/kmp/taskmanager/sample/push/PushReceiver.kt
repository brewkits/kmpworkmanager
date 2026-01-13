package io.kmp.taskmanager.sample.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.kmp.taskmanager.sample.background.data.WorkerTypes
import io.kmp.taskmanager.sample.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.sample.background.domain.TaskTrigger
import org.koin.core.context.GlobalContext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val koin = GlobalContext.get()
        val scheduler: BackgroundTaskScheduler = koin.get()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.enqueue(
                    id = "task-from-push",
                    trigger = TaskTrigger.OneTime(initialDelayMs = 5000),
                    workerClassName = WorkerTypes.UPLOAD_WORKER
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
