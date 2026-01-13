package io.kmp.taskmanager.sample

import android.app.Application
import io.kmp.taskmanager.sample.background.data.NativeTaskScheduler
import io.kmp.taskmanager.sample.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.sample.debug.AndroidDebugSource
import io.kmp.taskmanager.sample.debug.DebugSource
import io.kmp.taskmanager.sample.di.initKoin
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