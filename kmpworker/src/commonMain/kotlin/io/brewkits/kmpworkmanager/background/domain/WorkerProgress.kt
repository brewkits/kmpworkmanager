package io.brewkits.kmpworkmanager.background.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Represents the progress of a background task.
 *
 * Workers can report progress to provide real-time feedback to the UI,
 * especially important for long-running operations like:
 * - File downloads/uploads
 * - Data processing
 * - Batch operations
 * - Image compression
 *
 * **Usage in Worker:**
 * ```kotlin
 * class FileDownloadWorker(
 *     private val progressListener: ProgressListener?
 * ) : Worker {
 *     override suspend fun doWork(input: String?): Boolean {
 *         val totalBytes = getTotalFileSize()
 *         var downloaded = 0L
 *
 *         while (downloaded < totalBytes) {
 *             val chunk = downloadChunk()
 *             downloaded += chunk.size
 *
 *             val progress = (downloaded * 100 / totalBytes).toInt()
 *             progressListener?.onProgressUpdate(
 *                 WorkerProgress(
 *                     progress = progress,
 *                     message = "Downloaded $downloaded / $totalBytes bytes"
 *                 )
 *             )
 *         }
 *
 *         return true
 *     }
 * }
 * ```
 *
 * **Usage in UI:**
 * ```kotlin
 * val progressFlow = TaskEventBus.events.filterIsInstance<TaskProgressEvent>()
 *
 * LaunchedEffect(Unit) {
 *     progressFlow.collect { event ->
 *         progressBar.value = event.progress.progress
 *         statusText.value = event.progress.message
 *     }
 * }
 * ```
 *
 * @property progress Progress percentage (0-100)
 * @property message Optional human-readable progress message
 * @property currentStep Optional current step in multi-step process
 * @property totalSteps Optional total number of steps
 */
@Serializable
data class WorkerProgress(
    val progress: Int,
    val message: String? = null,
    val currentStep: Int? = null,
    val totalSteps: Int? = null
) {
    init {
        require(progress in 0..100) {
            "Progress must be between 0 and 100, got $progress"
        }

        if (currentStep != null && totalSteps != null) {
            require(currentStep in 1..totalSteps) {
                "currentStep ($currentStep) must be between 1 and totalSteps ($totalSteps)"
            }
        }
    }

    /**
     * Get a formatted progress string for display.
     *
     * Examples:
     * - "50%"
     * - "50% - Downloading file"
     * - "Step 3/5 - Processing data"
     */
    fun toDisplayString(): String {
        return buildString {
            if (currentStep != null && totalSteps != null) {
                append("Step $currentStep/$totalSteps")
            } else {
                append("$progress%")
            }

            if (message != null) {
                append(" - $message")
            }
        }
    }

    companion object {
        /**
         * Create progress for a specific step in a multi-step process.
         */
        fun forStep(step: Int, totalSteps: Int, message: String? = null): WorkerProgress {
            val progress = ((step - 1) * 100 / totalSteps).coerceIn(0, 100)
            return WorkerProgress(
                progress = progress,
                message = message,
                currentStep = step,
                totalSteps = totalSteps
            )
        }
    }
}

/**
 * Interface for receiving progress updates from workers.
 *
 * This is typically implemented by the platform-specific scheduler
 * to emit progress events to the UI via TaskEventBus.
 */
interface ProgressListener {
    /**
     * Called when a worker reports progress.
     *
     * @param progress The current progress state
     */
    fun onProgressUpdate(progress: WorkerProgress)
}

/**
 * Event emitted when a task reports progress.
 *
 * Subscribe to this via TaskProgressBus to receive real-time progress updates in the UI.
 *
 * @property taskId The ID of the task reporting progress
 * @property taskName The name/class of the worker
 * @property progress The progress information
 */
@Serializable
data class TaskProgressEvent(
    val taskId: String,
    val taskName: String,
    val progress: WorkerProgress
)

/**
 * Global event bus for task progress events.
 * Workers can emit progress updates here, and the UI can listen to them in real-time.
 *
 * Configuration:
 * - replay=1: Keeps the last progress update for late subscribers
 * - extraBufferCapacity=32: Buffer for rapid progress updates
 *
 * **Usage in UI:**
 * ```kotlin
 * LaunchedEffect(Unit) {
 *     TaskProgressBus.events.collect { event ->
 *         when (event.taskId) {
 *             "my-task" -> {
 *                 progressBar.value = event.progress.progress / 100f
 *                 statusText.value = event.progress.message
 *             }
 *         }
 *     }
 * }
 * ```
 */
object TaskProgressBus {
    private val _events = MutableSharedFlow<TaskProgressEvent>(
        replay = 1,  // Keep last progress event for each task
        extraBufferCapacity = 32
    )
    val events: SharedFlow<TaskProgressEvent> = _events.asSharedFlow()

    suspend fun emit(event: TaskProgressEvent) {
        _events.tryEmit(event)
    }
}
