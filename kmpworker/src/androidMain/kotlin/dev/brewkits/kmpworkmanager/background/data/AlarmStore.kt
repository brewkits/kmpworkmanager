package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import org.json.JSONObject

/**
 * Persists exact alarm metadata to SharedPreferences so alarms can be rescheduled
 * after a device reboot.
 *
 * **Why this is needed:**
 * AlarmManager alarms do NOT survive device reboots. Without persistence, exact alarms
 * scheduled via [NativeTaskScheduler] are silently lost when the device restarts, with
 * no error to the user or developer.
 *
 * **Storage format:**
 * Each alarm is stored as a JSON entry in a dedicated SharedPreferences file:
 * ```
 * kmp_alarm_{id} = {"id":"...", "atMs":1234567890, "worker":"com.example.MyWorker", "json":"..."}
 * ```
 * The `json` field holds the original full input JSON (not an overflow file path), so the
 * content survives reboots even if cacheDir files are cleared.
 *
 * **Callers:**
 * - [NativeTaskScheduler.scheduleExactAlarm] — saves metadata after successful scheduling
 * - [NativeTaskScheduler.cancel] — removes metadata when an alarm is cancelled
 * - [AlarmBootReceiver] — reads all metadata on BOOT_COMPLETED to reschedule future alarms
 */
internal object AlarmStore {

    private const val PREFS_NAME = "dev.brewkits.kmpworkmanager.alarms"
    private const val KEY_PREFIX = "kmp_alarm_"

    data class AlarmMetadata(
        val id: String,
        val atEpochMillis: Long,
        val workerClassName: String,
        val inputJson: String?
    )

    fun save(context: Context, metadata: AlarmMetadata) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("id", metadata.id)
                put("atMs", metadata.atEpochMillis)
                put("worker", metadata.workerClassName)
                if (metadata.inputJson != null) put("json", metadata.inputJson)
            }
            prefs.edit().putString(KEY_PREFIX + metadata.id, json.toString()).apply()
            Logger.d(LogTags.ALARM, "Saved alarm metadata for '${metadata.id}' (fire at ${metadata.atEpochMillis}ms)")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to persist alarm metadata for '${metadata.id}'", e)
        }
    }

    fun remove(context: Context, id: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PREFIX + id).apply()
            Logger.d(LogTags.ALARM, "Removed alarm metadata for '$id'")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to remove alarm metadata for '$id'", e)
        }
    }

    /**
     * Removes stale alarm metadata (SharedPreferences) **and** orphaned overflow files
     * (`kmp_input_*.json` in `cacheDir`) older than [maxAgeMs] milliseconds.
     *
     * **Why both in one call:**
     * Alarms that were silently dropped by the OS (low battery, force-stop) leave behind:
     * 1. A ghost entry in SharedPreferences — `AlarmStore.remove()` never ran
     * 2. A physical `kmp_input_*.json` file in `cacheDir` — `overflowFilePath` never deleted
     *
     * `getFutureAlarms()` prunes past entries on BOOT_COMPLETED, but devices that rarely
     * reboot accumulate both types of stale data. Call this at app startup or periodically.
     *
     * @param maxAgeMs Cutoff relative to `now`. Entries/files older than this are removed.
     *   Default: 24 hours. The 24 h window ensures in-progress tasks (which run well within
     *   that window) are never accidentally cleaned up.
     * @return Total number of items removed (metadata entries + physical files combined).
     */
    fun cleanupStaleAlarms(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var totalRemoved = 0

        // 1. Prune ghost SharedPreferences entries.
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stale = prefs.all
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .filter { (_, value) ->
                    try {
                        JSONObject(value as String).getLong("atMs") < cutoff
                    } catch (e: Exception) {
                        true  // Malformed entry — remove it too
                    }
                }
                .keys
            if (stale.isNotEmpty()) {
                val editor = prefs.edit()
                stale.forEach { editor.remove(it) }
                editor.apply()
                Logger.i(LogTags.ALARM, "cleanupStaleAlarms: removed ${stale.size} ghost SharedPreferences entries")
                totalRemoved += stale.size
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "cleanupStaleAlarms: failed to prune SharedPreferences entries", e)
        }

        // 2. Prune orphaned overflow files in cacheDir.
        // These are kmp_input_*.json files left behind when the process was killed before
        // the worker could call overflowFilePath?.delete() in its finally block.
        try {
            val cacheDir = context.cacheDir
            val zombies = cacheDir.listFiles { file ->
                file.name.startsWith("kmp_input_") &&
                    file.name.endsWith(".json") &&
                    file.lastModified() < cutoff
            } ?: emptyArray()

            var freedBytes = 0L
            for (file in zombies) {
                freedBytes += file.length()
                file.delete()
            }

            if (zombies.isNotEmpty()) {
                Logger.i(
                    LogTags.ALARM,
                    "cleanupStaleAlarms: deleted ${zombies.size} orphaned overflow files " +
                        "(${freedBytes / 1024} KB freed from cacheDir)"
                )
                totalRemoved += zombies.size
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "cleanupStaleAlarms: failed to prune cacheDir overflow files", e)
        }

        return totalRemoved
    }

    /**
     * Returns all persisted alarm entries whose scheduled time is in the future.
     * Past alarms are cleaned up automatically during this call.
     */
    fun getFutureAlarms(context: Context): List<AlarmMetadata> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val expired = mutableListOf<String>()
            val result = prefs.all
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    try {
                        val json = JSONObject(value as String)
                        val atMs = json.getLong("atMs")
                        if (atMs <= now) {
                            expired.add(key)
                            null
                        } else {
                            AlarmMetadata(
                                id = json.getString("id"),
                                atEpochMillis = atMs,
                                workerClassName = json.getString("worker"),
                                inputJson = if (json.has("json")) json.getString("json") else null
                            )
                        }
                    } catch (e: Exception) {
                        Logger.w(LogTags.ALARM, "Skipping malformed alarm entry '$key'")
                        expired.add(key)
                        null
                    }
                }
            // Clean up past / malformed entries
            if (expired.isNotEmpty()) {
                val editor = prefs.edit()
                expired.forEach { editor.remove(it) }
                editor.apply()
                Logger.d(LogTags.ALARM, "Pruned ${expired.size} expired alarm entries")
            }
            result
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Failed to read alarm metadata", e)
            emptyList()
        }
    }
}
