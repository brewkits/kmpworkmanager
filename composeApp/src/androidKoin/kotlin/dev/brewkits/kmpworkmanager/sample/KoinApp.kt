package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import android.util.Log
import dev.brewkits.kmpworkmanager.koin.kmpWorkerModule
import dev.brewkits.kmpworkmanager.sample.workers.SampleWorkerFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Koin Application - v2.1.0 Demo (Koin DI Integration).
 *
 * This demonstrates Koin integration:
 * - Uses kmpworkmanager-koin extension module
 * - 100% backward compatible with v2.0.0
 * - Familiar Koin API for existing users
 *
 * Perfect for:
 * - Apps already using Koin
 * - Teams familiar with Koin patterns
 * - Projects wanting DI benefits
 */
class KoinApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Koin with KMP WorkManager module
        startKoin {
            androidContext(this@KoinApp)
            modules(kmpWorkerModule(SampleWorkerFactory()))
        }

        Log.i(TAG, "✅ KMP WorkManager v2.1.0 initialized")
        Log.i(TAG, "📦 Approach: KOIN Extension Module")
        Log.i(TAG, "💡 Benefits: Familiar API, backward compatible with v2.0.0")
    }

    companion object {
        private const val TAG = "KoinApp"
    }
}
