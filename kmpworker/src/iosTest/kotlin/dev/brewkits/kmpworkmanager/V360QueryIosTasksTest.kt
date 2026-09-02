@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Unit coverage for [queryIosTasks] — the `WorkQuery`-style batch read backing
 * `NativeTaskScheduler.queryTasks` on iOS. Mirrors `V360ComputeIosTaskStateTest`'s approach:
 * every OS-level dependency is injected, so this exercises the enumeration + filter logic in
 * isolation.
 *
 * Naming convention: `VXYZ...Test` per CLAUDE.md — 3.6.0's.
 */
class V360QueryIosTasksTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_query_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
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

    private suspend fun query(
        storage: IosFileStorage,
        permittedTaskIds: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        workerClassNames: Set<String> = emptySet(),
        states: Set<TaskState.Kind> = emptySet(),
        isChainActive: suspend (String) -> Boolean = neverActive,
        isTaskPending: suspend (String) -> Boolean = neverPending,
        executionHistory: suspend () -> List<ExecutionRecord> = noHistory
    ): List<QueriedTask> = queryIosTasks(
        fileStorage = storage,
        permittedTaskIds = permittedTaskIds,
        tags = tags,
        workerClassNames = workerClassNames,
        states = states,
        isChainActive = isChainActive,
        isTaskPending = isTaskPending,
        executionHistory = executionHistory
    )

    private fun historyRecord(chainId: String, status: ExecutionStatus) = ExecutionRecord(
        id = "record-${kotlin.random.Random.nextInt()}",
        chainId = chainId,
        status = status,
        startedAtMs = 0L,
        endedAtMs = 100L,
        durationMs = 100L,
        totalSteps = 1,
        completedSteps = if (status == ExecutionStatus.SUCCESS) 1 else 0,
        platform = "ios",
        workerClassNames = listOf("TestWorker")
    )

    @Test
    fun `no filters returns every known standalone task and chain`() = runTest {
        val storage = makeStorage("no-filters")
        storage.saveTaskMetadata("task-1", mapOf("workerClassName" to "W"), periodic = false)
        storage.enqueueTask("task-1")
        storage.replaceChainAtomic("chain-1", listOf(listOf(TaskRequest(workerClassName = "W"))))

        val results = query(storage)
        val ids = results.map { it.id }.toSet()

        assertTrue("task-1" in ids, "results: $ids")
        assertTrue("chain-1" in ids, "results: $ids")
        storage.close()
    }

    @Test
    fun `an id with no known state is not present in results at all`() = runTest {
        // Distinguishes queryTasks from observeTaskState: an id nobody asked about, and that
        // isn't in any candidate source, must simply be ABSENT — not present with Unknown.
        val storage = makeStorage("absent")
        val results = query(storage)
        assertTrue(results.none { it.id == "never-mentioned-anywhere" })
        storage.close()
    }

    @Test
    fun `tags filter matches only a standalone task carrying that tag`() = runTest {
        val storage = makeStorage("tags-standalone")
        storage.saveTaskMetadata(
            "tagged-task",
            mapOf("workerClassName" to "W", DynamicTaskDispatcher.META_TAGS to "session-42"),
            periodic = false
        )
        storage.enqueueTask("tagged-task")
        storage.saveTaskMetadata("untagged-task", mapOf("workerClassName" to "W"), periodic = false)
        storage.enqueueTask("untagged-task")

        val results = query(storage, tags = setOf("session-42"))
        val ids = results.map { it.id }.toSet()

        assertEquals(setOf("tagged-task"), ids)
        storage.close()
    }

    @Test
    fun `tags filter matches a chain if any step carries that tag`() = runTest {
        val storage = makeStorage("tags-chain")
        storage.replaceChainAtomic(
            "chain-with-tag",
            listOf(listOf(TaskRequest(workerClassName = "W", tags = setOf("chain-tag"))))
        )
        storage.replaceChainAtomic(
            "chain-without-tag",
            listOf(listOf(TaskRequest(workerClassName = "W")))
        )

        val results = query(storage, tags = setOf("chain-tag"))
        val ids = results.map { it.id }.toSet()

        assertTrue("chain-with-tag" in ids, "results: $ids")
        assertFalse("chain-without-tag" in ids, "results: $ids")
        storage.close()
    }

    @Test
    fun `workerClassNames filter matches only tasks of that worker type`() = runTest {
        val storage = makeStorage("worker-filter")
        storage.saveTaskMetadata("worker-a-task", mapOf("workerClassName" to "WorkerA"), periodic = false)
        storage.enqueueTask("worker-a-task")
        storage.saveTaskMetadata("worker-b-task", mapOf("workerClassName" to "WorkerB"), periodic = false)
        storage.enqueueTask("worker-b-task")

        val results = query(storage, workerClassNames = setOf("WorkerA"))
        val ids = results.map { it.id }.toSet()

        assertEquals(setOf("worker-a-task"), ids)
        storage.close()
    }

    @Test
    fun `tags and workerClassNames are ANDed together`() = runTest {
        val storage = makeStorage("and-filters")
        storage.saveTaskMetadata(
            "tag-only", mapOf("workerClassName" to "OtherWorker", DynamicTaskDispatcher.META_TAGS to "target"),
            periodic = false
        )
        storage.enqueueTask("tag-only")
        storage.saveTaskMetadata("worker-only", mapOf("workerClassName" to "TargetWorker"), periodic = false)
        storage.enqueueTask("worker-only")
        storage.saveTaskMetadata(
            "both-match", mapOf("workerClassName" to "TargetWorker", DynamicTaskDispatcher.META_TAGS to "target"),
            periodic = false
        )
        storage.enqueueTask("both-match")

        val results = query(storage, tags = setOf("target"), workerClassNames = setOf("TargetWorker"))
        assertEquals(setOf("both-match"), results.map { it.id }.toSet())
        storage.close()
    }

    @Test
    fun `states filter matches only tasks in that TaskState Kind`() = runTest {
        val storage = makeStorage("states-filter")
        storage.saveTaskMetadata("dedicated-enqueued", mapOf("workerClassName" to "W"), periodic = false)

        val enqueuedResults = query(
            storage,
            permittedTaskIds = setOf("dedicated-enqueued"),
            isTaskPending = { it == "dedicated-enqueued" },
            states = setOf(TaskState.Kind.ENQUEUED)
        )
        assertTrue("dedicated-enqueued" in enqueuedResults.map { it.id })

        val runningResults = query(
            storage,
            permittedTaskIds = setOf("dedicated-enqueued"),
            isTaskPending = { it == "dedicated-enqueued" },
            states = setOf(TaskState.Kind.RUNNING)
        )
        assertFalse("dedicated-enqueued" in runningResults.map { it.id })
        storage.close()
    }

    @Test
    fun `a terminal id found only in execution history is included with no filters`() = runTest {
        val storage = makeStorage("history-only")
        val results = query(storage, executionHistory = { listOf(historyRecord("finished-task", ExecutionStatus.SUCCESS)) })

        val match = results.firstOrNull { it.id == "finished-task" }
        assertNotNull(match, "results: ${results.map { it.id }}")
        assertEquals(TaskState.Kind.SUCCEEDED, match.state.kind)
        storage.close()
    }

    @Test
    fun `a terminal history-only id is excluded by a tag filter since its metadata is gone`() = runTest {
        // Documents the known limitation from queryIosTasks's own KDoc: once a task/chain
        // completes, its tags/workerClassName are no longer persisted anywhere queryTasks
        // can read, so a non-empty tags/workerClassNames filter cannot match it.
        val storage = makeStorage("history-only-filtered")
        val results = query(
            storage,
            tags = setOf("some-tag"),
            executionHistory = { listOf(historyRecord("finished-task", ExecutionStatus.SUCCESS)) }
        )

        assertTrue(results.none { it.id == "finished-task" }, "results: ${results.map { it.id }}")
        storage.close()
    }

    @Test
    fun `a currently-executing chain dequeued but with a live definition is included`() = runTest {
        // The chain has been dequeued for execution (ChainExecutor.executeChain's
        // dequeueChain() call) but its definition is still on disk — listChainDefinitionIds
        // must catch this, unlike getActiveChainIds alone.
        val storage = makeStorage("executing-chain")
        storage.saveChainDefinition("executing-chain-1", listOf(listOf(TaskRequest(workerClassName = "W"))))
        // Deliberately not in the queue — simulates mid-execution.

        val results = query(storage, isChainActive = { it == "executing-chain-1" })
        val match = results.firstOrNull { it.id == "executing-chain-1" }

        assertNotNull(match, "results: ${results.map { it.id }}")
        assertEquals(TaskState.Kind.RUNNING, match.state.kind)
        storage.close()
    }

    @Test
    fun `a periodic task is included via listPeriodicTaskIds`() = runTest {
        val storage = makeStorage("periodic-included")
        storage.saveTaskMetadata(
            "periodic-task",
            mapOf("workerClassName" to "W", "isPeriodic" to "true", "intervalMs" to "60000", "anchoredStartMs" to "0"),
            periodic = true
        )

        val results = query(storage)
        assertTrue("periodic-task" in results.map { it.id }, "results: ${results.map { it.id }}")
        storage.close()
    }
}
