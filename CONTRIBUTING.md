# 🤝 Contributing to KMP TaskManager

Thank you for your interest in contributing to KMP TaskManager! This guide will help you get started with contributing to the project.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Documentation Guidelines](#documentation-guidelines)
- [Pull Request Process](#pull-request-process)
- [Release Process](#release-process)
- [Community](#community)

## 📜 Code of Conduct

This project adheres to a Code of Conduct that all contributors are expected to follow. Please be respectful and constructive in all interactions.

### Our Standards

- **Be welcoming**: Welcome newcomers and encourage diverse perspectives
- **Be respectful**: Disagree respectfully and assume good intentions
- **Be collaborative**: Work together to resolve conflicts
- **Be focused**: Stay on topic and be productive

## 🚀 Getting Started

### Ways to Contribute

1. **Report Bugs**: Found a bug? [Open an issue](https://github.com/vietnguyentuan2019/KMPTaskManager/issues)
2. **Suggest Features**: Have an idea? [Start a discussion](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions)
3. **Fix Issues**: Browse [open issues](https://github.com/vietnguyentuan2019/KMPTaskManager/issues) and submit PRs
4. **Improve Documentation**: Help make our docs better
5. **Write Tests**: Increase test coverage
6. **Share Knowledge**: Answer questions in discussions

### Good First Issues

Look for issues tagged with `good first issue` - these are great entry points for new contributors.

## 🛠️ Development Setup

### Prerequisites

- **JDK 17+**: Required for Kotlin compilation
- **Android Studio Hedgehog+** (2023.1.1 or newer)
- **Xcode 15+**: For iOS development (macOS only)
- **Git**: For version control

### Clone the Repository

```bash
git clone https://github.com/vietnguyentuan2019/KMPTaskManager.git
cd KMPTaskManager
```

### Project Structure

```
KMPTaskManager/
├── kmptaskmanager/          # Library module
│   ├── src/
│   │   ├── commonMain/      # Shared Kotlin code
│   │   ├── commonTest/      # Shared tests
│   │   ├── androidMain/     # Android-specific code
│   │   └── iosMain/         # iOS-specific code
│   └── build.gradle.kts
├── composeApp/              # Demo application
│   ├── src/
│   │   ├── commonMain/      # Shared UI code
│   │   ├── androidMain/     # Android app
│   │   └── iosMain/         # iOS app
│   └── build.gradle.kts
├── gradle/                  # Gradle configuration
├── ARCHITECTURE.md          # Architecture documentation
├── DEMO_GUIDE.md           # Demo app guide
├── ROADMAP.md              # Project roadmap
└── README.md               # Main documentation
```

### Build the Project

```bash
# Build everything
./gradlew build

# Build only library
./gradlew :kmptaskmanager:build

# Build demo app
./gradlew :composeApp:assembleDebug  # Android
```

### Run Tests

```bash
# Run all tests
./gradlew test

# Run specific test file
./gradlew :kmptaskmanager:testDebugUnitTest --tests "io.kmp.taskmanager.ContractsTest"

# Run with coverage
./gradlew test jacocoTestReport
```

### Run Demo App

**Android:**
```bash
./gradlew :composeApp:installDebug
adb shell am start -n com.example.kmpworkmanagerv2/.MainActivity
```

**iOS:**
1. Open `iosApp/iosApp.xcodeproj` in Xcode
2. Select target device/simulator
3. Press `Cmd + R` to run

## 🔄 Development Workflow

### 1. Create a Branch

Always create a new branch for your work:

```bash
git checkout -b feature/your-feature-name
# or
git checkout -b fix/bug-description
```

**Branch naming conventions:**
- `feature/description` - New features
- `fix/description` - Bug fixes
- `docs/description` - Documentation updates
- `test/description` - Test additions
- `refactor/description` - Code refactoring

### 2. Make Changes

- Write clean, readable code
- Follow existing code style
- Add tests for new functionality
- Update documentation as needed

### 3. Test Your Changes

```bash
# Before committing, always run:
./gradlew build          # Full build
./gradlew test           # All tests
./gradlew lint           # Code quality checks (Android)
```

### 4. Commit Your Changes

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```bash
git add .
git commit -m "feat: add network retry logic"
# or
git commit -m "fix: resolve ANR in WorkManager initialization"
# or
git commit -m "docs: update API documentation for TaskChain"
```

**Commit message format:**
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks
- `perf`: Performance improvements

### 5. Push and Create PR

```bash
git push origin feature/your-feature-name
```

Then create a Pull Request on GitHub.

## 💻 Coding Standards

### Kotlin Style Guide

Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

```kotlin
// ✅ Good
fun scheduleTask(
    id: String,
    trigger: TaskTrigger,
    constraints: Constraints = Constraints()
): ScheduleResult {
    require(id.isNotBlank()) { "Task ID must not be blank" }
    // Implementation
}

// ❌ Bad
fun scheduleTask(id:String,trigger:TaskTrigger,constraints:Constraints=Constraints()):ScheduleResult{
    // Implementation
}
```

### Documentation

Every public API must have KDoc:

```kotlin
/**
 * Schedules a background task with the specified configuration.
 *
 * This method enqueues a task to be executed by the platform's native scheduler
 * (WorkManager on Android, BGTaskScheduler on iOS).
 *
 * @param id Unique identifier for this task. Used for cancellation and updates.
 * @param trigger Defines when the task should run (OneTime, Periodic, Exact, etc.)
 * @param workerClassName Fully qualified class name of the worker implementation
 * @param constraints Optional execution constraints (network, battery, etc.)
 * @param inputJson Optional JSON string to pass data to the worker
 * @param policy How to handle existing task with same ID (KEEP or REPLACE)
 * @return ScheduleResult indicating if the task was ACCEPTED, REJECTED, or THROTTLED
 *
 * @throws IllegalArgumentException if id is blank or trigger is invalid
 *
 * @sample
 * ```kotlin
 * val result = scheduler.enqueue(
 *     id = "sync-task",
 *     trigger = TaskTrigger.Periodic(intervalMs = 15.minutes.inWholeMilliseconds),
 *     workerClassName = "com.example.SyncWorker",
 *     constraints = Constraints(requiresNetwork = true)
 * )
 * ```
 */
suspend fun enqueue(...): ScheduleResult
```

### Code Organization

- **One class per file** (except nested/inner classes)
- **Group related functions** together
- **Order**: Public API → Internal → Private
- **Imports**: Organize and remove unused

### Platform-Specific Code

Use `expect`/`actual` for platform differences:

```kotlin
// commonMain
expect class NativeTaskScheduler : BackgroundTaskScheduler

// androidMain
actual class NativeTaskScheduler(
    private val context: Context
) : BackgroundTaskScheduler {
    // Android implementation
}

// iosMain
actual class NativeTaskScheduler(
    private val workerFactory: IosWorkerFactory,
    private val taskIds: Set<String>
) : BackgroundTaskScheduler {
    // iOS implementation
}
```

### Error Handling

- Use `Result` type for operations that can fail
- Throw exceptions for programmer errors
- Log errors with appropriate level

```kotlin
// ✅ Good
suspend fun executeTask(): Result<String> {
    return try {
        val result = performWork()
        Result.success(result)
    } catch (e: Exception) {
        Logger.e(LogTags.WORKER, "Task failed: ${e.message}")
        Result.failure(e)
    }
}

// ❌ Bad - swallowing exceptions
suspend fun executeTask(): String? {
    return try {
        performWork()
    } catch (e: Exception) {
        null  // Lost error information
    }
}
```

## 🧪 Testing Guidelines

### Test Structure

```kotlin
class FeatureTest {
    // Setup
    private lateinit var scheduler: BackgroundTaskScheduler

    @BeforeTest
    fun setup() {
        // Initialize test dependencies
    }

    @AfterTest
    fun teardown() {
        // Cleanup
    }

    @Test
    fun `descriptive test name in backticks`() {
        // Given (setup)
        val input = createTestData()

        // When (action)
        val result = performAction(input)

        // Then (assertion)
        assertEquals(expected, result)
    }
}
```

### Test Coverage Goals

- **Common code**: 85%+ coverage
- **Platform-specific**: Integration tests (manual)
- **Critical paths**: 100% coverage (scheduling, execution)

### Test Categories

1. **Unit Tests** (`commonTest/`)
   - Business logic
   - Data classes
   - Utilities
   - No platform dependencies

2. **Integration Tests** (Manual)
   - Android WorkManager integration
   - iOS BGTaskScheduler integration
   - Full end-to-end flows

3. **UI Tests** (`composeApp/`)
   - Demo app functionality
   - User interaction flows

### Writing Good Tests

```kotlin
// ✅ Good test
@Test
fun `TaskChain with empty list should throw IllegalArgumentException`() {
    val scheduler = MockScheduler()
    val chain = scheduler.beginWith(TaskRequest("Worker1"))

    assertFailsWith<IllegalArgumentException> {
        chain.then(emptyList())
    }
}

// ❌ Bad test
@Test
fun test1() {  // Unclear name
    val s = MockScheduler()  // Unclear variable
    val c = s.beginWith(TaskRequest("W1"))  // Abbreviated
    // Missing assertion!
}
```

### Edge Cases to Test

- Null values
- Empty strings/collections
- Boundary values (0, MAX_VALUE, MIN_VALUE)
- Negative values
- Very large inputs
- Concurrent access

## 📝 Documentation Guidelines

### Types of Documentation

1. **Code Documentation** (KDoc)
   - Public APIs (required)
   - Complex logic (recommended)
   - Platform differences (required)

2. **User Documentation**
   - README.md - Getting started
   - DEMO_GUIDE.md - Demo app usage
   - ARCHITECTURE.md - Technical details

3. **Contributor Documentation**
   - CONTRIBUTING.md (this file)
   - ROADMAP.md - Future plans

### Documentation Standards

- **Be clear and concise**
- **Provide examples** for complex APIs
- **Explain the "why"**, not just the "what"
- **Keep up-to-date** with code changes
- **Use diagrams** for architecture

### Example Documentation

```kotlin
/**
 * Creates a task chain for sequential and parallel execution.
 *
 * Task chains allow you to define complex workflows where tasks execute in
 * a specific order, with support for parallel execution within steps.
 *
 * **Example: Sequential Chain**
 * ```kotlin
 * scheduler.beginWith(TaskRequest("Step1"))
 *     .then(TaskRequest("Step2"))
 *     .then(TaskRequest("Step3"))
 *     .enqueue()
 * ```
 *
 * **Example: Mixed Chain (Sequential + Parallel)**
 * ```kotlin
 * scheduler.beginWith(TaskRequest("Init"))
 *     .then(listOf(
 *         TaskRequest("ParallelTask1"),
 *         TaskRequest("ParallelTask2")
 *     ))
 *     .then(TaskRequest("Finalize"))
 *     .enqueue()
 * ```
 *
 * ## Platform Behavior
 *
 * ### Android
 * - Uses WorkManager's `WorkContinuation` API
 * - Native support for parallel execution
 * - Constraints applied to each task individually
 *
 * ### iOS
 * - Custom chain executor with serialization
 * - Parallel tasks use coroutines
 * - Limited by 30s/60s execution window
 * - All tasks in chain share same BGTask invocation
 *
 * @param task The first task to execute
 * @return TaskChain builder for fluent API
 *
 * @see TaskChain
 * @see TaskRequest
 */
fun beginWith(task: TaskRequest): TaskChain
```

## 🔀 Pull Request Process

### Before Submitting PR

- [ ] Code builds successfully
- [ ] All tests pass
- [ ] New features have tests
- [ ] Documentation is updated
- [ ] Lint checks pass
- [ ] No merge conflicts with main

### PR Title Format

Follow conventional commits format:

```
feat: add retry mechanism for failed tasks
fix: resolve iOS chain execution timeout
docs: update README with iOS 17 changes
```

### PR Description Template

```markdown
## Description
Brief description of changes

## Type of Change
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update

## How Has This Been Tested?
Describe the tests you ran to verify your changes

## Checklist
- [ ] My code follows the style guidelines
- [ ] I have performed a self-review
- [ ] I have commented my code where necessary
- [ ] I have updated the documentation
- [ ] My changes generate no new warnings
- [ ] I have added tests that prove my fix is effective or that my feature works
- [ ] New and existing unit tests pass locally
- [ ] Any dependent changes have been merged and published

## Screenshots (if applicable)
Add screenshots for UI changes

## Additional Notes
Any additional information for reviewers
```

### Code Review Process

1. **Automated Checks**: GitHub Actions run tests and lint
2. **Peer Review**: At least one maintainer reviews code
3. **Feedback**: Address review comments
4. **Approval**: Maintainer approves PR
5. **Merge**: Maintainer merges to main

### Review Criteria

Reviewers will check:
- Code quality and style
- Test coverage
- Documentation completeness
- Performance implications
- Security considerations
- Platform compatibility

## 🚢 Release Process

### Version Numbering

We follow [Semantic Versioning](https://semver.org/):

- **Major (X.0.0)**: Breaking changes
- **Minor (x.X.0)**: New features, backwards compatible
- **Patch (x.x.X)**: Bug fixes, backwards compatible

### Release Checklist

1. Update version in `gradle.properties` and `build.gradle.kts`
2. Update `CHANGELOG.md` with changes
3. Update `ROADMAP.md` to reflect completed items
4. Run full test suite
5. Build release artifacts
6. Create Git tag: `git tag v2.2.0`
7. Push to GitHub: `git push origin v2.2.0`
8. Publish to Maven Central (maintainers only)
9. Create GitHub Release with notes
10. Announce on social media/forums

## 🌐 Community

### Getting Help

- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: Questions and general discussion
- **Email**: vietnguyentuan@gmail.com

### Asking Questions

When asking for help:
1. Search existing issues first
2. Provide minimal reproducible example
3. Include platform details (Android/iOS version)
4. Share relevant logs
5. Describe expected vs actual behavior

### Reporting Bugs

Use this template:

```markdown
**Describe the bug**
A clear description of what the bug is.

**To Reproduce**
Steps to reproduce:
1. Schedule task with '...'
2. Set constraint to '...'
3. See error

**Expected behavior**
What you expected to happen.

**Actual behavior**
What actually happened.

**Environment:**
- Platform: [Android/iOS]
- OS Version: [e.g., Android 14, iOS 17]
- Library Version: [e.g., 2.2.0]
- Device: [e.g., Pixel 7, iPhone 15]

**Logs**
```
Paste relevant logs here
```

**Additional context**
Any other information about the problem.
```

### Feature Requests

Use this template:

```markdown
**Is your feature request related to a problem?**
Describe the problem.

**Describe the solution you'd like**
What you want to happen.

**Describe alternatives you've considered**
Other solutions you've considered.

**Additional context**
Any other relevant information.
```

## 🎓 Learning Resources

### Kotlin Multiplatform
- [Official KMP Docs](https://kotlinlang.org/docs/multiplatform.html)
- [KMP by Example](https://kotlinlang.org/docs/multiplatform-samples.html)

### Android Background Work
- [WorkManager Guide](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Background Work Overview](https://developer.android.com/guide/background)

### iOS Background Tasks
- [BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
- [WWDC 2019 Session](https://developer.apple.com/videos/play/wwdc2019/707/)

## 🙏 Thank You!

Your contributions make this project better. We appreciate your time and effort!

---

**Questions?** Open a [discussion](https://github.com/vietnguyentuan2019/KMPTaskManager/discussions) or email vietnguyentuan@gmail.com

**Last Updated:** December 2025
