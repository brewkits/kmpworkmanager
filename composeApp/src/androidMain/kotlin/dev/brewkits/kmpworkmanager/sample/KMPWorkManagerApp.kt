package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import dev.brewkits.kmpworkmanager.sample.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.debug.AndroidDebugSource
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.dsl.module

/**
 * The main Application class for Android.
 * Responsible for initializing Koin and providing the Android-specific implementation
 * of the BackgroundTaskScheduler.
 */
class KMPWorkManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val androidModule = module {
            single<BackgroundTaskScheduler> { NativeTaskScheduler(androidContext()) }
            single<DebugSource> { AndroidDebugSource(androidContext()) }
        }

        initKoin {
            androidLogger()
            androidContext(this@KMPWorkManagerApp)
            modules(androidModule)
        }
    }
}