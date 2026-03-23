package dev.brewkits.kmpworkmanager.background.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Result type for Worker execution.
 *
 * This sealed class provides a rich return type for workers, allowing them to:
 * - Return success/failure status
 * - Include optional messages
 * - Pass output data back to the caller
 * - Control retry behavior
 *
 * v2.3.0+: Introduced to support returning data from workers
 * v2.3.7+: data changed from Map<String, Any?> to JsonObject for safe serialization
 *
 * Example:
 * ```kotlin
 * class DataFetchWorker : Worker {
 *     override suspend fun doWork(input: String?): WorkerResult {
 *         return try {
 *             val items = fetchData()
 *             WorkerResult.Success(
 *                 message = "Fetched ${items.size} items",
 *                 data = buildJsonObject {
 *                     put("count", items.size)
 *                     put("firstItem", items.firstOrNull()?.id ?: "")
 *                 }
 *             )
 *         } catch (e: Exception) {
 *             WorkerResult.Failure(
 *                 message = "Failed: ${e.message}",
 *                 shouldRetry = true
 *             )
 *         }
 *     }
 * }
 * ```
 */
sealed class WorkerResult {
    /**
     * Represents successful worker execution.
     *
     * @param message Optional success message
     * @param data Optional output data to be passed to listeners via TaskCompletionEvent.
     *             Use [buildJsonObject] to construct: `buildJsonObject { put("key", value) }`
     * @param dataClass Optional hint for the data class name (for future typed deserialization)
     */
    data class Success(
        val message: String? = null,
        val data: JsonObject? = null,
        val dataClass: String? = null
    ) : WorkerResult()

    /**
     * Represents failed worker execution.
     *
     * @param message Error message describing the failure
     * @param shouldRetry Whether the task should be retried (hint for future retry logic)
     */
    data class Failure(
        val message: String,
        val shouldRetry: Boolean = false
    ) : WorkerResult()
}
