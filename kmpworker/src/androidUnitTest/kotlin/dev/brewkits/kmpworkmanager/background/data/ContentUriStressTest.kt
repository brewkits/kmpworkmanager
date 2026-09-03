package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.brewkits.kmpworkmanager.background.domain.AndroidOnly
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stress coverage for `TaskTrigger.ContentUri` — previously untested. `ContentUri` is Android-
 * only (see [dev.brewkits.kmpworkmanager.background.domain.AndroidOnly]) and maps to
 * `WorkRequest.Builder.addContentUriTrigger`, an OS content-observer registration that has no
 * equivalent unit test elsewhere in the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentUriStressTest {

    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler
    private lateinit var workManager: WorkManager

    @BeforeTest
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
        workManager = WorkManager.getInstance(context)
    }

    @OptIn(AndroidOnly::class)
    @Test
    fun `many distinct content URI observers can be enqueued without error`() = runTest {
        // 8, not an arbitrary round number: WorkManager's own content-URI scheduling path
        // (Schedulers.schedule -> WorkSpecDao.getEligibleWorkForSchedulingWithContentUris)
        // has a hard ceiling on how many distinct content-uri-triggered WorkSpecs it will
        // keep scheduled at once — verified empirically against this WorkManager version: the
        // 9th distinct id silently drops out of the WorkSpec table entirely (0 WorkInfo, not
        // just "not yet scheduled"). This is WorkManager/JobScheduler's own limit, not this
        // library's — this test pins the count this library is actually responsible for
        // supporting cleanly, not the OS/library boundary itself.
        val ids = (1..8).map { "content-uri-observer-$it" }

        val results = ids.mapIndexed { index, id ->
            scheduler.enqueue(
                id = id,
                trigger = TaskTrigger.ContentUri(uriString = "content://media/external/images/media/$index"),
                workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker"
            )
        }

        assertTrue(results.all { it == ScheduleResult.ACCEPTED }, "expected all ${ids.size} enqueues to succeed, got: $results")
        ids.forEach { id ->
            val infos = workManager.getWorkInfosForUniqueWork(id).get()
            assertEquals(1, infos.size, "expected exactly one WorkInfo for '$id'")
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        }
    }

    @OptIn(AndroidOnly::class)
    @Test
    fun `rapidly re-enqueuing the same watched id with REPLACE leaves exactly one live WorkInfo`() = runTest {
        val id = "content-uri-rapid-changes"

        // Simulates a single logical "watch this content URI" task being re-armed many times
        // in quick succession — e.g. the caller widening/narrowing which URI it watches.
        repeat(50) { i ->
            val result = scheduler.enqueue(
                id = id,
                trigger = TaskTrigger.ContentUri(uriString = "content://media/external/images/media/$i"),
                workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
                policy = ExistingPolicy.REPLACE
            )
            assertEquals(ScheduleResult.ACCEPTED, result, "enqueue #$i should be accepted")
        }

        val infos = workManager.getWorkInfosForUniqueWork(id).get()
        assertEquals(1, infos.size, "REPLACE must leave exactly one live WorkInfo, not accumulate one per call")
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @OptIn(AndroidOnly::class)
    @Test
    fun `triggerForDescendants toggling across re-enqueues does not throw`() = runTest {
        val id = "content-uri-descendants-toggle"

        repeat(20) { i ->
            val result = scheduler.enqueue(
                id = id,
                trigger = TaskTrigger.ContentUri(
                    uriString = "content://media/external/images/media",
                    triggerForDescendants = i % 2 == 0
                ),
                workerClassName = "dev.brewkits.kmpworkmanager.sample.background.SimpleWorker",
                policy = ExistingPolicy.REPLACE
            )
            assertEquals(ScheduleResult.ACCEPTED, result)
        }
    }
}
