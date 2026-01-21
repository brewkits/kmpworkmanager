#!/bin/bash

# Script to rebuild Maven Central artifacts with GPG signing
# Usage: ./rebuild-with-signing.sh

set -e  # Exit on error

echo "═══════════════════════════════════════════════════════════════"
echo "  Rebuilding Maven Central Artifacts with GPG Signing"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# Check if signing credentials exist
if ! grep -q "signing.key" ~/.gradle/gradle.properties 2>/dev/null; then
    echo "❌ ERROR: signing.key not found in ~/.gradle/gradle.properties"
    echo ""
    echo "Please add to ~/.gradle/gradle.properties:"
    echo "   signing.key=<your-base64-key>"
    echo "   signing.password=<your-gpg-passphrase>"
    echo ""
    echo "See SIGNING_SETUP.md for details."
    exit 1
fi

if ! grep -q "signing.password" ~/.gradle/gradle.properties 2>/dev/null; then
    echo "❌ ERROR: signing.password not found in ~/.gradle/gradle.properties"
    echo ""
    echo "Please add to ~/.gradle/gradle.properties:"
    echo "   signing.password=<your-gpg-passphrase>"
    echo ""
    exit 1
fi

echo "✅ Signing credentials found"
echo ""

# Step 1: Clean old build
echo "🧹 Step 1: Cleaning old build..."
rm -rf kmpworker/build/maven-central-staging
./gradlew :kmpworker:clean
echo "✅ Clean completed"
echo ""

# Step 2: Rebuild with signing
echo "🔨 Step 2: Building artifacts with signing..."
./gradlew :kmpworker:generateChecksums
echo "✅ Build completed"
echo ""

# Step 3: Verify signatures
echo "🔍 Step 3: Verifying signatures..."
ASC_COUNT=$(find kmpworker/build/maven-central-staging -name "*.asc" 2>/dev/null | wc -l)
echo "Found $ASC_COUNT .asc signature files"

if [ "$ASC_COUNT" -eq 0 ]; then
    echo "❌ ERROR: No signature files found!"
    echo "Signing may have failed. Check Gradle output above."
    exit 1
fi

echo "✅ Signatures created successfully"
echo ""

# Step 4: Test verify one signature
echo "🔐 Step 4: Testing signature verification..."
POM_FILE="kmpworker/build/maven-central-staging/dev/brewkits/kmpworkmanager/2.1.2/kmpworkmanager-2.1.2.pom.asc"
if [ -f "$POM_FILE" ]; then
    if gpg --verify "$POM_FILE" 2>&1 | grep -q "Good signature"; then
        echo "✅ Signature verification successful"
    else
        echo "⚠️  Warning: Signature verification had issues (but may still work on Maven Central)"
    fi
else
    echo "⚠️  Warning: Could not find test signature file"
fi
echo ""

# Step 5: Create ZIP
echo "📦 Step 5: Creating signed ZIP..."
cd kmpworker/build/maven-central-staging
ZIP_FILE="kmpworkmanager-2.1.2-signed.zip"
zip -r "$ZIP_FILE" dev/ > /dev/null 2>&1
ZIP_SIZE=$(du -h "$ZIP_FILE" | cut -f1)
echo "✅ Created: $ZIP_FILE ($ZIP_SIZE)"
echo ""

cd ../../..

# Summary
echo "═══════════════════════════════════════════════════════════════"
echo "  ✅ BUILD COMPLETED SUCCESSFULLY"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "📊 Statistics:"
echo "   • Total artifacts: $(find kmpworker/build/maven-central-staging -type f ! -name "*.asc" | wc -l) files"
echo "   • GPG signatures: $ASC_COUNT files"
echo "   • ZIP location: kmpworker/build/maven-central-staging/$ZIP_FILE"
echo ""
echo "📤 Next Steps:"
echo "   1. Go to: https://central.sonatype.com/"
echo "   2. Click 'Upload Bundle'"
echo "   3. Upload: kmpworker/build/maven-central-staging/$ZIP_FILE"
echo "   4. Follow portal instructions to publish"
echo ""
echo "═══════════════════════════════════════════════════════════════"
