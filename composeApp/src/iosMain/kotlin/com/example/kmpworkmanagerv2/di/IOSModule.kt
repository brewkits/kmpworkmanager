
package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.background.data.NativeTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import org.koin.dsl.module

val iosModule = module {
    single<BackgroundTaskScheduler> { NativeTaskScheduler() }
}
