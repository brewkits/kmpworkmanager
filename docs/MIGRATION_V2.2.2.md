# Migration Guide: v2.2.1 → v2.2.2

**Version:** 2.2.2
**Date:** 2026-02-05
**Type:** Fully Backward Compatible

---

## 📋 Summary

**Good News:** v2.2.2 is **100% backward compatible** with v2.2.1. No code changes are required!

**What's New:**
- 16 critical/high/medium bug fixes
- Enhanced configuration options
- New lifecycle methods
- Improved testing support

**Recommended Actions:**
1. Update dependency
2. Review new configuration options
3. Add optional enhancements
4. Run tests

---

## 1️⃣ Update Dependency

### build.gradle.kts (Kotlin)
```kotlin
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.2.2")  // Was: 2.2.1
}
```

### build.gradle (Groovy)
```groovy
dependencies {
    implementation 'dev.brewkits:kmpworkmanager:2.2.2'  // Was: 2.2.1
}
```

---

## 2️⃣ No Breaking Changes

**Your existing code continues to work without modifications!**

```kotlin
// v2.2.1 code (still works in v2.2.2)
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory()
)

val chain = TaskChain(
    id = "my-chain",
    steps = listOf(/* ... */)
)

WorkerApi.enqueueChain(chain)
```

---

## 3️⃣ Optional: Enable New Features

### 🔧 Production Log Configuration (Recommended)

**Problem Solved:** Reduce log noise in production builds

**Before (v2.2.1):** All logs (VERBOSE to ERROR) always shown
```kotlin
// Logs everything - clutters production logs
KmpWorkManager.initialize(context, workerFactory)
```

**After (v2.2.2):** Configurable log level
```kotlin
// Production: Only WARN and ERROR
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory(),
    config = KmpWorkManagerConfig(
        logLevel = Logger.Level.WARN
    )
)

// Development: Full logging
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory(),
    config = KmpWorkManagerConfig(
        logLevel = Logger.Level.DEBUG_LEVEL
    )
)
```

**Example: BuildConfig-based configuration**
```kotlin
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory(),
    config = KmpWorkManagerConfig(
        logLevel = if (BuildConfig.DEBUG) {
            Logger.Level.DEBUG_LEVEL
        } else {
            Logger.Level.WARN
        }
    )
)
```

---

### 📊 Custom Logger for Analytics (Optional)

**Problem Solved:** Send logs to Firebase, Sentry, or custom analytics

**Implementation:**
```kotlin
// 1. Implement CustomLogger interface
class AnalyticsLogger : CustomLogger {
    override fun log(
        level: Logger.Level,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        when (level) {
            Logger.Level.ERROR -> {
                // Send errors to crash reporting
                FirebaseCrashlytics.getInstance().log("ERROR: [$tag] $message")
                throwable?.let { FirebaseCrashlytics.getInstance().recordException(it) }
            }
            Logger.Level.WARN -> {
                // Track warnings in analytics
                FirebaseAnalytics.getInstance().logEvent("worker_warning", bundleOf(
                    "tag" to tag,
                    "message" to message
                ))
            }
            else -> {
                // Standard console logging
                println("[$tag] $message")
            }
        }
    }
}

// 2. Set custom logger
Logger.setCustomLogger(AnalyticsLogger())

// 3. (Optional) Reset to default
Logger.setCustomLogger(null)
```

---

### 🧪 Clean Shutdown for Testing (Recommended)

**Problem Solved:** Tests failed when reinitializing KmpWorkManager

**Before (v2.2.1):** No way to clean up Koin
```kotlin
@Test
fun testMultipleInitializations() {
    KmpWorkManager.initialize(context, factory1)
    // ❌ Can't reinitialize - Koin already started
}
```

**After (v2.2.2):** Call `shutdown()` between tests
```kotlin
@After
fun tearDown() {
    KmpWorkManager.shutdown()  // Clean up Koin resources
}

@Test
fun testInitialization1() {
    KmpWorkManager.initialize(context, factory1)
    // ... test logic
}

@Test
fun testInitialization2() {
    // Works! Previous test cleaned up
    KmpWorkManager.initialize(context, factory2)
    // ... test logic
}
```

