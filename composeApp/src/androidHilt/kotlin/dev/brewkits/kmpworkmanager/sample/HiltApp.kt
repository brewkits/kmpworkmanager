package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import android.util.Log
import dev.brewkits.kmpworkmanager.WorkerManagerInitializer
import dev.brewkits.kmpworkmanager.sample.workers.SampleWorkerFactory

/**
 * Hilt Application - v2.1.0 Demo (Hilt DI Integration - Future).
 *
 * NOTE: kmpworkmanager-hilt extension is not yet implemented.
 * This flavor currently uses manual initialization as a placeholder.
 *
 * Future implementation will demonstrate:
 * - @HiltAndroidApp annotation
 * - Hilt module for WorkerFactory
 * - Native Hilt/Dagger integration
 *
 * Perfect for:
 * - Android apps using Hilt/Dagger
 * - Enterprise projects with existing Hilt infrastructure
 * - Teams familiar with Dagger patterns
 */
class HiltApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // TODO: Replace with Hilt initialization when kmpworkmanager-hilt is ready
        // For now, use manual initialization
        WorkerManagerInitializer.initialize(
            workerFactory = SampleWorkerFactory(),
            context = this
        )

        Log.i(TAG, "✅ KMP WorkManager v2.1.0 initialized")
        Log.i(TAG, "📦 Approach: HILT (Placeholder - using manual init)")
        Log.i(TAG, "⚠️  kmpworkmanager-hilt extension coming soon!")
    }

    companion object {
        private const val TAG = "HiltApp"
    }
}
