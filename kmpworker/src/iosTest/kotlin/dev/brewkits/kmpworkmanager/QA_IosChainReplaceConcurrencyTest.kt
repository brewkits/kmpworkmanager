package dev.brewkits.kmpworkmanager

import kotlinx.cinterop.ExperimentalForeignApi
import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskChain
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/**
 * QA Test: iOS ChainExecutor Concurrency Bug on REPLACE Policy.
 *
 * This test verifies the fix for the architectural bug where `NativeTaskScheduler.enqueueChain`
 * was previously updating files on disk for REPLACE policy but potentially failing to cancel 
 * the currently running Coroutine Job inside `ChainExecutor`.
 */
@OptIn(ExperimentalForeignApi::class)
class QA_IosChainReplaceConcurrencyTest {

    private lateinit var fileStorage: IosFileStorage
    private lateinit var scheduler: NativeTaskScheduler
    private lateinit var testDirectoryURL: NSURL

    @BeforeTest
    fun setup() {
        val tempDir = NSTemporaryDirectory()
        val testDirName = "kmpworkmanager_replace_bug_${NSDate().timeIntervalSince1970()}"
        testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtURL(testDirectoryURL, withIntermediateDirectories = true, attributes = null, error = null)

        fileStorage = IosFileStorage(baseDirectory = testDirectoryURL)
        scheduler = NativeTaskScheduler(fileStorage = fileStorage)
    }

    @AfterTest
    fun tearDown() {
        kotlinx.coroutines.runBlocking { fileStorage.close() }
        NSFileManager.defaultManager.removeItemAtURL(testDirectoryURL, error = null)
    }

    @Test
    fun test_replace_policy_must_cancel_running_coroutine() {
        // runBlocking is required here because the executor dispatches file I/O onto real GCD
        // threads (Dispatchers.Default). runTest uses virtual time which does not advance for
        // GCD-dispatched coroutines, so delay() in the worker would never be reached.
        kotlinx.coroutines.runBlocking {
            val chainId = "concurrent-replace-chain"
            var workerStarted = false
            var workerCancelled = false

            // Worker suspends indefinitely, simulating a long-running BGTask.
            val factory = object : IosWorkerFactory {
                override fun createWorker(workerClassName: String): IosWorker {
                    return object : IosWorker {
                        override suspend fun doWork(
                            input: String?,
                            env: dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
                        ): WorkerResult {
                            workerStarted = true
                            try {
                                delay(30_000)
                                return WorkerResult.Success()
                            } catch (e: CancellationException) {
                                workerCancelled = true
                                throw e
                            }
                        }
                        override fun close() {}
                    }
                }
            }

            val executor = ChainExecutor(factory, fileStorage = fileStorage)

            // 1. Enqueue chain A with KEEP policy
            scheduler.beginWith(TaskRequest("LongWorker", "input1"))
                .withId(chainId, ExistingPolicy.KEEP).enqueue()

            // 2. Start executing in background (simulates BGTask handler)
            val executionJob = launch {
                executor.executeNextChainFromQueue()
            }

            // 3. Give the GCD-dispatched file I/O and worker startup real time to complete
            delay(300)
            assertTrue(workerStarted, "Precondition: worker should have started within 300ms")

            // 4. Enqueue replacement — scheduler calls ChainJobRegistry.cancel(chainId) which
            //    suspends until the old job's finally block finishes (cancelAndJoin semantics).
            scheduler.beginWith(TaskRequest("NewWorker", "input2"))
                .withId(chainId, ExistingPolicy.REPLACE).enqueue()

            // ChainJobRegistry.cancel() already awaited the old job's completion, so
            // workerCancelled must be true immediately after enqueue() returns.
            assertTrue(
                workerCancelled,
                "Old chain coroutine was NOT cancelled by REPLACE policy via NativeTaskScheduler"
            )

            executionJob.cancelAndJoin()
            executor.close()
        }
    }
}
