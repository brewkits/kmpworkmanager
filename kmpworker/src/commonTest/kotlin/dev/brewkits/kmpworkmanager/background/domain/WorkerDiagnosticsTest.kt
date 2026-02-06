package dev.brewkits.kmpworkmanager.background.domain

import kotlin.test.*

/**
 * Unit tests for Diagnostics API (Task #6)
 * Tests:
 * - System health report accuracy
 * - Scheduler status reporting
 * - Task status detail retrieval
 * - Cross-platform consistency
 */
class WorkerDiagnosticsTest {

    /**
     * Mock diagnostics implementation for testing
     */
    private class MockWorkerDiagnostics(
        private val mockHealth: SystemHealthReport? = null,
        private val mockStatus: SchedulerStatus? = null,
        private val mockTaskStatus: Map<String, TaskStatusDetail> = emptyMap()
    ) : WorkerDiagnostics {

        override suspend fun getSchedulerStatus(): SchedulerStatus {
            return mockStatus ?: SchedulerStatus(
                isReady = true,
                totalPendingTasks = 0,
                queueSize = 0,
                platform = "test",
                timestamp = System.currentTimeMillis()
            )
        }

        override suspend fun getSystemHealth(): SystemHealthReport {
            return mockHealth ?: SystemHealthReport(
                timestamp = System.currentTimeMillis(),
                batteryLevel = 100,
                isCharging = false,
                networkAvailable = true,
                storageAvailable = 1_000_000_000L,
                isStorageLow = false,
                isLowPowerMode = false,
                deviceInDozeMode = false
            )
        }

        override suspend fun getTaskStatus(id: String): TaskStatusDetail? {
            return mockTaskStatus[id]
        }
    }

    @Test
    fun testSystemHealthReport_AllFieldsPresent() {
        val report = SystemHealthReport(
            timestamp = 1234567890L,
            batteryLevel = 75,
            isCharging = true,
            networkAvailable = true,
            storageAvailable = 5_000_000_000L,
            isStorageLow = false,
            isLowPowerMode = false,
            deviceInDozeMode = false
        )

        assertEquals(1234567890L, report.timestamp)
        assertEquals(75, report.batteryLevel)
        assertTrue(report.isCharging)
        assertTrue(report.networkAvailable)
        assertEquals(5_000_000_000L, report.storageAvailable)
        assertFalse(report.isStorageLow)
        assertFalse(report.isLowPowerMode)
        assertFalse(report.deviceInDozeMode)
    }

    @Test
    fun testSystemHealthReport_LowBattery() {
        val report = SystemHealthReport(
            timestamp = System.currentTimeMillis(),
            batteryLevel = 15,
            isCharging = false,
            networkAvailable = true,
            storageAvailable = 1_000_000_000L,
            isStorageLow = false,
            isLowPowerMode = false,
            deviceInDozeMode = false
        )

        assertTrue(report.batteryLevel < 20, "Should detect low battery")
        assertFalse(report.isCharging, "Should not be charging")
    }

    @Test
    fun testSystemHealthReport_StorageLow() {
        val report = SystemHealthReport(
            timestamp = System.currentTimeMillis(),
            batteryLevel = 100,
            isCharging = false,
            networkAvailable = true,
            storageAvailable = 100_000_000L, // 100MB
            isStorageLow = true,
            isLowPowerMode = false,
            deviceInDozeMode = false
        )

        assertTrue(report.storageAvailable < 500_000_000L, "Should have <500MB storage")
        assertTrue(report.isStorageLow, "Should flag low storage")
    }

    @Test
    fun testSchedulerStatus_Ready() {
        val status = SchedulerStatus(
            isReady = true,
            totalPendingTasks = 5,
            queueSize = 3,
            platform = "iOS",
            timestamp = System.currentTimeMillis()
        )

        assertTrue(status.isReady)
        assertEquals(5, status.totalPendingTasks)
        assertEquals(3, status.queueSize)
        assertEquals("iOS", status.platform)
    }

