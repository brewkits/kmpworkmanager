# Maven Central Publishing Setup for io.brewkits

## Bước 1: Verify Ownership của Group ID `io.brewkits`

### Tạo Sonatype JIRA Ticket

1. **Truy cập**: https://issues.sonatype.org/

2. **Đăng nhập** bằng tài khoản `vietnguyentuan2009`

3. **Create Issue** với thông tin:
   - **Project**: Community Support - Open Source Project Repository Hosting (OSSRH)
   - **Issue Type**: New Project
   - **Summary**: `Request to publish io.brewkits to Maven Central`
   - **Description**:
     ```
     I would like to publish my Kotlin Multiplatform library to Maven Central.
     
     Group Id: io.brewkits
     Project URL: https://github.com/brewkits/kmpworkmanager
     SCM URL: https://github.com/brewkits/kmpworkmanager.git
     
     I am the owner of the GitHub organization "brewkits".
     I will verify ownership by creating a public repository as requested.
     ```
   - **Group Id**: `io.brewkits`
   - **Project URL**: `https://github.com/brewkits/kmpworkmanager`
   - **SCM URL**: `https://github.com/brewkits/kmpworkmanager.git`

4. **Submit** và đợi bot reply

### Verify Ownership qua GitHub

Sonatype bot sẽ yêu cầu bạn tạo một public repo với tên như: `OSSRH-12345` (ticket number)

**Cách verify:**
```bash
# Tạo repo mới trên GitHub với tên OSSRH-XXXXX (thay số ticket thật)
# Repo này chỉ cần tồn tại, không cần code

# Hoặc thêm TXT record cho domain brewkits.io (nếu có)
```

5. **Comment** trong JIRA ticket rằng đã tạo repo, đợi approve (1-2 ngày)

---

## Bước 2: Setup Credentials (Sau khi JIRA được approve)

### Tạo GPG Key (Nếu chưa có)

```bash
# List GPG keys hiện có
gpg --list-secret-keys --keyid-format LONG

# Nếu chưa có, tạo mới:
gpg --full-generate-key
# Chọn: RSA and RSA, 4096 bits
# Name: Brewkits
# Email: vietnguyentuan@gmail.com

# Export private key dạng base64
gpg --export-secret-keys YOUR_KEY_ID | base64 > gpg-key.txt
```

### Tạo file `~/.gradle/gradle.properties`

```properties
# Sonatype OSSRH Credentials
ossrhUsername=vietnguyentuan2009
ossrhPassword=YOUR_SONATYPE_PASSWORD

# GPG Signing
signing.key=<PASTE_BASE64_GPG_KEY_HERE>
signing.password=YOUR_GPG_KEY_PASSPHRASE

# GitHub Packages (Optional)
gpr.user=vietnguyentuan2019
gpr.token=YOUR_GITHUB_TOKEN
```

**⚠️ QUAN TRỌNG**: 
- File này chứa thông tin nhạy cảm, KHÔNG commit vào git
- Đặt tại `~/.gradle/gradle.properties` (global)
- Hoặc tại project root `gradle.properties` nhưng thêm vào `.gitignore`

---

## Bước 3: Cấu hình Maven Central Repository

File `kmpworker/build.gradle.kts` cần thêm repository:

```kotlin
publishing {
    repositories {
        // ... existing repositories ...
        
        maven {
            name = "OSSRH"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            
            credentials {
                username = project.findProperty("ossrhUsername") as String? ?: ""
                password = project.findProperty("ossrhPassword") as String? ?: ""
            }
        }
    }
}
```

---

## Bước 4: Publish to Maven Central

### Option A: Automatic (Recommended)

```bash
# Clean build
./gradlew clean

# Publish to OSSRH staging
./gradlew :kmpworker:publishAllPublicationsToOSSRHRepository

# Nếu thành công, release từ staging
# Truy cập: https://s01.oss.sonatype.org/
# Login -> Staging Repositories -> Close -> Release
```

### Option B: Manual Nexus Upload

```bash
# Create bundle
cd kmpworker/build/maven-central-staging
zip -r kmpworkmanager-1.1.0-bundle.zip io/

# Upload tại: https://s01.oss.sonatype.org/#stagingUpload
```

---

## Timeline Ước Tính

1. **JIRA Ticket Creation**: 5 phút
2. **GitHub Verification**: 5 phút (tạo repo)
3. **Sonatype Approval**: **1-2 ngày làm việc** ⏱️
4. **Setup Credentials**: 10 phút
5. **First Publish**: 30 phút
6. **Sync to Maven Central**: 2-4 giờ (automatic)

---

## Trong lúc chờ Sonatype approve...

Bạn có thể:
1. ✅ **Upload lên klibs.io** (không cần verification)
2. ✅ **Create GitHub Release** với artifacts
3. ✅ **Publish to GitHub Packages**

Các platform này không cần verification process!

---

## Next: Upload to klibs.io (Ngay bây giờ)

Vì klibs.io không yêu cầu verification, chúng ta có thể upload ngay!

**Link**: https://klib.io/

