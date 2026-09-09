# Test coverage — what is measured, and what is not

**Last updated:** 2026-09-09 · **Applies to:** v3.5.0

This document exists to answer one question honestly: *how much of this library is covered
by automated tests, and how much of that is actually measured rather than asserted?*

---

## Summary

| Area | Tests | Line coverage | How it is measured |
|---|---:|---|---|
| `kmpworker` — JVM/Android side | 232 | **≥ 62%** (CI-enforced floor) | Kover |
| `kmpworker-http` | 92 | **≥ 74%** (CI-enforced floor) | Kover |
| `kmpworker` — `commonMain` | 465 | included in the Kover figures above | Kover, via the JVM/Android target |
| `kmpworker` — `iosMain` | 549 | **not measured** | — |
| `kmpworker-ksp` | 29 | not measured | — |
| `kmpworker-testing` | 8 | not measured | — |

The floors are declared in each module's `build.gradle.kts` (`kover { reports { verify { rule
{ minBound(...) } } } }`) and enforced by `koverVerify`, which runs as part of `./gradlew
check`. They are floors, not current values — run the report to see where coverage actually
stands today.

```bash
./gradlew :kmpworker:koverHtmlReport :kmpworker-http:koverHtmlReport
# open kmpworker/build/reports/kover/html/index.html
```

CI publishes the same HTML as a downloadable artifact on every push to `main`
(`.github/workflows/coverage.yml`).

---

## The iOS gap

**Kover cannot instrument Kotlin/Native.** It works by instrumenting JVM bytecode, and
`iosMain` never becomes JVM bytecode. So the largest and most intricate part of this library
— roughly 12,200 lines implementing the queue, chain executor, file storage and BGTask
integration that exist *because* iOS has no WorkManager — has **no line-coverage number at
all**.

What can be said about it factually:

- It carries **549 tests**, more than any other single source set in the project.
- Those tests include the failure modes that matter most for a background-task library:
  `QA_PersistenceResilienceTest` (a 100-step chain killed at step 50 resumes at exactly step
  50), `AppendOnlyQueueCrcCorruptionTest`, `QueueCorruptionTest`,
  `QA_IosChainReplaceConcurrencyTest`, `IosRaceConditionTest`, `GracefulShutdownTest`.
- Two stress tests (`IosStorageStressTest`, `IosDynamicTaskDispatcherTest`) are excluded from
  the default run because they are flaky on constrained CI runners.

What **cannot** be said: any percentage. A test count is not coverage — it says nothing about
which branches are reached.

### Why not just turn on Kotlin/Native coverage?

Kotlin/Native has an experimental `-Xbinary-test-coverage` flag that emits LLVM profiling
data, which `llvm-profdata` + `llvm-cov` can turn into a report. Enabling it for this project
is tracked as planned work, not as something already done. **It is not currently enabled** —
if you are looking for it in the build files, it genuinely is not there.

---

## History of this document

Everything above replaces a version of this file that overstated the position, in ways worth
recording so they are not reintroduced:

| Prior claim | Reality |
|---|---|
| "Enabled `-Xbinary-test-coverage` for deep native analysis" | The flag appears in no build file in the repository, and never has |
| "iOS Logic: ~65.0% line, 100% branch — verified via binary instrumentation" | `IosBranchCoverageTest.kt` is an ordinary test class, not instrumentation; these numbers had no measurement behind them |
| Kover floors of "60%" and "70%" | The declared floors are 62% and 74% |
| "`TaskTrigger` — 100% — all trigger types (Periodic, OneTime, Windowed) covered" | Its own list omitted `Exact` and `ContentUri`, so the parenthetical and the percentage disagreed |
| "**KMP WorkManager v2.4.3** is certified as **Gold Master**" | Self-certification against no external standard |

The numbers in the old document were recorded at v2.4.3 and carried a warning that they could
not be reproduced. Numbers that cannot be reproduced should not be published at all — hence
this rewrite.
