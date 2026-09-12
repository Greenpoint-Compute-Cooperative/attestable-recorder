#!/usr/bin/env bash
# Rebuild the unsigned release APK from a clean tree and print its SHA-256.
# Optionally compare it, content-for-content, with a published (signed or unsigned) APK.
#
#   scripts/reproduce.sh                      # prints sha256 of app-release-unsigned.apk
#   scripts/reproduce.sh path/to/published.apk   # also compares contents, exit 1 on mismatch
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon -q clean >/dev/null
./gradlew --no-daemon -q :app:assembleRelease -PunsignedRelease
APK=app/build/outputs/apk/release/app-release-unsigned.apk
echo "toolchain: $(java -version 2>&1 | head -1 | tr -d '"') · $(grep distributionUrl gradle/wrapper/gradle-wrapper.properties | sed 's/.*\///')"
echo "app-release-unsigned.apk sha256: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
if [ $# -ge 1 ]; then
  python3 scripts/apk-compare.py "$APK" "$1"
fi
