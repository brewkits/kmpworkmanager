package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory

/**
 * Platform-specific accessor for retrieving the IosWorkerFactory on iOS.
 *
 * This ensures type safety - the registered WorkerFactory must implement IosWorkerFactory
 * on the iOS platform.
 *
 * Internal usage only - used by ChainExecutor and SingleTaskExecutor.
 *
 * @since 2.1.0
 */
object IosWorkerFactoryProvider {
    /**
     * Retrieves the iOS-specific WorkerFactory.
     *
     * @return The registered IosWorkerFactory instance
     * @throws IllegalStateException if WorkerManagerConfig is not initialized
     * @throws IllegalArgumentException if the registered factory is not an IosWorkerFactory
     */
    fun getIosWorkerFactory(): IosWorkerFactory {
        val factory: WorkerFactory = WorkerManagerConfig.getWorkerFactory()

        require(factory is IosWorkerFactory) {
            "On iOS, WorkerFactory must implement IosWorkerFactory. " +
            "Found: ${factory::class.simpleName}"
        }

        return factory
    }
}
