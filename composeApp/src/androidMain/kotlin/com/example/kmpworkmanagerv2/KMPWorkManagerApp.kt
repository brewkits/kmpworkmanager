package com.example.kmpworkmanagerv2

import android.app.Application
import com.example.kmpworkmanagerv2.background.data.NativeTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.di.initKoin
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
        }

        initKoin {
            androidLogger()
            androidContext(this@KMPWorkManagerApp)
            modules(androidModule)
        }
    }
}