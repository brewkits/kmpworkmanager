# Migration Guide: v2.2.x → v2.3.0

**Last Updated:** February 7, 2026

This guide helps you migrate from KMP WorkManager v2.2.x to v2.3.0.

---

## 📋 Overview

**Good News:** v2.3.0 is **100% backward compatible** with v2.2.x!

- ✅ No breaking changes
- ✅ All existing code continues to work
- ✅ Optional upgrades for new features
- ✅ Zero downtime migration

**Migration Time:** 0-30 minutes (depending on feature adoption)

---

## 🔄 What's New in v2.3.0

### Major Features

1. **WorkerResult API** - Return structured data from workers
2. **Built-in Workers Data Return** - All built-in workers return meaningful data
3. **Chain ID Support** - Prevent duplicate chain execution
4. **ExistingPolicy for Chains** - Control chain lifecycle

---

## 📦 Step 1: Update Dependency

### Gradle (Kotlin DSL)

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Before
            implementation("dev.brewkits:kmpworkmanager:2.2.2")

            // After
            implementation("dev.brewkits:kmpworkmanager:2.3.0")
        }
    }
}
```

### Gradle (Groovy)

```groovy
// build.gradle
commonMain {
    dependencies {
        implementation 'dev.brewkits:kmpworkmanager:2.3.0'
    }
}
```

---

## 🔧 Step 2: Optional Upgrades

### Option A: Keep Existing Code (No Changes Needed)

Your existing workers with `Boolean` returns continue to work:

```kotlin
class MyWorker : CommonWorker {
    override suspend fun doWork(input: String?): Boolean {
        // This still works in v2.3.0!
        return true
    }
}
```

✅ **Recommendation:** Keep this approach if you don't need data return.

---

### Option B: Upgrade to WorkerResult API

#### Before (v2.2.x)

```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): Boolean {
        try {
            uploadFile(input)
            return true
        } catch (e: Exception) {
            println("Upload failed: ${e.message}")
            return false
        }
    }
}
```

#### After (v2.3.0)

```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            val result = uploadFile(input)
            WorkerResult.Success(
                message = "Uploaded ${result.size} bytes in ${result.duration}ms",
                data = mapOf(
                    "fileSize" to result.size,
                    "duration" to result.duration,
                    "url" to result.url
                )
            )
        } catch (e: Exception) {
            WorkerResult.Failure("Upload failed: ${e.message}")
        }
    }
}
```

**Benefits:**
- ✅ Better error messages
- ✅ Return structured data
- ✅ Pass data between chained workers
- ✅ Improved debugging

---

## 🔗 Step 3: Chain Upgrades

### Prevent Duplicate Chain Execution

#### Before (v2.2.x)

```kotlin
// Problem: Multiple clicks create duplicate chains
button.onClick {
    scheduler.beginWith(TaskRequest("DownloadWorker"))
        .then(TaskRequest("ProcessWorker"))
        .then(TaskRequest("UploadWorker"))
        .enqueue() // Each click creates NEW chain with random UUID
}
```

#### After (v2.3.0)

```kotlin
// Solution: Use explicit ID with KEEP policy
button.onClick {
    scheduler.beginWith(TaskRequest("DownloadWorker"))
        .then(TaskRequest("ProcessWorker"))
        .then(TaskRequest("UploadWorker"))
        .withId("upload-workflow", policy = ExistingPolicy.KEEP)
        .enqueue() // Duplicate clicks are ignored if chain is running
}
```

**Options:**

```kotlin
// KEEP: Skip if chain already exists
.withId("my-chain", policy = ExistingPolicy.KEEP)

// REPLACE: Cancel old chain and start new one
.withId("my-chain", policy = ExistingPolicy.REPLACE)
```

---

## 🏗️ Step 4: Built-in Workers

### Using Built-in Workers with Data Return

#### Before (v2.2.x)

Built-in workers existed but couldn't return data:

```kotlin
scheduler.enqueue(
    id = "download-task",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker",
    inputJson = Json.encodeToString(downloadConfig)
)
// No way to get download results! ❌
```

#### After (v2.3.0)

Access returned data through event bus or custom worker:

```kotlin
// Option 1: Listen to TaskEventBus (sample app)
TaskEventBus.events.collect { event ->
    if (event.success && event.outputData != null) {
        val fileSize = event.outputData["fileSize"] as? Long
        val filePath = event.outputData["filePath"] as? String
        println("Downloaded $fileSize bytes to $filePath")
    }
}

