package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory

/**
 * Platform-specific initializer for KMP WorkManager.
 *
 * This provides a unified API for initializing the library on both Android and iOS
 * without requiring any DI framework.
 *
 * Usage (Android):
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val scheduler = WorkerManagerInitializer.initialize(
 *             workerFactory = MyWorkerFactory(),
 *             context = this
 *         )
 *     }
 * }
 * ```
 *
 * Usage (iOS):
 * ```kotlin
 * // In AppDelegate
 * fun initializeWorkManager() {
 *     WorkerManagerInitializer.initialize(
 *         workerFactory = MyWorkerFactory(),
 *         iosTaskIds = setOf("kmp-sync-task", "kmp-upload-task")
 *     )
 * }
 * ```
 *
 * @since 2.1.0
 */
expect object WorkerManagerInitializer {
    /**
     * Initializes KMP WorkManager with the provided WorkerFactory.
     *
     * **Android Parameters:**
     * - `context`: Application context (required)
     * - `workerFactory`: WorkerFactory implementation (must implement AndroidWorkerFactory)
     *
     * **iOS Parameters:**
     * - `workerFactory`: WorkerFactory implementation (must implement IosWorkerFactory)
     * - `iosTaskIds`: Set of task identifiers for background tasks (optional if defined in Info.plist)
     *
     * Thread-safe and idempotent - subsequent calls will throw an exception.
     *
     * @param workerFactory The WorkerFactory implementation to use
     * @param context Platform-specific context (Android only)
     * @param iosTaskIds Set of iOS task identifiers (iOS only, optional)
     * @return BackgroundTaskScheduler instance for scheduling tasks
     * @throws IllegalStateException if already initialized
     */
    fun initialize(
        workerFactory: WorkerFactory,
        context: Any? = null,
        iosTaskIds: Set<String> = emptySet()
    ): BackgroundTaskScheduler

    /**
     * Retrieves the initialized BackgroundTaskScheduler.
     *
     * @return The scheduler instance
     * @throws IllegalStateException if not initialized
     */
    fun getScheduler(): BackgroundTaskScheduler

    /**
     * Checks if WorkerManager has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    fun isInitialized(): Boolean

    /**
     * Resets the initializer state, allowing re-initialization.
     *
     * **Warning**: This is intended for testing only. Do not call in production code.
     * Calling this while tasks are running may cause undefined behavior.
     */
    fun reset()
}
