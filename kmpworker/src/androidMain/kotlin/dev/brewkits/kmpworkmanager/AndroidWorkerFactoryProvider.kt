package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory

/**
 * Platform-specific accessor for retrieving the AndroidWorkerFactory on Android.
 *
 * This ensures type safety - the registered WorkerFactory must implement AndroidWorkerFactory
 * on the Android platform.
 *
 * Internal usage only - used by KmpWorker and KmpHeavyWorker.
 *
 * @since 2.1.0
 */
object AndroidWorkerFactoryProvider {
    /**
     * Retrieves the Android-specific WorkerFactory.
     *
     * @return The registered AndroidWorkerFactory instance
     * @throws IllegalStateException if WorkerManagerConfig is not initialized
     * @throws IllegalArgumentException if the registered factory is not an AndroidWorkerFactory
     */
    fun getAndroidWorkerFactory(): AndroidWorkerFactory {
        val factory: WorkerFactory = WorkerManagerConfig.getWorkerFactory()

        require(factory is AndroidWorkerFactory) {
            "On Android, WorkerFactory must implement AndroidWorkerFactory. " +
            "Found: ${factory::class.simpleName}"
        }

        return factory
    }
}
