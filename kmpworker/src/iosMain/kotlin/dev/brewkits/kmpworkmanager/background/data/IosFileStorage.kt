package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.data.storage.ChainDefinitionStore
import dev.brewkits.kmpworkmanager.background.data.storage.ChainQueueRepository
import dev.brewkits.kmpworkmanager.background.data.storage.ChainProgressStore
import dev.brewkits.kmpworkmanager.background.data.storage.StorageMaintenance
import dev.brewkits.kmpworkmanager.background.data.storage.StorageFileIo
import dev.brewkits.kmpworkmanager.background.data.storage.TaskMetadataStore
import dev.brewkits.kmpworkmanager.background.data.storage.safeAppend
import dev.brewkits.kmpworkmanager.background.domain.TaskPriority
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import platform.Foundation.*
import platform.darwin.*
import kotlin.concurrent.AtomicInt

/**
 * Transaction record for chain operations
 * Logs all chain mutations for debugging and recovery
 */
@Serializable
internal data class ChainTransaction(
    val chainId: String,
    val action: String,  // "ENQUEUE", "DELETE", "REPLACE"
    val timestamp: Long,
    val succeeded: Boolean,
    val error: String? = null
)

/**
 * Aggregate light/network profile of the tasks currently pending in the dynamic-task queue.
 * See [IosFileStorage.getDynamicQueueConstraintSummary].
 */
internal data class DynamicQueueConstraintSummary(
    val pendingCount: Int,
    val heavyCount: Int,
    val networkRequiredCount: Int,
    val chargingRequiredCount: Int,
    /**
     * Earliest epoch-ms the master dispatcher should be re-requested, derived from pending
     * tasks' backoff floors ([DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS]).
     *
     * `null` means "now" — at least one pending task has no floor (or none are pending), so
     * the dispatcher should be woken as soon as possible rather than waiting out some other
     * task's backoff. Only when EVERY pending task has an unexpired floor does this hold the
     * minimum of those floors, so the dispatcher isn't woken early just to find nothing
     * runnable and burn BGTask quota re-scheduling itself.
     */
    val earliestBackoffFloorMs: Long?
) {
    /** True when every pending task is light — safe to schedule as `BGAppRefreshTask`. */
    val allLight: Boolean get() = pendingCount > 0 && heavyCount == 0

    /** True when every pending task requires network connectivity. */
    val allRequireNetwork: Boolean get() = pendingCount > 0 && networkRequiredCount == pendingCount

    /**
     * True when every pending task requires charging. Lets the master dispatcher's
     * `BGProcessingTaskRequest.requiresExternalPower` be set at the OS level — the OS then
     * simply won't wake the process at all until the device is plugged in, which is strictly
     * better than our own opt-in-gated `UIDevice.batteryMonitoringEnabled` runtime check
     * ([StandaloneConstraintGuard]): no host opt-in needed, and no wasted wake+defer cycle.
     */
    val allRequireCharging: Boolean get() = pendingCount > 0 && chargingRequiredCount == pendingCount
}

/**
 * Configuration for [IosFileStorage].
 *
 * @param diskSpaceBufferBytes Minimum free space required before any write (safety margin).
 *   Default: 50 MB.
 * @param deletedMarkerMaxAgeMs Age after which REPLACE-policy deletion markers are removed.
 *   Default: 7 days.
 * @param isTestMode When `null` (default), test mode is auto-detected from the process name
 *   (`test.kexe`). Pass `true` to force test mode (bypasses NSFileCoordinator), `false`
 *   to force production mode.
 * @param fileCoordinationTimeoutMs Maximum time to wait for an NSFileCoordinator lock before
 *   aborting. Default: 30 000 ms. Set to `0` to disable the timeout.
 */
@Serializable
public data class IosFileStorageConfig(
    val diskSpaceBufferBytes: Long = 50_000_000L,  // 50MB default (was 100MB)
    val deletedMarkerMaxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L,  // 7 days default
    val isTestMode: Boolean? = null,  // null = auto-detect, true/false = override
    val fileCoordinationTimeoutMs: Long = 30_000L  // 30 seconds default (0 = disabled)
)

