package com.example.kmpworkmanagerv2.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.kmpworkmanagerv2.background.data.WorkerTypes
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.TaskTrigger
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
