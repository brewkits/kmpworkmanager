package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory

/**
 * WorkManager [WorkerFactory] that creates [KmpWorker] and [KmpHeavyWorker] with
 * a directly injected [AndroidWorkerFactory], eliminating the Service Locator
 * (`KmpWorkManagerKoin.getKoin().get()`) anti-pattern.
 *
 * **Automatic setup:** When `KmpWorkManager.initialize()` is called and WorkManager has
 * not yet been initialized by the host app, this factory is registered automatically via
 * a [androidx.work.DelegatingWorkerFactory].
 *
 * **Manual setup (host app manages WorkManager init):**
 * If your app already calls `WorkManager.initialize()`, add this factory to your
 * [androidx.work.DelegatingWorkerFactory] before initializing WorkManager:
 *
 * ```kotlin
 * val delegating = DelegatingWorkerFactory()
 * delegating.addFactory(KmpWorkerFactory(MyWorkerFactory()))
 * // add your own factories here …
 *
 * WorkManager.initialize(
 *     context,
 *     Configuration.Builder()
 *         .setWorkerFactory(delegating)
 *         .build()
 * )
 *
 * KmpWorkManager.initialize(context, workerFactory = MyWorkerFactory())
 * ```
 *
 * Returns `null` for any class name that is not managed by this library, which causes
 * WorkManager to fall through to the next factory in the delegate chain.
 */
class KmpWorkerFactory(
    private val androidWorkerFactory: AndroidWorkerFactory
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        KmpWorker::class.java.name ->
            KmpWorker(appContext, workerParameters, androidWorkerFactory)
        KmpHeavyWorker::class.java.name ->
            KmpHeavyWorker(appContext, workerParameters, androidWorkerFactory)
        else -> null
    }
}
