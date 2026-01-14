# Event Persistence System Design

**Status:** Design Phase
**Issue:** #1
**Target:** v1.1.0
**Priority:** P0

---

## Problem Statement

Current `TaskEventBus` uses `SharedFlow(replay=5)` which provides short-term buffering but:
- Events are lost when app is force-quit
- Events emitted while UI is not running are lost permanently
- No reliable way to retrieve task results after app restart

**Impact:** Users don't see task completion status if app was in background/terminated.

---

## Goals

### Primary Goals
1. **Zero Event Loss**: 100% delivery guarantee even after force-quit
2. **Fast Retrieval**: <100ms to get unconsumed events on app launch
3. **Platform Consistency**: Same behavior on Android and iOS

### Non-Goals
1. Not implementing full event sourcing system
2. Not storing events indefinitely (automatic cleanup)
3. Not replacing EventBus (complementing it)

---

## Architecture

### High-Level Flow

```
┌─────────────┐
│   Worker    │
│  Completes  │
└──────┬──────┘
       │
       ├───────────┐
       │           │
       ▼           ▼
┌─────────────┐ ┌─────────────┐
│  EventBus   │ │ EventStore  │
│   (Memory)  │ │  (Disk)     │
└──────┬──────┘ └──────┬──────┘
       │               │
       │               │
       ▼               ▼
┌─────────────┐ ┌─────────────┐
│  UI (Live)  │ │ Persistence │
└─────────────┘ └─────────────┘
                      │
                      │ On App Launch
                      ▼
                ┌─────────────┐
                │  EventBus   │
                │   Replay    │
                └─────────────┘
```

### Components

#### 1. EventStore Interface (Common)
```kotlin
interface EventStore {
    suspend fun saveEvent(event: TaskCompletionEvent): String
    suspend fun getUnconsumedEvents(): List<StoredEvent>
    suspend fun markEventConsumed(eventId: String)
    suspend fun clearOldEvents(olderThanMs: Long): Int
}
```

#### 2. StoredEvent Data Model
```kotlin
data class StoredEvent(
    val id: String,              // UUID
    val event: TaskCompletionEvent,
    val timestamp: Long,         // Millis since epoch
    val consumed: Boolean = false
)
```

#### 3. Platform Implementations

**Android: SQLDelight**
- Use SQLite database via SQLDelight
- Schema:
  ```sql
  CREATE TABLE events (
      id TEXT PRIMARY KEY,
      task_name TEXT NOT NULL,
      success INTEGER NOT NULL,
      message TEXT NOT NULL,
      timestamp INTEGER NOT NULL,
      consumed INTEGER NOT NULL DEFAULT 0
  );

  CREATE INDEX idx_consumed_timestamp ON events(consumed, timestamp);
  ```

**iOS: File-based (IosFileStorage)**
- Reuse existing `IosFileStorage` infrastructure
- Store events as JSON in separate directory
- File naming: `event_{timestamp}_{uuid}.json`

---

## Implementation Plan

### Phase 1: Core Interface & Android Implementation (3 days)

**Day 1: Setup**
- [ ] Add SQLDelight dependency to build.gradle.kts
- [ ] Create EventStore.kt interface
- [ ] Design SQL schema
- [ ] Create StoredEvent data class

**Day 2: Android Implementation**
- [ ] Implement AndroidEventStore with SQLDelight
- [ ] Write CRUD operations
- [ ] Add cleanup logic
- [ ] Unit tests for Android implementation

**Day 3: Integration**
- [ ] Integrate EventStore with TaskEventBus
- [ ] Add EventSyncManager for app launch
- [ ] Integration tests

### Phase 2: iOS Implementation (2 days)

**Day 4: iOS Storage**
- [ ] Implement IosEventStore using IosFileStorage
- [ ] JSON serialization for events
- [ ] Atomic file operations
- [ ] Unit tests for iOS implementation

**Day 5: Cross-platform Testing**
- [ ] Integration tests on both platforms
- [ ] Performance testing (<100ms target)
- [ ] Stress testing (1000+ events)

---

## API Design

### Integration with EventBus

**Current:**
```kotlin
// Worker completes
TaskEventBus.emit(event) // ❌ Lost on force-quit
```

**After:**
```kotlin
// Worker completes
object TaskEventManager {
    suspend fun emit(event: TaskCompletionEvent) {
        // 1. Save to persistent storage
        eventStore.saveEvent(event)

        // 2. Emit to EventBus for live UI
        TaskEventBus.emit(event)
    }
}
```

**App Launch:**
```kotlin
// In Application.onCreate() or @main
object EventSyncManager {
    suspend fun syncEvents() {
        val missedEvents = eventStore.getUnconsumedEvents()

        missedEvents.forEach { storedEvent ->
            TaskEventBus.emit(storedEvent.event)
        }
    }
}
```

**UI Consumption:**
```kotlin
// In ViewModel
TaskEventBus.events.collect { event ->
    // Process event
    updateUI(event)

    // Mark as consumed
    eventStore.markEventConsumed(event.id) // Need eventId in flow
}
```

---

## Data Flow Examples

### Scenario 1: Normal Operation (App Running)

```
1. Worker completes task
   └─> TaskEventManager.emit()
       ├─> eventStore.saveEvent() [disk write ~5ms]
       └─> TaskEventBus.emit()    [in-memory]
2. UI receives event immediately
   └─> eventStore.markEventConsumed()
```

**Timeline:** ~10ms total

### Scenario 2: App in Background

```
1. Worker completes task (BGTask running)
   └─> TaskEventManager.emit()
       ├─> eventStore.saveEvent() [disk write ~5ms]
       └─> TaskEventBus.emit()    [no subscribers, lost]
2. User opens app
   └─> EventSyncManager.syncEvents()
       ├─> eventStore.getUnconsumedEvents() [<100ms]
       └─> Replay to TaskEventBus
3. UI receives event
   └─> eventStore.markEventConsumed()
```

**Timeline:** 100ms on app launch

### Scenario 3: Force-Quit During Task

```
1. Worker completes task
   └─> TaskEventManager.emit()
       ├─> eventStore.saveEvent() ✅ [persisted]
       └─> TaskEventBus.emit()    [lost]
2. App force-quit immediately
3. User opens app hours later
   └─> EventSyncManager.syncEvents()
       ├─> eventStore.getUnconsumedEvents()
       └─> Event recovered! ✅
```

**Result:** Zero event loss

---

## Performance Considerations

### Write Performance
- **Target:** <10ms per event
- **Strategy:**
  - Batch writes for multiple events
  - Async I/O (suspend functions)
  - No blocking on emit

### Read Performance
- **Target:** <100ms for getUnconsumedEvents()
- **Strategy:**
  - Index on `consumed` and `timestamp`
  - Limit to 100 most recent unconsumed events
  - Cache results for repeated calls

### Storage Size
- **Limit:** 1000 events max
- **Average Event Size:** ~200 bytes
- **Total Storage:** ~200 KB maximum
- **Cleanup:** Auto-delete consumed events after 1 hour

---

## Error Handling

### Write Failures
```kotlin
try {
    eventStore.saveEvent(event)
} catch (e: Exception) {
    Logger.e("EventStore", "Failed to save event", e)
    // Still emit to EventBus (best effort)
    TaskEventBus.emit(event)
}
```

### Read Failures
```kotlin
try {
    val events = eventStore.getUnconsumedEvents()
} catch (e: Exception) {
    Logger.e("EventStore", "Failed to retrieve events", e)
    // Continue app launch, events lost but app functional
}
```

### Corruption Recovery
- Detect corrupted events during read
- Log error and skip corrupted entries
- Auto-cleanup corrupted files

---

## Testing Strategy

### Unit Tests
- [ ] EventStore CRUD operations
- [ ] Cleanup logic (old events)
- [ ] Max events enforcement
- [ ] Serialization/deserialization

### Integration Tests
- [ ] End-to-end flow: emit → store → retrieve → consume
- [ ] Force-quit simulation
- [ ] App restart simulation
- [ ] Concurrent writes

### Performance Tests
- [ ] 1000 events stress test
- [ ] Write latency (<10ms)
- [ ] Read latency (<100ms)
- [ ] Memory usage monitoring

### Platform Tests
- [ ] Android SQLite behavior
- [ ] iOS file storage behavior
- [ ] Cross-platform consistency

---

## Migration Plan

### Backward Compatibility
- No breaking changes to existing APIs
- EventBus continues to work independently
- Opt-in via configuration

### Rollout Strategy
1. **v1.1.0-alpha**: Android implementation only
2. **v1.1.0-beta**: iOS implementation added
3. **v1.1.0-rc**: Production testing
4. **v1.1.0**: Stable release

### Configuration
```kotlin
// Enable event persistence (default: true in v1.1.0)
KmpWorkerConfig.eventPersistence = true

// Optional: custom config
KmpWorkerConfig.eventStoreConfig = EventStoreConfig(
    maxEvents = 500,
    consumedEventRetentionMs = 1_800_000L // 30 minutes
)
```

---

## Dependencies

### Android
```kotlin
dependencies {
    implementation("app.cash.sqldelight:android-driver:2.0.1")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")
}
```

### iOS
```kotlin
// No new dependencies - use existing IosFileStorage
```

### Common
```kotlin
dependencies {
    implementation("app.cash.sqldelight:runtime:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
```

---

## Success Metrics

### Functional
- [ ] 0% event loss in force-quit scenarios
- [ ] Events survive app restart
- [ ] UI receives all events even when launched later

### Performance
- [ ] Write latency <10ms (p95)
- [ ] Read latency <100ms (p95)
- [ ] Storage <1MB

### Reliability
- [ ] No crashes from EventStore
- [ ] Graceful degradation on errors
- [ ] Auto-recovery from corruption

---

## Open Questions

1. **Event ID Propagation**: How to pass eventId to UI for `markEventConsumed()`?
   - Option A: Change TaskCompletionEvent to include id (breaking change)
   - Option B: Use correlation via (taskName + timestamp) (fragile)
   - **Decision:** Option A - add optional id field with default null

2. **Cleanup Frequency**: When to run auto-cleanup?
   - Option A: Every write (low overhead, distributed)
   - Option B: App launch only (batched, might delay startup)
   - **Decision:** Option A with probabilistic triggering (10% of writes)

3. **iOS BGTaskScheduler Integration**: Should ChainExecutor use EventStore?
   - Yes - for consistency
   - Implement in separate PR to avoid scope creep

---

## Next Steps

1. **Review & Approval**: Team review of this design
2. **Prototype**: Build minimal Android implementation
3. **Benchmark**: Validate performance targets
4. **Implement**: Full implementation following plan
5. **Test**: Comprehensive testing
6. **Document**: Update user-facing docs

---

**Author:** Core Team
**Created:** 2026-01-14
**Status:** Ready for Implementation
