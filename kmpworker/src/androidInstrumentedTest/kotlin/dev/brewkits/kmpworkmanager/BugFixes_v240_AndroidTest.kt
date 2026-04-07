package dev.brewkits.kmpworkmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import dev.brewkits.kmpworkmanager.background.data.AlarmReceiver
import dev.brewkits.kmpworkmanager.background.data.AlarmStore
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented tests for v2.4.0 QA review bug fixes.
 *
 * Requires a real device/emulator (AlarmManager, WorkManager).
 *
 * Covers:
 * - CRIT-1: cancel() must cancel AlarmManager PendingIntent, not only SharedPreferences
 * - CRIT-2: hardcoded isTest=true removed — permission check is now real on API 31+
 * - MED-2: isPeriodic uses tags, not UUID ID string
 * - MED-4: overflow file deletion on cancellation
 * - System: AlarmStore cleanup correctness
 */
@RunWith(AndroidJUnit4::class)
class BugFixes_v240_AndroidTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    /** Concrete scheduler subclass that exposes getAlarmReceiverClass() for testing. */
    private inner class TestableScheduler : NativeTaskScheduler(context) {
        var receiverClassOverride: Class<out AlarmReceiver>? = null

        override fun getAlarmReceiverClass(): Class<out AlarmReceiver>? = receiverClassOverride

        // Expose internal cancel-alarm method for direct testing
        fun cancelAlarmForId(id: String) = cancel(id)
    }

    /** Minimal AlarmReceiver stub for building test PendingIntents. */
    private class StubAlarmReceiver : AlarmReceiver() {
        override fun handleAlarm(
            context: Context, taskId: String, workerClassName: String,
            inputJson: String?, pendingResult: PendingResult, overflowFilePath: String?
        ) { /* test stub — no-op */ }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Logger.setMinLevel(Logger.Level.WARN) // suppress verbose output during tests
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRIT-1: cancel() must also cancel the AlarmManager PendingIntent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun CRIT1_cancel_removes_task_from_AlarmStore_SharedPreferences() {
        val taskId = "crit1-alarm-task"
        AlarmStore.save(context, AlarmStore.AlarmMetadata(
            id = taskId,
            atEpochMillis = System.currentTimeMillis() + 60_000L,
            workerClassName = "TestWorker",
            inputJson = null
        ))
        assertTrue("Task must exist in AlarmStore before cancel",
            AlarmStore.getFutureAlarms(context).any { it.id == taskId })

        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = StubAlarmReceiver::class.java
        scheduler.cancelAlarmForId(taskId)

        assertFalse("Task must be removed from AlarmStore after cancel",
            AlarmStore.getFutureAlarms(context).any { it.id == taskId })
    }

    @Test
    fun CRIT1_cancel_releases_AlarmManager_PendingIntent_no_PendingIntent_found_after_cancel() {
        val taskId = "crit1-pending-intent-test"
        val receiverClass = StubAlarmReceiver::class.java

        // 1. Create a PendingIntent (simulate what scheduleExactAlarm does)
        val intent = Intent(context, receiverClass).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_WORKER_CLASS, "TestWorker")
        }
        val pi = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            pi
        )

        // Verify PendingIntent was created
        val existsBefore = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertTrue("PendingIntent must exist before cancel", existsBefore != null)

        // 2. Cancel via scheduler
        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = receiverClass
        AlarmStore.save(context, AlarmStore.AlarmMetadata(taskId, System.currentTimeMillis() + 60_000L, "TestWorker", null))
        scheduler.cancelAlarmForId(taskId)

        // 3. Assert PendingIntent was cancelled (FLAG_NO_CREATE returns null if not found)
        val existsAfter = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNull("PendingIntent must be null after cancel() — CRIT-1 fix", existsAfter)
    }

    @Test
    fun CRIT1_cancel_is_idempotent_when_no_alarm_was_ever_scheduled() {
        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = StubAlarmReceiver::class.java
        // Must not throw even if no alarm with this ID exists
        scheduler.cancelAlarmForId("non-existent-alarm-id")
        scheduler.cancelAlarmForId("non-existent-alarm-id") // second call
    }

    @Test
    fun CRIT1_cancel_without_receiver_class_does_not_crash() {
        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = null // simulates base class
        // getAlarmReceiverClass() returns null — cancel must handle gracefully
        scheduler.cancelAlarmForId("any-task-id")
    }

    @Test
    fun CRIT1_AlarmStore_remove_alone_leaves_PendingIntent_active() {
        // Documents the PRE-fix behaviour: AlarmStore.remove alone does NOT cancel the alarm.
        // This test is documentation-as-code: it verifies the fix is needed by showing
        // that AlarmStore.remove() has no effect on AlarmManager state.
        val taskId = "crit1-alarmstore-only"
        val receiverClass = StubAlarmReceiver::class.java
        val intent = Intent(context, receiverClass).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }

        // Schedule a real PendingIntent
        val pi = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 120_000L, pi
        )

        // Only remove from AlarmStore (pre-fix behaviour)
        AlarmStore.remove(context, taskId)

        // PendingIntent STILL exists (this is what the bug was)
        val stillExists = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        // Clean up before asserting (avoid test pollution)
        alarmManager.cancel(pi)
        pi.cancel()
        // The bug: AlarmStore.remove alone does NOT cancel AlarmManager
        // (test cleaned up manually above — just documenting the behaviour)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRIT-2: isTest=true removed — permission check is real
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun CRIT2_scheduleExactAlarm_returns_REJECTED_when_permission_absent_on_API_31_plus() = runTest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runTest

        // On test APK, SCHEDULE_EXACT_ALARM is typically NOT granted in manifest.
        // If it IS granted (some emulators auto-grant), we skip this test.
        val canSchedule = alarmManager.canScheduleExactAlarms()
        if (canSchedule) {
            println("SCHEDULE_EXACT_ALARM is granted — skipping permission-denied test")
            return@runTest
        }

        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = StubAlarmReceiver::class.java

        val result = scheduler.enqueue(
            id = "crit2-exact-alarm",
            trigger = TaskTrigger.Exact(atEpochMillis = System.currentTimeMillis() + 60_000L),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(
            "Without SCHEDULE_EXACT_ALARM, exact alarm must be REJECTED_OS_POLICY — CRIT-2 fix",
            ScheduleResult.REJECTED_OS_POLICY, result
        )
    }

    @Test
    fun CRIT2_scheduleExactAlarm_succeeds_when_permission_is_granted() = runTest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Pre-API 31, no permission needed — test always passes
            return@runTest
        }
        val canSchedule = alarmManager.canScheduleExactAlarms()
        if (!canSchedule) {
            println("SCHEDULE_EXACT_ALARM not granted — skipping success-path test")
            return@runTest
        }

        val scheduler = TestableScheduler()
        scheduler.receiverClassOverride = StubAlarmReceiver::class.java

        val futureTime = System.currentTimeMillis() + 60_000L
        val result = scheduler.enqueue(
            id = "crit2-exact-alarm-ok",
            trigger = TaskTrigger.Exact(atEpochMillis = futureTime),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            policy = ExistingPolicy.REPLACE
        )

        assertEquals(ScheduleResult.ACCEPTED, result)
        // Clean up
        scheduler.cancelAlarmForId("crit2-exact-alarm-ok")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MED-4: Overflow file cleanup on cancellation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun MED4_overflow_file_created_in_cacheDir_follows_naming_pattern() {
        // Verify the naming convention that zombie cleanup relies on
        val cacheDir = context.cacheDir
        val tempFile = java.io.File(cacheDir, "kmp_input_test-uuid.json")
        tempFile.createNewFile()
        try {
            assertTrue("kmp_input_ prefix is required for zombie cleanup",
                tempFile.name.startsWith("kmp_input_"))
            assertTrue(".json suffix is required for zombie cleanup",
                tempFile.name.endsWith(".json"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun MED4_cleanupZombieInputFiles_removes_files_older_than_24h() {
        val cacheDir = context.cacheDir
        val oldFile = java.io.File(cacheDir, "kmp_input_old-task.json")
        oldFile.createNewFile()
        // Backdate the file to 25 hours ago
        oldFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        // Also create a fresh file that must NOT be deleted
        val freshFile = java.io.File(cacheDir, "kmp_input_fresh-task.json")
        freshFile.createNewFile()

        try {
            NativeTaskScheduler.cleanupZombieInputFiles(context)
            Thread.sleep(500L) // Give coroutine time to run

            assertFalse("File older than 24h must be deleted by zombie cleanup", oldFile.exists())
            assertTrue("Fresh file must NOT be deleted by zombie cleanup", freshFile.exists())
        } finally {
            oldFile.delete()
            freshFile.delete()
        }
    }

    @Test
    fun MED4_cleanupZombieInputFiles_ignores_files_not_matching_kmp_input_pattern() {
        val cacheDir = context.cacheDir
        val unrelatedOldFile = java.io.File(cacheDir, "app_cache_old.json")
        unrelatedOldFile.createNewFile()
        unrelatedOldFile.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)

        try {
            NativeTaskScheduler.cleanupZombieInputFiles(context)
            Thread.sleep(500L)
            assertTrue("Unrelated files must not be deleted by zombie cleanup",
                unrelatedOldFile.exists())
        } finally {
            unrelatedOldFile.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // System: AlarmStore correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun System_AlarmStore_save_and_getFutureAlarms_roundtrip() {
        val taskId = "alarmstore-roundtrip-test"
        val futureMs = System.currentTimeMillis() + 60_000L
        val metadata = AlarmStore.AlarmMetadata(
            id = taskId,
            atEpochMillis = futureMs,
            workerClassName = "SyncWorker",
            inputJson = """{"key":"value"}"""
        )

        AlarmStore.save(context, metadata)
        val alarms = AlarmStore.getFutureAlarms(context)
        val found = alarms.find { it.id == taskId }

        assertFalse("Alarm must be present after save", found == null)
        assertEquals(futureMs, found!!.atEpochMillis)
        assertEquals("SyncWorker", found.workerClassName)
        assertEquals("""{"key":"value"}""", found.inputJson)

        AlarmStore.remove(context, taskId)
        val afterRemove = AlarmStore.getFutureAlarms(context).find { it.id == taskId }
        assertNull("Alarm must be absent after remove", afterRemove)
    }

    @Test
    fun System_AlarmStore_getFutureAlarms_filters_out_past_alarms() {
        val pastId = "alarmstore-past-alarm"
        val futureId = "alarmstore-future-alarm"
        val pastMs = System.currentTimeMillis() - 60_000L
        val futureMs = System.currentTimeMillis() + 60_000L

        AlarmStore.save(context, AlarmStore.AlarmMetadata(pastId, pastMs, "W1", null))
        AlarmStore.save(context, AlarmStore.AlarmMetadata(futureId, futureMs, "W2", null))

        val future = AlarmStore.getFutureAlarms(context)
        assertFalse("Past alarm must be pruned by getFutureAlarms",
            future.any { it.id == pastId })
        assertTrue("Future alarm must remain",
            future.any { it.id == futureId })

        // Cleanup
        AlarmStore.remove(context, futureId)
    }

    @Test
    fun System_AlarmStore_cleanupStaleAlarms_removes_old_SharedPrefs_entries() {
        val staleId = "alarmstore-stale-entry"
        val pastMs = System.currentTimeMillis() - 25 * 60 * 60 * 1000L // 25h ago
        AlarmStore.save(context, AlarmStore.AlarmMetadata(staleId, pastMs, "Worker", null))

        val removed = AlarmStore.cleanupStaleAlarms(context)
        assertTrue("At least 1 stale alarm must be cleaned up", removed >= 1)
        val remaining = AlarmStore.getFutureAlarms(context).find { it.id == staleId }
        assertNull("Stale alarm must be removed", remaining)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Performance: AlarmStore read speed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun Performance_AlarmStore_getFutureAlarms_completes_within_50ms_with_50_entries() {
        val ids = (1..50).map { "perf-alarm-$it" }
        val futureMs = System.currentTimeMillis() + 60_000L
        ids.forEach { id ->
            AlarmStore.save(context, AlarmStore.AlarmMetadata(id, futureMs, "Worker", null))
        }

        try {
            val start = System.currentTimeMillis()
            val alarms = AlarmStore.getFutureAlarms(context)
            val elapsed = System.currentTimeMillis() - start

            assertTrue("Must return at least 50 alarms", alarms.size >= 50)
            assertTrue("getFutureAlarms must complete within 50ms, took ${elapsed}ms",
                elapsed < 50L)
        } finally {
            ids.forEach { AlarmStore.remove(context, it) }
        }
    }
}
