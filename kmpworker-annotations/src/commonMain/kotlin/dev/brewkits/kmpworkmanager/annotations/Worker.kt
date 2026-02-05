package dev.brewkits.kmpworkmanager.annotations

/**
 * Annotation to mark worker classes for automatic factory generation
 * v2.2.2+ Experimental feature (KSP-based code generation)
 *
 * **Usage:**
 * ```kotlin
 * @Worker("SyncWorker")
 * class SyncWorker : AndroidWorker {
 *     override suspend fun doWork(input: String): Boolean {
 *         // Implementation
 *         return true
 *     }
 * }
 *
 * @Worker("UploadWorker")
 * class UploadWorker : AndroidWorker {
 *     override suspend fun doWork(input: String): Boolean {
 *         // Implementation
 *         return true
 *     }
 * }
 * ```
 *
 * **Generated code (automatic):**
 * ```kotlin
 * // WorkerFactoryGenerated.kt
 * class WorkerFactoryGenerated : AndroidWorkerFactory {
 *     override fun createWorker(workerClassName: String): AndroidWorker? {
 *         return when (workerClassName) {
 *             "SyncWorker" -> SyncWorker()
 *             "UploadWorker" -> UploadWorker()
 *             else -> null
 *         }
 *     }
 * }
 * ```
 *
 * **Benefits:**
 * - No manual factory boilerplate
 * - Type-safe worker creation
 * - Compile-time validation
 * - Auto-discovery of workers
 *
 * **Requirements:**
 * - KSP plugin enabled in build.gradle.kts
 * - kmpworker-ksp dependency added
 *
 * @param name Worker name (used in enqueue calls). Defaults to class simple name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Worker(val name: String = "")
