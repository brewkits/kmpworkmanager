#!/bin/bash
# run-real-device-tests.sh
# Comprehensive real device testing automation script
# Version: 1.0
# Date: 2026-02-26

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print banner
echo -e "${BLUE}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════╗
║                                                           ║
║   KMP WorkManager - Real Device Test Runner              ║
║   Version 2.3.4                                           ║
║                                                           ║
╚═══════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}"

# Check if device is connected
echo -e "${YELLOW}🔍 Checking for connected devices...${NC}"
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device$" | wc -l | xargs)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo -e "${RED}❌ ERROR: No devices connected${NC}"
    echo ""
    echo "Please connect an Android device via USB and enable USB debugging:"
    echo "  1. Settings → About Phone → Tap 'Build Number' 7 times"
    echo "  2. Settings → Developer Options → USB Debugging → Enable"
    echo "  3. Connect device via USB"
    echo "  4. Accept USB debugging prompt on device"
    echo ""
    exit 1
fi

echo -e "${GREEN}✅ Found $DEVICE_COUNT device(s)${NC}"
echo ""

# List connected devices
echo "Connected devices:"
adb devices -l | grep -v "List" | grep "device"
echo ""

# Get device info
echo -e "${BLUE}📱 Device Information:${NC}"
DEVICE_MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
DEVICE_MANUFACTURER=$(adb shell getprop ro.product.manufacturer | tr -d '\r')
ANDROID_VERSION=$(adb shell getprop ro.build.version.release | tr -d '\r')
API_LEVEL=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
ROM_DISPLAY=$(adb shell getprop ro.build.display.id | tr -d '\r')

echo "   Manufacturer: $DEVICE_MANUFACTURER"
echo "   Model: $DEVICE_MODEL"
echo "   Android: $ANDROID_VERSION (API $API_LEVEL)"
echo "   ROM: $ROM_DISPLAY"
echo ""

# Detect if it's an emulator
DEVICE_FINGERPRINT=$(adb shell getprop ro.build.fingerprint | tr -d '\r')
if [[ "$DEVICE_FINGERPRINT" == *"generic"* ]] || [[ "$DEVICE_FINGERPRINT" == *"sdk"* ]]; then
    echo -e "${RED}⚠️  WARNING: Emulator detected!${NC}"
    echo "   Real device tests MUST run on physical devices"
    echo "   Emulators cannot accurately test:"
    echo "     - Background execution reliability"
    echo "     - Doze mode behavior"
    echo "     - Battery optimization"
    echo "     - Chinese ROM restrictions"
    echo ""
    read -p "Continue anyway? (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Detect Chinese ROM
CHINESE_ROM=false
if [[ "$DEVICE_MANUFACTURER" == *"Xiaomi"* ]] || \
   [[ "$DEVICE_MANUFACTURER" == *"Huawei"* ]] || \
   [[ "$DEVICE_MANUFACTURER" == *"OPPO"* ]] || \
   [[ "$DEVICE_MANUFACTURER" == *"vivo"* ]] || \
   [[ "$DEVICE_MANUFACTURER" == *"Realme"* ]] || \
   [[ "$DEVICE_MANUFACTURER" == *"OnePlus"* ]]; then
    CHINESE_ROM=true
    echo -e "${YELLOW}⚠️  Chinese ROM detected: $DEVICE_MANUFACTURER${NC}"
    echo ""
    echo "   IMPORTANT: Chinese ROMs require special permissions:"
    echo ""
    echo "   For MIUI (Xiaomi):"
    echo "     1. Settings → Apps → Manage Apps → [App] → Autostart → Enable"
    echo "     2. Settings → Battery → [App] → No restrictions"
    echo ""
    echo "   For EMUI (Huawei):"
    echo "     1. Phone Manager → Protected Apps → [App] → Enable"
    echo ""
    echo "   For ColorOS (Oppo/Realme):"
    echo "     1. Settings → Battery → [App] → Allow background activity"
    echo ""
    read -p "Have you configured these permissions? (y/N): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Please configure permissions first, then re-run this script"
        exit 1
    fi
fi

# Build test APKs
echo -e "${YELLOW}🔨 Building test APKs...${NC}"
./gradlew :composeApp:assembleDebug :kmpworker:assembleDebugAndroidTest --quiet
echo -e "${GREEN}✅ Build complete${NC}"
echo ""

# Install app
echo -e "${YELLOW}📲 Installing app on device...${NC}"
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk 2>&1 | grep -v "already exists" || true
echo -e "${GREEN}✅ App installed${NC}"
echo ""

# Grant required permissions
echo -e "${YELLOW}🔐 Granting required permissions...${NC}"
PACKAGE_NAME="dev.brewkits.kmpworkmanager.sample"

# Exact alarm permission (Android 12+)
if [ "$API_LEVEL" -ge 31 ]; then
    echo "   - SCHEDULE_EXACT_ALARM..."
    adb shell appops set $PACKAGE_NAME SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
fi

# Notification permission (Android 13+)
if [ "$API_LEVEL" -ge 33 ]; then
    echo "   - POST_NOTIFICATIONS..."
    adb shell pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS 2>/dev/null || true
fi

# Battery optimization exemption (for testing only)
echo "   - Battery optimization exemption (testing only)..."
adb shell dumpsys deviceidle whitelist +$PACKAGE_NAME 2>/dev/null || true

