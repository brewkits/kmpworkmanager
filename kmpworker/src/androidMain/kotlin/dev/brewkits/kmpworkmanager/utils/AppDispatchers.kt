package dev.brewkits.kmpworkmanager.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Android implementation of [AppDispatchers].
 */
actual object AppDispatchers {
    actual val IO: CoroutineDispatcher = Dispatchers.IO
}
