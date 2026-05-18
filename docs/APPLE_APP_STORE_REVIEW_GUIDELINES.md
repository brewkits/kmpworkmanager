# Apple App Store Review — Background Task Compliance

> **TL;DR.** The library lets you dispatch many different workers behind a single
> `BGTaskScheduler` identifier declared in `Info.plist`. That power is
> double-edged: misused, it triggers Apple's §2.5.4 review (which can reject or
> retroactively ban your app). This doc tells you which patterns are safe and
> which are review bait.

## The relevant App Store guidelines

| § | Guideline | What it means for this library |
|---|---|---|
| 2.5.4 | "Multitasking apps may only use background services for their intended purposes." | Every worker dispatched through a `BGTaskScheduler` identifier must serve a function the user clearly understands. |
| 2.5.1 | "Apps should use APIs and frameworks for their intended purposes." | Don't use `BGTaskScheduler` to keep your app alive for analytics, ad refresh, or to "pre-cache things just in case." |
| 4.1 | "Don't impersonate or mislead." | If `Info.plist` declares a single identifier called `com.acme.refresh`, the user-visible name and description must cover everything that actually runs under it. |
| 5.1.1 (iii) | "If your app collects user or usage data, you must secure user consent." | Background uploads of analytics / telemetry need the same consent UX as foreground uploads. |

Apple's automated review pipeline cannot inspect your worker chain — they
can't see what a chain step actually does. But **human reviewers do read your
app**, and **TestFlight & post-release auditors do strace your app**. If your
background usage doesn't match the description you provided at submission, you
will get a "Rejected — Guideline 2.5.4" notice with no further detail.

---

## Patterns that are safe

The library's dynamic-task-dispatch pattern (one `Info.plist` identifier,
many worker classes) is safe **when all workers fall under a single
user-understandable function**. Concrete examples:

### ✅ Photo backup chain
`Info.plist` identifier: `com.acme.photo-backup`
Workers dispatched under it:
- `CompressPhotoWorker`
- `ChecksumWorker`
- `HttpUploadWorker`
- `MarkUploadedWorker`

Why this is fine: every worker is part of "back up the user's photo to their
cloud account." A reviewer reading your privacy disclosure understands all
four. App Store description mentions "photo backup runs in the background".

### ✅ Offline sync chain
`Info.plist` identifier: `com.acme.offline-sync`
Workers:
- `FetchPendingMutationsWorker`
- `HttpRequestWorker`
- `ConflictResolutionWorker`

Why this is fine: all workers are part of "sync user's offline edits when
connectivity returns." The user opted into offline mode in settings.

### ✅ Maintenance chain
`Info.plist` identifier: `com.acme.maintenance` (preferably as a
`BGProcessingTaskRequest`, not App Refresh)
Workers:
- `LogRotationWorker`
- `CacheEvictionWorker`
- `DbVacuumWorker`

Why this is fine: housekeeping work that has no privacy impact, runs only
when device is charging + on Wi-Fi (constraint), runs once per day at most.

---

## Patterns that get you rejected

