# Migrating to v3.1.0

This guide covers upgrading from any v3.0.x release to v3.1.0.

> **TL;DR.** v3.1.0 is a **drop-in, additive** upgrade — no code changes are required. It adds
> one new opt-in constraint, `Constraints.maxRetries`, and fixes an iOS chain-retry bug. Just
> bump the version.

---

## Bump the version

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:3.1.0")
    // ONLY if you use the built-in HTTP workers (Http*/ParallelHttp*):
    implementation("dev.brewkits:kmpworkmanager-http:3.1.0")
}
```

No breaking changes. Existing code compiles and behaves exactly as before unless you opt into
`maxRetries` (see below).

---

## New: `Constraints.maxRetries`

A hard ceiling on retry attempts. `maxRetries = N` allows at most **N + 1** total runs
(1 initial + N retries), after which the task is marked a permanent failure.

```kotlin
scheduler.enqueue(
    id = "sync",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "SyncWorker",
    constraints = Constraints(
        backoffPolicy = BackoffPolicy.EXPONENTIAL,
        backoffDelayMs = 10_000,
        maxRetries = 3,          // at most 4 runs total, then give up
    ),
)
```

Behavior details:

- Caps both `WorkerResult.Failure(shouldRetry = true)` and a `WorkerResult.Retry` that carries no
  explicit `attemptCap`. A per-result `attemptCap` still takes precedence over `maxRetries`.
- **Applies to one-time and chained tasks only.** Periodic tasks ignore it — a periodic task runs
  indefinitely by design, so a total-run ceiling is meaningless.
- The default `-1` preserves each platform's prior behavior:
  - **Android:** uncapped (WorkManager quota/backoff governs the upper bound).
  - **iOS:** 5 attempts for single tasks; a budget of 3 whole-chain retries.

If you never set `maxRetries`, nothing changes for your app.

### Why this exists

On Android, WorkManager has no native max-retry API, so returning
`WorkerResult.Failure(shouldRetry = true)` retried the task until the OS quota ran out — there was
no way to bound it from the worker. `maxRetries` closes that gap and mirrors the iOS retry budget,
so the two platforms now agree on what a retry ceiling means.

---

## Bug fix: iOS chains now actually retry

Prior to v3.1.0, an iOS **chain** that failed a step with retries still remaining was dropped from
the execution queue and never retried (its on-disk definition and progress were also left behind).
As of v3.1.0 such a chain is correctly re-enqueued and retried on a later BGTask invocation, up to
its retry budget, then abandoned cleanly.

- **No action required.** If your chains relied on the (broken) old behavior of a failed chain
  simply stopping, note that a chain returning `Failure(shouldRetry = true)` will now be retried.
  Return `Failure(shouldRetry = false)` for terminal failures that must not retry (this already
  abandoned the chain immediately and is unchanged).

---

## Security / maintenance

v3.1.0 also clears the outstanding CodeQL code-scanning findings (deprecated `NetworkInfo` API on
an unreachable pre-API-23 branch, an ignored `File` return status, and the demo app's default
`allowBackup`). These are internal/demo-only changes with no impact on the public API.
