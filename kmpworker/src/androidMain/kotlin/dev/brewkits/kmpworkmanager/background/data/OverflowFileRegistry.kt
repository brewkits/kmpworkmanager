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
 * **Storage (3.3.0): one file per entry**, not `SharedPreferences`. The registry is a
 * `cacheDir/overflow_registry/<encoded taskId>.path` file per mapping, containing the
 * overflow file's absolute path.
 *
 * **What changed and why**: the v2.5.0 version used a single `SharedPreferences` file.
 * `SharedPreferences` holds an in-memory cache per `Context` instance, and hosts with
 * separate processes (`:background`, `:push`, …) each get their own `Context` and
 * therefore their own cache. A `register()` in process A and a `consumeAndDelete()` in
 * process B could race: B's cache doesn't see A's write yet → returns null → the
 * overflow file leaks until the 24 h janitor sweeps it. Plain file I/O under `cacheDir`
 * has no such per-process caching layer — every read goes to the shared filesystem, so
 * this race is not merely mitigated, it cannot occur by construction. (Not something an
 * in-process Robolectric test can exercise directly — genuinely separate processes are
 * needed — but the fix removes the caching layer the race depended on entirely.)
 *
 * **Migration**: zero-config. The first `register`/`consumeAndDelete` call in a process
 * reads any legacy `SharedPreferences` entries once, writes them into the new file
 * layout, and clears the prefs. Idempotent and safe to run redundantly from multiple
 * processes — see [migrateLegacyEntriesIfNeeded].
 *
 * **Filename encoding**: task ids are caller-supplied (`BackgroundTaskScheduler.enqueue`
 * takes an arbitrary `id: String`), so they cannot be used as filenames directly — a
 * malicious or buggy id like `"../../../etc/passwd"` would otherwise let a caller write
 * outside `overflow_registry/`. [encodeTaskIdForFilename] percent-encodes every byte
 * outside `[A-Za-z0-9-]`, which is both injective (two different task ids can never
 * collide, unlike a hash — a collision here would silently merge two unrelated tasks'
 * overflow-file entries) and traversal-safe (no raw `/` or `.` ever reaches the
 * filesystem call).
 *
 * **What this does NOT replace**: `AlarmStore.cleanupStaleAlarms` is still the
 * defence-in-depth sweep for entries the registry missed (e.g. force-stop between
 * file write and registry write, app uninstalled + reinstalled with cache preserved).
 * The registry is the fast happy-path; the 24 h sweep is the long-tail safety net.
 */
internal object OverflowFileRegistry {

    private const val REGISTRY_DIR_NAME = "overflow_registry"
    private const val ENTRY_SUFFIX = ".path"
    private const val LEGACY_PREFS_NAME = "dev.brewkits.kmpworkmanager.overflow_files"
    private const val LEGACY_KEY_PREFIX = "of_"

    @Volatile
    private var legacyMigrationDone = false
    private val migrationLock = Any()

    private fun registryDir(context: Context): File =
        File(context.cacheDir, REGISTRY_DIR_NAME)

