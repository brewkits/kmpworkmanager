package dev.brewkits.kmpworkmanager.utils

import dev.brewkits.kmpworkmanager.background.data.IosDispatchers
import kotlinx.coroutines.CoroutineDispatcher

/**
 * iOS implementation of [AppDispatchers].
 */
actual object AppDispatchers {
    actual val IO: CoroutineDispatcher = IosDispatchers.IO
}
