@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression coverage for the `replaceItemAtURL`-based atomic overwrite path in
 * `IosFileStorage.writeStringToFile`. That branch is only reached when
 * `isTestMode == false` and the target file already exists — every other test in this
 * suite runs with the default (auto-detected) `isTestMode = true`, so without a test that
 * forces production mode here, the atomic-replace branch is never actually exercised.
 */
class IosFileStorageAtomicWriteTest {

    private lateinit var storage: IosFileStorage
    private lateinit var testDirectory: NSURL

    @BeforeTest
    fun setup() = runTest {
        val fileManager = NSFileManager.defaultManager
        val tempDir = fileManager.temporaryDirectory()
        testDirectory = tempDir.URLByAppendingPathComponent(
            "IosFileStorageAtomicWriteTest-${NSDate().timeIntervalSince1970()}-${(0..999999).random()}"
        )!!

        fileManager.createDirectoryAtURL(
            testDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )

        // Force production mode so writeStringToFile takes the replaceItemAtURL branch
        // on the second write instead of the isTestMode-gated simple write.
        storage = IosFileStorage(
            config = IosFileStorageConfig(isTestMode = false),
            baseDirectory = testDirectory
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        storage.close()
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    @Test
    fun `overwriting existing task metadata uses atomic replace and second write wins`() = runTest {
        val id = "atomic-write-task"

        storage.saveTaskMetadata(id, mapOf("version" to "1"), periodic = false)
        storage.saveTaskMetadata(id, mapOf("version" to "2"), periodic = false)

        val loaded = storage.loadTaskMetadata(id, periodic = false)
        assertEquals(mapOf("version" to "2"), loaded)
    }

    @Test
    fun `atomic replace leaves no leftover tmp files in directory`() = runTest {
        val id = "atomic-write-tmp-cleanup"

        storage.saveTaskMetadata(id, mapOf("version" to "1"), periodic = false)
        storage.saveTaskMetadata(id, mapOf("version" to "2"), periodic = false)
        storage.saveTaskMetadata(id, mapOf("version" to "3"), periodic = false)

        val tasksDir = testDirectory.URLByAppendingPathComponent("tasks")!!
        val entries = NSFileManager.defaultManager.contentsOfDirectoryAtURL(
            tasksDir,
            includingPropertiesForKeys = null,
            options = 0u,
            error = null
        ) as? List<NSURL> ?: emptyList()

        val leftoverTmpFiles = entries.filter { it.lastPathComponent?.contains(".tmp-") == true }
        assertTrue(leftoverTmpFiles.isEmpty(), "Found leftover tmp files: ${leftoverTmpFiles.map { it.lastPathComponent }}")

        // Sanity: the final metadata is still readable and correct — atomic replace
        // didn't corrupt or lose the file on the way to being tmp-free.
        assertEquals(mapOf("version" to "3"), storage.loadTaskMetadata(id, periodic = false))
    }

    @Test
    fun `overwriting existing chain progress uses atomic replace and second write wins`() = runTest {
        val chainId = "atomic-write-chain"

        storage.saveChainProgress(ChainProgress(chainId = chainId, totalSteps = 3, completedSteps = listOf(0)))
        storage.flushNow()
        storage.saveChainProgress(ChainProgress(chainId = chainId, totalSteps = 3, completedSteps = listOf(0, 1)))
        storage.flushNow()

        val loaded = storage.loadChainProgress(chainId)
        assertNotNull(loaded)
        assertEquals(listOf(0, 1), loaded.completedSteps)
    }
}
