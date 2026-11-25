#!/usr/bin/env bash
# The whole release, the same in GitHub Actions (.github/workflows/release.yml) and on a Mac:
#   both API17 apps (scripts/build.py) signed with the AOSP platform test key, the phone installer app
#   (installer/android/build.sh, its own release key), and the installer assets (scripts/package_installer.py).
# Needs macOS (the mac installer .app: osacompile, codesign), JDK 17, python3, openssl and an Android SDK with
# build-tools;34.0.0 and platforms;android-34.
# env: ANDROID_HOME  KS (phone installer keystore, outside the repo)  KS_PASS or KS_PASS_FILE
#      TOOLS (default build/ci-tools: scripts/ci_tools.sh fills it)  OUT (default build/release)
# Never connects to a vehicle.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"; cd "$REPO"
VERSION="$(python3 scripts/check_versions.py ${TAG:+--tag "$TAG"})"
TOOLS="${TOOLS:-$REPO/build/ci-tools}"; OUT="${OUT:-$REPO/build/release}"
SDK="${ANDROID_HOME:?set ANDROID_HOME}"; AAPT2="$SDK/build-tools/34.0.0/aapt2"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
bash scripts/ci_tools.sh "$TOOLS"
rm -rf "$OUT"; mkdir -p "$OUT/apks" "$OUT/assets"
for app in dashboard hub; do
  python3 scripts/build.py "$app" --android-jar "$TOOLS/android.jar" --r8-jar "$TOOLS/r8.jar" --aapt2 "$AAPT2" --java "$JAVA" --out "$OUT/build-$app"
done
python3 scripts/sign_apk_v1_legacy.py --key "$TOOLS/platform.pk8" --cert "$TOOLS/platform.x509.pem" "$OUT/build-dashboard/dashboard-UNSIGNED.apk" "$OUT/apks/N-Dashboard-API17.apk"
python3 scripts/sign_apk_v1_legacy.py --key "$TOOLS/platform.pk8" --cert "$TOOLS/platform.x509.pem" "$OUT/build-hub/hub-UNSIGNED.apk" "$OUT/apks/My-Apps-API17.apk"
APKS="$OUT/apks" OUT="$OUT/vn-installer-android.apk" bash installer/android/build.sh
python3 scripts/package_installer.py --out "$OUT/assets" --apks "$OUT/apks" --android-apk "$OUT/vn-installer-android.apk"
echo "release $VERSION:"; cat "$OUT/assets/SHA256SUMS.txt"
