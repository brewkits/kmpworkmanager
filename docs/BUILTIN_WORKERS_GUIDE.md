# Built-in Workers Guide

## Overview

KMP WorkManager provides 5 production-ready built-in workers that cover common background task scenarios. These workers are implemented in pure Kotlin Multiplatform code and work identically on Android and iOS.

**Purpose**: Demo/reference implementations showing how to build native workers. For production apps with complex requirements, consider implementing custom workers using platform-specific libraries.

---

## Quick Start

### 1. Initialize with Built-in Workers

```kotlin
// Initialize KMP WorkManager with built-in workers only
KmpWorkManager.initialize(
    context = this,
    workerFactory = BuiltinWorkerRegistry
)
```

### 2. Or Combine with Custom Workers

```kotlin
class MyWorkerFactory : WorkerFactory {
    override fun createWorker(workerClassName: String): Worker? {
        return when(workerClassName) {
            "MyCustomWorker" -> MyCustomWorker()
            else -> null
        }
    }
}

// Compose custom + built-in workers
KmpWorkManager.initialize(
    context = this,
    workerFactory = CompositeWorkerFactory(
        MyWorkerFactory(),      // Try custom workers first
        BuiltinWorkerRegistry   // Fall back to built-in
    )
)
```

---

## iOS Setup

For iOS apps using built-in workers, you need to configure background task identifiers:

### 1. Update Info.plist

Add built-in worker task IDs to `BGTaskSchedulerPermittedIdentifiers`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <!-- Your existing task IDs -->

    <!-- Built-in worker task IDs -->
    <string>demo-builtin-httprequest</string>
    <string>demo-builtin-httpsync</string>
    <string>demo-builtin-httpdownload</string>
    <string>demo-builtin-httpupload</string>
    <string>demo-builtin-filecompression</string>
</array>
```

### 2. Update Worker Factory (iOS Sample App)

If you're using a custom `IosWorkerFactory`, integrate `BuiltinWorkerRegistry`:

```kotlin
import dev.brewkits.kmpworkmanager.workers.BuiltinWorkerRegistry

class IosWorkerFactory {
    fun createWorker(workerClassName: String): IosWorker? {
        return when (workerClassName) {
            // Your custom workers
            "MyCustomWorker" -> MyCustomWorker()

            else -> {
                // Try builtin workers
                val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
                if (builtinWorker != null) {
                    WorkerAdapter(builtinWorker)
                } else {
                    null
                }
            }
        }
    }
}

// Adapter to wrap library Worker as IosWorker
private class WorkerAdapter(private val worker: Worker) : IosWorker {
    override suspend fun doWork(input: String?): Boolean {
        return worker.doWork(input)
    }
}
```

### 3. Update NativeTaskScheduler (if using custom implementation)

Add built-in worker task IDs to the permitted list:

```kotlin
val PERMITTED_TASK_IDS = setOf(
    // Your existing task IDs

    // Built-in worker task IDs
    "demo-builtin-httprequest",
    "demo-builtin-httpsync",
    "demo-builtin-httpdownload",
    "demo-builtin-httpupload",
    "demo-builtin-filecompression"
)
```

---

## Available Workers

### 1. HttpRequestWorker

**Purpose**: Execute HTTP requests without processing responses (fire-and-forget)

**Use Cases**:
- Analytics events
- Webhook notifications
- Health check pings
- Simple API calls

**Configuration**:
```kotlin
val config = HttpRequestConfig(
    url = "https://api.example.com/analytics",
    method = "POST",
    headers = mapOf(
        "Authorization" to "Bearer token",
        "Content-Type" to "application/json"
    ),
    body = """{"event":"app_opened","timestamp":${System.currentTimeMillis()}}""",
    timeoutMs = 30000
)

val inputJson = Json.encodeToString(HttpRequestConfig.serializer(), config)

scheduler.enqueue(
    id = "analytics-event",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpRequestWorker",
    inputJson = inputJson,
    constraints = Constraints(requiresNetwork = true)
)
```

**Performance**:
- Memory: ~2-3MB RAM
- Startup: <50ms

**Supported Methods**: GET, POST, PUT, DELETE, PATCH

---

### 2. HttpSyncWorker

**Purpose**: JSON synchronization (POST/GET JSON data with response)

**Use Cases**:
- Data synchronization
- Batch analytics uploads
- Periodic data sync
- API sync endpoints

**Configuration**:
```kotlin
val requestBody = buildJsonObject {
    put("lastSyncTime", System.currentTimeMillis() - 3600000)
    put("deviceId", "device-123")
}

val config = HttpSyncConfig(
    url = "https://api.example.com/sync",
    method = "POST",
    headers = mapOf("Authorization" to "Bearer token"),
    requestBody = requestBody,
    timeoutMs = 60000
)

