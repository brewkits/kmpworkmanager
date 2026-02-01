package dev.brewkits.kmpworkmanager.sample.background.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.stats.TaskStatsManager
import kotlinx.coroutines.delay
import kotlin.time.measureTime

/**
 * A generic CoroutineWorker that acts as the entry point for all deferrable tasks.
 * Emits events to TaskEventBus for UI updates.
 */
class KmpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val workerClassName = inputData.getString("workerClassName") ?: return Result.failure()
        val taskName = workerClassName.substringAfterLast('.')
        val taskId = "$taskName-${id}"
        TaskStatsManager.recordTaskStart(taskId, taskName)

        val startTime = System.currentTimeMillis()
        return try {
            val result = when (workerClassName) {
                // Original workers
                WorkerTypes.SYNC_WORKER -> executeSyncWorker()
                WorkerTypes.UPLOAD_WORKER -> executeUploadWorker()
                "Inexact-Alarm" -> executeInexactAlarm()

                // New workers - Phase 2
                WorkerTypes.DATABASE_WORKER -> executeDatabaseWorker()
                WorkerTypes.NETWORK_RETRY_WORKER -> executeNetworkRetryWorker()
                WorkerTypes.IMAGE_PROCESSING_WORKER -> executeImageProcessingWorker()
                WorkerTypes.LOCATION_SYNC_WORKER -> executeLocationSyncWorker()
                WorkerTypes.CLEANUP_WORKER -> executeCleanupWorker()
                WorkerTypes.BATCH_UPLOAD_WORKER -> executeBatchUploadWorker()
                WorkerTypes.ANALYTICS_WORKER -> executeAnalyticsWorker()

                else -> {
                    println("🤖 Android: Unknown worker type: $workerClassName")
                    Result.failure()
                }
            }
            val duration = System.currentTimeMillis() - startTime
            TaskStatsManager.recordTaskComplete(taskId, result is Result.Success, duration)
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            TaskStatsManager.recordTaskComplete(taskId, false, duration)
            println("🤖 Android: Worker failed: ${e.message}")
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Task",
                    success = false,
                    message = "❌ Task failed: ${e.message}"
                )
            )
            Result.failure()
        }
    }

    private suspend fun executeSyncWorker(): Result {
        println("🤖 Android: Starting SYNC_WORKER...")

        val steps = listOf("Fetching data", "Processing", "Saving")
        for ((index, step) in steps.withIndex()) {
            println("🤖 Android: 📊 [$step] ${index + 1}/${steps.size}")
            delay(800)
            println("🤖 Android: ✓ [$step] completed")
        }

        println("🤖 Android: 🎉 SYNC_WORKER finished successfully")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Sync",
                success = true,
                message = "🔄 Data synced successfully"
            )
        )

        return Result.success()
    }

    private suspend fun executeUploadWorker(): Result {
        println("🤖 Android: Starting UPLOAD_WORKER...")

        val totalSize = 100
        var uploaded = 0

        println("🤖 Android: 📤 Starting upload of ${totalSize}MB...")

        while (uploaded < totalSize) {
            delay(300)
            uploaded += 10
            val progress = (uploaded * 100) / totalSize
            println("🤖 Android: 📊 Upload progress: $uploaded/$totalSize MB ($progress%)")
        }

        println("🤖 Android: 🎉 UPLOAD_WORKER finished successfully")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Upload",
                success = true,
                message = "📤 Uploaded ${totalSize}MB successfully"
            )
        )

        return Result.success()
    }

    private suspend fun executeInexactAlarm(): Result {
        println("🤖 Android: Starting Inexact-Alarm...")
        delay(1000)
        println("🤖 Android: 🎉 Inexact-Alarm completed")

        TaskEventBus.emit(
            TaskCompletionEvent(
                taskName = "Alarm",
                success = true,
                message = "⏰ Alarm triggered successfully"
            )
        )

        return Result.success()
    }

    // New workers - Phase 2

    private suspend fun executeDatabaseWorker(): Result {
        println("🤖 Android: Starting DATABASE_WORKER...")

        try {
            val totalRecords = 1000
            val batchSize = 100
            var processed = 0

            while (processed < totalRecords) {
                delay(500)

                if (kotlin.random.Random.nextFloat() < 0.1f) {
                    throw Exception("Database transaction failed (simulated error)")
                }

                processed += batchSize
                val progress = (processed * 100) / totalRecords
                println("🤖 Android: Database progress: $processed/$totalRecords records ($progress%)")
            }

            println("🤖 Android: 🎉 DATABASE_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Database",
                    success = true,
                    message = "💾 Inserted $totalRecords records successfully"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Database",
                    success = false,
                    message = "❌ Database operation failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }

    private suspend fun executeNetworkRetryWorker(): Result {
        println("🤖 Android: Starting NETWORK_RETRY_WORKER...")

        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++
            println("🤖 Android: Network request attempt $attempt/$maxAttempts")

            try {
                delay(1000)

                if (attempt < 3) {
                    throw Exception("Network timeout (simulated)")
                }

                println("🤖 Android: 🎉 NETWORK_RETRY_WORKER succeeded on attempt $attempt")

                TaskEventBus.emit(
                    TaskCompletionEvent(
                        taskName = "NetworkRetry",
                        success = true,
                        message = "🌐 Network request succeeded on attempt $attempt"
                    )
                )

                return Result.success()
            } catch (e: Exception) {
                println("🤖 Android: Attempt $attempt failed: ${e.message}")

                if (attempt < maxAttempts) {
                    val backoffDelay = 2000L * (1 shl (attempt - 1))
                    println("🤖 Android: Retrying in ${backoffDelay}ms...")
                    delay(backoffDelay)
                } else {
                    TaskEventBus.emit(
                        TaskCompletionEvent(
                            taskName = "NetworkRetry",
                            success = false,
                            message = "❌ Network request failed after $maxAttempts attempts"
                        )
                    )
                    return Result.failure()
                }
            }
        }

        return Result.failure()
    }

    private suspend fun executeImageProcessingWorker(): Result {
        println("🤖 Android: Starting IMAGE_PROCESSING_WORKER...")

        try {
            val imageSizes = listOf("thumbnail", "medium", "large")
            val imageCount = 5

            for (imageNum in 1..imageCount) {
                for ((sizeIndex, size) in imageSizes.withIndex()) {
                    delay(600)

                    val totalSteps = imageCount * imageSizes.size
                    val currentStep = (imageNum - 1) * imageSizes.size + sizeIndex + 1
                    val progress = (currentStep * 100) / totalSteps

                    println("🤖 Android: Processing image $imageNum - $size ($currentStep/$totalSteps, $progress%)")
                }
            }

            println("🤖 Android: 🎉 IMAGE_PROCESSING_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ImageProcessing",
                    success = true,
                    message = "🖼️ Processed $imageCount images in ${imageSizes.size} sizes"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ImageProcessing",
                    success = false,
                    message = "❌ Image processing failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }

    private suspend fun executeLocationSyncWorker(): Result {
        println("🤖 Android: Starting LOCATION_SYNC_WORKER...")

        try {
            val locationPoints = 50
            val batchSize = 10
            var synced = 0

            while (synced < locationPoints) {
                delay(500)

                val batchEnd = minOf(synced + batchSize, locationPoints)
                synced = batchEnd

                val progress = (synced * 100) / locationPoints
                println("🤖 Android: Uploaded batch: $synced/$locationPoints points ($progress%)")
            }

            println("🤖 Android: 🎉 LOCATION_SYNC_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "LocationSync",
                    success = true,
                    message = "📍 Synced $locationPoints location points"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "LocationSync",
                    success = false,
                    message = "❌ Location sync failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }

    private suspend fun executeCleanupWorker(): Result {
        println("🤖 Android: Starting CLEANUP_WORKER...")

        try {
            println("🤖 Android: Scanning cache directories...")
            delay(800)

            val oldFiles = 127
            println("🤖 Android: Found $oldFiles old cache files to delete")

            var deleted = 0
            var spaceFreed = 0L

            while (deleted < oldFiles) {
                delay(50)
                deleted++
                spaceFreed += (100..5000).random()

                if (deleted % 20 == 0 || deleted == oldFiles) {
                    val progress = (deleted * 100) / oldFiles
                    val spaceMB = spaceFreed / 1024
                    println("🤖 Android: Cleanup progress: $deleted/$oldFiles files ($progress%), ${spaceMB}MB freed")
                }
            }

            val finalSpaceMB = spaceFreed / 1024
            println("🤖 Android: 🎉 CLEANUP_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Cleanup",
                    success = true,
                    message = "🧹 Deleted $oldFiles files, freed ${finalSpaceMB}MB"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Cleanup",
                    success = false,
                    message = "❌ Cleanup failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }

    private suspend fun executeBatchUploadWorker(): Result {
        println("🤖 Android: Starting BATCH_UPLOAD_WORKER...")

        try {
            val fileNames = listOf("document.pdf", "photo.jpg", "video.mp4", "report.xlsx", "backup.zip")
            val fileSizes = listOf(2, 5, 15, 1, 8)

            for ((index, fileName) in fileNames.withIndex()) {
                val fileSize = fileSizes[index]
                println("🤖 Android: Uploading file ${index + 1}/${fileNames.size}: $fileName (${fileSize}MB)")

                var uploaded = 0
                while (uploaded < fileSize) {
                    delay(300)
                    uploaded++
                    val fileProgress = (uploaded * 100) / fileSize
                    println("🤖 Android:   → $fileName: $uploaded/${fileSize}MB ($fileProgress%)")
                }

                val overallProgress = ((index + 1) * 100) / fileNames.size
                println("🤖 Android: Completed $fileName. Overall: ${index + 1}/${fileNames.size} ($overallProgress%)")
            }

            val totalSize = fileSizes.sum()
            println("🤖 Android: 🎉 BATCH_UPLOAD_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "BatchUpload",
                    success = true,
                    message = "📤 Uploaded ${fileNames.size} files (${totalSize}MB total)"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "BatchUpload",
                    success = false,
                    message = "❌ Batch upload failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }

    private suspend fun executeAnalyticsWorker(): Result {
        println("🤖 Android: Starting ANALYTICS_WORKER...")

        try {
            println("🤖 Android: Collecting pending analytics events...")
            delay(500)

            val eventCount = 243
            println("🤖 Android: Found $eventCount pending events")

            println("🤖 Android: Batching events...")
            delay(600)
            val batchCount = (eventCount + 49) / 50
            println("🤖 Android: Created $batchCount batches")

            println("🤖 Android: Compressing payload...")
            delay(700)
            val originalSize = eventCount * 2
            val compressedSize = (originalSize * 0.3).toInt()
            println("🤖 Android: Compressed ${originalSize}KB → ${compressedSize}KB")

            println("🤖 Android: Uploading to analytics server...")
            delay(1000)
            println("🤖 Android: Upload complete")

            println("🤖 Android: 🎉 ANALYTICS_WORKER completed")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Analytics",
                    success = true,
                    message = "📊 Synced $eventCount events (${compressedSize}KB)"
                )
            )

            return Result.success()
        } catch (e: Exception) {
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "Analytics",
                    success = false,
                    message = "❌ Analytics sync failed: ${e.message}"
                )
            )
            return Result.failure()
        }
    }
}