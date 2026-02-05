# KmpWorkManager KSP Processor

**v2.2.2+ Experimental Feature**

Auto-generates `WorkerFactory` implementation from `@Worker` annotated classes.

## Setup

### 1. Add KSP Plugin

```kotlin
// build.gradle.kts (project level)
plugins {
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}

// build.gradle.kts (app level)
plugins {
    id("com.google.devtools.ksp")
    // ... other plugins
}
```

### 2. Add Dependencies

```kotlin
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.2.2")
    implementation("dev.brewkits:kmpworkmanager-annotations:2.2.2")
    ksp("dev.brewkits:kmpworkmanager-ksp:2.2.2")
}
```

## Usage

### Before (Manual Factory)

```kotlin
// MyWorkerFactory.kt
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "DatabaseWorker" -> DatabaseWorker()
            "NotificationWorker" -> NotificationWorker()
            else -> null
        }
    }
}
```

### After (Auto-Generated)

```kotlin
// SyncWorker.kt
@Worker("SyncWorker")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Implementation
        return true
    }
}

// UploadWorker.kt
@Worker("UploadWorker")
class UploadWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Implementation
        return true
    }
}

// DatabaseWorker.kt
@Worker("DatabaseWorker")
class DatabaseWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Implementation
        return true
    }
}

// Application.kt - Use generated factory
import dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated

KmpWorkManager.initialize(
    context = this,
    workerFactory = AndroidWorkerFactoryGenerated()  // ✨ Auto-generated!
)
```

## Generated Code

KSP will generate:

```kotlin
// build/generated/ksp/debug/kotlin/.../AndroidWorkerFactoryGenerated.kt
package dev.brewkits.kmpworkmanager.generated

class AndroidWorkerFactoryGenerated : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "DatabaseWorker" -> DatabaseWorker()
            "NotificationWorker" -> NotificationWorker()
            else -> null
        }
    }
}
```

## iOS Support

Works the same way with `IosWorker`:

```kotlin
@Worker("SyncWorker")
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String): Boolean {
        // iOS implementation
        return true
    }
}

// Use generated factory
import dev.brewkits.kmpworkmanager.generated.IosWorkerFactoryGenerated

startKoin {
    modules(kmpWorkerModule(
        workerFactory = IosWorkerFactoryGenerated()  // ✨ Auto-generated!
    ))
}
```

## Custom Worker Names

By default, the class name is used. Override with annotation parameter:

```kotlin
@Worker("my-custom-sync-worker")
class SyncWorker : AndroidWorker {
    // ...
}
```

## Benefits

| Manual Factory | KSP Auto-Generated |
|----------------|-------------------|
| ❌ Boilerplate code | ✅ Zero boilerplate |
| ❌ Manual updates | ✅ Auto-discovery |
| ❌ Runtime errors | ✅ Compile-time validation |
| ❌ Easy to forget | ✅ Impossible to forget |

## Troubleshooting

### "Cannot find generated factory"

**Solution:** Rebuild your project (Build → Rebuild Project)

KSP runs during compilation. Clean and rebuild if generated files are missing.

### "Worker not found in factory"

**Checklist:**
- [ ] Class is annotated with `@Worker`
- [ ] Class extends `AndroidWorker` or `IosWorker`
- [ ] Project was rebuilt after adding annotation
- [ ] Check generated file in `build/generated/ksp/.../`

### Multiple platforms

KSP generates separate factories:
- `AndroidWorkerFactoryGenerated` for Android workers
- `IosWorkerFactoryGenerated` for iOS workers

Use the appropriate one for your platform.

## Performance

- **Compile-time:** KSP runs during compilation (adds ~1-2s to build time)
- **Runtime:** Zero overhead (same as manual factory)
- **Type-safety:** Full compile-time validation

## Limitations

- KSP requires project rebuild to pick up new workers
- Workers must have zero-argument constructors
- Annotation parameters are limited (name only)

## Migration from Manual Factory

1. Add `@Worker` annotation to all worker classes
2. Rebuild project (to generate factories)
3. Replace manual factory with generated one
4. Delete old manual factory code
5. Test worker scheduling

## Example Project

See `/composeApp/` for a complete example using KSP-generated factories.

---

**Status:** Experimental (v2.2.2)
**Stability:** Beta (production-ready after validation)
**Feedback:** https://github.com/brewkits/kmpworkmanager/issues
