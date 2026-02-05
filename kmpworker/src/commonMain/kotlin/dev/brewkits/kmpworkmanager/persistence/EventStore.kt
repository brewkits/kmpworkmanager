package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import kotlinx.serialization.Serializable

/**
 * Persistent storage for task completion events.
 *
 * Events are stored to survive app restarts and force-quits,
 * ensuring no event loss when UI is not actively listening.
 *
 * Implementation Strategy:
 * - Android: SQLDelight with SQLite database
 * - iOS: File-based storage (IosFileStorage) for consistency
 *
 * Lifecycle:
 * 1. Worker completes task → emit to EventBus + saveEvent()
 * 2. App launches → getUnconsumedEvents() + replay to EventBus
 * 3. UI processes event → markEventConsumed()
 * 4. Periodic cleanup → clearOldEvents()
 *
 * Performance:
 * - Target: <100ms for getUnconsumedEvents()
 * - Auto-cleanup events older than 7 days
 * - Maximum 1000 events stored
 */
interface EventStore {

    /**
     * Saves an event to persistent storage.
     *
     * @param event The event to save
     * @return Unique event ID for tracking
     */
    suspend fun saveEvent(event: TaskCompletionEvent): String

    /**
     * Retrieves all events that have not been consumed by the UI.
     *
     * Events are ordered by timestamp (oldest first).
     *
     * @return List of unconsumed events
     */
    suspend fun getUnconsumedEvents(): List<StoredEvent>

    /**
     * Marks an event as consumed by the UI.
     *
     * Consumed events are eligible for cleanup but remain in storage
     * for a grace period (configurable, default 1 hour).
     *
     * @param eventId The ID of the event to mark as consumed
     */
    suspend fun markEventConsumed(eventId: String)

    /**
     * Removes events older than the specified time.
     *
     * @param olderThanMs Timestamp in milliseconds (events older than this are deleted)
     * @return Number of events deleted
     */
    suspend fun clearOldEvents(olderThanMs: Long): Int

    /**
     * Deletes all events from storage.
     * Use with caution - primarily for testing.
     */
    suspend fun clearAll()

    /**
     * Returns the total number of events in storage.
     * Useful for monitoring and debugging.
     */
    suspend fun getEventCount(): Int
}

/**
 * Event with additional metadata for persistence.
 *
 * @property id Unique identifier for this event
 * @property event The actual task completion event
 * @property timestamp When the event was created (milliseconds since epoch)
 * @property consumed Whether the UI has processed this event
 */
@Serializable
data class StoredEvent(
    val id: String,
    val event: TaskCompletionEvent,
    val timestamp: Long,
    val consumed: Boolean = false
)

/**
 * Configuration for event storage behavior.
 */
data class EventStoreConfig(
    /**
     * Maximum number of events to keep in storage.
     * Oldest events are deleted when limit is exceeded.
     */
    val maxEvents: Int = 1000,

    /**
     * How long to keep consumed events (in milliseconds).
     * Default: 1 hour
     */
    val consumedEventRetentionMs: Long = 3_600_000L, // 1 hour

    /**
     * How long to keep unconsumed events (in milliseconds).
     * Default: 7 days
     */
    val unconsumedEventRetentionMs: Long = 7 * 24 * 3_600_000L, // 7 days

    /**
     * Whether to auto-cleanup on each write operation.
     * If false, cleanup must be triggered manually.
     */
    val autoCleanup: Boolean = true,

    /**
     * FIX: Deterministic cleanup interval (v2.2.2+)
     * Minimum time between cleanup runs (in milliseconds).
     * Default: 5 minutes (300000ms)
     * Replaces probabilistic 10% cleanup with time-based strategy.
     */
    val cleanupIntervalMs: Long = 300_000L, // 5 minutes

    /**
     * FIX: File size threshold for cleanup (v2.2.2+)
     * Trigger cleanup when file size exceeds this threshold (in bytes).
     * Default: 1MB (1048576 bytes)
     */
    val cleanupFileSizeThresholdBytes: Long = 1_048_576L // 1MB
)
