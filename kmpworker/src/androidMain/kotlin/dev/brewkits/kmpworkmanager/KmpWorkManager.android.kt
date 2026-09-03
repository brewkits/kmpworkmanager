package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.KmpWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.AndroidEventStore
import dev.brewkits.kmpworkmanager.persistence.AndroidExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android service registry — the Koin-free replacement for the private `koinApplication`
 * that used to live here.
 *
 * Each service is a `by lazy` singleton, which reproduces Koin's `single { }` semantics
 * (created once, on first access, thread-safely) without a DI container. The two
 * persistence stores are deliberately **not** lazy — see [createEagerServices].
 */
internal class AndroidServiceRegistry(
    val context: Context,
    val workerFactory: WorkerFactory
) {
    val androidWorkerFactory: AndroidWorkerFactory by lazy {
        workerFactory as? AndroidWorkerFactory
            ?: error("WorkerFactory must implement AndroidWorkerFactory on Android")
    }

    val backgroundTaskScheduler: BackgroundTaskScheduler by lazy {
        NativeTaskScheduler(context)
    }

    val eventStore: EventStore by lazy { AndroidEventStore(context) }

    val executionHistoryStore: ExecutionHistoryStore by lazy {
        AndroidExecutionHistoryStore(context)
    }

    /**
     * Instantiates the services that must exist before any worker runs, and publishes them
     * to the global runtime hooks that workers read from.
     *
     * LIFECYCLE: both stores register themselves through a global side channel —
     * [TaskEventManager.initialize] and [KmpWorkManagerRuntime.setHistoryStore] — which
     * workers consult via nullable reads. `ExecutionHistoryStore` already carried
     * `createdAtStart = true` in the Koin module for exactly this reason; `EventStore` did
     * not, so events went unpersisted (with only a warn log) unless the host app happened to
     * resolve it. Both are eager now, on both platforms.
     *
     * Both constructors are cheap — their directory resolution is itself `by lazy`, so no
     * file I/O happens on the main thread during init.
     */
    fun createEagerServices() {
        TaskEventManager.initialize(eventStore)
        KmpWorkManagerRuntime.setHistoryStore(executionHistoryStore)
    }
}

/**
 * Internal holder for the Android registry.
 *
 * **History:** this used to be `KmpWorkManagerKoin`, a private `KoinApplication` introduced
 * in 2.2.2 to stop the library's global `startKoin` from colliding with the host app's Koin
 * ("A KoinApplication has already been started"). Isolation solved the crash but still put
 * `koin-core` in every consumer's POM — see discussion #66. The container was only ever used
 * as a service locator over a handful of singletons, so it is now a plain registry and Koin
 * is gone entirely.
 */
internal object KmpWorkManagerAndroid {
    @Volatile
    private var registry: AndroidServiceRegistry? = null
    private val initLock = Any()

    fun initialize(
        context: Context,
        workerFactory: WorkerFactory,
        config: KmpWorkManagerConfig = KmpWorkManagerConfig(),
        throwOnDuplicate: Boolean = false
    ) {
        // Double-checked locking pattern for thread safety
        if (registry != null) {
            if (throwOnDuplicate) {
                throw IllegalStateException("KmpWorkManager already initialized")
            }
            Logger.w("KmpWorkManager", "Already initialized - ignoring duplicate call")
            return
        }

        synchronized(initLock) {
            // Check again inside lock (double-checked locking)
            if (registry != null) {
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
            dev.brewkits.kmpworkmanager.background.data.BaseKmpWorker.configNotificationTitle =
                config.androidForegroundNotificationTitle

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
                            "Add KmpWorkerFactory to your DelegatingWorkerFactory to eliminate the " +
                            "Service Locator fallback. See KmpWorkerFactory KDoc for setup instructions."
                    )
                }
            }

            val created = AndroidServiceRegistry(context = context, workerFactory = workerFactory)
            created.createEagerServices()
            registry = created

            // Cleanup stale overflow temp files from previous sessions.
            // If the app was force-killed between spilling the file and the worker's finally block,
            // the file is left orphaned in cacheDir. Clean up files older than 24 h at init time
            // when no workers are running yet, so there's no risk of racing with an active worker.
            cleanupStaleOverflowFiles(context)

