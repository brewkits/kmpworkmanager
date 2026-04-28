# 🤝 Contributing to KMP WorkManager

We welcome contributions to KMP WorkManager! Whether it's fixing a bug, improving documentation, or suggesting a feature, your help is appreciated.

## 🚀 How to Contribute

### 1. Report Bugs or Suggest Features
Open an issue on our [GitHub tracker](https://github.com/brewkits/kmpworkmanager/issues). Please include platform details (Android/iOS version) and a minimal way to reproduce the issue.

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

**Questions?** Email vietnguyentuan@gmail.com or open a discussion.

**Last Updated:** April 2026
