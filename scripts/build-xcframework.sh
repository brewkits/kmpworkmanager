#!/bin/bash
set -e

# Build KMPWorkManager.xcframework from source
# Output → native_workmanager/ios/Frameworks/KMPWorkManager.xcframework

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/.."
OUTPUT_DIR="$ROOT_DIR/build/xcframework"
BUILD_DIR="$ROOT_DIR/kmpworker/build/bin"
TMP_DIR="$ROOT_DIR/build/xcframework-tmp"

echo "==> Building KMPWorkManager release frameworks..."

cd "$ROOT_DIR"

./gradlew \
  :kmpworker:linkReleaseFrameworkIosArm64 \
  :kmpworker:linkReleaseFrameworkIosSimulatorArm64 \
  :kmpworker:linkReleaseFrameworkIosX64

echo "==> Combining simulator slices (arm64 + x86_64) with lipo..."

ARM64_SIM="$BUILD_DIR/iosSimulatorArm64/releaseFramework/KMPWorkManager.framework"
X86_64_SIM="$BUILD_DIR/iosX64/releaseFramework/KMPWorkManager.framework"

rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR/simulator"

# Copy arm64-simulator framework as base
cp -R "$ARM64_SIM" "$TMP_DIR/simulator/KMPWorkManager.framework"

# Merge binaries from both simulator architectures
lipo -create \
  "$ARM64_SIM/KMPWorkManager" \
  "$X86_64_SIM/KMPWorkManager" \
  -output "$TMP_DIR/simulator/KMPWorkManager.framework/KMPWorkManager"

echo "==> Creating XCFramework..."

rm -rf "$OUTPUT_DIR/KMPWorkManager.xcframework"

xcodebuild -create-xcframework \
  -framework "$BUILD_DIR/iosArm64/releaseFramework/KMPWorkManager.framework" \
  -framework "$TMP_DIR/simulator/KMPWorkManager.framework" \
  -output "$OUTPUT_DIR/KMPWorkManager.xcframework"

echo "==> Cleaning up temp..."
rm -rf "$TMP_DIR"

echo ""
echo "Done! XCFramework at: $OUTPUT_DIR/KMPWorkManager.xcframework"
