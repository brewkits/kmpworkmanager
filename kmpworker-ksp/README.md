# KmpWorkManager KSP Processor

Auto-generates `WorkerFactory` implementations from `@Worker` annotated classes, eliminating
manual factory boilerplate and preventing "worker not found" runtime errors.

## Setup

### 1. Add KSP Plugin

```kotlin
// build.gradle.kts (project level)
plugins {
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// build.gradle.kts (app / KMP module level)
plugins {
    id("com.google.devtools.ksp")
}
```

### 2. Add Dependencies

```kotlin
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.3.9")
    implementation("dev.brewkits:kmpworker-annotations:2.3.9")
    ksp("dev.brewkits:kmpworker-ksp:2.3.9")
}
```

## Usage

### Before (Manual Factory)

```kotlin
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker"   -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else           -> null
        }
    }
}
```

### After (Auto-Generated)

```kotlin
// SyncWorker.kt
@Worker("SyncWorker")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        // Implementation
        return WorkerResult.Success("Done")
    }
}

// Application.kt — use generated factory
import dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated

KmpWorkManager.initialize(
    context = this,
    workerFactory = AndroidWorkerFactoryGenerated()  // ✨ Auto-generated
)
```

## Generated Code

KSP generates a `providers` map so individual entries can be overridden for DI:

```kotlin
// build/generated/ksp/debug/kotlin/.../AndroidWorkerFactoryGenerated.kt
package dev.brewkits.kmpworkmanager.generated

class AndroidWorkerFactoryGenerated : AndroidWorkerFactory {
    val providers: ConcurrentHashMap<String, () -> AndroidWorker?> = ConcurrentHashMap<...>().apply {
        put("SyncWorker")   { SyncWorker()   as AndroidWorker }
        put("UploadWorker") { UploadWorker() as AndroidWorker }
    }

    override fun createWorker(workerClassName: String): AndroidWorker? =
        providers[workerClassName]?.invoke()
}
```

Override individual entries to inject from Koin, Hilt, or any DI framework:

```kotlin
AndroidWorkerFactoryGenerated().also {
    it.providers["SyncWorker"] = { get<SyncWorker>() }  // Koin
}
```

## iOS Support

Works the same way with `IosWorker`:

```kotlin
@Worker(name = "SyncWorker", bgTaskId = "com.example.sync")
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        return WorkerResult.Success("Synced")
    }
}

// Use generated factory
import dev.brewkits.kmpworkmanager.generated.IosWorkerFactoryGenerated

startKoin {
    modules(kmpWorkerModule(
        workerFactory = IosWorkerFactoryGenerated()  // ✨ Auto-generated
    ))
}
```

When any worker declares a `bgTaskId`, the generated `IosWorkerFactoryGenerated` also implements
`BgTaskIdProvider`, and `kmpWorkerModule()` validates all declared BGTask IDs against
`Info.plist → BGTaskSchedulerPermittedIdentifiers` at startup.

## Annotation Parameters

```kotlin
@Worker(
    name    = "SyncWorker",             // Factory key. Required for ProGuard safety.
    bgTaskId = "com.example.sync",      // iOS BGTaskScheduler identifier (iOS only)
    aliases  = ["OldSyncWorker"]        // Legacy names that also resolve to this class
)
class SyncWorker : AndroidWorker { ... }
```

| Parameter  | Default          | Description |
|------------|------------------|-------------|
| `name`     | simple class name | Factory lookup key. Explicit name is strongly recommended — see ProGuard note below. |
| `bgTaskId` | `""`             | iOS BGTask identifier. Validated against `Info.plist` at startup. |
| `aliases`  | `[]`             | Additional lookup keys, e.g. old class names after a rename. |

### ProGuard / Rename Safety

If `@Worker` has no explicit `name`, KSP emits a **build-time warning** because the factory
key defaults to the simple class name. A class rename or ProGuard obfuscation will silently
break any task already persisted under the old name.

**Fix:** always supply `name`:

```kotlin
@Worker(name = "SyncWorker")   // stable across renames and ProGuard
class SyncWorker : AndroidWorker { ... }
```

### Safe Rename with Aliases

When renaming a worker class that may have persisted tasks on devices:

```kotlin
// Step 1: add old name as alias
@Worker(name = "SyncWorkerV2", aliases = ["SyncWorker"])
class SyncWorkerV2 : AndroidWorker { ... }

// Step 2: after all devices drain queues, remove the alias
@Worker(name = "SyncWorkerV2")
class SyncWorkerV2 : AndroidWorker { ... }
```

## Deep Inheritance

Workers that extend a custom base class are fully supported:

```kotlin
// Intermediate base — no @Worker needed here
abstract class BaseAppWorker : AndroidWorker {
    // shared logic
}

// Concrete worker two levels deep — correctly included in generated factory
@Worker("DataSyncWorker")
class DataSyncWorker : BaseAppWorker() { ... }
```

## Troubleshooting

### "Cannot find generated factory"

Rebuild the project (**Build → Rebuild Project**). KSP runs during compilation; generated
files appear in `build/generated/ksp/…/kotlin/`.

### "Worker not found in factory"

Checklist:
- [ ] Class is annotated with `@Worker`
- [ ] Class extends `AndroidWorker` or `IosWorker` (directly or indirectly)
- [ ] Project was rebuilt after adding or modifying the annotation
- [ ] Explicit `name` matches the value stored in the task queue

### Multiple Platforms

KSP generates separate factories:
- `AndroidWorkerFactoryGenerated` — for `AndroidWorker` subclasses
- `IosWorkerFactoryGenerated` — for `IosWorker` subclasses

Use the appropriate one per platform.

## Benefits

| Manual Factory       | KSP Auto-Generated           |
|----------------------|------------------------------|
| ❌ Boilerplate `when` | ✅ Zero boilerplate           |
| ❌ Manual updates     | ✅ Auto-discovery at build    |
| ❌ Runtime "not found"| ✅ Build-time warning         |
| ❌ Easy to forget     | ✅ Enforced by compiler       |
| ❌ Rename risk        | ✅ ProGuard warning + aliases |

---

**Version:** 2.3.9
**Feedback:** https://github.com/brewkits/kmpworkmanager/issues
