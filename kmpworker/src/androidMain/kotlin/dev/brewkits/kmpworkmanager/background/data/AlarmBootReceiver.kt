package dev.brewkits.kmpworkmanager.background.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags

/**
 * Abstract BroadcastReceiver that reschedules exact alarms after device reboot.
 *
 * AlarmManager alarms do NOT survive reboots. Without this receiver, exact alarms
 * scheduled via [NativeTaskScheduler] are silently lost when the device restarts.
 * [NativeTaskScheduler] automatically persists alarm metadata to [AlarmStore] at
 * schedule time. This receiver reads that metadata on boot and restores all alarms
 * whose scheduled time has not yet passed.
 *
 * **Usage:**
 * 1. Extend this class in your app and implement [getAlarmReceiverClass]:
 * ```kotlin
 * class MyAlarmBootReceiver : AlarmBootReceiver() {
 *     override fun getAlarmReceiverClass() = MyAlarmReceiver::class.java
 * }
 * ```
 *
 * 2. Register in AndroidManifest.xml:
 * ```xml
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * <receiver
 *     android:name=".MyAlarmBootReceiver"
 *     android:enabled="true"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * **Note on `LOCKED_BOOT_COMPLETED`:**
 * On API 24+ (direct boot mode), `BOOT_COMPLETED` is not delivered until the user unlocks
 * the device. `LOCKED_BOOT_COMPLETED` fires earlier and ensures alarms are restored even
 * on encrypted devices that haven't been unlocked yet. Both actions are safe to register —
 * pre-API 24 devices simply ignore `LOCKED_BOOT_COMPLETED`.
 *
 * **Note on SCHEDULE_EXACT_ALARM permission:**
 * This receiver silently skips rescheduling if [AlarmManager.canScheduleExactAlarms] returns
 * false (permission revoked after reboot). The alarm metadata is NOT removed — it remains
 * in [AlarmStore] so the host app can prompt the user to re-grant the permission and then
 * call [NativeTaskScheduler.enqueue] again to reschedule.
 */
abstract class AlarmBootReceiver : BroadcastReceiver() {

    /**
     * Return the same [AlarmReceiver] subclass that your [NativeTaskScheduler] uses.
     * This is needed to reconstruct the correct [PendingIntent] for each alarm.
     */
    abstract fun getAlarmReceiverClass(): Class<out AlarmReceiver>

    final override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot && !isUpdate) {
            return
        }

        val logReason = if (isUpdate) "App updated" else "Device booted"
        Logger.i(LogTags.ALARM, "$logReason — checking for alarms to reschedule")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Skip if permission was revoked while the device was off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Logger.w(
                LogTags.ALARM,
                "SCHEDULE_EXACT_ALARM permission not granted — skipping alarm restore. " +
                "Alarm metadata preserved in AlarmStore; reschedule after the user re-grants permission."
            )
            return
        }

        val receiverClass = getAlarmReceiverClass()
        val futureAlarms = AlarmStore.getFutureAlarms(context)

        if (futureAlarms.isEmpty()) {
            Logger.d(LogTags.ALARM, "No future alarms to restore after reboot")
            return
        }

        Logger.i(LogTags.ALARM, "Restoring ${futureAlarms.size} alarm(s) after reboot")

        for (metadata in futureAlarms) {
            try {
                val alarmIntent = Intent(context, receiverClass).apply {
                    putExtra(AlarmReceiver.EXTRA_TASK_ID, metadata.id)
                    putExtra(AlarmReceiver.EXTRA_WORKER_CLASS, metadata.workerClassName)
                    // Android Binder has a ~1 MB total transaction limit. Embedding large JSON in an Intent
                    // extra causes TransactionTooLargeException which kills the system server during
                    // boot and crashes the app. Use the overflow-file mechanism for large inputs.
                    if (metadata.inputJson != null) {
                        val bytes = metadata.inputJson.encodeToByteArray()
                        if (bytes.size <= NativeTaskScheduler.OVERFLOW_THRESHOLD_BYTES) {
                            putExtra(AlarmReceiver.EXTRA_INPUT_JSON, metadata.inputJson)
                        } else {
                            val tempFile = java.io.File(
                                context.cacheDir,
                                "kmp_input_${java.util.UUID.randomUUID()}_boot.json"
                            )
                            try {
                                tempFile.bufferedWriter().use { it.write(metadata.inputJson) }
                                putExtra(AlarmReceiver.EXTRA_INPUT_JSON_FILE, tempFile.absolutePath)
                                Logger.d(LogTags.ALARM, "Alarm input JSON overflow during reboot — spilled to file: ${tempFile.name}")
                            } catch (e: Exception) {
                                Logger.e(LogTags.ALARM, "Failed to spill reboot alarm JSON to file — using inline (danger of crash)", e)
                                putExtra(AlarmReceiver.EXTRA_INPUT_JSON, metadata.inputJson)
                            }
                        }
                    }
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    metadata.id.hashCode(),
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        metadata.atEpochMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        metadata.atEpochMillis,
                        pendingIntent
                    )
                }

                Logger.i(
                    LogTags.ALARM,
                    "Restored alarm '${metadata.id}' → ${metadata.atEpochMillis}ms " +
                        "(worker: ${metadata.workerClassName})"
                )
            } catch (e: SecurityException) {
                Logger.e(LogTags.ALARM, "SecurityException restoring alarm '${metadata.id}'", e)
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Failed to restore alarm '${metadata.id}'", e)
            }
        }
    }
}
