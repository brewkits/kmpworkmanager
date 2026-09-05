# 🤝 Contributing to KMP WorkManager

We welcome contributions to KMP WorkManager! Whether it's fixing a bug, improving documentation, or suggesting a feature, your help is appreciated.

## 🚀 How to Contribute

### 1. Report Bugs or Suggest Features
Open an issue on our [GitHub tracker](https://github.com/brewkits/kmpworkmanager/issues). Please include platform details (Android/iOS version) and a minimal way to reproduce the issue.

**Found a security vulnerability?** Do not open a public issue — see [SECURITY.md](SECURITY.md) for the private reporting process.

### 2. Development Setup
- **JDK 17+**
- **Android Studio Hedgehog+**
- **Xcode 15+** (for iOS/macOS)

```bash
git clone https://github.com/brewkits/kmpworkmanager.git
cd kmpworkmanager
./gradlew build
```

### 3. Workflow
1. **Branch**: Create a branch from `main` (e.g., `fix/ios-timeout-hang`).
2. **Commit**: Use [Conventional Commits](https://www.conventionalcommits.org/) (e.g., `feat: add battery guard`).
3. **Test**: Run `./gradlew :kmpworker:allTests` before pushing. We aim for 100% coverage on critical scheduling paths.
4. **PR**: Open a Pull Request. Ensure your code follows the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).

---

## 🔒 Public API Stability

`kmpworker`, `kmpworker-http`, `kmpworker-annotations`, and `kmpworker-testing` use [Kotlin's binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) to catch accidental public API changes. If your change touches a public class/function/property in any of these modules:

1. Run `./gradlew apiDump` to regenerate the `.api` snapshot.
2. Commit the updated `.api` file alongside your change.
3. If the change removes or alters an existing public API, explain why in the PR — deleting or breaking a public signature is a breaking change and needs a version-bump/deprecation discussion, not a silent merge.

`./gradlew apiCheck` runs in CI and fails the build if the `.api` snapshot is out of date — this catches unintentional API drift before merge, not just intentional ones.

---

## 🧪 Testing Standards

We value **Invariant Testing** over simple examples. 
- **Bad Test**: "Scheduling works for this one case."
- **Good Test**: "After a REPLACE policy call, the drift-correction anchor must always be within the current time window."

If you touch persisted state (File Storage, SharedPreferences), please add an invariant test to ensure the state remains valid across multiple app lifecycles.

---

## 📝 Documentation Guidelines
- Public APIs must have **KDoc**.
- Explain the *why* and any platform-specific quirks (especially for iOS).
- Include a `@sample` block for complex APIs like `TaskChain`.

---

**Questions?** Open a [GitHub discussion](https://github.com/brewkits/kmpworkmanager/discussions) or issue.

**Last Updated:** September 2026
