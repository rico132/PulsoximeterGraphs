#!/usr/bin/env bash
# Sets up the environment needed to build the PulsoximeterGraphs Android app
# and runs the Gradle build.
#
# Usage:
#   ./build.sh                  # runs assembleDebug (default)
#   ./build.sh assembleRelease  # or any other Gradle task(s)/args
set -euo pipefail

# Always run from the android/ project root, regardless of the caller's cwd.
cd "$(dirname "${BASH_SOURCE[0]}")"

# Java 17 is required by the app's build (compileOptions/jvmTarget = 17) and by
# Gradle 8.13 / AGP 8.13.2. Falls back to this sandbox's JDK 17 if not already set.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

# Android SDK location. ANDROID_SDK_ROOT is the modern name; ANDROID_HOME is kept
# for compatibility with older tooling. Falls back to this sandbox's SDK install.
export ANDROID_HOME="${ANDROID_HOME:-/home/agent/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"

chmod +x ./gradlew

./gradlew "${@:-assembleDebug}"