val inputJson = Json.encodeToString(HttpSyncConfig.serializer(), config)

scheduler.enqueue(
    id = "data-sync",
    trigger = TaskTrigger.Periodic(intervalMs = 3600000), // Every hour
    workerClassName = "HttpSyncWorker",
    inputJson = inputJson,
    constraints = Constraints(requiresNetwork = true)
)
```

**Performance**:
- Memory: ~3-5MB RAM
- Startup: <50ms
- Default timeout: 60 seconds

**Features**:
- Automatic JSON content-type
- Response body logging (truncated)

---

### 3. HttpDownloadWorker

**Purpose**: Download files from HTTP/HTTPS URLs

**Use Cases**:
- File downloads
- App updates
- Media downloads
- Document downloads

**Configuration**:
```kotlin
val config = HttpDownloadConfig(
    url = "https://example.com/large-file.zip",
    savePath = "/storage/downloads/file.zip",
    headers = mapOf("User-Agent" to "MyApp/1.0"),
    timeoutMs = 300000 // 5 minutes
)

val inputJson = Json.encodeToString(HttpDownloadConfig.serializer(), config)

scheduler.enqueue(
    id = "download-file",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpDownloadWorker",
    inputJson = inputJson,
    constraints = Constraints(
        requiresNetwork = true,
        requiresCharging = true // Only download when charging
    )
)
```

**Performance**:
- Memory: ~3-5MB RAM (constant, regardless of file size)
- Supports files: GB+ (streaming download)
- Default timeout: 5 minutes

**Features**:
- Streaming downloads (no memory spikes)
- Atomic file operations (writes to .tmp then renames)
- Auto-creates parent directories
- Progress tracking support

---

### 4. HttpUploadWorker

**Purpose**: Upload files using multipart/form-data

**Use Cases**:
- Photo/video uploads
- Document uploads
- File sharing
- Profile picture updates

**Configuration**:
```kotlin
val config = HttpUploadConfig(
    url = "https://api.example.com/upload",
    filePath = "/storage/photo.jpg",
    fileFieldName = "photo",
    fileName = "profile.jpg",
    mimeType = "image/jpeg",
    headers = mapOf("Authorization" to "Bearer token"),
    fields = mapOf(
        "userId" to "123",
        "albumId" to "profile-photos"
    ),
    timeoutMs = 120000 // 2 minutes
)

val inputJson = Json.encodeToString(HttpUploadConfig.serializer(), config)

scheduler.enqueue(
    id = "upload-photo",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpUploadWorker",
    inputJson = inputJson,
    constraints = Constraints(requiresNetwork = true)
)
```

**Performance**:
- Memory: ~5-7MB RAM
- Default timeout: 2 minutes

**Features**:
- Multipart/form-data encoding
- Auto MIME type detection (40+ file types)
- Additional form fields support
- Custom filename support

**Supported MIME Types**: Images (jpg, png, gif, webp, heic), Videos (mp4, mov, avi, webm), Audio (mp3, wav, ogg), Documents (pdf, doc, xls, ppt), Archives (zip, tar, gz, 7z)

---

### 5. FileCompressionWorker

**Purpose**: Compress files/directories into ZIP archives

**Use Cases**:
- Log archiving
- Backup preparation
- Storage optimization
- Batch file compression

**Configuration**:
```kotlin
val config = FileCompressionConfig(
    inputPath = "/storage/logs",
    outputPath = "/storage/backups/logs.zip",
    compressionLevel = "high", // low, medium, high
    excludePatterns = listOf("*.tmp", ".DS_Store", "*.lock"),
    deleteOriginal = false
)

val inputJson = Json.encodeToString(FileCompressionConfig.serializer(), config)

scheduler.enqueue(
    id = "compress-logs",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "FileCompressionWorker",
    inputJson = inputJson,
    constraints = Constraints(
        requiresCharging = true,
        requiresDeviceIdle = true
    )
)
```

**Performance**:
- Memory: Platform-dependent
- Compression levels: low (fast), medium (balanced), high (best)

**Features**:
- Recursive directory compression
- Exclude patterns (*.tmp, .DS_Store, etc.)
- Optional source deletion
- Compression statistics logging

**Platform Implementations**:
- **Android**: java.util.zip.ZipOutputStream
- **iOS**: Foundation APIs (basic), recommend ZIPFoundation for production

---

## Extension Functions

For easier usage, import extension functions:

```kotlin
import dev.brewkits.kmpworkmanager.workers.builtins.scheduleHttpRequest
import dev.brewkits.kmpworkmanager.workers.builtins.scheduleDownload
import dev.brewkits.kmpworkmanager.workers.builtins.scheduleUpload
import dev.brewkits.kmpworkmanager.workers.builtins.scheduleCompression

