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
    single<BackgroundTaskScheduler> {
        NativeTaskScheduler(additionalPermittedTaskIds = iosTaskIds)
    }

    // Register the user's worker factory
    single<WorkerFactory> { workerFactory }
    single<IosWorkerFactory> {
        workerFactory as? IosWorkerFactory
            ?: error("WorkerFactory must implement IosWorkerFactory on iOS")
    }
}
