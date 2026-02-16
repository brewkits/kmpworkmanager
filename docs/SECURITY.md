# Security Policy

## Supported Versions

Security updates are provided for the latest major version. We recommend always using the latest stable release.

| Version | Supported | Status |
|---------|-----------|--------|
| 2.3.x   | ✅ Yes    | Current (SSRF protected) |
| 2.2.x   | ⚠️ Limited | Critical fixes only |
| 2.1.x   | ❌ No      | Upgrade required |
| < 2.0.0 | ❌ No      | Deprecated |

---

## Reporting a Vulnerability

**Please do not report security vulnerabilities via public GitHub issues.**

### How to Report

Email: **datacenter111@gmail.com**

Include:
- Clear description of the vulnerability
- Steps to reproduce (code snippets, POC)
- Potential impact assessment
- Your preferred disclosure timeline

### Response Process

1. **Acknowledgment**: Within 48 hours
2. **Investigation**: Security assessment (1-5 days)
3. **Fix Development**: Priority patch release
4. **Disclosure**: Security advisory after fix is deployed

We credit security researchers unless they prefer anonymity.

---

## Security Features (v2.3.1+)

### 🛡️ SSRF Protection

All HTTP workers (`HttpRequestWorker`, `HttpDownloadWorker`, `HttpUploadWorker`, `HttpSyncWorker`) include comprehensive SSRF protection:

**Blocked Targets:**
- **Localhost**: localhost, 127.0.0.1, ::1, 0.0.0.0
- **Private IPv4**: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
- **Link-Local**: 169.254.0.0/16 (includes AWS metadata 169.254.169.254)
- **Private IPv6**: fc00::/7 (ULA), fe80::/10 (link-local), fd00:ec2::254
- **Local Domains**: *.localhost, *.local
- **Invalid Schemes**: Only http:// and https:// allowed

**Implementation:**
```kotlin
// All HTTP workers validate URLs before making requests
if (!SecurityValidator.validateURL(url)) {
    return WorkerResult.Failure("Invalid or unsafe URL")
}
```

**Test Coverage:** 50+ test cases verify SSRF protection

### 🔒 Resource Limits

**File Upload Protection:**
- Maximum file size: 100MB
- Clear error messages on oversized files
- Prevents OOM crashes

**Request/Response Limits:**
- Request body: 10MB maximum
- Response body: 50MB maximum
- Configurable via `SecurityValidator` constants

**HTTP Client Management:**
- Automatic cleanup in finally blocks
- Prevents resource leaks
- Proper connection pooling

### 🔐 Data Protection

**Sensitive Data Logging:**
```kotlin
// Query parameters automatically redacted
SecurityValidator.sanitizedURL("https://api.com?key=secret")
// Returns: "https://api.com?[REDACTED]"

// String truncation for logs
SecurityValidator.truncateForLogging(longString, maxLength = 200)
```

**File Path Validation:**
```kotlin
// Prevents path traversal attacks
SecurityValidator.validateFilePath("/safe/path") // ✅ true
SecurityValidator.validateFilePath("../etc/passwd") // ❌ false
```

---

## Security Best Practices

### For Application Developers

#### 1. URL Validation

**DO:**
```kotlin
// Let the library validate URLs
scheduler.enqueue(
    id = "api-call",
    trigger = TaskTrigger.OneTime(),
    workerClassName = "HttpRequestWorker",
    inputJson = """{"url":"https://api.example.com"}"""
)
```

**DON'T:**
```kotlin
// Don't construct URLs from untrusted user input without validation
val userProvidedUrl = getUserInput() // ⚠️ DANGEROUS
```

**RECOMMENDATION:**
- Whitelist allowed domains
- Validate user input separately
- Use parameterized URLs instead of string concatenation

#### 2. File Operations

**DO:**
```kotlin
// Validate file paths
val sanitizedPath = userPath.replace("..", "")
if (SecurityValidator.validateFilePath(sanitizedPath)) {
    // Proceed with file operation
}
```

**DON'T:**
```kotlin
// Don't use user input directly in file paths
val file = File(userProvidedPath) // ⚠️ Path traversal risk
```

#### 3. Sensitive Data

**DO:**
```kotlin
// Use encrypted storage for sensitive data
val encryptedData = encrypt(sensitiveInfo)
scheduler.enqueue(
    workerClassName = "SecureUploadWorker",
    inputJson = """{"data":"$encryptedData"}"""
)
```

**DON'T:**
```kotlin
// Don't pass sensitive data in logs or inputJson without encryption
Logger.d("Password: $password") // ⚠️ NEVER DO THIS
```

#### 4. Network Security

**DO:**
```kotlin
// Prefer HTTPS over HTTP
url = "https://secure-api.com" // ✅ Encrypted

// Implement certificate pinning for critical APIs
// (requires custom HTTP client configuration)
```

**DON'T:**
```kotlin
// Don't use HTTP for sensitive operations
url = "http://api.com/login" // ⚠️ Unencrypted
```

#### 5. Dependency Management

**DO:**
```bash
# Regularly audit dependencies
./gradlew dependencyCheckAnalyze

# Keep KMP WorkManager updated
implementation("dev.brewkits:kmpworkmanager:2.3.2") // ✅ Latest
```

