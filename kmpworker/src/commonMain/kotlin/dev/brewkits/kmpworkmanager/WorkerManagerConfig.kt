package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import kotlin.concurrent.Volatile

/**
 * Global configuration singleton for KMP WorkManager.
 *
 * This provides a DI-agnostic service locator pattern for registering the WorkerFactory.
 * Eliminates the need for Koin or any other DI framework in the core library.
 *
 * Usage (Manual Registration):
 * ```kotlin
 * // In Application.onCreate() or AppDelegate
 * WorkerManagerConfig.initialize(MyWorkerFactory())
 * ```
 *
 * Usage (With DI):
 * ```kotlin
 * // DI extensions (koin/hilt) will call initialize() automatically
 * // You don't need to call this directly
 * ```
 *
 * @since 2.1.0
 */
object WorkerManagerConfig {
    @Volatile
    private var workerFactory: WorkerFactory? = null

    /**
     * Initializes the global WorkerFactory.
     *
     * Must be called once before scheduling any tasks from the main thread.
     *
     * **Thread Safety**: This method should be called from a single thread (typically main/UI thread)
     * during app initialization. The @Volatile annotation ensures visibility across threads after initialization.
     *
     * @param factory The WorkerFactory implementation to use
     * @throws IllegalStateException if already initialized
     */
    fun initialize(factory: WorkerFactory) {
        check(workerFactory == null) {
            "WorkerManagerConfig already initialized. Call reset() first if re-initialization is needed."
        }
        workerFactory = factory
    }

    /**
     * Retrieves the global WorkerFactory instance.
     *
     * @return The registered WorkerFactory
     * @throws IllegalStateException if not initialized
     */
    fun getWorkerFactory(): WorkerFactory {
        return workerFactory ?: error(
            "WorkerManagerConfig not initialized. " +
            "Call WorkerManagerConfig.initialize(factory) or use a DI extension module " +
            "(kmpworkmanager-koin or kmpworkmanager-hilt)."
        )
    }

    /**
     * Checks if the WorkerFactory has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    fun isInitialized(): Boolean = workerFactory != null

    /**
     * Resets the configuration, allowing re-initialization.
     *
     * **Warning**: This is intended for testing only. Do not call in production code.
     * Calling this while tasks are running may cause undefined behavior.
     */
    fun reset() {
        workerFactory = null
    }
}
