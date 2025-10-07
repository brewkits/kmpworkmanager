
package com.example.kmpworkmanagerv2.di

import org.koin.core.context.startKoin

import org.koin.core.module.Module

fun initKoin(platformModule: Module) {
    startKoin {
        modules(commonModule, platformModule)
    }
}
