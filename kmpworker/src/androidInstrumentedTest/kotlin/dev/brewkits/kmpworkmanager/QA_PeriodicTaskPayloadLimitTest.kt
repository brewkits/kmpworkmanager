package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * QA Test: Android Periodic Task Payload Limit (Character vs Byte Bug).
 */
@RunWith(AndroidJUnit4::class)
class QA_PeriodicTaskPayloadLimitTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun test_periodic_task_rejects_large_multibyte_payload() = runBlocking {
        val multiByteJson = "{\"data\":\"${"🔥".repeat(3900)}\"}"
        
        // The library now catches the exception and returns REJECTED_OS_POLICY 
        // instead of crashing the caller.
        val result = scheduler.enqueue(
            id = "qa-periodic-multibyte",
            trigger = TaskTrigger.Periodic(15 * 60 * 1000L),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = multiByteJson,
            policy = ExistingPolicy.REPLACE
        )
        
        assertEquals(ScheduleResult.REJECTED_OS_POLICY, result)
    }
}
