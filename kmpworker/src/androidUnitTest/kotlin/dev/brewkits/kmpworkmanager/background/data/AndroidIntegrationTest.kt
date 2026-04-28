package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.*
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import dev.brewkits.kmpworkmanager.workers.config.FileCompressionConfig
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidIntegrationTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun testEnqueueOneTimeWork() = runTest {
        val result = scheduler.enqueue(
            id = "test-one-time",
            trigger = TaskTrigger.OneTime(),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        assertEquals(ScheduleResult.ACCEPTED, result)
    }

    @Test
    fun testEnqueuePeriodicWork() = runTest {
        val result = scheduler.enqueue(
            id = "test-periodic",
            trigger = TaskTrigger.Periodic(intervalMs = 15 * 60 * 1000L),
            workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
        )
        assertEquals(ScheduleResult.ACCEPTED, result)
    }

    @Test
    fun testFileCompressionWorker() = runTest {
        val testDir = File(context.cacheDir, "test_compress")
        testDir.mkdirs()
        File(testDir, "file1.txt").writeText("Content 1")
        File(testDir, "file2.txt").writeText("Content 2")
        
        val outputZip = File(context.cacheDir, "output.zip")
        if (outputZip.exists()) outputZip.delete()

        val worker = FileCompressionWorker()
        val config = FileCompressionConfig(
            inputPath = testDir.absolutePath,
            outputPath = outputZip.absolutePath,
            deleteOriginal = true
        )
        
        val input = dev.brewkits.kmpworkmanager.KmpWorkManagerRuntime.json.encodeToString(
            FileCompressionConfig.serializer(), config
        )

        val result = worker.doWork(input, WorkerEnvironment(null, { false }))

        assertTrue(result is WorkerResult.Success)
        assertTrue(outputZip.exists())
        assertFalse(testDir.exists())
        
        outputZip.delete()
    }

    @Test
    fun testAndroidDiagnostics() = runTest {
        val diagnostics = AndroidWorkerDiagnostics(context)
        val status = diagnostics.getSchedulerStatus()
        assertTrue(status.isReady)
        assertEquals("android", status.platform)

        val health = diagnostics.getSystemHealth()
        assertNotNull(health)
    }

    @Test
    fun testAlarmStorePersistence() {
        val metadata = AlarmStore.AlarmMetadata(
            id = "alarm-1",
            atEpochMillis = System.currentTimeMillis() + 1000,
            workerClassName = "TestWorker",
            inputJson = "{}"
        )
        AlarmStore.save(context, metadata)
        
        val alarms = AlarmStore.getFutureAlarms(context)
        assertTrue(alarms.any { it.id == "alarm-1" })
        
        AlarmStore.remove(context, "alarm-1")
    }

    @Test
    fun testNativeTaskSchedulerCancel() {
        scheduler.cancel("non-existent")
        scheduler.cancelAll()
    }
}
