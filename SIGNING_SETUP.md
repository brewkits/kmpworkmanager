# GPG Signing Setup for Maven Central

Maven Central yêu cầu tất cả artifacts phải được sign với GPG key.

## Bước 1: Tạo GPG Key (nếu chưa có)

### Check xem đã có key chưa:
```bash
gpg --list-secret-keys --keyid-format LONG
```

### Nếu chưa có, tạo mới:
```bash
gpg --gen-key
```

Nhập thông tin:
- **Name**: Brewkits Team (hoặc tên của bạn)
- **Email**: vietnguyentuan@gmail.com (hoặc email của bạn)
- **Passphrase**: Chọn password mạnh và GHI NHỚ!

### Lấy Key ID:
```bash
gpg --list-secret-keys --keyid-format LONG
```

Output sẽ giống:
```
sec   rsa4096/ABCD1234EFGH5678 2026-01-21 [SC]
      1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ1234
uid                 [ultimate] Brewkits Team <email@example.com>
ssb   rsa4096/1234567890ABCDEF 2026-01-21 [E]
```

Key ID là: `ABCD1234EFGH5678`

## Bước 2: Export Key sang Base64

```bash
# Export private key
gpg --armor --export-secret-keys ABCD1234EFGH5678 | base64 > signing-key.txt

# Hoặc export inline (1 dòng, không có line breaks)
gpg --armor --export-secret-keys ABCD1234EFGH5678 | base64 | tr -d '\n' > signing-key-inline.txt
```

## Bước 3: Upload Public Key lên Key Servers

Maven Central cần verify signatures, nên phải upload public key:

```bash
# Upload to multiple servers
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EFGH5678
gpg --keyserver keys.openpgp.org --send-keys ABCD1234EFGH5678
gpg --keyserver pgp.mit.edu --send-keys ABCD1234EFGH5678
```

**QUAN TRỌNG**: Đợi vài phút để key replicate trên servers!

## Bước 4: Add Credentials vào Project

### Option 1: File ~/.gradle/gradle.properties (Recommended - Secure)

```properties
signing.key=<paste-base64-key-from-signing-key-inline.txt>
signing.password=<your-gpg-passphrase>
```

### Option 2: Project gradle.properties (KHÔNG khuyến khích - Có thể leak)

**CHÚ Ý**: Thêm vào `.gitignore`:
```
gradle.properties
```

Sau đó tạo file:
```properties
signing.key=<paste-base64-key>
signing.password=<your-gpg-passphrase>
```

### Option 3: Environment Variables

```bash
export ORG_GRADLE_PROJECT_signing_key="<base64-key>"
export ORG_GRADLE_PROJECT_signing_password="<passphrase>"
```

### Option 4: Command Line

```bash
./gradlew :kmpworker:generateChecksums \
  -Psigning.key="<base64-key>" \
  -Psigning.password="<passphrase>"
```

## Bước 5: Rebuild Artifacts với Signing

### Clean và rebuild:
```bash
# Clean
rm -rf kmpworker/build/maven-central-staging
./gradlew :kmpworker:clean

# Rebuild với signing
./gradlew :kmpworker:generateChecksums
```

### Verify signatures được tạo:
```bash
find kmpworker/build/maven-central-staging -name "*.asc" | head -10
```

Bạn sẽ thấy các file `.asc`:
```
kmpworkmanager-2.1.2.jar.asc
kmpworkmanager-2.1.2.pom.asc
kmpworkmanager-2.1.2.module.asc
kmpworkmanager-2.1.2-sources.jar.asc
...
```

## Bước 6: Verify Signing

```bash
# Pick một file để test
gpg --verify kmpworker/build/maven-central-staging/dev/brewkits/kmpworkmanager/2.1.2/kmpworkmanager-2.1.2.pom.asc
```

Kết quả phải là:
```
gpg: Good signature from "Brewkits Team <email@example.com>"
```

## Bước 7: Upload lại lên Maven Central

```bash
cd kmpworker/build/maven-central-staging
zip -r kmpworkmanager-2.1.2-signed.zip dev/
```

Upload file ZIP mới lên Maven Central Portal.

---

## Troubleshooting

### Lỗi: "gpg: signing failed: Inappropriate ioctl for device"

**Fix**:
```bash
export GPG_TTY=$(tty)
echo "export GPG_TTY=\$(tty)" >> ~/.bashrc  # hoặc ~/.zshrc
```

### Lỗi: "No secret key"

**Fix**: Key chưa được import. Re-import:
```bash
gpg --import <your-key-file>
```

### Lỗi: "signing.key is null"

**Fix**: Gradle không đọc được property. Check:
1. File `~/.gradle/gradle.properties` có tồn tại không?
2. Key có được paste đúng không? (1 dòng, không có line breaks)
3. Try pass qua command line thay vì file

### Key server timeout

**Fix**: Thử server khác:
```bash
gpg --keyserver hkp://keyserver.ubuntu.com:80 --send-keys ABCD1234EFGH5678
```

---

## Security Best Practices

1. **NEVER** commit `gradle.properties` với signing credentials
2. **ALWAYS** add `gradle.properties` vào `.gitignore`
3. **USE** `~/.gradle/gradle.properties` thay vì project file
4. **BACKUP** GPG key ở nơi an toàn:
   ```bash
   gpg --armor --export-secret-keys ABCD1234EFGH5678 > gpg-backup.asc
   ```
5. **PROTECT** passphrase - dùng password manager

---

## Quick Reference

### Environment Variables cho CI/CD:
```bash
ORG_GRADLE_PROJECT_signing_key=<base64-key>
ORG_GRADLE_PROJECT_signing_password=<passphrase>
```

### Gradle Command với Signing:
```bash
./gradlew :kmpworker:generateChecksums \
  -Psigning.key="${SIGNING_KEY}" \
  -Psigning.password="${SIGNING_PASSWORD}"
```

### Verify all signatures:
```bash
find kmpworker/build/maven-central-staging -name "*.asc" -exec gpg --verify {} \; 2>&1 | grep -E "(Good signature|BAD signature)"
```

---

## Reference
- [GPG Documentation](https://gnupg.org/documentation/)
- [Maven Central Requirements](https://central.sonatype.org/publish/requirements/)
- [Gradle Signing Plugin](https://docs.gradle.org/current/userguide/signing_plugin.html)
