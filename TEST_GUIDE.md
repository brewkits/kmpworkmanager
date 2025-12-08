# 🧪 KMP TaskManager Testing Guide

Comprehensive guide for testing KMP TaskManager - from unit tests to integration testing.

## 📋 Table of Contents

- [Test Structure](#test-structure)
- [Running Tests](#running-tests)
- [Unit Testing](#unit-testing)
- [Integration Testing](#integration-testing)
- [Platform-Specific Testing](#platform-specific-testing)
- [Test Coverage](#test-coverage)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## 📁 Test Structure

```
kmptaskmanager/
├── src/
│   ├── commonTest/               # Shared unit tests
│   │   └── io/kmp/taskmanager/
│   │       ├── ContractsTest.kt          # TaskTrigger, Constraints, enums
│   │       ├── TaskChainTest.kt          # TaskChain, TaskRequest
│   │       ├── TaskEventTest.kt          # EventBus, events
│   │       ├── UtilsTest.kt              # Logger, LogTags, TaskIds
│   │       ├── TaskTriggerHelperTest.kt  # Helper functions
│   │       ├── SerializationTest.kt      # JSON serialization
│   │       └── EdgeCasesTest.kt          # Boundary conditions
│   │
│   ├── androidUnitTest/          # Android unit tests (JVM)
│   │   └── [Future: Android-specific unit tests]
│   │
│   ├── androidTest/              # Android instrumentation tests
│   │   └── [Future: WorkManager integration tests]
│   │
│   └── iosTest/                  # iOS tests
│       └── [Future: BGTaskScheduler tests]
```

## 🚀 Running Tests

### All Tests

```bash
# Run all tests
./gradlew test

# Run with detailed output
./gradlew test --info

# Run specific module
./gradlew :kmptaskmanager:test
./gradlew :composeApp:test
```

### Specific Test Files

```bash
# Run single test file
./gradlew test --tests "io.kmp.taskmanager.ContractsTest"

# Run specific test method
./gradlew test --tests "io.kmp.taskmanager.ContractsTest.TaskTrigger*"

# Run multiple test files
./gradlew test --tests "io.kmp.taskmanager.*Test"
```

### Platform-Specific Tests

```bash
# Android unit tests
./gradlew :kmptaskmanager:testDebugUnitTest
./gradlew :kmptaskmanager:testReleaseUnitTest

# iOS tests (requires macOS)
./gradlew :kmptaskmanager:iosX64Test
./gradlew :kmptaskmanager:iosSimulatorArm64Test
./gradlew :kmptaskmanager:iosArm64Test
```

### Continuous Testing

```bash
# Watch mode (re-run on changes)
./gradlew test --continuous
```

## 🔬 Unit Testing

### Test Anatomy

```kotlin
class FeatureTest {
    // 1. Setup (optional)
    private lateinit var subject: Subject

    @BeforeTest
    fun setup() {
        subject = Subject()
    }

    @AfterTest
    fun teardown() {
        // Cleanup if needed
    }

    // 2. Test cases
    @Test
    fun `descriptive test name using backticks`() {
        // Given (Arrange)
        val input = createTestInput()

        // When (Act)
        val result = subject.performAction(input)

        // Then (Assert)
        assertEquals(expected, result)
    }
}
```

### Example: Testing TaskTrigger

```kotlin
@Test
fun `OneTime trigger with custom delay should preserve value`() {
    // Given
    val delayMs = 5000L

    // When
    val trigger = TaskTrigger.OneTime(initialDelayMs = delayMs)

    // Then
    assertEquals(delayMs, trigger.initialDelayMs)
}
```

### Testing Edge Cases

```kotlin
@Test
fun `TaskRequest with empty workerClassName should accept value`() {
    // Given
    val emptyName = ""

    // When
    val request = TaskRequest(workerClassName = emptyName)

    // Then
    assertEquals("", request.workerClassName)
}

@Test
fun `Constraints with negative backoffDelayMs should accept value`() {
    // Given
    val negativeDelay = -1000L

    // When
    val constraints = Constraints(backoffDelayMs = negativeDelay)

    // Then
    assertEquals(negativeDelay, constraints.backoffDelayMs)
}
```

### Testing Exceptions

```kotlin
@Test
fun `TaskChain with empty list should throw IllegalArgumentException`() {
    // Given
    val scheduler = MockScheduler()
    val chain = scheduler.beginWith(TaskRequest("Worker1"))

    // When/Then
    assertFailsWith<IllegalArgumentException> {
        chain.then(emptyList())
    }
}
```

### Async Testing

```kotlin
@Test
fun `EventBus should emit events successfully`() = runTest {
    // Given
    val event = TaskCompletionEvent("Task", true, "Success")
    val receivedEvents = mutableListOf<TaskCompletionEvent>()

    val job = launch {
        TaskEventBus.events.collect {
            receivedEvents.add(it)
        }
    }

    // When
    TaskEventBus.emit(event)
    delay(100) // Give time for collection

    // Then
    assertEquals(1, receivedEvents.size)
    assertEquals(event, receivedEvents[0])

    job.cancel()
}
```

## 🔗 Integration Testing

### Android Integration Tests

Integration tests for Android require an emulator or device.

#### Setup (build.gradle.kts)

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.work:work-testing:2.11.0")
}
```

#### Example: Testing WorkManager

```kotlin
@RunWith(AndroidJUnit4::class)
class NativeTaskSchedulerAndroidTest {
    private lateinit var context: Context
    private lateinit var scheduler: NativeTaskScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        scheduler = NativeTaskScheduler(context)
    }

    @Test
    fun testOneTimeTaskScheduling() = runTest {
        // Given
        val taskId = "test-task-${System.currentTimeMillis()}"
        val trigger = TaskTrigger.OneTime(initialDelayMs = 0)

        // When
        val result = scheduler.enqueue(
            id = taskId,
            trigger = trigger,
            workerClassName = "TestWorker"
        )

        // Then
        assertEquals(ScheduleResult.ACCEPTED, result)

        // Verify task is scheduled
        val workManager = WorkManager.getInstance(context)
        val workInfo = workManager.getWorkInfosForUniqueWork(taskId).await()
        assertTrue(workInfo.isNotEmpty())
    }

    @Test
    fun testTaskWithConstraints() = runTest {
        // Given
        val taskId = "constrained-task"
        val constraints = Constraints(requiresNetwork = true)

        // When
        val result = scheduler.enqueue(
            id = taskId,
            trigger = TaskTrigger.OneTime(),
            workerClassName = "NetworkWorker",
            constraints = constraints
        )

        // Then
        assertEquals(ScheduleResult.ACCEPTED, result)

        // Verify constraints are applied
        val workManager = WorkManager.getInstance(context)
        val workInfo = workManager.getWorkInfosForUniqueWork(taskId).await().first()
        assertTrue(workInfo.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED)
    }
}
```

#### Running Android Integration Tests

```bash
# Install and run tests on connected device
./gradlew :kmptaskmanager:connectedAndroidTest

# Run on specific device
./gradlew :kmptaskmanager:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.device=emulator-5554
```

### iOS Integration Tests

iOS integration tests can be run in Xcode or using xcodebuild.

#### Example: Testing BGTaskScheduler

```swift
import XCTest
import KMPTaskManager

class NativeTaskSchedulerIOSTest: XCTestCase {
    var scheduler: NativeTaskScheduler!
    var workerFactory: TestWorkerFactory!

    override func setUp() {
        super.setUp()
        workerFactory = TestWorkerFactory()
        scheduler = NativeTaskScheduler(
            workerFactory: workerFactory,
            taskIds: ["test-task"]
        )
    }

    func testTaskScheduling() async throws {
        // Given
        let taskId = "test-task"
        let trigger = TaskTriggerOneTime(initialDelayMs: 0)

        // When
        let result = try await scheduler.enqueue(
            id: taskId,
            trigger: trigger,
            workerClassName: "TestWorker"
        )

        // Then
        XCTAssertEqual(result, ScheduleResult.accepted)
    }

    func testInvalidTaskIdRejection() async throws {
        // Given
        let invalidTaskId = "not-in-plist"
        let trigger = TaskTriggerOneTime(initialDelayMs: 0)

        // When
        let result = try await scheduler.enqueue(
            id: invalidTaskId,
            trigger: trigger,
            workerClassName: "TestWorker"
        )

        // Then
        XCTAssertEqual(result, ScheduleResult.rejectedOsPolicy)
    }
}
```

## 🎯 Platform-Specific Testing

### Mock Schedulers

For unit testing code that depends on schedulers:

```kotlin
class MockBackgroundTaskScheduler : BackgroundTaskScheduler {
    val scheduledTasks = mutableListOf<ScheduledTask>()

    override suspend fun enqueue(
        id: String,
        trigger: TaskTrigger,
        workerClassName: String,
        constraints: Constraints,
        inputJson: String?,
        policy: ExistingPolicy
    ): ScheduleResult {
        scheduledTasks.add(
            ScheduledTask(id, trigger, workerClassName, constraints, inputJson, policy)
        )
        return ScheduleResult.ACCEPTED
    }

    override fun cancel(id: String) {
        scheduledTasks.removeIf { it.id == id }
    }

    override fun cancelAll() {
        scheduledTasks.clear()
    }

    override fun beginWith(task: TaskRequest): TaskChain {
        return TaskChain(this, listOf(task))
    }

    override fun beginWith(tasks: List<TaskRequest>): TaskChain {
        return TaskChain(this, tasks)
    }

    override fun enqueueChain(chain: TaskChain) {
        // Store chain for verification
    }

    data class ScheduledTask(
        val id: String,
        val trigger: TaskTrigger,
        val workerClassName: String,
        val constraints: Constraints,
        val inputJson: String?,
        val policy: ExistingPolicy
    )
}
```

### Usage in Tests

```kotlin
@Test
fun `ViewModel should schedule task on button click`() = runTest {
    // Given
    val mockScheduler = MockBackgroundTaskScheduler()
    val viewModel = MyViewModel(mockScheduler)

    // When
    viewModel.onScheduleButtonClicked()

    // Then
    assertEquals(1, mockScheduler.scheduledTasks.size)
    assertEquals("sync-task", mockScheduler.scheduledTasks[0].id)
}
```

## 📊 Test Coverage

### Generating Coverage Reports

```bash
# Run tests with coverage
./gradlew test jacocoTestReport

# View HTML report
open kmptaskmanager/build/reports/jacoco/test/html/index.html
```

### Current Coverage Statistics

**Version 2.2.0:**
- **Total test cases**: 101
- **Common code coverage**: 85%+
- **Test files**: 7
- **Test lines**: ~2000+

**Coverage breakdown:**
- Contracts (TaskTrigger, Constraints, enums): 100%
- TaskChain: 95%
- Utils (Logger, LogTags): 100%
- TaskEvent: 90%
- Serialization: 100%
- Edge cases: 100%

### Coverage Goals

- **Common code**: 85%+ ✅ (achieved)
- **Critical paths**: 100% (scheduling, execution)
- **Public APIs**: 100% ✅ (achieved)
- **Platform-specific**: Integration tests (manual)

## ✅ Best Practices

### 1. Test Naming

Use descriptive names with backticks:

```kotlin
// ✅ Good
@Test
fun `TaskChain with empty list should throw IllegalArgumentException`()

// ❌ Bad
@Test
fun test1()
```

### 2. Arrange-Act-Assert Pattern

```kotlin
@Test
fun `example test`() {
    // Arrange (Given)
    val input = createInput()

    // Act (When)
    val result = performAction(input)

    // Assert (Then)
    assertEquals(expected, result)
}
```

### 3. Test One Thing

```kotlin
// ✅ Good - Tests one aspect
@Test
fun `Constraints with requiresNetwork should set flag`() {
    val constraints = Constraints(requiresNetwork = true)
    assertTrue(constraints.requiresNetwork)
}

// ❌ Bad - Tests multiple things
@Test
fun `Constraints should work`() {
    val constraints = Constraints(requiresNetwork = true, requiresCharging = true)
    assertTrue(constraints.requiresNetwork)
    assertTrue(constraints.requiresCharging)
    assertEquals(Qos.Background, constraints.qos)
    // Testing too many things at once
}
```

### 4. Independent Tests

Tests should not depend on each other:

```kotlin
// ✅ Good - Each test is independent
@Test
fun `test A`() {
    val scheduler = MockScheduler()
    // Test A logic
}

@Test
fun `test B`() {
    val scheduler = MockScheduler()
    // Test B logic
}

// ❌ Bad - Tests depend on execution order
var sharedScheduler: MockScheduler? = null

@Test
fun `test A creates scheduler`() {
    sharedScheduler = MockScheduler()
}

@Test
fun `test B uses scheduler from A`() {
    sharedScheduler!!.schedule(...) // Fails if A doesn't run first
}
```

### 5. Test Edge Cases

Always test boundary conditions:

```kotlin
@Test
fun `handles zero value`()

@Test
fun `handles negative value`()

@Test
fun `handles max value`()

@Test
fun `handles empty string`()

@Test
fun `handles null value`()

@Test
fun `handles very large input`()
```

### 6. Use Meaningful Assertions

```kotlin
// ✅ Good - Clear assertion
assertEquals(ScheduleResult.ACCEPTED, result, "Task should be accepted")

// ❌ Bad - Generic assertion
assertTrue(result == ScheduleResult.ACCEPTED)
```

## 🔧 Troubleshooting

### Tests Failing After Changes

1. **Run clean build**:
   ```bash
   ./gradlew clean test
   ```

2. **Check for flaky tests**:
   ```bash
   ./gradlew test --rerun-tasks
   ```

3. **Run specific failing test**:
   ```bash
   ./gradlew test --tests "FailingTest" --info
   ```

### Slow Tests

1. **Parallel execution**:
   ```bash
   ./gradlew test --parallel --max-workers=4
   ```

2. **Skip unrelated tests**:
   ```bash
   ./gradlew :kmptaskmanager:test  # Only library tests
   ```

### Memory Issues

```bash
# Increase Gradle memory
export GRADLE_OPTS="-Xmx4096m"
./gradlew test
```

### Platform-Specific Issues

**Android:**
```bash
# Clear WorkManager database
adb shell pm clear com.example.app
```

**iOS:**
```bash
# Reset simulator
xcrun simctl shutdown all
xcrun simctl erase all
```

## 📚 Additional Resources

- [Kotlin Test Documentation](https://kotlinlang.org/api/latest/kotlin.test/)
- [Android Testing Guide](https://developer.android.com/training/testing)
- [XCTest Documentation](https://developer.apple.com/documentation/xctest)
- [Kotlinx Coroutines Test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)

---

**Happy Testing!** 🎉

For questions, see [CONTRIBUTING.md](CONTRIBUTING.md) or open a [discussion](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions).

**Last Updated:** December 2025
