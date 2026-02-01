package dev.brewkits.kmpworkmanager.sample.background.workers

import dev.brewkits.kmpworkmanager.sample.background.data.IosWorker
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskCompletionEvent
import dev.brewkits.kmpworkmanager.sample.background.domain.TaskEventBus
import dev.brewkits.kmpworkmanager.sample.utils.Logger
import dev.brewkits.kmpworkmanager.sample.utils.LogTags
import kotlinx.coroutines.delay

/**
 * CPU-intensive image processing simulation with progress reporting
 */
class ImageProcessingWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        Logger.i(LogTags.WORKER, "ImageProcessingWorker started")

        try {
            val imageSizes = listOf("thumbnail", "medium", "large")
            val imageCount = 5

            Logger.i(LogTags.WORKER, "Processing $imageCount images in ${imageSizes.size} sizes")

            for (imageNum in 1..imageCount) {
                for ((sizeIndex, size) in imageSizes.withIndex()) {
                    delay(600) // Simulate CPU-intensive processing

                    val totalSteps = imageCount * imageSizes.size
                    val currentStep = (imageNum - 1) * imageSizes.size + sizeIndex + 1
                    val progress = (currentStep * 100) / totalSteps

                    Logger.i(
                        LogTags.WORKER,
                        "Processing image $imageNum - $size ($currentStep/$totalSteps, $progress%)"
                    )
                }
            }

            Logger.i(LogTags.WORKER, "ImageProcessingWorker completed successfully")

            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ImageProcessing",
                    success = true,
                    message = "🖼️ Processed $imageCount images in ${imageSizes.size} sizes"
                )
            )

            return true
        } catch (e: Exception) {
            Logger.e(LogTags.WORKER, "ImageProcessingWorker failed: ${e.message}", e)
            TaskEventBus.emit(
                TaskCompletionEvent(
                    taskName = "ImageProcessing",
                    success = false,
                    message = "❌ Image processing failed: ${e.message}"
                )
            )
            return false
        }
    }
}
