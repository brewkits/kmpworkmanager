@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [ChainProgressStore], the Stage 1 extraction from `IosFileStorage`
 * (see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * These pin the behaviours the extraction had to preserve, each of which exists because it
 * was once a bug: the debounce coalescing rapid saves into one write, buffer eviction
 * beating the disk delete so an abandoned chain cannot be resurrected by the next flush,
 * self-healing of a corrupt file plus the one-shot flag that tells `executeChain` the
 * history is gone, and the synchronous emergency flush used by the BGTask expiration
 * handler.
 *
 * **On the two coordination strategies.** The split plan's acceptance criteria ask each
 * store's tests to exercise "both `DirectCoordinationStrategy` and
 * `NSFileCoordinationStrategy`". Those types do not exist in this codebase — neither does
 * any `CoordinationStrategy`; the plan and `CLAUDE.md` both describe an API that was never
 * written. What actually selects the path is [StorageFileIo]'s `coordinatorTestMode`, so
 * every test here runs against both values of it.
 *
 * That parameterisation proves the store behaves identically whichever value it is given,
 * which is worth having — but it is honestly *not* coverage of the real NSFileCoordinator.
 * `IosFileCoordinator.coordinate` ORs its parameter with `IosTestEnvironment.isTestEnvironment`,
 * and that is true for every test running in this binary, so the coordinated path is skipped
 * in both cases. Exercising the real coordinator from a test would need a seam that does not
 * exist today. Recorded rather than papered over.
 */
class ChainProgressStoreTest {

    private lateinit var testDirectory: NSURL
    private lateinit var chainsDir: NSURL
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val name = "ChainProgressStoreTest-$stamp-${platform.posix.rand()}"
        testDirectory = NSURL.fileURLWithPath("${NSTemporaryDirectory()}$name")
        chainsDir = testDirectory.safeAppend("chains")
        NSFileManager.defaultManager.createDirectoryAtURL(
            chainsDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        NSFileManager.defaultManager.removeItemAtURL(testDirectory, error = null)
    }

    /**
     * Builds a store on the shared temp directory. [coordinatorTestMode] is the flag the
     * split's acceptance criteria call a "coordination strategy" — see the class KDoc for
     * what it does and does not prove.
     */
    private fun newStore(coordinatorTestMode: Boolean): ChainProgressStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return ChainProgressStore(
            io = StorageFileIo(
                isTestMode = true,
                coordinatorTestMode = coordinatorTestMode,
                coordinationTimeoutMs = 30_000L
            ),
            backgroundScope = scope,
            persistenceJson = Json { ignoreUnknownKeys = true },
            chainsDir = { chainsDir }
        )
    }

    /** Runs [block] against a store built with each coordination flag. */
    private suspend fun bothStrategies(block: suspend (ChainProgressStore, String) -> Unit) {
        listOf(true, false).forEach { coordinatorTestMode ->
            block(newStore(coordinatorTestMode), "coordinatorTestMode=$coordinatorTestMode")
        }
    }

    /** Every file directly under [dir], as names. */
    private fun filesUnder(dir: NSURL): List<String> {
        val path = dir.path ?: return emptyList()
        return (NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null) ?: emptyList<Any?>())
            .mapNotNull { it as? String }
    }

    private fun progressFileExists(chainId: String): Boolean {
        val path = chainsDir.safeAppend("${chainId}_progress.json").path ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    @Test
    fun savedProgressIsReadableAfterAnExplicitFlush() = runTest {
        bothStrategies { store, label ->
            val chainId = "round-trip-$label"
            store.saveChainProgress(
                ChainProgress(chainId = chainId, totalSteps = 5, completedSteps = listOf(0, 1))
            )
            store.flushNow()

            val loaded = store.loadChainProgress(chainId)
            assertNotNull(loaded, "progress must survive a flush ($label)")
            assertEquals(5, loaded.totalSteps, label)
            assertEquals(listOf(0, 1), loaded.completedSteps, label)
        }
    }

    /**
     * The buffer exists to turn N rapid saves into one coordinated write — the reason
     * `saveChainProgress` is cheap enough to call after every step. What must survive the
     * batching is the *latest* value, not whichever one happened to be in flight.
     */
    @Test
    fun rapidSavesCoalesceIntoOneWriteOfTheLatestValue() = runTest {
        bothStrategies { store, label ->
            val chainId = "debounce-$label"
            repeat(4) { step ->
                store.saveChainProgress(
                    ChainProgress(
                        chainId = chainId,
                        totalSteps = 4,
                        completedSteps = (0..step).toList()
                    )
                )
            }

            // Nothing is on disk yet — the whole point of the debounce.
            assertFalse(
                progressFileExists(chainId),
                "the debounce must not have written yet ($label)"
            )

            // Real time, not virtual: the flush job runs on the store's own scope, which
            // runTest's scheduler does not control.
            withContext(Dispatchers.Default) { delay(500) }

            val loaded = store.loadChainProgress(chainId)
            assertNotNull(loaded, "the debounced flush must eventually write ($label)")
            assertEquals(listOf(0, 1, 2, 3), loaded.completedSteps, "latest value wins ($label)")
        }
    }

    /**
     * BUG (v2.5, "BUG 13"): chain-abandon paths call `deleteChainProgress` and then almost
     * immediately hit a flush. If the delete only removed the file, the still-buffered
     * `ChainProgress` was written straight back — the chain came back from the dead on the
     * next BGTask invocation. Buffer eviction must happen before the disk delete.
     */
    @Test
    fun deleteEvictsTheBufferSoALaterFlushCannotResurrectTheChain() = runTest {
        bothStrategies { store, label ->
            val chainId = "abandon-$label"
            store.saveChainProgress(ChainProgress(chainId = chainId, totalSteps = 3))

            store.deleteChainProgress(chainId)
            store.flushNow()

            assertFalse(progressFileExists(chainId), "the flush resurrected the file ($label)")
            assertNull(store.loadChainProgress(chainId), "abandoned progress came back ($label)")
        }
    }

    /**
     * A corrupt progress file must not be able to wedge a chain forever. It is deleted, and
     * the chain is flagged so `executeChain` can refuse to restart non-idempotent work that
     * has lost its history. The flag is one-shot by contract — the caller consumes it once,
     * immediately before executing.
     */
    @Test
    fun corruptProgressIsDeletedAndFlaggedExactlyOnce() = runTest {
        bothStrategies { store, label ->
            val chainId = "corrupt-$label"
            val file = chainsDir.safeAppend("${chainId}_progress.json")
            (("{ this is not json" as NSString)).writeToFile(
                file.path!!,
                atomically = false,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            assertTrue(progressFileExists(chainId), "test setup failed to write the file ($label)")

            assertNull(store.loadChainProgress(chainId), "corrupt data must not load ($label)")
            assertFalse(progressFileExists(chainId), "corrupt file must be deleted ($label)")

            assertTrue(store.consumeSelfHealedFlag(chainId), "self-heal must be flagged ($label)")
            assertFalse(
                store.consumeSelfHealedFlag(chainId),
                "the flag is one-shot — a second read must be false ($label)"
            )
        }
    }

    /** A chain nothing happened to must not look self-healed. */
    @Test
    fun anUntouchedChainIsNotFlaggedAsSelfHealed() = runTest {
        bothStrategies { store, label ->
            assertFalse(store.consumeSelfHealedFlag("never-seen-$label"), label)
        }
    }

    /** A missing progress file is absence, not corruption: null, and no self-heal flag. */
    @Test
    fun aMissingProgressFileLoadsAsNullWithoutFlaggingSelfHealing() = runTest {
        bothStrategies { store, label ->
            val chainId = "absent-$label"
            assertNull(store.loadChainProgress(chainId), label)
            assertFalse(
                store.consumeSelfHealedFlag(chainId),
                "absence must not be reported as corruption ($label)"
            )
        }
    }

    /**
     * The BGTask expiration handler and `applicationWillResignActive` cannot suspend, so
     * they call this. It has to persist without a coroutine caller.
     */
    @Test
    fun theSynchronousEmergencyFlushPersistsBufferedProgress() = runTest {
        bothStrategies { store, label ->
            val chainId = "emergency-$label"
            store.saveChainProgress(
                ChainProgress(chainId = chainId, totalSteps = 2, completedSteps = listOf(0))
            )

            withContext(Dispatchers.Default) { store.flushAllPendingProgress() }

            val loaded = store.loadChainProgress(chainId)
            assertNotNull(loaded, "the emergency flush must persist ($label)")
            assertEquals(listOf(0), loaded.completedSteps, label)
        }
    }

    /**
     * Flushing an empty buffer is a no-op, not an error — `IosFileStorage.close()` calls
     * `flushNow()` unconditionally, including on an instance that never saved anything.
     * Also asserts it writes nothing: an empty flush that created a file would leave a stray
     * artifact in the chains directory for every closed storage instance.
     */
    @Test
    fun flushingAnEmptyBufferIsHarmlessAndWritesNothing() = runTest {
        bothStrategies { store, label ->
            store.flushNow()
            store.flushNow()

            assertTrue(
                filesUnder(chainsDir).isEmpty(),
                "an empty flush must not create files: ${filesUnder(chainsDir)} ($label)"
            )
            assertNull(store.loadChainProgress("never-saved"), label)
        }
    }

    /** The test hook the close()-finally regression tests depend on still throws. */
    @Test
    fun theFlushFailureTestHookThrows() = runTest {
        bothStrategies { store, label ->
            store.testForceFlushFailure = true
            var threw = false
            try {
                store.flushNow()
            } catch (e: IllegalStateException) {
                threw = true
            }
            assertTrue(threw, "testForceFlushFailure must make flushNow throw ($label)")
        }
    }
}
