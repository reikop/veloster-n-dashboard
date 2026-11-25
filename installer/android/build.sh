#!/usr/bin/env bash
# Phone installer APK (vn-installer-android.apk): aapt2 (manifest + icon) + javac + d8 + apksigner. No Gradle.
# Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android/build.sh, MIT).
#
# ANDROID_HOME: SDK with build-tools;34.0.0 and platforms;android-34 (a phone app, minSdk 26: install permission,
#   adaptive icon). Install them with  sdkmanager "platforms;android-34" "build-tools;34.0.0"  if missing.
# KS: release keystore (PKCS12), kept OUT of every repo. The same key must sign every version, or phones refuse the update.
#   Created on the first build when the file does not exist.
# KS_PASS: its password, from the environment, or KS_PASS_FILE: a file holding it (first line).
# APKS: folder with N-Dashboard-API17.apk and My-Apps-API17.apk (required). Both go into the APK at assets/bundle/ with
#   bundle.txt (scripts/apk_info.py: package, versionCode, versionName, sha256); the app installs only those bytes.
# OUT: where the signed APK goes (default: <repo>/build/vn-installer-android.apk; build/ is gitignored).
# JAVA_HOME: JDK 17. d8 8.2 (build-tools 34) fails on enums compiled by javac 21, so javac must be 17.
#   D8_JAVA: the java that runs d8, when it is not $JAVA_HOME/bin/java.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SDK="${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || { echo "missing $JAR: sdkmanager \"platforms;android-34\"" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "missing $BT: sdkmanager \"build-tools;34.0.0\"" >&2; exit 1; }
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
"${JBIN}javac" -version 2>&1 | grep -q 'javac 17' || { echo "javac 17 required (set JAVA_HOME to JDK 17)" >&2; exit 1; }
KS="${KS:?set KS to the release keystore path (outside the repo)}"
case "$(cd "$(dirname "$KS")" 2>/dev/null && pwd)/" in "$REPO"/*) echo "KS must be outside the repo" >&2; exit 1 ;; esac
if [ -z "${KS_PASS:-}" ] && [ -n "${KS_PASS_FILE:-}" ]; then KS_PASS="$(head -1 "$KS_PASS_FILE")"; fi
: "${KS_PASS:?set KS_PASS or KS_PASS_FILE}"
export KS_PASS
OUT="${OUT:-$REPO/build/vn-installer-android.apk}"
APKS="${APKS:?set APKS to the folder with N-Dashboard-API17.apk and My-Apps-API17.apk (bundled into the app)}"
APKS="$(cd "$APKS" && pwd)"
# versionName = Flow.VERSION (one place, MAJOR.MINOR.PATCH). versionCode comes from it too, so the [업데이트] band
# (compares VERSION) and the newer-APK check (compares versionCode) cannot disagree:
# 2100000000 + MAJOR*1000000 + MINOR*1000 + PATCH — above the old date codes (2026112400) so 1.11.0 installs over them.
VER_NAME="$(grep -o 'VERSION = "[^"]*"' "$HERE/src/com/veloster/installer/Flow.java" | head -1 | cut -d'"' -f2)"
IFS=. read -r V_MA V_MI V_PA <<<"$VER_NAME"
case "$V_MA.$V_MI.$V_PA" in *[!0-9.]*|.*|*..*|*.) echo "Flow.VERSION must be MAJOR.MINOR.PATCH: $VER_NAME" >&2; exit 1 ;; esac
VER_CODE="${VER_CODE:-$(( 2100000000 + V_MA * 1000000 + V_MI * 1000 + V_PA ))}"
cd "$HERE"
rm -rf build && mkdir -p build/classes
python3 -I "$REPO/scripts/apk_info.py" --bundle "$APKS" build/bundle.txt >/dev/null   # refuses missing or wrong APKs
"$BT/aapt2" compile --dir res -o build/res.zip   # the launcher icon
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" -R build/res.zip --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" -o build/base.apk
# java.* (Base64, nio.file) comes from the JDK's API 8 surface; d8 maps it onto the phone's runtime (API 26+).
"${JBIN}javac" --release 8 -encoding UTF-8 -cp "$JAR" -d build/classes src/com/veloster/installer/*.java
"${D8_JAVA:-${JBIN}java}" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
python3 -I - build/base.apk build/classes.dex build/bundle.txt "$APKS" <<'PY'
import sys, zipfile
apk, dex, bundle, apks = sys.argv[1:]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    z.write(bundle, 'assets/bundle/bundle.txt')
    for name in ('N-Dashboard-API17.apk', 'My-Apps-API17.apk'):
        z.write(apks + '/' + name, 'assets/bundle/' + name)
PY
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
[ -f "$KS" ] || "${JBIN}keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -storepass:env KS_PASS -alias installer \
  -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=Veloster N dashboard phone installer"
mkdir -p "$(dirname "$OUT")"
"${JBIN}java" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass env:KS_PASS --out "$OUT" build/aligned.apk
rm -f "$OUT.idsig"
ls -l "$OUT"
