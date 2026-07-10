#!/data/data/com.termux/files/usr/bin/bash
# Build Netboost Android APK in Termux
# Requires: pkg install android-sdk android-ndk gradle

set -e

echo "==> Netboost APK Builder for Termux"
echo ""

# Check for Android SDK
if [ ! -d "$HOME/android-sdk" ]; then
    echo "Installing Android SDK..."
    pkg install -y android-sdk android-ndk gradle 2>/dev/null || {
        echo "Failed to install build tools. Try:"
        echo "  pkg install android-sdk android-ndk gradle"
        exit 1
    }
fi

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools"

# Accept licenses
yes | sdkmanager --licenses 2>/dev/null || true

# Install required SDK components
sdkmanager "platforms;android-34" "build-tools;34.0.0" 2>/dev/null || true

echo ""
echo "==> Building APK..."
cd "$(dirname "$0")"
gradle assembleDebug

echo ""
echo "==> APK built!"
ls -lh app/build/outputs/apk/debug/netboost-debug.apk 2>/dev/null || \
ls -lh app/build/outputs/apk/debug/*.apk
