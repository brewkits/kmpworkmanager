# Migration to v3.3.0 — Koin removal

**TL;DR:** `kmpWorkerModule()` is gone. Call `KmpWorkManager.initialize()` instead.
If you use Koin or Hilt, keep using it — just expose the scheduler from your own module.

Raised in [discussion #66](https://github.com/brewkits/kmpworkmanager/discussions/66).

## Why

`koin-core:4.0.0` shipped at `runtime` scope in every published artifact, so consumers who
never touched Koin still carried it on the classpath and in the iOS klib link. On iOS it was
worse than dead weight: `kmpWorkerModule()` was the only documented init path, and it was
declared as

```kotlin
expect fun kmpWorkerModule(...): org.koin.core.module.Module
```

— a public function returning a type from an `implementation`-scoped dependency, which forced
consumers to declare `koin-core` themselves just to call it.

The module never used a DI-container feature: no scopes, no qualifiers, no `parametersOf`, no
lazy graph. It is now a plain internal registry of `by lazy` singletons on each platform, and
Koin is gone from the published metadata.

## Android

Most apps are already on `KmpWorkManager.initialize()` — `kmpWorkerModule()` has been
`@Deprecated` on Android since 2.2.2. If you were still using it:

```kotlin
// before
startKoin {
    androidContext(this@MyApp)
    modules(kmpWorkerModule(workerFactory = MyWorkerFactory(), config = config))
}

// after
KmpWorkManager.initialize(
    context = this@MyApp,
    workerFactory = MyWorkerFactory(),
    config = config
)
```

## iOS

This is the real change — `kmpWorkerModule()` was the only documented path.

```kotlin
// before
val iosModule = module {
    includes(kmpWorkerModule(
        workerFactory = IosWorkerFactory(),
        config = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG_LEVEL)
    ))
}

// after — no DI framework needed
KmpWorkManager.initialize(
    workerFactory = IosWorkerFactory(),
    config = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG_LEVEL)
)
```

Everything the module used to do at construction time still happens eagerly inside
`initialize()`: logger setup, runtime config, the `IosWorkerFactory` type check, and the
`Info.plist → BGTaskSchedulerPermittedIdentifiers` validation for KSP-generated factories.

The services the Swift side needs are on the instance:

```kotlin
val kmp = KmpWorkManager.getInstance()
kmp.backgroundTaskScheduler
kmp.singleTaskExecutor      // -> IosBackgroundTaskHandler.handleSingleTask
kmp.chainExecutor           // -> IosBackgroundTaskHandler.handleChainExecutorTask
kmp.dynamicTaskDispatcher   // -> IosBackgroundTaskHandler.handleMasterDispatcherTask
```

## Still want Koin?

Nothing stops you — bring your own dependency and wrap the entry point. This is the pattern the
sample app uses:

```kotlin
val iosModule = module {
    KmpWorkManager.initialize(workerFactory = IosWorkerFactory())

    single<BackgroundTaskScheduler> { KmpWorkManager.getInstance().backgroundTaskScheduler }
    single<ChainExecutor> { KmpWorkManager.getInstance().chainExecutor }
}
```

Hilt is the same idea:

```kotlin
@Provides @Singleton
fun scheduler(): BackgroundTaskScheduler =
    KmpWorkManager.getInstance().backgroundTaskScheduler
```

The library deliberately does **not** ship a `kmpworkmanager-koin` bridge artifact. Once
`initialize()` exists, the wrapper above is three lines of your own code; publishing a module
× 4 targets × signing × Central upload to save those three lines is a permanent maintenance
cost for a one-time convenience.

## Bug fixed along the way

Porting the wiring surfaced a latent iOS bug worth knowing about if you relied on execution
history.

`EventStore` and `ExecutionHistoryStore` were plain lazy `single { }` bindings on iOS, and the
side effects that publish them globally (`TaskEventManager.initialize(...)`,
`KmpWorkManagerRuntime.setHistoryStore(...)`) only ran *if something resolved them* — and
nothing in the library or the sample app ever did.

So unless your own code explicitly pulled `ExecutionHistoryStore` or `EventStore` out of Koin,
`KmpWorkManagerRuntime.executionHistoryStore` stayed `null` on iOS, workers dropped every record
through the `?.save(record)` null-safe call, and `getExecutionHistory()` returned an empty list.
Android was correct via `createdAtStart = true`. If you *did* resolve either binding yourself,
you were unaffected — and you still are, since both are now created for you.

Both stores are now created eagerly during `initialize()` on both platforms.
`V330KoinFreeInitTest` (iOS) and `V330AndroidRegistryTest` (Android) assert it, including that
`TaskEventManager` holds the *same* instance the registry hands out.

## Removed API

| Removed | Replacement |
|---|---|
| `kmpWorkerModule(workerFactory, config, iosTaskIds)` | `KmpWorkManager.initialize(...)` |
| `kmpWorkerCoreModule(scheduler, workerFactory)` | `KmpWorkManager.initialize(...)` |
| `io.insert-koin:koin-core` (transitive) | — no longer a dependency |
| `io.insert-koin:koin-android` (transitive) | — no longer a dependency |
