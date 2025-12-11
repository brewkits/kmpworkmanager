# Changelog

All notable changes to KMP TaskManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.2.2] - 2025-12-11

### Changed
- **README.md**: Optimized structure from 543 to 420 lines (-23%)
  - Removed duplicate content across sections
  - Kept 4 essential real-world examples
  - Improved content organization and readability
  - Added clear links to detailed documentation
  - Removed implementation guide (moved to docs)

### Documentation
- Enhanced documentation structure for better discoverability
- Improved cross-referencing between documentation files

## [2.2.1] - 2025-12-09

### Added
- Comprehensive test suite with 41 new test cases
- `TaskTriggerHelperTest`: Helper function validation (6 tests)
- `SerializationTest`: JSON serialization/deserialization testing (11 tests)
- `EdgeCasesTest`: Boundary conditions and edge case testing (24 tests)
- **ARCHITECTURE.md**: Complete architecture documentation (500+ lines)
- **CONTRIBUTING.md**: Comprehensive contribution guidelines (400+ lines)
- **DEMO_GUIDE.md**: Detailed demo app usage guide (350+ lines)
- **TEST_GUIDE.md**: Testing best practices and guidelines (450+ lines)

### Changed
- **Test Coverage**: Increased from ~60 to ~101 test cases (+68% improvement)
- **Library Dependencies**: Updated to latest compatible versions:
  - Kotlin: 2.1.0 → 2.1.21
  - androidx-activity: 1.11.0 → 1.12.1
  - androidx-lifecycle: 2.9.4 → 2.9.6
  - composeMultiplatform: 1.9.0 → 1.9.3
  - androidx-work: 2.10.5 → 2.11.0
  - kotlinx-serialization: 1.7.1 → 1.8.1
  - kotlinx-coroutines: 1.8.0 → 1.10.2
- **README.md**: Reorganized documentation section, updated to v2.2.1
- **KmpWorker** (library): Replaced `println()` statements with structured `Logger` calls for production readiness

### Fixed
- **AndroidManifest.xml**: Fixed namespace prefix error (`android.label` → `android:label`)

### Removed
- **ROADMAP.md**: Removed from git tracking and documentation links
- Cleaned up unnecessary `.DS_Store` files

## [2.2.0] - 2024-12-XX

### Added

#### Android Platform
- **Fixed isHeavyTask bug**: Light tasks now use expedited work requests
- **BackoffPolicy support**: Retry strategies properly applied
- Helper methods for code reuse

#### iOS Platform
- **Configurable task IDs**: Runtime configuration via Koin module
- **Enhanced QoS documentation**: Comprehensive documentation

### Changed
- **Code quality**: Eliminated ~200 lines of duplicate code
- **API improvements**: Enhanced iOS NativeTaskScheduler constructor
- **Validation improvements**: Better task ID validation

## [2.1.0] - 2024-11-XX

### Added
- **Structured Logger**: 4-level logging system
- **iOS Enhancements**: Timeout protection, task validation, batch processing
- **Android Enhancements**: Notification channel auto-creation
- **Comprehensive Documentation**: 470+ lines in Contracts.kt

## [2.0.0] - 2024-10-XX

### Added
- Initial public release
- Cross-platform background task scheduling
- 9 different trigger types
- Task chains support
- Published to Maven Central

---

**Maintained by**: [Nguyễn Tuấn Việt](https://github.com/vietnguyentuan2019)
**License**: Apache 2.0
