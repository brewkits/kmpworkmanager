@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.data.IosFileStorage
import dev.brewkits.kmpworkmanager.background.data.IosFileStorageConfig
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.data.reconstructConstraintsFromMetadata
import dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.SystemConstraint
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closes **I-25**: `preserveFieldsLostByReenqueue` is a hand-maintained list of three keys,
 * and nothing failed if a fourth key was added elsewhere and not listed there.
 *
 * ### Why this needs a test rather than review
 *
 * A one-time task's metadata is rebuilt from scratch on every retry and every constraint
 * deferral: `IosBackgroundTaskHandler.handleOneTimeTaskResult` calls `scheduler.enqueue(...)`
 * with `ExistingPolicy.REPLACE`, and `NativeTaskScheduler.scheduleOneTimeTask` writes a fresh
 * `buildMap` that only knows about what that call passed. Anything the caller stored that the
 * rebuild does not re-derive is gone — permanently, for the rest of that task's life.
 *
 * Two mechanisms carry state across that rebuild:
 *
 * 1. **Re-derived from `Constraints`** — `reconstructConstraintsFromMetadata` reads the
 *    constraint keys back out of the old metadata and hands them to `enqueue`, which writes
 *    them again through `putStandaloneConstraintMetadata`.
 * 2. **Copied forward explicitly** — `preserveFieldsLostByReenqueue` (plus the attempt
 *    counter, handled at each call site) restores the keys `Constraints` has no room for.
 *
 * A new key belongs to one of those two mechanisms. Added to neither, it is silently dropped,
 * and the symptom surfaces much later as a task that lost its deadline, its tags, or its
 * window — with nothing pointing back at the commit that added the key.
 *
 * ### What this test actually does
 *
 * It runs the real rebuild — a genuine `NativeTaskScheduler` writing to a real
 * `IosFileStorage`, re-enqueued exactly the way the retry path does it — and compares the
 * metadata before and after. The set of keys lost across that rebuild is then asserted to be
 * *exactly* the set `preserveFieldsLostByReenqueue` restores.
 *
 * So it fails in both directions, which is the point:
 *
 * - Add a metadata key that the rebuild does not re-derive, and the lost set grows beyond the
 *   preserved list — the test names the key and says where to add it.
 * - Make the rebuild re-derive a key that is currently copied forward, and the lost set
 *   shrinks — the preserve list has a stale entry to delete.
 */
class ReenqueueMetadataPreservationTest {

    /**
     * The keys `preserveFieldsLostByReenqueue` copies forward, plus the attempt counter that
     * its two call sites restore alongside it. This list is the thing under test: it is
     * asserted against what the rebuild actually loses, not trusted.
     */
    private val explicitlyPreservedKeys = setOf(
        "windowLatest",
        DynamicTaskDispatcher.META_TAGS,
        DynamicTaskDispatcher.META_DEADLINE_MS,
        DynamicTaskDispatcher.META_ATTEMPT_COUNT
    )

