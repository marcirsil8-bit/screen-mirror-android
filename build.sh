#!/bin/bash
# Build script for Screen Mirror apps
# Requires: Android SDK + Gradle + JDK 17

set -e

echo "=== 🔨 Compilando Screen Mirror Sender ==="
cd "$(dirname "$0")/sender"
./gradlew assembleRelease --no-daemon -x lint
cp app/build/outputs/apk/release/app-release-unsigned.apk ../screen-mirror-sender.apk 2>/dev/null || \
cp app/build/outputs/apk/debug/app-debug.apk ../screen-mirror-sender.apk
echo "✅ Sender APK: screen-mirror-sender.apk"

echo ""
echo "=== 🔨 Compilando Screen Mirror Receiver ==="
cd "$(dirname "$0")/receiver"
./gradlew assembleRelease --no-daemon -x lint
cp app/build/outputs/apk/release/app-release-unsigned.apk ../screen-mirror-receiver.apk 2>/dev/null || \
cp app/build/outputs/apk/debug/app-debug.apk ../screen-mirror-receiver.apk
echo "✅ Receiver APK: screen-mirror-receiver.apk"

echo ""
echo "=== ✅ Build completo ==="
echo "📱 Instale o sender no celular"
echo "📺 Instale o receiver na TV Box"
echo "📶 Conecte ambos na mesma rede WiFi"
