package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.background.data.IosFileCoordinator
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.*

/**
 * Streams a JSONL file line-by-line using Okio.
 *
 * Peak RAM is minimal as Okio handles buffering and line detection internally.
 * This implementation is safe against multi-byte UTF-8 character corruption
 * on chunk boundaries, which was a risk with the manual NSInputStream approach.
 */
internal fun streamLinesFromPath(path: String, block: (String) -> Unit) {
    try {
        FileSystem.SYSTEM.source(path.toPath()).buffer().use { source ->
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isNotBlank()) block(line)
            }
        }
    } catch (e: Exception) {
        // Callers treat a missing or unreadable file the same as an empty file.
    }
}

/**
 * Streams a JSONL file line-by-line inside an NSFileCoordinator read lock.
 */
internal fun streamLinesCoordinated(url: NSURL, block: (String) -> Unit) {
    IosFileCoordinator.coordinateSync(url, write = false) { safeUrl ->
        val path = safeUrl.path ?: return@coordinateSync
        streamLinesFromPath(path, block)
    }
}
