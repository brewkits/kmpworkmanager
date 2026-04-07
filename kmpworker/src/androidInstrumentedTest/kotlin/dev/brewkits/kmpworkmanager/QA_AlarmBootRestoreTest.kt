package dev.brewkits.kmpworkmanager

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brewkits.kmpworkmanager.background.data.AlarmBootReceiver
import dev.brewkits.kmpworkmanager.background.data.AlarmReceiver
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull

/**
 * QA Test: Android Exact Alarm Boot Restore Bug.
 */
@RunWith(AndroidJUnit4::class)
class QA_AlarmBootRestoreTest {

    private class TestAlarmBootReceiver : AlarmBootReceiver() {
        override fun getAlarmReceiverClass(): Class<out AlarmReceiver> {
            return AlarmReceiver::class.java
        }
    }

    @Test
    fun test_alarm_boot_receiver_exists_and_handles_intent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Verify we can instantiate a concrete implementation
        val receiver = TestAlarmBootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        
        // Verify it doesn't crash when receiving the intent
        try {
            receiver.onReceive(context, intent)
        } catch (e: Exception) {
            throw AssertionError("BUG EXPOSED: AlarmBootReceiver implementation crashed on ACTION_BOOT_COMPLETED", e)
        }
    }
}
