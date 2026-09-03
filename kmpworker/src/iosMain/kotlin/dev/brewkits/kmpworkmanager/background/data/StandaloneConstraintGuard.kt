@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data

import dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.SystemConstraint
import platform.Foundation.NSProcessInfo
import platform.Foundation.lowPowerModeEnabled
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/**
 * Stamps the subset of [Constraints] that only the dispatch-time [StandaloneConstraintGuard]
 * can enforce (BGTaskScheduler has no OS-level flag for them) into a standalone task's
 * persisted metadata. Only written when non-default, matching the convention already used
 * for tags/deadlines in this metadata map — existing metadata files stay byte-identical for
 * callers that never touch these fields.
 */
internal fun MutableMap<String, String>.putStandaloneConstraintMetadata(constraints: Constraints) {
    if (constraints.requiresUnmeteredNetwork) {
        put(DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK, "true")
    }
    if (SystemConstraint.REQUIRE_BATTERY_NOT_LOW in constraints.systemConstraints) {
        put(DynamicTaskDispatcher.META_REQUIRES_BATTERY_NOT_LOW, "true")
    }
    if (SystemConstraint.ALLOW_LOW_BATTERY in constraints.systemConstraints) {
        put(DynamicTaskDispatcher.META_ALLOW_LOW_BATTERY, "true")
    }
    if (constraints.backoffPolicy != BackoffPolicy.EXPONENTIAL) {
        put(DynamicTaskDispatcher.META_BACKOFF_POLICY, constraints.backoffPolicy.name)
    }
    if (constraints.backoffDelayMs != 30_000L) {
        put(DynamicTaskDispatcher.META_BACKOFF_DELAY_MS, "${constraints.backoffDelayMs}")
    }
}

/**
 * Rebuilds a [Constraints] instance from a standalone task's persisted metadata — the inverse
 * of [putStandaloneConstraintMetadata]. Used when a task must be re-submitted via
 * `scheduler.enqueue()` (the static-Info.plist-identifier retry path in
 * `IosBackgroundTaskHandler.handleOneTimeTaskResult`), so a retry doesn't silently drop
 * `requiresUnmeteredNetwork`/`systemConstraints`/backoff fields that a plain
 * `Constraints(requiresNetwork = ..., requiresCharging = ..., isHeavyTask = ...)` would.
 */
internal fun reconstructConstraintsFromMetadata(rawMeta: Map<String, String>): Constraints {
    val systemConstraints = buildSet {
        if (rawMeta[DynamicTaskDispatcher.META_REQUIRES_BATTERY_NOT_LOW] == "true") {
            add(SystemConstraint.REQUIRE_BATTERY_NOT_LOW)
        }
        if (rawMeta[DynamicTaskDispatcher.META_ALLOW_LOW_BATTERY] == "true") {
            add(SystemConstraint.ALLOW_LOW_BATTERY)
        }
    }
    val backoffPolicy = rawMeta[DynamicTaskDispatcher.META_BACKOFF_POLICY]
        ?.let { runCatching { BackoffPolicy.valueOf(it) }.getOrNull() }
        ?: BackoffPolicy.EXPONENTIAL
    val backoffDelayMs = rawMeta[DynamicTaskDispatcher.META_BACKOFF_DELAY_MS]?.toLongOrNull() ?: 30_000L

    return Constraints(
        requiresNetwork = rawMeta["requiresNetwork"] == "true",
        requiresUnmeteredNetwork = rawMeta[DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK] == "true",
        requiresCharging = rawMeta["requiresCharging"] == "true",
        isHeavyTask = rawMeta["isHeavyTask"] == "true",
        backoffPolicy = backoffPolicy,
        backoffDelayMs = backoffDelayMs,
        systemConstraints = systemConstraints,
        // Without this, a dedicated-identifier task's custom maxRetries survives only its
        // FIRST retry: handleOneTimeTaskResult re-submits via scheduler.enqueue(reconstructed
        // constraints), which re-derives metadata from the Constraints it's given — dropping
        // back to the -1 default here would silently erase the caller's cap from the freshly
        // written metadata, reverting subsequent attempts to DEFAULT_ATTEMPT_CAP.
        maxRetries = rawMeta[DynamicTaskDispatcher.META_MAX_RETRIES]?.toIntOrNull() ?: -1
    )
}

/**
 * Dispatch-time constraint check for standalone (non-chain) iOS tasks, re-read from persisted
 * metadata since only string flags survive a BGTask relaunch — there is no live [Constraints]
 * instance at this point. Chain steps have their own inline checks against a live [Constraints]
 * in `ChainExecutor.executeTask`; this is deliberately a separate, smaller check rather than a
 * shared abstraction with that code, since the two operate on different input shapes
 * (`Map<String, String>` here vs. `Constraints` there).
 */
internal object StandaloneConstraintGuard {

    /**
     * Returns a human-readable reason the task must not run yet, or `null` if every
     * constraint recorded in [rawMeta] is currently satisfied.
     *
     * @param isNotCharging Injectable for testing; defaults to the real `UIDevice` read.
     *   **Opt-in via host app**: mirrors `ChainExecutor`'s battery guard — reads
     *   `device.batteryMonitoringEnabled` but never toggles it (a toggle here would race the
     *   host's own UI thread and risks breaking the host's battery widget; see
     *   `ChainExecutor.executeTask`'s battery-guard KDoc for the full rationale). If monitoring
     *   is disabled (the default), we cannot tell whether the device is charging — treat that
     *   as "not violated" so we never wrongly block legitimate work.
     * @param isLowPowerModeEnabled Injectable for testing; defaults to the real `NSProcessInfo`
     *   read.
     */
    fun violationReason(
        rawMeta: Map<String, String>?,
        networkStateProvider: IosNetworkStateProvider,
        isNotCharging: () -> Boolean = ::defaultIsNotCharging,
        isLowPowerModeEnabled: () -> Boolean = { NSProcessInfo.processInfo().lowPowerModeEnabled }
    ): String? {
        val meta = rawMeta ?: return null

        if (meta[DynamicTaskDispatcher.META_REQUIRES_UNMETERED_NETWORK] == "true" &&
            networkStateProvider.isNetworkCellular()
        ) {
            return "Requires unmetered (Wi-Fi) network but cellular is active"
        }

        if (meta["requiresCharging"] == "true" && isNotCharging()) {
            return "Requires charging but device is not connected to power"
        }

        val allowLowBattery = meta[DynamicTaskDispatcher.META_ALLOW_LOW_BATTERY] == "true"
        if (!allowLowBattery &&
            meta[DynamicTaskDispatcher.META_REQUIRES_BATTERY_NOT_LOW] == "true" &&
            isLowPowerModeEnabled()
        ) {
            return "Requires battery not low, but Low Power Mode is enabled"
        }

        return null
    }

    private fun defaultIsNotCharging(): Boolean {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) return false
        val state = device.batteryState
        val isCharging = state != UIDeviceBatteryState.UIDeviceBatteryStateUnplugged &&
            state != UIDeviceBatteryState.UIDeviceBatteryStateUnknown
        return !isCharging
    }
}
