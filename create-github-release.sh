#!/bin/bash

echo "📦 Creating GitHub Release v2.3.0..."

# Check if tag exists on remote
if ! git ls-remote --tags origin | grep -q "refs/tags/v2.3.0"; then
    echo "⚠️  Tag v2.3.0 not found on remote. Pushing..."
    git push origin v2.3.0
fi

# Create release
gh release create v2.3.0 \
  --title "v2.3.0 - WorkerResult API & Chain IDs" \
  --notes-file - <<'NOTES'
## 🎉 Major Features

**WorkerResult API - Structured Data Return**
- New `WorkerResult` sealed class for type-safe worker responses
- Workers can return structured data that flows through task chains  
- 100% backward compatible - existing Boolean returns still work

**Built-in Workers Data Return Support**
- All 5 built-in workers now return WorkerResult with structured data
- HttpRequestWorker, HttpSyncWorker, HttpDownloadWorker, HttpUploadWorker, FileCompressionWorker

**Chain ID & ExistingPolicy Support**
- `withId(id: String, policy: ExistingPolicy)` method for TaskChain
- Prevents duplicate execution on re-composition
- `ExistingPolicy.KEEP` - Skip if chain exists
- `ExistingPolicy.REPLACE` - Cancel existing and restart

## ✨ Enhancements

- Demo app UX improvements with task execution locking
- Auto-dismiss toasts, visual status indicators
- Automatic file creation for demo chains

## 🐛 Bug Fixes

- Fixed iOS simulator TLS certificate issues
- Fixed chain loop prevention with explicit IDs
- Fixed log message consistency for chain failures

## 📖 Documentation

- Complete migration guide from v2.2.x
- Updated API reference with WorkerResult examples
- Comprehensive test report (100% pass rate)

## 🔄 Breaking Changes

**None** - 100% Backward Compatible

## 📦 Installation

\`\`\`kotlin
// Gradle (Kotlin DSL)
implementation("dev.brewkits:kmpworkmanager:2.3.0")
\`\`\`

See [CHANGELOG.md](https://github.com/brewkits/kmpworkmanager/blob/main/CHANGELOG.md) for full details.
NOTES

echo "✅ GitHub Release created!"
echo "🔗 https://github.com/brewkits/kmpworkmanager/releases/tag/v2.3.0"
