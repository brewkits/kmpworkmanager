package dev.brewkits.kmpworkmanager.persistence

import android.content.Context
import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Android implementation of [ExecutionHistoryStore].
 */
class AndroidExecutionHistoryStore(context: Context) : ExecutionHistoryStore {

    private val json = KmpWorkManagerRuntime.json
    private val fileLock = Any()

    private val historyFile: File by lazy {
        val dir = File(context.filesDir, "dev.brewkits.kmpworkmanager/history").apply { mkdirs() }
        File(dir, "history.jsonl").apply { if (!exists()) createNewFile() }
    }

    override suspend fun save(record: ExecutionRecord): Unit = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                // Disk space guard
                val usableSpace = historyFile.parentFile?.usableSpace ?: 0L
                if (usableSpace < 1024 * 1024L) {
                    Logger.e(LogTags.SCHEDULER, "ExecutionHistory: disk critically low, skipping save")
                    return@withContext
                }

                historyFile.appendText(json.encodeToString(record) + "\n")
                
                if (historyFile.length() > 512 * 1024L) { 
                    pruneIfNeeded()
                }
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "ExecutionHistory: failed to save record", e)
            }
        }
    }

    override suspend fun getRecords(limit: Int): List<ExecutionRecord> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            if (!historyFile.exists() || historyFile.length() == 0L) return@withContext emptyList()

            try {
                val records = mutableListOf<ExecutionRecord>()
                historyFile.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                records.add(json.decodeFromString<ExecutionRecord>(line))
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                    }
                }
                records.reversed().take(limit)
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "ExecutionHistory: failed to read records", e)
                emptyList()
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            try {
                if (historyFile.exists()) {
                    historyFile.delete()
                    historyFile.createNewFile()
                }
                Logger.d(LogTags.SCHEDULER, "ExecutionHistory cleared")
            } catch (e: Exception) {
                Logger.e(LogTags.SCHEDULER, "ExecutionHistory: failed to clear", e)
            }
        }
    }

    private fun pruneIfNeeded() {
        try {
            val allRecords = mutableListOf<String>()
            historyFile.bufferedReader().use { reader ->
                reader.forEachLine { if (it.isNotBlank()) allRecords.add(it) }
            }

            if (allRecords.size <= ExecutionHistoryStore.MAX_RECORDS) return

            val kept = allRecords.takeLast(ExecutionHistoryStore.MAX_RECORDS)
            val tempFile = File(historyFile.parent, "history.jsonl.tmp")
            
            tempFile.bufferedWriter().use { writer ->
                kept.forEach {
                    writer.write(it)
                    writer.newLine()
                }
            }

            if (!tempFile.renameTo(historyFile)) {
                Logger.w(LogTags.SCHEDULER, "ExecutionHistory: atomic rename failed during pruning")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "ExecutionHistory: pruning failed", e)
        }
    }
}
