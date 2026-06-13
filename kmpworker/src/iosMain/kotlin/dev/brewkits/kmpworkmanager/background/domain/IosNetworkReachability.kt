package dev.brewkits.kmpworkmanager.background.domain

import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT

/**
 * Real network reachability for iOS, backed by `NWPathMonitor` (Network.framework, iOS 12+).
 *
 * Replaces the previous hardcoded `checkNetworkReachability() = true` placeholder
 * (issue #40). A single process-wide monitor runs on a background GCD queue and updates
 * [snapshot] whenever the OS reports a path change; [isReachable] reads that snapshot —
 * a non-blocking O(1) call suitable for the synchronous diagnostics path.
 *
 * Self-contained: no Swift bridge required. Until the monitor delivers its first update,
 * [isReachable] returns `true` (conservative — matches the old placeholder's optimism so
 * a not-yet-warmed monitor never spuriously reports "offline").
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosNetworkReachability {

    // 1 = reachable, 0 = not reachable. Seeded to 1 (see KDoc).
    private val reachable = AtomicInt(1)

    private val monitor by lazy {
        nw_path_monitor_create().also { m ->
            nw_path_monitor_set_update_handler(m) { path ->
                val satisfied = nw_path_get_status(path) == nw_path_status_satisfied
                reachable.value = if (satisfied) 1 else 0
            }
            nw_path_monitor_set_queue(m, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
            nw_path_monitor_start(m)
        }
    }

    /**
     * Snapshot of current reachability. Starts the monitor lazily on first call.
     *
     * In a test environment the monitor is NOT started: NWPathMonitor's update handler
     * fires on a background GCD queue and outlives the per-class test process teardown,
     * which Kotlin/Native surfaces as a process-level crash unattributed to any test
     * (it made the whole iosSimulatorArm64Test run flaky). Tests get the conservative
     * `true` seed instead. Detection mirrors [IosFileCoordinator]'s test-env short-circuit.
     */
    fun isReachable(): Boolean {
        if (dev.brewkits.kmpworkmanager.utils.IosTestEnvironment.isTestEnvironment) {
            return reachable.value == 1
        }
        monitor // touch to start lazily
        return reachable.value == 1
    }
}
