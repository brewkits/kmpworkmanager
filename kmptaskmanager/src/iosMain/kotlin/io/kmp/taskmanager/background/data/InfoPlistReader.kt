package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.utils.LogTags
import io.kmp.taskmanager.utils.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSArray
import platform.Foundation.NSBundle

/**
 * Utility to read BGTaskSchedulerPermittedIdentifiers from Info.plist
 *
 * v4.0.0+: Dynamically validates task IDs against Info.plist configuration
 *
 * This eliminates the need to manually synchronize task IDs between:
 * - Info.plist BGTaskSchedulerPermittedIdentifiers
 * - Kotlin code permitted task IDs
 * - Swift BGTaskScheduler registration
 *
 * Example Info.plist:
 * ```xml
 * <key>BGTaskSchedulerPermittedIdentifiers</key>
 * <array>
 *     <string>kmp_chain_executor_task</string>
 *     <string>my-sync-task</string>
 *     <string>my-upload-task</string>
 * </array>
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
object InfoPlistReader {

    private const val BG_TASK_SCHEDULER_KEY = "BGTaskSchedulerPermittedIdentifiers"

    /**
     * Reads permitted task identifiers from Info.plist
     *
     * @return Set of permitted task IDs, or empty set if key not found
     */
    fun readPermittedTaskIds(): Set<String> {
        val bundle = NSBundle.mainBundle
        val permittedIds = bundle.objectForInfoDictionaryKey(BG_TASK_SCHEDULER_KEY) as? NSArray

        if (permittedIds == null) {
            Logger.w(LogTags.SCHEDULER, """
                BGTaskSchedulerPermittedIdentifiers not found in Info.plist

                To enable background tasks on iOS, add to Info.plist:
                <key>BGTaskSchedulerPermittedIdentifiers</key>
                <array>
                    <string>kmp_chain_executor_task</string>
                    <string>your-task-id</string>
                </array>
            """.trimIndent())
            return emptySet()
        }

        val result = mutableSetOf<String>()
        for (i in 0 until permittedIds.count.toInt()) {
            val taskId = permittedIds.objectAtIndex(i.toULong()) as? String
            if (taskId != null) {
                result.add(taskId)
            }
        }

        Logger.i(LogTags.SCHEDULER, "Read ${result.size} permitted task IDs from Info.plist: ${result.joinToString()}")
        return result
    }

    /**
     * Validates that a task ID is present in Info.plist
     *
     * @param taskId Task identifier to validate
     * @return true if task ID is permitted, false otherwise
     */
    fun isTaskIdPermitted(taskId: String): Boolean {
        return readPermittedTaskIds().contains(taskId)
    }
}
