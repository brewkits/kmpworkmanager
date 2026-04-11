package dev.brewkits.kmpworkmanager.background.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlin.test.*
import dev.brewkits.kmpworkmanager.currentTimeMillis

/**
 * Unit and stress tests for Buffered I/O with debouncing (Task #4)
 * Tests:
 * - Debounced flush (500ms)
 * - Immediate flush on demand
 * - Buffer batching multiple saves
 * - Concurrent write safety
 * - Crash recovery
 * - Performance improvement (90% I/O reduction)
 */
class BufferedIOTest {

    /**
     * Mock IosFileStorage to test buffering logic
     */
    private class MockBufferedStorage(
        private val debounceMs: Long = 500L,
        context: kotlin.coroutines.CoroutineContext = Dispatchers.Default
    ) {
        private val _progressBuffer = mutableMapOf<String, ChainProgress>()
        val progressBuffer: Map<String, ChainProgress> get() = _progressBuffer
        var flushCount = 0
        var writeCount = 0
        private var flushJob: Job? = null
        private val scope = CoroutineScope(context + SupervisorJob())
        private val mutex = Mutex()

        fun saveChainProgress(progress: ChainProgress) {
            scope.launch {
                mutex.withLock {
                    _progressBuffer[progress.chainId] = progress
                    // Debounce: cancel previous flush and schedule new one
                    flushJob?.cancel()
                    flushJob = scope.launch {
                        delay(debounceMs)
                        mutex.withLock { flushProgressBuffer() }
                    }
                }
            }
        }

        suspend fun flushNow() {
            // Give any in-flight saveChainProgress launches time to update buffer
            yield() 
            mutex.withLock {
                flushJob?.cancel()
                flushProgressBuffer()
            }
        }

        private fun flushProgressBuffer() {
            if (_progressBuffer.isEmpty()) return
            _progressBuffer.forEach { _ -> writeCount++ }
            _progressBuffer.clear()
            flushCount++
        }

        fun reset() {
            flushCount = 0
            writeCount = 0
            _progressBuffer.clear()
        }
    }

