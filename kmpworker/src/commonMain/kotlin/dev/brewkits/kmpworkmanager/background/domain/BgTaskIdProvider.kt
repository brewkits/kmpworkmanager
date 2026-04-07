package dev.brewkits.kmpworkmanager.background.domain

/**
 * Implemented by KSP-generated [IosWorkerFactoryGenerated] to expose the set of
 * BGTask IDs that this factory's workers require.
 *
 * When `kmpWorkerModule()` receives a [WorkerFactory] that also implements this
 * interface, it automatically cross-checks the declared IDs against the app's
 * `Info.plist` `BGTaskSchedulerPermittedIdentifiers` and fails fast with a
 * descriptive error if any are missing — catching the misconfiguration at
 * startup instead of silently failing at runtime.
 *
 * You do not need to implement this interface manually. It is automatically added
 * to the KSP-generated factory when you annotate workers with
 * `@Worker(bgTaskId = "com.example.my-task")`.
 *
 * **Example (KSP-generated):**
 * ```kotlin
 * // Generated — do not edit
 * class IosWorkerFactoryGenerated : IosWorkerFactory, BgTaskIdProvider {
 *     override val requiredBgTaskIds = setOf(
 *         "com.example.sync-task",
 *         "com.example.upload-task"
 *     )
 *     override fun createWorker(workerClassName: String): IosWorker? = when (workerClassName) {
 *         "SyncWorker"   -> SyncWorker()
 *         "UploadWorker" -> UploadWorker()
 *         else           -> null
 *     }
 * }
 * ```
 *
 * **Manual usage (without KSP):**
 * ```kotlin
 * class MyIosWorkerFactory : IosWorkerFactory, BgTaskIdProvider {
 *     override val requiredBgTaskIds = setOf("com.example.sync-task")
 *     override fun createWorker(workerClassName: String): IosWorker? = ...
 * }
 * ```
 */
interface BgTaskIdProvider {
    /**
     * The set of `BGTaskSchedulerPermittedIdentifiers` entries that this factory's
     * workers require. Validated against `Info.plist` at `kmpWorkerModule()` startup.
     */
    val requiredBgTaskIds: Set<String>
}
