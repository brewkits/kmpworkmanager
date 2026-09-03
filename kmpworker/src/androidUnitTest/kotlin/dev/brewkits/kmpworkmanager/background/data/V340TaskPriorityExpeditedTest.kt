package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.TaskPriority
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Regression test for the `TaskPriority` no-op gap closed in 3.4.0: `TaskPriority.kt`'s KDoc
 * documents `CRITICAL`/`HIGH` -> `setExpedited()`, `NORMAL`/`LOW` -> standard work, but
 * `buildOneTimeWorkRequest` used to call `setExpedited()` unconditionally (subject only to
 * delay/heavy/charging/unmetered checks), never reading `task?.priority` at all.
 *
 * Naming convention: `VXYZBugFixesTest` per CLAUDE.md — this is 3.4.0's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V340TaskPriorityExpeditedTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    private fun task(priority: TaskPriority) = TaskRequest(
        workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
        priority = priority
    )

    @Test
    fun `CRITICAL priority task is expedited`() {
        assertTrue(scheduler.shouldExpedite(task(TaskPriority.CRITICAL), Constraints(), initialDelayMs = 0L))
    }

    @Test
    fun `HIGH priority task is expedited`() {
        assertTrue(scheduler.shouldExpedite(task(TaskPriority.HIGH), Constraints(), initialDelayMs = 0L))
    }

    @Test
    fun `NORMAL priority task is not expedited`() {
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.NORMAL), Constraints(), initialDelayMs = 0L))
    }

    @Test
    fun `LOW priority task is not expedited`() {
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.LOW), Constraints(), initialDelayMs = 0L))
    }

    @Test
    fun `standalone task with no TaskRequest is never expedited regardless of delay or constraints`() {
        // Standalone enqueue() has no priority parameter on either platform — TaskPriority
        // lives on TaskRequest, which is chain-step-only by contract. `task = null` here
        // mirrors what scheduleOneTimeWork passes when the caller used the plain enqueue() API.
        assertFalse(scheduler.shouldExpedite(task = null, constraints = Constraints(), initialDelayMs = 0L))
    }

    @Test
    fun `CRITICAL priority task with nonzero delay is not expedited`() {
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.CRITICAL), Constraints(), initialDelayMs = 5_000L))
    }

    @Test
    fun `CRITICAL priority heavy task is not expedited`() {
        val heavy = Constraints(isHeavyTask = true)
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.CRITICAL), heavy, initialDelayMs = 0L))
    }

    @Test
    fun `CRITICAL priority task requiring charging is not expedited`() {
        val charging = Constraints(requiresCharging = true)
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.CRITICAL), charging, initialDelayMs = 0L))
    }

    @Test
    fun `CRITICAL priority task requiring unmetered network is not expedited`() {
        val unmetered = Constraints(requiresUnmeteredNetwork = true)
        assertFalse(scheduler.shouldExpedite(task(TaskPriority.CRITICAL), unmetered, initialDelayMs = 0L))
    }
}
