@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * iOS-specific tests for v2.4.2 bug fixes.
 * 
 * Verifies that the drift-correction logic correctly handles:
 * - Version upgrades (missing anchoredStartMs)
 * - REPLACE policy (resetting the anchor)
 * - Immediate execution (delayMs = 0)
 */
class BugFixes_v242_IosTest {

    private fun makeTempDir(name: String): NSURL {
        val tmpDir = NSTemporaryDirectory()
        val tmp = NSURL.fileURLWithPath(tmpDir)
        val dir = tmp.URLByAppendingPathComponent("kmptest_$name", isDirectory = true)!!
        NSFileManager.defaultManager.removeItemAtURL(dir, null)
        NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun makeStorage(name: String): IosFileStorage {
        return IosFileStorage(
            config = IosFileStorageConfig(diskSpaceBufferBytes = 0L),
            baseDirectory = makeTempDir(name)
        )
    }

    @Test
    fun testREPLACEPolicyResetsPeriodicTaskAnchorAndAllowsImmediateRun() = runTest {
        val storage = makeStorage("ios-replace-anchor")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "periodic-task"
        val interval = 15 * 60 * 1000L

        // 1. Initial schedule
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = interval, runImmediately = true),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        val meta1 = storage.loadTaskMetadata(taskId, periodic = true)
        val anchor1 = meta1?.get("anchoredStartMs")?.toLong()
        assertNotNull(anchor1, "Anchor must be established on first schedule")

        // Wait a bit to ensure nowMs increases
        kotlinx.coroutines.delay(10)

        // 2. Schedule again with REPLACE
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = interval, runImmediately = true),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        val meta2 = storage.loadTaskMetadata(taskId, periodic = true)
        val anchor2 = meta2?.get("anchoredStartMs")?.toLong()
        assertNotNull(anchor2)
        assertTrue(anchor2 > anchor1!!, "Anchor must be reset during REPLACE policy, expected $anchor2 > $anchor1")
    }

    @Test
    fun testMissingAnchoredStartMsInMetadataIsTreatedAsFirstSchedule() = runTest {
        val storage = makeStorage("ios-upgrade-anchor")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "upgrade-task"
        val interval = 15 * 60 * 1000L

        // Manually save metadata WITHOUT anchoredStartMs (simulating v2.4.0)
        storage.saveTaskMetadata(taskId, mapOf(
            "isPeriodic" to "true",
            "intervalMs" to "$interval",
            "workerClassName" to "TestWorker"
        ), periodic = true)

        // Schedule again (e.g. app startup call)
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Periodic(intervalMs = interval, runImmediately = true),
            workerClassName = "TestWorker",
            constraints = Constraints(),
            inputJson = null,
            policy = ExistingPolicy.KEEP
        )

        val meta = storage.loadTaskMetadata(taskId, periodic = true)
        assertNotNull(meta?.get("anchoredStartMs"), "Anchor must be established when missing from old metadata")
    }
}
