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
 *         <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * **Note on `MY_PACKAGE_REPLACED`:** [onReceive] already handles this action (an app update
 * can reset AlarmManager state on some OEMs the same way a reboot does) — it was previously
 * missing from this manifest example, so a host app that copied only what was shown here
 * never actually registered for it and this receiver's "App updated" restore path silently
 * never ran.
 *
 * **Note on `LOCKED_BOOT_COMPLETED` — read this before relying on it:**
 * On API 24+, `BOOT_COMPLETED` is not delivered until the user unlocks the device, and
 * `LOCKED_BOOT_COMPLETED` fires earlier. Registering it is harmless (pre-API 24 devices
 * ignore it), but **as this library stands it does not buy you a pre-unlock restore**, and
 * the snippet above is deliberately not `directBootAware`. Two things would have to change
 * together, and neither is a manifest edit the host app can make alone:
 *
 *  1. The receiver would need `android:directBootAware="true"`. Without it the system does
 *     not start the component before first unlock, so `LOCKED_BOOT_COMPLETED` never arrives
 *     no matter what the intent-filter says.
 *  2. [AlarmStore] would have to move to device-protected storage. It reads and writes
 *     through `context.getSharedPreferences(...)` on the credential-encrypted context, which
 *     is simply not readable before first unlock — so a direct-boot-aware receiver would wake
 *     up and find no alarms to restore.
 *
 * What you actually get today: alarms are restored at `BOOT_COMPLETED`, i.e. after the first
 * unlock. For the overwhelming majority of apps that is the right trade — moving alarm
 * metadata to device-protected storage means it is no longer protected by the user's
 * credential, which is a real decision, not a checkbox. If you need pre-unlock restore, file
 * an issue describing the use case.
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

        // Clean up any zombie overflow files from previous crashed sessions
        NativeTaskScheduler.cleanupZombieInputFiles(context)

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

                // CRC32-derived request code (matches NativeTaskScheduler) so that
                // FLAG_UPDATE_CURRENT resolves to the same PendingIntent slot the original
                // schedule used. Using String.hashCode() here would collide and either fail
                // to update or silently double-arm alarms after reboot.
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    PendingIntentCodes.forTaskId(metadata.id),
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
