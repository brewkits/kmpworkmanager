package io.brewkits.kmpworkmanager

import io.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import io.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import io.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import io.brewkits.kmpworkmanager.background.domain.TaskEventManager
import io.brewkits.kmpworkmanager.background.domain.WorkerFactory
import io.brewkits.kmpworkmanager.persistence.EventStore
import io.brewkits.kmpworkmanager.persistence.IosEventStore
import org.koin.dsl.module

/**
 * iOS implementation of the Koin module.
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 *
 * Usage:
 * ```kotlin
 * // Basic usage
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpWorkerModule(
 *             workerFactory = MyWorkerFactory()
 *         ))
 *     }
 * }
 *
 * // With additional task IDs (optional - reads from Info.plist automatically)
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpWorkerModule(
 *             workerFactory = MyWorkerFactory(),
 *             iosTaskIds = setOf("my-sync-task", "my-upload-task")
 *         ))
 *     }
 * }
 * ```
 *
 * @param workerFactory User-provided factory implementing IosWorkerFactory
 * @param iosTaskIds Additional iOS task IDs (optional, Info.plist is primary source)
 */
actual fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    iosTaskIds: Set<String>
) = module {
    // Validate factory type early (fail-fast on iOS)
    require(workerFactory is IosWorkerFactory) {
        """
        ❌ Invalid WorkerFactory for iOS platform

        Expected: IosWorkerFactory
        Received: ${workerFactory::class.qualifiedName}

        Solution:
        Create a factory implementing IosWorkerFactory on iOS:

        class MyWorkerFactory : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                return when (workerClassName) {
                    "SyncWorker" -> SyncWorker()
                    else -> null
                }
            }
        }

        Then pass it to kmpWorkerModule:
        kmpWorkerModule(workerFactory = MyWorkerFactory())
        """.trimIndent()
    }

    single<BackgroundTaskScheduler> {
        NativeTaskScheduler(additionalPermittedTaskIds = iosTaskIds)
    }

    // Register the user's worker factory (already validated above)
    single<WorkerFactory> { workerFactory }
    single<IosWorkerFactory> { workerFactory }

    // Event persistence
    single<EventStore> {
        val store = IosEventStore()

        // Initialize TaskEventManager with the store
        TaskEventManager.initialize(store)

        store
    }
}
