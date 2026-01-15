package dev.brewkits.kmpworkmanager.background.domain

/**
 * Marks APIs that are only available on Android platform.
 *
 * When you use an API annotated with `@AndroidOnly` on iOS, it will:
 * - Return `ScheduleResult.REJECTED_OS_POLICY` for scheduling operations
 * - Be ignored or have no effect for constraints
 *
 * This annotation serves as a compile-time warning to help developers understand
 * platform limitations and avoid using Android-specific features that won't work on iOS.
 *
 * **Example**:
 * ```kotlin
 * @OptIn(AndroidOnly::class)
 * scheduler.enqueue(
 *     id = "media-observer",
 *     trigger = TaskTrigger.ContentUri(/* ... */),  // Android-only trigger
 *     workerClassName = "MediaWorker"
 * )
 * // iOS: Returns REJECTED_OS_POLICY
 * ```
 *
 * **Common Android-Only Features**:
 * - `TaskTrigger.ContentUri` - Content URI monitoring
 * - `TaskTrigger.Exact` - Exact alarm scheduling (iOS uses notifications instead)
 * - `Constraints.requiresCharging` - Charging requirement (iOS: BGProcessingTask only)
 * - `Constraints.systemConstraints` - Battery/storage/idle constraints
 * - `Constraints.backoffPolicy` - Automatic retry behavior
 *
 * See [iOS Best Practices](../../../docs/ios-best-practices.md) for platform differences.
 */
@RequiresOptIn(
    message = "This API is Android-only and will return REJECTED_OS_POLICY or be ignored on iOS. " +
            "See documentation for platform-specific behavior.",
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
annotation class AndroidOnly
