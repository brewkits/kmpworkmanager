@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Unit coverage for [computeIosTaskState] — the pure snapshot function backing
 * `NativeTaskScheduler.observeTaskState` on iOS. Every OS-level dependency (chain-active
 * registry, BGTaskScheduler pending check, execution history) is injected as a lambda so
 * this exercises the precedence logic in isolation, without a real `BGTaskScheduler` or
 * `ChainJobRegistry`.
 *
 * Naming convention: `VXYZ...Test` per CLAUDE.md — 3.4.0's.
 */
class V340ComputeIosTaskStateTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_taskstate_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage = IosFileStorage(
        config = IosFileStorageConfig(isTestMode = true),
        baseDirectory = makeTempDir(tag)
    )

    private val neverActive: suspend (String) -> Boolean = { false }
    private val neverPending: suspend (String) -> Boolean = { false }
    private val noHistory: suspend () -> List<ExecutionRecord> = { emptyList() }

    private suspend fun snapshot(
        id: String,
        storage: IosFileStorage,
        permittedTaskIds: Set<String> = emptySet(),
        isChainActive: suspend (String) -> Boolean = neverActive,
        isTaskPending: suspend (String) -> Boolean = neverPending,
        executionHistory: suspend () -> List<ExecutionRecord> = noHistory
    ): TaskState = computeIosTaskState(
        id = id,
        fileStorage = storage,
        permittedTaskIds = permittedTaskIds,
        isChainActive = isChainActive,
        isTaskPending = isTaskPending,
        executionHistory = executionHistory
    )

    private fun historyRecord(chainId: String, status: ExecutionStatus, errorMessage: String? = null) = ExecutionRecord(
        id = "record-${kotlin.random.Random.nextInt()}",
        chainId = chainId,
        status = status,
        startedAtMs = 0L,
        endedAtMs = 100L,
        durationMs = 100L,
        totalSteps = 1,
        completedSteps = if (status == ExecutionStatus.SUCCESS) 1 else 0,
        errorMessage = errorMessage,
        platform = "ios",
        workerClassNames = listOf("TestWorker")
    )

    // ==================== Unknown ====================

    @Test
    fun `an id with no metadata and no chain and no history is Unknown`() = runTest {
        val storage = makeStorage("unknown")
        assertEquals(TaskState.Unknown, snapshot("never-seen", storage))
        storage.close()
    }

    // ==================== Chain precedence ====================

    @Test
    fun `a chain currently in the queue is Enqueued`() = runTest {
        val storage = makeStorage("chain-queued")
        // replaceChainAtomic both saves the definition AND enqueues — the real path a
        // freshly-scheduled chain goes through.
        storage.replaceChainAtomic("chain-1", listOf(listOf(TaskRequest(workerClassName = "W"))))

        assertEquals(TaskState.Enqueued, snapshot("chain-1", storage))
        storage.close()
    }

    @Test
    fun `a chain registered as active in ChainJobRegistry is Running even if still queued`() = runTest {
        val storage = makeStorage("chain-running")
        storage.replaceChainAtomic("chain-2", listOf(listOf(TaskRequest(workerClassName = "W"))))

        val state = snapshot("chain-2", storage, isChainActive = { it == "chain-2" })
        assertEquals(TaskState.Running, state)
        storage.close()
    }

    @Test
    fun `a chain definition present but not queued and not active is still Enqueued not Unknown`() = runTest {
        val storage = makeStorage("chain-def-only")
        storage.saveChainDefinition("chain-3", listOf(listOf(TaskRequest(workerClassName = "W"))))
        // Deliberately not enqueued into the chain queue — narrow-window case.

        assertEquals(TaskState.Enqueued, snapshot("chain-3", storage))
        storage.close()
    }

    @Test
    fun `chain namespace takes precedence over standalone task namespace for a colliding id`() = runTest {
        val storage = makeStorage("collision")
        val id = "shared-id"
        storage.replaceChainAtomic(id, listOf(listOf(TaskRequest(workerClassName = "W"))))
        storage.saveTaskMetadata(id, mapOf("workerClassName" to "OtherWorker"), periodic = false)

        // Chain check must win — this test documents the precedence rule, not just asserts it.
        assertEquals(TaskState.Enqueued, snapshot(id, storage))
        storage.close()
    }

    // ==================== Standalone task: dedicated Info.plist identifier ====================

    @Test
    fun `a dedicated-identifier task with a pending BGTaskRequest is Enqueued`() = runTest {
        val storage = makeStorage("dedicated-pending")
        storage.saveTaskMetadata("dedicated-1", mapOf("workerClassName" to "W"), periodic = false)

        val state = snapshot(
            "dedicated-1", storage,
            permittedTaskIds = setOf("dedicated-1"),
            isTaskPending = { it == "dedicated-1" }
        )
        assertEquals(TaskState.Enqueued, state)
        storage.close()
    }

    @Test
    fun `a dedicated-identifier task with metadata but no pending request is Running`() = runTest {
        val storage = makeStorage("dedicated-running")
        storage.saveTaskMetadata("dedicated-2", mapOf("workerClassName" to "W"), periodic = false)

        val state = snapshot(
            "dedicated-2", storage,
            permittedTaskIds = setOf("dedicated-2"),
            isTaskPending = neverPending
        )
        assertEquals(TaskState.Running, state)
        storage.close()
    }

    // ==================== Standalone task: dynamic queue ====================

    @Test
    fun `a dynamic-queue task still in the queue is Enqueued`() = runTest {
        val storage = makeStorage("dynamic-queued")
        storage.saveTaskMetadata("dynamic-1", mapOf("workerClassName" to "W"), periodic = false)
        storage.enqueueTask("dynamic-1")

        // Not in permittedTaskIds -> dynamic queue path.
        assertEquals(TaskState.Enqueued, snapshot("dynamic-1", storage))
        storage.close()
    }

    @Test
    fun `a dynamic-queue task dequeued but with metadata still present is Running`() = runTest {
        val storage = makeStorage("dynamic-running")
        storage.saveTaskMetadata("dynamic-2", mapOf("workerClassName" to "W"), periodic = false)
        storage.enqueueTask("dynamic-2")
        assertEquals("dynamic-2", storage.dequeueTask())

        assertEquals(TaskState.Running, snapshot("dynamic-2", storage))
        storage.close()
    }

    @Test
    fun `a periodic task's metadata is also found via the dynamic-queue path`() = runTest {
        val storage = makeStorage("periodic")
        storage.saveTaskMetadata(
            "periodic-1",
            mapOf("workerClassName" to "W", "isPeriodic" to "true", "intervalMs" to "60000", "anchoredStartMs" to "0"),
            periodic = true
        )

        // Periodic metadata exists but isn't in the dynamic tasksQueue between runs — Running
        // is the documented best-effort inference for "metadata exists, not in queue".
        assertEquals(TaskState.Running, snapshot("periodic-1", storage))
        storage.close()
    }

    // ==================== Execution history fallback ====================

    @Test
    fun `no live state but a SUCCESS history record reports Succeeded`() = runTest {
        val storage = makeStorage("history-success")
        assertEquals(
            TaskState.Succeeded(),
            snapshot("done-task", storage, executionHistory = { listOf(historyRecord("done-task", ExecutionStatus.SUCCESS)) })
        )
        storage.close()
    }

    @Test
    fun `no live state but a FAILURE history record reports Failed with the error message`() = runTest {
        val storage = makeStorage("history-failure")
        val state = snapshot(
            "failed-task", storage,
            executionHistory = { listOf(historyRecord("failed-task", ExecutionStatus.FAILURE, "boom")) }
        )
        assertEquals(TaskState.Failed("boom"), state)
        storage.close()
    }

    @Test
    fun `no live state but an ABANDONED history record reports Failed`() = runTest {
        val storage = makeStorage("history-abandoned")
        val state = snapshot(
            "abandoned-task", storage,
            executionHistory = { listOf(historyRecord("abandoned-task", ExecutionStatus.ABANDONED)) }
        )
        assertTrue(state is TaskState.Failed)
        storage.close()
    }

    @Test
    fun `no live state but a SKIPPED history record reports Cancelled`() = runTest {
        val storage = makeStorage("history-skipped")
        val state = snapshot(
            "skipped-task", storage,
            executionHistory = { listOf(historyRecord("skipped-task", ExecutionStatus.SKIPPED)) }
        )
        assertEquals(TaskState.Cancelled, state)
        storage.close()
    }

    @Test
    fun `live metadata takes precedence over a stale history record for the same id`() = runTest {
        // Simulates: task ran once and failed (history has a FAILURE record), was later
        // re-enqueued fresh under the same id, and is now pending again. The live metadata
        // must win — a caller must never see a fresh retry reported as the old failure.
        val storage = makeStorage("history-vs-live")
        storage.saveTaskMetadata("retried-task", mapOf("workerClassName" to "W"), periodic = false)
        storage.enqueueTask("retried-task")

        val state = snapshot(
            "retried-task", storage,
            executionHistory = { listOf(historyRecord("retried-task", ExecutionStatus.FAILURE, "old failure")) }
        )
        assertEquals(TaskState.Enqueued, state)
        storage.close()
    }

    @Test
    fun `history is only consulted for the matching id not just the newest record overall`() = runTest {
        val storage = makeStorage("history-filter")
        val state = snapshot(
            "target-id", storage,
            executionHistory = {
                listOf(
                    historyRecord("some-other-id", ExecutionStatus.FAILURE),
                    historyRecord("target-id", ExecutionStatus.SUCCESS)
                )
            }
        )
        assertEquals(TaskState.Succeeded(), state)
        storage.close()
    }
}
