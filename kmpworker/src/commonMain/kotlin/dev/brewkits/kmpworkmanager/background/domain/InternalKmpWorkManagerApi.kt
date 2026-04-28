package dev.brewkits.kmpworkmanager.background.domain

/**
 * Marks APIs that are part of the KMP WorkManager implementation internals.
 *
 * These APIs are public only to allow access from integration test modules in
 * multi-module projects. They are not stable and may change without notice.
 *
 * **Do not use in production code.** Annotate test-only call sites with
 * `@OptIn(InternalKmpWorkManagerApi::class)` to explicitly acknowledge this.
 *
 * **Example — integration test in a consumer app module**:
 * ```kotlin
 * @OptIn(InternalKmpWorkManagerApi::class)
 * val scheduler = NativeTaskScheduler(forceWaitMigration = true)
 * ```
 */
@RequiresOptIn(
    message = "This is an internal KMP WorkManager API intended for testing only. " +
        "It may change or be removed without notice. " +
        "Annotate your call site with @OptIn(InternalKmpWorkManagerApi::class) to proceed.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalKmpWorkManagerApi
