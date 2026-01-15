# 🗺️ KMP WorkManager Roadmap v1.1.0

**Target Release:** Q1 2026 (February 2026)
**Focus:** Reliability & iOS Improvements

---

## 📋 Overview

Version 1.1.0 focuses on fixing critical reliability issues discovered through production usage and research analysis, particularly around iOS chain execution and event delivery.

---

## 🎯 Goals

1. **Zero Event Loss**: 100% event delivery guarantee
2. **Chain Resilience**: Resume capability for interrupted chains
3. **Better Documentation**: Clear iOS limitations guide

---

## 🚀 Features & Improvements

### 1. Event Persistence System

**Status:** 🔴 Not Started
**Priority:** P0 - Critical
**Estimated Effort:** 5 days
**Assignee:** TBD

**Problem:**
Current EventBus uses `SharedFlow(replay=0)`, causing events to be lost if UI is not actively listening when background tasks complete.

**Solution:**
Implement persistent event store with SQLite/SQLDelight:

```kotlin
interface EventStore {
    suspend fun saveEvent(event: TaskCompletionEvent): String // Returns eventId
    suspend fun getUnconsumedEvents(): List<TaskCompletionEvent>
    suspend fun markEventConsumed(eventId: String)
    suspend fun clearOldEvents(olderThanMs: Long)
}
```

**Implementation Plan:**
1. Add SQLDelight dependency
2. Create event database schema
3. Implement EventStore with IosFileStorage fallback
4. Integrate with TaskEventBus
5. Add EventSyncManager for app launch sync
6. Write integration tests

**Success Criteria:**
- [x] 0% event loss in stress test (1000 events)
- [x] <100ms latency for retrieving missed events
- [x] Works after app force-quit and restart

**Files to Modify:**
- `kmpworker/src/commonMain/kotlin/dev.brewkits/kmpworkmanager/background/domain/TaskCompletionEvent.kt`
- New: `kmpworker/src/commonMain/kotlin/dev.brewkits/kmpworkmanager/persistence/EventStore.kt`
- New: `kmpworker/src/iosMain/kotlin/dev.brewkits/kmpworkmanager/persistence/IosEventStore.kt`

**Related Issues:** #1

---

### 2. iOS Chain State Restoration

**Status:** 🔴 Not Started
**Priority:** P1 - High
**Estimated Effort:** 7 days
**Assignee:** TBD

**Problem:**
When iOS BGTask times out or is terminated, chains restart from step 1, wasting resources and time.

**Current Behavior:**
```kotlin
// ChainExecutor.kt:166
catch (e: TimeoutCancellationException) {
    fileStorage.deleteChainDefinition(chainId) // ❌ Loses all progress
    return false
}
```

**Solution:**
Save chain progress after each completed step:

```kotlin
data class ChainProgress(
    val chainId: String,
    val totalSteps: Int,
    val completedSteps: List<Int>, // [0, 1] means steps 0 and 1 done
    val lastFailedStep: Int?,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)
```

**Implementation Plan:**
1. Design ChainProgress data model
2. Extend IosFileStorage with progress methods:
   ```kotlin
   fun saveChainProgress(chainId: String, progress: ChainProgress)
   fun loadChainProgress(chainId: String): ChainProgress?
   fun deleteChainProgress(chainId: String)
   ```
3. Modify ChainExecutor.executeChain():
   ```kotlin
   val progress = fileStorage.loadChainProgress(chainId)
   val startIndex = progress?.let { it.lastFailedStep ?: it.completedSteps.lastOrNull()?.plus(1) } ?: 0

   for (index in startIndex until steps.size) {
       val stepSuccess = executeStep(steps[index])
       if (stepSuccess) {
           fileStorage.saveChainProgress(chainId, progress.copy(
               completedSteps = progress.completedSteps + index
           ))
       } else {
           fileStorage.saveChainProgress(chainId, progress.copy(
               lastFailedStep = index,
               retryCount = progress.retryCount + 1
           ))
           break
       }
   }
   ```
4. Add retry limit handling
5. Write unit tests for resume logic

**Success Criteria:**
- [x] Chain with 5 steps, interrupted at step 3, resumes from step 3
- [x] Retry limit prevents infinite loops
- [x] <5% performance overhead
- [x] Progress files cleaned up after completion

**Files to Modify:**
- `kmpworker/src/iosMain/kotlin/dev.brewkits/kmpworkmanager/background/data/ChainExecutor.kt`
- `kmpworker/src/iosMain/kotlin/dev.brewkits/kmpworkmanager/background/data/IosFileStorage.kt`

**Related Issues:** #2

---

### 3. iOS Limitations Documentation

**Status:** 🟡 In Progress
**Priority:** P0 - Critical
**Estimated Effort:** 3 days
**Assignee:** TBD

**Problem:**
Developers coming from Android assume iOS has same capabilities, leading to confusion and bugs.

**Solution:**
Comprehensive documentation covering:

1. **Force-Quit Behavior**
   - Document in README with ⚠️ warning box
   - Add to docs/platform-setup.md

