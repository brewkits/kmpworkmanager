# Mutation Testing Guide

Mutation testing for KMP WorkManager to ensure test suite quality.

---

## What is Mutation Testing?

Mutation testing evaluates the **quality of your tests** by introducing small code changes (mutations) and checking if tests fail. If tests pass despite mutations, it indicates weak test coverage.

**Example:**
```kotlin
// Original code
if (count > 0) { ... }

// Mutation 1: Change operator
if (count >= 0) { ... }  // Should fail a test

// Mutation 2: Change constant
if (count > 1) { ... }   // Should fail a test

// Mutation 3: Negate condition
if (count <= 0) { ... }  // Should fail a test
```

If tests pass after these mutations, your tests aren't thorough enough.

---

## Why Mutation Testing?

**Traditional Code Coverage Problems:**
- 85% line coverage doesn't mean quality tests
- Tests might execute code but not verify behavior
- False sense of security

**Mutation Testing Benefits:**
- Reveals weak assertions
- Finds redundant tests
- Improves test quality
- Validates edge case handling

---

## Mutation Testing for KMP WorkManager

### Current Status (v2.3.2)

**Line Coverage:** 85% (excellent)
**Mutation Coverage:** TBD (to be measured)

**Target Mutation Coverage:** >75% (industry standard)

---

## Tools for Mutation Testing

### 1. Pitest (JVM/Android only)

**Limitations:**
- Only works for JVM/Android code
- Does not support Kotlin Multiplatform common code
- Cannot test iOS-specific code

**Setup:**

Add to `build.gradle.kts`:
```kotlin
plugins {
    id("info.solidsoft.pitest") version "1.15.0"
}

pitest {
    targetClasses.set(listOf("dev.brewkits.kmpworkmanager.*"))
    targetTests.set(listOf("dev.brewkits.kmpworkmanager.*Test"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutators.set(listOf("DEFAULTS"))
    timeoutConst.set(10000)
}
```

**Run:**
```bash
./gradlew pitest
open kmpworker/build/reports/pitest/index.html
```

**Expected Results:**
- Mutation coverage: 70-80%
- Strong tests kill >75% of mutants
- Surviving mutants indicate test gaps

---

### 2. Stryker (JavaScript/TypeScript)

Not applicable for Kotlin Multiplatform.

---

### 3. Manual Mutation Testing

For common/iOS code, perform manual mutation testing:

#### Step 1: Identify Critical Code

Target high-risk areas:
- SecurityValidator (SSRF protection)
- ChainExecutor (iOS chain logic)
- AppendOnlyQueue (memory optimization)
- NativeTaskScheduler (platform integration)

#### Step 2: Introduce Mutations

**Example: SecurityValidator**

Original:
```kotlin
fun isPrivateIPv4(hostname: String): Boolean {
    val parts = hostname.split(".")
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }

    return when {
        octets[0] == 10 -> true // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true // 192.168.0.0/16
        else -> false
    }
}
```

**Mutation 1:** Change `==` to `!=`
```kotlin
octets[0] != 10 -> true  // Should fail SecurityValidatorTest
```

**Mutation 2:** Change range `16..31` to `16..32`
```kotlin
octets[1] in 16..32 -> true  // Should fail testPrivateIPv4
```

**Mutation 3:** Remove condition
```kotlin
// Comment out: octets[0] == 192 && octets[1] == 168 -> true
// Should fail testBlockPrivate192_168Network
```

#### Step 3: Run Tests

```bash
./gradlew :kmpworker:testDebugUnitTest
```

**Expected:** All tests should **FAIL** after mutations
**If tests pass:** Test coverage gap detected

#### Step 4: Fix Weak Tests

Add assertions for surviving mutations:
```kotlin
@Test
fun testPrivateIPv4DetectionComprehensive() {
    // Test boundary conditions
    assertTrue(SecurityValidator.validateURL("http://172.15.0.1")) // Just outside range
    assertFalse(SecurityValidator.validateURL("http://172.16.0.1")) // Start of range
    assertFalse(SecurityValidator.validateURL("http://172.31.255.255")) // End of range
    assertTrue(SecurityValidator.validateURL("http://172.32.0.1")) // Just outside range
}
```

---

## Mutation Testing Checklist

### Common Mutations to Test

**Arithmetic Operators:**
- [ ] `+` → `-`
- [ ] `*` → `/`
- [ ] `++` → `--`

**Relational Operators:**
- [ ] `>` → `>=`
- [ ] `<` → `<=`
- [ ] `==` → `!=`

**Logical Operators:**
- [ ] `&&` → `||`
- [ ] `!` → (remove)

**Boundary Values:**
- [ ] `0` → `1`
- [ ] `size` → `size - 1`
- [ ] `range.start` → `range.start + 1`

**Return Values:**
- [ ] `true` → `false`
- [ ] `null` → non-null
- [ ] `Success` → `Failure`

**Conditional Statements:**
- [ ] `if (condition)` → `if (!condition)`
- [ ] `if (x == 0)` → `if (x != 0)`

---

## Critical Mutation Tests for KMP WorkManager

### 1. SecurityValidator Mutations

**Target:** SSRF protection logic

```kotlin
// Original
if (hostname.startsWith("169.254.")) return true

// Mutation 1: Change prefix
if (hostname.startsWith("169.253.")) return true  // MUST FAIL test

// Mutation 2: Change logic
if (!hostname.startsWith("169.254.")) return true  // MUST FAIL test
```

