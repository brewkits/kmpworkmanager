package io.kmp.taskmanager.background.data

import io.kmp.taskmanager.background.domain.TaskRequest
import io.kmp.taskmanager.utils.Logger
import io.kmp.taskmanager.utils.LogTags
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * One-time migration from NSUserDefaults to file storage.
 * Runs automatically on first launch of v3.0.0.
 *
 * Migration Strategy:
 * 1. Check if migration has already been performed
 * 2. Read all data from NSUserDefaults (queue, chains, metadata)
 * 3. Write to file storage using IosFileStorage
 * 4. Verify integrity
 * 5. Mark migration as complete
 * 6. Keep NSUserDefaults data for rollback until explicitly cleared
 */
internal class StorageMigration(
    private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val fileStorage: IosFileStorage = IosFileStorage()
) {

    companion object {
        private const val MIGRATION_FLAG_KEY = "kmp_storage_migration_v3_completed"
        private const val CHAIN_DEFINITION_PREFIX = "kmp_chain_definition_"
        private const val TASK_META_PREFIX = "kmp_task_meta_"
        private const val PERIODIC_META_PREFIX = "kmp_periodic_meta_"
        private const val CHAIN_QUEUE_KEY = "kmp_chain_queue"
    }

    /**
     * Check if migration has already been performed
     */
    fun isMigrated(): Boolean {
        return userDefaults.boolForKey(MIGRATION_FLAG_KEY)
    }

    /**
     * Perform migration from NSUserDefaults to file storage
     * @return MigrationResult with success status and details
     */
    suspend fun migrate(): MigrationResult {
        if (isMigrated()) {
            Logger.i(LogTags.SCHEDULER, "Migration already completed, skipping")
            return MigrationResult(
                success = true,
                message = "Migration already completed",
                chainsMigrated = 0,
                metadataMigrated = 0
            )
        }

        Logger.i(LogTags.SCHEDULER, "Starting storage migration from NSUserDefaults to file storage")

        try {
            var chainCount = 0
            var metadataCount = 0

            // 1. Migrate chain queue
            val queue = userDefaults.stringArrayForKey(CHAIN_QUEUE_KEY) as? List<String>
            if (queue != null && queue.isNotEmpty()) {
                Logger.d(LogTags.SCHEDULER, "Migrating chain queue with ${queue.size} items")

                // Enqueue each chain ID
                queue.forEach { chainId ->
                    try {
                        fileStorage.enqueueChain(chainId)
                        chainCount++
                    } catch (e: Exception) {
                        Logger.e(LogTags.SCHEDULER, "Failed to enqueue chain $chainId during migration", e)
                    }
                }
            }

            // 2. Migrate chain definitions
            val allKeys = userDefaults.dictionaryRepresentation().keys as List<*>
            allKeys.forEach { key ->
                val keyStr = key as? String ?: return@forEach

                when {
                    // Chain definitions
                    keyStr.startsWith(CHAIN_DEFINITION_PREFIX) -> {
                        val chainId = keyStr.removePrefix(CHAIN_DEFINITION_PREFIX)
                        val jsonString = userDefaults.stringForKey(keyStr)

                        if (jsonString != null) {
                            try {
                                val steps = Json.decodeFromString<List<List<TaskRequest>>>(jsonString)
                                fileStorage.saveChainDefinition(chainId, steps)
                                chainCount++
                                Logger.d(LogTags.SCHEDULER, "Migrated chain definition: $chainId")
                            } catch (e: Exception) {
                                Logger.e(LogTags.SCHEDULER, "Failed to migrate chain $chainId", e)
                            }
                        }
                    }

                    // Task metadata
                    keyStr.startsWith(TASK_META_PREFIX) -> {
                        val taskId = keyStr.removePrefix(TASK_META_PREFIX)
                        val metadata = userDefaults.dictionaryForKey(keyStr) as? Map<String, String>

                        if (metadata != null) {
                            try {
                                fileStorage.saveTaskMetadata(taskId, metadata, periodic = false)
                                metadataCount++
                                Logger.d(LogTags.SCHEDULER, "Migrated task metadata: $taskId")
                            } catch (e: Exception) {
                                Logger.e(LogTags.SCHEDULER, "Failed to migrate task metadata $taskId", e)
                            }
                        }
                    }

                    // Periodic metadata
                    keyStr.startsWith(PERIODIC_META_PREFIX) -> {
                        val taskId = keyStr.removePrefix(PERIODIC_META_PREFIX)
                        val metadata = userDefaults.dictionaryForKey(keyStr) as? Map<String, String>

                        if (metadata != null) {
                            try {
                                fileStorage.saveTaskMetadata(taskId, metadata, periodic = true)
                                metadataCount++
                                Logger.d(LogTags.SCHEDULER, "Migrated periodic metadata: $taskId")
                            } catch (e: Exception) {
                                Logger.e(LogTags.SCHEDULER, "Failed to migrate periodic metadata $taskId", e)
                            }
                        }
                    }
                }
            }

            // 3. Verify migration integrity
            val queueSize = fileStorage.getQueueSize()
            if (queue != null && queueSize != queue.size) {
                Logger.w(LogTags.SCHEDULER, "Queue size mismatch after migration: expected ${queue.size}, got $queueSize")
            }

            // 4. Mark migration as complete
            userDefaults.setBool(true, forKey = MIGRATION_FLAG_KEY)
            userDefaults.synchronize()

            val result = MigrationResult(
                success = true,
                message = "Migration completed successfully",
                chainsMigrated = chainCount,
                metadataMigrated = metadataCount
            )

            Logger.i(LogTags.SCHEDULER, "Migration completed: ${result.chainsMigrated} chains, ${result.metadataMigrated} metadata items")
            return result

        } catch (e: Exception) {
            Logger.e(LogTags.SCHEDULER, "Migration failed", e)
            return MigrationResult(
                success = false,
                message = "Migration failed: ${e.message}",
                chainsMigrated = 0,
                metadataMigrated = 0
            )
        }
    }

    /**
     * Clear NSUserDefaults data after successful migration (optional, for cleanup)
     * WARNING: This cannot be undone. Only call after confirming migration success.
     */
    fun clearOldStorage() {
        Logger.w(LogTags.SCHEDULER, "Clearing old NSUserDefaults storage")

        val allKeys = userDefaults.dictionaryRepresentation().keys as List<*>
        allKeys.forEach { key ->
            val keyStr = key as? String ?: return@forEach

            when {
                keyStr.startsWith(CHAIN_DEFINITION_PREFIX) ||
                keyStr.startsWith(TASK_META_PREFIX) ||
                keyStr.startsWith(PERIODIC_META_PREFIX) ||
                keyStr == CHAIN_QUEUE_KEY -> {
                    userDefaults.removeObjectForKey(keyStr)
                    Logger.d(LogTags.SCHEDULER, "Removed old storage key: $keyStr")
                }
            }
        }

        userDefaults.synchronize()
        Logger.i(LogTags.SCHEDULER, "Old storage cleared (NSUserDefaults data removed)")
    }

    /**
     * Rollback to NSUserDefaults (in case of issues with file storage)
     * This resets the migration flag, allowing the system to use NSUserDefaults again.
     */
    fun rollback() {
        Logger.w(LogTags.SCHEDULER, "Rolling back to NSUserDefaults storage")
        userDefaults.setBool(false, forKey = MIGRATION_FLAG_KEY)
        userDefaults.synchronize()
        Logger.i(LogTags.SCHEDULER, "Rolled back to NSUserDefaults. File storage will not be used.")
    }
}

/**
 * Result of migration operation
 */
data class MigrationResult(
    val success: Boolean,
    val message: String,
    val chainsMigrated: Int,
    val metadataMigrated: Int
)
