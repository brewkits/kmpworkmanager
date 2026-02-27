@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Time utilities for iOS tests
 * Uses platform.Foundation.NSDate for iOS-specific tests
 */

/**
 * Get current time in milliseconds since epoch
 * Uses NSDate for iOS compatibility (avoids Kotlin/Native compiler bug with Clock.System)
 */
@OptIn(ExperimentalForeignApi::class)
internal fun currentTimeMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}

/**
 * Measure execution time of a block
 * @return Pair of (result, durationMs)
 */
internal inline fun <T> measureTimeMillis(block: () -> T): Pair<T, Long> {
    val start = currentTimeMillis()
    val result = block()
    val duration = currentTimeMillis() - start
    return Pair(result, duration)
}