            Logger.i("KmpWorkManager", "✅ Initialized (no DI framework required)")
        }
    }

    /**
     * Dispatched onto `Dispatchers.IO` (mirrors `NativeTaskScheduler.cleanupZombieInputFiles`'s
     * established pattern for the same kind of fire-and-forget cacheDir sweep) rather than
     * running inline: `initialize()` is documented to be called from
     * `Application.onCreate()` (main thread) and was previously doing this directory-listing
     * + per-file-delete I/O synchronously while holding `initLock` — blocking the main thread
     * at cold-start and blocking any concurrent `initialize()`/`shutdown()` call on another
     * thread for the same duration.
     */
    private fun cleanupStaleOverflowFiles(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
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
    }

    /**
     * Shutdown KmpWorkManager and release resources (thread-safe)
     * Useful for testing or when reinitializing with different configuration
     */
    fun shutdown() {
        synchronized(initLock) {
            if (registry == null) {
                Logger.w("KmpWorkManager", "Not initialized - nothing to shutdown")
                return
            }
            registry = null
            // Release the global side-channel registrations too. TaskEventManager.initialize()
            // is first-call-wins, so leaving the claim in place would make a later
            // initialize() silently keep this dead registry's store.
            TaskEventManager.releaseStore()
            KmpWorkManagerRuntime.clearHistoryStore()
            Logger.i("KmpWorkManager", "✅ Shutdown complete - resources released")
        }
    }

    /**
     * @throws IllegalStateException if [initialize] has not been called. The message is the
     * one workers surface when WorkManager instantiates them before app init has run, so
     * keep it actionable.
     */
    fun requireRegistry(): AndroidServiceRegistry = registry ?: throw IllegalStateException(
        """
        KmpWorkManager not initialized!

        Call KmpWorkManager.initialize() in your Application.onCreate():

        KmpWorkManager.initialize(
            context = this,
            workerFactory = MyWorkerFactory()
        )
        """.trimIndent()
    )

    fun isInitialized(): Boolean = registry != null
}

/**
 * Public API for KmpWorkManager initialization.
 *
 * **Usage — no DI framework required:**
 * ```kotlin
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *
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
 *
 * **If you use Koin, Hilt or anything else,** wrap this in your own module — the library no
 * longer ships one:
 * ```kotlin
 * // Koin
 * val appModule = module {
 *     single { KmpWorkManager.getInstance().backgroundTaskScheduler }
 * }
 *
 * // Hilt
 * @Provides @Singleton
 * fun scheduler(): BackgroundTaskScheduler =
 *     KmpWorkManager.getInstance().backgroundTaskScheduler
 * ```
 */
object KmpWorkManager {
    /**
     * Initialize KmpWorkManager.
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
        KmpWorkManagerAndroid.initialize(context, workerFactory, config)
    }

    /**
     * Check if KmpWorkManager is initialized
     */
    fun isInitialized(): Boolean = KmpWorkManagerAndroid.isInitialized()

    /**
     * Get KmpWorkManager instance
     *
     * @return KmpWorkManagerInstance with access to backgroundTaskScheduler
     * @throws IllegalStateException if not initialized
     */
    fun getInstance(): KmpWorkManagerInstance {
        return KmpWorkManagerInstance(KmpWorkManagerAndroid.requireRegistry())
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
        KmpWorkManagerAndroid.shutdown()
    }
}

/**
 * KmpWorkManager instance providing access to scheduler and other services
 */
class KmpWorkManagerInstance internal constructor(private val registry: AndroidServiceRegistry) {
    /**
     * Background task scheduler for enqueuing and managing tasks
     */
    val backgroundTaskScheduler: BackgroundTaskScheduler
        get() = registry.backgroundTaskScheduler

    /**
     * Persistent store for task completion events.
     */
    val eventStore: EventStore
        get() = registry.eventStore

    /**
     * The factory passed to [KmpWorkManager.initialize].
     *
     * Exposed for hosts that run workers outside WorkManager — a custom
     * `BroadcastReceiver` for exact alarms, for example — and therefore need to resolve a
     * worker by class name themselves.
     */
    val workerFactory: WorkerFactory
        get() = registry.workerFactory

    /**
     * Returns the most recent task execution records, newest first.
     *
     * Records persist locally across app launches. Call this when the app foregrounds
     * and upload to your analytics backend, then call [clearExecutionHistory] to free
     * disk space.
     *
     * @param limit Maximum number of records to return. Defaults to 100.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord> =
        backgroundTaskScheduler.getExecutionHistory(limit)

    /**
     * Deletes all locally stored execution history records.
     * Call after a successful server upload to free disk space.
     */
    suspend fun clearExecutionHistory() = backgroundTaskScheduler.clearExecutionHistory()
}
