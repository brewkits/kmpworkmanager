# Migrating from v2.3.3 to v2.3.4

**Published:** February 26, 2026

This version fixes some critical issues and improves performance. The main change is that `chain.enqueue()` is now a suspend function, which means you'll need to update your code.

Migration should take 10-30 minutes depending on your project size.

---

## What's Changed

**Breaking change:** `chain.enqueue()` is now suspending

**Why:** The old blocking version could cause deadlocks in some scenarios. Making it suspending eliminates this issue entirely.

**Performance improvements:**
- HTTP operations are 60-86% faster (singleton HttpClient by default)
- Progress tracking is more reliable (flush interval reduced from 500ms to 100ms)
- New API to manually flush progress on iOS before app suspension

---

## The Main Change: chain.enqueue() is Now Suspending

### Before (v2.3.3)

```kotlin
fun scheduleMyChain() {
    val chain = scheduler.beginWith(
        TaskRequest(workerClassName = "Step1Worker")
    ).then(
        TaskRequest(workerClassName = "Step2Worker")
    )

    chain.enqueue()  // This won't compile in v2.3.4
}
```

### After (v2.3.4) - Three Options

**Option 1: Make your function suspending**

```kotlin
suspend fun scheduleMyChain() {
    val chain = scheduler.beginWith(
        TaskRequest(workerClassName = "Step1Worker")
    ).then(
        TaskRequest(workerClassName = "Step2Worker")
    )

    chain.enqueue()  // Works now
}
```

**Option 2: Wrap in a coroutine**

```kotlin
fun scheduleMyChain() {
    CoroutineScope(Dispatchers.IO).launch {
        val chain = scheduler.beginWith(
            TaskRequest(workerClassName = "Step1Worker")
        ).then(
            TaskRequest(workerClassName = "Step2Worker")
        )

        chain.enqueue()  // Works inside coroutine
    }
}
```

**Option 3: Use the deprecated blocking version (temporary)**

```kotlin
fun scheduleMyChain() {
    val chain = scheduler.beginWith(
        TaskRequest(workerClassName = "Step1Worker")
    ).then(
        TaskRequest(workerClassName = "Step2Worker")
    )

    chain.enqueueBlocking()  // Deprecated - will be removed in v3.0.0
}
```

---

## Tests Need Updating Too

If you have integration tests that call `enqueue()`, wrap them in `runBlocking`:

### Before

```kotlin
@Test
fun testChainExecution() {
    val chain = scheduler.beginWith(task1).then(task2)
    chain.enqueue()  // Won't compile

    // assertions...
}
```

### After

```kotlin
@Test
fun testChainExecution() = runBlocking {
    val chain = scheduler.beginWith(task1).then(task2)
    chain.enqueue()  // Works now

    // assertions...
}
```

Or make the test function suspend (if your test framework supports it):

```kotlin
@Test
suspend fun testChainExecution() {
    val chain = scheduler.beginWith(task1).then(task2)
    chain.enqueue()

    // assertions...
}
```

---

## HTTP Performance (No Code Changes Needed)

All built-in HTTP workers now use a singleton `HttpClient` by default. This makes HTTP operations 60-86% faster due to connection pooling and SSL session reuse.

You don't need to change anything - it's automatic.

If you were already providing a custom `HttpClient`, it will still be used:

```kotlin
val customClient = HttpClient { /* your config */ }
val worker = HttpRequestWorker(httpClient = customClient)
```

---

## iOS Progress Flushing (Optional But Recommended)

v2.3.4 adds a new API to flush pending progress updates before your app goes to the background. This prevents data loss on iOS.

Add this to your `AppDelegate`:

```swift
import UIKit
import KMPWorkManager

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    func applicationWillResignActive(_ application: UIApplication) {
        // Flush progress before app enters background
        KmpWorkManager.shared.backgroundTaskScheduler.flushPendingProgress()
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Also flush on termination (rare but possible)
        KmpWorkManager.shared.backgroundTaskScheduler.flushPendingProgress()
    }
}
```

This takes 10-50ms and guarantees no progress data is lost when iOS suspends your app.

On Android, this is a no-op (WorkManager handles persistence automatically).

---

## Migration Steps

