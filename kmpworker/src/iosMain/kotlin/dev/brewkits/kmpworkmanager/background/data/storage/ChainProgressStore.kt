@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.encodeAsPathComponent
import dev.brewkits.kmpworkmanager.utils.LogTags
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSThread
import platform.Foundation.NSURL

/**
 * Owns chain-progress persistence: the debounced write buffer, the emergency synchronous
 * flush, self-healing corrupt-file recovery, and the self-healed-chain flag.
 *
 * Stage 1 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 * `IosFileStorage` keeps its progress methods and delegates each one here, so no call site
 * outside this package changed; `ChainExecutor` alone has twenty of them.
 *
 * Everything below was moved verbatim from `IosFileStorage`, including the comments that
 * record *why* each guard exists — several of them are the surviving evidence of bugs this
 * project already paid for once (progress resurrection after abandon, the re-buffer clobber,
 * nested `runBlocking` thread starvation). Only the collaborator references changed: file I/O
 * and coordination now go through [io], and the chains directory arrives as a lambda so the
 * façade's `by lazy` directory creation still happens on first touch rather than at
 * construction.
 *
 * **Lock ordering:** [progressMutex] is completely independent of `IosFileStorage`'s
 * `queueMutex` and `enqueueMutex`; no code path may hold this mutex and either of those at
 * the same time. Splitting this class out of the god-class does not relax that rule — it is
 * what makes it enforceable, since this file now physically cannot reach the queue locks.
 *
 * @param io the shared file-I/O + coordination primitives.
 * @param backgroundScope the storage instance's scope. Shared with the façade on purpose:
 * `IosFileStorage.close()` cancels it, and the debounced flush job must die with it.
 * @param persistenceJson tolerant reader for persisted data (`ignoreUnknownKeys`). Note the
 * asymmetry, preserved from the original: reads go through this, writes use plain [Json].
 * @param chainsDir resolves the chains directory. A lambda, not an `NSURL`, so that the
 * façade's lazy directory creation is not forced at construction time.
 */
