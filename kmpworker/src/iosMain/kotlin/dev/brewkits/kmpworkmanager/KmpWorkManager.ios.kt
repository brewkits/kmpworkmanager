package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.data.InfoPlistReader
import dev.brewkits.kmpworkmanager.background.data.IosBackgroundTaskHandler
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BgTaskIdProvider
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.IosEventStore
import dev.brewkits.kmpworkmanager.persistence.IosExecutionHistoryStore
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.atomicfu.atomic

/**
 * iOS service registry — the Koin-free replacement for the old `kmpWorkerModule()`.
 *
 * Each service is a `by lazy` singleton, which reproduces Koin's `single { }` semantics
 * (created once, on first access, thread-safely) without a DI container. The two
 * persistence stores are deliberately **not** lazy — see [createEagerServices].
 */
internal class IosServiceRegistry(
    private val workerFactory: IosWorkerFactory,
    private val config: KmpWorkManagerConfig,
    private val additionalTaskIds: Set<String>,
    private val appGroupIdentifier: String? = null
) {
    /**
     * Resolved once, shared by every store below. `null` (the default) keeps every store on
     * its own default `IosFileStorage()` — unchanged from pre-3.6.0 behavior. When
     * [appGroupIdentifier] is set, this is a single [IosFileStorage] rooted at the App
     * Group's shared container, so a Widget/Share Extension using the same identifier can
     * read the same files. Resolving eagerly (not `by lazy`) means a misconfigured App Group
     * entitlement fails loudly at [initialize] time rather than silently on first task
     * enqueue — see the KDoc on [KmpWorkManager.initialize]'s `appGroupIdentifier` param for
     * what "misconfigured" means here.
     */
    private val sharedFileStorage: IosFileStorage? = appGroupIdentifier?.let { identifier ->
        val containerURL = platform.Foundation.NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(identifier)
        require(containerURL != null) {
            """
            ❌ App Group container unavailable for identifier: '$identifier'

            containerURLForSecurityApplicationGroupIdentifier returned nil. This means the
            App Group entitlement is missing or misconfigured — the app was NOT falling back
            to its default private storage, because doing so silently would make the library
            write your data to the wrong place instead of failing where you can see it.

            Fix: add the App Group capability in Xcode (Signing & Capabilities → + Capability
            → App Groups) with identifier '$identifier', matching exactly what you passed to
            KmpWorkManager.initialize(appGroupIdentifier = ...).
            """.trimIndent()
        }
        IosFileStorage(
            config = IosFileStorageConfig(diskSpaceBufferBytes = config.minFreeDiskSpaceBytes),
            baseDirectory = containerURL
        )
    }

    val singleTaskExecutor: SingleTaskExecutor by lazy {
        SingleTaskExecutor(workerFactory = workerFactory)
    }

    val chainExecutor: ChainExecutor by lazy {
        sharedFileStorage?.let {
            ChainExecutor(
                workerFactory = workerFactory,
                onContinuationNeeded = {
                    IosBackgroundTaskHandler.triggerChainExecutorReschedule(chainExecutor)
                },
                fileStorage = it
            )
        } ?: ChainExecutor(
            workerFactory = workerFactory,
            // Resolved lazily inside the callback, exactly as the Koin module's `get()` did.
            // The lambda runs at continuation time, long after this initializer completes.
            onContinuationNeeded = {
                IosBackgroundTaskHandler.triggerChainExecutorReschedule(chainExecutor)
            }
        )
    }

    val dynamicTaskDispatcher: DynamicTaskDispatcher by lazy {
        sharedFileStorage?.let {
            DynamicTaskDispatcher(singleTaskExecutor = singleTaskExecutor, fileStorage = it)
        } ?: DynamicTaskDispatcher(singleTaskExecutor = singleTaskExecutor)
    }

    val backgroundTaskScheduler: BackgroundTaskScheduler by lazy {
        sharedFileStorage?.let {
            NativeTaskScheduler(
                additionalPermittedTaskIds = additionalTaskIds,
                diskSpaceBufferBytes = config.minFreeDiskSpaceBytes,
                singleTaskExecutor = singleTaskExecutor,
                chainExecutor = chainExecutor,
                fileStorage = it
            )
        } ?: NativeTaskScheduler(
            additionalPermittedTaskIds = additionalTaskIds,
            diskSpaceBufferBytes = config.minFreeDiskSpaceBytes,
            singleTaskExecutor = singleTaskExecutor,
            chainExecutor = chainExecutor
        )
    }

    val eventStore: EventStore by lazy { IosEventStore() }

    val executionHistoryStore: ExecutionHistoryStore by lazy { IosExecutionHistoryStore() }

    /**
     * Instantiates the services that must exist before any background task runs, and
     * publishes them to the global runtime hooks that workers read from.
     *
     * LIFECYCLE: both stores register themselves through a global side channel —
     * [TaskEventManager.initialize] and [KmpWorkManagerRuntime.setHistoryStore] — which
     * workers consult via nullable reads (`KmpWorkManagerRuntime.executionHistoryStore?`,
     * `eventStoreRef.value?`). Under the old Koin module these were plain lazy `single { }`
     * bindings on iOS, so the side effect only fired if the host app happened to resolve
     * them. Nothing in the library ever did, so on iOS execution history was silently never
     * recorded and `getExecutionHistory()` always returned an empty list. Android had this
     * right already via `createdAtStart = true`. Creating both eagerly here fixes that and
     * makes the two platforms behave identically.
     *
     * Both constructors are cheap — their directory resolution is itself `by lazy`, so no
     * file I/O happens on the init thread.
     */
    fun createEagerServices() {
        TaskEventManager.initialize(eventStore)
        KmpWorkManagerRuntime.setHistoryStore(executionHistoryStore)
    }
}