**Application Lifecycle (Optional):**
```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(this, MyWorkerFactory())
    }

    override fun onTerminate() {
        super.onTerminate()
        KmpWorkManager.shutdown()  // Clean shutdown
    }
}
```

---

### 🍎 iOS: Custom File Storage Config (Optional)

**Problem Solved:**
- Hardcoded 100MB disk space check too aggressive
- Fixed cleanup age
- Inflexible test detection

**Before (v2.2.1):** No configuration
```kotlin
val storage = IosFileStorage()  // Uses hardcoded defaults
```

**After (v2.2.2):** Configurable
```kotlin
val config = IosFileStorageConfig(
    diskSpaceBufferBytes = 25_000_000L,       // 25MB (default: 50MB)
    deletedMarkerMaxAgeMs = 3_600_000L,       // 1 hour (default: 7 days)
    isTestMode = true,                         // Explicit test mode
    fileCoordinationTimeoutMs = 15_000L        // 15s timeout (default: 30s)
)

val storage = IosFileStorage(config)
```

**Common Scenarios:**

**Low-storage devices:**
```kotlin
IosFileStorageConfig(
    diskSpaceBufferBytes = 10_000_000L  // 10MB buffer
)
```

**Aggressive cleanup:**
```kotlin
IosFileStorageConfig(
    deletedMarkerMaxAgeMs = 1_800_000L  // 30 minutes
)
```

**Test environment:**
```kotlin
IosFileStorageConfig(
    isTestMode = true  // Skip NSFileCoordinator in tests
)

// Or set environment variable:
// KMPWORKMANAGER_TEST_MODE=1
```

---

### 🤖 Android: Event Store Cleanup (Automatic)

**Problem Solved:** Probabilistic cleanup (10% random) was unreliable

**Before (v2.2.1):**
- 10% chance of cleanup on each write
- Could go hours without cleanup

**After (v2.2.2):**
- Deterministic cleanup based on time OR file size
- Guaranteed to run

**Default Behavior (No Action Needed):**
```kotlin
// Cleanup triggers when:
// - 5 minutes elapsed since last cleanup, OR
// - File size exceeds 1MB
val store = AndroidEventStore(context)  // Uses smart defaults
```

**Custom Configuration (Optional):**
```kotlin
val config = EventStoreConfig(
    autoCleanup = true,
    cleanupIntervalMs = 600_000L,              // 10 minutes
    cleanupFileSizeThresholdBytes = 2_097_152L // 2MB
)

val store = AndroidEventStore(context, config)
```

---

## 4️⃣ Behavioral Changes (Fixes Only)

### These bugs are now fixed automatically:

| Issue | v2.2.1 Behavior | v2.2.2 Behavior |
|-------|-----------------|-----------------|
| UTF-8 emoji in task names (iOS) | Corrupted/truncated | Works correctly |
| Concurrent KmpWorkManager.initialize() | Crash | Thread-safe |
| Progress loss during iOS shutdown | Possible | Prevented |
| Logger config race | Data race | Thread-safe |
| Large Android event files | OOM crash | Streaming (no OOM) |
| Event store cleanup | Unreliable (10%) | Deterministic |

**You don't need to change anything - these fixes are automatic!**

---

## 5️⃣ Testing Your Migration

### Automated Tests

**Run existing tests:**
```bash
./gradlew test
```

**Expected:** All tests pass (100% backward compatible)

---

### Manual Testing Checklist

- [ ] **Basic functionality**
  - [ ] Tasks enqueue successfully
  - [ ] Workers execute correctly
  - [ ] Chains complete as expected

- [ ] **Log level filtering** (if configured)
  - [ ] Production shows only WARN/ERROR
  - [ ] Development shows DEBUG/INFO

- [ ] **Custom logger** (if configured)
  - [ ] Logs reach analytics service
  - [ ] Errors tracked in crash reporting

- [ ] **Shutdown lifecycle** (if used)
  - [ ] `shutdown()` completes without errors
  - [ ] Can reinitialize after shutdown

- [ ] **UTF-8 edge cases** (iOS)
  - [ ] Task names with emoji work
  - [ ] Chinese/Arabic/Cyrillic text handled

- [ ] **Large event files** (Android)
  - [ ] No OOM with 1000+ events
  - [ ] Event cleanup runs reliably

