package dev.brewkits.kmpworkmanager.background.data

import android.content.pm.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pin-down tests for [KmpHeavyWorker]'s foreground service type aliases.
 *
 * Before v2.5 the FGS type was hardcoded to `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
 * Camera apps that needed `mediaProcessing` (Android 15+ transcoding) had no
 * extension point — overriding the worker meant copy-pasting the entire
 * `createForegroundInfo()` body, which we want to discourage.
 *
 * v2.5 exposes:
 *   - `protected open val foregroundServiceType: Int` (default = DATA_SYNC)
 *   - `companion object` constants `FGS_DATA_SYNC`, `FGS_MEDIA_PROCESSING`, …
 *
 * These tests assert:
 *  1. Each public alias resolves to the right `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`.
 *  2. `FGS_MEDIA_PROCESSING` equals the real
 *     `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (0x2000 / 8192).
 *     Until v3.5.0 both the constant and this assertion said `0x1000` (4096),
 *     and this comment actively told future PRs not to "fix" it. That was wrong:
 *     4096 is not any publicly-declarable FGS type, so Android 15 rejected every
 *     worker using the alias. Assert against the platform constant, not a literal,
 *     so the two can never drift apart again.
 *  3. The aliases are distinct integers (no copy-paste typo collapsing two
 *     constants to the same value).
 *
 * **Why not Robolectric?** The constants are pure compile-time integers
 * exported by `android.content.pm.ServiceInfo`. No Context, no Manifest, no
 * lifecycle — a plain JVM unit test is enough.
 */
class KmpHeavyWorkerFgsTypeTest {

    @Test
    fun fgsDataSync_isServiceInfoDataSync() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, KmpHeavyWorker.FGS_DATA_SYNC)
    }

    @Test
    fun fgsMediaPlayback_isServiceInfoMediaPlayback() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, KmpHeavyWorker.FGS_MEDIA_PLAYBACK)
    }

    @Test
    fun fgsCamera_isServiceInfoCamera() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA, KmpHeavyWorker.FGS_CAMERA)
    }

    @Test
    fun fgsLocation_isServiceInfoLocation() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, KmpHeavyWorker.FGS_LOCATION)
    }

    @Test
    fun fgsConnectedDevice_isServiceInfoConnectedDevice() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, KmpHeavyWorker.FGS_CONNECTED_DEVICE)
    }

    @Test
    fun fgsMediaProcessing_matchesPlatformConstant() {
        // The project builds at compileSdk = 36, so the API 35 constant IS on the compile
        // classpath. Assert against it rather than a literal — that is what stops the two
        // from drifting apart, which is exactly how the 4096 bug survived three releases.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            KmpHeavyWorker.FGS_MEDIA_PROCESSING,
        )
        // Belt-and-braces on the actual number, so a platform-constant regression is loud.
        assertEquals(0x2000, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
        assertEquals(8192, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
        // Regression guard: the pre-v3.5.0 value was not merely stale, it was never a valid
        // FGS type at all.
        assertNotEquals(4096, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
    }

    @Test
    fun allFgsAliases_areDistinct() {
        // Guard against a copy-paste typo where two constants end up with the same value.
        val all = listOf(
            KmpHeavyWorker.FGS_DATA_SYNC,
            KmpHeavyWorker.FGS_MEDIA_PLAYBACK,
            KmpHeavyWorker.FGS_CAMERA,
            KmpHeavyWorker.FGS_LOCATION,
            KmpHeavyWorker.FGS_CONNECTED_DEVICE,
            KmpHeavyWorker.FGS_MEDIA_PROCESSING,
        )
        val distinct = all.toSet()
        assertEquals(all.size, distinct.size, "FGS aliases collide: $all")
    }

    @Test
    fun fgsMediaProcessing_isNotDataSync() {
        // The whole point of v2.5: a camera-app transcoder MUST NOT silently fall back
        // to dataSync. This pins the distinction.
        assertNotEquals(KmpHeavyWorker.FGS_DATA_SYNC, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
        assertTrue(KmpHeavyWorker.FGS_MEDIA_PROCESSING > 0, "FGS type must be a positive bit value")
    }
}
