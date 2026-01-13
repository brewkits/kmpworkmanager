# Migration Guide: v3.x → v4.0.0

## Overview

Version 4.0.0 introduces a **worker factory pattern** that eliminates hardcoded workers from the library, making it truly extensible. This is a **breaking change** that requires updating your initialization code.

**Migration Time**: ~30 minutes

---

## What Changed?

### 1. Worker Factory Required

**Before (v3.x)**: Library contained hardcoded example workers
```kotlin
// Workers were hidden inside KmpWorker.kt - not accessible to users
```

**After (v4.0.0)**: You provide your own worker factory
```kotlin
class MyWorkerFactory : AndroidWorkerFactory {  // or IosWorkerFactory
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else -> null
        }
    }
}
```

---

### 2. WorkerTypes Object Removed

**Before (v3.x)**: Used library's WorkerTypes constants
```kotlin
WorkerTypes.SYNC_WORKER          // ❌ Removed
WorkerTypes.UPLOAD_WORKER        // ❌ Removed
WorkerTypes.HEAVY_PROCESSING_WORKER  // ❌ Removed
```

**After (v4.0.0)**: Define your own constants
```kotlin
object MyWorkers {
    const val SYNC = "SyncWorker"
    const val UPLOAD = "UploadWorker"
    const val PROCESS = "ProcessWorker"
}
```

---

### 3. Worker Implementation

**Before (v3.x)**: Workers were private inside library

**After (v4.0.0)**: Implement AndroidWorker or IosWorker interface
```kotlin
class SyncWorker : AndroidWorker {  // or IosWorker on iOS
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic here
        Logger.i("SyncWorker", "Syncing data...")
        delay(2000)
        TaskEventBus.emit(TaskCompletionEvent("Sync", true, "✅ Synced"))
        return true
    }
}
```

---

### 4. Koin Initialization

**Before (v3.x)**: No factory parameter
```kotlin
startKoin {
    androidContext(this@Application)
    modules(kmpTaskManagerModule())  // ❌ No longer works
}
```

**After (v4.0.0)**: Pass factory parameter
```kotlin
startKoin {
    androidContext(this@Application)
    modules(kmpTaskManagerModule(
        workerFactory = MyWorkerFactory()  // ✅ Required
    ))
}
```

---

### 5. iOS Task ID Validation

**Before (v3.x)**: Hardcoded list of permitted task IDs

**After (v4.0.0)**: Automatically reads from Info.plist
- No need to duplicate task IDs in Kotlin code
- Task IDs validated against Info.plist at runtime
- Better error messages when validation fails

---

## Step-by-Step Migration

### Step 1: Create Worker Implementations

Create separate worker classes for your background tasks.

#### Android Worker Example

```kotlin
// androidMain/kotlin/com/myapp/workers/SyncWorker.kt
package com.myapp.workers

import io.kmp.taskmanager.background.domain.AndroidWorker
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import kotlinx.coroutines.delay

class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic
        delay(2000)  // Simulate work

        // Emit event for UI updates
        TaskEventBus.emit(
            TaskCompletionEvent("Sync", true, "✅ Data synced successfully")
        )

        return true  // Success
    }
}
```

#### iOS Worker Example

```kotlin
// iosMain/kotlin/com/myapp/workers/SyncWorker.kt
package com.myapp.workers

import io.kmp.taskmanager.background.data.IosWorker
import io.kmp.taskmanager.background.domain.TaskEventBus
import io.kmp.taskmanager.background.domain.TaskCompletionEvent
import kotlinx.coroutines.delay

class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        // Your sync logic (can be shared with Android via expect/actual)
        delay(2000)  // Simulate work

        // Emit event for UI updates
        TaskEventBus.emit(
            TaskCompletionEvent("Sync", true, "✅ Data synced successfully")
        )

        return true  // Success
    }
}
```

---

### Step 2: Create Worker Factory

Create a factory that maps worker class names to instances.

#### Android Factory

```kotlin
// androidMain/kotlin/com/myapp/workers/MyWorkerFactory.kt
package com.myapp.workers

import io.kmp.taskmanager.background.domain.AndroidWorker
import io.kmp.taskmanager.background.domain.AndroidWorkerFactory

class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "ProcessWorker" -> ProcessWorker()
            else -> null  // Unknown worker
        }
    }
}
```

#### iOS Factory

