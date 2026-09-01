package dev.brewkits.kmpworkmanager.background.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the tag contract behind [BackgroundTaskScheduler.cancelByTag].
 *
 * The stakes are specific: iOS persists a standalone task's tags as one comma-separated
 * metadata string, so a tag containing a comma would be split in two on the way back out
 * and `cancelByTag` would quietly fail to match it. A cancellation API that silently fails
 * to cancel is worse than one that refuses the input, so [TaskRequest] rejects such tags at
 * construction — these tests pin that.
 */
class TaskTagValidationTest {

    @Test
    fun validTags_areAccepted() {
        val task = TaskRequest(
            workerClassName = "SyncWorker",
            tags = setOf("user-123", "background_sync", "com.example.scope", "tag with spaces", "标签")
        )
        assertEquals(5, task.tags.size)
    }

    @Test
    fun emptyTagSet_isAccepted() {
        assertTrue(TaskRequest(workerClassName = "SyncWorker").tags.isEmpty())
    }

    @Test
    fun commaInTag_isRejected() {
        // The critical case: comma is iOS's separator in META_TAGS.
        val e = assertFailsWith<IllegalArgumentException> {
            TaskRequest(workerClassName = "SyncWorker", tags = setOf("user,123"))
        }
        assertTrue(
            e.message!!.contains("comma"),
            "The failure must name the real reason so a caller can fix it. Got: ${e.message}"
        )
    }

    @Test
    fun blankTag_isRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaskRequest(workerClassName = "SyncWorker", tags = setOf(""))
        }
        assertFailsWith<IllegalArgumentException> {
            TaskRequest(workerClassName = "SyncWorker", tags = setOf("   "))
        }
    }

    @Test
    fun overlongTag_isRejected() {
        val tooLong = "x".repeat(MAX_TASK_TAG_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            TaskRequest(workerClassName = "SyncWorker", tags = setOf(tooLong))
        }
    }

    @Test
    fun tagAtExactLengthLimit_isAccepted() {
        // Boundary: the limit itself must be usable, not off-by-one rejected.
        val exact = "x".repeat(MAX_TASK_TAG_LENGTH)
        val task = TaskRequest(workerClassName = "SyncWorker", tags = setOf(exact))
        assertEquals(exact, task.tags.single())
    }

    @Test
    fun oneBadTagRejectsTheWholeRequest_notJustThatTag() {
        // Partial acceptance would leave the caller believing all tags are cancellable.
        assertFailsWith<IllegalArgumentException> {
            TaskRequest(workerClassName = "SyncWorker", tags = setOf("good-tag", "bad,tag"))
        }
    }

    @Test
    fun copyWithBadTag_isAlsoRejected() {
        // data class copy() re-runs init, so the guard cannot be bypassed by copying.
        val ok = TaskRequest(workerClassName = "SyncWorker", tags = setOf("fine"))
        assertFailsWith<IllegalArgumentException> {
            ok.copy(tags = setOf("not,fine"))
        }
    }
}
