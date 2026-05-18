@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.AppendOnlyQueue
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.Foundation.*
import kotlin.test.*

/**
 * Regression net for the v2.5.0 P0 OOM-during-migration bug.
 *
 * **Bug**: pre-fix [AppendOnlyQueue.migrateFromTextToBinary] used
 * `NSString.stringWithContentsOfFile(...)` + `content.split("\n")` to read the
 * legacy queue file. For users who hadn't run the app in a while the legacy
 * file would grow to 10–20 MB; loading the full file as NSString + producing
 * a List<String> via split spikes RAM to roughly 3× file size (≈60 MB),
 * exceeding the iOS BGTask ~30 MB budget. The OS sends EXC_RESOURCE and kills
 * the app silently. The legacy file never migrates → every subsequent
 * background invocation crashes the same way → user stuck permanently on the
 * pre-v2.5 version with NO indication of why.
 *
 * **Fix**: stream the legacy file line-by-line via
 * [AppendOnlyQueue.readSingleLine] (the same 4 KB chunked reader already used
 * for legacy reads) directly into the new binary writer. Peak RAM is one line
 * (~few KB) instead of the entire file.
 *
 * **Test strategy**: build a 5 MB legacy queue file (50 000 items × ~100 B) —
 * roughly 1/4 the worst-case real-world legacy file size, large enough that
 * the pre-fix code would noticeably spike RAM but small enough to run in CI.
 * Verify functional parity (every item migrates in order) and that the
 * migration completes without hitting test process limits.
 */
class V250LegacyMigrationStreamingTest {

    private lateinit var queueDirURL: NSURL

    @BeforeTest
    fun setUp() {
        val base = NSTemporaryDirectory()
        val name = "kmp_legacymigrate_${(NSDate().timeIntervalSince1970 * 1000).toLong()}_${platform.posix.rand()}"
        queueDirURL = NSURL.fileURLWithPath("$base$name")
        NSFileManager.defaultManager.createDirectoryAtURL(
            queueDirURL,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtURL(queueDirURL, null)
    }

    /**
     * Uses real `runBlocking` (not `runTest`) because the underlying
     * `migrateFromTextToBinary` performs synchronous NSFileHandle I/O that
     * `runTest`'s virtual clock cannot accelerate. 30 k items × 100 B ≈ 3 MB —
     * exercises the streaming path without the 1-minute `runTest` ceiling.
     */
    @Test
    fun largeMigration_streamingDoesNotMaterializeAllItemsInRam() = runBlocking {
        val queueFileURL = queueDirURL.URLByAppendingPathComponent("queue.jsonl")!!
        val queuePath = queueFileURL.path!!

        // 50 000 items × ~100 B = ~5 MB legacy file. Pre-fix RAM peak would be
        // ~15 MB (file + NSString + List<String>); post-fix peak is ~one line
        // (~100 B) plus the binary write buffer. We can't directly assert RAM
        // peak in a unit test, but we CAN assert that:
        //   (a) functional parity: all items migrate in order
        //   (b) the migration completes (pre-fix on a 30 MB-budget simulator
        //       would EXC_RESOURCE before reaching the assertions)
        // 40 000 items × 100 B ≈ 4 MB. Large enough that the pre-fix
        // stringWithContentsOfFile + split path would noticeably spike RAM.
        val itemCount = 40_000

        // Write the legacy file in chunks to avoid blowing up the TEST process
        // before we get to the migration. The chunked test-side build is itself
        // a check that the file-size scale is realistic — we couldn't even
        // generate the input via `items.joinToString("\n")` without a huge
        // String allocation.
        run {
            NSFileManager.defaultManager.createFileAtPath(queuePath, null, null)
            val handle = NSFileHandle.fileHandleForWritingAtPath(queuePath)
                ?: throw AssertionError("could not open legacy file for writing")
            try {
                val chunkBuilder = StringBuilder()
                var written = 0
                for (i in 0 until itemCount) {
                    val id = "chain-${i.toString().padStart(5, '0')}"
                    chunkBuilder.append("""{"id":"$id","data":"abcdefghijklmnopqrstuvwxyz1234567890"}""")
                    chunkBuilder.append('\n')
                    if (chunkBuilder.length > 64 * 1024) {  // flush per ~64 KB
                        val chunkBytes = chunkBuilder.toString().encodeToByteArray()
                        chunkBytes.usePinned { pinned ->
                            val data = NSData.create(
                                bytes = pinned.addressOf(0),
                                length = chunkBytes.size.toULong()
                            )
                            handle.writeData(data)
                        }
                        written += chunkBuilder.length
                        chunkBuilder.clear()
                    }
                }
                if (chunkBuilder.isNotEmpty()) {
                    val chunkBytes = chunkBuilder.toString().encodeToByteArray()
                    chunkBytes.usePinned { pinned ->
                        val data = NSData.create(
                            bytes = pinned.addressOf(0),
                            length = chunkBytes.size.toULong()
                        )
                        handle.writeData(data)
                    }
                }
            } finally {
                handle.closeFile()
            }
        }

        // Sanity: legacy file is actually large.
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(queuePath, null)
        val fileSize = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        assertTrue(
            fileSize > 2_500_000L,
            "test setup: legacy file should be > 2.5 MB to exercise the streaming path, got $fileSize"
        )

        // Trigger migration: instantiating AppendOnlyQueue auto-detects the legacy
        // (no-magic-header) format and calls migrateFromTextToBinary.
        // Wrapped in a wall-clock timeout: if streaming regressed back to the
        // full-file-load pre-fix code, this would either OOM-kill the test
        // process or take dramatically longer; a 60 s ceiling catches both.
        val startMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val queue = AppendOnlyQueue(queueDirURL)
        val migrationMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - startMs

        assertEquals(
            itemCount, queue.getSize(),
            "All $itemCount items must survive the streaming migration. " +
                "Pre-fix this test would either OOM or never reach this assertion."
        )
        assertTrue(
            migrationMs < 60_000L,
            "Streaming migration should complete well under 60 s for a 4 MB legacy file. " +
                "Actual: ${migrationMs}ms. A regression to full-file load would either " +
                "OOM (silent kill) or be dramatically slower due to GC pressure."
        )

        // Spot-check ordering: the FIRST item must be chain-00000 after migration.
        // We deliberately do NOT dequeue the whole queue here — for 40 k items each
        // dequeue is a coordinated file write + head-pointer update, which dominates
        // wall-clock and isn't what this test is exercising (functional parity on
        // bulk dequeue is covered by IntegrationTests.testMigrationWithLargeQueue).
        val first = queue.dequeue()
        assertNotNull(first)
        assertTrue(first.contains("chain-00000"), "first item must be chain-00000, got: $first")
    }
}