/**
 * Thread-safe file-based storage for iOS task data using native iOS APIs.
 * Replaces NSUserDefaults to fix race conditions and improve performance.
 *
 * Features:
 * - Atomic file operations using NSFileCoordinator
 * - Bounded queue size (max 1000 chains)
 * - Chain size limits (max 10MB per chain)
 * - Automatic garbage collection
 * - No third-party dependencies (pure iOS APIs)
 * - Configurable disk space safety margin
 *
 * File Structure:
 * ```
 * Library/Application Support/dev.brewkits.kmpworkmanager/
 * ├── queue.jsonl              # Chain queue (append-only)
 * ├── chains/
 * │   ├── <uuid1>.json         # Chain definitions
 * │   └── <uuid2>.json
 * └── metadata/
 *     ├── tasks/
 *     │   └── <taskId>.json    # Task metadata
 *     └── periodic/
 *         └── <taskId>.json    # Periodic metadata
 * ```
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
public class IosFileStorage(
    private val config: IosFileStorageConfig = IosFileStorageConfig(),
    private val baseDirectory: NSURL? = null  // null = use AppSupport/dev.brewkits.kmpworkmanager
) {

    private val fileManager = NSFileManager.defaultManager

    private val isTestMode: Boolean =
        config.isTestMode ?: dev.brewkits.kmpworkmanager.utils.IosTestEnvironment.isTestEnvironment

    /**
     * The shared file-I/O + coordination primitives. Stage 0b of the SRP split — see
     * `docs/internal/IOS_FILE_STORAGE_SPLIT.md`. The private helpers below now delegate here
     * so that the stores extracted in later stages can take this object by constructor
     * instead of each re-implementing atomic writes and coordination.
     */
    private val io = StorageFileIo(
        isTestMode = isTestMode,
        // Deliberately config.isTestMode, not isTestMode: IosFileCoordinator runs its own
        // test-environment detection, so feeding auto-detection in here would apply it twice.
        coordinatorTestMode = config.isTestMode ?: false,
        coordinationTimeoutMs = config.fileCoordinationTimeoutMs
    )

    // Tolerant Json for all persisted data: ignores unknown keys so that data written by a
    // newer schema version does not crash on rollback or when consumed by an older class.
    private val persistenceJson = Json { ignoreUnknownKeys = true }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Test-only: exposes whether the background scope is still active. Used by
     * close()-finally regression tests to verify the scope is cancelled even when
     * `flushNow()` throws.
     */
    internal val isBackgroundScopeActive: Boolean
        get() = backgroundScope.coroutineContext[Job.Key]?.isActive == true

    /**
     * Test-only: when set to true, the next [flushNow] call throws synchronously.
     * Used by close()-finally regression tests to deterministically simulate the
     * real failure modes (full disk, EACCES, NSFileCoordinator error) which are
     * otherwise hard to trigger in the simulator-test sandbox. Production code
     * never reads or writes this field.
     */
    internal var testForceFlushFailure: Boolean
        get() = progressStore.testForceFlushFailure
        set(value) { progressStore.testForceFlushFailure = value }

    /**
     * Test-only: optional delay (ms) inserted inside [enqueueChainInternal] between
     * the size-limit check and the actual queue write. Lets a regression test pin
     * the check-then-act window so a concurrent enqueueChain / replaceChainAtomic
     * pair can be forced to race deterministically. Default 0 → no delay.
     * Production code never reads or writes this field.
     */
    internal var testEnqueueInternalDelayMs: Long
        get() = chainQueue.testEnqueueInternalDelayMs
        set(value) { chainQueue.testEnqueueInternalDelayMs = value }

    /**
     * Test-only: optional delay (ms) inserted at the start of [loadChainDefinition], i.e. the
     * first suspension point of `ChainExecutor.executeChain`'s prologue. Lets a regression
     * test cancel a chain execution deterministically AFTER the chain has been dequeued but
     * BEFORE step execution begins — the window in which a cancellation used to lose the
     * chain outright. Default 0 → no delay. Production code never reads or writes this field.
     */
    internal var testLoadChainDefinitionDelayMs: Long
        get() = chainDefinitionStore.testLoadChainDefinitionDelayMs
        set(value) { chainDefinitionStore.testLoadChainDefinitionDelayMs = value }


    // Lock ordering: the queue mutexes moved to ChainQueueRepository together with the
    // queues they protect, and the progress mutex to ChainProgressStore. See
    // ChainQueueRepository's Lock-Ordering Invariant banner — it is now checkable by reading
    // one file, which is the point of having moved them.


    /**
     * Chain-progress persistence — the debounced buffer, the emergency flush and the
     * self-healing recovery. Stage 1 of the SRP split; see
     * `docs/internal/IOS_FILE_STORAGE_SPLIT.md`. The progress methods below are delegations,
     * kept so that no call site outside this package had to move.
     *
     * `chainsDirURL` is passed as a lambda rather than a value: it is `by lazy` and creates
     * the directory on first touch, and resolving it here would force that at construction.
     */
    /**
     * Task-metadata persistence. Stage 2 of the SRP split. Same lambda-directory reasoning as
     * [progressStore]: `tasksDirURL`/`periodicDirURL` are `by lazy` and create their
     * directories on first touch.
     */
    private val taskMetadataStore = TaskMetadataStore(
        io = io,
        persistenceJson = persistenceJson,
        tasksDir = { tasksDirURL },
        periodicDir = { periodicDirURL }
    )

    /**
     * Disk-space guard and the periodic maintenance run. Stage 4 of the SRP split. Built
     * before the stores that use it — `saveChainDefinition` gates on [checkDiskSpace].
     */
    private val maintenance = StorageMaintenance(
        io = io,
        baseDir = { baseDir },
        maintenanceTimestampFile = { maintenanceTimestampURL },
        diskSpaceBufferBytes = config.diskSpaceBufferBytes,
        isTestMode = isTestMode
    )

    private val progressStore = ChainProgressStore(
        io = io,
        backgroundScope = backgroundScope,
        persistenceJson = persistenceJson,
        chainsDir = { chainsDirURL }
    )

    /**
     * Chain definitions and the REPLACE-policy deleted markers. Stage 4 of the SRP split.
     *
     * `deleteProgress` is a lambda rather than a direct `ChainProgressStore` dependency: the
     * only thing chain definitions need from progress is "drop it, this definition is
     * corrupt", and passing that one capability keeps the two stores independent of each
     * other instead of coupling the whole of one to the whole of the other.
     */
    private val chainDefinitionStore = ChainDefinitionStore(
        io = io,
        maintenance = maintenance,
        persistenceJson = persistenceJson,
        chainsDir = { chainsDirURL },
        deletedChainsDir = { deletedChainsDirURL },
        maxChainSizeBytes = MAX_CHAIN_SIZE_BYTES,
        deletedMarkerMaxAgeMs = config.deletedMarkerMaxAgeMs,
        deleteProgress = { id -> progressStore.deleteChainProgress(id) }
    )

    /**
     * The two append-only queues, their locks and counters, and the atomic chain REPLACE.
     * Stage 3 of the SRP split. Constructed last: the REPLACE transaction spans definitions,
     * progress and metadata, so all three have to exist first.
     */
    private val chainQueue = ChainQueueRepository(
        io = io,
        backgroundScope = backgroundScope,
        isTestMode = isTestMode,
        queueDir = { baseDir.safeAppend("queue") },
        tasksQueueDir = { baseDir.safeAppend("tasks_queue") },
        transactionLogFile = { baseDir.safeAppend("transactions.jsonl") },
        maxQueueSize = MAX_QUEUE_SIZE,
        definitions = chainDefinitionStore,
        progress = progressStore,
        metadata = taskMetadataStore
    )


    companion object {
        const val MAX_QUEUE_SIZE = 1000
        const val MAX_CHAIN_SIZE_BYTES = 10_485_760L // 10MB

        /**
         * Debounce window for progress flush (100ms)
         * Balances performance (batching) vs data safety
         *
         * **Why 100ms?**
         * - Reduces data loss window from 500ms to 100ms (80% improvement)
         * - Still allows batching of rapid progress updates
         * - iOS can suspend apps aggressively - shorter window = safer
         * - Minimal performance impact (flush still batched)
         */

        private const val BASE_DIR_NAME = "dev.brewkits.kmpworkmanager"
        private const val QUEUE_FILE_NAME = "queue.jsonl"
        private const val CHAINS_DIR_NAME = "chains"
        private const val METADATA_DIR_NAME = "metadata"
        private const val TASKS_DIR_NAME = "tasks"
        private const val PERIODIC_DIR_NAME = "periodic"
        private const val DELETED_CHAINS_DIR_NAME = "deleted_chains"
    }

    /**
     * Base directory path: Library/Application Support/dev.brewkits.kmpworkmanager/
     */
    private val baseDir: NSURL by lazy {
        val basePath = if (baseDirectory != null) {
            ensureDirectoryExists(baseDirectory)
            baseDirectory
        } else {
            val urls = fileManager.URLsForDirectory(
                NSApplicationSupportDirectory,
                NSUserDomainMask
            ) as List<*>
            val appSupportDir = urls.firstOrNull() as? NSURL
                ?: throw IllegalStateException("Could not locate Application Support directory")

            val path = appSupportDir.safeAppend(BASE_DIR_NAME)
            ensureDirectoryExists(path)
            path
        }

        Logger.d(LogTags.SCHEDULER, "IosFileStorage initialized at: ${basePath.path}")
        basePath
    }

    private val chainsDirURL: NSURL by lazy {
        val url = baseDir.safeAppend(CHAINS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val metadataDirURL: NSURL by lazy {
        val url = baseDir.safeAppend(METADATA_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val tasksDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(TASKS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val periodicDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(PERIODIC_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }
    private val deletedChainsDirURL: NSURL by lazy {
        val url = metadataDirURL.safeAppend(DELETED_CHAINS_DIR_NAME)
        ensureDirectoryExists(url)
        url
    }

    internal val maintenanceTimestampURL: NSURL by lazy {
        baseDir.safeAppend("last_maintenance.txt")
    }

    init {
        // Skip the auto-launched maintenance job in test environments. It runs on
        // backgroundScope (Dispatchers.Default) and, for a fresh temp dir, sees
        // "maintenance never run" → executes performMaintenanceTasks() immediately,
        // which logs and writes files. When the test that constructed this storage has
        // already finished (and didn't await close(), or close() raced the launch), that
        // output arrives after teardown — Kotlin/Native's test runner reports it as
        // "Received output for test that is not running" and the whole job crashes
        // (issue #42). Tests that need maintenance call performMaintenanceTasks() directly,
        // so gating only the auto-launch costs no coverage. Production is unaffected.
        if (!dev.brewkits.kmpworkmanager.utils.IosTestEnvironment.isTestEnvironment) {
        backgroundScope.launch {
            // NOTE: the queue-size counters are deliberately NOT eagerly initialized here.
            //
            // This background job runs on Dispatchers.Default and would otherwise race with
            // enqueueTask, which is non-atomic across its disk write and its counter update:
            // enqueueTask appends to the queue file FIRST, then decides whether to seed the
            // counter from disk (when UNINITIALIZED) or incrementAndGet() (when already set).
            // If this job read the queue size in that window — after the append but before
            // enqueueTask updated the counter — it would seed the counter to the new disk
            // size (already counting the appended item); enqueueTask would then take the
            // "else" branch and increment again, double-counting that item (counter = N+1
            // for N items on disk). getTasksQueueSize() then over-reports by one, and the
            // dispatcher / FIFO / retry-cap assertions fail intermittently (observed as a
            // flaky 5-test cluster on loaded CI runners, never locally).
            //
            // Eager initialization is redundant anyway: enqueueTask and getTasksQueueSize
            // both lazily seed the counter from disk on first use when it is UNINITIALIZED,
            // and dequeueTask is a no-op on the counter while UNINITIALIZED. Removing the
            // only concurrent writer makes the counter math deterministic.
            val hoursSinceLastMaintenance = maintenance.hoursSinceLastMaintenance()

            if (hoursSinceLastMaintenance >= 24) {
                // Run immediately if maintenance hasn't run in 24+ hours
                val overdueMsg = if (hoursSinceLastMaintenance == Int.MAX_VALUE) "never run" else "$hoursSinceLastMaintenance hours"
                Logger.i(LogTags.SCHEDULER, "Maintenance overdue ($overdueMsg). Running immediately...")
                performMaintenanceTasks()
            } else {
                // Wait 5s for app to stabilize before running maintenance
                delay(5000)
                performMaintenanceTasks()
            }
        }
        }
    }

    // ==================== Queue Operations ====================

    /** Enqueue a chain ID (thread-safe, atomic against the queue-size cap). */
    suspend fun enqueueChain(chainId: String) = chainQueue.enqueueChain(chainId)


    /** Dequeue the next chain ID, or null when the queue is empty. */
    suspend fun dequeueChain(): String? = chainQueue.dequeueChain()

    /** Enqueue a single task ID onto the dynamic-task queue. */
    suspend fun enqueueTask(id: String) = chainQueue.enqueueTask(id)

    /** Dequeue the next dynamic task ID, or null when that queue is empty. */
    suspend fun dequeueTask(): String? = chainQueue.dequeueTask()

    /**
     * True if [id] is currently sitting in the dynamic-task queue. Used by
     * [NativeTaskScheduler.observeTaskState] to distinguish "waiting for the master
     * dispatcher" from "already dequeued".
     */
    internal suspend fun isTaskInDynamicQueue(id: String): Boolean =
        chainQueue.isTaskInDynamicQueue(id)

    /** Current dynamic-task queue depth. */
    suspend fun getTasksQueueSize(): Int = chainQueue.getTasksQueueSize()

    /** Current chain queue depth — always read from disk, for cross-instance correctness. */
    suspend fun getQueueSize(): Int = chainQueue.getQueueSize()
    /** Aggregate constraint profile of everything in the dynamic-task queue. */
    internal suspend fun getDynamicQueueConstraintSummary(): DynamicQueueConstraintSummary =
        chainQueue.getDynamicQueueConstraintSummary()

    /** Reorder the chain queue by task priority, highest first. */
    suspend fun sortQueueByPriority() = chainQueue.sortQueueByPriority()

    /**
     * Replace a chain's definition and re-enqueue it, as one transaction: mark deleted,
     * drop the old definition and progress, save the new definition, enqueue.
     */
    suspend fun replaceChainAtomic(chainId: String, newSteps: List<List<TaskRequest>>) =
        chainQueue.replaceChainAtomic(chainId, newSteps)


    // readQueueInternal() and writeQueueInternal() removed - no longer needed

    // ==================== Chain Definition Operations ====================

    /** Save a chain definition to disk. Refuses definitions over the size limit. */
    fun saveChainDefinition(id: String, steps: List<List<TaskRequest>>) =
        chainDefinitionStore.saveChainDefinition(id, steps)

    /**
     * Load a chain definition, self-healing (deleting) a corrupt file and its progress.
     *
     * `suspend` because the self-healing path calls [deleteChainProgress], which must acquire
     * the progress mutex to evict the buffer entry.
     */
    suspend fun loadChainDefinition(id: String): List<List<TaskRequest>>? =
        chainDefinitionStore.loadChainDefinition(id)

    /** Delete a chain definition. */
    fun deleteChainDefinition(id: String) = chainDefinitionStore.deleteChainDefinition(id)

    /** Check whether a chain definition exists on disk. */
    fun chainExists(id: String): Boolean = chainDefinitionStore.chainExists(id)

    // ==================== Deleted Chain Markers ====================

    /**
     * Mark a chain as deleted to prevent duplicate execution under the REPLACE policy.
     * The marker carries a timestamp so the reaper can age it out.
     */
    fun markChainAsDeleted(chainId: String) = chainDefinitionStore.markChainAsDeleted(chainId)

    /** Whether a chain has been marked deleted. [ChainExecutor] skips those. */
    fun isChainDeleted(chainId: String): Boolean = chainDefinitionStore.isChainDeleted(chainId)

    /** Clear a chain's deleted marker, after skipping its execution. */
    fun clearDeletedMarker(chainId: String) = chainDefinitionStore.clearDeletedMarker(chainId)

    /**
     * Remove deleted markers older than [IosFileStorageConfig.deletedMarkerMaxAgeMs]
     * (default 7 days), so they cannot leak disk space.
     */
    fun cleanupStaleDeletedMarkers() = chainDefinitionStore.cleanupStaleDeletedMarkers()

    /**
     * Perform periodic maintenance tasks: stale marker cleanup and metadata cleanup.
     * Called from the init block with a startup delay to avoid blocking app launch.
     */
    fun performMaintenanceTasks() = maintenance.runMaintenance(
        listOf(
            { cleanupStaleDeletedMarkers() },
            { cleanupStaleMetadata(olderThanDays = 7) }
        )
    )

    /**
     * Returns true when maintenance is overdue based on the given hour interval.
     *
     * Exposed for testing — allows tests to verify the skip-if-recent guard without
     * waiting for the actual 24h window.
     *
     * @param hoursInterval The interval in hours. A value of 0 always returns true.
     */
    fun isMaintenanceRequired(hoursInterval: Int): Boolean =
        maintenance.isMaintenanceRequired(hoursInterval)

    /**
     * All chain IDs currently in the queue (not yet dequeued). Used by stress tests to check
     * queue integrity without going through [getQueueSize], which counts physical entries
     * including logically-deleted REPLACE residues.
     */
    suspend fun getActiveChainIds(): List<String> = chainQueue.getActiveChainIds()

    /**
     * Queued chain IDs whose definition contains a task matching [workerClassName] or
     * carrying [tag]. Either may be null; both null returns an empty list.
     */
    suspend fun findChainIdsByWorkerOrTag(
        workerClassName: String? = null,
        tag: String? = null
    ): List<String> = chainQueue.findChainIdsByWorkerOrTag(workerClassName, tag)



    // ==================== Chain Progress Operations ====================
    //
    // Implementation lives in ChainProgressStore (storage/ChainProgressStore.kt). These are
    // delegations: Stage 1 of the SRP split moved the code without moving any caller, so the
    // twenty call sites in ChainExecutor — several of them inside cancellation paths — are
    // untouched. Later stages retire the delegations once callers take the store directly.

    /**
     * Buffer a progress update for this chain. The buffer is flushed to disk after a
     * debounce window, batching rapid updates into a single write.
     *
     * For critical checkpoints (chain completion, BGTask expiration), call [flushNow]
     * immediately after to guarantee durability before the process is suspended.
     *
     * @param progress The progress state to save
     */
    suspend fun saveChainProgress(progress: ChainProgress) = progressStore.saveChainProgress(progress)

    /**
     * Flush buffered progress immediately.
     * Use before critical points: chain completion, shutdown, BGTask expiration.
     *
     * Concurrent flush calls are safe — atomic state management ensures only one flush
     * runs at a time; additional callers await the in-progress flush.
     *
     * @throws Exception if flush fails (caller should handle)
     */
    suspend fun flushNow() = progressStore.flushNow()

    /**
     * Flush all pending progress to disk synchronously.
     *
     * Designed for call sites that cannot suspend: BGTask expiration handler,
     * `applicationWillResignActive`, and shutdown sequences. Blocks the calling thread
     * for up to 450 ms (leaving 50 ms headroom before the iOS 500 ms watchdog).
     *
     * Prefer [flushNow] from suspend contexts — it does the same work without blocking.
     */
    fun flushAllPendingProgress() = progressStore.flushAllPendingProgress()

    /**
     * Load chain progress from file, self-healing (deleting) a corrupt file.
     *
     * @param chainId The chain ID
     * @return The progress state, or null if no progress file exists or is corrupt
     */
    fun loadChainProgress(chainId: String): ChainProgress? = progressStore.loadChainProgress(chainId)

    /**
     * Returns `true` (and clears the flag) if this chain's progress file was deleted by
     * self-healing corruption recovery since this process started.
     *
     * Call this once, immediately before executing a chain. A `true` result means the
     * chain has lost its execution history — callers must check [TaskRequest.isIdempotent]
     * for every task in the chain before deciding whether to restart or quarantine.
     */
    suspend fun consumeSelfHealedFlag(chainId: String): Boolean =
        progressStore.consumeSelfHealedFlag(chainId)

    /**
     * Delete chain progress, from the in-memory buffer and from disk.
     *
     * Should be called when the chain completes, is abandoned (exceeded retry limit), or
     * its definition is deleted.
     *
     * @param chainId The chain ID
     */
    suspend fun deleteChainProgress(chainId: String) = progressStore.deleteChainProgress(chainId)

    // ==================== Metadata Operations ====================
    //
    // Implementation lives in TaskMetadataStore (storage/TaskMetadataStore.kt). These are
    // delegations: Stage 2 of the SRP split moved the code without moving any caller. The
    // plan wanted NativeTaskScheduler and IosBackgroundTaskHandler switched to an injected
    // store in the same stage; keeping the two apart is the point of staging, so the
    // delegations retire when those call sites move, not before.

    /** Save task metadata. */
    fun saveTaskMetadata(id: String, metadata: Map<String, String>, periodic: Boolean) =
        taskMetadataStore.saveTaskMetadata(id, metadata, periodic)

    /** Load task metadata, self-healing (deleting) a corrupt file. */
    fun loadTaskMetadata(id: String, periodic: Boolean): Map<String, String>? =
        taskMetadataStore.loadTaskMetadata(id, periodic)

    /**
     * List all non-periodic task IDs that have saved metadata, as raw on-disk names.
     * Used by the catch-up executor to find missed exact-alarm tasks.
     *
     * **Consumption**: single-use Sequence (backed by a stateful OS enumerator).
     */
    fun listTaskIds(): Sequence<String> = taskMetadataStore.listTaskIds()

    /**
     * Like [listTaskIds] but **decoded** back to the original id. Used by
     * [computeIosTaskState]/`queryTasks`, which report ids back to the caller.
     */
    internal fun listOneTimeTaskIdsDecoded(): Sequence<String> =
        taskMetadataStore.listOneTimeTaskIdsDecoded()

    /** Periodic counterpart of [listOneTimeTaskIdsDecoded]. */
    internal fun listPeriodicTaskIds(): Sequence<String> = taskMetadataStore.listPeriodicTaskIds()

    /**
     * List all chain IDs that have a saved chain **definition** on disk — broader than
     * [getActiveChainIds], which only sees chains still queued.
     *
     * **Consumption**: single-use Sequence (backed by a stateful OS enumerator).
     */
    internal fun listChainDefinitionIds(): Sequence<String> =
        chainDefinitionStore.listChainDefinitionIds()

    /** Delete task metadata. */
    fun deleteTaskMetadata(id: String, periodic: Boolean) =
        taskMetadataStore.deleteTaskMetadata(id, periodic)

    /**
     * Finds standalone (non-chain) task IDs whose stored metadata matches [workerClassName]
     * or carries [tag], scanning both the one-time and periodic metadata directories.
     *
     * The chain-side counterpart is [findChainIdsByWorkerOrTag]; both are needed because a
     * task can be scheduled either way and `cancelByTag` must reach both. Returns pairs of
     * `(taskId, isPeriodic)` so the caller can delete the right metadata file — the two
     * directories can legitimately hold the same id.
     *
     * Either parameter may be null; passing both as null returns an empty list.
     */
    fun findTaskIdsByWorkerOrTag(
        workerClassName: String? = null,
        tag: String? = null
    ): List<Pair<String, Boolean>> = taskMetadataStore.findTaskIdsByWorkerOrTag(workerClassName, tag)

    /** Cleanup stale metadata older than [olderThanDays] days. */
    fun cleanupStaleMetadata(olderThanDays: Int = 7) =
        taskMetadataStore.cleanupStaleMetadata(olderThanDays)

    // ==================== Helper Methods ====================

    /**
     * Ensure directory exists, create if not
     */
    private fun ensureDirectoryExists(url: NSURL) = io.ensureDirectoryExists(url)


    /**
     * Read string from file
     */
    private fun readStringFromFile(url: NSURL): String? = io.readStringFromFile(url)

    /**
     * Write string to file atomically.
     *
     * When [url] already has content on disk, uses [NSFileManager.replaceItemAtURL] — a
     * true filesystem-atomic swap — per this project's own established invariant (see
     * [AppendOnlyQueue]'s compaction fix): `NSString.writeToFile(atomically:)` is an older
     * API with known reliability gaps under some `NSFileProtection` classes and disk
     * conditions, the exact class of bug this codebase already fixed for the queue file.
     * Every caller of this function (task metadata, chain definitions, chain progress,
     * the transaction log) carries the same risk, so the fix applies here unconditionally
     * rather than only to the queue.
     *
     * Falls back to a direct (non-atomic) write if the target does not exist yet — there
     * is nothing to atomically replace on a first write — or in test mode, matching this
     * function's prior `atomically = !isTestMode` behavior (tests intentionally trade
     * atomicity for speed).
     */
    private fun writeStringToFile(url: NSURL, content: String) = io.writeStringToFile(url, content)


    /**
     * Delete file if exists
     */
    private fun deleteFile(url: NSURL) = io.deleteFile(url)

    /**
     * Synchronous file coordination bridge for non-suspend callers.
     *
     * Blocks the calling thread via runBlocking — safe only from threads that are NOT
     * Dispatchers.Default coroutine threads (e.g. the GCD high-priority queue, init blocks,
     * or Swift-called functions). Never call this from inside a suspend function; use
     * [coordinatedSuspend] instead to avoid blocking a Dispatchers.Default thread.
     */
    private fun <T> coordinated(url: NSURL, write: Boolean, block: (NSURL) -> T): T =
        io.coordinated(url, write, block)

    /**
     * Suspend-native file coordination for use inside coroutines.
     *
     * Calls [IosFileCoordinator.coordinate] directly without a runBlocking bridge.
     * This ensures the calling Dispatchers.Default thread is released while
     * NSFileCoordinator waits on IosDispatchers.IO — preventing thread starvation
     * when multiple chains flush progress concurrently inside [flushProgressBuffer].
     *
     * Only call from suspend functions. Non-suspend callers must use [coordinated].
     */
    private suspend fun <T> coordinatedSuspend(url: NSURL, write: Boolean, block: (NSURL) -> T): T =
        io.coordinatedSuspend(url, write, block)

    /**
     * Flush pending progress and cancel all background jobs.
     *
     * Call when the app is shutting down, the storage instance is no longer needed,
     * or tests are cleaning up.
     */
    suspend fun close() {
        // Two-phase shutdown:
        //   Phase 1 (try):     flushNow — best-effort durable save of buffered progress
        //                      BEFORE we kill the scope. If we cancelled first, the
        //                      ChainProgressStore's in-flight debounced flush job would die
        //                      (it runs on this same backgroundScope, which is why the store
        //                      is handed the scope rather than owning one) and buffered
        //                      updates would be silently lost.
        //   Phase 2 (finally): cancel + join — MUST run even if flush threw, otherwise
        //                      the backgroundScope keeps running forever (coroutine leak).
        //                      Common trigger: full disk / EACCES on flush → previous
        //                      single-block try/catch swallowed the exception and skipped
        //                      cancel+join, leaking workers across test teardowns and
        //                      app shutdowns.
        try {
            flushNow()
        } catch (e: Exception) {
            Logger.e(LogTags.CHAIN, "IosFileStorage.close: flushNow failed (proceeding with cancel)", e)
        } finally {
            try {
                val scopeJob = backgroundScope.coroutineContext[Job.Key]
                backgroundScope.cancel()
                scopeJob?.join()
                Logger.i(LogTags.CHAIN, "IosFileStorage closed - background scope cancelled")
            } catch (e: Exception) {
                Logger.e(LogTags.CHAIN, "IosFileStorage.close: cancel/join failed", e)
            }
        }
    }
}

/**
 * Exception thrown when insufficient disk space is available
 */
class InsufficientDiskSpaceException(
    val required: Long,
    val available: Long
) : Exception(
    "Insufficient disk space. Required: ${required / 1024 / 1024}MB, " +
    "Available: ${available / 1024 / 1024}MB"
)

/**
 * Encodes a caller-supplied task/chain id for safe use as a single filesystem path
 * component (e.g. `"$id.json"` in [saveTaskMetadata], `"${chainId}_progress.json"` in the
 * chain-progress paths).
 *
 * Task and chain ids come straight from the public API (`scheduler.enqueue(id, ...)`,
 * `TaskChain.withId(...)`) and are interpolated directly into filenames throughout this
 * class. Unescaped, an id containing `/` lets a caller construct additional path
 * segments, and an id that is exactly `.` or `..` is a reserved filesystem name with
 * special meaning — either could produce a path outside the intended storage directory
 * (`safeAppend` only guards against `URLByAppendingPathComponent` returning null; it has
 * no traversal awareness).
 *
 * Deliberately narrow — only `/`, a bare `.`/`..`, and (to keep the mapping injective,
 * see below) literal `%` are percent-encoded. Every other character, including letters,
 * digits, `-`, `_`, dots within a longer string, and Unicode, passes through unchanged.
 * Broadening this (e.g. escaping all non-ASCII) would change the on-disk filename for
 * ids that are already safe today, silently breaking `loadTaskMetadata`/
 * `loadChainDefinition` for tasks an app scheduled before upgrading past this fix. Only
 * genuinely hazardous ids change filename — and a genuinely hazardous id was never going
 * to resolve correctly before this fix either.
 *
 * `%` is escaped first, before `/`, so the mapping stays injective: without this, an id
 * containing the literal text `"%2F"` would be indistinguishable on disk from an id
 * containing a real `/` — two different ids silently sharing one file.
 */
internal fun String.encodeAsPathComponent(): String {
    if (this == ".") return "%2E"
    if (this == "..") return "%2E%2E"
    if ('%' !in this && '/' !in this) return this
    return replace("%", "%25").replace("/", "%2F")
}

/**
 * Exact inverse of [encodeAsPathComponent] — recovers a task/chain id from its on-disk
 * filename. Needed when enumerating a metadata directory, where the filename is the only
 * record of the id (see [IosFileStorage.findTaskIdsByWorkerOrTag]).
 *
 * **Replacement order is the reverse of the encoder's and must stay that way.** The encoder
 * escapes `%` *before* `/`, so the decoder must unescape `/` *before* `%`. Decoding in the
 * other order would corrupt any id containing the literal text `"%2F"`: `"a%2Fb"` encodes to
 * `"a%252Fb"`, and unescaping `%25`→`%` first would yield `"a%2Fb"` which a subsequent
 * `%2F`→`/` pass would then wrongly turn into `"a/b"` — a different id.
 */
internal fun String.decodeFromPathComponent(): String {
    if (this == "%2E") return "."
    if (this == "%2E%2E") return ".."
    if ('%' !in this) return this
    return replace("%2F", "/").replace("%25", "%")
}

