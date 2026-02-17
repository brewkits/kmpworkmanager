package dev.brewkits.kmpworkmanager

import android.content.Context
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.AndroidEventStore
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.utils.Logger
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Private Koin instance for KMP WorkManager (v2.2.2+)
 *
 * **Breaking Change:** Isolated Koin to prevent conflicts with host app's Koin.
 *
 * **Problem (v2.2.1):**
 * - Used global Koin (startKoin)
 * - Conflicts with host app's Koin = instant crash
 * - "A KoinApplication has already been started" error
 *
 * **Solution (v2.2.2+):**
 * - Private KoinApplication (not global)
 * - Host app's Koin is completely independent
 * - No conflicts, no crashes
 *
 * **Migration:**
 * ```kotlin
 * // OLD (v2.2.1 and earlier)
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpWorkerModule(workerFactory = MyWorkerFactory()))
 * }
 *
 * // NEW (v2.2.2+)
 * KmpWorkManager.initialize(
 *     context = this@Application,
 *     workerFactory = MyWorkerFactory(),
 *     config = KmpWorkManagerConfig(logLevel = Logger.Level.INFO)
 * )
 * ```
 */
internal object KmpWorkManagerKoin {
    private lateinit var koinApp: KoinApplication
    @Volatile
    private var isInitialized = false
    private val initLock = Any()

    /**
     * Initialize private Koin instance (thread-safe)
     *
     * @param context Android application context
     * @param workerFactory Worker factory implementation
     * @param config Configuration for logging and other settings
     * @throws IllegalStateException if already initialized with throw=true
     */
    fun initialize(
        context: Context,
        workerFactory: WorkerFactory,
        config: KmpWorkManagerConfig = KmpWorkManagerConfig(),
        throwOnDuplicate: Boolean = false
    ) {
        // Double-checked locking pattern for thread safety
        if (isInitialized) {
            if (throwOnDuplicate) {
                throw IllegalStateException("KmpWorkManager already initialized")
            }
            Logger.w("KmpWorkManager", "Already initialized - ignoring duplicate call")
            return
        }

        synchronized(initLock) {
            // Check again inside lock (double-checked locking)
            if (isInitialized) {
                if (throwOnDuplicate) {
                    throw IllegalStateException("KmpWorkManager already initialized")
                }
                Logger.w("KmpWorkManager", "Already initialized - ignoring duplicate call")
                return
            }

            // Initialize logger with config
            Logger.setMinLevel(config.logLevel)
            config.customLogger?.let { Logger.setCustomLogger(it) }

            // Create private Koin instance (not global!)
            koinApp = koinApplication {
                modules(
                    module {
                        // Context
                        single<Context> { context }

                        // Worker factory
                        single<WorkerFactory> { workerFactory }
                        single<AndroidWorkerFactory> {
                            workerFactory as? AndroidWorkerFactory
                                ?: error("WorkerFactory must implement AndroidWorkerFactory on Android")
                        }

                        // Task scheduler
                        single<BackgroundTaskScheduler> {
                            NativeTaskScheduler(get())
                        }

                        // Event persistence
                        single<EventStore> {
                            val store = AndroidEventStore(get())
                            TaskEventManager.initialize(store)
                            store
                        }
                    }
                )
            }

            isInitialized = true
            Logger.i("KmpWorkManager", "✅ Initialized with private Koin (isolated from host app)")
        }
    }

    /**
     * Shutdown KmpWorkManager and release resources (thread-safe)
     * Useful for testing or when reinitializing with different configuration
     */
    fun shutdown() {
        synchronized(initLock) {
            if (!isInitialized) {
                Logger.w("KmpWorkManager", "Not initialized - nothing to shutdown")
                return
            }

            try {
                koinApp.close()
                Logger.i("KmpWorkManager", "✅ Shutdown complete - Koin resources released")
            } catch (e: Exception) {
                Logger.e("KmpWorkManager", "Error during shutdown", e)
            } finally {
                isInitialized = false
            }
        }
    }

    /**
     * Get private Koin instance
     * @throws IllegalStateException if not initialized
     */
    fun getKoin(): Koin {
        if (!isInitialized) {
            throw IllegalStateException(
                """
                KmpWorkManager not initialized!

                Call KmpWorkManager.initialize() in your Application.onCreate():

                KmpWorkManager.initialize(
                    context = this,
                    workerFactory = MyWorkerFactory()
                )
                """.trimIndent()
            )
        }
        return koinApp.koin
    }

    /**
     * Check if initialized (thread-safe)
     */
    fun isInitialized(): Boolean {
        // Volatile read ensures visibility across threads
        return isInitialized
    }
}

/**
 * Public API for KmpWorkManager initialization (v2.2.2+)
 *
 * **Usage:**
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *
 *         // Initialize KmpWorkManager
 *         KmpWorkManager.initialize(
 *             context = this,
 *             workerFactory = MyWorkerFactory(),
 *             config = KmpWorkManagerConfig(
 *                 logLevel = Logger.Level.INFO  // Production-friendly
 *             )
 *         )
 *     }
 * }
 * ```
 */
object KmpWorkManager {
    /**
     * Initialize KmpWorkManager with isolated Koin
     *
     * @param context Android application context
     * @param workerFactory Worker factory implementation
     * @param config Configuration for logging and other settings
     */
    fun initialize(
        context: Context,
        workerFactory: WorkerFactory,
        config: KmpWorkManagerConfig = KmpWorkManagerConfig()
    ) {
        KmpWorkManagerKoin.initialize(context, workerFactory, config)
    }

    /**
     * Check if KmpWorkManager is initialized
     */
    fun isInitialized(): Boolean = KmpWorkManagerKoin.isInitialized()

    /**
     * Get KmpWorkManager instance for accessing services
     *
     * @return KmpWorkManagerInstance with access to backgroundTaskScheduler
     * @throws IllegalStateException if not initialized
     */
    fun getInstance(): KmpWorkManagerInstance {
        return KmpWorkManagerInstance(KmpWorkManagerKoin.getKoin())
    }

    /**
     * Shutdown KmpWorkManager and release all resources
     *
     * **Use Cases:**
     * - Test cleanup between test runs
     * - App logout/user switch scenarios
     * - When reinitializing with different configuration
     *
     * **Example:**
     * ```kotlin
     * // Cleanup in tests
     * @After
     * fun tearDown() {
     *     KmpWorkManager.shutdown()
     * }
     * ```
     */
    fun shutdown() {
        KmpWorkManagerKoin.shutdown()
    }
}

/**
 * Instance wrapper for KmpWorkManager providing access to services
 */
class KmpWorkManagerInstance internal constructor(private val koin: Koin) {
    /**
     * Get the background task scheduler for enqueuing tasks
     */
    val backgroundTaskScheduler: BackgroundTaskScheduler
        get() = koin.get()
}