### 1. Update Your Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.3.4")  // Update from 2.3.3
}
```

### 2. Try Building

```bash
./gradlew build
```

You'll see errors like:

```
Suspend function 'enqueue' should be called only from a coroutine or another suspend function
```

### 3. Fix Each Error

For each error, pick one of these fixes:

**Make the calling function suspend:**
```kotlin
suspend fun myFunction() {
    chain.enqueue()
}
```

**Or wrap in a coroutine:**
```kotlin
fun myFunction() {
    CoroutineScope(Dispatchers.IO).launch {
        chain.enqueue()
    }
}
```

**Or use the deprecated blocking version (temporary):**
```kotlin
fun myFunction() {
    chain.enqueueBlocking()  // Works but deprecated
}
```

### 4. Update Your Tests

Wrap test functions in `runBlocking`:

```kotlin
@Test
fun testSomething() = runBlocking {
    chain.enqueue()
}
```

### 5. Verify Everything Works

```bash
./gradlew clean build
./gradlew test
```

---

## Platform-Specific Examples

### Android Activity/Fragment

```kotlin
class MyActivity : AppCompatActivity() {
    fun scheduleWork() {
        lifecycleScope.launch {
            val chain = scheduler.beginWith(task)
            chain.enqueue()
        }
    }
}
```

### Android ViewModel

```kotlin
class MyViewModel : ViewModel() {
    fun scheduleWork() {
        viewModelScope.launch {
            val chain = scheduler.beginWith(task)
            chain.enqueue()
        }
    }
}
```

### iOS SwiftUI

```swift
struct ContentView: View {
    func scheduleWork() {
        Task {
            let chain = scheduler.beginWith(task: task)
            try await chain.enqueue()
        }
    }
}
```

### iOS UIKit

```swift
class MyViewController: UIViewController {
    func scheduleWork() {
        Task {
            let chain = scheduler.beginWith(task: task)
            try await chain.enqueue()
        }
    }
}
```

---

## Common Patterns

### One-Shot Chain Scheduling

```kotlin
// Make it suspend:
suspend fun scheduleDataSync() {
    scheduler.beginWith(
        TaskRequest("DownloadWorker")
    ).then(
        TaskRequest("ProcessWorker")
    ).then(
        TaskRequest("UploadWorker")
    ).enqueue()
}

// Or wrap in launch:
fun scheduleDataSync() {
    CoroutineScope(Dispatchers.IO).launch {
        scheduler.beginWith(
            TaskRequest("DownloadWorker")
        ).then(
            TaskRequest("ProcessWorker")
        ).then(
            TaskRequest("UploadWorker")
        ).enqueue()
    }
}
```

### Reusable Chain Builder

```kotlin
class ChainBuilder(private val scheduler: BackgroundTaskScheduler) {
    suspend fun buildAndEnqueue() {  // Made suspending
        val chain = scheduler.beginWith(task1)
            .then(task2)
            .then(task3)
        chain.enqueue()
    }
}
```

### Conditional Chains

```kotlin
suspend fun scheduleConditional(includeStep2: Boolean) {
    var chain = scheduler.beginWith(task1)

    if (includeStep2) {
        chain = chain.then(task2)
    }

    chain.enqueue()
}
```

---

## Troubleshooting

**Error: "Suspend function should be called only from a coroutine"**

Wrap in `launch` or make the function suspending.

**Error: "Type mismatch: inferred type is Unit but TestResult was expected"**

Add `= runBlocking` to your test function.

**Warning: "enqueueBlocking() is deprecated"**

Switch to using suspending `enqueue()` with proper coroutine handling.

---

## What You Get After Migrating

- 60-86% faster HTTP operations (automatic)
- Zero deadlock risk (eliminated blocking code)
- 90% less progress data loss on iOS (shorter flush interval + manual API)
- Better resource usage (connection pooling)
- Cleaner async code (proper suspend functions)
- Ready for v3.0.0

---

## Need to Roll Back?

```kotlin
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.3.3")
}
```

Then rebuild:
```bash
./gradlew clean build
```

---

## Time Estimate

- Small projects (< 10 chain calls): 10-15 minutes
- Medium projects (10-50 chain calls): 20-30 minutes
- Large projects (50+ chain calls): 30-60 minutes

**Tip:** Use your IDE's "Find Usages" to locate all `enqueue()` calls, then update them one by one.

---

## Questions?

- GitHub Issues: https://github.com/brewkits/kmpworkmanager/issues
- Email: datacenter111@gmail.com

---

**Last updated:** February 26, 2026
