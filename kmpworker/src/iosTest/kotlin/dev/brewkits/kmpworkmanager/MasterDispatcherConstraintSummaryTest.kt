@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Covers [IosFileStorage.getDynamicQueueConstraintSummary] — the aggregate
 * light/network profile the Master Dispatcher uses to pick between an unconstrained
 * `BGProcessingTaskRequest` and a cheaper, better-scheduled `BGAppRefreshTaskRequest`.
 *
 * Background: raised in
 * https://github.com/brewkits/kmpworkmanager/discussions/78 and tracked as
 * https://github.com/brewkits/kmpworkmanager/issues/79 — the dispatcher previously
 * always requested an unconstrained `BGProcessingTaskRequest`, even when every pending
 * dynamic task was light and network-dependent. See
 * docs/ios-dynamic-task-scheduling.md § 5.
 */
class MasterDispatcherConstraintSummaryTest {

    private fun makeTempDir(tag: String): NSURL {
        val base = NSTemporaryDirectory()
        val name = "kmp_master_dispatcher_summary_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        return url
    }

    private fun makeStorage(tag: String): IosFileStorage {
        return IosFileStorage(
            config = IosFileStorageConfig(isTestMode = true),
            baseDirectory = makeTempDir(tag)
        )
    }

    private fun metaFor(requiresNetwork: Boolean, isHeavyTask: Boolean): Map<String, String> = mapOf(
        "workerClassName" to "EchoWorker",
        "requiresNetwork" to "$requiresNetwork",
        "requiresCharging" to "false",
        "isHeavyTask" to "$isHeavyTask"
    )

    @Test
    fun `empty queue reports no pending tasks and is not all-light or all-network`() = runTest {
        val storage = makeStorage("empty")

        val summary = storage.getDynamicQueueConstraintSummary()

        assertEquals(0, summary.pendingCount)
        assertFalse(summary.allLight, "an empty queue has nothing to schedule as BGAppRefreshTask")
        assertFalse(summary.allRequireNetwork)
    }

    @Test
    fun `queue of only light network tasks is reported all-light and all-network`() = runTest {
        val storage = makeStorage("all-light-network")

        storage.enqueueTask("light-net-1")
        storage.saveTaskMetadata("light-net-1", metaFor(requiresNetwork = true, isHeavyTask = false), periodic = false)
        storage.enqueueTask("light-net-2")
        storage.saveTaskMetadata("light-net-2", metaFor(requiresNetwork = true, isHeavyTask = false), periodic = false)

        val summary = storage.getDynamicQueueConstraintSummary()

        assertEquals(2, summary.pendingCount)
        assertTrue(summary.allLight, "all queued tasks are light — dispatcher should use BGAppRefreshTaskRequest")
        assertTrue(summary.allRequireNetwork)
    }

    @Test
    fun `a single heavy task among light ones flips allLight to false`() = runTest {
        val storage = makeStorage("mixed-heavy")

        storage.enqueueTask("light-1")
        storage.saveTaskMetadata("light-1", metaFor(requiresNetwork = false, isHeavyTask = false), periodic = false)
        storage.enqueueTask("heavy-1")
        storage.saveTaskMetadata("heavy-1", metaFor(requiresNetwork = false, isHeavyTask = true), periodic = false)

        val summary = storage.getDynamicQueueConstraintSummary()

        assertEquals(2, summary.pendingCount)
        assertFalse(summary.allLight, "one heavy task in the queue means BGProcessingTaskRequest is still required")
    }

    @Test
    fun `mixed network requirements are not reported as all-network`() = runTest {
        val storage = makeStorage("mixed-network")

        storage.enqueueTask("net-1")
        storage.saveTaskMetadata("net-1", metaFor(requiresNetwork = true, isHeavyTask = true), periodic = false)
        storage.enqueueTask("offline-ok-1")
        storage.saveTaskMetadata("offline-ok-1", metaFor(requiresNetwork = false, isHeavyTask = true), periodic = false)

        val summary = storage.getDynamicQueueConstraintSummary()

        assertFalse(
            summary.allRequireNetwork,
            "requiresNetworkConnectivity=true would wrongly block the offline-capable task too"
        )
    }

