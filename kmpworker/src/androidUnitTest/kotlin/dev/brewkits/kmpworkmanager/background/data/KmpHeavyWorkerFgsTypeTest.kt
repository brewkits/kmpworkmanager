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
 *  2. `FGS_MEDIA_PROCESSING` is the documented `0x1000` literal — `ServiceInfo`
 *     doesn't expose the constant on AOSP API ≤ 34, so we keep the literal in
 *     the companion object and pin it here so an accidental "fix" by a future
 *     PR (e.g. moving to `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`)
 *     doesn't silently change the wire value.
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
    fun fgsMediaProcessing_isAndroid15Literal() {
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING is API 35+ and not exposed
        // on the compile classpath at compileSdk=34. The constant value per AOSP is 0x1000
        // (4096). Pin it so a future "cleanup" doesn't change the on-wire value.
        assertEquals(0x1000, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
        assertEquals(4096, KmpHeavyWorker.FGS_MEDIA_PROCESSING)
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