    /**
     * Keys that a re-enqueue drops **and that nothing reads back**, so dropping them is
     * currently harmless.
     *
     * `windowEarliest` is written by `scheduleWindowedTask` and read by nothing in the
     * codebase — it is a record of the original window for a human reading the file, not an
     * input to any decision. Preserving it would be cargo-cult: copying a value forward for
     * no reader.
     *
     * It is listed here rather than merged into the assertion so that the day something
     * *does* read it, this entry is the note explaining that it does not survive a retry.
     * If you add a reader, move the key to [explicitlyPreservedKeys] and to
     * `preserveFieldsLostByReenqueue`.
     */
    private val lostButUnreadKeys = setOf("windowEarliest")

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun makeStorage(tag: String): IosFileStorage {
        val name = "kmp_reenqueue_${tag}_${(NSDate().timeIntervalSince1970 * 1000).toLong()}" +
            "_${platform.posix.rand()}"
        val url = NSURL.fileURLWithPath("${NSTemporaryDirectory()}$name")
        NSFileManager.defaultManager.createDirectoryAtURL(
            url,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        return IosFileStorage(config = IosFileStorageConfig(isTestMode = true), baseDirectory = url)
    }

    /** Every constraint field that has a metadata representation, all set to a non-default. */
    private fun everyConstraintSet() = Constraints(
        requiresNetwork = true,
        requiresUnmeteredNetwork = true,
        requiresCharging = true,
        isHeavyTask = true,
        backoffPolicy = BackoffPolicy.LINEAR,
        backoffDelayMs = 45_000L,
        systemConstraints = setOf(
            SystemConstraint.REQUIRE_BATTERY_NOT_LOW,
            SystemConstraint.ALLOW_LOW_BATTERY
        ),
        maxRetries = 7
    )

    /**
     * The core invariant. Everything a one-time task can carry is set, the task is re-enqueued
     * the way a retry does it, and the keys that did not survive are compared against the
     * hand-maintained preserve list.
     */
    @Test
    fun `every metadata key lost by a re-enqueue is on the preserve list`() = runTest {
        val storage = makeStorage("full-set")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "reenqueue-full"

        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Windowed(earliest = 0L, latest = nowMs() + 3_600_000L),
            workerClassName = "com.example.SyncWorker",
            constraints = everyConstraintSet(),
            inputJson = """{"k":"v"}""",
            policy = ExistingPolicy.REPLACE,
            tags = setOf("alpha", "beta"),
            deadlineMs = nowMs() + 7_200_000L
        )

        val before = storage.loadTaskMetadata(taskId, periodic = false).orEmpty().toMutableMap()
        // The attempt counter is written by the retry path rather than at schedule time; add
        // it so the comparison covers it the way a real retry would.
        before[DynamicTaskDispatcher.META_ATTEMPT_COUNT] = "3"
        storage.saveTaskMetadata(taskId, before, periodic = false)

        assertTrue(
            before.containsKey("windowLatest"),
            "test setup is wrong: a Windowed task must persist windowLatest, got ${before.keys}"
        )

        // Exactly what IosBackgroundTaskHandler does on a retry or a constraint deferral.
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "com.example.SyncWorker",
            constraints = reconstructConstraintsFromMetadata(before),
            inputJson = """{"k":"v"}""",
            policy = ExistingPolicy.REPLACE
        )

        val after = storage.loadTaskMetadata(taskId, periodic = false).orEmpty()
        val lost = before.keys - after.keys