    /**
     * Percent-encodes every byte outside `[A-Za-z0-9-]`. Injective (distinct inputs never
     * collide) and always produces a filesystem-safe, traversal-safe name — see class doc.
     */
    private fun encodeTaskIdForFilename(taskId: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(taskId.length)
        for (byte in taskId.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val isSafe = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code
            if (isSafe) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(hex[(c shr 4) and 0xF]).append(hex[c and 0xF])
            }
        }
        return sb.toString()
    }

    private fun entryFile(context: Context, taskId: String): File =
        File(registryDir(context), encodeTaskIdForFilename(taskId) + ENTRY_SUFFIX)

    /**
     * Writes `path` for `taskId`, atomically (temp file + rename, same volume as
     * `cacheDir` so the rename is atomic on ext4/f2fs). A reader can never observe a
     * half-written entry.
     */
    private fun writeEntryAtomic(target: File, path: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        tmp.writeText(path)
        if (!tmp.renameTo(target)) {
            // Fall back to a direct write if rename failed (e.g. cross-volume — should
            // not happen for two files in the same directory, but never leave the entry
            // unwritten over an edge case).
            tmp.delete()
            target.writeText(path)
        }
    }

    /**
     * One-time, idempotent migration from the v2.5.0 `SharedPreferences` store. Safe to
     * invoke redundantly (including concurrently from separate processes): re-writing an
     * already-migrated entry is harmless, and clearing an already-empty prefs file is a
     * no-op. Failures are logged and swallowed — migration is a convenience, not a
     * correctness requirement; the janitor sweep still catches anything this misses.
     */
    private fun migrateLegacyEntriesIfNeeded(context: Context) {
        if (legacyMigrationDone) return
        synchronized(migrationLock) {
            if (legacyMigrationDone) return
            try {
                val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legacyEntries = prefs.all
                if (legacyEntries.isNotEmpty()) {
                    var migrated = 0
                    for ((key, value) in legacyEntries) {
                        if (!key.startsWith(LEGACY_KEY_PREFIX)) continue
                        val taskId = key.removePrefix(LEGACY_KEY_PREFIX)
                        val path = value as? String ?: continue
                        writeEntryAtomic(entryFile(context, taskId), path)
                        migrated++
                    }
                    // apply() — async is fine; the files are now the source of truth, so a
                    // brief window where both prefs and files hold the same entries is harmless.
                    prefs.edit().clear().apply()
                    if (migrated > 0) {
                        Logger.i(LogTags.ALARM, "OverflowFileRegistry: migrated $migrated legacy entrie(s) to file-backed storage")
                    }
                }
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "OverflowFileRegistry: legacy migration failed (non-fatal, janitor sweep remains): ${e.message}", e)
            } finally {
                legacyMigrationDone = true
            }
        }
    }

    /**
     * Record that `path` is the overflow file for `taskId`. Durable before returning so
     * the mapping survives a process kill in the next few ms.
     *
     * Safe to call with a null path — becomes a no-op so callers can pass through the
     * `inputJson <= threshold` branch without an `if (overflow) register else nothing`.
     *
     * If `taskId` already has an entry (e.g. `ExistingPolicy.REPLACE` rescheduling the same
     * id with a new large input, or an exact alarm being rescheduled), the OLD overflow file
     * it pointed to is deleted before the entry is overwritten with the new path — otherwise
     * that old file becomes unreachable through the registry (only `taskId`'s single entry
     * slot is ever consulted) and leaks in `cacheDir` until the 24h janitor sweep, defeating
     * this registry's whole purpose of immediate cleanup for the common reschedule case.
     */
    fun register(context: Context, taskId: String, path: String?) {
        if (path == null) return
        migrateLegacyEntriesIfNeeded(context)
        try {
            val target = entryFile(context, taskId)
            if (target.exists()) {
                val oldPath = target.readText()
                if (oldPath != path) {
                    try {
                        val oldFile = File(oldPath)
                        if (oldFile.exists() && oldFile.delete()) {
                            Logger.d(LogTags.ALARM, "OverflowFileRegistry: replaced overflow file for '$taskId', deleted stale: $oldPath")
                        }
                    } catch (e: Exception) {
                        Logger.w(LogTags.ALARM, "OverflowFileRegistry: failed to delete stale overflow file for '$taskId' ($oldPath): ${e.message}")
                    }
                }
            }
            writeEntryAtomic(target, path)
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "OverflowFileRegistry.register failed for '$taskId': ${e.message}", e)
        }
    }

    /**
     * Consume the registry entry for `taskId`: read the path, delete the overflow file,
     * remove the entry. Returns the path that was deleted (or null if no entry existed).
     *
     * Called from `NativeTaskScheduler.cancel(id)` so the cacheDir does not grow with
     * every cancelled large-input task.
     */
    fun consumeAndDelete(context: Context, taskId: String): String? {
        migrateLegacyEntriesIfNeeded(context)
        return try {
            val entry = entryFile(context, taskId)
            if (!entry.exists()) return null
            val path = entry.readText()

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

            entry.delete()
            path
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "OverflowFileRegistry.consumeAndDelete failed for '$taskId': ${e.message}", e)
            null
        }
    }

    /** Separator between a chain id and its step/task suffix in a chain-step registry key. */
    private const val CHAIN_STEP_KEY_SEPARATOR = "#"

    /**
     * The registry key a chain step's overflow file is registered under — stable and
     * derivable from (chainId, stepIndex, taskIndex), unlike a random UUID. This is what
     * lets [consumeAndDeleteForChain] find every step's overflow file for a given chain:
     * a chain is only ever cancelled by its [chainId] (via `NativeTaskScheduler.cancel`),
     * never by a per-step id, so a per-step id that cannot be derived from [chainId] can
     * never be looked up again — the overflow file (and its registry entry) would leak
     * forever, surviving even the 24h janitor sweep (which only targets [AlarmStore]-based
     * exact-alarm overflow files, not chain-step ones).
     */
    fun chainStepKey(chainId: String, stepIndex: Int, taskIndex: Int): String =
        "$chainId$CHAIN_STEP_KEY_SEPARATOR$stepIndex$CHAIN_STEP_KEY_SEPARATOR$taskIndex"

    /**
     * Consumes every registry entry belonging to [chainId] (i.e. every key produced by
     * [chainStepKey] for this chain, regardless of step/task index) — deletes each
     * overflow file and its registry entry. Called from `NativeTaskScheduler.cancel(id)`
     * when `id` is a chain id, so cancelling a chain before a large-input step ever ran
     * does not orphan its overflow file.
     *
     * Percent-encoding (see [encodeTaskIdForFilename]) processes each input character
     * independently and deterministically, so encoding a prefix of a key always yields a
     * prefix of that key's full encoding — a plain filename `startsWith` check on the
     * *encoded* prefix is sufficient; no decoding of existing filenames is needed.
     *
     * @return number of overflow files deleted.
     */
    fun consumeAndDeleteForChain(context: Context, chainId: String): Int {
        migrateLegacyEntriesIfNeeded(context)
        val encodedPrefix = encodeTaskIdForFilename("$chainId$CHAIN_STEP_KEY_SEPARATOR")
        val dir = registryDir(context)
        val matches = try {
            dir.listFiles { file -> file.name.startsWith(encodedPrefix) && file.name.endsWith(ENTRY_SUFFIX) }
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "OverflowFileRegistry.consumeAndDeleteForChain failed to list entries for '$chainId': ${e.message}", e)
            null
        } ?: return 0

        var deleted = 0
        for (entry in matches) {
            try {
                val path = entry.readText()
                val file = File(path)
                if (file.exists() && file.delete()) {
                    deleted++
                    Logger.d(LogTags.ALARM, "OverflowFileRegistry: deleted chain-step overflow file for '$chainId': $path")
                }
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "OverflowFileRegistry: chain-step file delete failed for '$chainId' (${entry.name}): ${e.message}")
            } finally {
                entry.delete()
            }
        }
        return deleted
    }

    /**
     * Resets the one-time-migration latch. Used only for testing — production processes
     * migrate at most once per process lifetime by design.
     */
    internal fun resetMigrationStateForTesting() {
        synchronized(migrationLock) {
            legacyMigrationDone = false
        }
    }
}
