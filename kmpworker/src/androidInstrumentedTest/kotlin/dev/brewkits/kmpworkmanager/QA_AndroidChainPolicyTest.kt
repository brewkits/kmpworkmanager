package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * QA Test: Android WorkManager `enqueueChain` policy bug.
 *
 * This test verifies that `NativeTaskScheduler.enqueueChain` correctly applies
 * the `ExistingPolicy` (like REPLACE or KEEP) by utilizing WorkManager's Unique Work API
 * (`beginUniqueWork`).
 * 
 * If it uses `beginWith` (anonymous work), the policy is completely ignored,
 * and duplicate parallel chains will be created, leading to resource exhaustion.
 */
@RunWith(AndroidJUnit4::class)
class QA_AndroidChainPolicyTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: NativeTaskScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        scheduler = NativeTaskScheduler(context)
        workManager.cancelAllWork()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork()
    }

    @Test
    fun test_enqueueChain_respects_REPLACE_policy_via_UniqueWork() = runBlocking {
        val chainId = "qa-chain-replace-bug"
        
        val task1 = TaskRequest("TestWorker1", "input1")
        val chain1 = scheduler.beginWith(task1)
        
        // 1. Enqueue first chain
        scheduler.enqueueChain(chain1, chainId, ExistingPolicy.REPLACE)
        
        val task2 = TaskRequest("TestWorker2", "input2")
        val chain2 = scheduler.beginWith(task2)
        
        // 2. Enqueue second chain with REPLACE policy (same ID)
        scheduler.enqueueChain(chain2, chainId, ExistingPolicy.REPLACE)
        
        // 3. Verify
        // If the bug exists (using `beginWith` instead of `beginUniqueWork`), 
        // WorkManager won't recognize this as a Unique Work, and `getWorkInfosForUniqueWork`
        // will return an empty list!
        val workInfos = workManager.getWorkInfosForUniqueWork(chainId).get()
        
        assertTrue(
            workInfos.isNotEmpty(), 
            "BUG EXPOSED: Unique work chain does not exist for ID '$chainId'. " +
            "NativeTaskScheduler is using `beginWith` (anonymous) instead of `beginUniqueWork`. " +
            "ExistingPolicy is being completely ignored!"
        )
        
        // If fixed, there should only be the tasks from chain2.
        val hasNewTask = workInfos.any { it.tags.contains("worker-TestWorker2") }
        assertTrue(hasNewTask, "The new chain was not enqueued properly.")
    }
}
