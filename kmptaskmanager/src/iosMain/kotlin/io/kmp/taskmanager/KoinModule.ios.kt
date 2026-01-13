package io.kmp.taskmanager

import io.kmp.taskmanager.background.data.IosWorkerFactory
import io.kmp.taskmanager.background.data.NativeTaskScheduler
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.background.domain.WorkerFactory
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
 *         modules(kmpTaskManagerModule(
 *             workerFactory = MyWorkerFactory()
 *         ))
 *     }
 * }
 *
 * // With additional task IDs (optional - reads from Info.plist automatically)
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpTaskManagerModule(
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
actual fun kmpTaskManagerModule(
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

        Then pass it to kmpTaskManagerModule:
        kmpTaskManagerModule(workerFactory = MyWorkerFactory())
        """.trimIndent()
    }

    single<BackgroundTaskScheduler> {
        NativeTaskScheduler(additionalPermittedTaskIds = iosTaskIds)
    }

    // Register the user's worker factory (already validated above)
    single<WorkerFactory> { workerFactory }
    single<IosWorkerFactory> { workerFactory }
}
