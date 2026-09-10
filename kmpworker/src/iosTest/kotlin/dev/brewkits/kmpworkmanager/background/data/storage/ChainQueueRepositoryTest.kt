@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.domain.TaskPriority
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [ChainQueueRepository], the Stage 3 extraction from `IosFileStorage`
 * (see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`) and the one the plan calls riskiest.
 *
 * The queues themselves are `AppendOnlyQueue`, which has its own tests. What is pinned here
 * is what this class adds on top: the size cap, the priority reordering, and — the reason
 * this class owns three other stores — `replaceChainAtomic`, whose effects span the deleted
 * marker, the definition, the progress buffer and the queue, and must all happen or none.
 *
 * Unlike the other store tests these do not parameterise over the coordination flag: nothing
 * here goes through `coordinated()` except the transaction audit log, and the flag is proven
 * inert by `ChainProgressStoreTest`.
 */
class ChainQueueRepositoryTest {

    private lateinit var testDirectory: NSURL
    private lateinit var scope: CoroutineScope
    private lateinit var definitions: ChainDefinitionStore
    private lateinit var progress: ChainProgressStore
    private lateinit var metadata: TaskMetadataStore

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        testDirectory = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}ChainQueueRepositoryTest-$stamp-${platform.posix.rand()}"
        )
        NSFileManager.defaultManager.createDirectoryAtURL(
            testDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    private fun dir(name: String): NSURL = testDirectory.safeAppend(name).also {
        NSFileManager.defaultManager.createDirectoryAtURL(
            it,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    private fun newRepository(maxQueueSize: Int = 1000): ChainQueueRepository {
        val io = StorageFileIo(
            isTestMode = true,
            coordinatorTestMode = true,
            coordinationTimeoutMs = 30_000L
        )
        val json = Json { ignoreUnknownKeys = true }
        val chainsDir = dir("chains")
        progress = ChainProgressStore(io, scope, json) { chainsDir }
        definitions = ChainDefinitionStore(
            io = io,
            maintenance = StorageMaintenance(
                io = io,
                baseDir = { testDirectory },
                maintenanceTimestampFile = { testDirectory.safeAppend("last_maintenance.txt") },
                diskSpaceBufferBytes = 0L,
                isTestMode = true
            ),
            persistenceJson = json,
            chainsDir = { chainsDir },
            deletedChainsDir = { dir("deleted") },
            maxChainSizeBytes = 10_485_760L,
            deletedMarkerMaxAgeMs = 7L * 24 * 60 * 60 * 1000,
            deleteProgress = { id -> progress.deleteChainProgress(id) }
        )
        metadata = TaskMetadataStore(
            io = io,
            persistenceJson = json,
            tasksDir = { dir("tasks") },
            periodicDir = { dir("periodic") }
        )
        return ChainQueueRepository(
            io = io,
            backgroundScope = scope,
            isTestMode = true,
            queueDir = { testDirectory.safeAppend("queue") },
            tasksQueueDir = { testDirectory.safeAppend("tasks_queue") },
            transactionLogFile = { testDirectory.safeAppend("transactions.jsonl") },
            maxQueueSize = maxQueueSize,
            definitions = definitions,
            progress = progress,
            metadata = metadata
        )
    }

    private fun steps(vararg workers: String): List<List<TaskRequest>> =
        workers.map { listOf(TaskRequest(workerClassName = it)) }

    @Test
    fun chainsComeBackOutInTheOrderTheyWentIn() = runTest {
        val repo = newRepository()
        repo.enqueueChain("first")
        repo.enqueueChain("second")

        assertEquals(2, repo.getQueueSize())
        assertEquals(listOf("first", "second"), repo.getActiveChainIds())
        assertEquals("first", repo.dequeueChain())
        assertEquals("second", repo.dequeueChain())
        assertNull(repo.dequeueChain(), "an empty queue yields null, not an error")
    }

    @Test
    fun theDynamicTaskQueueIsSeparateFromTheChainQueue() = runTest {
        val repo = newRepository()
        repo.enqueueChain("a-chain")
        repo.enqueueTask("a-task")

        assertEquals(1, repo.getQueueSize())
        assertEquals(1, repo.getTasksQueueSize())
        assertTrue(repo.isTaskInDynamicQueue("a-task"))
        assertFalse(repo.isTaskInDynamicQueue("a-chain"))

        assertEquals("a-task", repo.dequeueTask())
        assertFalse(
            repo.isTaskInDynamicQueue("a-task"),
            "a dequeued task is no longer pending — this is what observeTaskState reads"
        )
    }

    /**
     * The cap is what stops a runaway producer from filling the disk. It is enforced under a
     * dedicated mutex precisely because a check-then-act would otherwise let two concurrent
     * callers both pass the check at cap-1.
     */
    @Test
    fun theQueueCapIsEnforced() = runTest {
        val repo = newRepository(maxQueueSize = 3)
        repeat(3) { repo.enqueueChain("chain-$it") }

        var threw = false
        try {
            repo.enqueueChain("one-too-many")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "enqueue past the cap must fail loudly")
        assertEquals(3, repo.getQueueSize(), "the refused chain must not be queued")
    }

    /** The same cap applies to the dynamic-task queue. */
    @Test
    fun theTaskQueueCapIsEnforced() = runTest {
        val repo = newRepository(maxQueueSize = 2)
        repeat(2) { repo.enqueueTask("task-$it") }

        var threw = false
        try {
            repo.enqueueTask("one-too-many")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "enqueue past the cap must fail loudly")
        assertEquals(2, repo.getTasksQueueSize())
    }

    /**
     * Priority ordering is by the chain's definition, so a HIGH chain enqueued last still
     * runs first. A chain with no definition on disk must not be dropped by the sort.
     */
    @Test
    fun sortingReordersTheQueueByPriorityWithoutLosingChains() = runTest {
        val repo = newRepository()
        definitions.saveChainDefinition(
            "low",
            listOf(listOf(TaskRequest(workerClassName = "W", priority = TaskPriority.LOW)))
        )
        definitions.saveChainDefinition(
            "high",
            listOf(listOf(TaskRequest(workerClassName = "W", priority = TaskPriority.HIGH)))
        )
        repo.enqueueChain("low")
        repo.enqueueChain("no-definition")
        repo.enqueueChain("high")

        repo.sortQueueByPriority()

        val sorted = repo.getActiveChainIds()
        assertEquals(3, sorted.size, "the sort must not lose a chain: $sorted")
        assertEquals("high", sorted.first(), "highest priority first: $sorted")
        assertTrue("no-definition" in sorted, "an undefined chain must survive: $sorted")
    }

    /**
     * REPLACE is a transaction across four things. All of them have to happen, or a replaced
     * chain resumes against the wrong definition — the class of bug the atomic version exists
     * to prevent.
     */
    @Test
    fun replaceSwapsTheDefinitionMarksDeletedDropsProgressAndReQueues() = runTest {
        val repo = newRepository()
        definitions.saveChainDefinition("chain", steps("Old"))
        progress.saveChainProgress(
            ChainProgress(chainId = "chain", totalSteps = 1, completedSteps = listOf(0))
        )
        progress.flushNow()
        assertNotNull(progress.loadChainProgress("chain"), "test setup failed")

        repo.replaceChainAtomic("chain", steps("New"))

        assertEquals(
            listOf("New"),
            definitions.loadChainDefinition("chain")?.map { it.single().workerClassName },
            "the new definition must be on disk"
        )
        assertTrue(
            definitions.isChainDeleted("chain"),
            "the marker must be set so an in-flight execution of the old chain stops"
        )
        assertNull(
            progress.loadChainProgress("chain"),
            "stale progress must be dropped — otherwise the new definition resumes mid-way"
        )
        assertEquals(listOf("chain"), repo.getActiveChainIds(), "the chain must be re-queued")
    }

    /** The queue scan `cancelByTag` uses reaches into each queued chain's definition. */
    @Test
    fun queuedChainsCanBeFoundByWorkerOrTag() = runTest {
        val repo = newRepository()
        definitions.saveChainDefinition(
            "sync",
            listOf(listOf(TaskRequest(workerClassName = "SyncWorker", tags = setOf("nightly"))))
        )
        definitions.saveChainDefinition("other", steps("OtherWorker"))
        repo.enqueueChain("sync")
        repo.enqueueChain("other")

        assertEquals(listOf("sync"), repo.findChainIdsByWorkerOrTag(workerClassName = "SyncWorker"))
        assertEquals(listOf("sync"), repo.findChainIdsByWorkerOrTag(tag = "nightly"))
        assertTrue(
            repo.findChainIdsByWorkerOrTag().isEmpty(),
            "asking for nothing must return nothing, not everything"
        )
    }

    /**
     * The dispatcher decides whether to ask iOS for a network- or charging-gated BGTask from
     * this summary, so the counts must reflect the metadata of everything actually pending.
     */
    @Test
    fun theConstraintSummaryAggregatesPendingTaskMetadata() = runTest {
        val repo = newRepository()
        metadata.saveTaskMetadata(
            "heavy-net",
            mapOf("isHeavyTask" to "true", "requiresNetwork" to "true"),
            periodic = false
        )
        metadata.saveTaskMetadata("plain", mapOf("isHeavyTask" to "false"), periodic = false)
        repo.enqueueTask("heavy-net")
        repo.enqueueTask("plain")

        val summary = repo.getDynamicQueueConstraintSummary()

        assertEquals(2, summary.pendingCount)
        assertEquals(1, summary.heavyCount)
        assertEquals(1, summary.networkRequiredCount)
        assertEquals(0, summary.chargingRequiredCount)
        assertFalse(summary.allLight, "one heavy task means the queue is not all-light")
        assertNull(
            summary.earliestBackoffFloorMs,
            "a floor is only reported when every pending task has one"
        )
    }

    /** An empty queue reports an empty profile, not a misleading all-light one. */
    @Test
    fun theConstraintSummaryOfAnEmptyQueueIsNotAllLight() = runTest {
        val summary = newRepository().getDynamicQueueConstraintSummary()
        assertEquals(0, summary.pendingCount)
        assertFalse(summary.allLight)
        assertFalse(summary.allRequireNetwork)
        assertFalse(summary.allRequireCharging)
    }
}
