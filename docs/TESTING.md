# Testing Guide

This document describes how to run and write tests for KMP WorkManager.

## Table of Contents

- [Running Tests](#running-tests)
- [Test Categories](#test-categories)
- [Writing Tests](#writing-tests)
- [Best Practices](#best-practices)
- [Example Test Patterns](#example-test-patterns)

---

## Running Tests

### iOS Tests

Run all iOS tests:
```bash
./gradlew kmpworker:iosX64Test
```

Run specific test class:
```bash
./gradlew kmpworker:iosX64Test --tests "IntegrationTests"
```

Run single test:
```bash
./gradlew kmpworker:iosX64Test --tests "IntegrationTests.testMigrationFromTextToBinary*"
```

### Android Tests

Run all Android unit tests:
```bash
./gradlew kmpworker:testDebugUnitTest
```

Run specific test class:
```bash
./gradlew kmpworker:testDebugUnitTest --tests "*.KmpHeavyWorkerTest"
```

### Common Tests

Run common (platform-agnostic) tests:
```bash
./gradlew kmpworker:allTests
```

---

## Test Categories

### 1. Unit Tests

**Purpose:** Test individual functions and classes in isolation

**Location:**
- iOS: `src/iosTest/kotlin/`
- Android: `src/androidTest/kotlin/`
- Common: `src/commonTest/kotlin/`

**Examples:**
- `CRC32Test.kt` - CRC32 algorithm validation
- `AppendOnlyQueueTest.kt` - Queue operations
- `QueueCorruptionTest.kt` - Corruption handling

**Characteristics:**
- Fast execution (< 1 second per test)
- No external dependencies
- Isolated with temporary directories
- Test one thing at a time

### 2. Integration Tests

**Purpose:** Test complete workflows and interactions between components

**Location:** `src/iosTest/kotlin/IntegrationTests.kt`

**Test Cases:**
- ✅ `testMigrationFromTextToBinary` - Legacy format migration
- ✅ `testMigrationWithEmptyQueue` - Empty queue handling
- ✅ `testMigrationWithLargeQueue` - 1000 items migration
- ✅ `testForceQuitRecovery` - App restart recovery
- ✅ `testExistingPolicyKeep` - KEEP policy behavior
- ✅ `testExistingPolicyReplace` - REPLACE policy behavior
- ✅ `testDiskFullHandling` - Disk space errors
- ✅ `testQueueCorruptionRecovery` - Corruption recovery
- ✅ `testBinaryFormatIntegrity` - CRC validation
- ✅ `testBinaryFormatCRCDetection` - Corrupted data detection
- ✅ `testCompactionTriggeredAt80Percent` - Auto compaction

**Characteristics:**
- Moderate execution time (1-5 seconds per test)
- Tests multiple components together
- Simulates real-world scenarios
- Verifies end-to-end workflows

### 3. Stress Tests

**Purpose:** Test performance, scalability, and resource management under load

**Location:** `src/iosTest/kotlin/StressTests.kt`

**Test Cases:**
- ✅ `testHighConcurrency` - 1000 concurrent operations
- ✅ `testConcurrentEnqueueDequeue` - Simultaneous operations
- ✅ `testLargeQueuePerformance` - 10,000 items performance
- ✅ `testLargeChainDefinitions` - Large data handling
- ✅ `testChainExecutorTimeout` - Timeout simulation
- ✅ `testMemoryUsage` - Memory leak detection
- ✅ `testFileHandleCleanup` - Resource leak detection
- ✅ `testRapidEnqueueDequeue` - Alternating operations
- ✅ `testMaxQueueSize` - Size limit enforcement

**Characteristics:**
- Slow execution (5-30 seconds per test)
- High resource usage
- Tests scalability limits
- Validates performance requirements

---

## Writing Tests

### Test Structure

Follow the Arrange-Act-Assert pattern:

```kotlin
@Test
fun `testFeatureName - description of what it tests`() = runTest {
    // Arrange: Set up test data and environment
    val queue = AppendOnlyQueue(testDirectoryURL)
    queue.enqueue("item-1")

    // Act: Perform the operation
    val result = queue.dequeue()

    // Assert: Verify the outcome
    assertEquals("item-1", result)
}
```

### Test Naming Convention

Use descriptive test names with backticks:

✅ **Good:**
```kotlin
fun `testMigrationFromTextToBinary - preserves all data and updates format`()
fun `testHighConcurrency - 1000 concurrent enqueues with no data loss`()
fun `testDiskFullHandling - throws clear error when disk full`()
```

❌ **Bad:**
```kotlin
fun test1()
fun testMigration()
fun testConcurrency()
```

### Test Isolation

**Always use temporary directories:**

```kotlin
@BeforeTest
fun setup() {
    val tempDir = NSTemporaryDirectory()
    val testDirName = "test_${NSDate().timeIntervalSince1970}"
    testDirectoryURL = NSURL.fileURLWithPath("$tempDir$testDirName")

    NSFileManager.defaultManager.createDirectoryAtURL(
        testDirectoryURL,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
}

@AfterTest
fun tearDown() {
    NSFileManager.defaultManager.removeItemAtURL(testDirectoryURL, error = null)
}
```

### Assertions

Use descriptive assertion messages:

```kotlin
// Good - clear message
assertEquals(
    expected = 100,
    actual = queue.getSize(),
    message = "Queue should have 100 items after enqueue"
)

// Bad - no message
assertEquals(100, queue.getSize())
```

### Performance Testing

Use `TimeSource` for performance measurements:

```kotlin
val startTime = TimeSource.Monotonic.markNow()

// ... operation to measure ...

val duration = startTime.elapsedNow()
assertTrue(
    duration.inWholeMilliseconds < 1000,
    "Operation should complete in < 1s (actual: ${duration.inWholeMilliseconds}ms)"
)
```

---

## Best Practices

### 1. Test One Thing at a Time

✅ **Good:**
```kotlin
@Test
fun `testEnqueue - adds item to queue`() = runTest {
    queue.enqueue("item-1")
    assertEquals(1, queue.getSize())
}

@Test
fun `testDequeue - removes item from queue`() = runTest {
    queue.enqueue("item-1")
    val result = queue.dequeue()
    assertEquals("item-1", result)
    assertEquals(0, queue.getSize())
}
```

❌ **Bad:**
```kotlin
@Test
fun `testQueueOperations`() = runTest {
    // Tests enqueue, dequeue, size, compaction all in one
}
```

### 2. Test Edge Cases

Always test:
- Empty state
- Single item
- Boundary conditions (max size, min size)
- Error conditions
- Concurrent access

### 3. Use Descriptive Variable Names

```kotlin
// Good
val expectedChainId = "chain-123"
val actualChainId = fileStorage.dequeueChain()
assertEquals(expectedChainId, actualChainId)

// Bad
val x = "chain-123"
val y = fileStorage.dequeueChain()
assertEquals(x, y)
```

### 4. Clean Up Resources

Always clean up in `@AfterTest`:

```kotlin
@AfterTest
fun tearDown() {
    // Close any open resources
    executor?.close()

    // Delete temporary files
    NSFileManager.defaultManager.removeItemAtURL(testDirectoryURL, error = null)
}
```

### 5. Test Realistic Scenarios

Prefer real-world scenarios over artificial tests:

✅ **Good:**
```kotlin
fun `testForceQuitRecovery - queue persisted after app restart`() = runTest {
    // Simulate real force-quit scenario
    fileStorage.enqueueChain("chain-1")
    // Drop reference (simulating kill)
    val newFileStorage = IosFileStorage()
    assertEquals(1, newFileStorage.getQueueSize())
}
```

❌ **Bad:**
```kotlin
fun `testFileExists`() = runTest {
    // Too low-level, not testing real functionality
}
```

---

## Example Test Patterns

### Pattern 1: Migration Test

```kotlin
@Test
fun `testMigration - converts old format to new format`() = runTest {
    // 1. Create legacy data
    createLegacyQueue(items = listOf("item-1", "item-2"))

    // 2. Trigger migration
    val queue = AppendOnlyQueue(testDirectoryURL)

    // 3. Verify data preserved
    assertEquals(2, queue.getSize())

    // 4. Verify new format
    verifyBinaryFormat(queue)
}
```

### Pattern 2: Concurrency Test

```kotlin
@Test
fun `testConcurrency - no race conditions with parallel access`() = runTest {
    val jobs = (1..10).map { threadId ->
        async {
            repeat(100) { itemId ->
                queue.enqueue("thread-$threadId-item-$itemId")
            }
        }
    }

    jobs.awaitAll()

    assertEquals(1000, queue.getSize())
}
```

### Pattern 3: Error Handling Test

```kotlin
@Test
fun `testErrorHandling - throws clear exception on invalid input`() = runTest {
    val exception = assertFailsWith<IllegalArgumentException> {
        queue.enqueue("")  // Invalid: empty string
    }

    assertTrue(exception.message!!.contains("cannot be empty"))
}
```

### Pattern 4: Performance Test

```kotlin
@Test
fun `testPerformance - handles 10000 items efficiently`() = runTest {
    val startTime = TimeSource.Monotonic.markNow()

    repeat(10_000) { i ->
        queue.enqueue("item-$i")
    }

    val duration = startTime.elapsedNow()

    assertTrue(
        duration.inWholeMilliseconds < 10_000,
        "Should process 10K items in < 10s (actual: ${duration.inWholeMilliseconds}ms)"
    )
}
```

### Pattern 5: State Persistence Test

```kotlin
@Test
fun `testPersistence - state survives restart`() = runTest {
    // 1. Modify state
    val originalQueue = AppendOnlyQueue(testDirectoryURL)
    originalQueue.enqueue("item-1")

    // 2. Drop reference (simulating restart)
    // ... originalQueue goes out of scope ...

    // 3. Create new instance
    val newQueue = AppendOnlyQueue(testDirectoryURL)

    // 4. Verify state restored
    assertEquals(1, newQueue.getSize())
    assertEquals("item-1", newQueue.dequeue())
}
```

---

## Debugging Failed Tests

### 1. Check Logs

Tests use `Logger` for detailed output:

```bash
./gradlew kmpworker:iosX64Test --info | grep -A 10 "FAILED"
```

### 2. Run Single Test

Isolate the failing test:

```bash
./gradlew kmpworker:iosX64Test --tests "IntegrationTests.testMigration*" --info
```

### 3. Add Debug Output

```kotlin
@Test
fun `testDebug`() = runTest {
    val queue = AppendOnlyQueue(testDirectoryURL)
    println("Queue directory: ${testDirectoryURL.path}")

    queue.enqueue("item-1")
    println("Queue size after enqueue: ${queue.getSize()}")

    val result = queue.dequeue()
    println("Dequeued: $result")
}
```

### 4. Check Test Environment

Verify test isolation:

```bash
ls -la "$(NSTemporaryDirectory())" | grep kmpworkmanager
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Run iOS Tests
  run: ./gradlew kmpworker:iosX64Test

- name: Run Android Tests
  run: ./gradlew kmpworker:testDebugUnitTest

- name: Upload Test Reports
  if: failure()
  uses: actions/upload-artifact@v3
  with:
    name: test-reports
    path: kmpworker/build/reports/tests/
```

---

## Coverage Goals

- **Unit tests:** > 80% code coverage
- **Integration tests:** All critical workflows
- **Stress tests:** All performance-critical paths

Run coverage report:
```bash
./gradlew kmpworker:koverHtmlReport
open kmpworker/build/reports/kover/html/index.html
```

---

## Additional Resources

- [Kotlin Test Documentation](https://kotlinlang.org/api/latest/kotlin.test/)
- [kotlinx-coroutines-test Guide](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [iOS Testing Best Practices](https://developer.apple.com/documentation/xctest)

---

**Last Updated:** 2026-01-29
**Version:** 2.2.0
