package dev.brewkits.kmpworkmanager.annotations

/**
 * Marks a worker class for automatic factory generation via KSP.
 *
 * **Usage:**
 * ```kotlin
 * // Android worker — no bgTaskId needed
 * @Worker("SyncWorker")
 * class SyncWorker : AndroidWorker {
 *     override suspend fun doWork(input: String?): WorkerResult { ... }
 * }
 *
 * // iOS worker — supply bgTaskId for automatic Info.plist validation
 * @Worker("UploadWorker", bgTaskId = "com.example.upload-task")
 * class UploadWorker : IosWorker {
 *     override suspend fun doWork(input: String?): WorkerResult { ... }
 * }
 * ```
 *
 * **Generated code (automatic):**
 * ```kotlin
 * // AndroidWorkerFactoryGenerated.kt
 * class AndroidWorkerFactoryGenerated : AndroidWorkerFactory {
 *     val providers: MutableMap<String, () -> AndroidWorker?> = mutableMapOf(
 *         "SyncWorker" to { SyncWorker() }
 *     )
 *     override fun createWorker(workerClassName: String): AndroidWorker? =
 *         providers[workerClassName]?.invoke()
 * }
 *
 * // IosWorkerFactoryGenerated.kt  (also implements BgTaskIdProvider)
 * class IosWorkerFactoryGenerated : IosWorkerFactory, BgTaskIdProvider {
 *     override val requiredBgTaskIds = setOf("com.example.upload-task")
 *     val providers: MutableMap<String, () -> IosWorker?> = mutableMapOf(
 *         "UploadWorker" to { UploadWorker() }
 *     )
 *     override fun createWorker(workerClassName: String): IosWorker? =
 *         providers[workerClassName]?.invoke()
 * }
 * ```
 *
 * When `IosWorkerFactoryGenerated` is passed to `kmpWorkerModule()`, the library
 * automatically validates that all `bgTaskId` values are declared in
 * `Info.plist → BGTaskSchedulerPermittedIdentifiers` — failing fast at app startup
 * instead of silently misbehaving at runtime.
 *
 * **Benefits:**
 * - No manual factory boilerplate
 * - Automatic Info.plist BGTask ID validation (iOS)
 * - DI-framework-agnostic (override individual `providers` entries)
 * - Compile-time worker discovery
 *
 * **Requirements:**
 * - KSP plugin enabled in build.gradle.kts
 * - `kmpworker-ksp` dependency added
 *
 * @param name Worker name used in `TaskRequest(workerClassName = ...)` calls.
 *   Defaults to the class simple name.
 * @param bgTaskId iOS `BGTaskSchedulerPermittedIdentifiers` entry required by this worker.
 *   Leave empty (`""`) for Android-only workers. When non-empty, `kmpWorkerModule()`
 *   validates the ID against `Info.plist` at startup.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Worker(
    val name: String = "",
    val bgTaskId: String = ""
)
