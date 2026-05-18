@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P1/P2 bug surfaced by the proactive audit: nested
 * `withTimeout` in [ChainExecutor.executeTask] at the deepest task-level layer.
 *
 * **Bug**: when an outer scope's `TimeoutCancellationException` propagates DOWN
 * through `withTimeout(taskTimeout)`, the inner catch used to log
 * `"Timeout after Xms (limit: ${taskTimeout}ms)"`, emit a
 * `TaskCompletionEvent(success=false, message="⏱️ Timeout after Xms")` to
 * `TaskEventBus`, and a corresponding `TaskFailedEvent` to telemetry hooks —
 * even when the task ran for far less than `taskTimeout`. Apps observing
 * `TaskEventBus` saw "task X timed out" for tasks that were actually pre-empted
 * by an outer scope's wall-clock budget.
 *
 * Same bug class as `V250NestedTimeoutMisattributionTest` (`executeChain` layer)
 * but at the task layer. The chain-layer `coroutineScope` re-throws the inner
 * cancellation cleanly, so chain progress isn't polluted — but the telemetry
 * emitted from inside `executeTask`'s catch is already on the bus by then.
 *
 * **Fix**: compare `duration` (elapsed wall-clock since task start) against
 * `taskTimeout`. If `duration < taskTimeout`, the cancellation cannot have come
 * from our own `withTimeout(taskTimeout)` timer — rethrow so the outer scope
 * observes its own cancellation, without emitting misleading telemetry.
 *
 * **Test strategy**: subscribe to `TaskEventBus.events` BEFORE triggering a
 * caller-side `withTimeout(1500ms)` that fires while a slow worker is mid-`delay`.
 * Verify NO `TaskCompletionEvent` with message starting with "⏱️ Timeout after"
 * is emitted for that task.
 */
class V250TaskTimeoutMisattributionTest {

    private fun makeTempDir(): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_tasktimeout_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir()
    )

    @Test
    fun outerCancellation_atTaskLayer_doesNotEmitSpuriousTaskTimeoutEvent(): Unit = runBlocking {
        // Unique-per-test marker so the TaskEventBus subscriber can filter out
        // replay/bleed-through from other tests using the global singleton.
        val taskMarker = "SlowWorker_BUG11_${(NSDate().timeIntervalSince1970 * 1000).toLong()}"

        // Collector started BEFORE the action so it observes every emission.
        // UnconfinedTestDispatcher equivalent for runBlocking: Dispatchers.Unconfined
        // starts the collector eagerly.
        val collected = mutableListOf<TaskCompletionEvent>()
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch {
            TaskEventBus.events
                .filter { it.taskName == taskMarker }
                .collect { collected.add(it) }
        }

        val storage = makeStorage()
        val executor = ChainExecutor(
            workerFactory = object : IosWorkerFactory {
                override fun createWorker(workerClassName: String): IosWorker = object : IosWorker {
                    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
                        // Far longer than the caller-side withTimeout below, well under
                        // taskTimeout (20 s for APP_REFRESH). When the caller's timer
                        // fires, this delay observes the cancellation.
                        delay(30_000)
                        return WorkerResult.Success()
                    }
                }
            },
            taskType = BGTaskType.APP_REFRESH,  // taskTimeout = 20_000 ms; chainTimeout = 25_000 ms
            fileStorage = storage
        )
        try {
            executor.resetShutdownState()

            val chainId = "outer-task-cancel-chain"
            storage.saveChainDefinition(chainId, listOf(listOf(TaskRequest(taskMarker))))
            storage.enqueueChain(chainId)

            // Caller-side withTimeout fires at 1.5 s while the worker is inside delay(30 s).
            // Pre-fix, executeTask's catch (TCE) emits TaskCompletionEvent with
            // message "⏱️ Timeout after Xms" + TaskFailedEvent with the same
            // misattribution. Post-fix, the catch detects duration < taskTimeout and
            // rethrows BEFORE emitting any telemetry.
            try {
                withTimeout(1500L) {
                    withContext(Dispatchers.Default) {
                        executor.executeChainsInBatch(maxChains = 1, totalTimeoutMs = 60_000L)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Expected — caller's timer fired. We don't assert on this; we care
                // about telemetry side effects emitted BEFORE the cancel completed.
            }

            // Brief settle so any `coroutineScope.launch { TaskEventBus.emit(...) }`
            // dispatch from executeTask's catch has a chance to reach the bus before
            // we read `collected`. TaskEventBus.emit uses tryEmit (non-suspending) but
            // the call site dispatches via launch in some paths.
            delay(200)

            val timeoutEvents = collected.filter {
                it.message?.contains("Timeout after") == true
            }
            assertTrue(
                timeoutEvents.isEmpty(),
                "REGRESSION: ${timeoutEvents.size} spurious task-timeout event(s) emitted to " +
                    "TaskEventBus for a task that ran for ~1.5 s (taskTimeout is 20 s; " +
                    "cancellation came from an outer scope). The task-layer catch in " +
                    "ChainExecutor.executeTask must detect `duration < taskTimeout` and " +
                    "rethrow so the outer scope observes its own cancellation — instead of " +
                    "emitting misleading telemetry. Events seen: " +
                    timeoutEvents.joinToString { "msg='${it.message}'" }
            )
        } finally {
            collectorJob.cancel()
            executor.close()
            storage.close()
        }
    }
}
