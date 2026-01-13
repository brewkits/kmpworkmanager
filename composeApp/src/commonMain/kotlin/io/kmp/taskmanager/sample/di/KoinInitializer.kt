package io.kmp.taskmanager.sample.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * Advanced Koin initialization function for Multiplatform targets (Android, iOS)
 * that require both common and platform-specific dependencies.
 *
 * @param platformModule The Koin module containing platform-specific implementations.
 * @return The initialized KoinApplication instance.
 */
fun initKoin(platformModule: Module): KoinApplication {
    return startKoin {
        // Load both the shared common dependencies and the platform-specific dependencies.
        modules(commonModule, platformModule)
    }
}