#!/bin/bash

# KMP WorkManager - Maven Central Publishing Script
# Automated script to publish to Maven Central (Sonatype OSSRH)

set -e  # Exit on error

echo "🚀 KMP WorkManager - Maven Central Publishing Script"
echo "=================================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check prerequisites
echo "📋 Checking prerequisites..."

# Check if credentials exist
if [ ! -f "$HOME/.gradle/gradle.properties" ]; then
    echo -e "${RED}❌ Error: ~/.gradle/gradle.properties not found${NC}"
    echo ""
    echo "Please create ~/.gradle/gradle.properties with:"
    echo ""
    echo "ossrhUsername=your_sonatype_username"
    echo "ossrhPassword=your_sonatype_password"
    echo "signing.key=<BASE64_GPG_KEY>"
    echo "signing.password=your_gpg_password"
    echo ""
    echo "See MAVEN_CENTRAL_SETUP.md for details"
    exit 1
fi

# Check if GPG signing key is configured
if ! grep -q "signing.key" "$HOME/.gradle/gradle.properties"; then
    echo -e "${YELLOW}⚠️  Warning: signing.key not found in gradle.properties${NC}"
    echo "Publishing without signing may fail on Maven Central"
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo -e "${GREEN}✅ Prerequisites check passed${NC}"
echo ""

# Confirm version
VERSION=$(grep "^version = " kmpworker/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
echo "📦 Publishing version: ${GREEN}${VERSION}${NC}"
echo ""

read -p "Is this the correct version? (Y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Nn]$ ]]; then
    echo "Please update version in kmpworker/build.gradle.kts"
    exit 1
fi

# Clean build
echo ""
echo "🧹 Cleaning previous build..."
./gradlew clean

# Build and test
echo ""
echo "🔨 Building library..."
./gradlew :kmpworker:build

echo ""
echo "✅ Build successful!"

# Publish to local staging first (for verification)
echo ""
echo "📦 Publishing to local staging (for verification)..."
./gradlew :kmpworker:publishAllPublicationsToMavenCentralLocalRepository

# Generate checksums
echo ""
echo "🔐 Generating checksums..."
./gradlew :kmpworker:generateChecksums

echo ""
echo "✅ Local staging complete"
echo "Artifacts location: kmpworker/build/maven-central-staging/"

# Verify artifacts exist
STAGING_DIR="kmpworker/build/maven-central-staging/io/brewkits/kmpworker/${VERSION}"
if [ ! -d "$STAGING_DIR" ]; then
    echo -e "${RED}❌ Error: Staging directory not found${NC}"
    exit 1
fi

# Count artifacts
JAR_COUNT=$(find "$STAGING_DIR" -name "*.jar" | wc -l)
POM_COUNT=$(find "$STAGING_DIR" -name "*.pom" | wc -l)
MD5_COUNT=$(find kmpworker/build/maven-central-staging -name "*.md5" | wc -l)
SHA1_COUNT=$(find kmpworker/build/maven-central-staging -name "*.sha1" | wc -l)

echo ""
echo "📊 Artifact Summary:"
echo "  - JARs: $JAR_COUNT"
echo "  - POMs: $POM_COUNT"
echo "  - MD5 checksums: $MD5_COUNT"
echo "  - SHA1 checksums: $SHA1_COUNT"

# Final confirmation
echo ""
echo -e "${YELLOW}⚠️  IMPORTANT${NC}"
echo "You are about to publish to Maven Central (Sonatype OSSRH)"
echo "This will:"
echo "  1. Upload all artifacts to Sonatype staging repository"
echo "  2. Sign artifacts with your GPG key"
echo "  3. Deploy to staging for review"
echo ""
echo "After staging, you need to:"
echo "  - Login to https://s01.oss.sonatype.org/"
echo "  - Find your staging repository"
echo "  - Click 'Close' to verify"
echo "  - Click 'Release' to publish to Maven Central"
echo ""

read -p "Continue with publishing? (yes/NO) " -r
echo
if [[ ! $REPLY == "yes" ]]; then
    echo "Publishing cancelled"
    exit 0
fi

# Publish to OSSRH
echo ""
echo "🚀 Publishing to Maven Central (OSSRH)..."
echo "This may take several minutes..."
echo ""

if ./gradlew :kmpworker:publishAllPublicationsToOSSRHRepository; then
    echo ""
    echo -e "${GREEN}✅ Publishing successful!${NC}"
    echo ""
    echo "📋 Next Steps:"
    echo "1. Visit https://s01.oss.sonatype.org/"
    echo "2. Login with your Sonatype credentials"
    echo "3. Click 'Staging Repositories' in left menu"
    echo "4. Find 'io.brewkits-XXXX' repository"
    echo "5. Click 'Close' button (wait for validation)"
    echo "6. If validation passes, click 'Release' button"
    echo ""
    echo "⏱️  Sync to Maven Central: ~2-4 hours after release"
    echo "⏱️  Appear on klibs.io: ~24 hours after Maven Central sync"
    echo ""
    echo "🎉 Congratulations!"
else
    echo ""
    echo -e "${RED}❌ Publishing failed${NC}"
    echo ""
    echo "Common issues:"
    echo "- Missing signing credentials"
    echo "- Incorrect Sonatype credentials"
    echo "- Group ID not verified in Sonatype JIRA"
    echo ""
    echo "Check logs above for details"
    exit 1
fi

