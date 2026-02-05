package dev.brewkits.kmpworkmanager

import android.content.Context
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.persistence.AndroidEventStore
import dev.brewkits.kmpworkmanager.persistence.EventStore
import dev.brewkits.kmpworkmanager.utils.Logger
import org.koin.dsl.module

/**
 * Android implementation of the Koin module.
 *
 * **⚠️ DEPRECATED in v2.2.2** - Use `KmpWorkManager.initialize()` instead
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 * v2.2.2+ DEPRECATED: Replaced with isolated Koin to prevent conflicts
 *
 * **Migration (v2.2.2+):**
 * ```kotlin
 * // OLD (causes Koin conflicts)
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpWorkerModule(workerFactory = MyWorkerFactory()))
 * }
 *
 * // NEW (isolated Koin, no conflicts)
 * KmpWorkManager.initialize(
 *     context = this@Application,
 *     workerFactory = MyWorkerFactory(),
 *     config = KmpWorkManagerConfig(logLevel = Logger.Level.INFO)
 * )
 * ```
 *
 * @param workerFactory User-provided factory implementing AndroidWorkerFactory
 * @param config Configuration for logging and other settings
 * @param iosTaskIds Ignored on Android (iOS-only parameter)
 */
@Deprecated(
    message = "Use KmpWorkManager.initialize() instead to avoid Koin conflicts",
    replaceWith = ReplaceWith(
        "KmpWorkManager.initialize(context, workerFactory, config)",
        "dev.brewkits.kmpworkmanager.KmpWorkManager"
    ),
    level = DeprecationLevel.WARNING
)
actual fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    config: KmpWorkManagerConfig,
    iosTaskIds: Set<String>
) = module {
    // This module is now deprecated
    // For backward compatibility, we still provide the dependencies
    // but users should migrate to KmpWorkManager.initialize()

    Logger.w(
        "KmpWorkManager",
        """
        ⚠️ DEPRECATED: kmpWorkerModule() is deprecated in v2.2.2

        Using global Koin can cause conflicts with your app's Koin instance.

        Please migrate to:
        KmpWorkManager.initialize(
            context = this@Application,
            workerFactory = MyWorkerFactory(),
            config = KmpWorkManagerConfig(logLevel = Logger.Level.INFO)
        )

        See MIGRATION.md for details.
        """.trimIndent()
    )

    // Initialize logger with config
    Logger.setMinLevel(config.logLevel)
    config.customLogger?.let { Logger.setCustomLogger(it) }

    single<BackgroundTaskScheduler> {
        val context = get<Context>()
        NativeTaskScheduler(context)
    }

    // Register the user's worker factory
    single<WorkerFactory> { workerFactory }
    single<AndroidWorkerFactory> {
        workerFactory as? AndroidWorkerFactory
            ?: error("WorkerFactory must implement AndroidWorkerFactory on Android")
    }

    // Event persistence
    single<EventStore> {
        val context = get<Context>()
        val store = AndroidEventStore(context)

        // Initialize TaskEventManager with the store
        TaskEventManager.initialize(store)

        store
    }
}