```kotlin
// iosMain/kotlin/com/myapp/workers/MyWorkerFactory.kt
package com.myapp.workers

import io.kmp.taskmanager.background.data.IosWorker
import io.kmp.taskmanager.background.data.IosWorkerFactory

class MyWorkerFactory : IosWorkerFactory {
    override fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "ProcessWorker" -> ProcessWorker()
            else -> null  // Unknown worker
        }
    }
}
```

---

### Step 3: Define Worker Identifiers

Replace `WorkerTypes` with your own constants.

```kotlin
// commonMain/kotlin/com/myapp/workers/MyWorkers.kt
package com.myapp.workers

object MyWorkers {
    const val SYNC = "SyncWorker"
    const val UPLOAD = "UploadWorker"
    const val PROCESS = "ProcessWorker"
}
```

---

### Step 4: Update Koin Initialization

#### Android

```kotlin
// androidMain/kotlin/com/myapp/MyApplication.kt
package com.myapp

import android.app.Application
import com.myapp.workers.MyWorkerFactory
import io.kmp.taskmanager.kmpTaskManagerModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@MyApplication)
            modules(
                kmpTaskManagerModule(
                    workerFactory = MyWorkerFactory()  // ✅ Required in v4.0.0
                )
            )
        }
    }
}
```

#### iOS

```kotlin
// iosMain/kotlin/com/myapp/KoinSetup.kt
package com.myapp

import com.myapp.workers.MyWorkerFactory
import io.kmp.taskmanager.kmpTaskManagerModule
import org.koin.core.context.startKoin

fun initKoinIos() {
    startKoin {
        modules(
            kmpTaskManagerModule(
                workerFactory = MyWorkerFactory(),  // ✅ Required in v4.0.0
                iosTaskIds = setOf()  // Optional - reads from Info.plist automatically
            )
        )
    }
}
```

---

### Step 5: Update Task Scheduling Code

Replace `WorkerTypes` constants with your own.

#### Before (v3.x)

```kotlin
scheduler.enqueue(
    id = "sync-task",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = WorkerTypes.SYNC_WORKER,  // ❌ Removed
    constraints = Constraints(requiresNetwork = true)
)
```

#### After (v4.0.0)

```kotlin
scheduler.enqueue(
    id = "sync-task",
    trigger = TaskTrigger.Periodic(intervalMs = 900_000),
    workerClassName = MyWorkers.SYNC,  // ✅ Your constant
    constraints = Constraints(requiresNetwork = true)
)
```

---

### Step 6: iOS Info.plist Verification

Ensure all task IDs are declared in your Info.plist. v4.0.0 automatically validates against this.

```xml
<!-- iosApp/iosApp/Info.plist -->
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>kmp_chain_executor_task</string>  <!-- Internal library task -->
    <string>my-sync-task</string>
    <string>my-upload-task</string>
</array>
```

---

## Common Migration Patterns

### Pattern 1: Shared Worker Logic

Use expect/actual to share worker implementation between platforms.

```kotlin
// commonMain/kotlin/com/myapp/workers/SyncWorker.kt
expect class SyncWorker() {
    suspend fun executeSync(): Boolean
}

// androidMain/kotlin/com/myapp/workers/SyncWorker.kt
actual class SyncWorker : AndroidWorker {
    actual suspend fun executeSync(): Boolean {
        // Android-specific implementation
        return true
    }

    override suspend fun doWork(input: String?) = executeSync()
}

// iosMain/kotlin/com/myapp/workers/SyncWorker.kt
actual class SyncWorker : IosWorker {
    actual suspend fun executeSync(): Boolean {
        // iOS-specific implementation
        return true
    }

    override suspend fun doWork(input: String?) = executeSync()
}
```

---

### Pattern 2: Dependency Injection in Workers

Use Koin to inject dependencies into workers.

```kotlin
class SyncWorker(
    private val repository: DataRepository = KoinContext.get().get()
) : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        val data = repository.fetchData()
        return data != null
    }
}
```

---

### Pattern 3: Parametrized Input

Use kotlinx.serialization for type-safe worker input.

```kotlin
@Serializable
data class UploadData(val fileUrl: String, val size: Long)

class UploadWorker : AndroidWorker {
    override suspend fun doWork(input: String?): Boolean {
        val uploadData = input?.let { Json.decodeFromString<UploadData>(it) }
        if (uploadData == null) return false

        // Upload file
        return uploadFile(uploadData.fileUrl, uploadData.size)
    }
}

// When scheduling
val data = UploadData("/path/to/file", 1024)
scheduler.enqueue(
    id = "upload",
    trigger = TaskTrigger.OneTime(),
    workerClassName = MyWorkers.UPLOAD,
    inputJson = Json.encodeToString(data)  // Serialize
)
```

