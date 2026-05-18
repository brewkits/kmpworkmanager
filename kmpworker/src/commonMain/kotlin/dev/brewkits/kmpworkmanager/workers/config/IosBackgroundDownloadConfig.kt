package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for downloads that should survive **full app termination** on iOS.
 *
 * Backed by `URLSessionConfiguration.background(withIdentifier:)`. Unlike a regular
 * `BGTaskScheduler` download (~30 s budget), an iOS background URL session is managed
 * by the system daemon and continues running while the app is suspended, swiped away
 * from the app switcher, or killed by the OS. When the download finishes, iOS relaunches
 * the app long enough to deliver the completion event to the host's AppDelegate.
 *
 * **Host integration is required** — the library cannot intercept system relaunches by
 * itself. Wire up:
 *
 * ```swift
 * // AppDelegate.swift
 * func application(
 *     _ application: UIApplication,
 *     handleEventsForBackgroundURLSession identifier: String,
 *     completionHandler: @escaping () -> Void
 * ) {
 *     IosBackgroundUrlSessionManager.shared.handleBackgroundEvents(
 *         identifier: identifier,
 *         completionHandler: completionHandler
 *     )
 * }
 * ```
 *
 * See `docs/IOS_BACKGROUND_URL_SESSION.md` for the full setup including custom
 * `URLSessionDelegate` hooks and how persistence interacts with the regular chain queue.
 *
 * **Not supported on Android** — this config is iOS-only; on Android, ordinary
 * `HttpDownloadWorker` already runs inside WorkManager which already survives process
 * death without special wiring.
 *
 * @property url The HTTP/HTTPS URL to download.
 * @property savePath Absolute path on disk where the completed file lands.
 * @property sessionIdentifier The reverse-DNS identifier for the background `URLSession`.
 *   Must be stable across app launches — iOS uses this to reconnect to the system-held
 *   session when the app relaunches. Convention: `"<bundleId>.bgdownload.<purpose>"`.
 *   Default: `"dev.brewkits.kmpworkmanager.background"`.
 * @property headers Optional headers. Cookies and credentials handled by the system
 *   shared `HTTPCookieStorage`; if you need a non-shared cookie jar, configure the
 *   session via [IosBackgroundUrlSessionManager] before enqueueing.
 * @property isDiscretionary When `true`, iOS will delay the download until the system
 *   decides it is "convenient" (Wi-Fi, charging). Best for large non-urgent media.
 *   Default `false` — start immediately if conditions allow.
 * @property allowsCellularAccess When `false`, the download is suspended whenever the
 *   device is on cellular. Default `true`.
 * @property timeoutMs Per-request timeout. iOS allows longer effective timeouts here
 *   than foreground URLSessions because the daemon manages retries. Default 30 minutes.
 */
@Serializable
data class IosBackgroundDownloadConfig(
    val url: String,
    val savePath: String,
    val sessionIdentifier: String = "dev.brewkits.kmpworkmanager.background",
    val headers: Map<String, String>? = null,
    val isDiscretionary: Boolean = false,
    val allowsCellularAccess: Boolean = true,
    val timeoutMs: Long = 30 * 60 * 1000L
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(savePath.isNotBlank()) { "Save path cannot be blank" }
        require(sessionIdentifier.isNotBlank()) { "sessionIdentifier cannot be blank" }
        require(timeoutMs > 0) { "Timeout must be positive" }
    }
}