        assertEquals(
            explicitlyPreservedKeys + lostButUnreadKeys,
            lost,
            "The keys a re-enqueue drops must match preserveFieldsLostByReenqueue exactly.\n" +
                "  before = ${before.keys.sorted()}\n" +
                "  after  = ${after.keys.sorted()}\n" +
                "If a key appears in 'lost' but not in the preserve list, the rebuild is " +
                "silently discarding it: add it to preserveFieldsLostByReenqueue in " +
                "IosBackgroundTaskHandler, and to explicitlyPreservedKeys here. If a key is " +
                "on the preserve list but no longer lost, the rebuild now re-derives it and " +
                "the preserve entry is stale."
        )
    }

    /**
     * The other half of the same invariant: everything the rebuild *does* re-derive must come
     * back with the value it had, not merely with the key present. A constraint that
     * round-trips as a default is as lost as one that is missing — worse, because it looks
     * intact.
     */
    @Test
    fun `constraints survive a re-enqueue with their values intact`() = runTest {
        val storage = makeStorage("values")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "reenqueue-values"

        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "com.example.SyncWorker",
            constraints = everyConstraintSet(),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )

        val before = storage.loadTaskMetadata(taskId, periodic = false).orEmpty()
        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "com.example.SyncWorker",
            constraints = reconstructConstraintsFromMetadata(before),
            inputJson = null,
            policy = ExistingPolicy.REPLACE
        )
        val after = storage.loadTaskMetadata(taskId, periodic = false).orEmpty()

        before.forEach { (key, value) ->
            if (key in explicitlyPreservedKeys || key in lostButUnreadKeys) return@forEach
            assertEquals(
                value,
                after[key],
                "'$key' changed across a re-enqueue: '$value' -> '${after[key]}'. A constraint " +
                    "that silently reverts to its default on the first retry is the bug class " +
                    "reconstructConstraintsFromMetadata exists to prevent."
            )
        }
    }

    /**
     * `reconstructConstraintsFromMetadata` is the inverse of `putStandaloneConstraintMetadata`,
     * and the round-trip must be stable: reconstructing from metadata written by a
     * reconstruction must give the same `Constraints`. An asymmetry here means a value that
     * drifts one step per retry rather than failing outright.
     */
    @Test
    fun `constraint reconstruction is idempotent`() = runTest {
        val storage = makeStorage("idempotent")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "reenqueue-idempotent"
        val original = everyConstraintSet()

        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "W",
            constraints = original,
            policy = ExistingPolicy.REPLACE
        )
        val firstPass = reconstructConstraintsFromMetadata(
            storage.loadTaskMetadata(taskId, periodic = false).orEmpty()
        )

        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(0L),
            workerClassName = "W",
            constraints = firstPass,
            policy = ExistingPolicy.REPLACE
        )
        val secondPass = reconstructConstraintsFromMetadata(
            storage.loadTaskMetadata(taskId, periodic = false).orEmpty()
        )

        assertEquals(firstPass, secondPass, "a second retry must not drift from the first")
        assertEquals(
            original.requiresUnmeteredNetwork, firstPass.requiresUnmeteredNetwork,
            "requiresUnmeteredNetwork must survive the first rebuild"
        )
        assertEquals(original.isHeavyTask, firstPass.isHeavyTask, "isHeavyTask must survive")
        assertEquals(original.maxRetries, firstPass.maxRetries, "maxRetries must survive")
        assertEquals(original.backoffPolicy, firstPass.backoffPolicy, "backoffPolicy must survive")
        assertEquals(original.backoffDelayMs, firstPass.backoffDelayMs, "backoffDelayMs must survive")
        assertEquals(
            original.systemConstraints, firstPass.systemConstraints,
            "systemConstraints must survive"
        )
    }

    /**
     * The bug this test found on its first run: `scheduleWindowedTask` persisted no
     * constraint metadata at all.
     *
     * `scheduleOneTimeTask` was fixed for exactly this in v3.4.0
     * (`V340OneTimeTaskMetadataFieldsTest`), and the Windowed path — which also uses a
     * dedicated Info.plist identifier and therefore also retries through
     * `reconstructConstraintsFromMetadata` — never got the same fix. Every Windowed task's
     * first retry reconstructed all-false constraints: a heavy task was re-submitted as a
     * `BGAppRefreshTaskRequest` (~30s ceiling) instead of a `BGProcessingTaskRequest`
     * (minutes), and the caller's `maxRetries` cap was silently replaced by the default.
     */
    @Test
    fun `a windowed task persists the constraints its retry has to rebuild from`() = runTest {
        val storage = makeStorage("windowed-constraints")
        val scheduler = NativeTaskScheduler(fileStorage = storage)
        val taskId = "windowed-heavy"

        scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.Windowed(earliest = 0L, latest = nowMs() + 3_600_000L),
            workerClassName = "com.example.HeavyWorker",
            constraints = everyConstraintSet(),
            policy = ExistingPolicy.REPLACE
        )

        val meta = storage.loadTaskMetadata(taskId, periodic = false).orEmpty()
        val rebuilt = reconstructConstraintsFromMetadata(meta)

        assertTrue(
            rebuilt.isHeavyTask,
            "a heavy Windowed task must still be heavy after a retry rebuild — otherwise its " +
                "re-submission is downgraded to a ~30s BGAppRefreshTaskRequest. meta=${meta.keys}"
        )
        assertTrue(rebuilt.requiresNetwork, "requiresNetwork must survive a Windowed retry")
        assertTrue(rebuilt.requiresCharging, "requiresCharging must survive a Windowed retry")
        assertTrue(
            rebuilt.requiresUnmeteredNetwork,
            "requiresUnmeteredNetwork must survive a Windowed retry"
        )
        assertEquals(7, rebuilt.maxRetries, "the caller's retry cap must survive a Windowed retry")
        assertEquals(
            BackoffPolicy.LINEAR, rebuilt.backoffPolicy,
            "backoffPolicy must survive a Windowed retry"
        )
        assertEquals(
            setOf(SystemConstraint.REQUIRE_BATTERY_NOT_LOW, SystemConstraint.ALLOW_LOW_BATTERY),
            rebuilt.systemConstraints,
            "systemConstraints must survive a Windowed retry"
        )
    }
}