---

## 6️⃣ Common Migration Scenarios

### Scenario 1: Basic App (No Changes Needed)

**Your code:**
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KmpWorkManager.initialize(this, MyWorkerFactory())
    }
}
```

**Action:** ✅ **None!** Just update dependency and you're done.

---

### Scenario 2: Production App (Add Log Config)

**Add log configuration:**
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        KmpWorkManager.initialize(
            context = this,
            workerFactory = MyWorkerFactory(),
            config = KmpWorkManagerConfig(
                logLevel = if (BuildConfig.DEBUG) {
                    Logger.Level.DEBUG_LEVEL
                } else {
                    Logger.Level.WARN  // Production: less noise
                }
            )
        )
    }
}
```

**Benefit:** Cleaner production logs, faster performance

---

### Scenario 3: Testing Framework (Add Shutdown)

**Add test cleanup:**
```kotlin
@RunWith(AndroidJUnit4::class)
class MyWorkerTest {

    @After
    fun tearDown() {
        KmpWorkManager.shutdown()  // Clean up between tests
    }

    @Test
    fun testWorker() {
        KmpWorkManager.initialize(context, TestWorkerFactory())
        // ... test logic
    }
}
```

**Benefit:** Reliable test isolation

---

### Scenario 4: Analytics Integration (Add Custom Logger)

**Implement custom logger:**
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager
        KmpWorkManager.initialize(
            context = this,
            workerFactory = MyWorkerFactory(),
            config = KmpWorkManagerConfig(logLevel = Logger.Level.WARN)
        )

        // Set custom logger
        Logger.setCustomLogger(object : CustomLogger {
            override fun log(level: Logger.Level, tag: String, message: String, throwable: Throwable?) {
                if (level == Logger.Level.ERROR) {
                    Crashlytics.log("$tag: $message")
                    throwable?.let { Crashlytics.recordException(it) }
                }
            }
        })
    }
}
```

**Benefit:** Automatic crash reporting

---

## 7️⃣ Troubleshooting

### Issue: "Tests still fail with Koin already started"

**Solution:** Make sure to call `shutdown()` in test cleanup
```kotlin
@After
fun tearDown() {
    KmpWorkManager.shutdown()
}
```

---

### Issue: "Still seeing too many logs in production"

**Solution:** Verify log level configuration
```kotlin
// Check initialization
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory(),
    config = KmpWorkManagerConfig(
        logLevel = Logger.Level.WARN  // Make sure this is set
    )
)
```

---

### Issue: "UTF-8 characters still corrupted (iOS)"

**Solution:** Make sure you're on v2.2.2 (not v2.2.1)
```bash
# Check gradle dependency
./gradlew dependencies | grep kmpworkmanager

# Should show: dev.brewkits:kmpworkmanager:2.2.2
```

---

## 8️⃣ Rollback Plan (If Needed)

If you encounter issues, you can safely rollback to v2.2.1:

```kotlin
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.2.1")
}
```

**Note:** v2.2.2 has no breaking changes, so rollback is safe but **not recommended** (you'll lose bug fixes).

---

## 9️⃣ Getting Help

**Found an issue?**
1. Check [Release Notes](./V2.2.2_RELEASE_NOTES.md)
2. Search [GitHub Issues](https://github.com/brewkits/kmpworkmanager/issues)
3. Create new issue with:
   - v2.2.2 tag
   - Minimal reproduction
   - Stack trace (if crash)

**Need clarification?**
- [API Documentation](./API.md)
- [GitHub Discussions](https://github.com/brewkits/kmpworkmanager/discussions)

---

## 🎉 Summary

v2.2.2 is a **drop-in replacement** for v2.2.1 with:
- ✅ Zero breaking changes
- ✅ 16 critical bug fixes
- ✅ Optional new features
- ✅ Improved stability and performance

**Recommended steps:**
1. Update dependency to 2.2.2
2. (Optional) Add log level configuration
3. (Optional) Add shutdown() to test cleanup
4. Run tests to verify
5. Deploy with confidence!

**Estimated migration time:** 5-15 minutes (mostly configuration)

---

**Questions?** Open an issue or discussion on GitHub.