2. **BGTask Time Limits**
   - BGAppRefreshTask: ~30s
   - BGProcessingTask: ~60s (but requires user charging + WiFi)
   - Document recommended chain lengths

3. **API Annotations**
   ```kotlin
   @RequiresOptIn(
       message = "This trigger is Android-only. iOS returns REJECTED_OS_POLICY",
       level = RequiresOptIn.Level.WARNING
   )
   annotation class AndroidOnlyTrigger

   @AndroidOnlyTrigger
   data object ContentUri : TaskTrigger
   ```

4. **Migration Guide**
   - Create docs/ios-migration.md
   - Common pitfalls and solutions

**Success Criteria:**
- [x] README has iOS limitations section
- [x] All Android-only APIs annotated
- [x] Migration guide complete with examples
- [x] Platform-specific examples in docs/

**Files to Create/Modify:**
- README.md
- docs/platform-setup.md
- docs/ios-migration.md (new)
- docs/ios-best-practices.md (new)
- `kmpworker/src/commonMain/kotlin/dev.brewkits/kmpworkmanager/background/domain/Contracts.kt`

**Related Issues:** #3

---

### 4. Increase EventBus Short-term Replay

**Status:** 🔴 Not Started
**Priority:** P1 - High
**Estimated Effort:** 1 day
**Assignee:** TBD

**Problem:**
Events emitted within seconds before UI starts listening are lost.

**Solution:**
Simple one-line change + tests:

```kotlin
// BEFORE
private val _events = MutableSharedFlow<TaskCompletionEvent>(replay = 0, extraBufferCapacity = 64)

// AFTER
private val _events = MutableSharedFlow<TaskCompletionEvent>(
    replay = 5, // Keep last 5 events in memory
    extraBufferCapacity = 64
)
```

**Rationale:**
- Provides immediate relief while EventStore is being implemented
- 5 events ~= last 1-2 minutes of activity
- Minimal memory overhead (~1KB)

**Success Criteria:**
- [x] Events within 2 minutes are available to new subscribers
- [x] Memory usage <1KB per event
- [x] Unit tests verify replay behavior

**Files to Modify:**
- `kmpworker/src/commonMain/kotlin/dev.brewkits/kmpworkmanager/background/domain/TaskCompletionEvent.kt`

**Related Issues:** #4

---

## 📊 Timeline

```
Week 1-2:  Event Persistence System (#1)
Week 2-3:  iOS Chain State Restoration (#2)
Week 3:    Documentation (#3)
Week 3:    EventBus Replay (#4)
Week 4:    Testing, bug fixes, release prep
```

**Total:** ~4 weeks (20 working days)

---

## 🧪 Testing Requirements

### Unit Tests
- [ ] EventStore CRUD operations
- [ ] Chain resume from various failure points
- [ ] EventBus replay with multiple subscribers

### Integration Tests
- [ ] Full chain execution with interruption
- [ ] Event delivery after app restart
- [ ] Multi-chain concurrent execution

### Manual Testing Checklist
- [ ] Test on iOS 15, 16, 17, 18
- [ ] Test with real BGTaskScheduler (device required)
- [ ] Force-quit during chain execution
- [ ] Airplane mode / network interruption
- [ ] Low battery scenarios

---

## 📦 Release Criteria

- [x] All P0 issues resolved
- [x] All P1 issues resolved
- [x] Test coverage > 85%
- [x] Documentation complete
- [x] Migration guide available
- [x] Beta testing with 3+ production apps
- [x] Performance regression < 5%
- [x] No new memory leaks

---

## 🔄 Migration Guide (v1.0.0 → v1.1.0)

### Breaking Changes
**None** - Fully backward compatible

### New Features Available
```kotlin
// 1. Event persistence (automatic, no code change needed)
TaskEventBus.events.collect { event ->
    // Will now receive missed events on app launch
}

// 2. Chain resume (automatic, no code change needed)
scheduler.beginWith(taskA).then(taskB).then(taskC).enqueue()
// If interrupted at taskB, will resume from taskB automatically

// 3. Platform annotations
@OptIn(AndroidOnlyTrigger::class)
scheduler.enqueue(trigger = TaskTrigger.ContentUri(...))
```

### Recommended Actions
1. Review logs for `REJECTED_OS_POLICY` warnings
2. Add `@OptIn` annotations where needed
3. Read new iOS best practices guide

---

## 🎯 Success Metrics

### Pre-release Metrics (Current v1.0.0)
- Event loss rate: ~15% (estimated)
- Chain resume capability: 0% (fail = restart)
- iOS documentation: 60% complete
- Developer complaints: 3+ regarding iOS limitations

### Post-release Metrics (Target v1.1.0)
- Event loss rate: <1%
- Chain resume success rate: >95%
- iOS documentation: 100% complete
- Developer satisfaction: >4.5/5

---

## 📝 Notes

- All improvements maintain backward compatibility
- Focus on iOS because it's the weak point vs Android
- State restoration is the most complex feature (7 days)
- Documentation is equally important as code fixes

---

**Prepared by:** Core Team
**Last Updated:** 2026-01-14
**Status:** Planning Phase
