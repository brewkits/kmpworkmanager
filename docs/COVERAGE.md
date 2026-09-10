# Test coverage — what is measured, and what is not

**Last updated:** 2026-09-10 · **Applies to:** v3.5.0

This document exists to answer one question honestly: *how much of this library is covered
by automated tests, and how much of that is actually measured rather than asserted?*

---

## Summary

| Area | Tests | Line coverage | How it is measured |
|---|---:|---|---|
| `kmpworker` — JVM/Android side | 253 | 73.3% measured, **≥ 70%** (CI-enforced floor) | Kover |
| `kmpworker-http` | 92 | 78.2% measured, **≥ 74%** (CI-enforced floor) | Kover |
| `kmpworker` — `commonMain` | 482 | included in the Kover figures above | Kover, via the JVM/Android target |
| `kmpworker` — `iosMain` | 608 | **not measured** | — |
| `kmpworker-ksp` | 29 | not measured | — |
| `kmpworker-testing` | 8 | not measured | — |

The floors are declared in each module's `build.gradle.kts` (`kover { reports { verify { rule
{ minBound(...) } } } }`) and enforced by `koverVerify`, which runs as part of `./gradlew
check`. They are floors, not current values, and the measured figures above were taken on
2026-09-10 — run the report to see where coverage stands today. The `kmpworker` floor moved
from 62% to 70% in v3.5.0 after new tests took the measured figure from 69.4% to 73.3%; a
floor left far below the real number stops being a ratchet and silently permits regressions.

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

- It carries **608 tests**, more than any other single source set in the project.
- Those tests include the failure modes that matter most for a background-task library:
  `QA_PersistenceResilienceTest` (a 100-step chain killed at step 50 resumes at exactly step
  50), `AppendOnlyQueueCrcCorruptionTest`, `QueueCorruptionTest`,
  `QA_IosChainReplaceConcurrencyTest`, `IosRaceConditionTest`, `GracefulShutdownTest`.
- Two stress tests (`IosStorageStressTest`, `IosDynamicTaskDispatcherTest`) are excluded from
  the default run because they are flaky on constrained CI runners.

What **cannot** be said: any percentage. A test count is not coverage — it says nothing about
which branches are reached.

### Why not just turn on Kotlin/Native coverage?

Because the flag no longer exists. This was measured in v3.5.0 rather than assumed.

The advice found in older articles is to pass `-Xbinary-test-coverage` (or, earlier still,
`-Xcoverage` + `-Xlibrary-to-cover`) to the Kotlin/Native compiler, which emits LLVM profiling
data that `llvm-profdata` + `llvm-cov` turn into a report. Passing either flag to the compilers
installed for this project produces:

```
warning: flag is not supported by this version of the compiler: -Xbinary-test-coverage
warning: flag is not supported by this version of the compiler: -Xcoverage
```

Reproduced against `kotlinc-native` **2.1.21** (the version this project builds with) and
against **2.4.0** and **2.4.10**; neither flag appears in `kotlinc-native -X` for any of the
three. So this is not a "not enabled yet" situation and upgrading Kotlin does not fix it:
there is currently no supported compiler-level coverage instrumentation for Kotlin/Native,
and an iOS line-coverage percentage for this library cannot be produced by that route.

If a coverage number for `iosMain` is ever required, the remaining options are to re-run the
same logic through a JVM target purely for measurement (which measures a different binary), or
to wait for the toolchain. Neither is planned. Until then this document reports iOS strength
as test counts and named failure modes, and says so explicitly instead of inventing a percentage.

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
