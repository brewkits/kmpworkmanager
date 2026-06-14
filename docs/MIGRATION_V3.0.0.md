# Migrating to v3.0.0

This guide covers upgrading from any v2.5.x release to v3.0.0.

> **TL;DR.** v3.0.0 (1) upgrades the HTTP layer from **Ktor 2 to Ktor 3**, and
> (2) **moves the built-in HTTP workers into a new `kmpworkmanager-http` artifact** so the
> core engine no longer depends on Ktor. If your app is already on Ktor 3 and uses the
> HTTP workers, add the new artifact + register `HttpWorkerRegistry`. If your app is
> **still on Ktor 2**, this is a breaking change — migrate to Ktor 3, or stay on `2.5.1`.

---

## Bump the version

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:3.0.0")
    // ONLY if you use the built-in HTTP workers (Http*/ParallelHttp*):
    implementation("dev.brewkits:kmpworkmanager-http:3.0.0")
}
```

### HTTP workers moved to `kmpworkmanager-http`

The core `kmpworkmanager` artifact no longer carries Ktor. The six Ktor-based workers
(`HttpRequestWorker`, `HttpSyncWorker`, `HttpDownloadWorker`, `HttpUploadWorker`,
`ParallelHttpDownloadWorker`, `ParallelHttpUploadWorker`) and `HttpClientProvider` now
live in `kmpworkmanager-http`.

- **If you don't use built-in HTTP workers:** do nothing — you just gained a slimmer,
  Ktor-free core.
- **If you do:** add the artifact above and register `HttpWorkerRegistry` alongside the
  core `BuiltinWorkerRegistry`:

  ```kotlin
  import dev.brewkits.kmpworkmanager.workers.HttpWorkerRegistry      // kmpworkmanager-http
  import dev.brewkits.kmpworkmanager.workers.BuiltinWorkerRegistry   // core (FileCompression)
  import dev.brewkits.kmpworkmanager.workers.CompositeWorkerFactory  // core

  val factory = CompositeWorkerFactory(
      MyWorkerFactory(),       // your workers
      HttpWorkerRegistry,      // Http*/ParallelHttp* workers
      BuiltinWorkerRegistry,   // FileCompressionWorker
  )
  ```

  Worker **class names and config packages are unchanged**, so existing `workerClassName`
  task IDs and persisted JSON keep working — only the dependency + registry wiring changes.

If you use the KSP-generated `WorkerFactory`:

```kotlin
dependencies {
    ksp("dev.brewkits:kmpworker-ksp:3.0.0")
    commonMain.implementation("dev.brewkits:kmpworker-annotations:3.0.0")
}
```

---

## Breaking change: Ktor 3 is now required

### Why you can't mix Ktor 2 and Ktor 3

Ktor 2 and Ktor 3 publish under the **same Maven coordinates** (`io.ktor:ktor-client-core`,
`ktor-client-okhttp`, `ktor-client-darwin`, …) and are **binary-incompatible** (Ktor 3
moved its IO layer to `kotlinx-io`, changing `ByteReadChannel` and friends).

Gradle resolves each module to a **single version** — by default the highest requested.
So if your app declares Ktor 2 and pulls in `kmpworkmanager:3.0.0` (Ktor 3), Gradle forces
Ktor **3** onto the whole classpath. Your own code, compiled against Ktor 2, then hits
Ktor 3 jars at runtime and fails with `NoSuchMethodError` / `NoClassDefFoundError`.

There is **no single artifact that can satisfy both** Ktor versions.

### Your options

| Situation | What to do |
|---|---|
| Already on Ktor 3 | Just bump to `3.0.0`. No code changes. |
| On Ktor 2, ready to migrate | Migrate your app to Ktor 3 ([Ktor 3 migration guide](https://ktor.io/docs/migrating-3.html)), then bump to `3.0.0`. |
| On Ktor 2, can't migrate yet | **Stay on `dev.brewkits:kmpworkmanager:2.5.1`.** The 2.5.x line continues to target Ktor 2. |

---

## Behavioral fix included in this release

Ktor 3's `ByteReadChannel.readAvailable()` returns **`-1` at end-of-stream** (Ktor 2
returned `0`). The built-in `HttpDownloadWorker` and `ParallelHttpDownloadWorker` read
loops now break on `-1`, preventing a potential spin / stale-buffer read at EOF.

If you wrote **custom workers** that read a Ktor `ByteReadChannel` with a
`while (!channel.isClosedForRead)` loop, apply the same guard:

```kotlin
val n = channel.readAvailable(buf, 0, buf.size)
if (n == -1) break // Ktor 3 EOF signal
if (n > 0) { /* ... */ }
```

---

## Checklist

- [ ] App (and all other libraries) are on Ktor 3, **or** you stayed on `kmpworkmanager:2.5.1`.
- [ ] Bumped `kmpworkmanager`, `kmpworker-ksp`, `kmpworker-annotations` to `3.0.0`.
- [ ] Custom `ByteReadChannel` read loops guard against `readAvailable() == -1`.
- [ ] HTTP download/upload tasks run green on both Android and iOS.