    @Test
    fun `periodic dynamic task metadata is also aggregated`() = runTest {
        val storage = makeStorage("periodic")

        storage.enqueueTask("periodic-light")
        storage.saveTaskMetadata(
            "periodic-light",
            mapOf(
                "isPeriodic" to "true",
                "intervalMs" to "60000",
                "anchoredStartMs" to "0",
                "workerClassName" to "EchoWorker",
                "requiresNetwork" to "true",
                "requiresCharging" to "false",
                "isHeavyTask" to "false"
            ),
            periodic = true
        )

        val summary = storage.getDynamicQueueConstraintSummary()

        assertEquals(1, summary.pendingCount)
        assertTrue(summary.allLight)
        assertTrue(summary.allRequireNetwork)
    }

    @Test
    fun `dequeued tasks drop out of the aggregate`() = runTest {
        val storage = makeStorage("dequeue")

        storage.enqueueTask("heavy-1")
        storage.saveTaskMetadata("heavy-1", metaFor(requiresNetwork = false, isHeavyTask = true), periodic = false)
        storage.enqueueTask("light-1")
        storage.saveTaskMetadata("light-1", metaFor(requiresNetwork = false, isHeavyTask = false), periodic = false)

        assertFalse(storage.getDynamicQueueConstraintSummary().allLight)

        assertEquals("heavy-1", storage.dequeueTask())

        val summary = storage.getDynamicQueueConstraintSummary()
        assertEquals(1, summary.pendingCount)
        assertTrue(summary.allLight, "after the heavy task is dequeued, only the light task remains")
    }

    // ==================== earliestBackoffFloorMs ====================
    // Covers the fix where rescheduleMasterDispatcher() ignored backoff floors entirely
    // (always requested earliestBeginDate = now), causing the dispatcher to wake
    // immediately, find nothing runnable, and re-request itself repeatedly for the whole
    // backoff duration instead of waiting it out.

    @Test
    fun `earliestBackoffFloorMs is null for an empty queue`() = runTest {
        val storage = makeStorage("floor-empty")

        assertNull(storage.getDynamicQueueConstraintSummary().earliestBackoffFloorMs)
    }

    @Test
    fun `earliestBackoffFloorMs is null when a pending task has no backoff floor`() = runTest {
        val storage = makeStorage("floor-none")

        storage.enqueueTask("no-floor-task")
        storage.saveTaskMetadata("no-floor-task", metaFor(requiresNetwork = false, isHeavyTask = false), periodic = false)

        assertNull(
            storage.getDynamicQueueConstraintSummary().earliestBackoffFloorMs,
            "A task with no floor is ready now — the dispatcher must not wait for anything"
        )
    }

    @Test
    fun `earliestBackoffFloorMs is null when only SOME pending tasks have a floor`() = runTest {
        val storage = makeStorage("floor-mixed")

        storage.enqueueTask("floored")
        storage.saveTaskMetadata(
            "floored",
            metaFor(requiresNetwork = false, isHeavyTask = false) +
                (DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS to "99999999999999"),
            periodic = false
        )
        storage.enqueueTask("ready-now")
        storage.saveTaskMetadata("ready-now", metaFor(requiresNetwork = false, isHeavyTask = false), periodic = false)

        assertNull(
            storage.getDynamicQueueConstraintSummary().earliestBackoffFloorMs,
            "One ready task means the dispatcher should wake ASAP, not wait for the other's floor"
        )
    }

    @Test
    fun `earliestBackoffFloorMs is the minimum floor when every pending task is backed off`() = runTest {
        val storage = makeStorage("floor-min")

        storage.enqueueTask("floor-later")
        storage.saveTaskMetadata(
            "floor-later",
            metaFor(requiresNetwork = false, isHeavyTask = false) +
                (DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS to "5000"),
            periodic = false
        )
        storage.enqueueTask("floor-sooner")
        storage.saveTaskMetadata(
            "floor-sooner",
            metaFor(requiresNetwork = false, isHeavyTask = false) +
                (DynamicTaskDispatcher.META_NEXT_RETRY_EARLIEST_MS to "1000"),
            periodic = false
        )

        assertEquals(1000L, storage.getDynamicQueueConstraintSummary().earliestBackoffFloorMs)
    }
}