internal class ChainProgressStore(
    private val io: StorageFileIo,
    private val backgroundScope: CoroutineScope,
    private val persistenceJson: Json,
    private val chainsDir: () -> NSURL
) {

    /**
     * Buffered I/O for progress saves
     * In-memory buffer to batch NSFileCoordinator calls (90% I/O reduction)
     */
    private val progressBuffer = mutableMapOf<String, ChainProgress>()
    private val progressMutex = Mutex()
    private var flushJob: kotlinx.coroutines.Job? = null
    private var flushCompletionSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // Tracks chain IDs whose progress file was deleted by self-healing during this process
    // lifetime. Used by executeChain to prevent non-idempotent chains from silently
    // restarting after corruption.
    //
    // Backed by a lock-free CAS loop (AtomicReference<Set<String>>), NOT progressMutex — the
    // writer is loadChainProgress()'s corrupt-data catch block, which runs inside the
    // non-suspend `coordinated()` callback and therefore cannot suspend to acquire a
    // coroutine Mutex. It previously bridged with `runBlocking { progressMutex.withLock {} }`,
    // which reintroduces exactly the "runBlocking can starve Dispatchers.Default" risk this
    // codebase already fixed once for flushAllPendingProgress's own history (see that
    // function's KDoc) — corrupt-progress recovery is rare, but a coroutine calling
    // loadChainProgress from Dispatchers.Default while this briefly blocks the thread is a
    // real, if narrow, deadlock-adjacent risk on Kotlin/Native's bounded thread pool.
    private val selfHealedProgressChains = kotlin.concurrent.AtomicReference<Set<String>>(emptySet())

    /**
     * Test-only: when set to true, the next [flushNow] call throws synchronously.
     * Used by close()-finally regression tests to deterministically simulate the
     * real failure modes (full disk, EACCES, NSFileCoordinator error) which are
     * otherwise hard to trigger in the simulator-test sandbox. Production code
     * never reads or writes this field.
     */
    internal var testForceFlushFailure: Boolean = false

    /**
     * Test-only: delay inserted before each per-chain write inside [flushProgressBuffer].
     *
     * Widens the window in which a flush can be cancelled *mid-write*, which is the only
     * state where the `finally` below matters. Without it the write loop finishes far too
     * quickly in test mode to land a cancellation inside it — an earlier attempt to provoke
     * the deadlock through the public API alone failed across ~65 rounds of contention.
     * Production code never reads or writes this field. Same pattern as
     * `IosFileStorage.testEnqueueInternalDelayMs`.
     */
    internal var testFlushWriteDelayMs: Long = 0L

    private companion object {
        private const val FLUSH_DEBOUNCE_MS = 100L

        /** Wall-clock budget for the flush itself, leaving headroom before the iOS watchdog. */
        private const val FLUSH_TIMEOUT_MS = 450L

        /**
         * Budget for the force-release that follows a timed-out flush. Sized so the caller's
         * total worst case stays at [FLUSH_TIMEOUT_MS] + this, i.e. the 500 ms the design
         * documents — rather than "450 ms plus however long a mutex holder takes".
         */
        private const val FORCE_RELEASE_BUDGET_MS = 50L
    }

    /**
     * Buffer a progress update for this chain. The buffer is flushed to disk after a
     * [FLUSH_DEBOUNCE_MS] debounce window, batching rapid updates into a single write.
     *
     * For critical checkpoints (chain completion, BGTask expiration), call [flushNow]
     * immediately after to guarantee durability before the process is suspended.
     *
     * @param progress The progress state to save
     */
    suspend fun saveChainProgress(progress: ChainProgress) {
        progressMutex.withLock {
            // Update buffer (O(1) in-memory operation)
            progressBuffer[progress.chainId] = progress

            Logger.v(
                LogTags.CHAIN,
                "Buffered progress for ${progress.chainId} " +
                    "(${progress.getCompletionPercentage()}% complete, " +
                    "buffer size: ${progressBuffer.size})"
            )

            // Schedule debounced flush (only if not currently flushing)
            if (flushCompletionSignal == null) {
                flushJob?.cancel()
                flushJob = backgroundScope.launch {
                    delay(FLUSH_DEBOUNCE_MS)
                    flushProgressBuffer()
                }
            }
        }
    }

    /**
     * Flush buffered progress to disk (batched write).
     *
     * Writes all buffered progress in one batch, reducing NSFileCoordinator calls from
     * N (one per progress update) to the number of chains active during the debounce window.
     * Uses [flushCompletionSignal] to coordinate with concurrent [flushNow] calls.
     */
    // Suppressions, here and on loadChainProgress: both functions are verbatim moves whose
    // findings were carried in kmpworker's detekt baseline at their old location. A baseline
    // keys on declaration text, so moving code makes it look new. The shapes detekt objects
    // to are load-bearing — the nesting is the try/finally that re-buffers unwritten updates,
    // and `catch (Exception)` + `is CancellationException` is how a flush distinguishes "this
    // write failed, keep the data" from "we were cancelled, rethrow". Rewriting either to
    // please the linter during a refactor whose whole contract is "no behaviour change" is
    // how a move turns into a regression.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun flushProgressBuffer() {
        // No elvis on this `withLock`: the lambda either returns from the whole function (the
        // empty-buffer branch below) or yields a non-null Pair, so the `?: return` that used
        // to follow it was unreachable — and read as though an empty buffer arrived here as
        // null, which it never does.
        val (bufferSnapshot, completionSignal) = progressMutex.withLock {
            if (progressBuffer.isEmpty()) {
                return
            }

            // Create completion signal to coordinate with flushNow()
            val newSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
            flushCompletionSignal = newSignal

            val snapshot = progressBuffer.toMap()
            progressBuffer.clear()

            Logger.d(LogTags.CHAIN, "Flushing ${snapshot.size} progress updates to disk")

            // Return snapshot and signal for processing outside lock
            Pair(snapshot, newSignal)
        }

        // Write all progress files in batch (outside mutex to allow concurrent saves)
        val remainingToFlush = bufferSnapshot.toMutableMap()
        try {
            for ((chainId, progress) in bufferSnapshot) {
                // Check cancellation before each file write. This ensures that if
                // withTimeoutOrNull fires, we exit as soon as the current NSFileCoordinator
                // call returns (the coordinator itself cannot be interrupted mid-call).
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                if (testFlushWriteDelayMs > 0L) delay(testFlushWriteDelayMs)

                val progressFile = chainsDir().safeAppend("${chainId.encodeAsPathComponent()}_progress.json")
                val json = Json.encodeToString(progress)

                try {
                    // Use coordinatedSuspend (not coordinated) here: flushProgressBuffer is a
                    // suspend fun running on Dispatchers.Default. coordinated() uses runBlocking
                    // which would block the Dispatchers.Default thread while NSFileCoordinator
                    // waits on IosDispatchers.IO — thread starvation with N concurrent chains.
                    // coordinatedSuspend() suspends the coroutine instead of blocking the thread.
                    io.coordinatedSuspend(progressFile, write = true) { safeUrl ->
                        io.writeStringToFile(safeUrl, json)
                    }
                    remainingToFlush.remove(chainId)
                    Logger.v(
                        LogTags.CHAIN,
                        "Flushed progress for $chainId (${progress.getCompletionPercentage()}% complete)"
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Logger.e(LogTags.CHAIN, "Failed to flush progress for $chainId", e)
                    // We don't remove it from remainingToFlush, so it gets re-buffered in finally block
                }
            }

            Logger.i(LogTags.CHAIN, "✅ Progress flush completed (${bufferSnapshot.size} updates)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            Logger.w(
                LogTags.CHAIN,
                "Progress flush cancelled! ${remainingToFlush.size} unwritten updates " +
                    "will be re-buffered."
            )
            throw e
        } finally {
            // NonCancellable is load-bearing, not decoration. This block's job is to complete
            // `completionSignal`, and `flushNow()` parks on that signal indefinitely:
            //
            //     flushJob?.cancelAndJoin()                                    // can cancel THIS flush
            //     val signal = progressMutex.withLock { flushCompletionSignal }
            //     signal?.await()                                              // forever, if nobody completes it
            //
            // `Mutex.withLock` suspends when the mutex is contended, and suspending inside a
            // cancelled coroutine throws — so without this wrapper, a flush cancelled while a
            // concurrent `saveChainProgress` held the mutex would skip both the reset below
            // and `completionSignal.complete(Unit)`, leaving a signal nobody will ever
            // complete for the next `flushNow()` — i.e. `IosFileStorage.close()` or a chain
            // completion hanging for good.
            //
            // The uncontended fast path does not suspend and so does not throw, which is why
            // this never showed up in testing; `ProgressFlushDeadlockTest` demonstrates the
            // underlying mechanism directly rather than relying on winning that race.
            withContext(NonCancellable) {
                progressMutex.withLock {
                    flushCompletionSignal = null

                    // Re-buffer any items that failed or were cancelled to prevent data loss —
                    // but ONLY if no newer update arrived for that chainId while we were writing
                    // outside the lock. saveChainProgress() can run concurrently during that
                    // window (it only needs progressMutex, which this loop doesn't hold), so an
                    // unconditional putAll() here would clobber a legitimately newer in-memory
                    // value with this stale pre-failure snapshot — silently regressing progress
                    // and, after a crash before the next successful flush, causing a resumed
                    // chain to re-run an already-completed step with a non-idempotent worker.
                    for ((chainId, progress) in remainingToFlush) {
                        if (!progressBuffer.containsKey(chainId)) {
                            progressBuffer[chainId] = progress
                        }
                    }
                
                    // Re-schedule a flush if new items arrived or if we rebuffered items.
                    // Previously: saveChainProgress() called during a flush saw flushCompletionSignal != null
                    // and skipped scheduling a new job. After the flush completed, those items stayed in
                    // progressBuffer indefinitely if no further saveChainProgress() was called.
                    if (progressBuffer.isNotEmpty() && flushJob?.isActive != true) {
                        flushJob = backgroundScope.launch {
                            delay(FLUSH_DEBOUNCE_MS)
                            flushProgressBuffer()
                        }
                        Logger.d(
                            LogTags.CHAIN,
                            "Scheduled follow-up flush for ${progressBuffer.size} items " +
                                "buffered during previous flush"
                        )
                    }
                }
            }
            completionSignal.complete(Unit)
        }
    }

    /**
     * Flush buffered progress immediately (blocking)
     * Use before critical points: chain completion, shutdown, BGTask expiration
     *
     * **When to call:**
     * - Chain completion (ensure final progress is persisted)
     * - App shutdown / BGTask expiration (prevent data loss)
     * - Before reading progress (ensure buffer is flushed)
     *
     * Concurrent flush calls are safe — atomic state management ensures only one flush
     * runs at a time; additional callers await the in-progress flush.
     *
     * @throws Exception if flush fails (caller should handle)
     */
    suspend fun flushNow() {
        if (testForceFlushFailure) {
            error("test-induced flush failure (V250CloseFinallyTest)")
        }
        // Cancel pending debounced flush
        flushJob?.cancelAndJoin()

        // Wait for any in-progress flush to complete
        val signal = progressMutex.withLock {
            flushCompletionSignal
        }
        signal?.await()

        // Now safe to flush immediately
        flushProgressBuffer()

        Logger.d(LogTags.CHAIN, "Immediate progress flush completed")
    }

    /**
     * Flush all pending progress to disk synchronously.
     *
     * Designed for call sites that cannot suspend: BGTask expiration handler,
     * `applicationWillResignActive`, and shutdown sequences. Blocks the calling thread
     * for up to 450 ms (leaving 50 ms headroom before the iOS 500 ms watchdog).
     *
     * Prefer [flushNow] from suspend contexts — it does the same work without blocking.
     */
    fun flushAllPendingProgress() {
        Logger.i(
            LogTags.CHAIN,
            "Emergency progress flush requested — " +
                "thread: '${NSThread.currentThread.name}', isMain: ${NSThread.isMainThread}"
        )

        // Launch the coroutine eagerly on Dispatchers.Default BEFORE runBlocking starts.
        //
        // Previous impl: dispatch_async(highQueue) { runBlocking { flushNow() } }
        // Problem: the GCD async block called runBlocking which internally called coordinated()
        // which called ANOTHER runBlocking — nested runBlocking. The inner runBlocking blocked the
        // GCD thread while IosDispatchers.IO ran the actual NSFileCoordinator call. With N chains
        // in the progress buffer, this caused N serial GCD-thread-blocks.
        //
        // Fix: launch coroutine immediately (queued on Dispatchers.Default, not blocked),
        // then runBlocking only parks to wait for the deferred result. The coroutine itself
        // uses coordinatedSuspend() which suspends the coroutine (releases the Default thread)
        // while NSFileCoordinator runs on IosDispatchers.IO.
        //
        // Timeout: 450ms — leaves 50ms headroom before the iOS 500ms Watchdog kill.
        // Use backgroundScope (SupervisorJob + Dispatchers.Default) instead of GlobalScope —
        // same semantics, avoids the DelicateCoroutinesApi opt-in and ties the deferred
        // to the storage instance lifecycle rather than the process lifetime.
        val deferred = backgroundScope.async { flushNow() }

        val timedOut = runBlocking {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { deferred.await() }
        } == null

        if (timedOut) {
            deferred.cancel()
            // Force-release the completion signal so saveChainProgress() is not left stuck.
            //
            // This is bounded, and that is the point. It used to be a bare `runBlocking` with
            // no timeout, which quietly broke this function's entire contract: the KDoc
            // promises the caller is blocked for at most 450 ms, and the log line below used
            // to assert "Main thread safe (Watchdog respected)" — while the code could park
            // the main thread on `progressMutex` for as long as its holder wanted. The path
            // is reached precisely when the system is already contended (that is why the
            // flush timed out), so it was unbounded exactly when contention was likeliest.
            //
            // FORCE_RELEASE_BUDGET_MS is the 50 ms of headroom the 450 ms figure already
            // reserves, so the worst case for the caller stays under the documented 500 ms.
            val released = runBlocking {
                withTimeoutOrNull(FORCE_RELEASE_BUDGET_MS) {
                    progressMutex.withLock {
                        flushCompletionSignal?.complete(Unit)
                        flushCompletionSignal = null
                    }
                    flushJob?.cancel()
                    true
                }
            } == true

            Logger.w(
                LogTags.CHAIN,
                "⚠️ Progress flush timed out after ${FLUSH_TIMEOUT_MS}ms — likely " +
                    "NSFileCoordinator contention (iCloud sync or file lock). Deferred " +
                    "coroutine cancelled; signal " +
                    (if (released) "force-released." else
                        "NOT released within ${FORCE_RELEASE_BUDGET_MS}ms — the flush's own " +
                            "finally completes it instead.") +
                    " Caller blocked for at most " +
                    "${FLUSH_TIMEOUT_MS + FORCE_RELEASE_BUDGET_MS}ms."
            )
        }

        Logger.i(LogTags.CHAIN, "Emergency progress flush ${if (timedOut) "timed out" else "completed"}")
    }

    /**
     * Load chain progress from file with self-healing for corrupt data.
     *
     * **Schema evolution:** If [ChainProgress.schemaVersion] in the file is older than
     * [ChainProgress.CURRENT_SCHEMA_VERSION], logs a warning and attempts to load the
     * data anyway (additive changes survive via `ignoreUnknownKeys = true`). If the schema gap
     * is too large to handle gracefully, add an explicit migration branch here before
     * bumping [ChainProgress.CURRENT_SCHEMA_VERSION].
     *
     * @param chainId The chain ID
     * @return The progress state, or null if no progress file exists or is corrupt
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadChainProgress(chainId: String): ChainProgress? {
        val progressFile = chainsDir().safeAppend("${chainId.encodeAsPathComponent()}_progress.json")

        return io.coordinated(progressFile, write = false) { safeUrl ->
            val json = io.readStringFromFile(safeUrl) ?: return@coordinated null

            try {
                val progress = persistenceJson.decodeFromString<ChainProgress>(json)

                // Schema version check — warn on version mismatch but do not self-heal
                // for additive changes. Only bump CURRENT_SCHEMA_VERSION for breaking changes
                // and add a migration branch below.
                val fileVersion = progress.schemaVersion
                val currentVersion = ChainProgress.CURRENT_SCHEMA_VERSION
                if (fileVersion < currentVersion) {
                    Logger.w(
                        LogTags.CHAIN,
                        "Chain $chainId progress schema v$fileVersion < current v$currentVersion. " +
                            "Loaded successfully (additive change). " +
                            "Add migration logic here if fields were removed or types changed."
                    )
                    // ── Future migration example ──────────────────────────────────────────
                    // when (fileVersion) {
                    //     0 -> migrateFromV0(progress)
                    //     else -> progress
                    // }
                }

                progress
            } catch (e: Exception) {
                Logger.e(
                    LogTags.CHAIN,
                    "🩹 Self-healing: Corrupt chain progress detected for $chainId. " +
                        "Deleting corrupt file...",
                    e
                )

                // Delete corrupt progress file
                io.deleteFile(progressFile)

                // Record that this chain's progress was self-healed so that executeChain can
                // refuse to restart non-idempotent chains (e.g. payment workflows).
                // Lock-free CAS add — see selfHealedProgressChains' declaration for why this
                // isn't progressMutex-protected like progressBuffer is.
                markProgressSelfHealed(chainId)

                Logger.w(
                    LogTags.CHAIN,
                    "Corrupt progress for chain $chainId removed. " +
                    "Chain restart will be allowed only if all tasks are idempotent."
                )
                null
            }
        }
    }

    /**
     * Returns `true` (and clears the flag) if this chain's progress file was deleted by
     * self-healing corruption recovery since this process started.
     *
     * Call this once, immediately before executing a chain. A `true` result means the
     * chain has lost its execution history — callers must check [TaskRequest.isIdempotent]
     * for every task in the chain before deciding whether to restart or quarantine.
     */
    suspend fun consumeSelfHealedFlag(chainId: String): Boolean {
        while (true) {
            val current = selfHealedProgressChains.value
            if (chainId !in current) return false
            if (selfHealedProgressChains.compareAndSet(current, current - chainId)) return true
        }
    }

    /**
     * Lock-free CAS add to [selfHealedProgressChains]. Split out from [loadChainProgress]'s
     * catch block (a non-suspend context) so that block stays a plain function call instead
     * of a `runBlocking` bridge.
     */
    private fun markProgressSelfHealed(chainId: String) {
        while (true) {
            val current = selfHealedProgressChains.value
            if (chainId in current) return
            if (selfHealedProgressChains.compareAndSet(current, current + chainId)) return
        }
    }

    /**
     * Delete chain progress file.
     *
     * Should be called when:
     * - Chain completes successfully
     * - Chain is abandoned (exceeded retry limit)
     * - Chain definition is deleted
     *
     * @param chainId The chain ID
     */
    suspend fun deleteChainProgress(chainId: String) {
        // Buffer-eviction MUST run before the disk delete. Otherwise the next debounced
        // `flushProgressBuffer` (or `flushNow` from executeChain's finally block) writes
        // the buffered ChainProgress back to disk, resurrecting state we just abandoned.
        // Same root cause as the v2.5 BUG 13 finding: chain-abandon paths would call
        // deleteChainProgress then immediately hit flushNow, restoring the deleted file
        // and leaving the chain effectively un-abandoned across BGTask invocations.
        progressMutex.withLock {
            progressBuffer.remove(chainId)
        }
        val progressFile = chainsDir().safeAppend("${chainId.encodeAsPathComponent()}_progress.json")
        io.deleteFile(progressFile)
        Logger.d(LogTags.CHAIN, "Deleted chain progress $chainId (buffer + disk)")
    }
}
