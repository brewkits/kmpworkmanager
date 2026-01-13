package io.kmp.taskmanager.sample.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

/**
 * Initializes Koin for targets that only require the common module (e.g., tests, simple previews).
 *
 * @param appDeclaration Optional lambda to configure the Koin application further.
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    startKoin {
        appDeclaration()
        // Include the module containing common dependencies.
        modules(commonModule)
    }
}