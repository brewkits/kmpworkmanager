package io.kmp.taskmanager

import io.kmp.taskmanager.background.data.TaskIds
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TaskIdsTest {

    @Test
    fun `TaskIds should have correct constant values`() {
        assertEquals("heavy-task-1", TaskIds.HEAVY_TASK_1)
        assertEquals("one-time-upload", TaskIds.ONE_TIME_UPLOAD)
        assertEquals("periodic-sync-task", TaskIds.PERIODIC_SYNC_TASK)
        assertEquals("exact-reminder", TaskIds.EXACT_REMINDER)
    }

    @Test
    fun `TaskIds constants should be unique`() {
        val ids = listOf(
            TaskIds.HEAVY_TASK_1,
            TaskIds.ONE_TIME_UPLOAD,
            TaskIds.PERIODIC_SYNC_TASK,
            TaskIds.EXACT_REMINDER
        )

        // Verify all IDs are unique
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun `TaskIds should follow kebab-case naming convention`() {
        val ids = listOf(
            TaskIds.HEAVY_TASK_1,
            TaskIds.ONE_TIME_UPLOAD,
            TaskIds.PERIODIC_SYNC_TASK,
            TaskIds.EXACT_REMINDER
        )

        ids.forEach { id ->
            // Verify kebab-case: lowercase with hyphens
            assertEquals(id, id.lowercase())
            kotlin.test.assertTrue(id.matches(Regex("[a-z0-9-]+")), "Task ID '$id' does not follow kebab-case convention")
        }
    }
}

class LoggerTest {

    @Test
    fun `Logger Level enum should have all levels`() {
        val levels = Logger.Level.entries.toList()

        assertEquals(4, levels.size)
        kotlin.test.assertTrue(levels.contains(Logger.Level.DEBUG))
        kotlin.test.assertTrue(levels.contains(Logger.Level.INFO))
        kotlin.test.assertTrue(levels.contains(Logger.Level.WARN))
        kotlin.test.assertTrue(levels.contains(Logger.Level.ERROR))
    }

    @Test
    fun `Logger Level values should be distinct`() {
        val debug = Logger.Level.DEBUG
        val info = Logger.Level.INFO
        val warn = Logger.Level.WARN
        val error = Logger.Level.ERROR

        assertNotEquals(debug, info)
        assertNotEquals(debug, warn)
        assertNotEquals(debug, error)
        assertNotEquals(info, warn)
        assertNotEquals(info, error)
        assertNotEquals(warn, error)
    }

    @Test
    fun `Logger Level should have correct names`() {
        assertEquals("DEBUG", Logger.Level.DEBUG.name)
        assertEquals("INFO", Logger.Level.INFO.name)
        assertEquals("WARN", Logger.Level.WARN.name)
        assertEquals("ERROR", Logger.Level.ERROR.name)
    }

    @Test
    fun `Logger Level should have correct ordinals`() {
        assertEquals(0, Logger.Level.DEBUG.ordinal)
        assertEquals(1, Logger.Level.INFO.ordinal)
        assertEquals(2, Logger.Level.WARN.ordinal)
        assertEquals(3, Logger.Level.ERROR.ordinal)
    }
}

class LogTagsTest {

    @Test
    fun `LogTags should have correct constant values`() {
        assertEquals("TaskScheduler", LogTags.SCHEDULER)
        assertEquals("TaskWorker", LogTags.WORKER)
        assertEquals("TaskChain", LogTags.CHAIN)
        assertEquals("ExactAlarm", LogTags.ALARM)
        assertEquals("Permission", LogTags.PERMISSION)
        assertEquals("PushNotification", LogTags.PUSH)
        assertEquals("Debug", LogTags.DEBUG)
        assertEquals("Error", LogTags.ERROR)
    }

    @Test
    fun `LogTags should be unique`() {
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.DEBUG,
            LogTags.ERROR
        )

        // Verify all tags are unique
        assertEquals(8, tags.toSet().size)
    }

    @Test
    fun `LogTags should follow consistent naming pattern`() {
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.DEBUG,
            LogTags.ERROR
        )

        tags.forEach { tag ->
            // Verify PascalCase: starts with uppercase
            kotlin.test.assertTrue(tag.first().isUpperCase(), "Tag '$tag' does not start with uppercase letter")
        }
    }

    @Test
    fun `LogTags should be descriptive and not abbreviated`() {
        // Verify tags are not just single letters or overly abbreviated
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.DEBUG,
            LogTags.ERROR
        )

        tags.forEach { tag ->
            kotlin.test.assertTrue(tag.length >= 4, "Tag '$tag' is too short (less than 4 characters)")
        }
    }
}