---

## Error Handling

### Error: "Worker factory returned null"

**Cause**: Worker class name not registered in factory

**Fix**: Add the worker to your factory's `when()` statement

```kotlin
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "MissingWorker" -> MissingWorker()  // ← Add this
            else -> null
        }
    }
}
```

---

### Error: "WorkerFactory must implement AndroidWorkerFactory on Android"

**Cause**: Passing wrong factory type for platform

**Fix**: Use platform-specific factory interface

```kotlin
// Android - implement AndroidWorkerFactory
class MyWorkerFactory : AndroidWorkerFactory { ... }

// iOS - implement IosWorkerFactory
class MyWorkerFactory : IosWorkerFactory { ... }
```

---

### Error: "Task ID validation failed" (iOS)

**Cause**: Task ID not in Info.plist `BGTaskSchedulerPermittedIdentifiers`

**Fix**: Add task ID to Info.plist and register handler in Swift

```xml
<!-- Info.plist -->
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>my-task-id</string>  <!-- Add this -->
</array>
```

```swift
// iOSApp.swift
BGTaskScheduler.shared.register(forTaskWithIdentifier: "my-task-id") { task in
    // Handle task
}
```

---

## Testing Your Migration

### 1. Verify Koin Initialization

```kotlin
@Test
fun `factory is registered in Koin`() {
    startKoin {
        modules(kmpTaskManagerModule(workerFactory = MyWorkerFactory()))
    }

    val factory = KoinContext.get().get<AndroidWorkerFactory>()
    assertNotNull(factory)
}
```

---

### 2. Verify Worker Creation

```kotlin
@Test
fun `factory creates correct worker`() {
    val factory = MyWorkerFactory()

    val syncWorker = factory.createWorker("SyncWorker")
    assertNotNull(syncWorker)
    assertTrue(syncWorker is SyncWorker)

    val unknownWorker = factory.createWorker("UnknownWorker")
    assertNull(unknownWorker)
}
```

---

### 3. Verify Worker Execution

```kotlin
@Test
fun `worker executes successfully`() = runTest {
    val worker = SyncWorker()
    val result = worker.doWork(null)
    assertTrue(result)
}
```

---

## Benefits of v4.0.0

1. **Extensibility**: Add workers without modifying library code
2. **Type Safety**: Compile-time checks for worker registration
3. **Better Errors**: Clear validation messages with actionable fixes
4. **iOS Automation**: Info.plist reading eliminates duplicate configuration
5. **Testability**: Mock worker factories easily
6. **Separation of Concerns**: Library doesn't contain example workers
7. **Flexibility**: Use your own DI, serialization, etc.

---

## Rollback Strategy

If you encounter issues and need to stay on v3.x temporarily:

```gradle
// In your build.gradle.kts
dependencies {
    implementation("io.kmp.taskmanager:kmptaskmanager:3.0.0")  // v3.x
}
```

**Note**: v3.x will be supported for security patches for 6 months.

---

## Need Help?

- **GitHub Issues**: https://github.com/yourusername/KMPTaskManager/issues
- **Discussions**: https://github.com/yourusername/KMPTaskManager/discussions
- **Migration Examples**: See `examples/migration-v4` directory

---

## Frequently Asked Questions

### Q: Can I migrate incrementally?

A: No, v4.0.0 requires full migration due to API changes. Plan for ~30 minutes.

### Q: Do I need to update my Swift code?

A: No, Swift BGTaskScheduler registration remains the same. Only Kotlin code changes.

### Q: Can I use the old WorkerTypes?

A: No, `WorkerTypes` is marked with `@Deprecated(level = DeprecationLevel.ERROR)` and will cause compilation errors.

### Q: What about existing scheduled tasks?

A: Existing tasks will fail with "Worker factory returned null" error. Cancel and reschedule them with your new worker class names.

### Q: Can I use dependency injection in workers?

A: Yes! Use Koin, constructor injection, or manual instantiation in your factory.

---

**Migration checklist**:
- [ ] Create worker implementations (AndroidWorker/IosWorker)
- [ ] Create worker factory (AndroidWorkerFactory/IosWorkerFactory)
- [ ] Define worker identifier constants (replace WorkerTypes)
- [ ] Update Koin initialization (pass workerFactory)
- [ ] Update all scheduler.enqueue() calls (use new constants)
- [ ] Verify iOS Info.plist task IDs
- [ ] Test worker creation and execution
- [ ] Cancel and reschedule existing tasks

Good luck with your migration! 🚀
