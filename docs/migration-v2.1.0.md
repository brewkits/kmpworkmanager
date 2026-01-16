# Migration Guide: v2.0.0 → v2.1.0

## Overview

**Version 2.1.0** introduces DI-agnostic architecture - Koin is now **optional**.

### Key Changes
- ✅ Core library has **zero DI framework dependencies**
- ✅ **100% backward compatible** with v2.0.0 (with kmpworker-koin extension)
- ✅ New manual initialization API (no DI framework required)
- ✅ Koin support moved to optional `kmpworkmanager-koin` extension
- ✅ Hilt support available via optional `kmpworkmanager-hilt` extension (Android only)

---

## Migration Paths

### Option 1: Keep Using Koin (Recommended for Existing Users)

**Minimal changes** - just add one dependency:

#### Step 1: Add Koin Extension Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.1.0")
    implementation("dev.brewkits:kmpworkmanager-koin:2.1.0")  // ADD THIS
}
```

#### Step 2: No Code Changes Needed!

Your existing Koin setup continues to work:

```kotlin
// Android
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)
            modules(kmpWorkerModule(MyWorkerFactory()))  // Works exactly as before
        }
    }
}

// iOS
fun initKoin() {
    startKoin {
        modules(kmpWorkerModule(
            workerFactory = MyWorkerFactory(),
            iosTaskIds = setOf("my-task")
        ))
    }
}
```

---

### Option 2: Migrate to Manual Initialization (No DI Framework)

**Best for new projects** or if you don't use Koin elsewhere.

#### Step 1: Remove Koin Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.1.0")
    // Remove: kmpworkmanager-koin
    // Remove: koin-core, koin-android
}
```

#### Step 2: Replace Koin Setup with Manual Initialization

**Android:**

```kotlin
// Before (v2.0.0 with Koin):
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)
            modules(kmpWorkerModule(MyWorkerFactory()))
        }
    }
}

// After (v2.1.0 manual):
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkerManagerInitializer.initialize(
            workerFactory = MyWorkerFactory(),
            context = this
        )
    }
}
```

**iOS:**

```kotlin
// Before (v2.0.0 with Koin):
fun initKoin() {
    startKoin {
        modules(kmpWorkerModule(
            workerFactory = MyWorkerFactory(),
            iosTaskIds = setOf("my-task")
        ))
    }
}

// After (v2.1.0 manual):
fun initializeWorkManager() {
    WorkerManagerInitializer.initialize(
        workerFactory = MyWorkerFactory(),
        iosTaskIds = setOf("my-task")
    )
}
```

---

### Option 3: Migrate to Hilt (Android Only)

**Best for apps already using Hilt/Dagger.**

#### Step 1: Add Hilt Extension Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.1.0")
    implementation("dev.brewkits:kmpworkmanager-hilt:2.1.0")
}
```

#### Step 2: Set Up Hilt Module

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideWorkerFactory(): WorkerFactory = MyWorkerFactory()
}

@HiltAndroidApp
class MyApp : Application() {
    @Inject lateinit var initializer: WorkerManagerHiltInitializer

    override fun onCreate() {
        super.onCreate()
        initializer.initialize()
    }
}
```

---

## Breaking Changes

**None!** Version 2.1.0 is 100% backward compatible when using `kmpworkmanager-koin` extension.

---

## New APIs

### WorkerManagerConfig

Global service locator for WorkerFactory (internal - used by library):

```kotlin
object WorkerManagerConfig {
    fun initialize(factory: WorkerFactory)
    fun getWorkerFactory(): WorkerFactory
    fun isInitialized(): Boolean
    fun reset()  // Testing only
}
```

### WorkerManagerInitializer

Unified initialization API:

```kotlin
expect object WorkerManagerInitializer {
    fun initialize(
        workerFactory: WorkerFactory,
        context: Any? = null,           // Android: Context (required)
        iosTaskIds: Set<String> = emptySet()  // iOS: Task IDs (optional)
    ): BackgroundTaskScheduler

    fun getScheduler(): BackgroundTaskScheduler
    fun isInitialized(): Boolean
    fun reset()  // Testing only
}
```

---

## Troubleshooting

### "Unresolved reference: kmpWorkerModule"

**Cause**: Missing `kmpworkmanager-koin` dependency.

**Solution**: Add the dependency:
```kotlin
implementation("dev.brewkits:kmpworkmanager-koin:2.1.0")
```

### "WorkerManagerConfig not initialized"

**Cause**: Forgot to call `WorkerManagerInitializer.initialize()`.

**Solution**: Initialize in Application.onCreate() (Android) or AppDelegate (iOS):
```kotlin
WorkerManagerInitializer.initialize(
    workerFactory = MyWorkerFactory(),
    context = applicationContext  // Android only
)
```

### "WorkerFactory must implement AndroidWorkerFactory"

**Cause**: On Android, your factory must implement `AndroidWorkerFactory` (not just `WorkerFactory`).

**Solution**:
```kotlin
class MyWorkerFactory : AndroidWorkerFactory {  // Not just WorkerFactory
    override fun createWorker(workerClassName: String): AndroidWorker? {
        // ...
    }
}
```

---

## FAQ

**Q: Do I need to migrate away from Koin?**
A: No! Koin support is fully maintained in the `kmpworkmanager-koin` extension. It's just optional now.

**Q: Will my existing v2.0.0 code break?**
A: No, as long as you add the `kmpworkmanager-koin:2.1.0` dependency, everything works as before.

**Q: Should I use manual initialization or Koin?**
A:
- **Use Koin** if: You already use Koin in your app, or prefer DI frameworks
- **Use manual** if: You want zero dependencies, or building a new lightweight project
- **Use Hilt** if: You already use Hilt in your Android app

**Q: Can I mix manual and Koin initialization?**
A: No, choose one approach per app. The `WorkerManagerConfig` can only be initialized once.

---

## Next Steps

1. ✅ Choose your migration path
2. ✅ Update dependencies
3. ✅ Update initialization code (if using manual)
4. ✅ Test your app
5. ✅ Enjoy the flexibility!

For more help, see:
- [README.md](../README.md)
- [GitHub Issues](https://github.com/yourusername/kmpworkmanager/issues)
