# v2.3.0 Release Checklist

## ✅ Pre-Release (Completed)

- [x] Code reviewed and tested
- [x] All tests passing (100%)
- [x] Documentation updated
- [x] CHANGELOG.md updated
- [x] Version bumped to 2.3.0
- [x] Git commit created
- [x] Git tag v2.3.0 created
- [x] Maven artifacts built and signed
- [x] Publishing guide created

## 📝 To Do Before Publishing

- [ ] Review CHANGELOG.md for accuracy
- [ ] Review generated POM file
- [ ] Verify all signatures (.asc files)
- [ ] Test artifacts locally:
  ```bash
  ./gradlew :composeApp:clean :composeApp:build
  ```

## 🚀 Publishing Steps

### 1. Push to Remote
```bash
git push origin main
git push origin v2.3.0
```

### 2. Publish to Maven Central (Choose One)

#### Option A: Gradle Command
```bash
./gradlew publishAllPublicationsToOSSRHRepository
```

#### Option B: Manual Upload
```bash
cd ~/.m2/repository/dev/brewkits/kmpworkmanager/2.3.0/
jar -cvf kmpworkmanager-2.3.0-bundle.jar *
# Upload to https://s01.oss.sonatype.org
```

### 3. Sonatype Nexus
- [ ] Login to https://s01.oss.sonatype.org
- [ ] Find staging repository
- [ ] Click "Close" (wait for validation)
- [ ] Click "Release"

### 4. Verification (Wait ~30 minutes)
- [ ] Check Maven Central: https://search.maven.org/artifact/dev.brewkits/kmpworkmanager/2.3.0/jar
- [ ] Test in sample project:
  ```kotlin
  dependencies {
      implementation("dev.brewkits:kmpworkmanager:2.3.0")
  }
  ```

## 📢 Post-Release

### GitHub Release
- [ ] Create GitHub release from tag v2.3.0
- [ ] Copy CHANGELOG.md content
- [ ] Attach PUBLISHING_GUIDE.md
- [ ] Publish release

### Documentation
- [ ] Update README badges (if any)
- [ ] Update website (if applicable)
- [ ] Create migration guide PR

### Announcements
- [ ] Reddit r/kotlin, r/KotlinMultiplatform
- [ ] Twitter/X announcement
- [ ] LinkedIn post
- [ ] Dev.to article
- [ ] Kotlin Slack #libraries channel

### Monitoring
- [ ] Monitor Maven Central stats
- [ ] Watch for issues on GitHub
- [ ] Check community feedback

## 🎯 Success Criteria

- [ ] Artifact available on Maven Central
- [ ] Sample app builds with new version
- [ ] No critical issues reported within 24h
- [ ] Documentation accessible
- [ ] Community feedback positive

---

**Release Date:** February 7, 2026
**Version:** 2.3.0
**Status:** Ready to publish! 🚀
