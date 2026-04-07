package dev.brewkits.kmpworkmanager.background.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_async
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import kotlin.coroutines.CoroutineContext

/**
 * Custom dispatchers for iOS background work.
 */
internal object IosDispatchers {
    
    /**
     * A dispatcher for I/O-bound tasks (File, Network).
     * 
     * On iOS, Kotlin/Native's [Dispatchers.Default] has a very limited thread pool.
     * Since [IosFileCoordinator] and other file operations use synchronous blocking 
     * APIs, running them on Default can easily lead to thread starvation.
     * 
     * This dispatcher offloads work to a GCD global background queue, which is 
     * optimized by the OS for concurrent I/O.
     */
    val IO: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
            dispatch_async(queue) {
                block.run()
            }
        }
    }
}
