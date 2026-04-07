package dev.brewkits.kmpworkmanager.persistence

import dev.brewkits.kmpworkmanager.background.domain.ExecutionRecord

/**
 * Persistent store for [ExecutionRecord]s.
 *
 * Records are appended after each chain execution and automatically pruned when
 * the store exceeds [MAX_RECORDS] entries. The store is append-only (no update/delete
 * per record) to keep I/O cheap in a background context.
 *
 * Platform implementations:
 * - iOS: [IosExecutionHistoryStore] — JSONL file in Application Support
 * - Android: [AndroidExecutionHistoryStore] — JSONL file in filesDir
 */
interface ExecutionHistoryStore {

    /**
     * Append a single [ExecutionRecord] to the store.
     * Triggers auto-pruning when the total exceeds [MAX_RECORDS].
     */
    suspend fun save(record: ExecutionRecord)

    /**
     * Return the most recent records, newest first.
     *
     * @param limit Maximum number of records to return. Defaults to 100.
     */
    suspend fun getRecords(limit: Int = 100): List<ExecutionRecord>

    /**
     * Delete all stored records.
     * Call after a successful server upload to free disk space.
     */
    suspend fun clear()

    companion object {
        /** Maximum records kept on disk. Older records are pruned automatically. */
        const val MAX_RECORDS = 500
    }
}
