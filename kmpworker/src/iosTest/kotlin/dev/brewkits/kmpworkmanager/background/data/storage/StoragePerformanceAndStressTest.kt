@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import dev.brewkits.kmpworkmanager.StressTests
import dev.brewkits.kmpworkmanager.background.data.ChainProgress
import dev.brewkits.kmpworkmanager.background.domain.TaskRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Performance and concurrency characteristics of the stores extracted in v3.5.0.
 *
 * The split moved the mutexes and the write buffer into new classes. Their unit tests prove
 * each store's contract on a quiet machine and one call at a time; this file covers the two
 * properties that only show up under volume — that the listings stay usable as a directory
 * grows, and that concurrent writers do not lose or corrupt records.
 *
 * **On the assertions.** Wall-clock thresholds are deliberately loose and scale-relative
 * rather than absolute: a simulator on a loaded CI runner is an order of magnitude slower
 * than a quiet laptop, and a benchmark that fails on a busy machine gets muted, which is
 * worse than no benchmark. What is actually asserted is the *shape* — that cost grows
 * roughly linearly rather than quadratically, and that nothing is dropped — with generous
 * ceilings that only a genuine regression in complexity would breach.
 *
 * The heaviest cases sit behind [StressTests], the same opt-in gate the rest of the suite
 * uses, and announce themselves when they skip.
 */
class StoragePerformanceAndStressTest {

