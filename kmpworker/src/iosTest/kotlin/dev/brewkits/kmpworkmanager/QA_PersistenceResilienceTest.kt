@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA Test: Persistence Resilience — Chain resumes without duplicates after force-kill
 *
 * Scenarios validated:
 * 1. ChainProgress correctly identifies the next unexecuted step after partial completion.
 * 2. ChainProgress persisted to IosFileStorage survives a simulated process restart
 *    (new IosFileStorage + ChainExecutor instances reading the same filesystem).
 * 3. A 100-chain batch enqueued and partially run resumes from the correct checkpoint —
 *    no step is executed twice (no duplicate), no step is skipped (no data loss).
 * 4. Schema version field is present and correct (guards against future silent migrations).
 * 5. A chain where all 100 steps are completed is not re-executed after resume.
 *
 * Run: ./gradlew :kmpworker:iosSimulatorArm64Test
 */
@OptIn(ExperimentalForeignApi::class)
class QA_PersistenceResilienceTest {

    private lateinit var storage: IosFileStorage

    @BeforeTest
    fun setup() = runTest {
        storage = IosFileStorage()
        drainQueue()
    }

    @AfterTest
    fun cleanup() = runTest {
        drainQueue()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-01: ChainProgress.getNextStepIndex() returns correct resume point
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc01_chainProgress_resumesFromCorrectStep_notFromBeginning() {
        val totalSteps = 100
        var progress = ChainProgress(chainId = "tc01-chain", totalSteps = totalSteps)

        // Simulate completing first 5 steps (like a process kill mid-chain)
        for (i in 0 until 5) {
            progress = progress.withCompletedStep(i)
        }

        // Resume point must be step 5, not 0
        assertEquals(5, progress.getNextStepIndex(),
            "After 5 completed steps, resume must start at index 5, not 0")

        assertEquals(5, progress.completedSteps.size)
        assertFalse(progress.isComplete(), "Chain must not be marked complete with 95 steps remaining")
        assertEquals(5, progress.getCompletionPercentage(),
            "Completion percentage must be 5% after 5 of 100 steps")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-02: Completed steps are not re-executed after resume
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc02_completedSteps_areSkipped_noDoubleExecution() {
        val totalSteps = 20
        var progress = ChainProgress(chainId = "tc02-chain", totalSteps = totalSteps)

        // Mark even steps (0, 2, 4, … 18) as completed
        for (i in 0 until totalSteps step 2) {
            progress = progress.withCompletedStep(i)
        }

        // Verify none of the completed even steps reappear as "next"
        val remainingSteps = mutableListOf<Int>()
        var p = progress
        while (true) {
            val next = p.getNextStepIndex() ?: break
            assertFalse(next % 2 == 0,
                "Completed even step $next must never appear as the next step to execute")
            p = p.withCompletedStep(next)
            remainingSteps.add(next)
        }

        assertEquals(10, remainingSteps.size,
            "Exactly 10 odd steps (1,3,5,...19) must remain after 10 even steps are pre-completed")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19), remainingSteps,
            "Remaining steps must be in ascending order and all odd")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-03: Progress persists across IosFileStorage instances (simulates process restart)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc03_persistedProgress_survivesProcessRestart_resumesCorrectly() = runTest {
        val chainId = "tc03-persist-chain"
        val totalSteps = 10

        // Phase 1: "before kill" — save progress with 3 completed steps
        val progressBeforeKill = ChainProgress(
            chainId = chainId,
            totalSteps = totalSteps,
            completedSteps = listOf(0, 1, 2)
        )
        storage.saveChainProgress(progressBeforeKill)

        // Phase 2: "process restart" — new storage instance (same filesystem)
        val storageAfterRestart = IosFileStorage()
        val loadedProgress = storageAfterRestart.loadChainProgress(chainId)

        assertNotNull(loadedProgress, "Progress must be loadable after simulated process restart")
        assertEquals(chainId, loadedProgress.chainId)
        assertEquals(totalSteps, loadedProgress.totalSteps)
        assertEquals(listOf(0, 1, 2), loadedProgress.completedSteps,
            "Completed steps must survive the process restart")
        assertEquals(3, loadedProgress.getNextStepIndex(),
            "After restart, execution must resume from step 3, not step 0")

        // Cleanup
        storageAfterRestart.loadChainProgress(chainId) // Access to ensure it's flushed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-04: 100-step chain — force-kill at step 50 resumes from 50, not 0
    //        Execution counter must be exactly 50 (no duplicates, no skips)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc04_100StepChain_forceKillAt50_resumesAt50_noduplicates() = runTest {
        val chainId = "tc04-100step-chain"
        val totalSteps = 100
        val killAfterStep = 50

        // Build 100-step chain: each step has one task
        val steps: List<List<TaskRequest>> = (0 until totalSteps).map { stepIdx ->
            listOf(TaskRequest(workerClassName = "Step$stepIdx"))
        }
        storage.saveChainDefinition(chainId, steps)
        storage.enqueueChain(chainId)

        // Simulate "kill at step 50" by saving ChainProgress with steps 0–49 completed
        val progressAtKill = ChainProgress(
            chainId = chainId,
            totalSteps = totalSteps,
            completedSteps = (0 until killAfterStep).toList()
        )
        storage.saveChainProgress(progressAtKill)

        // "Process restart": new storage + executor; track which steps actually ran
        val executedSteps = mutableListOf<Int>()
        val resumeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                val stepIdx = workerClassName.removePrefix("Step").toIntOrNull()
                    ?: return null
                return object : IosWorker {
                    override suspend fun doWork(input: String?): WorkerResult {
                        executedSteps.add(stepIdx)
                        return WorkerResult.Success()
                    }
                }
            }
        }