echo -e "${GREEN}✅ Permissions granted${NC}"
echo ""

# Run tests
echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                                                           ║${NC}"
echo -e "${BLUE}║   Running Real Device Test Suite                         ║${NC}"
echo -e "${BLUE}║                                                           ║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Test menu
echo "Select test suite to run:"
echo ""
echo "  1) All Tests (Comprehensive - ~30 minutes)"
echo "  2) Background Execution Tests (~10 minutes)"
echo "  3) Constraint Tests (~15 minutes)"
echo "  4) Stress Tests (~10 minutes)"
echo "  5) Performance Benchmarks (~5 minutes)"
echo "  6) Chinese ROM Tests (~5 minutes)"
echo "  7) Single Test (Manual selection)"
echo "  0) Exit"
echo ""
read -p "Enter choice [0-7]: " CHOICE

TEST_CLASS="dev.brewkits.kmpworkmanager.RealDeviceTestSuite"

case $CHOICE in
    1)
        echo -e "${YELLOW}Running all tests...${NC}"
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS
        ;;
    2)
        echo -e "${YELLOW}Running background execution tests...${NC}"
        METHODS="$TEST_CLASS#test_01_OneTimeTask_ExecutesInBackground,$TEST_CLASS#test_02_PeriodicTask_ExecutesRegularly,$TEST_CLASS#test_03_TaskChain_ExecutesSequentially"
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$METHODS
        ;;
    3)
        echo -e "${YELLOW}Running constraint tests...${NC}"
        METHODS="$TEST_CLASS#test_04_NetworkConstraint_BlocksWithoutNetwork,$TEST_CLASS#test_05_ChargingConstraint_WaitsForCharger"
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$METHODS
        ;;
    4)
        echo -e "${YELLOW}Running stress tests...${NC}"
        METHODS="$TEST_CLASS#test_06_StressTest_100ConcurrentTasks,$TEST_CLASS#test_07_RapidReschedule_NoMemoryLeak"
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$METHODS
        ;;
    5)
        echo -e "${YELLOW}Running performance benchmarks...${NC}"
        METHODS="$TEST_CLASS#test_09_Performance_SchedulingLatency,$TEST_CLASS#test_10_Performance_TaskExecutionStartTime"
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$METHODS
        ;;
    6)
        if [ "$CHINESE_ROM" = true ]; then
            echo -e "${YELLOW}Running Chinese ROM tests...${NC}"
            ./gradlew :kmpworker:connectedDebugAndroidTest \
              -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS#test_08_ChineseROM_CompatibilityCheck
        else
            echo -e "${YELLOW}⏭️  Skipped: Not a Chinese ROM device${NC}"
        fi
        ;;
    7)
        echo ""
        echo "Available test methods:"
        echo "  test_01_OneTimeTask_ExecutesInBackground"
        echo "  test_02_PeriodicTask_ExecutesRegularly"
        echo "  test_03_TaskChain_ExecutesSequentially"
        echo "  test_04_NetworkConstraint_BlocksWithoutNetwork"
        echo "  test_05_ChargingConstraint_WaitsForCharger"
        echo "  test_06_StressTest_100ConcurrentTasks"
        echo "  test_07_RapidReschedule_NoMemoryLeak"
        echo "  test_08_ChineseROM_CompatibilityCheck"
        echo "  test_09_Performance_SchedulingLatency"
        echo "  test_10_Performance_TaskExecutionStartTime"
        echo ""
        read -p "Enter test method name: " TEST_NAME
        ./gradlew :kmpworker:connectedDebugAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS#$TEST_NAME
        ;;
    0)
        echo "Exiting..."
        exit 0
        ;;
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

# Check test results
echo ""
echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                                                           ║${NC}"
echo -e "${BLUE}║   Test Results                                            ║${NC}"
echo -e "${BLUE}║                                                           ║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Open test report
REPORT_PATH="kmpworker/build/reports/androidTests/connected/index.html"
if [ -f "$REPORT_PATH" ]; then
    echo -e "${GREEN}✅ Test report generated:${NC}"
    echo "   file://$(pwd)/$REPORT_PATH"
    echo ""

    # Try to open in browser (macOS)
    if [[ "$OSTYPE" == "darwin"* ]]; then
        read -p "Open report in browser? (Y/n): " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Nn]$ ]]; then
            open "$REPORT_PATH"
        fi
    fi
else
    echo -e "${YELLOW}⚠️  Test report not found${NC}"
fi

# Show device logs
echo ""
read -p "Show device logs? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}📝 Device logs (last 100 lines):${NC}"
    echo ""
    adb logcat -d | grep -E "KmpWorkManager|Worker|RealDevice" | tail -100
fi

echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                                                           ║${NC}"
echo -e "${GREEN}║   ✅ Real Device Testing Complete                         ║${NC}"
echo -e "${GREEN}║                                                           ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Cleanup
echo "Cleanup options:"
echo "  1) Uninstall test app from device"
echo "  2) Keep app installed"
echo ""
read -p "Enter choice [1-2]: " CLEANUP_CHOICE

if [ "$CLEANUP_CHOICE" = "1" ]; then
    echo "Uninstalling..."
    adb uninstall $PACKAGE_NAME 2>/dev/null || true
    echo -e "${GREEN}✅ App uninstalled${NC}"
fi

echo ""
echo "Thank you for testing KMP WorkManager! 🚀"
echo ""
