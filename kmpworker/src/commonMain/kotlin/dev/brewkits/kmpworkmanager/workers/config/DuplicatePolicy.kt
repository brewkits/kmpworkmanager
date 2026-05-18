package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * What to do when the destination file already exists at `savePath` before a download
 * starts.
 *
 * Default is [OVERWRITE] to preserve pre-v2.5 behaviour. New code that handles media
 * gallery / cache scenarios should pick [SKIP] or [RENAME] explicitly — silently
 * overwriting a file the user already imported is a frequent footgun.
 */
@Serializable
enum class DuplicatePolicy {
    /**
     * Replace the file at `savePath` with the freshly downloaded one. Pre-v2.5 default
     * and the only behaviour available before this enum existed.
     */
    OVERWRITE,

    /**
     * Pick a new save path by appending `_1`, `_2`, … before the file extension until
     * a non-existing path is found. The original file is left in place. Useful for
     * media imports where the user expects every download to land as a distinct file.
     */
    RENAME,

    /**
     * Do not touch the existing file and return `WorkerResult.Success` immediately —
     * no HTTP request is issued. Useful for resumable batch imports where a previous
     * run already finished some files.
     */
    SKIP
}