// Simplified scheduling
scheduler.scheduleHttpRequest(
    id = "ping-api",
    url = "https://api.example.com/ping",
    method = "POST",
    body = """{"status":"active"}"""
)

scheduler.scheduleDownload(
    id = "download-file",
    url = "https://example.com/file.zip",
    savePath = "/downloads/file.zip",
    requiresCharging = true
)

scheduler.scheduleUpload(
    id = "upload-photo",
    url = "https://api.example.com/upload",
    filePath = "/storage/photo.jpg",
    headers = mapOf("Authorization" to "Bearer token")
)

scheduler.scheduleCompression(
    id = "compress-logs",
    inputPath = "/storage/logs",
    outputPath = "/storage/logs.zip",
    compressionLevel = "high"
)
```

---

## Security Features

All workers include built-in security validation:

### URL Validation
- Only http:// and https:// schemes allowed
- Prevents file:// and other dangerous schemes

### File Path Validation
- Prevents path traversal attacks (../)
- Validates absolute paths

### Size Limits
- Request body: 10MB max
- Response body: 50MB max (logged)

### Safe Logging
- URLs sanitized (no query params logged)
- Request/response bodies truncated
- Sensitive data redacted

---

## Configuration Reference

### HttpRequestConfig
```kotlin
data class HttpRequestConfig(
    val url: String,                          // Required: http:// or https://
    val method: String = "GET",               // GET, POST, PUT, DELETE, PATCH
    val headers: Map<String, String>? = null, // Optional headers
    val body: String? = null,                 // Optional body for POST/PUT/PATCH
    val timeoutMs: Long = 30000               // Default: 30 seconds
)
```

### HttpSyncConfig
```kotlin
data class HttpSyncConfig(
    val url: String,                          // Required: http:// or https://
    val method: String = "POST",              // GET, POST, PUT, PATCH
    val headers: Map<String, String>? = null, // Optional headers
    val requestBody: JsonElement? = null,     // Optional JSON body
    val timeoutMs: Long = 60000               // Default: 60 seconds
)
```

### HttpDownloadConfig
```kotlin
data class HttpDownloadConfig(
    val url: String,                          // Required: http:// or https://
    val savePath: String,                     // Required: absolute path
    val headers: Map<String, String>? = null, // Optional headers
    val timeoutMs: Long = 300000              // Default: 5 minutes
)
```

### HttpUploadConfig
```kotlin
data class HttpUploadConfig(
    val url: String,                          // Required: http:// or https://
    val filePath: String,                     // Required: file to upload
    val fileFieldName: String,                // Required: form field name
    val fileName: String? = null,             // Optional: custom filename
    val mimeType: String? = null,             // Optional: auto-detected if null
    val headers: Map<String, String>? = null, // Optional headers
    val fields: Map<String, String>? = null,  // Optional form fields
    val timeoutMs: Long = 120000              // Default: 2 minutes
)
```

### FileCompressionConfig
```kotlin
data class FileCompressionConfig(
    val inputPath: String,                    // Required: file or directory
    val outputPath: String,                   // Required: output .zip path
    val compressionLevel: String = "medium",  // low, medium, high
    val excludePatterns: List<String>? = null, // Optional: *.tmp, .DS_Store
    val deleteOriginal: Boolean = false       // Delete source after compress
)
```

---

## Testing

### Run Unit Tests
```bash
./gradlew :kmpworker:testDebugUnitTest
```

### Test Coverage
- Configuration validation: 100%
- Serialization/deserialization: 100%
- Worker factory: 100%
- Security validation: 100%

### Integration Tests
For full integration tests (HTTP, file I/O), use platform-specific test suites:

```bash
# Android integration tests
./gradlew :kmpworker:connectedAndroidTest

