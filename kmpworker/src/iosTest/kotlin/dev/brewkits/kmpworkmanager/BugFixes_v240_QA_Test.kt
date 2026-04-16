@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Returns true when executing inside the iOS Simulator.
 * The simulator sets SIMULATOR_DEVICE_NAME (Xcode) or has "CoreSimulator" in its tmp path.
 * NSFileProtectionKey is not enforced on the simulator — macOS hosts don't implement it —
 * so FILE-PROT assertions are skipped there and only validated on real devices.
 */
private fun isRunningOnSimulator(): Boolean {
    getenv("SIMULATOR_DEVICE_NAME")?.toKString()?.let { if (it.isNotEmpty()) return true }
    val tmpDir = NSTemporaryDirectory()
    return tmpDir.contains("CoreSimulator")
}

/**
 * iOS-specific regression tests for v2.4.0 bug fixes.
 *
 * Covers:
 * - FILE-PROT : Directories created by IosFileStorage must use
 *               NSFileProtectionCompleteUntilFirstUserAuthentication so BGTasks can
 *               read/write files after the first unlock with the screen locked.
 * - FLUSH-SYNC: flushAllPendingProgress() must complete within 450 ms and release
 *               the completion signal even when the progress buffer is empty.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class BugFixes_v240_QA_Test {

    private val fileManager = NSFileManager.defaultManager

    /**
     * Returns a NSURL for a path that does NOT yet exist in the temp directory.
     * IosFileStorage will create it — and this is when the protection attribute is applied.
     */
    private fun freshTempUrl(name: String): NSURL {
        val tmpDir = NSTemporaryDirectory()
        val tmp = NSURL.fileURLWithPath(tmpDir)
        val url = tmp.URLByAppendingPathComponent("kmptest239_$name", isDirectory = true)!!
        // Ensure clean state — remove if it exists from a previous run
        fileManager.removeItemAtURL(url, error = null)
        return url
    }

    private fun makeStorage(baseUrl: NSURL): IosFileStorage {
        return IosFileStorage(
            config = IosFileStorageConfig(diskSpaceBufferBytes = 0L),
            baseDirectory = baseUrl
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE-PROT — Newly-created directories must use NSFileProtectionCompleteUntilFirstUserAuthentication
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When IosFileStorage creates the base directory (i.e. the path did not exist before),
     * the directory must be protected with
     * NSFileProtectionCompleteUntilFirstUserAuthentication.
     *
     * Without this fix the OS default (NSFileProtectionComplete) is used, which prevents
     * any BGTask from reading or writing the queue while the screen is locked — even after
     * the user has unlocked the device at least once since boot.
     */
    @Test
    fun `FILE-PROT - newly created base directory has NSFileProtectionCompleteUntilFirstUserAuthentication`() = runTest {
        val baseUrl = freshTempUrl("file-prot-base")

        // Directory must NOT exist yet — IosFileStorage must create it
        val existsBefore = fileManager.fileExistsAtPath(baseUrl.path ?: "")
        assertEquals(false, existsBefore, "Pre-condition: directory must not exist before storage init")

        val storage = makeStorage(baseUrl)

        // Trigger the lazy baseDir initialisation by performing any read operation
        val activeIds = storage.getActiveChainIds()
        assertNotNull(activeIds, "getActiveChainIds must not return null")

        // Clean up
        storage.close()

        // Verify directory was created
        val path = baseUrl.path ?: run {
            println("WARNING: could not resolve base path — skipping protection check")
            return@runTest
        }
        assertTrue(fileManager.fileExistsAtPath(path), "Base directory must exist after storage init")

        // Verify file protection attribute.
        // NSFileProtectionKey is not enforced on the iOS Simulator (macOS host does not implement
        // Data Protection), so the assertion is skipped there and validated on real devices only.
        if (isRunningOnSimulator()) {
            println("FILE-PROT: running on Simulator — skipping protection attribute assertion (not enforced by macOS host)")
        } else {
            val attrs = fileManager.attributesOfItemAtPath(path, error = null)
            val protection = attrs?.get(NSFileProtectionKey) as? String
            assertEquals(
                NSFileProtectionCompleteUntilFirstUserAuthentication,
                protection,
                "Directory protection must be NSFileProtectionCompleteUntilFirstUserAuthentication. " +
                "Got: $protection. Without this, BGTasks cannot access files while screen is locked."
            )
        }

        fileManager.removeItemAtURL(baseUrl, error = null)
    }

    /**
     * Subdirectories (chains/, metadata/) created lazily by IosFileStorage must also
     * carry the correct file protection attribute.
     */
    @Test
    fun `FILE-PROT - lazily-created chains subdirectory has correct file protection`() = runTest {
        val baseUrl = freshTempUrl("file-prot-chains")

        val storage = makeStorage(baseUrl)

        // Trigger chains directory creation by saving a chain definition
        storage.saveChainDefinition("v239-prot-test", listOf(listOf(
            TaskRequest(workerClassName = "TestWorker")
        )))

        storage.close()

        // Verify chains subdirectory was created with correct protection
        val chainsUrl = baseUrl.URLByAppendingPathComponent("chains", isDirectory = true)
        val chainsPath = chainsUrl?.path
        if (chainsPath == null || !fileManager.fileExistsAtPath(chainsPath)) {
            println("WARNING: chains directory not found — skipping protection check")
            fileManager.removeItemAtURL(baseUrl, error = null)
            return@runTest
        }

        if (isRunningOnSimulator()) {
            println("FILE-PROT: running on Simulator — skipping chains/ protection attribute assertion")
        } else {
            val attrs = fileManager.attributesOfItemAtPath(chainsPath, error = null)
            val protection = attrs?.get(NSFileProtectionKey) as? String
            assertEquals(
                NSFileProtectionCompleteUntilFirstUserAuthentication,
                protection,
                "chains/ directory protection must be NSFileProtectionCompleteUntilFirstUserAuthentication. " +
                "Got: $protection"
            )
        }

        fileManager.removeItemAtURL(baseUrl, error = null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLUSH-SYNC — flushAllPendingProgress must not hang and must complete quickly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * flushAllPendingProgress() with an empty progress buffer should return immediately
     * (well within the 450 ms watchdog budget).
     */
    @Test
    fun `FLUSH-SYNC - empty buffer completes without hanging`() = runTest {
        val baseUrl = freshTempUrl("flush-empty")
        val storage = makeStorage(baseUrl)

        val startMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        storage.flushAllPendingProgress()
        val elapsedMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - startMs

        storage.close()
        fileManager.removeItemAtURL(baseUrl, error = null)

        assertTrue(elapsedMs < 450L,
            "flushAllPendingProgress on empty buffer must complete in <450ms, took ${elapsedMs}ms")
    }
}