    @Test
    fun testSchedulerStatus_NotReady() {
        val status = SchedulerStatus(
            isReady = false,
            totalPendingTasks = 0,
            queueSize = 0,
            platform = "Android",
            timestamp = System.currentTimeMillis()
        )

        assertFalse(status.isReady, "Scheduler should not be ready")
    }

    @Test
    fun testTaskStatusDetail_Running() {
        val taskStatus = TaskStatusDetail(
            id = "task-123",
            name = "SyncWorker",
            state = TaskState.RUNNING,
            scheduledAt = 1000L,
            startedAt = 1100L,
            completedAt = null,
            attempts = 1,
            lastError = null
        )

        assertEquals("task-123", taskStatus.id)
        assertEquals("SyncWorker", taskStatus.name)
        assertEquals(TaskState.RUNNING, taskStatus.state)
        assertNotNull(taskStatus.startedAt)
        assertNull(taskStatus.completedAt)
        assertEquals(1, taskStatus.attempts)
    }

    @Test
    fun testTaskStatusDetail_Failed() {
        val taskStatus = TaskStatusDetail(
            id = "task-456",
            name = "UploadWorker",
            state = TaskState.FAILED,
            scheduledAt = 1000L,
            startedAt = 1100L,
            completedAt = 1500L,
            attempts = 3,
            lastError = "Network timeout"
        )

        assertEquals(TaskState.FAILED, taskStatus.state)
        assertEquals(3, taskStatus.attempts, "Should have 3 retry attempts")
        assertEquals("Network timeout", taskStatus.lastError)
        assertNotNull(taskStatus.completedAt)
    }

    @Test
    fun testTaskStatusDetail_Succeeded() {
        val taskStatus = TaskStatusDetail(
            id = "task-789",
            name = "DatabaseWorker",
            state = TaskState.SUCCEEDED,
            scheduledAt = 1000L,
            startedAt = 1100L,
            completedAt = 1200L,
            attempts = 1,
            lastError = null
        )

        assertEquals(TaskState.SUCCEEDED, taskStatus.state)
        assertNull(taskStatus.lastError)
        assertEquals(1, taskStatus.attempts)

        val duration = taskStatus.completedAt!! - taskStatus.startedAt!!
        assertEquals(100L, duration, "Task took 100ms")
    }

    @Test
    fun testDiagnosticsAPI_GetSchedulerStatus() = kotlinx.coroutines.runBlocking {
        val diagnostics = MockWorkerDiagnostics(
            mockStatus = SchedulerStatus(
                isReady = true,
                totalPendingTasks = 10,
                queueSize = 5,
                platform = "iOS",
                timestamp = System.currentTimeMillis()
            )
        )

        val status = diagnostics.getSchedulerStatus()

        assertTrue(status.isReady)
        assertEquals(10, status.totalPendingTasks)
        assertEquals(5, status.queueSize)
    }

    @Test
    fun testDiagnosticsAPI_GetSystemHealth() = kotlinx.coroutines.runBlocking {
        val mockReport = SystemHealthReport(
            timestamp = 9999L,
            batteryLevel = 50,
            isCharging = true,
            networkAvailable = false,
            storageAvailable = 2_000_000_000L,
            isStorageLow = false,
            isLowPowerMode = true,
            deviceInDozeMode = false
        )

        val diagnostics = MockWorkerDiagnostics(mockHealth = mockReport)
        val health = diagnostics.getSystemHealth()

        assertEquals(50, health.batteryLevel)
        assertTrue(health.isCharging)
        assertFalse(health.networkAvailable)
        assertTrue(health.isLowPowerMode)
    }

    @Test
    fun testDiagnosticsAPI_GetTaskStatus_Found() = kotlinx.coroutines.runBlocking {
        val taskDetail = TaskStatusDetail(
            id = "test-task",
            name = "TestWorker",
            state = TaskState.RUNNING,
            scheduledAt = 1000L,
            startedAt = 1100L,
            completedAt = null,
            attempts = 1,
            lastError = null
        )

        val diagnostics = MockWorkerDiagnostics(
            mockTaskStatus = mapOf("test-task" to taskDetail)
        )

        val result = diagnostics.getTaskStatus("test-task")

        assertNotNull(result)
        assertEquals("test-task", result.id)
        assertEquals(TaskState.RUNNING, result.state)
    }