**Tests that should fail:**
- `testBlock169_254LinkLocal`
- `testSSRFProtectionIntegration`

---

### 2. ChainExecutor Mutations (iOS)

**Target:** Time-slicing logic

```kotlin
// Original
val timeoutMs = when (taskType) {
    BGTaskType.PROCESSING -> 255_000L  // 255s
    BGTaskType.APP_REFRESH -> 25_500L   // 25.5s
}

// Mutation 1: Increase timeout (dangerous!)
BGTaskType.PROCESSING -> 300_000L  // 300s - should cause timeout failures

// Mutation 2: Swap values
BGTaskType.PROCESSING -> 25_500L  // Wrong timeout - should fail chain tests
```

**Tests that should fail:**
- `ChainExecutorTest.testProcessingTaskTimeout`
- `ChainExecutorTest.testAppRefreshTaskTimeout`

---

### 3. AppendOnlyQueue Mutations (iOS)

**Target:** O(1) memory optimization

```kotlin
// Original
private const val READ_CHUNK_SIZE = 8192  // 8KB chunks

// Mutation: Increase chunk size
private const val READ_CHUNK_SIZE = 81920  // 80KB - defeats optimization

// Original
fun loadChainIds(): List<String> {
    return file.useLines { lines ->
        lines.take(1000).toList()  // Limit memory usage
    }
}

// Mutation: Remove limit
lines.toList()  // Load entire file - should fail memory tests
```

**Tests that should fail:**
- `QueueOptimizationTest.testQueueMemoryO1`
- `QueueOptimizationTest.testLargeQueueMemoryUsage`

---

## Mutation Coverage Report Template

```markdown
# Mutation Testing Results

**Date:** 2026-02-16
**Version:** 2.3.2
**Tool:** Pitest (Android) + Manual (Common/iOS)

## Summary

| Module | Total Mutants | Killed | Survived | Coverage |
|--------|---------------|--------|----------|----------|
| SecurityValidator | 45 | 40 | 5 | 88.9% |
| ChainExecutor | 32 | 28 | 4 | 87.5% |
| AppendOnlyQueue | 28 | 25 | 3 | 89.3% |
| NativeTaskScheduler | 50 | 38 | 12 | 76.0% |
| **Total** | **155** | **131** | **24** | **84.5%** |

## Surviving Mutants Analysis

### 1. SecurityValidator (5 survivors)

**Mutant:** Changed `octets[0] == 10` to `octets[0] == 11`
**Status:** Survived
**Action:** Add test for boundary around 10.x.x.x range

**Mutant:** Removed null check in `extractHostname()`
**Status:** Survived
**Action:** Add test with malformed URLs

### 2. ChainExecutor (4 survivors)

**Mutant:** Increased cleanup timeout by 1 second
**Status:** Survived
**Action:** Add precise timing assertion

## Recommendations

1. Add 15 new test cases for surviving mutants
2. Improve assertion precision in 8 existing tests
3. Target mutation coverage >90% for critical modules
```

---

## Automating Mutation Testing

### GitHub Actions Workflow

```yaml
name: Mutation Testing

on:
  schedule:
    - cron: '0 2 * * 0'  # Weekly on Sunday
  workflow_dispatch:

jobs:
  mutation-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'

      - name: Run Pitest
        run: ./gradlew pitest

      - name: Upload Report
        uses: actions/upload-artifact@v3
        with:
          name: mutation-report
          path: kmpworker/build/reports/pitest/

      - name: Check Mutation Coverage
        run: |
          COVERAGE=$(grep -oP 'mutationCoverage>\K[0-9]+' kmpworker/build/reports/pitest/mutations.xml)
          if [ $COVERAGE -lt 75 ]; then
            echo "❌ Mutation coverage $COVERAGE% is below 75% threshold"
            exit 1
          fi
```

---

## Best Practices

### DO:

✅ Run mutation tests weekly (slow process)
✅ Focus on critical security code first
✅ Fix surviving mutants by adding tests
✅ Use mutation testing to guide test improvements
✅ Track mutation coverage over time

### DON'T:

❌ Don't run mutation tests in CI (too slow)
❌ Don't aim for 100% mutation coverage (diminishing returns)
❌ Don't ignore equivalent mutants (code that does the same thing)
❌ Don't trust line coverage alone

---

## Expected Mutation Coverage

### Realistic Targets by Module

| Module | Criticality | Target Coverage |
|--------|-------------|-----------------|
| SecurityValidator | HIGH | 90%+ |
| ChainExecutor | HIGH | 85%+ |
| AppendOnlyQueue | MEDIUM | 80%+ |
| NativeTaskScheduler | HIGH | 85%+ |
| Built-in Workers | MEDIUM | 75%+ |
| Utilities | LOW | 70%+ |

### Overall Target

- **Minimum:** 75% mutation coverage
- **Target:** 80-85% mutation coverage
- **Excellent:** 90%+ mutation coverage

---

## Resources

- [Pitest Documentation](https://pitest.org/)
- [Mutation Testing Best Practices](https://github.com/theofidry/awesome-mutation-testing)
- [OWASP Mutation Testing](https://owasp.org/www-community/controls/Mutation_Testing)

---

**Version:** 2.3.2
**Last Updated:** February 16, 2026
**Status:** 🔬 Experimental - Mutation testing in evaluation phase
