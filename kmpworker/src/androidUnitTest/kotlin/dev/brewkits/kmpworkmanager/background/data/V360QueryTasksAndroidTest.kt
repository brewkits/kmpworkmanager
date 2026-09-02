package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.TaskState
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Covers `NativeTaskScheduler.queryTasks` — the `WorkQuery`-style batch read added in 3.6.0.
 *
 * Naming convention: `VXYZ...Test` per CLAUDE.md — 3.6.0's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V360QueryTasksAndroidTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun `no filters returns every enqueued standalone task`() = runTest {
        scheduler.enqueue(
            id = "query-task-1",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        scheduler.enqueue(
            id = "query-task-2",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val results = scheduler.queryTasks()
        val ids = results.map { it.id }.toSet()

        assertTrue("query-task-1" in ids, "results: $ids")
        assertTrue("query-task-2" in ids, "results: $ids")
    }

    @Test
    fun `tags filter matches only tasks carrying that user tag`() = runTest {
        scheduler.enqueue(
            id = "tagged-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
            tags = setOf("session-42")
        )
        scheduler.enqueue(
            id = "untagged-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val results = scheduler.queryTasks(tags = setOf("session-42"))
        val ids = results.map { it.id }.toSet()

        assertTrue("tagged-task" in ids, "results: $ids")
        assertFalse("untagged-task" in ids, "results: $ids")
    }

    @Test
    fun `workerClassNames filter matches only tasks of that worker type`() = runTest {
        scheduler.enqueue(
            id = "worker-a-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        scheduler.enqueue(
            id = "worker-b-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.OtherWorker"
        )

        val results = scheduler.queryTasks(
            workerClassNames = setOf("dev.brewkits.kmpworkmanager.sample.background.SimpleWorker")
        )
        val ids = results.map { it.id }.toSet()

        assertTrue("worker-a-task" in ids, "results: $ids")
        assertFalse("worker-b-task" in ids, "results: $ids")
    }

    @Test
    fun `tags and workerClassNames filters are ANDed together`() = runTest {
        // Matches tag but not worker.
        scheduler.enqueue(
            id = "tag-match-only",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.OtherWorker",
            tags = setOf("target-tag")
        )
        // Matches worker but not tag.
        scheduler.enqueue(
            id = "worker-match-only",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        // Matches both.
        scheduler.enqueue(
            id = "both-match",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
            tags = setOf("target-tag")
        )

        val results = scheduler.queryTasks(
            tags = setOf("target-tag"),
            workerClassNames = setOf("dev.brewkits.kmpworkmanager.sample.background.SimpleWorker")
        )
        val ids = results.map { it.id }.toSet()

        assertEquals(setOf("both-match"), ids)
    }

    @Test
    fun `states filter matches only tasks in that TaskState Kind`() = runTest {
        // Robolectric's WorkManagerTestInitHelper wires a GreedyScheduler + SynchronousExecutor
        // that runs an unconstrained task to completion synchronously during enqueue() itself —
        // it never observably sits at ENQUEUED. requiresCharging=true (device not charging by
        // default in Robolectric) is the standard trick to keep a WorkInfo genuinely pending,
        // without needing WorkManagerTestInitHelper's TestDriver.
        scheduler.enqueue(
            id = "enqueued-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
            constraints = Constraints(requiresCharging = true)
        )

        val enqueuedOnly = scheduler.queryTasks(states = setOf(TaskState.Kind.ENQUEUED))
        assertTrue("enqueued-task" in enqueuedOnly.map { it.id }, "results: ${enqueuedOnly.map { it.id to it.state }}")

        val succeededOnly = scheduler.queryTasks(states = setOf(TaskState.Kind.SUCCEEDED))
        assertFalse("enqueued-task" in succeededOnly.map { it.id })
    }

    @Test
    fun `an unrelated task is excluded by a tag filter it does not carry`() = runTest {
        scheduler.enqueue(
            id = "irrelevant-task",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )

        val results = scheduler.queryTasks(tags = setOf("nonexistent-tag"))
        assertFalse("irrelevant-task" in results.map { it.id })
    }

    @Test
    fun `a chain's steps are grouped under the chain id via the chain- tag`() = runTest {
        val chain = TaskChain(
            scheduler,
            listOf(TaskRequest(workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"))
        ).then(TaskRequest(workerClassName = "dev.brewkits.kmpworkmanager.sample.background.OtherWorker"))

        scheduler.enqueueChain(chain, id = "query-chain-1")

        val results = scheduler.queryTasks()
        val chainResult = results.firstOrNull { it.id == "query-chain-1" }

        assertNotNull(chainResult, "chain must be queryable by its own id, not per-step random ids: ${results.map { it.id }}")
    }

    @Test
    fun `a chain matches a tag carried by any one of its steps`() = runTest {
        val chain = TaskChain(
            scheduler,
            listOf(
                TaskRequest(
                    workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
                    tags = setOf("chain-tag")
                )
            )
        ).then(TaskRequest(workerClassName = "dev.brewkits.kmpworkmanager.sample.background.OtherWorker"))

        scheduler.enqueueChain(chain, id = "query-chain-2")

        val results = scheduler.queryTasks(tags = setOf("chain-tag"))
        assertTrue("query-chain-2" in results.map { it.id }, "results: ${results.map { it.id }}")
    }
}