**DON'T:**
```kotlin
// Don't use outdated versions with known vulnerabilities
implementation("dev.brewkits:kmpworkmanager:1.0.0") // ⚠️ Vulnerable
```

---

## Common Security Pitfalls

### 1. SSRF Attacks

**Vulnerability:**
```kotlin
// User provides URL
val url = request.getParameter("url")
// Direct usage without validation
httpClient.get(url) // ⚠️ SSRF vulnerable
```

**Fix:**
```kotlin
// Validate before use
if (SecurityValidator.validateURL(url)) {
    httpClient.get(url)
} else {
    throw SecurityException("Invalid URL")
}
```

### 2. Path Traversal

**Vulnerability:**
```kotlin
// User provides filename
val filename = request.getParameter("file")
// Direct file access
File("/uploads/$filename").read() // ⚠️ Can access ../../../etc/passwd
```

**Fix:**
```kotlin
// Validate and sanitize
val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "")
if (SecurityValidator.validateFilePath(safeName)) {
    File("/uploads/$safeName").read()
}
```

### 3. Sensitive Data Exposure

**Vulnerability:**
```kotlin
// Logging sensitive data
Logger.i("User password: $password") // ⚠️ Leaked in logs
```

**Fix:**
```kotlin
// Never log sensitive data
Logger.i("User authenticated successfully") // ✅ Safe
```

### 4. Insecure Deserialization

**Vulnerability:**
```kotlin
// Deserializing untrusted data
val obj = Json.decodeFromString<Task>(untrustedInput) // ⚠️ Risk
```

**Fix:**
```kotlin
// Validate schema and use safe deserialization
val json = Json { ignoreUnknownKeys = true }
try {
    val obj = json.decodeFromString<Task>(input)
    // Validate object state
    require(obj.isValid()) { "Invalid task" }
} catch (e: Exception) {
    // Handle deserialization errors
}
```

---

## Secure Configuration Examples

### Android: Network Security Config

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.example.com</domain>
        <!-- Certificate pinning for critical APIs -->
        <pin-set>
            <pin digest="SHA-256">base64encodedpublickey==</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```

```xml
<!-- AndroidManifest.xml -->
<application
    android:networkSecurityConfig="@xml/network_security_config">
</application>
```

### iOS: App Transport Security

```xml
<!-- Info.plist -->
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsArbitraryLoads</key>
    <false/>
    <key>NSExceptionDomains</key>
    <dict>
        <key>api.example.com</key>
        <dict>
            <key>NSIncludesSubdomains</key>
            <true/>
            <key>NSRequiresCertificateTransparency</key>
            <true/>
        </dict>
    </dict>
</dict>
```

---

## Security Checklist

Before deploying to production:

### Code Review
- [ ] All HTTP URLs validated with `SecurityValidator.validateURL()`
- [ ] File paths validated with `SecurityValidator.validateFilePath()`
- [ ] No sensitive data in logs or error messages
- [ ] User input properly sanitized
- [ ] HTTPS used for all external APIs

### Configuration
- [ ] Network Security Config (Android) configured
- [ ] App Transport Security (iOS) enabled
- [ ] Minimum TLS version 1.2+ enforced
- [ ] Certificate pinning for critical endpoints (optional)

### Dependencies
- [ ] All dependencies up to date
- [ ] Security audit completed (`dependencyCheckAnalyze`)
- [ ] No known vulnerabilities in dependency tree

### Testing
- [ ] SSRF protection tests passing
- [ ] Path traversal tests passing
- [ ] Error handling tests passing
- [ ] Security edge cases covered

### Documentation
- [ ] Security best practices documented for team
- [ ] Incident response plan in place
- [ ] Security contacts updated

---

## Security Audit Results

### v2.3.1 Security Assessment

**Date:** February 2026
**Auditor:** Internal Security Review

**Findings:**

| Category | Rating | Notes |
|----------|--------|-------|
| SSRF Protection | ✅ PASS | Comprehensive validation implemented |
| Resource Limits | ✅ PASS | File size limits enforced (100MB) |
| Input Validation | ✅ PASS | Path traversal prevented |
| Data Encryption | ⚠️ N/A | Responsibility of host application |
| Dependency Security | ✅ PASS | All dependencies up to date |
| Error Handling | ✅ PASS | No sensitive data leaked in errors |

**Overall Rating:** 9/10 - Production Ready

**Recommendations:**
1. ✅ Implemented: SSRF protection
2. ✅ Implemented: Resource limits
3. ✅ Implemented: Comprehensive tests
4. 🔄 Ongoing: Regular dependency audits

---

## Additional Resources

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [OWASP Mobile Security](https://owasp.org/www-project-mobile-security/)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)
- [iOS Security Guide](https://support.apple.com/guide/security/welcome/web)
- [Kotlin Security](https://kotlinlang.org/docs/security.html)

---

## Changelog

### v2.3.1 (February 2026)
- ✅ Added comprehensive SSRF protection
- ✅ Added 50+ security tests
- ✅ Added file size limits (100MB)
- ✅ Added URL validation documentation

### v2.3.0 (February 2026)
- Added initial security validation

### Earlier Versions
- See [CHANGELOG.md](../CHANGELOG.md) for full history

---

**Last Updated:** February 16, 2026
**Version:** 2.3.2
**Maintainer:** Nguyễn Tuấn Việt (datacenter111@gmail.com)
