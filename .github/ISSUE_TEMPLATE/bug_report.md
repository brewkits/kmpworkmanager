---
name: Bug Report
about: Report a bug to help us improve KMP WorkManager
title: '[BUG] '
labels: bug
assignees: ''
---

## Bug Description

<!-- A clear and concise description of what the bug is -->

## Expected Behavior

<!-- What you expected to happen -->

## Actual Behavior

<!-- What actually happened -->

## Steps to Reproduce

1.
2.
3.
4.

## Code Sample

```kotlin
// Minimal code sample that reproduces the issue (enqueueTask/enqueueChain call,
// worker definition, Constraints, etc.)

```

## Environment

**KMP WorkManager Version:**
<!-- e.g., 3.4.1 -->

**Platform:**
- [ ] Android
- [ ] iOS

**Platform Details:**
- Android API Level:
- iOS Version:
- Device/Simulator:

**Modules used:**
- [ ] kmpworker
- [ ] kmpworker-http
- [ ] kmpworker-ksp
- [ ] kmpworker-annotations

**Dependencies:**
- Kotlin:
- Kotlin Multiplatform plugin:

**Build Configuration:**
```kotlin
// Relevant parts of build.gradle.kts (worker registration, KSP setup)

```

## Logs/Stack Trace

```
// Paste relevant logs or stack traces here.
// On iOS, background storage/queue failures are logged via the structured Logger —
// include the surrounding lines, not just the failing one.

```

## Screenshots

<!-- If applicable, add screenshots to help explain the problem -->

## Workaround

<!-- If you found a temporary workaround, describe it here -->

## Additional Context

<!-- Add any other context about the problem here -->

## Checklist

- [ ] I have searched existing issues
- [ ] I am using the latest version of KMP WorkManager
- [ ] I have included logs / stack trace
- [ ] I have provided a minimal code sample
- [ ] I have tested on a real device (if applicable)
