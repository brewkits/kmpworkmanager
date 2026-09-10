@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import dev.brewkits.kmpworkmanager.background.data.ChainTransaction
import dev.brewkits.kmpworkmanager.background.data.DynamicQueueConstraintSummary
import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.domain.TaskPriority
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingToURL
import platform.Foundation.seekToEndOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeData
import kotlin.concurrent.AtomicInt

/**
 * Owns the two append-only queues — the chain execution queue and the dynamic single-task
 * dispatch queue — together with their size accounting, the priority sort, and the atomic
 * chain REPLACE.
 *
 * Stage 3 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`), and the
 * one the plan calls riskiest. `AppendOnlyQueue` is left exactly as it is; what moves here is
 * ownership of its lifecycle and of the mutexes and counters around it.
 *
 * **Why this class depends on three other stores.** `replaceChainAtomic` is not a queue
 * operation that happens to touch other data — it is a transaction across four of them
 * (marker, definition, progress, queue) that must hold `queueMutex` for its whole duration.
 * Leaving it on the façade would mean exporting `queueMutex` so the façade could hold it,
 * which puts one half of a locking invariant in one file and the other half somewhere else.
 * Keeping the transaction next to the lock it depends on is what lets the invariant below be
 * checked by reading a single file.
 *
 * @param io the shared file-I/O + coordination primitives.
 * @param backgroundScope the storage instance's scope; the queues' compaction runs on it.
 * @param isTestMode passed through to [AppendOnlyQueue].
 * @param queueDir resolves the chain queue's directory.
 * @param tasksQueueDir resolves the dynamic-task queue's directory.
 * @param transactionLogFile resolves the append-only REPLACE audit log.
 * @param maxQueueSize hard cap on either queue's depth.
 * @param definitions chain definitions + deleted markers, for the REPLACE transaction and
 * the priority sort.
 * @param progress chain progress, for the REPLACE transaction.
 * @param metadata task metadata, for the dynamic queue's constraint summary.
 */
