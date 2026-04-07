package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime
import dev.brewkits.kmpworkmanager.background.data.IosDispatchers
import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * iOS implementation of [ExecutionHistoryStore].
 */
@OptIn(ExperimentalForeignApi::class)
class IosExecutionHistoryStore : ExecutionHistoryStore {

    private val json = KmpWorkManagerRuntime.json
    private val fileManager = NSFileManager.defaultManager
    private val mutex = Mutex()

    // Estimated total record count. Incremented on each save; reset to MAX_RECORDS after
    // a prune (pruneInternal truncates to exactly MAX_RECORDS). Starts at 0 (unknown);
    // once any prune runs, the estimate is always accurate.
    // Protected by [mutex], so @Volatile is not needed.
    private var estimatedRecordCount: Int = 0

    private val baseDir: NSURL by lazy {
        val urls = fileManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask) as List<*>
        val appSupportDir = urls.firstOrNull() as? NSURL
            ?: throw IllegalStateException("Could not locate Application Support directory")
        val dir = appSupportDir
            .URLByAppendingPathComponent("dev.brewkits.kmpworkmanager", isDirectory = true)
            ?.URLByAppendingPathComponent("history", isDirectory = true)
            ?: throw IllegalStateException("URLByAppendingPathComponent returned null constructing history directory URL")
        val path = dir.path ?: throw IllegalStateException("History directory path is null")
        if (!fileManager.fileExistsAtPath(path)) {
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                fileManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = errorPtr.ptr)
            }
        }
        dir
    }

    private val historyFileURL: NSURL by lazy {
        val url = baseDir.URLByAppendingPathComponent("history.jsonl")
            ?: throw IllegalStateException("URLByAppendingPathComponent returned null constructing history.jsonl URL")
        val path = url.path ?: ""
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, null, null)
        }
        url
    }

    override suspend fun save(record: ExecutionRecord): Unit = withContext(IosDispatchers.IO) {
        mutex.withLock {
            IosFileCoordinator.coordinate(historyFileURL, write = true) { safeUrl ->
                // Disk space guard: avoid writing if disk is critically low (< 1MB)
                val usableSpace = checkFreeSpace(safeUrl)
                if (usableSpace < 1024 * 1024L) {
                    Logger.e(LogTags.SCHEDULER, "ExecutionHistory: disk critically low ($usableSpace bytes), skipping save")
                    return@coordinate
                }

                val line = json.encodeToString(record) + "\n"
                val path = safeUrl.path ?: return@coordinate
                memScoped {
                    val nsLine = line as NSString
                    val data = nsLine.dataUsingEncoding(NSUTF8StringEncoding) ?: return@coordinate
                    val handle = NSFileHandle.fileHandleForWritingAtPath(path)
                    if (handle != null) {
                        handle.seekToEndOfFile()
                        handle.writeData(data)
                        handle.closeFile()
                    } else {
                        fileManager.createFileAtPath(path, data, null)
                    }
                }
            }
            
            estimatedRecordCount++
            // Prune when estimated record count or file size exceeds thresholds.
            // Once estimatedRecordCount > MAX_RECORDS, pruneInternal() runs on EVERY
            // subsequent save, keeping the store at exactly MAX_RECORDS permanently.
            if (shouldPrune()) {
                pruneInternal()
                // After pruning, exactly MAX_RECORDS lines remain in the file.
                estimatedRecordCount = ExecutionHistoryStore.MAX_RECORDS
            }
        }
    }

    override suspend fun getRecords(limit: Int): List<ExecutionRecord> = withContext(IosDispatchers.IO) {
        mutex.withLock {
            val lines = readAllLines()
            lines.reversed().take(limit).mapNotNull<String, ExecutionRecord> { line ->
                runCatching { json.decodeFromString<ExecutionRecord>(line) }.getOrNull()
            }
        }
    }

    override suspend fun clear(): Unit = withContext(IosDispatchers.IO) {
        mutex.withLock {
            IosFileCoordinator.coordinate(historyFileURL, write = true) { safeUrl ->
                val path = safeUrl.path ?: return@coordinate
                fileManager.createFileAtPath(path, null, null)
            }
            estimatedRecordCount = 0
            Logger.d(LogTags.SCHEDULER, "ExecutionHistory cleared")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun readAllLines(): List<String> {
        val path = historyFileURL.path ?: return emptyList()
        if (!fileManager.fileExistsAtPath(path)) return emptyList()
        val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: return emptyList()
        return (content as String).lines().filter { it.isNotBlank() }
    }

    private fun checkFreeSpace(url: NSURL): Long {
        val path = url.path ?: return 0L
        val attributes = fileManager.attributesOfFileSystemForPath(path, error = null) ?: return 0L
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longValue ?: 0L
    }

    private fun shouldPrune(): Boolean {
        // 1. Count-based: prune when estimated record count exceeds MAX_RECORDS.
        //    Once this triggers the first time, estimatedRecordCount is reset to
        //    MAX_RECORDS, so every subsequent save (estimatedRecordCount = MAX_RECORDS+1)
        //    triggers another prune — guaranteeing the store is always ≤ MAX_RECORDS.
        if (estimatedRecordCount > ExecutionHistoryStore.MAX_RECORDS) return true
        // 2. File-size-based: also prune for large individual records (≥ 256KB).
        val path = historyFileURL.path ?: return false
        val attrs = fileManager.attributesOfItemAtPath(path, error = null) ?: return false
        val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
        return size > 256 * 1024L
    }

    private suspend fun pruneInternal() {
        val lines = readAllLines()
        if (lines.size <= ExecutionHistoryStore.MAX_RECORDS) return

        val kept = lines.takeLast(ExecutionHistoryStore.MAX_RECORDS)
        val newContent = kept.joinToString("\n") + "\n"
        IosFileCoordinator.coordinate(historyFileURL, write = true) { safeUrl ->
            val path = safeUrl.path ?: return@coordinate
            val nsContent = newContent as NSString
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                nsContent.writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = errorPtr.ptr)
            }
        }
        Logger.d(LogTags.SCHEDULER, "ExecutionHistory pruned: kept ${kept.size} records")
    }
}
