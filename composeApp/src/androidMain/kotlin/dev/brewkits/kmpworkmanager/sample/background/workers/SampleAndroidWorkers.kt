package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.time.measureTime

class SyncAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val steps = listOf("Fetching data", "Processing", "Saving")
        for ((index, step) in steps.withIndex()) {
            Logger.d(LogTags.WORKER, "Android: [$step] ${index + 1}/${steps.size}")
            delay(800)
        }
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Sync", success = true, message = "Data synced successfully"))
        return WorkerResult.Success(message = "Data synced successfully")
    }
}

class UploadAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val totalSize = 100
        var uploaded = 0
        while (uploaded < totalSize) {
            delay(300)
            uploaded += 10
            Logger.d(LogTags.WORKER, "Android: Upload progress: $uploaded/$totalSize MB")
        }
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Upload", success = true, message = "Uploaded ${totalSize}MB successfully"))
        return WorkerResult.Success(message = "Uploaded ${totalSize}MB successfully")
    }
}

class HeavyProcessingAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        var primes: List<Int> = emptyList()
        val duration = measureTime {
            primes = (2..10000).filter { n ->
                if (n < 2) false
                else (2..sqrt(n.toDouble()).toInt()).none { n % it == 0 }
            }
        }
        delay(2000)
        val message = "Calculated ${primes.size} primes in ${duration.inWholeMilliseconds}ms"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Heavy Processing", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}

class DatabaseAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val totalRecords = 1000
        val batchSize = 100
        var processed = 0
        while (processed < totalRecords) {
            delay(500)
            processed += batchSize
            Logger.d(LogTags.WORKER, "Android: Database progress: $processed/$totalRecords records")
        }
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Database", success = true, message = "Inserted $totalRecords records successfully"))
        return WorkerResult.Success(message = "Inserted $totalRecords records successfully")
    }
}

class NetworkRetryAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            delay(1000)
            if (attempt == maxAttempts) {
                val message = "Network request succeeded on attempt $attempt"
                TaskEventBus.emit(TaskCompletionEvent(taskName = "NetworkRetry", success = true, message = message))
                return WorkerResult.Success(message = message)
            }
            delay(2000L * (1 shl (attempt - 1)))
        }
        return WorkerResult.Failure("Network request failed after $maxAttempts attempts")
    }
}

class ImageProcessingAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val imageSizes = listOf("thumbnail", "medium", "large")
        val imageCount = 5
        for (imageNum in 1..imageCount) {
            for (size in imageSizes) {
                delay(600)
                Logger.d(LogTags.WORKER, "Android: Processing image $imageNum - $size")
            }
        }
        val message = "Processed $imageCount images in ${imageSizes.size} sizes"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "ImageProcessing", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}

class LocationSyncAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val locationPoints = 50
        val batchSize = 10
        var synced = 0
        while (synced < locationPoints) {
            delay(500)
            synced = minOf(synced + batchSize, locationPoints)
            Logger.d(LogTags.WORKER, "Android: Location sync: $synced/$locationPoints points")
        }
        val message = "Synced $locationPoints location points"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "LocationSync", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}

class CleanupAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        delay(800)
        val oldFiles = 127
        var deleted = 0
        var spaceFreed = 0L
        while (deleted < oldFiles) {
            delay(50)
            deleted++
            spaceFreed += (100..5000).random()
        }
        val message = "Deleted $oldFiles files, freed ${spaceFreed / 1024}MB"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Cleanup", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}

class BatchUploadAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val files = listOf("document.pdf" to 2, "photo.jpg" to 5, "video.mp4" to 15, "report.xlsx" to 1, "backup.zip" to 8)
        for ((name, size) in files) {
            var uploaded = 0
            while (uploaded < size) {
                delay(300)
                uploaded++
                Logger.d(LogTags.WORKER, "Android: $name: $uploaded/${size}MB")
            }
        }
        val totalSize = files.sumOf { it.second }
        val message = "Uploaded ${files.size} files (${totalSize}MB total)"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "BatchUpload", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}

class AnalyticsAndroidWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        delay(500)
        val eventCount = 243
        delay(600)
        delay(700)
        delay(1000)
        val compressedSize = (eventCount * 2 * 0.3).toInt()
        val message = "Synced $eventCount events (${compressedSize}KB)"
        TaskEventBus.emit(TaskCompletionEvent(taskName = "Analytics", success = true, message = message))
        return WorkerResult.Success(message = message)
    }
}
