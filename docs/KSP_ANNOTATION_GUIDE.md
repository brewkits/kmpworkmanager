# KSP & Annotation Guide - KMPWorkManager

> **v2.2.2+ Experimental Feature**
> Auto-generate WorkerFactory with `@Worker` annotation and KSP

## 📚 Table of Contents

- [Introduction](#introduction)
- [Setup](#setup)
- [Usage](#usage)
- [Examples](#examples)
- [Advanced Usage](#advanced-usage)
- [Troubleshooting](#troubleshooting)
- [Migration Guide](#migration-guide)

## Introduction

### The Problem

Previously, you had to manually create a `WorkerFactory`:

```kotlin
// ❌ Manual - Easy to forget, lots of boilerplate
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "DatabaseWorker" -> DatabaseWorker()
            // Add new worker? Must remember to update here!
            else -> null
        }
    }
}
```

**Drawbacks:**
- ❌ Manual update required when adding new workers
- ❌ Easy to forget adding workers to factory
- ❌ Runtime errors if worker is missing
- ❌ Lots of boilerplate code

### The Solution

With KSP annotation, everything is automatic:

```kotlin
// ✅ Auto-generated - Impossible to forget!
@Worker("SyncWorker")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Your code here
        return true
    }
}

// Use generated factory
KmpWorkManager.initialize(
    context = this,
    workerFactory = AndroidWorkerFactoryGenerated() // ✨ Auto!
)
```

**Benefits:**
- ✅ Zero boilerplate
- ✅ Automatic worker discovery
- ✅ Compile-time validation
- ✅ Impossible to forget adding workers
- ✅ Type-safe

## Setup

### 1. Add KSP Plugin

**Project-level `build.gradle.kts`:**

```kotlin
plugins {
    // Existing plugins...
    id("com.google.devtools.ksp") version "2.1.21-1.0.29" apply false
}
```

**App-level `build.gradle.kts`:**

```kotlin
plugins {
    id("com.google.devtools.ksp")
    // ... other plugins
}
```

### 2. Add Dependencies

```kotlin
dependencies {
    // Core library
    implementation("dev.brewkits:kmpworkmanager:2.2.2")

    // Annotation (lightweight, ~5KB)
    implementation("dev.brewkits:kmpworkmanager-annotations:2.2.2")

    // KSP processor (compile-time only)
    ksp("dev.brewkits:kmpworkmanager-ksp:2.2.2")
}
```

### 3. Sync & Rebuild

```bash
# Sync Gradle
./gradlew build

# or in IDE: File → Sync Project with Gradle Files
```

## Usage

### Step 1: Annotate Workers

Add `@Worker` annotation to all worker classes:

```kotlin
package com.example.workers

import dev.brewkits.kmpworkmanager.annotations.Worker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker

@Worker("SyncWorker")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Sync logic
        return true
    }
}

@Worker("UploadWorker")
class UploadWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Upload logic
        return true
    }
}

@Worker("DatabaseWorker")
class DatabaseWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Database logic
        return true
    }
}
```

### Step 2: Rebuild Project

KSP runs at compile time. Rebuild to generate code:

```bash
# Command line
./gradlew clean build

# or in Android Studio
Build → Rebuild Project
```

### Step 3: Use Generated Factory

KSP automatically creates a factory in package `dev.brewkits.kmpworkmanager.generated`:

```kotlin
// Application.kt
import android.app.Application
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize with generated factory
        KmpWorkManager.initialize(
            context = this,
            workerFactory = AndroidWorkerFactoryGenerated()
        )
    }
}
```

**Done!** No additional code required.

## Examples

### Android Workers

```kotlin
// Notification Worker
@Worker("NotificationWorker")
class NotificationWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Show notification
        return true
    }
}

// Analytics Worker
@Worker("AnalyticsWorker")
class AnalyticsWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Send analytics
        return true
    }
}

// File Cleanup Worker
@Worker("CleanupWorker")
class CleanupWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Clean temp files
        return true
    }
}
```

### iOS Workers

KSP also supports iOS:

```kotlin
// Swift/Kotlin interop
@Worker("SyncWorker")
class SyncWorker : IosWorker {
    override suspend fun doWork(input: String): Boolean {
        // iOS sync logic
        return true
    }
}

// Use generated factory in iOS
import dev.brewkits.kmpworkmanager.generated.IosWorkerFactoryGenerated

startKoin {
    modules(kmpWorkerModule(
        workerFactory = IosWorkerFactoryGenerated()
    ))
}
```

### Custom Worker Names

By default, the class name is used. Override with parameter:

```kotlin
// Use custom name
@Worker("my-custom-sync-task")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        return true
    }
}

// Schedule with custom name
scheduler.enqueue(
    id = "sync-task-1",
    trigger = TaskTrigger.OneTime(initialDelayMs = 0),
    workerClassName = "my-custom-sync-task", // ← Use custom name
    constraints = Constraints()
)
```

### Multiple Modules

KSP works with multi-module projects:

```
app/
├── workers/
│   ├── SyncWorker.kt (@Worker)
│   ├── UploadWorker.kt (@Worker)
│   └── DatabaseWorker.kt (@Worker)
└── Application.kt (use generated factory)

feature-module/
├── FeatureWorker.kt (@Worker)
└── ... (generated factory per module)
```

Each module gets its own factory:
- `app`: `AndroidWorkerFactoryGenerated`
- `feature-module`: `FeatureWorkerFactoryGenerated`

Combine factories:

```kotlin
class CombinedFactory : AndroidWorkerFactory {
    private val factories = listOf(
        AndroidWorkerFactoryGenerated(),
        FeatureWorkerFactoryGenerated()
    )

    override fun createWorker(workerClassName: String): AndroidWorker? {
        return factories.firstNotNullOfOrNull { it.createWorker(workerClassName) }
    }
}
```

## Advanced Usage

### Viewing Generated Code

Generated code is located at:

```
build/generated/ksp/debug/kotlin/dev/brewkits/kmpworkmanager/generated/
├── AndroidWorkerFactoryGenerated.kt
└── IosWorkerFactoryGenerated.kt (if iOS workers exist)
```

Example generated code:

```kotlin
// Auto-generated - DO NOT EDIT
package dev.brewkits.kmpworkmanager.generated

import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import com.example.workers.SyncWorker
import com.example.workers.UploadWorker
import com.example.workers.DatabaseWorker

class AndroidWorkerFactoryGenerated : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "DatabaseWorker" -> DatabaseWorker()
            else -> null
        }
    }
}
```

### Dependency Injection

Workers with Koin/Dagger:

```kotlin
@Worker("SyncWorker")
class SyncWorker(
    private val api: ApiService,  // Injected
    private val db: Database      // Injected
) : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        // Use injected dependencies
        return true
    }
}

// Custom factory with DI
class DIWorkerFactory(private val koin: Koin) : AndroidWorkerFactory {
    private val generated = AndroidWorkerFactoryGenerated()

    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> koin.get<SyncWorker>()
            else -> generated.createWorker(workerClassName)
        }
    }
}
```

### Testing

Mock generated factory:

```kotlin
class TestWorkerFactory : AndroidWorkerFactory {
    var mockWorker: AndroidWorker? = null

    override fun createWorker(workerClassName: String): AndroidWorker? {
        return mockWorker
    }
}

@Test
fun `test worker scheduling`() {
    val testFactory = TestWorkerFactory()
    testFactory.mockWorker = mockk<SyncWorker>()

    KmpWorkManager.initialize(context, testFactory)
    // Test...
}
```

## Troubleshooting

### "Cannot find AndroidWorkerFactoryGenerated"

**Cause:** KSP hasn't generated code yet

**Solution:**
1. Rebuild project: `Build → Rebuild Project`
2. Verify KSP plugin is applied: `plugins { id("com.google.devtools.ksp") }`
3. Check dependency: `ksp("dev.brewkits:kmpworkmanager-ksp:2.2.2")`
4. Sync Gradle files

### "Worker not found in factory"

**Checklist:**
- [ ] Does the class have `@Worker` annotation?
- [ ] Does the class extend `AndroidWorker` or `IosWorker`?
- [ ] Did you rebuild after adding the annotation?
- [ ] Does the worker name in `@Worker` match the `enqueue()` call?

**Debug:**
1. Check generated file at: `build/generated/ksp/.../AndroidWorkerFactoryGenerated.kt`
2. Verify worker is present in the `when` clause
3. Check worker name spelling

### "KSP runs but no code generated"

**Cause:** No workers found

**Solution:**
1. Verify `@Worker` import: `import dev.brewkits.kmpworkmanager.annotations.Worker`
2. Check that the class extends `AndroidWorker` or `IosWorker`
3. Enable KSP logging:

```kotlin
// build.gradle.kts
ksp {
    arg("verbose", "true")
}
```

### Build Time Slow

**Cause:** KSP adds ~1-2s to build time

**Optimization:**
1. Use build cache: `org.gradle.caching=true` in `gradle.properties`
2. Incremental compilation: KSP only runs when workers change
3. Parallel builds: `org.gradle.parallel=true`

## Migration Guide

### From Manual Factory to KSP

**Before:**

```kotlin
// Old manual factory
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            else -> null
        }
    }
}

// Application.kt
KmpWorkManager.initialize(
    context = this,
    workerFactory = MyWorkerFactory()
)
```

**Migration Steps:**

1. **Add annotations:**

```kotlin
@Worker("SyncWorker")
class SyncWorker : AndroidWorker { ... }

@Worker("UploadWorker")
class UploadWorker : AndroidWorker { ... }
```

2. **Setup KSP** (see [Setup](#setup))

3. **Rebuild project**

4. **Replace factory:**

```kotlin
// New - use generated factory
KmpWorkManager.initialize(
    context = this,
    workerFactory = AndroidWorkerFactoryGenerated()
)
```

5. **Delete old factory:**

```kotlin
// Delete MyWorkerFactory.kt ✅
```

6. **Test:**

```bash
./gradlew test
./gradlew connectedAndroidTest
```

### Gradual Migration

Combine old + new factories during transition:

```kotlin
class HybridFactory : AndroidWorkerFactory {
    private val manual = MyWorkerFactory()
    private val generated = AndroidWorkerFactoryGenerated()

    override fun createWorker(workerClassName: String): AndroidWorker? {
        // Try generated first, fallback to manual
        return generated.createWorker(workerClassName)
            ?: manual.createWorker(workerClassName)
    }
}
```

Migrate workers incrementally:
1. Add `@Worker` to worker A → rebuild
2. Test worker A
3. Remove worker A from manual factory
4. Repeat for workers B, C, D...
5. Delete manual factory when empty

## Performance

| Aspect | Manual Factory | KSP Generated | Winner |
|--------|---------------|---------------|--------|
| **Build Time** | 0s | +1-2s | Manual |
| **Runtime Performance** | Same | Same | Tie |
| **Type Safety** | Runtime | Compile-time | ✅ KSP |
| **Maintenance** | Manual | Automatic | ✅ KSP |
| **Boilerplate** | ~50 lines | 0 lines | ✅ KSP |
| **Error Prevention** | Low | High | ✅ KSP |

**Conclusion:** Minimal build time cost for massive developer experience improvement.

## FAQ

**Q: Does KSP run on every build?**
A: Incremental only. KSP only runs when annotated workers change.

**Q: Should generated code be committed to Git?**
A: No. Add `build/` to `.gitignore`. KSP regenerates on each build.

**Q: Does it support multi-module projects?**
A: Yes. Each module gets its own factory. Combine factories if needed.

**Q: Does it support iOS?**
A: Yes. KSP generates `IosWorkerFactoryGenerated` for iOS workers.

**Q: How does it work with dependency injection?**
A: KSP doesn't inject dependencies. Use a custom factory wrapper with Koin/Dagger.

**Q: Can I customize the generated code?**
A: No. Generated code is read-only. Customize via custom factory wrapper.

**Q: Is it production ready?**
A: Experimental status (v2.2.2). Beta stability. Requires validation in production apps.

**Q: What if I need complex worker initialization?**
A: Use custom factory wrapper that delegates to generated factory for simple cases and handles complex initialization separately.

## Resources

- **KSP Documentation**: https://kotlinlang.org/docs/ksp-overview.html
- **Main README**: ../README.md
- **KSP Module README**: ../kmpworker-ksp/README.md
- **Example App**: ../composeApp/
- **Report Issues**: https://github.com/brewkits/kmpworkmanager/issues

## Feedback

KSP annotation is an experimental feature. Please report bugs and suggestions:

https://github.com/brewkits/kmpworkmanager/issues/new

---

**Version:** 2.2.2
**Status:** Experimental
**License:** Apache 2.0