### 🚫 Cross-purpose dispatch under one identifier
`Info.plist` identifier: `com.acme.background` (generic name 🚩)
Workers dispatched under it:
- `PhotoBackupWorker` (uploads user content)
- `AdMetricsWorker` (sends ad impressions)
- `LocationLoggerWorker` (records GPS even when feature isn't in use)
- `CompetitorPriceCheckerWorker` (scrapes a third-party URL)

This is a **§2.5.4 + §5.1.1 + privacy-policy-mismatch** trifecta. Even if your
`Info.plist` "only declares one identifier" the actual behaviour is multi-purpose
and undisclosed.

Fix:
1. Split into multiple `BGTaskScheduler` identifiers, each scoped to one
   user-understandable function.
2. List each identifier + its purpose in the app's App Store description.
3. Update your privacy policy to disclose the data flow for each.

### 🚫 Long-tail background work to keep DAU stats up
Dispatching a worker every 6 hours that just `URLSession.dataTask` to your
analytics endpoint, with the side effect of "marking the user as active
today." This violates §2.5.4 (the worker has no user-facing purpose) and
§4.5.4 (push notifications cannot be used purely for marketing — same logic
applies here).

### 🚫 Background fetch of content that's never shown
Pre-fetching content "just in case" when the worker has no signal that the
user will look at it. Apple cracks down on this because it wastes battery for
no user benefit. If the user opens the app once a month, pre-fetching daily
is a §2.5.4 violation.

### 🚫 Hidden cryptocurrency / proof-of-work
Self-explanatory, but worth saying: any background CPU work that isn't
directly providing a service the user requested is review bait. Even
non-crypto "ML training on user device while idle" is subject to §3.1.2
(consent + disclosure).

---

## Practical compliance checklist

Before submitting an app that uses this library:

1. **Audit your `Info.plist`.**
   ```xml
   <key>BGTaskSchedulerPermittedIdentifiers</key>
   <array>
       <string>com.acme.photo-backup</string>
       <string>com.acme.offline-sync</string>
       <string>com.acme.maintenance</string>
   </array>
   ```
   Each identifier should name **a specific feature**, not a generic
   "background" bucket.

2. **Audit every worker class.** Open `@Worker(name = …)` annotations and
   group by which `BGTaskScheduler` identifier they get dispatched under.
   Each group should map to one user-understandable feature.

3. **Audit your App Store description.** Search for "background" in the
   description and screenshots. If your app does background uploads, say so.
   "App backs up your photos automatically when you're on Wi-Fi" is the kind
   of one-liner that resolves §2.5.4 questions.

4. **Audit your privacy policy.** Every endpoint your workers hit must be
   listed. CDN, analytics, third-party SDK callbacks — all of it.

5. **Don't share identifiers across unrelated features.** Two unrelated
   workers under one identifier is the single most common §2.5.4 trigger.

6. **Cellular vs Wi-Fi.** Use `Constraints.requiresUnmeteredNetwork = true`
   for any worker that uploads user content > 1 MB. Reviewers regularly check
   "does the app eat my cellular data without warning."

7. **Background fetch interval.** Don't request more than once per hour from
   `BGAppRefreshTaskRequest.earliestBeginDate`. iOS will silently throttle
   you, and the review team treats high-frequency requests as a red flag.

---

## What the library can and cannot do for you

| Concern | Library | You (the host app) |
|---|---|---|
| Disk persistence + retry | ✅ Handles it | — |
| Worker dispatch | ✅ Dispatches multiple worker classes per identifier | — |
| Tagging worker → identifier | ❌ Not enforced at compile time | Audit yourself per §2.5.4 |
| Privacy disclosure | ❌ No automated check | Update policy + Info.plist `NSUsageDescription`s |
| App Store description match | ❌ Cannot know | Review submission text |
| Cellular constraint | ✅ `Constraints.requiresUnmeteredNetwork` | Set it on user-content uploads |
| BGTask interval rate-limit | ⚠️ Library will submit whatever you request | Cap to ≥ 1 hour for App Refresh |

The library is a power tool. Apple's review process treats power tools as the
*developer's responsibility* — there is no §2.5.4 carve-out for "but the
library let me do it."

---

## If you get rejected

A §2.5.4 rejection typically reads something like:

> Guideline 2.5.4 — Performance — Software Requirements
>
> Your app declares support for background modes in its Info.plist but you have
> not provided features that require persistent background execution. Apps that
> declare support for background modes for their intended purposes only.

Resolution path:
1. Reply to the reviewer in App Store Connect explaining which
   `BGTaskScheduler` identifier serves which user-visible feature.
2. Provide a screenshot / screen recording demonstrating the feature.
3. If they push back, remove the identifier that doesn't map to a clear
   feature — even if you "wanted to use it eventually."

A §2.5.4 rejection is **resolvable** if you act fast and provide concrete
mapping evidence. Repeat rejections on the same guideline escalate to
account-level review which is much harder to recover from.

---

## See also

- [`docs/IOS_BGTASK_LIMITS.md`](./IOS_BGTASK_LIMITS.md) — the OS-level physics
  (opportunistic scheduling, time budget) that constrain what's even possible.
- [`docs/ios-best-practices.md`](./ios-best-practices.md) — engineering-level
  best practices for BGTask integration.
- [Apple — App Review Guideline 2.5.4](https://developer.apple.com/app-store/review/guidelines/#2.5.4)
- [Apple — BGTaskScheduler documentation](https://developer.apple.com/documentation/backgroundtasks)
