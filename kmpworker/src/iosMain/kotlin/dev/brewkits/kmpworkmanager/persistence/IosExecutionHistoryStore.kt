package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*

/**
 * iOS implementation of [ExecutionHistoryStore].
 *
 * **Storage:** `Library/Application Support/dev.brewkits.kmpworkmanager/history/history.jsonl`
 * — one [ExecutionRecord] JSON per line (JSONL format), newest appended at the end.
 *
 * **Thread safety:** All writes are serialized via [Mutex] and use [IosFileCoordinator]
 * for atomic cross-process consistency.
 *
 * **Auto-pruning:** When total records exceed [ExecutionHistoryStore.MAX_RECORDS], the
 * oldest entries are dropped to keep the file bounded.
 */
@OptIn(ExperimentalForeignApi::class)
class IosExecutionHistoryStore : ExecutionHistoryStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val fileManager = NSFileManager.defaultManager
    private val mutex = Mutex()

    private val baseDir: NSURL by lazy {
        val urls = fileManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask) as List<*>
        val appSupportDir = urls.firstOrNull() as? NSURL
            ?: throw IllegalStateException("Could not locate Application Support directory")
        val dir = appSupportDir
            .URLByAppendingPathComponent("dev.brewkits.kmpworkmanager", isDirectory = true)!!
            .URLByAppendingPathComponent("history", isDirectory = true)!!
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
        val url = baseDir.URLByAppendingPathComponent("history.jsonl")!!
        val path = url.path ?: ""
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, null, null)
        }
        url
    }

    override suspend fun save(record: ExecutionRecord): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            IosFileCoordinator.coordinate(historyFileURL, write = true) { safeUrl ->
                val line = json.encodeToString(record) + "\n"
                val path = safeUrl.path ?: return@coordinate
                memScoped {
                    val nsLine = line as NSString
                    val data = nsLine.dataUsingEncoding(NSUTF8StringEncoding) ?: return@coordinate
                    if (fileManager.fileExistsAtPath(path)) {
                        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
                        if (handle != null) {
                            handle.seekToEndOfFile()
                            handle.writeData(data)
                            handle.closeFile()
                        }
                    } else {
                        fileManager.createFileAtPath(path, data, null)
                    }
                }
            }
            pruneIfNeeded()
        }
    }

    override suspend fun getRecords(limit: Int): List<ExecutionRecord> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val lines = readAllLines()
            lines
                .reversed()  // newest first
                .take(limit)
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<ExecutionRecord>(line) }
                        .onFailure { Logger.w(LogTags.SCHEDULER, "ExecutionHistory: skipping malformed record: ${it.message}") }
                        .getOrNull()
                }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            IosFileCoordinator.coordinate(historyFileURL, write = true) { safeUrl ->
                val path = safeUrl.path ?: return@coordinate
                fileManager.createFileAtPath(path, null, null)
            }
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

    private fun pruneIfNeeded() {
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
        Logger.d(LogTags.SCHEDULER, "ExecutionHistory pruned: kept ${kept.size} of ${lines.size} records")
    }
}
