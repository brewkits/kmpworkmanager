package dev.brewkits.kmpworkmanager.background.data

import java.util.zip.CRC32

/**
 * Stable, collision-resistant PendingIntent request codes derived from task IDs.
 *
 * `String.hashCode()` is a 32-bit polynomial that collides frequently for typical UUID/name
 * strings (birthday paradox: ~50% collision at ~65k tasks). CRC32 uses the IEEE 802.3
 * polynomial with much better distribution and is available on every Android API level.
 *
 * The sign bit is masked so the value is non-negative, which avoids confusing the Android
 * PendingIntent system and makes log output easier to read.
 *
 * Used by both [NativeTaskScheduler] (when arming alarms) and [AlarmBootReceiver] (when
 * restoring alarms after reboot). Keeping a single derivation in one place is what makes
 * `FLAG_UPDATE_CURRENT` actually resolve to the same PendingIntent slot across processes.
 */
internal object PendingIntentCodes {
    fun forTaskId(id: String): Int {
        val crc = CRC32()
        crc.update(id.toByteArray(Charsets.UTF_8))
        return (crc.value and 0x7FFFFFFFL).toInt()
    }
}
