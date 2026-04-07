package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import kotlin.concurrent.Volatile

/**
 * Lightweight runtime container for configuration values that need to be accessible
 * from platform-specific executors (ChainExecutor on iOS, KmpWorker on Android)
 * without going through Koin or constructor injection.
 *
 * Set once during `initialize()` / `kmpWorkerModule()` and read-only thereafter.
 * All fields are `@Volatile` for safe cross-thread visibility.
 */
internal object KmpWorkManagerRuntime {

    @Volatile
    var telemetryHook: TelemetryHook? = null
        private set

    /**
     * Battery level (0–100) below which tasks are deferred (when not charging).
     * 0 = guard disabled.
     */
    @Volatile
    var minBatteryLevelPercent: Int = 5
        private set

    fun configure(config: KmpWorkManagerConfig) {
        telemetryHook = config.telemetryHook
        minBatteryLevelPercent = config.minBatteryLevelPercent
    }

    /** For tests only. */
    internal fun reset() {
        telemetryHook = null
        minBatteryLevelPercent = 5
    }
}