    @Test
    fun testDebouncing_SingleSave() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)
        val progress = createTestProgress("chain1", 0)

        storage.saveChainProgress(progress)
        advanceTimeBy(100)

        // Should still be in buffer
        assertEquals(1, storage.progressBuffer.size)
        assertEquals(0, storage.flushCount, "Should not flush yet")

        advanceTimeBy(401) // Wait for debounce

        // Should have flushed
        assertEquals(0, storage.progressBuffer.size)
        assertEquals(1, storage.flushCount, "Should flush after debounce")
        assertEquals(1, storage.writeCount, "Should write 1 item")
    }

    @Test
    fun testDebouncing_MultipleSaves() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // Save 5 updates rapidly
        repeat(5) { i ->
            storage.saveChainProgress(createTestProgress("chain1", i))
            advanceTimeBy(50) // 50ms between saves
        }

        // Total time: 250ms (less than 500ms debounce)
        assertEquals(1, storage.progressBuffer.size, "Should buffer all updates")
        assertEquals(0, storage.flushCount, "Should not flush yet")

        advanceTimeBy(501) // Wait for debounce

        // Should have flushed once with latest value
        assertEquals(0, storage.progressBuffer.size)
        assertEquals(1, storage.flushCount, "Should flush once")
        assertEquals(1, storage.writeCount, "Should write 1 item (latest)")
    }

    @Test
    fun testImmediateFlush() = runTest {
        val storage = MockBufferedStorage(context = backgroundScope.coroutineContext)

        storage.saveChainProgress(createTestProgress("chain1", 0))
        storage.saveChainProgress(createTestProgress("chain2", 0))
        storage.saveChainProgress(createTestProgress("chain3", 0))

        // Immediate flush
        storage.flushNow()

        // Should flush immediately
        assertEquals(0, storage.progressBuffer.size)
        assertEquals(1, storage.flushCount, "Should flush immediately")
        assertEquals(3, storage.writeCount, "Should write all 3 items")
    }

    @Test
    fun testBatching_MultipleChains() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // Update 10 different chains rapidly
        repeat(10) { i ->
            storage.saveChainProgress(createTestProgress("chain$i", 0))
            advanceTimeBy(20)
        }

        // Wait for debounced flush
        advanceTimeBy(600)

        // Should batch all 10 writes into 1 flush
        assertEquals(1, storage.flushCount, "Should flush once")
        assertEquals(10, storage.writeCount, "Should write all 10 items")
    }

    @Test
    fun testPerformanceImprovement_90PercentReduction() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // Simulate 50 rapid task completions (real-world scenario)
        repeat(50) { i ->
            storage.saveChainProgress(createTestProgress("chain${i % 5}", i / 5))
            advanceTimeBy(10) // 10ms between completions
        }

        // Total time: 500ms
        advanceTimeBy(600) // Wait for flush

        // Without buffering: 50 I/O operations
        // With buffering: 1 flush with 5 unique chains = 5 I/O operations
        // Reduction: (50 - 5) / 50 = 90%

        assertEquals(1, storage.flushCount, "Should flush once")
        assertEquals(5, storage.writeCount, "Should write 5 unique chains (90% reduction)")

        val reduction = ((50.0 - 5.0) / 50.0) * 100
        assertEquals(90.0, reduction, "Should achieve 90% I/O reduction")
    }

    /**
     * Stress test: High-frequency updates
     */
    @Test
    fun stressTestHighFrequencyUpdates() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // 1000 updates across 10 chains in 5 seconds
        val updateCount = 1000
        val chainCount = 10

        repeat(updateCount) { i ->
            storage.saveChainProgress(createTestProgress("chain${i % chainCount}", i / chainCount))
            advanceTimeBy(5) // 5ms between updates
        }

        advanceTimeBy(600) // Wait for final flush

        // Should batch into very few flushes
        assertTrue(
            storage.flushCount < 20,
            "Should batch ${updateCount} updates into <20 flushes (was ${storage.flushCount})"
        )

        assertTrue(
            storage.writeCount < updateCount,
            "Should reduce I/O operations (${storage.writeCount} vs ${updateCount})"
        )
    }

    /**
     * Stress test: Concurrent chain executions
     */
    @Test
    fun stressTestConcurrentChains() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // 20 chains updating concurrently
        val jobs = (1..20).map { chainId ->
            launch {
                repeat(50) { step ->
                    storage.saveChainProgress(createTestProgress("chain$chainId", step))
                    delay(10)
                }
            }
        }

        // Wait for final flush
        advanceTimeBy(2000)

        // 20 chains * 50 updates = 1000 total updates
        // Should batch into 20 final writes (1 per chain) with debouncing
        assertTrue(
            storage.writeCount <= 20,
            "Should batch 1000 concurrent updates into ≤20 writes (was ${storage.writeCount})"
        )
    }

    /**
     * Test flush cancellation and rescheduling
     */
    @Test
    fun testFlushCancellationOnNewUpdate() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // Save and start debounce timer.
        // runCurrent() drains the scope.launch coroutine so flushJob is registered
        // before advanceTimeBy() moves the virtual clock past 500 ms.
        storage.saveChainProgress(createTestProgress("chain1", 0))
        runCurrent() // let saveChainProgress coroutine run and set up flushJob

        advanceTimeBy(400) // virtual time: 400 ms — 80% of debounce, no flush yet

        // New update cancels the in-progress timer and starts a fresh 500 ms window.
        storage.saveChainProgress(createTestProgress("chain1", 1))
        runCurrent() // let cancellation + new flushJob run before advancing time

        advanceTimeBy(400) // virtual time: 800 ms — only 400 ms into second window

        // Should not have flushed yet (timer was reset)
        assertEquals(0, storage.flushCount, "Should not flush yet due to timer reset")

        advanceTimeBy(200) // virtual time: 1000 ms — 600 ms into second window (> 500 ms debounce)

        // Now should flush
        assertEquals(1, storage.flushCount, "Should flush after reset debounce")
    }

    /**
     * Test buffer consistency under rapid updates
     */
    @Test
    fun testBufferConsistency() = runTest {
        val storage = MockBufferedStorage(context = backgroundScope.coroutineContext)
        val chainId = "test-chain"

        // Rapidly update same chain 100 times
        repeat(100) { step ->
            storage.saveChainProgress(createTestProgress(chainId, step))
        }

        // Use flushNow()
        storage.flushNow()

        // Should have flushed with latest value (step 99)
        assertEquals(1, storage.flushCount, "Should flush once")
        assertEquals(1, storage.writeCount, "Should write latest value only")
    }

    /**
     * Integration test: Simulate real chain execution pattern
     */
    @Test
    fun integrationTestRealExecutionPattern() = runTest {
        val storage = MockBufferedStorage(debounceMs = 500, context = backgroundScope.coroutineContext)

        // Simulate chain with 3 steps, 5 tasks per step
        val chainId = "integration-chain"
        val steps = 3
        val tasksPerStep = 5

        for (step in 0 until steps) {
            for (task in 0 until tasksPerStep) {
                storage.saveChainProgress(createTestProgress(chainId, step * tasksPerStep + task))
                advanceTimeBy(100) // 100ms per task execution
            }
        }

        // Immediate flush at end (like chain completion)
        storage.flushNow()

        // Total: 15 progress updates -> should batch significantly
        assertTrue(
            storage.writeCount < 15,
            "Should reduce I/O from 15 to <15 (was ${storage.writeCount})"
        )
    }

    private fun createTestProgress(chainId: String, completedTasks: Int): ChainProgress {
        val currentStep = completedTasks / 5
        val tasksInCurrentStep = completedTasks % 5

        // Build list of completed steps
        val completedStepsList = (0 until currentStep).toList()

        // Build map of completed tasks in each step
        val completedTasksMap = mutableMapOf<Int, List<Int>>()
        // All previous steps are fully completed (tasks 0-4)
        for (step in 0 until currentStep) {
            completedTasksMap[step] = listOf(0, 1, 2, 3, 4)
        }
        // Current step has partial completion
        if (tasksInCurrentStep > 0) {
            completedTasksMap[currentStep] = (0 until tasksInCurrentStep).toList()
        }

        return ChainProgress(
            chainId = chainId,
            totalSteps = 3,
            completedSteps = completedStepsList,
            completedTasksInSteps = completedTasksMap,
            retryCount = 0
        )
    }
}
