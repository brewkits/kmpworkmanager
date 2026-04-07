package dev.brewkits.kmpworkmanager.persistence

import android.content.Context
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android implementation of [ExecutionHistoryStore].
 *
 * **Storage:** `{filesDir}/dev.brewkits.kmpworkmanager/history/history.jsonl`
 * — one [ExecutionRecord] JSON per line (JSONL format), newest appended at the end.
 *
 * **Thread safety:** File access is serialized via `synchronized(fileLock)`.
 * All public methods dispatch to [Dispatchers.IO].
 *
 * **Auto-pruning:** When total records exceed [ExecutionHistoryStore.MAX_RECORDS], the
 * oldest entries are dropped to keep the file bounded.
 */
class AndroidExecutionHistoryStore(context: Context) : ExecutionHistoryStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val fileLock = Any()

    private val historyFile: File by lazy {
        val dir = File(context.filesDir, "dev.brewkits.kmpworkmanager/history").apply { mkdirs() }
        File(dir, "history.jsonl").apply { if (!exists()) createNewFile() }
    }

    override suspend fun save(record: ExecutionRecord): Unit = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                historyFile.appendText(json.encodeToString(record) + "\n")
                pruneIfNeeded()
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "ExecutionHistory: failed to save record", e)
            }
        }
    }

    override suspend fun getRecords(limit: Int): List<ExecutionRecord> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            readAllLines()
                .reversed()  // newest first
                .take(limit)
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<ExecutionRecord>(line) }
                        .onFailure { Logger.w(LogTags.SCHEDULER, "ExecutionHistory: skipping malformed record: ${it.message}") }
                        .getOrNull()
                }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                historyFile.writeText("")
                Logger.d(LogTags.SCHEDULER, "ExecutionHistory cleared")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "ExecutionHistory: failed to clear", e)
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun readAllLines(): List<String> =
        if (historyFile.exists()) historyFile.readLines().filter { it.isNotBlank() }
        else emptyList()

    private fun pruneIfNeeded() {
        val lines = readAllLines()
        if (lines.size <= ExecutionHistoryStore.MAX_RECORDS) return
        val kept = lines.takeLast(ExecutionHistoryStore.MAX_RECORDS)
        historyFile.writeText(kept.joinToString("\n") + "\n")
        Logger.d(LogTags.SCHEDULER, "ExecutionHistory pruned: kept ${kept.size} of ${lines.size} records")
    }
}
