package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.TaskIds
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.utils.LogTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TaskIdsTest {

    @Test
    fun TaskIds_should_have_correct_constant_values() {
        assertEquals("heavy-task-1", TaskIds.HEAVY_TASK_1)
        assertEquals("one-time-upload", TaskIds.ONE_TIME_UPLOAD)
        assertEquals("periodic-sync-task", TaskIds.PERIODIC_SYNC_TASK)
        assertEquals("exact-reminder", TaskIds.EXACT_REMINDER)
    }

    @Test
    fun TaskIds_constants_should_be_unique() {
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
    fun TaskIds_should_follow_kebab_case_naming_convention() {
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

class LoggerEnumTest {

    @Test
    fun Logger_Level_enum_should_have_all_levels() {
        val levels = Logger.Level.entries.toList()

        assertEquals(5, levels.size)
        kotlin.test.assertTrue(levels.contains(Logger.Level.VERBOSE))
        kotlin.test.assertTrue(levels.contains(Logger.Level.DEBUG_LEVEL))
        kotlin.test.assertTrue(levels.contains(Logger.Level.INFO))
        kotlin.test.assertTrue(levels.contains(Logger.Level.WARN))
        kotlin.test.assertTrue(levels.contains(Logger.Level.ERROR))
    }

    @Test
    fun Logger_Level_values_should_be_distinct() {
        val debug = Logger.Level.DEBUG_LEVEL
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
    fun Logger_Level_should_have_correct_names() {
        assertEquals("DEBUG_LEVEL", Logger.Level.DEBUG_LEVEL.name)
        assertEquals("INFO", Logger.Level.INFO.name)
        assertEquals("WARN", Logger.Level.WARN.name)
        assertEquals("ERROR", Logger.Level.ERROR.name)
    }

    @Test
    fun Logger_Level_should_have_correct_ordinals() {
        assertEquals(0, Logger.Level.VERBOSE.ordinal)
        assertEquals(1, Logger.Level.DEBUG_LEVEL.ordinal)
        assertEquals(2, Logger.Level.INFO.ordinal)
        assertEquals(3, Logger.Level.WARN.ordinal)
        assertEquals(4, Logger.Level.ERROR.ordinal)
    }
}

class LogTagsTest {

    @Test
    fun LogTags_should_have_correct_constant_values() {
        assertEquals("TaskScheduler", LogTags.SCHEDULER)
        assertEquals("TaskWorker", LogTags.WORKER)
        assertEquals("TaskChain", LogTags.CHAIN)
        assertEquals("ExactAlarm", LogTags.ALARM)
        assertEquals("Permission", LogTags.PERMISSION)
        assertEquals("PushNotification", LogTags.PUSH)
        assertEquals("Debug", LogTags.TAG_DEBUG)
        assertEquals("Error", LogTags.ERROR)
    }

    @Test
    fun LogTags_should_be_unique() {
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.TAG_DEBUG,
            LogTags.ERROR
        )

        // Verify all tags are unique
        assertEquals(8, tags.toSet().size)
    }

    @Test
    fun LogTags_should_follow_consistent_naming_pattern() {
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.TAG_DEBUG,
            LogTags.ERROR
        )

        tags.forEach { tag ->
            // Verify PascalCase: starts with uppercase
            kotlin.test.assertTrue(tag.first().isUpperCase(), "Tag '$tag' does not start with uppercase letter")
        }
    }

    @Test
    fun LogTags_should_be_descriptive_and_not_abbreviated() {
        // Verify tags are not just single letters or overly abbreviated
        val tags = listOf(
            LogTags.SCHEDULER,
            LogTags.WORKER,
            LogTags.CHAIN,
            LogTags.ALARM,
            LogTags.PERMISSION,
            LogTags.PUSH,
            LogTags.TAG_DEBUG,
            LogTags.ERROR
        )

        tags.forEach { tag ->
            kotlin.test.assertTrue(tag.length >= 4, "Tag '$tag' is too short (less than 4 characters)")
        }
    }
}
