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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    private suspend fun drainQueue() {
        while (storage.getQueueSize() > 0) {
            storage.dequeueChain()
        }
        storage.flushNow()
    }

    @Test
    fun tc01_chainProgress_resumesFromCorrectStep_notFromBeginning() {
        val totalSteps = 100
        var progress = ChainProgress(chainId = "tc01-chain", totalSteps = totalSteps)

        for (i in 0 until 5) {
            progress = progress.withCompletedStep(i)
        }

        assertEquals(5, progress.getNextStepIndex())
        assertEquals(5, progress.completedSteps.size)
        assertFalse(progress.isComplete())
    }

    @Test
    fun tc02_completedSteps_areSkipped_noDoubleExecution() {
        val totalSteps = 20
        var progress = ChainProgress(chainId = "tc02-chain", totalSteps = totalSteps)

        for (i in 0 until totalSteps step 2) {
            progress = progress.withCompletedStep(i)
        }

        val remainingSteps = mutableListOf<Int>()
        var p = progress
        while (true) {
            val next = p.getNextStepIndex() ?: break
            assertFalse(next % 2 == 0)
            p = p.withCompletedStep(next)
            remainingSteps.add(next)
        }

        assertEquals(10, remainingSteps.size)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17, 19), remainingSteps)
    }

    @Test
    fun tc03_persistedProgress_survivesProcessRestart_resumesCorrectly() = runTest {
        val chainId = "tc03-persist-chain"
        val totalSteps = 10

        val progressBeforeKill = ChainProgress(
            chainId = chainId,
            totalSteps = totalSteps,
            completedSteps = listOf(0, 1, 2)
        )
        storage.saveChainProgress(progressBeforeKill)
        storage.flushNow()

        val storageAfterRestart = IosFileStorage()
        val loadedProgress = storageAfterRestart.loadChainProgress(chainId)

        assertNotNull(loadedProgress)
        assertEquals(chainId, loadedProgress.chainId)
        assertEquals(listOf(0, 1, 2), loadedProgress.completedSteps)
        assertEquals(3, loadedProgress.getNextStepIndex())
    }

    @Test
    fun tc04_100StepChain_forceKillAt50_resumesAt50_noduplicates() = runTest {
        val chainId = "tc04-100step-chain"
        val totalSteps = 100
        val killAfterStep = 50

        val steps: List<List<TaskRequest>> = (0 until totalSteps).map { stepIdx ->
            listOf(TaskRequest(workerClassName = "Step$stepIdx"))
        }
        storage.saveChainDefinition(chainId, steps)
        storage.enqueueChain(chainId)

        val progressAtKill = ChainProgress(
            chainId = chainId,
            totalSteps = totalSteps,
            completedSteps = (0 until killAfterStep).toList()
        )
        storage.saveChainProgress(progressAtKill)
        storage.flushNow()

        val executedSteps = mutableListOf<Int>()
        val resumeFactory = object : IosWorkerFactory {
            override fun createWorker(workerClassName: String): IosWorker? {
                val stepIdx = workerClassName.removePrefix("Step").toIntOrNull()
                if (stepIdx != null) executedSteps.add(stepIdx)
                return object : IosWorker {
                    override suspend fun doWork(input: String?): WorkerResult = WorkerResult.Success()
                }
            }
        }

        val executor = ChainExecutor(resumeFactory)
        withContext(Dispatchers.Default) { executor.executeChainsInBatch(maxChains = 1) }
        storage.flushNow()

        assertTrue(executedSteps.isNotEmpty(), "At least one step must execute after resume")
        assertFalse(executedSteps.any { it < killAfterStep }, "Steps before 50 must not be re-executed")
        assertEquals(killAfterStep, executedSteps.first(), "Resume must start exactly at step 50")
    }
}