// TooManyFunctions: 15 against a threshold of 11. Splitting further would separate
// replaceChainAtomic from the queueMutex it must hold for its whole duration, which is the
// one thing this class exists to keep together. The two queues could in principle become two
// classes, but they share the size-cap policy and the transaction touches both.
@Suppress("TooManyFunctions")
internal class ChainQueueRepository(
    private val io: StorageFileIo,
    private val backgroundScope: CoroutineScope,
    private val isTestMode: Boolean,
    private val queueDir: () -> NSURL,
    private val tasksQueueDir: () -> NSURL,
    private val transactionLogFile: () -> NSURL,
    private val maxQueueSize: Int,
    private val definitions: ChainDefinitionStore,
    private val progress: ChainProgressStore,
    private val metadata: TaskMetadataStore
) {

    /**
     * Test-only: optional delay (ms) inserted inside `enqueueChainInternal` while the
     * enqueue mutex is held, so a regression test can widen the check-then-act window
     * deterministically. Production code never reads or writes this field.
     */
    internal var testEnqueueInternalDelayMs: Long = 0L

    private val queue: AppendOnlyQueue by lazy {
        val dir = queueDir()
        io.ensureDirectoryExists(dir)
        AppendOnlyQueue(
            baseDirectoryURL = dir,
            compactionScope = backgroundScope,
            isTestMode = isTestMode
        )
    }

    private val tasksQueue: AppendOnlyQueue by lazy {
        val dir = tasksQueueDir()
        io.ensureDirectoryExists(dir)
        AppendOnlyQueue(
            baseDirectoryURL = dir,
            compactionScope = backgroundScope,
            isTestMode = isTestMode
        )
    }

    // ==================== Lock-Ordering Invariant ====================
    // This class uses two coroutine mutexes for the chain queue and two for the task queue.
    // To prevent deadlock they must NEVER be acquired in conflicting orders. The only
    // permitted ordering is:
    //
    //   queueMutex  →  enqueueMutex     (replaceChainAtomic holds queueMutex, then
    //                                    acquires enqueueMutex around the
    //                                    enqueueChainInternal call so the
    //                                    check-then-act is atomic against
    //                                    concurrent enqueueChain callers)
    //
    // AppendOnlyQueue has one lock of its own, and this class's queueMutex wraps calls into
    // it, giving the outer ordering:
    //   ChainQueueRepository.queueMutex → AppendOnlyQueue.queueMutex
    //
    // (This used to name an AppendOnlyQueue.corruptionMutex in the middle. That lock was
    // acquired in exactly one place in that class and protected nothing its queueMutex did
    // not already protect, so it was removed in v3.5.0 — see the corruption branch of
    // AppendOnlyQueue.dequeue().)
    //
    // ChainProgressStore's progressMutex is also in play, via replaceChainAtomic's call to
    // deleteChainProgress: the real ordering there is queueMutex → progressMutex.
    //
    // (This corrects the invariant as it was written in IosFileStorage, which claimed
    // progressMutex was "COMPLETELY INDEPENDENT" and that "no code path may hold both
    // progressMutex and queueMutex simultaneously". replaceChainAtomic has always held both.
    // The code is safe — nothing anywhere takes progressMutex before queueMutex, so no cycle
    // exists — but a rule that production code visibly violates teaches the next reader to
    // ignore the rule. The accurate statement is the ordering above.)
    //
    // IMPORTANT: any new code that acquires two of these mutexes must follow this order.
    // Acquiring in reverse order will cause a coroutine deadlock (all involved coroutines
    // suspend forever, no error is thrown, the BGTask is killed by iOS watchdog).
    // ===================================================================

    /** Protects coordinated queue operations (dequeue, replaceChainAtomic, etc.). */
    private val queueMutex = Mutex()

    /**
     * Dedicated mutex for enqueue operations to ensure atomic check-then-act
     * for the queue-size cap.
     *
     * `enqueueChain` has a check-then-act pattern: two concurrent callers could both
     * read counter = cap - 1, both pass the limit check, and both enqueue — resulting in
     * a queue over the cap. This mutex prevents that race.
     *
     * Separate from [queueMutex] to avoid deadlock: `replaceChainAtomic` holds queueMutex
     * and enqueues internally.
     */
    private val enqueueMutex = Mutex()

    /**
     * Queue size counter for O(1) size checks.
     * Initialized to [UNINITIALIZED_COUNTER] (-1) instead of 0.
     *
     * **Why -1 and not 0:** if `enqueueChain` is called before the real value is known, a
     * limit check against 0 allows enqueues even when the queue already holds cap-1 items
     * (app restart with a nearly-full queue).
     *
     * The sentinel signals `enqueueChain` to read the actual size from disk (O(N), one-time)
     * before checking the limit. Afterwards the counter is accurate and checks are O(1).
     */
    private val queueSizeCounter = AtomicInt(UNINITIALIZED_COUNTER)

    // There is no tasksQueueMutex counterpart to queueMutex. One was declared alongside
    // these when the dynamic-task queue was added (ca9db14) and never acquired anywhere; it
    // sat unused in IosFileStorage until this move made detekt notice. Dropped rather than
    // carried: a mutex nobody takes is not protection, it is a claim of protection.
    private val enqueueTasksMutex = Mutex()
    private val tasksQueueSizeCounter = AtomicInt(UNINITIALIZED_COUNTER)

    /**
     * Enqueue a chain ID to the queue (thread-safe, atomic).
     *
     * Wrapped in enqueueMutex to make the check-then-act atomic.
     * Without the mutex, two concurrent callers could both read counter=999, both pass the
     * maxQueueSize check, and both enqueue — exceeding the limit by the number of
     * concurrent callers.
     *
     * Delegates to [enqueueChainInternal] which is also used by [replaceChainAtomic]
     * (under queueMutex) to avoid deadlock — enqueueMutex and queueMutex are always
     * acquired in the same order: queueMutex first, enqueueMutex second.
     */
    suspend fun enqueueChain(chainId: String) = enqueueMutex.withLock {
        enqueueChainInternal(chainId)
    }

    /**
     * Internal enqueue without enqueueMutex — called from replaceChainAtomic (under queueMutex).
     *
     * **Cross-process safety:** [queueSizeCounter] is an in-process AtomicInt. If two
     * OS processes share the same storage path (e.g. main app + Notification Service
     * Extension), each process holds an independent counter that diverges after any
     * enqueue/dequeue in the other process. Using the counter for the maxQueueSize
     * check would allow the limit to be exceeded by the number of concurrent processes.
     *
     * Fix: always read the actual size from disk for the limit check. The counter is
     * still updated for in-process approximations (e.g. debug logging), but the
     * authoritative check is the disk size. Enqueue is not a hot path (called once
     * per chain schedule), so the O(disk) overhead is acceptable.
     */
    private suspend fun enqueueChainInternal(chainId: String) {
        // Always read disk size for the limit check — cross-process safe.
        val currentSize = queue.getSize()

        if (currentSize >= maxQueueSize) {
            Logger.e(
                LogTags.CHAIN,
                "Queue size limit reached ($maxQueueSize). Cannot enqueue chain: $chainId"
            )
            error("Queue size limit exceeded")
        }

        // Test-only hook: widens the check-then-act window so a regression test
        // can deterministically race two enqueue paths. No-op in production.
        if (testEnqueueInternalDelayMs > 0L) {
            delay(testEnqueueInternalDelayMs)
        }

        queue.enqueue(chainId)

        // Keep in-process counter roughly in sync for diagnostic logging.
        // Not used for enforcement — disk read above is authoritative.
        if (queueSizeCounter.value == UNINITIALIZED_COUNTER) {
            queueSizeCounter.value = currentSize + 1
        } else {
            queueSizeCounter.incrementAndGet()
        }

        Logger.v(LogTags.CHAIN, "Enqueued chain $chainId. Queue size (disk): $currentSize → ${currentSize + 1}")
    }

    /**
     * Dequeue the first chain ID from the queue (thread-safe, atomic)
     * Updates queue size counter atomically
     * @return Chain ID or null if queue is empty
     */
    suspend fun dequeueChain(): String? {
        // O(1) dequeue operation (with automatic compaction at 80% threshold)
        val chainId = queue.dequeue()

        if (chainId == null) {
            Logger.v(LogTags.CHAIN, "Queue is empty")
        } else {
            // Decrement counter atomically (lock-free), only if already initialized
            val currentVal = queueSizeCounter.value
            if (currentVal != UNINITIALIZED_COUNTER && currentVal > 0) {
                queueSizeCounter.decrementAndGet()
            }
            val remaining = if (queueSizeCounter.value == UNINITIALIZED_COUNTER) {
                "unknown"
            } else {
                queueSizeCounter.value.toString()
            }
            Logger.v(LogTags.CHAIN, "Dequeued chain $chainId. Remaining: $remaining")
        }

        return chainId
    }

    /**
     * Enqueue a task ID to the tasks queue (thread-safe, atomic).
     */
    suspend fun enqueueTask(id: String) = enqueueTasksMutex.withLock {
        val currentSize = tasksQueue.getSize()

        if (currentSize >= maxQueueSize) {
            Logger.e(
                LogTags.SCHEDULER,
                "Tasks queue size limit reached ($maxQueueSize). Cannot enqueue task: $id"
            )
            error("Tasks queue size limit exceeded")
        }

        tasksQueue.enqueue(id)

        if (tasksQueueSizeCounter.value == UNINITIALIZED_COUNTER) {
            tasksQueueSizeCounter.value = currentSize + 1
        } else {
            tasksQueueSizeCounter.incrementAndGet()
        }

        Logger.v(LogTags.SCHEDULER, "Enqueued task $id. Tasks queue size (disk): $currentSize → ${currentSize + 1}")
    }

    /**
     * Dequeue the first task ID from the tasks queue (thread-safe, atomic).
     */
    suspend fun dequeueTask(): String? {
        val taskId = tasksQueue.dequeue()

        if (taskId == null) {
            Logger.v(LogTags.SCHEDULER, "Tasks queue is empty")
        } else {
            val currentVal = tasksQueueSizeCounter.value
            if (currentVal != UNINITIALIZED_COUNTER && currentVal > 0) {
                tasksQueueSizeCounter.decrementAndGet()
            }
            val remaining = if (tasksQueueSizeCounter.value == UNINITIALIZED_COUNTER) {
                "unknown"
            } else {
                tasksQueueSizeCounter.value.toString()
            }
            Logger.v(LogTags.SCHEDULER, "Dequeued task $taskId. Remaining: $remaining")
        }

        return taskId
    }

    /**
     * True if [id] is currently sitting in the dynamic-task queue (dequeued only right
     * before [DynamicTaskDispatcher] executes it, and re-enqueued on retry/backoff/
     * constraint deferral). Used by [NativeTaskScheduler.observeTaskState] to distinguish
     * "waiting for the master dispatcher to pick it up" from "already dequeued" for a
     * dynamic-queue task — the latter is the closest signal available to "likely executing"
     * that doesn't require a live registry (see that method's KDoc for the full caveat).
     *
     * O(N) on queue size (bounded by [maxQueueSize]), same cost class as
     * [getDynamicQueueConstraintSummary] — acceptable since this is a diagnostic/observability
     * read, not called from a hot path.
     */
    internal suspend fun isTaskInDynamicQueue(id: String): Boolean = tasksQueue.getAllItems().contains(id)

    /**
     * Get current tasks queue size.
     */
    suspend fun getTasksQueueSize(): Int {
        val cached = tasksQueueSizeCounter.value
        return if (cached == UNINITIALIZED_COUNTER) {
            val actual = tasksQueue.getSize()
            tasksQueueSizeCounter.value = actual
            actual
        } else {
            cached
        }
    }

    /**
     * Get current queue size — always reads from disk for correctness.
     *
     * Multiple IosFileStorage instances sharing the same path (e.g. NativeTaskScheduler
     * and ChainExecutor) each have an independent in-memory queueSizeCounter that can
     * diverge after any enqueue/dequeue performed by the other instance.  Reading from
     * disk on every call prevents stale-counter bugs at the cost of a single I/O per call,
     * which is acceptable since getQueueSize() is called infrequently (once per BGTask).
     */
    suspend fun getQueueSize(): Int = queue.getSize()


    /**
     * Aggregate light/network profile of every task currently sitting in the dynamic-task
     * queue. Lets the master dispatcher be scheduled with constraints that actually match
     * what's pending, instead of always requesting an unconstrained `BGProcessingTask`.
     * See docs/ios-dynamic-task-scheduling.md § 5.
     *
     * Always reads from disk (like [getQueueSize]) — the same multi-instance staleness
     * concern applies, and this is called once per master-dispatcher schedule decision,
     * not per task, so the extra I/O is acceptable.
     *
     * **Performance**: O(N) on queue size (bounded by [maxQueueSize]) — one metadata
     * read per pending task.
     */
    internal suspend fun getDynamicQueueConstraintSummary(): DynamicQueueConstraintSummary {
        val ids = tasksQueue.getAllItems()
        var heavyCount = 0
        var networkCount = 0
        var chargingCount = 0
        var minFloorMs: Long? = null
        var everyPendingHasFloor = ids.isNotEmpty()
        for (id in ids) {
            // A dynamic task ID is either one-time or periodic metadata — never both.
            val meta = metadata.loadTaskMetadata(id, periodic = false) ?: metadata.loadTaskMetadata(id, periodic = true)
            if (meta?.get("isHeavyTask") == "true") heavyCount++
            if (meta?.get("requiresNetwork") == "true") networkCount++
            if (meta?.get("requiresCharging") == "true") chargingCount++

            val floorMs = meta?.get(DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS)?.toLongOrNull()
            if (floorMs == null) {
                everyPendingHasFloor = false
            } else {
                minFloorMs = if (minFloorMs == null) floorMs else minOf(minFloorMs, floorMs)
            }
        }
        return DynamicQueueConstraintSummary(
            pendingCount = ids.size,
            heavyCount = heavyCount,
            networkRequiredCount = networkCount,
            chargingRequiredCount = chargingCount,
            earliestBackoffFloorMs = if (everyPendingHasFloor) minFloorMs else null
        )
    }

    /**
     * Sort the execution queue by task priority (highest first).
     *
     * Drains all chain IDs from the queue, sorts them by the maximum [TaskPriority]
     * weight of their tasks, then re-enqueues in descending priority order.
     * This ensures CRITICAL and HIGH priority chains execute before NORMAL/LOW ones
     * within the same BGTask window.
     *
     * **Crash safety:** Chain definitions remain on disk throughout — only queue order
     * changes. If the process is killed mid-sort, chains may be orphaned in the queue
     * (definition files exist but queue is partially empty). The next BGTask invocation
     * will process whichever chains were successfully re-enqueued.
     *
     * Should be called once at the start of each batch execution window.
     */
    suspend fun sortQueueByPriority() {
        val size = queue.getSize()
        if (size <= 1) return  // Nothing to sort

        // Read all items WITHOUT dequeuing them to prevent data loss if process crashes
        val chainIds = queue.getAllItems()
        if (chainIds.isEmpty()) return

        // Sort by max priority weight (highest first), stable (preserves FIFO for equal priorities)
        // `loadChainDefinition` is suspend (the self-healing path needs to evict the
        // progress buffer entry under progressMutex), so we precompute weights
        // sequentially in a suspend-friendly loop rather than from inside a non-
        // suspend `sortedByDescending` lambda.
        val weights = mutableMapOf<String, Int>()
        for (chainId in chainIds) {
            weights[chainId] = definitions.loadChainDefinition(chainId)
                ?.flatten()
                ?.maxOfOrNull { it.priority.weight }
                ?: TaskPriority.NORMAL.weight
        }
        val sorted = chainIds.sortedByDescending { weights[it] ?: TaskPriority.NORMAL.weight }

        // Only replace if order actually changed
        if (sorted != chainIds) {
            queue.replaceContents(sorted)
            if (sorted.first() != chainIds.first()) {
                Logger.d(LogTags.CHAIN, "Queue reordered by priority: ${sorted.take(3).joinToString()} ...")
            }
        }
    }

    /**
     * Replace chain atomically
     *
     * **Problem:** Old REPLACE implementation had TOCTOU race condition:
     * 1. Mark deleted
     * 2. Delete old files
     * 3. Save new definition
     * 4. Enqueue async ← **GAP** - another thread could enqueue duplicate
     *
     * **Solution:** Atomic transaction with queue mutex:
     * - All steps under single queueMutex.withLock
     * - Synchronous enqueue (no async gap)
     * - Transaction log for debugging
     *
     * @param chainId Chain ID to replace
     * @param newSteps New chain steps
     * @throws Exception if transaction fails (rollback automatic via mutex)
     */
    // TooGenericExceptionCaught, here and in logTransaction: verbatim moves whose findings
    // were baselined at the old location. Both are deliberate — the transaction logs whatever
    // went wrong and rethrows, and the audit log swallows everything because a failed audit
    // write must not fail the operation it was auditing.
    @Suppress("TooGenericExceptionCaught")
    suspend fun replaceChainAtomic(
        chainId: String,
        newSteps: List<List<TaskRequest>>
    ) = queueMutex.withLock {
        val txn = ChainTransaction(
            chainId = chainId,
            action = "REPLACE",
            timestamp = (NSDate().timeIntervalSince1970 * 1000).toLong(),
            succeeded = false
        )

        Logger.i(LogTags.CHAIN, "🔄 Atomic REPLACE transaction started for chain $chainId")

        try {
            // Step 1: Mark as deleted (prevent concurrent execution)
            definitions.markChainAsDeleted(chainId)

            // Step 2: Delete old files
            definitions.deleteChainDefinition(chainId)
            progress.deleteChainProgress(chainId)

            // Step 3: Save new definition
            definitions.saveChainDefinition(chainId, newSteps)

            // Step 4: Enqueue under enqueueMutex INSIDE queueMutex (the documented
            // lock order: queueMutex first, enqueueMutex second — never the reverse).
            //
            // History: previously called `enqueueChainInternal(chainId)` directly,
            // bypassing enqueueMutex entirely. That left a window where a concurrent
            // [enqueueChain] caller (holding enqueueMutex but not queueMutex) and this
            // REPLACE could both read size = maxQueueSize - 1, both pass the limit
            // check, and both enqueue — exceeding the queue cap by 1+ per concurrent
            // caller. Cap drift causes downstream "Queue size limit exceeded" errors
            // that surface far from this site. The fix: acquire enqueueMutex here so
            // the check-then-act inside [enqueueChainInternal] is observed atomically.
            enqueueMutex.withLock {
                enqueueChainInternal(chainId)
            }

            // Step 5: Log successful transaction
            val successTxn = txn.copy(succeeded = true)
            logTransaction(successTxn)

            Logger.i(LogTags.CHAIN, "✅ Atomic REPLACE transaction completed for chain $chainId")

        } catch (e: Exception) {
            // Log failed transaction
            val failedTxn = txn.copy(succeeded = false, error = e.message)
            logTransaction(failedTxn)

            Logger.e(LogTags.CHAIN, "❌ Atomic REPLACE transaction failed for chain $chainId", e)
            throw e
        }
    }

    /**
     * Log transaction for debugging.
     * Append-only log for auditing chain operations.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun logTransaction(txn: ChainTransaction) {
        try {
            val logFile = transactionLogFile()
            val json = Json.encodeToString(txn)
            val line = "$json\n"

            val path = logFile.path ?: return

            // Create if doesn't exist (outside coordinated block for efficiency)
            if (!io.fileManager.fileExistsAtPath(path)) {
                io.fileManager.createFileAtPath(path, null, null)
            }

            io.coordinated(logFile, write = true) { safeUrl ->
                memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val fileHandle = NSFileHandle.fileHandleForWritingToURL(safeUrl, errorPtr.ptr)

                    if (fileHandle == null) {
                        val error = errorPtr.value
                        Logger.w(
                            LogTags.CHAIN,
                            "Failed to open transaction log: ${error?.localizedDescription}"
                        )
                        return@coordinated  // Non-critical, skip logging
                    }

                    try {
                        fileHandle.seekToEndOfFile()
                        fileHandle.writeData(line.toNSData())
                    } finally {
                        try {
                            fileHandle.closeFile()
                        } catch (e: Exception) {
                            Logger.w(LogTags.CHAIN, "Error closing transaction log file handle", e)
                        }
                    }
                }
            }

            Logger.d(
                LogTags.CHAIN,
                "Transaction logged: ${txn.action} - " +
                    (if (txn.succeeded) "SUCCESS" else "FAILED")
            )
        } catch (e: Exception) {
            Logger.w(LogTags.CHAIN, "Failed to log transaction (non-critical)", e)
        }
    }

    /**
     * Returns all chain IDs currently active in the queue (not yet dequeued).
     *
     * Reads the live queue from disk. Used by stress tests to verify queue
     * integrity without relying on getQueueSize() which counts physical entries
     * including logically-deleted REPLACE residues.
     */
    suspend fun getActiveChainIds(): List<String> = queue.getAllItems()

    /**
     * Scans all chain IDs currently in the execution queue and returns those whose
     * definition contains at least one task matching [workerClassName] OR whose
     * [TaskRequest.tags] contains [tag].
     *
     * Either [workerClassName] or [tag] may be null — omitting both returns an empty list.
     *
     * **Performance:** O(N × S) where N = queue depth and S = average steps per chain.
     * This is a fire-and-forget maintenance path, not on the hot execution path.
     */
    suspend fun findChainIdsByWorkerOrTag(
        workerClassName: String? = null,
        tag: String? = null
    ): List<String> {
        if (workerClassName == null && tag == null) return emptyList()
        val allChainIds = queue.getAllItems()
        return allChainIds.filter { chainId ->
            val steps = definitions.loadChainDefinition(chainId) ?: return@filter false
            steps.flatten().any { task ->
                (workerClassName != null && task.workerClassName == workerClassName) ||
                (tag != null && tag in task.tags)
            }
        }
    }

    /** Converts a UTF-8 string to NSData using byte-array encoding (not char count). */
    private fun String.toNSData(): NSData {
        val bytes = this.encodeToByteArray()
        return bytes.usePinned { pinned ->
            NSData.create(
                bytes = pinned.addressOf(0),
                length = bytes.size.toULong()
            )
        }
    }

    private companion object {
        /** Sentinel: counter not yet seeded from disk. */
        private const val UNINITIALIZED_COUNTER = -1
    }
}
