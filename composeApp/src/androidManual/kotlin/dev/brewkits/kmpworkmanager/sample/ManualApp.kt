package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import android.util.Log
import dev.brewkits.kmpworkmanager.WorkerManagerInitializer
import dev.brewkits.kmpworkmanager.sample.workers.SampleWorkerFactory

/**
 * Manual Application - v2.1.0 Demo (No DI Framework).
 *
 * This demonstrates the simplest initialization approach:
 * - No dependency injection framework required
 * - Direct WorkerManagerInitializer.initialize() call
 * - Zero external dependencies (besides the core library)
 *
 * Perfect for:
 * - New projects wanting minimal dependencies
 * - Lightweight apps
 * - Testing without DI complexity
 */
class ManualApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize KMP WorkManager manually - no DI needed!
        WorkerManagerInitializer.initialize(
            workerFactory = SampleWorkerFactory(),
            context = this
        )

        Log.i(TAG, "✅ KMP WorkManager v2.1.0 initialized")
        Log.i(TAG, "📦 Approach: MANUAL (No DI Framework)")
        Log.i(TAG, "💡 Benefits: Zero DI dependencies, simple setup")
    }

    companion object {
        private const val TAG = "ManualApp"
    }
}
