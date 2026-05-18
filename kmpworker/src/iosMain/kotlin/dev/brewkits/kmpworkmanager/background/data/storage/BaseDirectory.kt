@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.brewkits.kmpworkmanager.background.data.storage

import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDomainMask

/**
 * Wraps the on-disk root path used by every iOS storage component.
 *
 * Stage 0 of the SRP split (P1.1 — see `docs/internal/IOS_FILE_STORAGE_SPLIT.md`).
 *
 * Today this only exists to fix the `null = AppSupport / non-null = test override`
 * branching that is scattered through `IosFileStorage`. The next stages will move
 * the individual stores into this `storage` package, each one taking a
 * `BaseDirectory` as a constructor argument so the wiring is explicit instead of
 * relying on a nullable parameter.
 */
public class BaseDirectory internal constructor(internal val root: NSURL) {

    public companion object {
        /**
         * Resolves to `Library/Application Support/dev.brewkits.kmpworkmanager` under
         * the host app's sandbox. This is the production default and matches what
         * the legacy `IosFileStorage(baseDirectory = null)` resolves to.
         */
        public fun appSupport(): BaseDirectory {
            val fm = NSFileManager.defaultManager
            val urls = fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            val appSupport = (urls.first() as NSURL)
                .URLByAppendingPathComponent("dev.brewkits.kmpworkmanager")
                ?: error("Failed to resolve Application Support directory")
            return BaseDirectory(appSupport)
        }

        /**
         * Wraps an arbitrary directory — useful for tests that need an isolated
         * filesystem root or for host apps that want to live outside Application
         * Support (e.g. an App Group container shared with extensions).
         */
        public fun at(url: NSURL): BaseDirectory = BaseDirectory(url)
    }
}