/**
 * Public API for KMP WorkManager initialization on iOS.
 *
 * **Usage — no DI framework required:**
 * ```kotlin
 * KmpWorkManager.initialize(
 *     workerFactory = MyWorkerFactory(),
 *     config = KmpWorkManagerConfig(logLevel = Logger.Level.INFO)
 * )
 *
 * val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
 * ```
 *
 * **If you use Koin,** wrap this in your own module — the library no longer ships one:
 * ```kotlin
 * val appModule = module {
 *     single { KmpWorkManager.getInstance().backgroundTaskScheduler }
 * }
 * ```
 *
 * **BGTask ID validation (automatic with KSP):**
 * When [WorkerFactory] implements [BgTaskIdProvider] (generated by `kmpworker-ksp`),
 * [initialize] validates that all declared BGTask IDs are present in
 * `Info.plist → BGTaskSchedulerPermittedIdentifiers`, failing fast with a descriptive
 * error rather than silently misbehaving at background-task time.
 */
object KmpWorkManager {
    // AtomicRef gives both visibility and atomicity for the check-then-set in initialize(),
    // matching the idiom already used by TaskEventManager.
    private val registryRef = atomic<IosServiceRegistry?>(null)

    /**
     * Initializes KMP WorkManager.
     *
     * @param workerFactory User-provided factory; must implement `IosWorkerFactory`.
     * @param config Configuration for logging, telemetry and disk-space guards.
     * @param iosTaskIds Additional BGTask IDs beyond those in Info.plist (optional —
     *   Info.plist remains the primary source).
     * @param appGroupIdentifier Optional App Group container identifier (`group.<bundleId>...`).
     *   When set, all task/chain/progress storage is rooted at that shared container instead
     *   of the app's private Application Support directory, via
     *   `NSFileManager.containerURLForSecurityApplicationGroupIdentifier`.
     *
     *   **What this enables**: a Widget or Share Extension in the same App Group can construct
     *   its own `IosFileStorage(baseDirectory = <same container URL>)` and call the read-only
     *   `loadTaskMetadata` to observe what the main app scheduled — see
     *   `docs/IOS_APP_GROUP_STORAGE.md`.
     *
     *   **What this does NOT enable**: running the scheduler in more than one process at
     *   once, or reading execution history. `ChainJobRegistry`, the progress-flush debounce
     *   buffer, and the dynamic queue's size counters are all in-memory and process-local —
     *   two processes both calling `KmpWorkManager.initialize(appGroupIdentifier = ...)`
     *   against the same container and both scheduling/dispatching work would race each other
     *   and can corrupt shared state (e.g. exceed `MAX_QUEUE_SIZE`, drop progress updates).
     *   Exactly **one** process (normally the main app) may run the scheduler against a given
     *   container; every other process sharing it must be read-only. Separately,
     *   `IosEventStore`/`IosExecutionHistoryStore` (backing `getExecutionHistory()`) are not
     *   wired to [appGroupIdentifier] at all — both always resolve their own
     *   `NSApplicationSupportDirectory` path, so an extension cannot read execution history
     *   through this parameter today.
     *
     *   Fails fast with [IllegalArgumentException] if the App Group entitlement is missing or
     *   the identifier doesn't match Xcode's configured App Group — see the thrown message
     *   for the exact fix. Left `null` (the default), behavior is unchanged from pre-3.6.0:
     *   private Application Support storage, as before.
     */
    fun initialize(
        workerFactory: WorkerFactory,
        config: KmpWorkManagerConfig = KmpWorkManagerConfig(),
        iosTaskIds: Set<String> = emptySet(),
        appGroupIdentifier: String? = null
    ) {
        if (registryRef.value != null) {
            Logger.w("KmpWorkManager", "Already initialized - ignoring duplicate call")
            return
        }

        // LIFECYCLE: everything below used to run at Koin *module construction* time — i.e.
        // eagerly, at the same point in app startup. Keep it eager so the fail-fast
        // diagnostics still fire before the first task can be scheduled.
        Logger.setMinLevel(config.logLevel)
        config.customLogger?.let { Logger.setCustomLogger(it) }

        KmpWorkManagerRuntime.configure(config)

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

            Then pass it to KmpWorkManager.initialize:
            KmpWorkManager.initialize(workerFactory = MyWorkerFactory())
            """.trimIndent()
        }

        if (workerFactory is BgTaskIdProvider) {
            val permittedIds = InfoPlistReader.readPermittedTaskIds()
            val missing = workerFactory.requiredBgTaskIds - permittedIds
            require(missing.isEmpty()) {
                """
                ❌ Missing BGTask IDs in Info.plist

                The following IDs are declared via @Worker(bgTaskId = ...) but are absent
                from BGTaskSchedulerPermittedIdentifiers in Info.plist:

                ${missing.joinToString("\n") { "  • $it" }}

                Fix: add them to your Info.plist:
                <key>BGTaskSchedulerPermittedIdentifiers</key>
                <array>
                ${missing.joinToString("\n") { "    <string>$it</string>" }}
                    <!-- ...existing IDs... -->
                </array>
                """.trimIndent()
            }
        }

        val created = IosServiceRegistry(
            workerFactory = workerFactory,
            config = config,
            additionalTaskIds = iosTaskIds,
            appGroupIdentifier = appGroupIdentifier
        )
        // Publish first, then create the eager services, so a losing racer never runs the
        // global registration side effects a second time.
        if (!registryRef.compareAndSet(null, created)) {
            Logger.w("KmpWorkManager", "Already initialized - ignoring duplicate call")
            return
        }
        created.createEagerServices()

        Logger.i("KmpWorkManager", "✅ Initialized (no DI framework required)")
    }

    /** Returns true once [initialize] has completed. */
    fun isInitialized(): Boolean = registryRef.value != null

    /**
     * @return [KmpWorkManagerInstance] with access to the scheduler and executors.
     * @throws IllegalStateException if [initialize] has not been called.
     */
    fun getInstance(): KmpWorkManagerInstance = KmpWorkManagerInstance(requireRegistry())

    /**
     * Releases resources. Useful for tests, logout / user-switch flows, or when
     * reinitializing with a different configuration.
     */
    fun shutdown() {
        if (registryRef.getAndSet(null) == null) return
        // Release the global side-channel registrations too. TaskEventManager.initialize()
        // is first-call-wins, so leaving the claim in place would make a later
        // initialize() silently keep this dead registry's store.
        TaskEventManager.releaseStore()
        KmpWorkManagerRuntime.clearHistoryStore()
        Logger.i("KmpWorkManager", "✅ Shutdown complete")
    }

    internal fun requireRegistry(): IosServiceRegistry = registryRef.value ?: error(
        """
        KmpWorkManager not initialized!

        Call KmpWorkManager.initialize() during app startup:

        KmpWorkManager.initialize(
            workerFactory = MyWorkerFactory()
        )
        """.trimIndent()
    )
}

/**
 * Accessor for the initialized services. Obtain one via [KmpWorkManager.getInstance].
 *
 * The executors are exposed because the iOS host app must hand them to
 * `IosBackgroundTaskHandler` from its `BGTaskScheduler` registration closures.
 */
class KmpWorkManagerInstance internal constructor(private val registry: IosServiceRegistry) {

    /** Background task scheduler for enqueuing and managing tasks. */
    val backgroundTaskScheduler: BackgroundTaskScheduler get() = registry.backgroundTaskScheduler

    /** Executor for single tasks — pass to `IosBackgroundTaskHandler.handleSingleTask`. */
    val singleTaskExecutor: SingleTaskExecutor get() = registry.singleTaskExecutor

    /** Executor for task chains — pass to `IosBackgroundTaskHandler.handleChainExecutorTask`. */
    val chainExecutor: ChainExecutor get() = registry.chainExecutor

    /** Dispatcher for the master BGTask — pass to `IosBackgroundTaskHandler.handleMasterDispatcherTask`. */
    val dynamicTaskDispatcher: DynamicTaskDispatcher get() = registry.dynamicTaskDispatcher

    /** Persistent store for task completion events. */
    val eventStore: EventStore get() = registry.eventStore

    /**
     * Returns the most recent task execution records, newest first.
     *
     * @param limit Maximum number of records to return. Defaults to 100.
     */
    suspend fun getExecutionHistory(limit: Int = 100): List<ExecutionRecord> =
        backgroundTaskScheduler.getExecutionHistory(limit)

    /** Deletes all locally stored execution history records. */
    suspend fun clearExecutionHistory() = backgroundTaskScheduler.clearExecutionHistory()
}