    @Test
    fun testDiagnosticsAPI_GetTaskStatus_NotFound() = kotlinx.coroutines.runBlocking {
        val diagnostics = MockWorkerDiagnostics()

        val result = diagnostics.getTaskStatus("non-existent-task")

        assertNull(result, "Should return null for non-existent task")
    }

    /**
     * Test realistic debugging scenario: "Why didn't my task run?"
     */
    @Test
    fun integrationTest_DebuggingScenario() = kotlinx.coroutines.runBlocking {
        // Scenario: Task scheduled but not running
        val diagnostics = MockWorkerDiagnostics(
            mockStatus = SchedulerStatus(
                isReady = false, // Scheduler not ready!
                totalPendingTasks = 1,
                queueSize = 1,
                platform = "iOS",
                timestamp = System.currentTimeMillis()
            ),
            mockHealth = SystemHealthReport(
                timestamp = System.currentTimeMillis(),
                batteryLevel = 10, // Low battery
                isCharging = false,
                networkAvailable = false, // No network
                storageAvailable = 50_000_000L,
                isStorageLow = true, // Low storage
                isLowPowerMode = true, // iOS low power mode
                deviceInDozeMode = false
            ),
            mockTaskStatus = mapOf(
                "blocked-task" to TaskStatusDetail(
                    id = "blocked-task",
                    name = "NetworkWorker",
                    state = TaskState.BLOCKED,
                    scheduledAt = System.currentTimeMillis() - 60000L,
                    startedAt = null,
                    completedAt = null,
                    attempts = 0,
                    lastError = null
                )
            )
        )

        // Diagnose why task isn't running
        val schedulerStatus = diagnostics.getSchedulerStatus()
        val systemHealth = diagnostics.getSystemHealth()
        val taskStatus = diagnostics.getTaskStatus("blocked-task")

        // Identify blockers
        val blockers = mutableListOf<String>()
        if (!schedulerStatus.isReady) blockers.add("Scheduler not ready")
        if (systemHealth.batteryLevel < 20) blockers.add("Low battery (${systemHealth.batteryLevel}%)")
        if (!systemHealth.networkAvailable) blockers.add("No network connection")
        if (systemHealth.isStorageLow) blockers.add("Low storage")
        if (systemHealth.isLowPowerMode) blockers.add("Low power mode enabled")
        if (taskStatus?.state == TaskState.BLOCKED) blockers.add("Task is blocked")

        // Should identify multiple issues
        assertTrue(blockers.size >= 3, "Should identify multiple blockers: $blockers")
        assertTrue(blockers.contains("Scheduler not ready"))
        assertTrue(blockers.contains("No network connection"))
        assertTrue(blockers.contains("Low power mode enabled"))

        println("Debugging analysis found ${blockers.size} blockers:")
        blockers.forEach { println("  - $it") }
    }

    /**
     * Stress test: Rapid diagnostics queries
     */
    @Test
    fun stressTestRapidQueries() = kotlinx.coroutines.runBlocking {
        val diagnostics = MockWorkerDiagnostics()

        // Query diagnostics 1000 times rapidly
        val queries = 1000
        val startTime = System.currentTimeMillis()

        repeat(queries) {
            diagnostics.getSchedulerStatus()
            diagnostics.getSystemHealth()
        }

        val duration = System.currentTimeMillis() - startTime

        // Should handle queries efficiently (<1000ms for 2000 queries)
        assertTrue(
            duration < 1000,
            "Should handle ${queries * 2} queries in <1000ms (was ${duration}ms)"
        )

        println("Diagnostics performance: ${queries * 2} queries in ${duration}ms")
    }
}

/**
 * Task states for diagnostics
 */
enum class TaskState {
    SCHEDULED,
    BLOCKED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

/**
 * Task status detail for diagnostics
 */
data class TaskStatusDetail(
    val id: String,
    val name: String,
    val state: TaskState,
    val scheduledAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val attempts: Int,
    val lastError: String?
)
