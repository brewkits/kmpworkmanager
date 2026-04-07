package dev.brewkits.kmpworkmanager.utils

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-agnostic dispatchers for background work.
 */
expect object AppDispatchers {
    /**
     * Dispatcher for I/O-bound tasks (File, Network).
     *
     * - Android: Maps to Dispatchers.IO
     * - iOS: Maps to a custom GCD-backed dispatcher for blocking I/O
     */
    val IO: CoroutineDispatcher
}
