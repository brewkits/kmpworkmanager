package dev.brewkits.kmpworkmanager.utils

/**
 * Get current time in milliseconds since epoch.
 * Implemented via expect/actual to avoid Kotlin/Native compiler bugs with Clock.System.
 */
expect fun currentTimeMillis(): Long