# iOS integration tests
./gradlew :kmpworker:iosTest
```

---

## Best Practices

### 1. Network Constraints
Always set `requiresNetwork = true` for HTTP workers:
```kotlin
constraints = Constraints(requiresNetwork = true)
```

### 2. Battery Optimization
For heavy operations, require charging:
```kotlin
constraints = Constraints(
    requiresCharging = true,
    requiresDeviceIdle = true // iOS only
)
```

### 3. Error Handling
Workers return `true` on success, `false` on failure. WorkManager will retry failed workers based on backoff policy.

### 4. Timeouts
Set appropriate timeouts based on operation:
- Quick API calls: 30 seconds
- Sync operations: 60 seconds
- Downloads: 5+ minutes
- Uploads: 2+ minutes

### 5. Progress Tracking
For download/upload workers, provide ProgressListener:
```kotlin
class MyDownloadWorker(
    progressListener: ProgressListener? = null
) : HttpDownloadWorker(progressListener = progressListener)
```

---

## Limitations

These are **demo/reference implementations** with some limitations:

### General Limitations
- Fire-and-forget pattern (no response processing in most workers)
- No retry logic (handled by WorkManager)
- No request/response interceptors
- No advanced error handling

### HttpRequestWorker / HttpSyncWorker
- Cannot process response data (can only check status code)
- No cookie management
- No response caching

### HttpDownloadWorker
- No resume support
- No parallel chunk downloads
- No background download delegation (iOS URLSession background)

### HttpUploadWorker
- Loads entire file into memory (not streaming)
- No chunked upload
- No resume support

### FileCompressionWorker
- iOS implementation is basic (recommends ZIPFoundation for production)
- No encryption support
- No password protection
- No multi-volume archives

---

## Production Recommendations

For production apps with complex requirements:

### 1. Use Platform-Specific Libraries

**Flutter/Dart Layer**:
- HTTP: Use `dio`, `http` packages (better than Ktor)
- Download: Use `flutter_downloader`
- Upload: Use `flutter_uploader`
- Compression: Use `archive` package

**React Native Layer**:
- HTTP: Use `axios`, `fetch` API
- Download: Use `react-native-fs`, `rn-fetch-blob`
- Upload: Use `react-native-upload`
- Compression: Use `react-native-zip-archive`

### 2. Implement Custom Workers

For advanced features, implement custom workers:
```kotlin
class ProductionDownloadWorker : Worker {
    override suspend fun doWork(input: String?): Boolean {
        // Use platform-specific download manager
        // Support resume, parallel chunks, background mode
        // Process response data
        // Advanced error handling
    }
}
```

### 3. When to Use Built-in Workers

Use built-in workers for:
- ✅ Simple fire-and-forget analytics
- ✅ Basic health checks
- ✅ Simple file operations
- ✅ Demo/prototype apps
- ✅ Learning/reference implementations

Don't use built-in workers for:
- ❌ Complex API integrations requiring response processing
- ❌ Large file operations requiring resume/pause
- ❌ Production apps with strict requirements
- ❌ Advanced features (caching, interceptors, auth flows)

---

## Dependencies

Built-in workers use these libraries:

```kotlin
// Ktor Client 3.0.3 - HTTP operations
implementation("io.ktor:ktor-client-core:3.0.3")
implementation("io.ktor:ktor-client-okhttp:3.0.3") // Android
implementation("io.ktor:ktor-client-darwin:3.0.3") // iOS

// Okio 3.9.1 - File I/O
implementation("com.squareup.okio:okio:3.9.1")

// Kotlinx Serialization - JSON handling
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

---

## Troubleshooting

### Worker not found
```
Error: Worker class not found: HttpRequestWorker
```
**Solution**: Make sure you initialized with `BuiltinWorkerRegistry` or `CompositeWorkerFactory`.

### Network error
```
Error: HTTP request failed: Connection refused
```
**Solution**: Check `requiresNetwork = true` in constraints. Verify URL is reachable.

### File not found
```
Error: File does not exist: /storage/file.jpg
```
**Solution**: Verify file path is correct and file exists. Check file permissions.

### Timeout error
```
Error: Request timed out
```
**Solution**: Increase `timeoutMs` in config. Check network speed.

### Compression failed (iOS)
```
Warning: iOS ZIP compression requires ZIPFoundation library
```
**Solution**: Current iOS implementation is basic. For production, integrate ZIPFoundation library.

---

## FAQ

**Q: Can I process HTTP response data?**
A: HttpRequestWorker only checks status codes. For response processing, implement a custom worker or use HttpSyncWorker which logs responses.

**Q: Do workers support authentication flows?**
A: Workers support custom headers (Bearer tokens, API keys). For OAuth flows, implement custom worker.

**Q: Can I resume failed downloads?**
A: No. Built-in workers don't support resume. Use platform-specific download managers for this feature.

**Q: Are workers production-ready?**
A: They're demo/reference implementations. For production with complex requirements, use platform-specific libraries or custom workers.

**Q: Can I use workers without network?**
A: Only FileCompressionWorker works offline. HTTP workers require network connectivity.

**Q: How do I handle worker failures?**
A: WorkManager automatically retries failed workers based on backoff policy. Workers return `false` on failure.

**Q: Can I chain workers?**
A: Not directly. Listen to worker completion events and schedule the next worker programmatically.

---

## Examples

For examples of how to use built-in workers, please refer to the main demo application (`composeApp`).

---

## Support

For questions or issues:
- GitHub: https://github.com/brewkits/kmpworkmanager
- Email: datacenter111@gmail.com

---

## License

Copyright © 2024 Nguyễn Tuấn Việt at Brewkits

Built with ❤️ for the KMP community