// Option 2: Create wrapper worker
class MyDownloadWorker(
    private val downloadWorker: HttpDownloadWorker
) : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val result = downloadWorker.doWork(input)

        when (result) {
            is WorkerResult.Success -> {
                val fileSize = result.data?.get("fileSize") as? Long
                // Use the data!
                saveToDatabase(fileSize)
                return result
            }
            is WorkerResult.Failure -> {
                logError(result.message)
                return result
            }
        }
    }
}
```

---

## 📊 Migration Examples

### Example 1: Simple Worker Migration

#### Before

```kotlin
class SyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): Boolean {
        return syncData()
    }
}
```

#### After

```kotlin
class SyncWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val result = syncData()

        return WorkerResult.Success(
            message = "Synced ${result.count} records",
            data = mapOf(
                "recordCount" to result.count,
                "syncDuration" to result.duration,
                "lastSyncTime" to System.currentTimeMillis()
            )
        )
    }
}
```

---

### Example 2: Chain with Data Passing

#### Before

```kotlin
scheduler.beginWith(TaskRequest("FetchWorker"))
    .then(TaskRequest("ProcessWorker"))
    .then(TaskRequest("SaveWorker"))
    .enqueue()

// Workers can't pass data to each other! ❌
```

#### After

```kotlin
scheduler.beginWith(TaskRequest("FetchWorker"))
    .then(TaskRequest("ProcessWorker"))
    .then(TaskRequest("SaveWorker"))
    .withId("data-pipeline", policy = ExistingPolicy.KEEP)
    .enqueue()

// FetchWorker returns data
class FetchWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val data = fetchFromApi()
        return WorkerResult.Success(
            data = mapOf("apiData" to data)
        )
    }
}

// ProcessWorker uses data (future feature - v2.4.0)
// SaveWorker persists results
```

---

### Example 3: Error Handling

#### Before

```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): Boolean {
        return try {
            upload(input)
            true
        } catch (e: NetworkException) {
            println("Network error")
            false
        } catch (e: IOException) {
            println("IO error")
            false
        }
    }
}
```

#### After

```kotlin
class UploadWorker : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return try {
            val result = upload(input)
            WorkerResult.Success(
                message = "Uploaded successfully",
                data = mapOf("uploadId" to result.id)
            )
        } catch (e: NetworkException) {
            WorkerResult.Failure("Network error: ${e.message}")
        } catch (e: IOException) {
            WorkerResult.Failure("IO error: ${e.message}")
        }
    }
}
```

---

## 🧪 Testing Migration

### Update Tests for WorkerResult

#### Before

```kotlin
@Test
fun testWorker() = runTest {
    val worker = MyWorker()
    val result = worker.doWork(null)
    assertTrue(result) // Boolean
}
```

#### After

```kotlin
@Test
fun testWorker() = runTest {
    val worker = MyWorker()
    val result = worker.doWork(null)

    assertTrue(result is WorkerResult.Success)
    assertEquals("Expected message", (result as WorkerResult.Success).message)
    assertNotNull(result.data)
}
```

---

## ⚠️ Common Migration Issues

### Issue 1: Type Mismatch

**Problem:**
```kotlin
// Compiler error: Type mismatch
override suspend fun doWork(input: String?): Boolean {
    return WorkerResult.Success() // ❌
}
```

**Solution:**
```kotlin
// Change return type to WorkerResult
override suspend fun doWork(input: String?): WorkerResult {
    return WorkerResult.Success() // ✅
}
```

---

### Issue 2: Chain Looping

**Problem:**
```kotlin
// Chain runs multiple times on button clicks
button.onClick {
    scheduler.beginWith(task).enqueue()
}
```

**Solution:**
```kotlin
// Add explicit ID with KEEP policy
button.onClick {
    scheduler.beginWith(task)
        .withId("button-chain", policy = ExistingPolicy.KEEP)
        .enqueue()
}
```

---

### Issue 3: Missing Data Access

**Problem:**
```kotlin
// How do I get the returned data?
scheduler.enqueue(...) // ❌ Can't access result
```

**Solution:**

```kotlin
// Option 1: Use TaskEventBus (sample app)
TaskEventBus.events.collect { event ->
    println("Task ${event.taskName}: ${event.outputData}")
}

