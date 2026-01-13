# Changelog

All notable changes to KMP WorkManager will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-01-13

### Added
- Worker factory pattern for better extensibility
- Automatic iOS task ID validation from Info.plist
- Type-safe serialization extensions with reified inline functions
- File-based storage on iOS for better performance
- Smart exact alarm fallback on Android
- Heavy task support with foreground services
- Unified API for Android and iOS
- Comprehensive test suite with 41+ test cases
- Support for task chains with parallel and sequential execution
- Multiple trigger types (OneTime, Periodic, Exact, NetworkChange)
- Rich constraint system (network, battery, charging, storage)
- Background task scheduling using WorkManager (Android) and BGTaskScheduler (iOS)

### Changed
- Rebranded from `kmpworker` to `kmpworkmanager`
- Migrated package from `io.kmp.worker` to `io.brewkits.kmpworkmanager`
- Project organization moved to brewkits/kmpworkmanager

### Documentation
- Complete API reference documentation
- Platform setup guides for Android and iOS
- Quick start guide
- Task chains documentation
- Architecture overview
- Examples and use cases
