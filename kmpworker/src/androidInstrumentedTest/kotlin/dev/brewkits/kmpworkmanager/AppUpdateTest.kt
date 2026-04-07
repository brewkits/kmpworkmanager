package dev.brewkits.kmpworkmanager

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brewkits.kmpworkmanager.background.data.AlarmBootReceiver
import dev.brewkits.kmpworkmanager.background.data.AlarmStore
import dev.brewkits.kmpworkmanager.background.data.AlarmReceiver
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {

    private lateinit var context: Context

    // Mock implementation of abstract AlarmBootReceiver
    private class TestAlarmBootReceiver : AlarmBootReceiver() {
        override fun getAlarmReceiverClass(): Class<out AlarmReceiver> {
            return AlarmReceiver::class.java
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun test_alarm_reschedule_on_package_replaced() {
        // 1. Store a mock alarm
        val id = "update-test-alarm"
        val metadata = AlarmStore.AlarmMetadata(
            id = id,
            atEpochMillis = System.currentTimeMillis() + 100000,
            workerClassName = "TestWorker",
            inputJson = "{}"
        )
        AlarmStore.save(context, metadata)

        // 2. Simulate MY_PACKAGE_REPLACED broadcast
        val receiver = TestAlarmBootReceiver()
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        
        // This should trigger the reschedule logic
        receiver.onReceive(context, intent)
        
        // 3. Verify alarm still exists in store (getFutureAlarms prunes past ones but keeps future ones)
        val alarms = AlarmStore.getFutureAlarms(context)
        assertTrue(alarms.any { it.id == id }, "Alarm should remain in store after update broadcast")
    }
}