// Option 2: Create custom worker wrapper
class MyWrapper(private val worker: CommonWorker) : CommonWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        val result = worker.doWork(input)
        // Access result.data here
        return result
    }
}

// Option 3: Wait for v2.4.0 direct data access API
```

---

## 📱 Platform-Specific Notes

### Android

No platform-specific changes needed. WorkManager handles everything automatically.

### iOS

No platform-specific changes needed. BGTaskScheduler integration works seamlessly.

---

## ✅ Migration Checklist

Use this checklist to track your migration:

- [ ] **Update dependency to v2.3.0**
- [ ] **Run existing tests** (should pass without changes)
- [ ] **Identify workers that benefit from data return**
- [ ] **Update worker signatures to WorkerResult** (optional)
- [ ] **Add chain IDs to prevent duplicates** (recommended)
- [ ] **Update tests for WorkerResult** (if using WorkerResult)
- [ ] **Test built-in worker chains** (if using built-in workers)
- [ ] **Update documentation** (internal team docs)
- [ ] **Deploy to staging**
- [ ] **Verify in production**

---

## 🎯 Migration Strategy

### Strategy 1: Zero-Risk (Recommended for Production)

**Timeline:** 0 minutes

1. Update dependency to v2.3.0
2. Run tests (should pass)
3. Deploy

**Result:** You get bug fixes and performance improvements with zero code changes.

---

### Strategy 2: Gradual Adoption (Recommended for Growth)

**Timeline:** 1-2 weeks

1. Update dependency to v2.3.0
2. Run tests
3. **Week 1:** Migrate 1-2 critical workers to WorkerResult
4. **Week 2:** Add chain IDs to existing chains
5. Test and deploy incrementally

**Result:** Gradually adopt new features with low risk.

---

### Strategy 3: Full Adoption (Recommended for New Projects)

**Timeline:** 1-4 weeks

1. Update dependency to v2.3.0
2. Migrate ALL workers to WorkerResult
3. Add chain IDs everywhere
4. Update all tests
5. Comprehensive testing
6. Deploy

**Result:** Fully leverage v2.3.0 features.

---

## 🆘 Need Help?

### Resources

- 📖 **[V2.3.0 Release Notes](V2.3.0_RELEASE_NOTES.md)** - Full feature list
- 📚 **[API Reference](api-reference.md)** - Complete API documentation
- 🔧 **[Built-in Workers Guide](BUILTIN_WORKERS_GUIDE.md)** - Worker data return examples
- 💡 **[Examples](examples.md)** - Real-world code samples

### Support

- 🐛 **Found a bug?** [Report it](https://github.com/brewkits/kmp_worker/issues)
- 💬 **Have questions?** [Ask on GitHub](https://github.com/brewkits/kmp_worker/discussions)
- 📧 **Enterprise support:** datacenter111@gmail.com

---

## 📈 Benefits After Migration

| Benefit | Before v2.3.0 | After v2.3.0 |
|---------|---------------|--------------|
| **Return data from workers** | ❌ Boolean only | ✅ Structured data |
| **Error messages** | ⚠️ Manual logging | ✅ Built-in message |
| **Prevent duplicate chains** | ❌ Manual tracking | ✅ Built-in KEEP policy |
| **Built-in worker data** | ❌ No access | ✅ Full metadata |
| **Debugging** | ⚠️ Limited info | ✅ Detailed logs |
| **Type safety** | ⚠️ Boolean ambiguity | ✅ Explicit Success/Failure |

---

<div align="center">

**Migration Complete? 🎉**

Start building better background tasks with v2.3.0!

**[📖 Quick Start](quickstart.md)** | **[📚 Full Documentation](../README.md)** | **[🗺️ Roadmap](ROADMAP.md)**

</div>
