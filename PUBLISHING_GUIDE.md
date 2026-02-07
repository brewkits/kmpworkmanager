# KMP WorkManager v2.3.0 - Publishing Guide

## 📦 Maven Artifacts Location

All signed artifacts have been generated at:
```
~/.m2/repository/dev/brewkits/kmpworkmanager/2.3.0/
```

## 📋 Generated Artifacts

### Main Library
- `kmpworkmanager-2.3.0.jar` - Main JAR
- `kmpworkmanager-2.3.0.pom` - POM file
- `kmpworkmanager-2.3.0.module` - Gradle metadata
- `kmpworkmanager-2.3.0-sources.jar` - Sources
- `kmpworkmanager-2.3.0-kotlin-tooling-metadata.json` - Kotlin metadata

### Platform-Specific Artifacts
- Android: `kmpworkmanager-android-*`
- iOS ARM64: `kmpworkmanager-iosarm64-*`
- iOS Simulator ARM64: `kmpworkmanager-iossimulatorarm64-*`
- iOS x64: `kmpworkmanager-iosx64-*`

### Signatures
All artifacts have `.asc` signature files (GPG signed)

---

## 🚀 Option 1: Publish via Gradle (Recommended)

### Prerequisites
Ensure `~/.gradle/gradle.properties` has:
```properties
ossrhUsername=your_sonatype_username
ossrhPassword=your_sonatype_password
signing.key=your_base64_gpg_key
signing.password=your_gpg_password
```

### Publish Command
```bash
# Publish to OSSRH staging
./gradlew publishAllPublicationsToOSSRHRepository

# Or step by step
./gradlew :kmpworker:publishToMavenLocal
./gradlew :kmpworker:publishAllPublicationsToOSSRHRepository
```

---

## 🔧 Option 2: Manual Upload to Sonatype

### Step 1: Create Bundle
```bash
cd ~/.m2/repository/dev/brewkits/kmpworkmanager/2.3.0/
jar -cvf kmpworkmanager-2.3.0-bundle.jar *
```

### Step 2: Upload to Sonatype
1. Go to https://s01.oss.sonatype.org/
2. Login with your credentials
3. Click "Staging Upload" (left sidebar)
4. Select "Artifact Bundle" tab
5. Upload the bundle JAR file
6. Click "Upload Bundle"

### Step 3: Close and Release
1. Go to "Staging Repositories"
2. Find your repository (devbrewkits-XXXX)
3. Select it and click "Close"
4. Wait for validation (2-5 minutes)
5. Click "Release"
6. Artifacts will sync to Maven Central in ~30 minutes

---

## 🔍 Option 3: Direct OSSRH API Upload

### Using Maven Deploy Plugin
```bash
mvn deploy:deploy-file \
  -DgroupId=dev.brewkits \
  -DartifactId=kmpworkmanager \
  -Dversion=2.3.0 \
  -Dpackaging=jar \
  -Dfile=kmpworkmanager-2.3.0.jar \
  -DrepositoryId=ossrh \
  -Durl=https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
```

---

## ✅ Verification After Publishing

### 1. Check OSSRH Nexus Repository Manager
```
https://s01.oss.sonatype.org/#stagingRepositories
```

### 2. Check Maven Central Search (after ~30 min)
```
https://search.maven.org/artifact/dev.brewkits/kmpworkmanager/2.3.0/jar
```

### 3. Test Integration
```kotlin
// In build.gradle.kts
dependencies {
    implementation("dev.brewkits:kmpworkmanager:2.3.0")
}
```

---

## 📝 Troubleshooting

### Issue: Missing Signatures
```bash
# Re-sign all artifacts
./gradlew signKotlinMultiplatformPublication
./gradlew signAndroidReleasePublication
./gradlew signIosArm64Publication
```

### Issue: Invalid POM
Check `kmpworker/build.gradle.kts` publishing section:
- groupId: dev.brewkits
- artifactId: kmpworkmanager
- version: 2.3.0
- All required POM metadata present

### Issue: Validation Errors on Sonatype
Common fixes:
- Ensure all artifacts have `.asc` signatures
- Ensure POM has description, URL, licenses, developers, SCM
- Ensure sources JAR and javadoc JAR are included

---

## 🎯 Quick Publish Checklist

- [ ] All artifacts generated in ~/.m2/repository
- [ ] All artifacts have .asc signatures
- [ ] Sonatype credentials configured
- [ ] Push git tag to remote: `git push origin v2.3.0`
- [ ] Run publish command
- [ ] Close staging repository
- [ ] Release to Maven Central
- [ ] Verify on search.maven.org (wait 30 min)
- [ ] Update GitHub release with notes
- [ ] Announce on social media / forums

---

## 📞 Support

If you encounter issues:
- Sonatype Support: https://issues.sonatype.org
- Maven Central Guide: https://central.sonatype.org/publish/publish-guide/
- Project Issues: https://github.com/brewkits/kmp_worker/issues

---

**Generated:** February 7, 2026  
**Version:** 2.3.0  
**Build:** Successful ✅
