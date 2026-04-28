package dev.brewkits.kmpworkmanager.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord
import dev.brewkits.kmpworkmanager.background.domain.ExecutionStatus
import dev.brewkits.kmpworkmanager.background.domain.TaskCompletionEvent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class AndroidPersistenceTest {

    private lateinit var context: Context
    private lateinit var eventStore: AndroidEventStore
    private lateinit var historyStore: AndroidExecutionHistoryStore
    private lateinit var testFilesDir: File

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testFilesDir = File(context.filesDir, "dev.brewkits.kmpworkmanager")
        if (testFilesDir.exists()) {
            testFilesDir.deleteRecursively()
        }
        eventStore = AndroidEventStore(context)
        historyStore = AndroidExecutionHistoryStore(context)
    }

    @AfterTest
    fun tearDown() {
        if (testFilesDir.exists()) {
            testFilesDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveAndGetUnconsumedEvents() = runTest {
        val event1 = TaskCompletionEvent("Task1", true, "Success 1")
        val event2 = TaskCompletionEvent("Task2", false, "Failure 2")

        eventStore.saveEvent(event1)
        eventStore.saveEvent(event2)

        val unconsumed = eventStore.getUnconsumedEvents()
        assertEquals(2, unconsumed.size)
        assertEquals("Task1", unconsumed[0].event.taskName)
        assertEquals("Task2", unconsumed[1].event.taskName)
    }

    @Test
    fun testMarkEventConsumed() = runTest {
        val event = TaskCompletionEvent("Task1", true, "Success")
        val id = eventStore.saveEvent(event)

        assertEquals(1, eventStore.getUnconsumedEvents().size)
        
        eventStore.markEventConsumed(id)
        
        assertEquals(0, eventStore.getUnconsumedEvents().size)
        assertEquals(1, eventStore.getEventCount())
    }

    @Test
    fun testClearOldEvents() = runTest {
        val event = TaskCompletionEvent("Task1", true, "Success")
        eventStore.saveEvent(event)
        
        val deleted = eventStore.clearOldEvents(86400000L) // 24 hours
        assertEquals(0, deleted)
        assertEquals(1, eventStore.getEventCount())
    }

    @Test
    fun testClearAllEvents() = runTest {
        eventStore.saveEvent(TaskCompletionEvent("T1", true, ""))
        eventStore.saveEvent(TaskCompletionEvent("T2", true, ""))
        assertEquals(2, eventStore.getEventCount())
        
        eventStore.clearAll()
        assertEquals(0, eventStore.getEventCount())
    }

    @Test
    fun testExecutionHistorySaveAndGet() = runTest {
        val record = ExecutionRecord(
            id = "rec-1",
            chainId = "chain-1",
            status = ExecutionStatus.SUCCESS,
            startedAtMs = 1000L,
            endedAtMs = 2000L,
            durationMs = 1000L,
            totalSteps = 1,
            completedSteps = 1,
            failedStep = null,
            errorMessage = null,
            retryCount = 0,
            platform = "android",
            workerClassNames = listOf("Worker1")
        )

        historyStore.save(record)
        
        val records = historyStore.getRecords(10)
        assertEquals(1, records.size)
        assertEquals("rec-1", records[0].id)
    }

    @Test
    fun testExecutionHistoryClear() = runTest {
        historyStore.save(ExecutionRecord(
            id = "rec-1", chainId = "c1", status = ExecutionStatus.SUCCESS, 
            startedAtMs = 1L, endedAtMs = 2L, durationMs = 1L, totalSteps = 1, 
            completedSteps = 1, failedStep = null, errorMessage = null, retryCount = 0, 
            platform = "android", workerClassNames = listOf("W1")
        ))
        
        assertEquals(1, historyStore.getRecords(10).size)
        historyStore.clear()
        assertEquals(0, historyStore.getRecords(10).size)
    }
}
