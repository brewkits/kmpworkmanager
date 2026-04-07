package dev.brewkits.kmpworkmanager.utils

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS implementation of currentTimeMillis.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}
