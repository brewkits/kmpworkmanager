package dev.brewkits.kmpworkmanager.workers.config

import kotlinx.serialization.Serializable

/**
 * Configuration for uploads that should survive **full app termination** on iOS.
 *
 * Backed by `URLSessionConfiguration.background(withIdentifier:)`, the upload counterpart of
 * [IosBackgroundDownloadConfig] — same daemon-managed lifecycle, same host-integration
 * requirement (`application(_:handleEventsForBackgroundURLSession:completionHandler:)`).
 *
 * **Source must be a file on disk** — `NSURLSessionUploadTask` requires
 * `uploadTaskWithRequest(_:fromFile:)` for a background session; in-memory request bodies
 * (`fromData:`) are not supported by background sessions at all, so there is no data-based
 * alternative to offer here. Write your payload to disk first if it doesn't already live there.
 *
 * **Not supported on Android** — this config is iOS-only; on Android, ordinary
 * `HttpUploadWorker` already runs inside WorkManager which already survives process death
 * without special wiring.
 *
 * @property url The HTTP/HTTPS endpoint to upload to.
 * @property filePath Absolute path on disk to the file being uploaded. Must exist and be
 *   readable at enqueue time — the daemon reads directly from this path, so it must remain
 *   valid for the lifetime of the upload (don't delete it until completion is observed via
 *   `TaskEventBus`).
 * @property sessionIdentifier The reverse-DNS identifier for the background `URLSession`.
 *   Must be stable across app launches — iOS uses this to reconnect to the system-held
 *   session when the app relaunches. Convention: `"<bundleId>.bgupload.<purpose>"`.
 *   Default: `"dev.brewkits.kmpworkmanager.background"` — shares the download manager's
 *   default identifier, since `NSURLSession` background sessions safely multiplex both
 *   upload and download tasks on one identifier.
 * @property headers Optional headers. Cookies and credentials handled by the system shared
 *   `HTTPCookieStorage`; if you need a non-shared cookie jar, configure the session via
 *   `IosBackgroundUrlSessionManager` before enqueueing.
 * @property httpMethod HTTP method for the upload request. Default `"POST"`.
 * @property sharedContainerIdentifier Optional App Group container identifier
 *   (`group.<bundleId>...`). When set, the underlying `NSURLSessionConfiguration` is
 *   configured with `sharedContainerIdentifier`, allowing a Share/Widget Extension in the
 *   same App Group to also initiate or observe transfers on this session. This shares only
 *   the *transport* — [dev.brewkits.kmpworkmanager.workers.builtins.BackgroundDownloadStateStore]
 *   (completion tracking) always writes to the main app's Application Support directory
 *   regardless of this setting, so an extension cannot read pending/complete transfer state
 *   today. See `docs/IOS_BACKGROUND_URL_SESSION.md`.
 * @property allowsCellularAccess When `false`, the upload is suspended whenever the device is
 *   on cellular. Default `true`.
 * @property timeoutMs Per-request timeout. Default 30 minutes.
 */
@Serializable
data class IosBackgroundUploadConfig(
    val url: String,
    val filePath: String,
    val sessionIdentifier: String = "dev.brewkits.kmpworkmanager.background",
    val headers: Map<String, String>? = null,
    val httpMethod: String = "POST",
    val sharedContainerIdentifier: String? = null,
    val allowsCellularAccess: Boolean = true,
    val timeoutMs: Long = 30 * 60 * 1000L
) {
    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(filePath.isNotBlank()) { "File path cannot be blank" }
        require(sessionIdentifier.isNotBlank()) { "sessionIdentifier cannot be blank" }
        require(httpMethod.isNotBlank()) { "httpMethod cannot be blank" }
        require(timeoutMs > 0) { "Timeout must be positive" }
    }
}
