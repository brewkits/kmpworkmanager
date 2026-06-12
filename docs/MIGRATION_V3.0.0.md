# Migrating to v3.0.0

This guide covers upgrading from any v2.5.x release to v3.0.0.

> **TL;DR.** v3.0.0 upgrades the HTTP layer from **Ktor 2 to Ktor 3**. If your app
> is already on Ktor 3, this is a drop-in bump. If your app is **still on Ktor 2**,
> this is a **breaking change** — you must migrate to Ktor 3 as well, or stay on
> `2.5.1`. The library's own public API (worker constructors, configs,
> `HttpClientProvider`) is unchanged.

---

## Bump the version

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("dev.brewkits:kmpworkmanager:3.0.0")
}
```

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
