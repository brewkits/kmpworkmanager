package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.IosEventStore
import kotlin.concurrent.Volatile

/**
 * iOS implementation of WorkerManagerInitializer.
 *
 * Initializes:
 * 1. WorkerManagerConfig with the provided WorkerFactory
 * 2. IosEventStore for task event persistence
 * 3. TaskEventManager for event handling
 * 4. NativeTaskScheduler for background task scheduling
 *
 * @since 2.1.0
 */
actual object WorkerManagerInitializer {
    @Volatile
    private var scheduler: BackgroundTaskScheduler? = null

    @Volatile
    private var eventStore: EventStore? = null

    actual fun initialize(
        workerFactory: WorkerFactory,
        context: Any?,
        iosTaskIds: Set<String>
    ): BackgroundTaskScheduler {
        check(scheduler == null) {
            "WorkerManagerInitializer already initialized. Call reset() first if re-initialization is needed."
        }

        // 1. Register factory globally
        WorkerManagerConfig.initialize(workerFactory)

        // 2. Initialize EventStore for task event persistence
        val store = IosEventStore()
        TaskEventManager.initialize(store)
        eventStore = store

        // 3. Create and cache scheduler
        val nativeScheduler = NativeTaskScheduler(
            additionalPermittedTaskIds = iosTaskIds
        )
        scheduler = nativeScheduler

        return nativeScheduler
    }

    actual fun getScheduler(): BackgroundTaskScheduler {
        return scheduler ?: error(
            "WorkerManagerInitializer not initialized. " +
            "Call WorkerManagerInitializer.initialize(factory, iosTaskIds = ...) first."
        )
    }

    actual fun isInitialized(): Boolean = scheduler != null

    actual fun reset() {
        scheduler = null
        eventStore = null
        WorkerManagerConfig.reset()
    }
}
