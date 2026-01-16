package dev.brewkits.kmpworkmanager.koin

import dev.brewkits.kmpworkmanager.WorkerManagerInitializer
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * iOS implementation of the Koin module for KMP WorkManager.
 *
 * This module uses WorkerManagerInitializer to set up the scheduler,
 * maintaining backward compatibility with v2.0.0 while using the new DI-agnostic core.
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
 * @since 2.1.0
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
        // Use WorkerManagerInitializer to set up everything
        WorkerManagerInitializer.initialize(
            workerFactory = workerFactory,
            iosTaskIds = iosTaskIds
        )
    }

    // Register the user's worker factory for direct injection if needed
    single<WorkerFactory> { workerFactory }
    single<IosWorkerFactory> { workerFactory }
}
