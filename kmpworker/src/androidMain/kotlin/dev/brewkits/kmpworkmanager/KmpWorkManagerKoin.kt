package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.KmpHeavyWorker
import dev.brewkits.kmpworkmanager.background.data.KmpWorker
import dev.brewkits.kmpworkmanager.background.data.KmpWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.AndroidEventStore
import dev.brewkits.kmpworkmanager.persistence.AndroidExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.utils.Logger
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Private Koin instance for KMP WorkManager
 *
 * **Breaking Change:** Isolated Koin to prevent conflicts with host app's Koin.
 *
 * **Problem:**
 * - Used global Koin (startKoin)
 * - Conflicts with host app's Koin = instant crash
 * - "A KoinApplication has already been started" error
 *
 * **Solution:**
 * - Private KoinApplication (not global)
 * - Host app's Koin is completely independent
 * - No conflicts, no crashes
 *
 * **Migration:**
 * ```kotlin
 * // OLD
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpWorkerModule(workerFactory = MyWorkerFactory()))
 * }
 *
 * // NEW
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

            // Propagate runtime-accessible config (telemetry, battery guard, etc.)
            KmpWorkManagerRuntime.configure(config)

            // Propagate optional foreground notification title to KmpWorker
            dev.brewkits.kmpworkmanager.background.data.BaseKmpWorker.configNotificationTitle = config.androidForegroundNotificationTitle

            // Register KmpWorkerFactory with WorkManager so KmpWorker / KmpHeavyWorker receive
            // AndroidWorkerFactory via constructor injection instead of a Service Locator lookup.
            // Only attempted when WorkManager has not yet been initialized by the host app.
            val androidWorkerFactory = workerFactory as? AndroidWorkerFactory
            if (androidWorkerFactory != null) {
                if (!WorkManager.isInitialized()) {
                    val delegating = DelegatingWorkerFactory()
                    delegating.addFactory(KmpWorkerFactory(androidWorkerFactory))
                    WorkManager.initialize(
                        context,
                        Configuration.Builder()
                            .setWorkerFactory(delegating)
                            .build()
                    )
                    Logger.i("KmpWorkManager", "✅ WorkManager initialized with KmpWorkerFactory")
                } else {
                    Logger.w(
                        "KmpWorkManager",
                        "WorkManager already initialized by host app — KmpWorkerFactory not registered. " +
                            "Add KmpWorkerFactory to your DelegatingWorkerFactory to eliminate the Koin " +
                            "Service Locator fallback. See KmpWorkerFactory KDoc for setup instructions."
                    )
                }
            }

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

                        // Execution history persistence — created eagerly so
                        // KmpWorkManagerRuntime.executionHistoryStore is set before any worker runs.
                        single<ExecutionHistoryStore>(createdAtStart = true) {
                            AndroidExecutionHistoryStore(get()).also {
                                KmpWorkManagerRuntime.setHistoryStore(it)
                            }
                        }
                    }
                )
            }

            // Cleanup stale overflow temp files from previous sessions.
            // If the app was force-killed between spilling the file and the worker's finally block,
            // the file is left orphaned in cacheDir. Clean up files older than 24 h at init time
            // when no workers are running yet, so there's no risk of racing with an active worker.
            cleanupStaleOverflowFiles(context)

            isInitialized = true
            Logger.i("KmpWorkManager", "✅ Initialized with private Koin (isolated from host app)")
        }
    }

    private fun cleanupStaleOverflowFiles(context: Context) {
        try {
            val maxAgeMs = 24 * 60 * 60 * 1000L
            val now = System.currentTimeMillis()
            val deleted = context.cacheDir
                .listFiles { file -> file.name.startsWith("kmp_input_") && file.name.endsWith(".json") }
                ?.count { file ->
                    val stale = now - file.lastModified() > maxAgeMs
                    if (stale) file.delete() else false
                } ?: 0
            if (deleted > 0) {
                Logger.d("KmpWorkManager", "Cleaned up $deleted stale overflow file(s) from cacheDir")
            }
        } catch (e: Exception) {
            Logger.w("KmpWorkManager", "Error cleaning up stale overflow files", e)
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
 * Public API for KmpWorkManager initialization
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
     * Get KmpWorkManager instance
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
 * KmpWorkManager instance providing access to scheduler and other services
 */
class KmpWorkManagerInstance internal constructor(private val koin: Koin) {
    /**
     * Background task scheduler for enqueuing and managing tasks
     */
    val backgroundTaskScheduler: BackgroundTaskScheduler
        get() = koin.get()

    /**
     * Returns the most recent task execution records, newest first.
     *
     * Records persist locally across app launches. Call this when the app foregrounds
     * and upload to your analytics backend, then call [clearExecutionHistory] to free
     * disk space.
     *
     * @param limit Maximum number of records to return. Defaults to 100.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord> =
        backgroundTaskScheduler.getExecutionHistory(limit)

    /**
     * Deletes all locally stored execution history records.
     * Call after a successful server upload to free disk space.
     */
    suspend fun clearExecutionHistory() = backgroundTaskScheduler.clearExecutionHistory()
}
