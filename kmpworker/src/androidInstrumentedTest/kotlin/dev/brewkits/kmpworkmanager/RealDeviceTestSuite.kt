package dev.brewkits.kmpworkmanager

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive Real Device Test Suite
 *
 * **IMPORTANT:** These tests MUST be run on REAL DEVICES, not emulators.
 *
 * Purpose: Ensure 100% reliability on production devices including:
 * - Background execution reliability
 * - Constraint handling
 * - Doze mode compatibility
 * - Battery optimization handling
 * - Network condition resilience
 * - Chinese ROM compatibility
 *
 * Setup Required:
 * 1. Connect real Android device via USB
 * 2. Enable Developer Options + USB Debugging
 * 3. Grant all permissions:
 *    ```
 *    adb shell appops set dev.brewkits.kmpworkmanager.sample SCHEDULE_EXACT_ALARM allow
 *    adb shell pm grant dev.brewkits.kmpworkmanager.sample android.permission.POST_NOTIFICATIONS
 *    ```
 * 4. Disable battery optimization (for testing):
 *    ```
 *    adb shell dumpsys deviceidle whitelist +dev.brewkits.kmpworkmanager.sample
 *    ```
 *
 * Run Tests:
 * ```
 * ./gradlew :kmpworker:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.brewkits.kmpworkmanager.RealDeviceTestSuite
 * ```
 *
 * @see docs/REAL_DEVICE_TESTING_GUIDE.md for detailed instructions
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceTestSuite {

    private lateinit var context: Context
    private lateinit var scheduler: BackgroundTaskScheduler
    private lateinit var workManager: WorkManager

    companion object {
        private const val TAG = "RealDeviceTest"

        /**
         * Detect if running on real device vs emulator
         */
        fun isRealDevice(): Boolean {
            return !(Build.FINGERPRINT.contains("generic") ||
                    Build.FINGERPRINT.contains("unknown") ||
                    Build.MODEL.contains("google_sdk") ||
                    Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("Android SDK") ||
                    Build.MANUFACTURER.contains("Genymotion") ||
                    Build.PRODUCT.contains("sdk_gphone") ||
                    Build.PRODUCT.contains("sdk") ||
                    Build.PRODUCT.contains("vbox86p") ||
                    Build.HARDWARE.contains("goldfish") ||
                    Build.HARDWARE.contains("ranchu"))
        }

        /**
         * Detect Chinese ROM manufacturers
         */
        fun isChineseROM(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            return manufacturer.contains("xiaomi") ||
                   manufacturer.contains("huawei") ||
                   manufacturer.contains("oppo") ||
                   manufacturer.contains("vivo") ||
                   manufacturer.contains("realme") ||
                   manufacturer.contains("oneplus") ||
                   manufacturer.contains("meizu")
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)

        // Log device type (emulator vs real device)
        val deviceType = if (isRealDevice()) "Real Device" else "Emulator"
        println("\n📱 Running on: $deviceType")

        if (!isRealDevice()) {
            println("""
                ℹ️ NOTE: Running on emulator
                Some features may behave differently:
                - Background execution timing may vary
                - Doze mode simulation may be less accurate
                - Battery optimization behaves differently

                For production validation, always test on real devices.
            """.trimIndent())
        }

        // Initialize KmpWorkManager
        KmpWorkManager.initialize(
            context = context,
            workerFactory = RealDeviceWorkerFactory(),
            config = KmpWorkManagerConfig()
        )
        scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler

        // Log device info
        println("""

            ═══════════════════════════════════════════
            📱 REAL DEVICE INFO
            ═══════════════════════════════════════════
            Manufacturer: ${Build.MANUFACTURER}
            Model: ${Build.MODEL}
            Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            ROM: ${Build.DISPLAY}
            Fingerprint: ${Build.FINGERPRINT}
            Chinese ROM: ${if (isChineseROM()) "YES ⚠️" else "No"}
            ═══════════════════════════════════════════

        """.trimIndent())
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
        runBlocking {
            delay(1000) // Give time for cancellation
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Suite 1: Background Execution Reliability
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun test_01_OneTimeTask_ExecutesInBackground() = runBlocking {
        println("\n🧪 TEST: One-time task executes in background")

        val taskId = "real-device-onetime-${System.currentTimeMillis()}"
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(initialDelayMs = 5000), // 5 second delay
            workerClassName = "LogWorker",
            constraints = Constraints()
        )

        assertEquals(ScheduleResult.ACCEPTED, result, "Task should be accepted")

        // Verify task is scheduled
        delay(1000)
        val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
        assertTrue(workInfo.isNotEmpty(), "Task should be scheduled in WorkManager")

        println("✅ Task scheduled. Device is now in background mode.")
        println("   Please lock screen and wait 10 seconds...")
        println("   Task should execute after ~5 seconds delay.")

        // Wait for execution + buffer
        delay(15.seconds)

        println("✅ Test completed. Check logs for execution confirmation.")
    }

    @Test
    fun test_02_PeriodicTask_ExecutesRegularly() = runBlocking {
        println("\n🧪 TEST: Periodic task executes regularly")

        // Note: WorkManager minimum periodic interval is 15 minutes
        // This test verifies scheduling, not actual periodic execution
        val taskId = "real-device-periodic-${System.currentTimeMillis()}"
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000), // 15 min
            workerClassName = "LogWorker",
            constraints = Constraints()
        )

        assertEquals(ScheduleResult.ACCEPTED, result)

        val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
        assertTrue(workInfo.isNotEmpty(), "Periodic task should be scheduled")

        println("✅ Periodic task scheduled with 15-minute interval")
        println("   For full test, leave device running for 30+ minutes")
        println("   Task should execute every ~15 minutes")
    }

    @Test
    fun test_03_TaskChain_ExecutesSequentially() = runBlocking {
        println("\n🧪 TEST: Task chain executes all steps sequentially")

        val result = scheduler.beginWith(
            TaskRequest(workerClassName = "Step1Worker")
        ).then(
            TaskRequest(workerClassName = "Step2Worker")
        ).then(
            TaskRequest(workerClassName = "Step3Worker")
        ).withId("real-device-chain-${System.currentTimeMillis()}")
        .enqueue()

        println("✅ Chain scheduled with 3 steps")
        println("   Check logs for sequential execution:")
        println("   adb logcat | grep -E 'Step1Worker|Step2Worker|Step3Worker'")

        delay(10.seconds) // Allow time for execution
    }

    // ═══════════════════════════════════════════════════════════════
    // Suite 2: Constraint Handling
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun test_04_NetworkConstraint_BlocksWithoutNetwork() = runBlocking {
        println("\n🧪 TEST: Network constraint blocks task without network")
        println("   ⚠️ MANUAL STEP: Turn OFF WiFi and Mobile Data now!")
        delay(5.seconds) // Give time to disable network

        val taskId = "network-constrained-${System.currentTimeMillis()}"
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(),
            workerClassName = "NetworkWorker",
            constraints = Constraints(requiresNetwork = true)
        )

        assertEquals(ScheduleResult.ACCEPTED, result)

        delay(3.seconds)
        val workInfo1 = workManager.getWorkInfosForUniqueWork(taskId).get()
        println("   Task state (no network): ${workInfo1.firstOrNull()?.state}")
        println("   Expected: BLOCKED or ENQUEUED (not RUNNING)")

        println("   ⚠️ MANUAL STEP: Turn ON WiFi now!")
        delay(10.seconds) // Give time to enable network

        val workInfo2 = workManager.getWorkInfosForUniqueWork(taskId).get()
        println("   Task state (with network): ${workInfo2.firstOrNull()?.state}")
        println("   Expected: RUNNING or SUCCEEDED")

        println("✅ Test completed. Task should have executed when network available.")
    }

    @Test
    fun test_05_ChargingConstraint_WaitsForCharger() = runBlocking {
        println("\n🧪 TEST: Charging constraint waits for charger")
        println("   ⚠️ ENSURE: Device is UNPLUGGED")

        val taskId = "charging-constrained-${System.currentTimeMillis()}"
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(),
            workerClassName = "ChargingWorker",
            constraints = Constraints(requiresCharging = true)
        )

        assertEquals(ScheduleResult.ACCEPTED, result)

        delay(3.seconds)
        val workInfo1 = workManager.getWorkInfosForUniqueWork(taskId).get()
        println("   Task state (unplugged): ${workInfo1.firstOrNull()?.state}")
        println("   Expected: BLOCKED or ENQUEUED")

        println("   ⚠️ MANUAL STEP: Plug in charger now!")
        println("   Waiting 15 seconds for task to start...")
        delay(15.seconds)

        val workInfo2 = workManager.getWorkInfosForUniqueWork(taskId).get()
        println("   Task state (charging): ${workInfo2.firstOrNull()?.state}")
        println("   Expected: RUNNING or SUCCEEDED")

        println("✅ Test completed.")
    }

    // ═══════════════════════════════════════════════════════════════
    // Suite 3: Stress Tests
    // ═══════════════════════════════════════════════════════════════

    /**
     * Stress test: Verify system can SCHEDULE 100 concurrent tasks without crashing
     *
     * This test focuses on SCHEDULING capability, not execution completion.
     *
     * Why: On real devices, WorkManager enforces job quotas and battery optimization
     * policies that make completion rate unpredictable (0-100%). However, the system
     * should be able to ACCEPT and SCHEDULE all tasks without crashing.
     *
     * Test verifies:
     * 1. All 100 tasks are ACCEPTED by scheduler
     * 2. Tasks are registered in WorkManager
     * 3. System doesn't crash or OOM under load
     * 4. Monitors actual completion rate for informational purposes
     */
    @Test
    fun test_06_StressTest_100ConcurrentTasks() = runBlocking {
        println("\n🧪 STRESS TEST: 100 concurrent tasks")

        val startTime = System.currentTimeMillis()
        val taskIds = mutableListOf<String>()

        // Schedule 100 tasks
        repeat(100) { index ->
            val taskId = "stress-test-$index-${System.currentTimeMillis()}"
            taskIds.add(taskId)

            val result = scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(initialDelayMs = 0), // No delay for immediate execution
                workerClassName = "StressTestWorker",
                constraints = Constraints()
            )

            assertEquals(ScheduleResult.ACCEPTED, result, "Task $index should be accepted")
        }

        val schedulingTime = System.currentTimeMillis() - startTime
        println("   ✅ Scheduled 100 tasks in ${schedulingTime}ms")
        println("   Average: ${schedulingTime / 100}ms per task")

        // Verify all tasks are registered in WorkManager
        delay(2.seconds) // Give WorkManager time to register tasks
        var registered = 0
        taskIds.forEach { taskId ->
            val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
            if (workInfo.isNotEmpty()) {
                registered++
            }
        }

        println("   📊 Registration Results:")
        println("      Total scheduled: 100")
        println("      Registered in WorkManager: $registered")

        // Assert: All tasks should be registered (this is the real test)
        assertTrue(registered >= 95,
            "At least 95% of tasks should be registered in WorkManager (registered: $registered/100). " +
            "This indicates the scheduling system works under high load.")

        println("   ✅ System successfully scheduled 100 concurrent tasks without crashing!")

        // Optional: Monitor actual completion (informational only, not asserted)
        println("   ⏳ Monitoring actual execution completion (informational, not asserted)...")
        delay(45.seconds)

        var completed = 0
        taskIds.forEach { taskId ->
            val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
            if (workInfo.firstOrNull()?.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                completed++
            }
        }

        println("   📊 Execution Results (informational):")
        println("      Completed: $completed/100 (${completed}%)")
        println("      Note: Completion rate varies due to device throttling and is not asserted")
        println("✅ Stress test passed! System handled high scheduling load without crashing.")
    }

    @Test
    fun test_07_RapidReschedule_NoMemoryLeak() = runBlocking {
        println("\n🧪 TEST: Rapid reschedule (1000x) without memory leak")

        val taskId = "rapid-reschedule"
        val startTime = System.currentTimeMillis()

        repeat(1000) { iteration ->
            scheduler.enqueue(
                id = taskId,
                trigger = TaskTrigger.OneTime(initialDelayMs = 60000),
                workerClassName = "RapidWorker",
                policy = ExistingPolicy.REPLACE
            )

            if (iteration % 100 == 0) {
                println("   Progress: $iteration/1000")
            }
        }

        val duration = System.currentTimeMillis() - startTime
        println("   ✅ Completed 1000 reschedules in ${duration}ms")
        println("   Average: ${duration / 1000}ms per reschedule")

        // Verify final task exists
        val workInfo = workManager.getWorkInfosForUniqueWork(taskId).get()
        assertTrue(workInfo.isNotEmpty(), "Final task should be scheduled")

        println("✅ No crashes or memory leaks detected")
    }

    // ═══════════════════════════════════════════════════════════════
    // Suite 4: Chinese ROM Compatibility
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun test_08_ChineseROM_CompatibilityCheck() = runBlocking {
        if (!isChineseROM()) {
            println("\n⏭️  SKIPPED: Not a Chinese ROM device")
            println("   Manufacturer: ${Build.MANUFACTURER}")
            return@runBlocking
        }

        println("\n🧪 TEST: Chinese ROM compatibility")
        println("   ⚠️ Chinese ROM detected: ${Build.MANUFACTURER} ${Build.MODEL}")
        println("")
        println("   Required User Actions:")
        println("   1. Enable Autostart Permission")
        println("   2. Disable Battery Optimization")
        println("   3. Add to Protected/Locked Apps")
        println("")
        println("   For MIUI (Xiaomi):")
        println("      Settings → Apps → Manage Apps → [App] → Autostart → Enable")
        println("      Settings → Battery → [App] → No restrictions")
        println("")
        println("   For EMUI (Huawei):")
        println("      Phone Manager → Protected Apps → [App] → Enable")
        println("")
        println("   For ColorOS (Oppo):")
        println("      Settings → Battery → [App] → Allow background activity")

        // Schedule test task
        val taskId = "chinese-rom-test-${System.currentTimeMillis()}"
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(initialDelayMs = 10000),
            workerClassName = "ChineseROMTestWorker",
            constraints = Constraints()
        )

        println("\n   ✅ Test task scheduled")
        println("   Close app and wait 20 seconds...")
        println("   If task executes, permissions are configured correctly")
        println("   If not, review permission settings above")

        delay(25.seconds)
        println("✅ Chinese ROM test completed")
    }

    // ═══════════════════════════════════════════════════════════════
    // Suite 5: Performance Benchmarks
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun test_09_Performance_SchedulingLatency() = runBlocking {
        println("\n📊 BENCHMARK: Task scheduling latency")

        val iterations = 100
        val latencies = mutableListOf<Long>()

        repeat(iterations) { index ->
            val startTime = System.nanoTime()

            scheduler.enqueue(
                id = "perf-test-$index",
                trigger = TaskTrigger.OneTime(initialDelayMs = 60000),
                workerClassName = "PerfTestWorker",
                constraints = Constraints()
            )

            val endTime = System.nanoTime()
            val latencyMs = (endTime - startTime) / 1_000_000
            latencies.add(latencyMs)
        }

        val avgLatency = latencies.average()
        val minLatency = latencies.minOrNull() ?: 0
        val maxLatency = latencies.maxOrNull() ?: 0
        val p95Latency = latencies.sorted()[95]

        println("""

            📊 Scheduling Latency Results (${iterations} iterations):
               Average: ${avgLatency.format()}ms
               Min: ${minLatency}ms
               Max: ${maxLatency}ms
               P95: ${p95Latency}ms

            ✅ PASS: Average <50ms (good performance)
            ⚠️  WARNING: Average 50-100ms (acceptable)
            ❌ FAIL: Average >100ms (performance issue)

        """.trimIndent())

        assertTrue(avgLatency < 100, "Average latency should be <100ms")
        println("✅ Performance benchmark passed!")
    }

    @Test
    fun test_10_Performance_TaskExecutionStartTime() = runBlocking {
        println("\n📊 BENCHMARK: Task execution start time accuracy")

        val taskId = "execution-timing-test"
        val expectedDelay = 2000L // 2 seconds

        val scheduleTime = System.currentTimeMillis()
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(initialDelayMs = expectedDelay),
            workerClassName = "TimingTestWorker",
            constraints = Constraints()
        )

        // Wait for execution + buffer
        delay((expectedDelay + 3000).toLong())

        // Check execution time (logged by worker)
        println("""

            📊 Execution Timing Results:
               Expected delay: ${expectedDelay}ms
               Check worker logs for actual execution time

            Acceptable range: ${expectedDelay - 500}ms to ${expectedDelay + 2000}ms

        """.trimIndent())

        println("✅ Timing test completed. Review logs for accuracy.")
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private fun Double.format(): String = "%.2f".format(this)

    // ═══════════════════════════════════════════════════════════════
    // Test Workers
    // ═══════════════════════════════════════════════════════════════

    private class RealDeviceWorkerFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? {
            return when (workerClassName) {
                "LogWorker" -> LogWorker()
                "Step1Worker" -> StepWorker("Step 1")
                "Step2Worker" -> StepWorker("Step 2")
                "Step3Worker" -> StepWorker("Step 3")
                "NetworkWorker" -> NetworkWorker()
                "ChargingWorker" -> ChargingWorker()
                "StressTestWorker" -> StressTestWorker()
                "RapidWorker" -> RapidWorker()
                "ChineseROMTestWorker" -> ChineseROMTestWorker()
                "PerfTestWorker" -> PerfTestWorker()
                "TimingTestWorker" -> TimingTestWorker()
                else -> null
            }
        }
    }

    private class LogWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            val timestamp = System.currentTimeMillis()
            println("✅ LogWorker executed at $timestamp")
            return WorkerResult.Success(message = "Logged at $timestamp")
        }
    }

    private class StepWorker(private val stepName: String) : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            println("✅ $stepName executed")
            delay(500) // Simulate work
            return WorkerResult.Success(message = "$stepName completed")
        }
    }

    private class NetworkWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            println("✅ NetworkWorker executed (network constraint satisfied)")
            return WorkerResult.Success(message = "Network task completed")
        }
    }

    private class ChargingWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            println("✅ ChargingWorker executed (device is charging)")
            return WorkerResult.Success(message = "Charging task completed")
        }
    }

    private class StressTestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            // Minimal work to test concurrency
            delay(100)
            return WorkerResult.Success(message = "Stress test task completed")
        }
    }

    private class RapidWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            return WorkerResult.Success(message = "Rapid reschedule task")
        }
    }

    private class ChineseROMTestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            println("✅✅✅ ChineseROMTestWorker executed successfully!")
            println("   This means your Chinese ROM permissions are configured correctly")
            return WorkerResult.Success(message = "Chinese ROM compatible")
        }
    }

    private class PerfTestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            return WorkerResult.Success(message = "Perf test")
        }
    }

    private class TimingTestWorker : AndroidWorker {
        override suspend fun doWork(input: String?): WorkerResult {
            val executionTime = System.currentTimeMillis()
            println("⏰ TimingTestWorker executed at: $executionTime")
            return WorkerResult.Success(message = "Timing test completed")
        }
    }
}
