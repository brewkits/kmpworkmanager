# KMP WorkManager Development Roadmap

This document outlines the development roadmap for KMP WorkManager, focusing on reliability, developer experience, and enterprise features.

## Released Versions

### ✅ v2.1.1 - Critical Fixes & iOS Transparency (January 20, 2026)

**Status**: Released

**Key Changes**:
- Fixed `GlobalScope` usage in `AppendOnlyQueue` compaction
- Fixed race condition in `ChainExecutor.isShuttingDown` access
- Added `ExactAlarmIOSBehavior` enum for transparent iOS exact alarm handling

**Impact**:
- Better lifecycle management and testability
- Thread-safe shutdown operations
- Explicit handling of iOS exact alarm limitations

**Documentation**: See [CHANGELOG.md](CHANGELOG.md#211---2026-01-20)

---

### ✅ v2.1.0 - Performance & Graceful Shutdown (January 20, 2026)

**Status**: Released

**Key Changes**:
- iOS queue operations 13-40x faster with O(1) append-only queue
- Graceful shutdown for iOS BGTask expiration
- Fixed storage migration bugs

**Impact**:
- Significant performance improvements for iOS
- More reliable background task execution
- Better handling of system resource constraints

**Documentation**: See [CHANGELOG.md](CHANGELOG.md#210---2026-01-20)

---

### ✅ v2.0.1 - Concurrency & Stability Fixes (January 19, 2026)

**Status**: Released

**Key Changes**:
- Fixed thread-safety issues in iOS
- Fixed ANR risk in Android AlarmReceiver
- Improved ExistingPolicy.KEEP logic

**Documentation**: See [CHANGELOG.md](CHANGELOG.md#201---2026-01-19)

---

### ✅ v2.0.0 - Group ID Migration (January 15, 2026)

**Status**: Released
**Breaking Change**: Maven artifact migrated from `io.brewkits` to `dev.brewkits`

**Documentation**: See [CHANGELOG.md](CHANGELOG.md#200---2026-01-15)

---

## Upcoming Versions

### v2.2.0 - FileCoordinationStrategy & BGTaskHelper (Q1 2026)

**Status**: Planned
**Priority**: Medium
**Estimated Release**: February 2026

**Goals**:
1. Professional test environment detection
2. Simplified Swift integration
3. Improved DX for iOS developers

**Technical Debt Resolution**:

#### 1. FileCoordinationStrategy Pattern
**Problem**: Current `IosFileStorage` uses magic string "test.kexe" to detect test environment
**Solution**: Dependency Injection with Strategy Pattern
```kotlin
interface FileCoordinationStrategy {
    fun <T> coordinated(url: NSURL, write: Boolean, block: () -> T): T
}

class ProductionFileCoordinationStrategy : FileCoordinationStrategy
class TestFileCoordinationStrategy : FileCoordinationStrategy

internal class IosFileStorage(
    private val coordinationStrategy: FileCoordinationStrategy = ProductionFileCoordinationStrategy()
)
```

**Benefits**:
- Clean, testable code without magic strings
- Extensible for future coordination strategies (iCloud, Network, etc.)
- Proper test coverage of real file coordination behavior

#### 2. BGTaskHelper Wrapper
**Problem**: Users must manually implement complex `expirationHandler` logic in Swift
**Solution**: Kotlin wrapper for BGTask handling
```kotlin
class BGTaskHelper(private val chainExecutor: ChainExecutor) {
    fun handleBGTaskExpiration(task: BGTask) {
        // Centralized logic - user just calls this from Swift
    }
}
```

**Swift Integration (Before)**:
```swift
task.expirationHandler = {
    Task {
        do {
            try await chainExecutor.requestShutdown()
            // ... complex logic ...
        } catch {
            // ... error handling ...
        }
    }
    task.setTaskCompleted(success: false)
}
```

**Swift Integration (After)**:
```swift
task.expirationHandler = {
    bgTaskHelper.handleBGTaskExpiration(task: task)
}
```

**Benefits**:
- Reduces user boilerplate from 10+ lines to 1 line
- Updates automatically with library versions
- Less error-prone for developers

**Deliverables**:
- [ ] Implement `FileCoordinationStrategy` interface and implementations
- [ ] Update `IosFileStorage` to use strategy pattern
- [ ] Create `BGTaskHelper` wrapper class
- [ ] Update Swift demo app to use new helper
- [ ] Update documentation and migration guide
- [ ] Add comprehensive tests for both features

---

### v2.3.0 - Event Persistence & Smart Retries (Q2 2026)

**Status**: Planned
**Priority**: High
**Estimated Release**: March 2026

**Event Persistence System**:
- Persistent storage for `TaskCompletionEvent` (survives app kills)
- Automatic event replay on app launch
- Zero event loss guarantee
- SQLDelight on Android, file-based on iOS

**Smart Retry Policies**:
- Error-aware retry strategies (network failures vs. business logic errors)
- Exponential backoff with jitter
- Circuit breaker patterns
- Configurable max retry limits per task type

**Platform Capabilities API**:
```kotlin
expect object PlatformCapabilities {
    val supportsExactTiming: Boolean
    val supportsChargingConstraint: Boolean
    val maxTaskDuration: Duration
    val maxChainLength: Int
}
```

**Deliverables**:
- [ ] Design event persistence schema
- [ ] Implement SQLDelight storage for Android
- [ ] Implement file-based storage for iOS
- [ ] Create retry policy DSL
- [ ] Add platform capabilities detection
- [ ] Write comprehensive tests
- [ ] Update documentation

---

### v2.4.0 - Typed Results & Enhanced Observability (Q3 2026)

**Status**: Planning
**Priority**: Medium
**Estimated Release**: May 2026

**Typed Result Data Passing**:
```kotlin
sealed class WorkResult {
    data class Success(val data: JsonElement?) : WorkResult()
    data class Failure(val error: WorkError, val shouldRetry: Boolean) : WorkResult()
}

interface Worker {
    suspend fun doWork(input: String?): WorkResult  // Changed from Boolean
}
```

**Task Execution History & Analytics**:
- Query past task executions and results
- Task statistics: success rate, average duration, failure patterns
- Optional SQLDelight persistence with configurable retention
- Production monitoring and debugging support

**Advanced Testing Support**:
- Test workers with simulated delays and failures
- Verify retry logic without actual time delays
- Mock platform schedulers for unit testing

**Deliverables**:
- [ ] Design `WorkResult` sealed class hierarchy
- [ ] Migrate `Worker` interface to return `WorkResult`
- [ ] Create execution history storage
- [ ] Build analytics query API
- [ ] Develop testing utilities
- [ ] Migration guide for v2.x → v2.4.0

---

### v3.0.0 - Enhanced Constraints & Cross-Chain Dependencies (Q4 2026)

**Status**: Research
**Priority**: Medium
**Estimated Release**: August 2026

**Enhanced Constraint System**:
- Location-based constraints (geofencing)
- Time-window constraints (run only between 2AM-4AM)
- Custom constraint predicates
- Battery percentage thresholds

**Cross-Chain Dependencies**:
```kotlin
scheduler.enqueue(
    id = "report-generation",
    dependsOn = listOf("data-sync", "analytics-processing"),
    trigger = TaskTrigger.OneTime()
)
```

**Performance Monitoring**:
- Built-in performance metrics (execution time, memory usage)
- APM integration (Firebase Performance, Datadog, etc.)
- Automatic anomaly detection

**Deliverables**:
- [ ] Research platform constraint capabilities
- [ ] Design constraint API
- [ ] Implement dependency graph system
- [ ] Add performance monitoring hooks
- [ ] Integration examples for popular APM tools

---

## Future Considerations (Post v3.0.0)

### Web Support (WebAssembly/JS)
- Extend to Web platform using Web Workers
- ServiceWorker integration for PWAs
- Background Sync API support

### Desktop Support (JVM/Native)
- macOS background support
- Windows task scheduler integration
- Linux systemd/cron integration

### Advanced Scheduling
- Cron-like expressions for complex scheduling
- Calendar integration (run task on first Monday of month)
- Dynamic scheduling based on external events

### Cloud Integration
- Firebase Cloud Messaging integration for remote task triggering
- AWS Amplify DataStore sync integration
- Cloud-based task queue integration

---

## Version Numbering

We follow [Semantic Versioning](https://semver.org/):

- **Major (X.0.0)**: Breaking changes, API redesigns
- **Minor (x.X.0)**: New features, backward-compatible
- **Patch (x.x.X)**: Bug fixes, no new features

---

## Contributing to the Roadmap

Have suggestions or feature requests? Please:

1. Open an issue with the `[Feature Request]` label
2. Describe the use case and problem it solves
3. Provide examples of the proposed API

Community feedback helps shape our priorities!

---

## Support

- **GitHub Issues**: [github.com/brewkits/kmpworkmanager/issues](https://github.com/brewkits/kmpworkmanager/issues)
- **Documentation**: [github.com/brewkits/kmpworkmanager](https://github.com/brewkits/kmpworkmanager)

---

Last Updated: January 20, 2026
