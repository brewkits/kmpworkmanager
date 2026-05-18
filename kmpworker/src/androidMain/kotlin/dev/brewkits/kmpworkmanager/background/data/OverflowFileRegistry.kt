package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import java.io.File

/**
 * Tracks the `cacheDir/kmp_input_*.json` overflow files keyed by their owning task id.
 *
 * **Why this exists** — v2.5.0 QA review caught that `NativeTaskScheduler.cancel(id)`
 * left the overflow `tempFile` in `cacheDir` orphaned. It relied on
 * `AlarmStore.cleanupStaleAlarms` running 24 h later to mop up. Apps that schedule and
 * cancel high volumes of large-input tasks (e.g. camera upload chains where the user
 * frequently cancels a draft) accumulated megabytes of orphaned files in the meantime.
 *
 * The registry is a small `SharedPreferences`-backed `taskId → absolutePath` map.
 * Production cost: one extra prefs read on schedule, one on cancel. Both are sync but
 * the prefs file is tiny (single string per id) so this stays well under 1 ms.
 *
 * **Concurrency**: `SharedPreferences` itself is thread-safe; we use `commit()` for
 * registration (so the file path is durable before the alarm fires) and `apply()` for
 * deletion (eventual is fine — the file is already gone by the time we update prefs).
 *
 * **What this does NOT replace**: `AlarmStore.cleanupStaleAlarms` is still the
 * defence-in-depth sweep for entries the registry missed (e.g. force-stop between
 * file write and registry write, registry pruned by the OS, app uninstalled +
 * reinstalled with cache preserved). The registry is the fast happy-path; the 24 h
 * sweep is the long-tail safety net.
 */
internal object OverflowFileRegistry {

    private const val PREFS_NAME = "dev.brewkits.kmpworkmanager.overflow_files"
    private const val KEY_PREFIX = "of_"

    /**
     * Record that `path` is the overflow file for `taskId`. Synchronous commit so the
     * mapping is durable even if the process is killed before `cancel` runs.
     *
     * Safe to call with a null path — becomes a no-op so callers can pass through the
     * `inputJson <= threshold` branch without an `if (overflow) register else nothing`.
     */
    fun register(context: Context, taskId: String, path: String?) {
        if (path == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // commit() — synchronous; the mapping must be on disk before the schedule call
            // returns. Otherwise a process kill in the next few ms would orphan the file.
            prefs.edit().putString(KEY_PREFIX + taskId, path).commit()
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "OverflowFileRegistry.register failed for '$taskId': ${e.message}", e)
        }
    }

    /**
     * Consume the registry entry for `taskId`: read the path, delete the file, remove
     * the entry. Returns the path that was deleted (or null if no entry existed).
     *
     * Called from `NativeTaskScheduler.cancel(id)` so the cacheDir does not grow with
     * every cancelled large-input task.
     */
    fun consumeAndDelete(context: Context, taskId: String): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val path = prefs.getString(KEY_PREFIX + taskId, null) ?: return null

            // Best-effort delete. The file may already be gone (worker finished + cleaned
            // up before cancel raced in); that's fine.
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    Logger.d(LogTags.ALARM, "OverflowFileRegistry: deleted overflow file for '$taskId': $path")
                }
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "OverflowFileRegistry: file delete failed for '$taskId' ($path): ${e.message}")
            }

            // apply() — async is fine; the file is already gone, the prefs entry is
            // just bookkeeping that no longer points to anything real.
            prefs.edit().remove(KEY_PREFIX + taskId).apply()
            path
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "OverflowFileRegistry.consumeAndDelete failed for '$taskId': ${e.message}", e)
            null
        }
    }
}
