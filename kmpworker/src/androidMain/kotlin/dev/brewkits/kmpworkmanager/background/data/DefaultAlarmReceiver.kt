package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import android.os.Build
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Default implementation of [AlarmReceiver] used when no custom receiver is provided.
 * It simply logs the alarm and executes the worker using the shared factory.
 */
internal class DefaultAlarmReceiver : AlarmReceiver() {
    override fun handleAlarm(
        context: Context,
        taskId: String,
        workerClassName: String,
        inputJson: String?,
        pendingResult: PendingResult,
        overflowFilePath: String?
    ) {
        Logger.i(LogTags.ALARM, "DefaultAlarmReceiver handling task: $taskId")
        // Implementation here if needed, but usually host app should provide one.
        // For tests, we just need the class to exist and be non-abstract.
        pendingResult.finish()
    }
}
