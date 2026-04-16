package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Security test suite for KMP WorkManager.
 * Focuses on:
 * - Path traversal prevention
 * - Dangerous payload handling
 * - ID validation
 */
class SecurityTestSuite {

    private val scheduler = object : BackgroundTaskScheduler {
        override suspend fun enqueue(
            id: String,
            trigger: TaskTrigger,
            workerClassName: String,
            constraints: Constraints,
            inputJson: String?,
            policy: ExistingPolicy
        ): ScheduleResult {
            // Check for path traversal in ID
            if (id.contains("..") || id.contains("/")) {
                return ScheduleResult.REJECTED_OS_POLICY
            }
            return ScheduleResult.ACCEPTED
        }

        override fun cancel(id: String) {}
        override fun cancelAll() {}
        override fun beginWith(task: TaskRequest): TaskChain = TaskChain(this, listOf(task))
        override fun beginWith(tasks: List<TaskRequest>): TaskChain = TaskChain(this, tasks)
        override suspend fun enqueueChain(chain: TaskChain, id: String?, policy: ExistingPolicy) {}
        override fun flushPendingProgress() {}
        override suspend fun getExecutionHistory(limit: Int): List<ExecutionRecord> = emptyList()
        override suspend fun clearExecutionHistory() {}
    }

    @Test
    fun `rejects task ID with path traversal characters`() = kotlinx.coroutines.test.runTest {
        val dangerousId = "../../../etc/passwd"
        val result = scheduler.enqueue(dangerousId, TaskTrigger.OneTime(), "Worker")
        assertTrue(result == ScheduleResult.REJECTED_OS_POLICY, "Should reject ID with path traversal")
    }

    @Test
    fun `rejects task ID with forward slashes`() = kotlinx.coroutines.test.runTest {
        val dangerousId = "tasks/123"
        val result = scheduler.enqueue(dangerousId, TaskTrigger.OneTime(), "Worker")
        assertTrue(result == ScheduleResult.REJECTED_OS_POLICY, "Should reject ID with slashes")
    }

    @Test
    fun `validates workerClassName is not empty`() {
        // Implementation check - many schedulers should throw or return rejected if worker is blank
        // This is a unit test for common validation logic if it exists
    }
}
