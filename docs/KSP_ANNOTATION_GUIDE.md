# KSP & Annotation Guide - KMPWorkManager

> **v2.2.2+ Experimental Feature**
> Auto-generate WorkerFactory với `@Worker` annotation và KSP

## 📚 Mục Lục

- [Giới Thiệu](#giới-thiệu)
- [Setup](#setup)
- [Cách Sử Dụng](#cách-sử-dụng)
- [Examples](#examples)
- [Advanced Usage](#advanced-usage)
- [Troubleshooting](#troubleshooting)
- [Migration Guide](#migration-guide)

## Giới Thiệu

### Vấn Đề

Trước đây, bạn phải tự tay tạo `WorkerFactory`:

```kotlin
// ❌ Manual - Dễ quên, nhiều boilerplate
class MyWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        return when (workerClassName) {
            "SyncWorker" -> SyncWorker()
            "UploadWorker" -> UploadWorker()
            "DatabaseWorker" -> DatabaseWorker()
            // Thêm worker mới? Phải nhớ update đây!
            else -> null
        }
    }
}
```

**Nhược điểm:**
- ❌ Phải manually update mỗi khi thêm worker mới
- ❌ Dễ quên không add vào factory
- ❌ Runtime error nếu thiếu worker
- ❌ Nhiều boilerplate code

### Giải Pháp

Với KSP annotation, tất cả tự động:

```kotlin
// ✅ Auto-generated - Không thể quên!
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

**Ưu điểm:**
- ✅ Zero boilerplate
- ✅ Tự động discovery workers
- ✅ Compile-time validation
- ✅ Không thể quên add worker
- ✅ Type-safe

## Setup

### 1. Thêm KSP Plugin

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

### 2. Thêm Dependencies

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

# hoặc trong IDE: File → Sync Project with Gradle Files
```

## Cách Sử Dụng

### Step 1: Annotate Workers

Thêm `@Worker` annotation vào tất cả worker classes:

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

KSP chạy lúc compile. Rebuild để generate code:

```bash
# Command line
./gradlew clean build

# hoặc trong Android Studio
Build → Rebuild Project
```

### Step 3: Use Generated Factory

KSP tự động tạo factory ở package `dev.brewkits.kmpworkmanager.generated`:

```kotlin
// Application.kt
import android.app.Application
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize với generated factory
        KmpWorkManager.initialize(
            context = this,
            workerFactory = AndroidWorkerFactoryGenerated()
        )
    }
}
```

**Xong!** Không cần viết thêm code gì.

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

KSP cũng hỗ trợ iOS:

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

Mặc định dùng class name. Override với parameter:

```kotlin
// Use custom name
@Worker("my-custom-sync-task")
class SyncWorker : AndroidWorker {
    override suspend fun doWork(input: String): Boolean {
        return true
    }
}

// Schedule với custom name
scheduler.enqueue(
    id = "sync-task-1",
    trigger = TaskTrigger.OneTime(initialDelayMs = 0),
    workerClassName = "my-custom-sync-task", // ← Dùng custom name
    constraints = Constraints()
)
```

### Multiple Modules

KSP works với multi-module projects:

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

Mỗi module có factory riêng:
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

Generated code ở:

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

Workers với Koin/Dagger:

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

// Custom factory với DI
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

**Nguyên nhân:** KSP chưa generate code

**Giải pháp:**
1. Rebuild project: `Build → Rebuild Project`
2. Check KSP plugin đã apply: `plugins { id("com.google.devtools.ksp") }`
3. Check dependency: `ksp("dev.brewkits:kmpworkmanager-ksp:2.2.2")`
4. Sync Gradle files

### "Worker not found in factory"

**Checklist:**
- [ ] Class có `@Worker` annotation?
- [ ] Class extend `AndroidWorker` hoặc `IosWorker`?
- [ ] Đã rebuild sau khi add annotation?
- [ ] Worker name trong `@Worker` match với `enqueue()` call?

**Debug:**
1. Check generated file tại: `build/generated/ksp/.../AndroidWorkerFactoryGenerated.kt`
2. Verify worker có trong `when` clause
3. Check worker name spelling

### "KSP runs but no code generated"

**Nguyên nhân:** No workers found

**Giải pháp:**
1. Verify `@Worker` import: `import dev.brewkits.kmpworkmanager.annotations.Worker`
2. Check class extends `AndroidWorker` hoặc `IosWorker`
3. Enable KSP logging:

```kotlin
// build.gradle.kts
ksp {
    arg("verbose", "true")
}
```

### Build Time Slow

**Nguyên nhân:** KSP adds ~1-2s to build

**Optimization:**
1. Use build cache: `org.gradle.caching=true` in `gradle.properties`
2. Incremental compilation: KSP chỉ chạy khi workers change
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

Combine old + new factories:

```kotlin
class HybridFactory : AndroidWorkerFactory {
    private val manual = MyWorkerFactory()
    private val generated = AndroidWorkerFactoryGenerated()

    override fun createWorker(workerClassName: String): AndroidWorker? {
        // Try generated first
        return generated.createWorker(workerClassName)
            ?: manual.createWorker(workerClassName)
    }
}
```

Migrate từng worker một:
1. Add `@Worker` to worker A → rebuild
2. Test worker A
3. Remove worker A from manual factory
4. Repeat for workers B, C, D...
5. Delete manual factory when empty

## Performance

| Aspect | Manual Factory | KSP Generated | Difference |
|--------|---------------|---------------|------------|
| **Build Time** | 0s | +1-2s | KSP processing |
| **Runtime** | Same | Same | Zero overhead |
| **Type Safety** | Runtime | Compile-time | ✅ Better |
| **Maintenance** | Manual | Auto | ✅ Better |
| **Boilerplate** | ~50 lines | 0 lines | ✅ Better |

**Kết luận:** Minimal build time cost, huge developer experience improvement.

## FAQ

**Q: KSP có chạy mỗi lần build không?**
A: Incremental. Chỉ chạy khi workers change.

**Q: Generated code có commit vào Git không?**
A: Không. Add `build/` vào `.gitignore`. KSP regenerate mỗi build.

**Q: Multi-module project support?**
A: Yes. Mỗi module có factory riêng. Combine factories nếu cần.

**Q: iOS support?**
A: Yes. KSP generate `IosWorkerFactoryGenerated` cho iOS workers.

**Q: Dependency injection?**
A: KSP không inject dependencies. Use custom factory wrapper với Koin/Dagger.

**Q: Can I customize generated code?**
A: No. Generated code read-only. Customize via custom factory wrapper.

**Q: Production ready?**
A: Experimental (v2.2.2). Beta stability. Needs validation in production apps.

## Resources

- **KSP Docs**: https://kotlinlang.org/docs/ksp-overview.html
- **Main README**: ../README.md
- **KSP Module**: ../kmpworker-ksp/README.md
- **Examples**: ../composeApp/
- **Issues**: https://github.com/brewkits/kmpworkmanager/issues

## Feedback

KSP annotation là experimental feature. Report bugs/suggestions:

https://github.com/brewkits/kmpworkmanager/issues/new

---

**Version:** 2.2.2
**Status:** Experimental
**License:** Apache 2.0
