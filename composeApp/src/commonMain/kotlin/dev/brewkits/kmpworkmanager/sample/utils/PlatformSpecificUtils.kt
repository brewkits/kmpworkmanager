package dev.brewkits.kmpworkmanager.sample.utils

expect fun createDummyFiles(context: Any): Pair<String, String>
expect fun getDummyDownloadPath(context: Any): String
expect fun getDummyUploadPath(context: Any): String
expect fun getDummyCompressionInputPath(context: Any): String
expect fun getDummyCompressionOutputPath(context: Any): String

/**
 * Checks whether [identifier] resolves to a real App Group container on this platform —
 * a read-only probe, never calls `KmpWorkManager.initialize()` again (it's a one-shot
 * singleton; a second call is a no-op, so re-invoking it from a UI button couldn't
 * demonstrate anything about App Group setup either way).
 *
 * Always returns a fixed "not applicable" message on Android — App Group storage is an
 * iOS-only feature (see `docs/IOS_APP_GROUP_STORAGE.md`).
 */
expect fun checkAppGroupContainerStatus(identifier: String): String
