package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore

/**
 * Lightweight runtime container for configuration values.
 *
 * Converted to a class with global singleton to bypass Kotlin K2 compiler crash
 * on objects with delegated/private properties in commonMain.
 */
internal class KmpWorkManagerRuntimeContainer {
    @kotlin.concurrent.Volatile
    var telemetryHook: TelemetryHook? = null
    
    @kotlin.concurrent.Volatile
    var minBatteryLevelPercent: Int = 5
    
    @kotlin.concurrent.Volatile
    var executionHistoryStore: ExecutionHistoryStore? = null

    /**
     * Shared Json instance for all internal persistence and serialization.
     * Reusing this instance caches class descriptors.
     * explicitNulls = false reduces disk footprint.
     */
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false // Skip writing null values to save disk and I/O time
    }

    fun configure(config: KmpWorkManagerConfig) {
        telemetryHook = config.telemetryHook
        minBatteryLevelPercent = config.minBatteryLevelPercent
    }

    fun setHistoryStore(store: ExecutionHistoryStore) {
        executionHistoryStore = store
    }

    // ── Safe Telemetry Helpers ───────────────────────────────────────────────

    fun notifyTaskStarted(event: TelemetryHook.TaskStartedEvent) {
        runCatching { telemetryHook?.onTaskStarted(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onTaskStarted failed", it) }
    }

    fun notifyTaskCompleted(event: TelemetryHook.TaskCompletedEvent) {
        runCatching { telemetryHook?.onTaskCompleted(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onTaskCompleted failed", it) }
    }

    fun notifyTaskFailed(event: TelemetryHook.TaskFailedEvent) {
        runCatching { telemetryHook?.onTaskFailed(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onTaskFailed failed", it) }
    }

    fun notifyChainCompleted(event: TelemetryHook.ChainCompletedEvent) {
        runCatching { telemetryHook?.onChainCompleted(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onChainCompleted failed", it) }
    }

    fun notifyChainFailed(event: TelemetryHook.ChainFailedEvent) {
        runCatching { telemetryHook?.onChainFailed(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onChainFailed failed", it) }
    }

    fun notifyChainSkipped(event: TelemetryHook.ChainSkippedEvent) {
        runCatching { telemetryHook?.onChainSkipped(event) }
            .onFailure { dev.brewkits.kmpworkmanager.utils.Logger.w("Telemetry", "onChainSkipped failed", it) }
    }

    fun reset() {
        telemetryHook = null
        minBatteryLevelPercent = 5
        executionHistoryStore = null
    }
}

/**
 * Global singleton instance for the runtime.
 */
internal val KmpWorkManagerRuntime = KmpWorkManagerRuntimeContainer()
