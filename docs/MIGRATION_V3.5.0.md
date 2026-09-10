# Migration to v3.5.0

**TL;DR:** two source-compatible breaking changes, both of which fix a wrong answer. Most
projects upgrade by bumping the version and recompiling. Check this page if you hardcoded
`4096` for a media-processing foreground service, or if a test asserts `isPending()` after
`cancelAll()`.

Nothing was removed. No API you call today disappears in 3.5.0.

```kotlin
implementation("dev.brewkits:kmpworkmanager:3.5.0")
implementation("dev.brewkits:kmpworkmanager-http:3.5.0")   // optional — Ktor 3 HTTP workers
```

---

## 1. `KmpHeavyWorker.FGS_MEDIA_PROCESSING` is now `8192`, was `4096`

**Affects:** Android apps that run a `KmpHeavyWorker` with
`foregroundServiceType = FGS_MEDIA_PROCESSING`, *and* wrote `4096` somewhere of their own to
match what the library was sending.

`4096` was not the media-processing type. It is not any foreground-service type at all — the
platform constant `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` is `8192`. On
Android 15 the consequence was total: every media-processing heavy worker threw
`ForegroundServiceTypeException` and retried forever.

The constant now reads from the platform instead of declaring its own literal:

```kotlin
@JvmField val FGS_MEDIA_PROCESSING: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
```

### What to do

If you worked around the old value, remove the workaround:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />

<application>
    <service
        android:name="androidx.work.impl.foreground.SystemForegroundService"
        android:foregroundServiceType="mediaProcessing"
        tools:node="merge" />
</application>
```

See the [Android FGS Type Guide](ANDROID_FGS_GUIDE.md) for the full per-type table.

If you never hardcoded anything and simply used the constant, you need to do nothing beyond
recompiling — which you get for free by bumping the dependency.

### Why it is a `val` and not a `const`

Worth knowing if you are used to seeing these as `const val`. A `const val` is inlined into
*your* bytecode at *your* compile time, so an app that bumped the dependency without
recompiling would have carried the stale `4096` regardless. A field read picks up the corrected
value on a version bump alone.

The ABI shape is identical either way — both emit
`public static final field FGS_MEDIA_PROCESSING I` — so this is not a binary-compatibility
break, and `apiCheck` stays green across the change.

---

## 2. `FakeBackgroundTaskScheduler.isPending(id)` returns `false` after `cancelAll()`

**Affects:** tests using `kmpworker-testing` that call `cancelAll()` and then assert on
`isPending(...)`.

`cancelAll()` sets a flag without touching `cancelledIds`, and `isPending()` only consulted
`cancelledIds`. So after cancelling everything, `isPending("x")` kept answering `true` — while
`pendingTaskCount()`, answering the same question about the same state, correctly returned `0`.

```kotlin
val fake = FakeBackgroundTaskScheduler()
fake.enqueueTask(TaskRequest(id = "x", /* … */))
fake.cancelAll()

fake.pendingTaskCount()   // 0     — was already correct
fake.isPending("x")       // false — was true before 3.5.0
```

### What to do

A test that asserted the old behaviour now fails:

```kotlin
fake.cancelAll()
assertTrue(fake.isPending("x"))    // ← now red, correctly
```

Invert it. The assertion was passing on a wrong answer, so the fix is to assert what you
actually meant:

```kotlin
fake.cancelAll()
assertFalse(fake.isPending("x"))
```

`kmpworker-testing` had no tests of its own before this release. Its first eight found this.

---

## New in 3.5.0 — opt-in, nothing required

### `kmpworker-testing` is published for the first time

The module exists in 3.4.1's source tree but was never published. It ships starting with
3.5.0; there is no earlier version to depend on.

```kotlin
commonTest.dependencies {
    implementation("dev.brewkits:kmpworker-testing:3.5.0")
}
```

### TLS certificate pinning

Off unless you configure it, so upgrading changes nothing on its own.

```kotlin
HttpClientProvider.configurePinning(
    listOf(
        CertificatePin(
            hostname = "api.example.com",
            sha256Pins = listOf(
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",   // current leaf
                "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",   // backup
            ),
        ),
    ),
)
```

Two things to get right before you turn this on:

- **Always declare a backup pin.** A single pin means the next certificate rotation bricks
  every installed copy of your app, and you cannot fix it from the server side. This is the
  classic way pinning goes wrong.
- **Call it before the first HTTP worker runs.** `HttpClientProvider` builds its client on
  first use. Calling `configurePinning` afterwards is safe — it closes and rebuilds the client
  — but you lose the existing connection pool.

---

## Behaviour changes you do not need to act on

These need no code change, but they alter observable behaviour, so they are worth knowing if
you have tests or dashboards watching for the old shape.

| Change | What you might notice |
|---|---|
| Retry backoff now has equal jitter on both platforms | Retries after a mass failure spread out instead of arriving in one spike. Any test asserting an exact retry timestamp needs a tolerance. |
| Chain steps now share the 10 240-byte WorkManager cap, split 2 048 input / 6 144 output | A chain step's input and its predecessor's output used to be checked against 8 KB *independently*, so a legal pair could meet at ~16 KB and kill the chain inside `WorkerWrapper` before any worker code ran. Now: input over its share spills losslessly to a `cacheDir` file; output over its share is dropped and logged as `WARN` (`output too large to forward to the next chain step`), and the next step sees `Data.EMPTY`. Standalone tasks are unaffected and keep the full 8 KB. If you forward large payloads between chain steps, pass a file path or row id instead of the bytes. |
| `cancelByTag()` / `cancelByWorkerClass()` log a `WARN` when unimplemented | Custom `BackgroundTaskScheduler` implementations that never implemented tagging now say so once per member, instead of silently cancelling nothing. |
| iOS log lines with `%` render correctly | `NSLog` was being handed log text as its format string. Log output changes shape only where a message contained `%`. |
| iOS: dot-prefixed task and chain ids are now listed | Ids beginning with `.` were invisible to every listing API on iOS. If you have such ids on disk from an earlier version, they will start appearing. |

---

## Removed API

None. Nothing was removed in 3.5.0.

---

## Full detail

[`docs/release-notes/v3.5.0-RELEASE-NOTES.md`](release-notes/v3.5.0-RELEASE-NOTES.md) covers all
twenty-one fixes, the internal refactors, and what was verified on real hardware.