        val resumeStorage = IosFileStorage()
        val resumeExecutor = ChainExecutor(resumeFactory)
        resumeExecutor.resetShutdownState()
        resumeExecutor.executeChainsInBatch(maxChains = 1)

        // Assertions
        assertEquals(50, executedSteps.size,
            "Exactly 50 steps must execute (steps 50-99), not 100 (would mean double execution)")

        val expectedSteps = (killAfterStep until totalSteps).toList()
        assertEquals(expectedSteps, executedSteps.sorted(),
            "Only steps 50-99 must execute after resume — steps 0-49 must be skipped")

        for (step in 0 until killAfterStep) {
            assertFalse(executedSteps.contains(step),
                "Step $step was completed before kill and must NOT be re-executed (duplicate!)")
        }

        // Clean up resume storage
        while (resumeStorage.dequeueChain() != null) { /* drain */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-05: Fully completed chain is not re-executed after restart
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc05_fullyCompletedChain_isNotReExecutedAfterRestart() = runTest {
        val chainId = "tc05-done-chain"
        val totalSteps = 5

        val steps: List<List<TaskRequest>> = (0 until totalSteps).map { i ->
            listOf(TaskRequest(workerClassName = "Step$i"))
        }
        storage.saveChainDefinition(chainId, steps)
        storage.enqueueChain(chainId)

        // Save progress showing all steps done
        val fullyDone = ChainProgress(
            chainId = chainId,
            totalSteps = totalSteps,
            completedSteps = (0 until totalSteps).toList()
        )
        storage.saveChainProgress(fullyDone)

        assertTrue(fullyDone.isComplete(), "Progress must report isComplete = true")
        assertNull(fullyDone.getNextStepIndex(),
            "getNextStepIndex must return null for a fully completed chain")
        assertEquals(100, fullyDone.getCompletionPercentage())

        // Verify: a new executor finds nothing to execute
        var executedCount = 0
        val countingFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? =
                object : IosWorker {
                    override suspend fun doWork(input: String?): WorkerResult {
                        executedCount++
                        return WorkerResult.Success()
                    }
                }
        }

        val freshStorage = IosFileStorage()
        val freshExecutor = ChainExecutor(countingFactory)
        freshExecutor.resetShutdownState()
        freshExecutor.executeChainsInBatch(maxChains = 5)

        assertEquals(0, executedCount,
            "A fully-completed chain must not trigger any worker execution after restart")

        while (freshStorage.dequeueChain() != null) { /* drain */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-06: Schema version field is present and matches CURRENT_SCHEMA_VERSION
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc06_chainProgress_schemaVersion_isPresent_andCurrent() {
        val progress = ChainProgress(chainId = "tc06", totalSteps = 1)

        assertEquals(
            ChainProgress.CURRENT_SCHEMA_VERSION,
            progress.schemaVersion,
            "Default schemaVersion must equal CURRENT_SCHEMA_VERSION (${ChainProgress.CURRENT_SCHEMA_VERSION})"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-07: retryCount increments on failure; hasExceededRetries fires at maxRetries
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun tc07_retryCount_incrementsOnFailure_andExceedsAtMaxRetries() {
        val maxRetries = 3
        var progress = ChainProgress(
            chainId = "tc07-retry",
            totalSteps = 5,
            maxRetries = maxRetries
        )

        assertFalse(progress.hasExceededRetries(), "Fresh chain must not have exceeded retries")

        repeat(maxRetries) { attempt ->
            progress = progress.withFailure(stepIndex = 0, errorMessage = "Error at attempt $attempt")
            if (attempt < maxRetries - 1) {
                assertFalse(progress.hasExceededRetries(),
                    "retryCount=${progress.retryCount} must not yet exceed maxRetries=$maxRetries")
            }
        }

        assertTrue(progress.hasExceededRetries(),
            "After $maxRetries failures, hasExceededRetries must return true")
        assertEquals(maxRetries, progress.retryCount)
        assertEquals("Error at attempt ${maxRetries - 1}", progress.lastError)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun drainQueue() {
        while (storage.dequeueChain() != null) { /* drain */ }
    }
}