    private lateinit var root: NSURL
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setup() {
        val stamp = (NSDate().timeIntervalSince1970 * 1000).toLong()
        root = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}StoragePerfTest-$stamp-${platform.posix.rand()}"
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        NSFileManager.defaultManager.removeItemAtURL(root, error = null)
    }

    private fun dir(name: String): NSURL = root.safeAppend(name).also {
        NSFileManager.defaultManager.createDirectoryAtURL(
            it,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    private fun io() = StorageFileIo(
        isTestMode = true,
        coordinatorTestMode = true,
        coordinationTimeoutMs = 30_000L
    )

    private fun metadataStore(tasks: NSURL, periodic: NSURL) = TaskMetadataStore(
        io = io(),
        persistenceJson = Json { ignoreUnknownKeys = true },
        tasksDir = { tasks },
        periodicDir = { periodic }
    )

    // ---- Performance ----------------------------------------------------------------

    /**
     * `listJsonFileIds` returns a Sequence backed by an OS enumerator specifically so a
     * caller can stream ids without materialising them all. The property that matters is
     * that taking the first element does not depend on directory size — a caller looking for
     * one id must not pay for 2,000 of them.
     */
    @Test
    fun theListingIsLazyAndDoesNotCostTheWholeDirectoryToPeek() = runTest {
        val tasks = dir("tasks")
        val store = metadataStore(tasks, dir("periodic"))
        withContext(Dispatchers.Default) {
            repeat(2_000) { store.saveTaskMetadata("task-$it", mapOf("w" to "W"), periodic = false) }
        }

        val mark = TimeSource.Monotonic.markNow()
        val first = store.listTaskIds().first()
        val peek = mark.elapsedNow()

        assertNotNull(first)
        assertTrue(
            peek.inWholeMilliseconds < 250,
            "peeking one id out of 2,000 took ${peek.inWholeMilliseconds}ms — the Sequence " +
                "is no longer lazy if this grows with directory size"
        )
    }

    /**
     * Directory scans must stay linear. The guard is a ratio, not a clock: scanning 4x the
     * files may legitimately take 4x as long on any machine, but an accidental O(N²) — one
     * `loadTaskMetadata` per entry inside a per-entry loop, say — shows up as ~16x.
     */
    @Test
    fun theTagScanCostGrowsLinearlyWithDirectorySize() = runTest {
        val small = metadataStore(dir("small"), dir("small-p"))
        val large = metadataStore(dir("large"), dir("large-p"))
        withContext(Dispatchers.Default) {
            repeat(250) { small.saveTaskMetadata("t-$it", mapOf("workerClassName" to "W"), periodic = false) }
            repeat(1_000) { large.saveTaskMetadata("t-$it", mapOf("workerClassName" to "W"), periodic = false) }
        }

        fun scan(store: TaskMetadataStore): Long {
            val mark = TimeSource.Monotonic.markNow()
            store.findTaskIdsByWorkerOrTag(workerClassName = "W")
            return mark.elapsedNow().inWholeMicroseconds.coerceAtLeast(1)
        }

        // Warm up so neither measurement pays one-off costs the other does not.
        scan(small); scan(large)
        val smallCost = scan(small)
        val largeCost = scan(large)

        val ratio = largeCost.toDouble() / smallCost.toDouble()
        assertTrue(
            ratio < 10.0,
            "4x the files cost ${ratio}x the time — linear would be ~4x, quadratic ~16x " +
                "(small=${smallCost}us large=${largeCost}us)"
        )
    }

    /**
     * The progress buffer exists to collapse rapid saves into one coordinated write. A
     * thousand saves for one chain must therefore cost about one write, not a thousand —
     * this is the property that makes `saveChainProgress` cheap enough to call after every
     * chain step.
     */
    @Test
    fun rapidProgressSavesCollapseIntoOneWrite() = runTest {
        val chains = dir("chains")
        val store = ChainProgressStore(io(), scope, Json { ignoreUnknownKeys = true }) { chains }

        val mark = TimeSource.Monotonic.markNow()
        repeat(1_000) { step ->
            store.saveChainProgress(
                ChainProgress(chainId = "hot-chain", totalSteps = 1_000, completedSteps = listOf(step))
            )
        }
        val buffered = mark.elapsedNow()
        store.flushNow()

        assertEquals(
            listOf(999),
            store.loadChainProgress("hot-chain")?.completedSteps,
            "the last write must win"
        )
        assertTrue(
            buffered.inWholeMilliseconds < 2_000,
            "1,000 buffered saves took ${buffered.inWholeMilliseconds}ms — that is disk " +
                "latency per save, i.e. the debounce is not coalescing"
        )
    }

    // ---- Concurrency and stress -----------------------------------------------------

    /**
     * Distinct chains flush through one shared buffer and one mutex. Concurrent writers must
     * not drop each other's records — the failure mode is silent, and it costs a chain its
     * resume point.
     */
    @Test
    fun concurrentProgressWritersDoNotLoseRecords() = runTest {
        val chains = dir("concurrent-chains")
        val store = ChainProgressStore(io(), scope, Json { ignoreUnknownKeys = true }) { chains }
        val chainCount = 60

        withContext(Dispatchers.Default) {
            (0 until chainCount).map { i ->
                async {
                    repeat(5) { step ->
                        store.saveChainProgress(
                            ChainProgress(
                                chainId = "chain-$i",
                                totalSteps = 5,
                                completedSteps = (0..step).toList()
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        store.flushNow()

        (0 until chainCount).forEach { i ->
            val loaded = store.loadChainProgress("chain-$i")
            assertNotNull(loaded, "chain-$i was lost by a concurrent flush")
            assertEquals(5, loaded.totalSteps, "chain-$i was corrupted")
        }
    }

    /**
     * The metadata store has no mutex of its own — each write is one coordinated file
     * operation on its own path. That is only safe if distinct ids never share a file, so
     * this asserts the property directly under concurrency rather than trusting it.
     */
    @Test
    fun concurrentMetadataWritersEachKeepTheirOwnRecord() = runTest {
        val store = metadataStore(dir("mt-tasks"), dir("mt-periodic"))
        val count = 120

        withContext(Dispatchers.Default) {
            (0 until count).map { i ->
                async { store.saveTaskMetadata("task-$i", mapOf("owner" to "$i"), periodic = false) }
            }.awaitAll()
        }

        (0 until count).forEach { i ->
            assertEquals(
                "$i",
                store.loadTaskMetadata("task-$i", periodic = false)?.get("owner"),
                "task-$i lost or overwritten by a concurrent writer"
            )
        }
        assertEquals(count, store.listOneTimeTaskIdsDecoded().toList().size)
    }

    /**
     * Load-sensitive: a larger, longer-running version of the concurrency check, plus the
     * definition store, run together so the two share a filesystem the way they do in
     * production. Gated because its timing sensitivity makes it flaky on loaded runners.
     */
    @Test
    fun storesStayConsistentUnderSustainedConcurrentLoad() = runTest {
        if (StressTests.skip("Storage stores under sustained concurrent load")) return@runTest

        val chains = dir("stress-chains")
        val progress = ChainProgressStore(io(), scope, Json { ignoreUnknownKeys = true }) { chains }
        val sharedIo = io()
        val definitions = ChainDefinitionStore(
            io = sharedIo,
            maintenance = StorageMaintenance(
                io = sharedIo,
                baseDir = { root },
                maintenanceTimestampFile = { root.safeAppend("last_maintenance.txt") },
                diskSpaceBufferBytes = 0L,
                isTestMode = true
            ),
            persistenceJson = Json { ignoreUnknownKeys = true },
            chainsDir = { chains },
            deletedChainsDir = { dir("stress-deleted") },
            maxChainSizeBytes = 10_485_760L,
            deletedMarkerMaxAgeMs = 7L * 24 * 60 * 60 * 1000,
            deleteProgress = { id -> progress.deleteChainProgress(id) }
        )
        val chainCount = 200

        withContext(Dispatchers.Default) {
            (0 until chainCount).map { i ->
                async {
                    definitions.saveChainDefinition(
                        "chain-$i",
                        listOf(listOf(TaskRequest(workerClassName = "W$i")))
                    )
                    repeat(10) { step ->
                        progress.saveChainProgress(
                            ChainProgress(
                                chainId = "chain-$i",
                                totalSteps = 10,
                                completedSteps = (0..step).toList()
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        progress.flushNow()

        (0 until chainCount).forEach { i ->
            assertNotNull(definitions.loadChainDefinition("chain-$i"), "definition chain-$i lost")
            assertNotNull(progress.loadChainProgress("chain-$i"), "progress chain-$i lost")
        }
        assertEquals(
            chainCount,
            definitions.listChainDefinitionIds().toList().size,
            "the definition listing must see every chain, and no progress sidecar"
        )
    }
}
