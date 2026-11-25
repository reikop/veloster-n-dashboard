#!/usr/bin/env bash
# Fetch the pinned build inputs for scripts/build.py and the APK signer into $1 (CI and anyone without the tools):
#   android.jar  SDK platform 16 (android-16_r05), the API the API17 apps compile against
#   r8.jar       R8/D8 8.3.37 from Google's Maven
#   platform.pk8 / platform.x509.pem  the public AOSP platform *test* key (the head unit only accepts it)
# Every download is checked against its SHA-256; a changed file stops the build. aapt2 comes from the SDK's build-tools.
set -euo pipefail
OUT="${1:?usage: ci_tools.sh DIR}"; mkdir -p "$OUT"; cd "$OUT"
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
get() {  # url file sha256 [base64]
  [ -f "$2" ] && [ "$(sha "$2")" = "$3" ] && return 0
  if [ "${4:-}" = base64 ]; then curl -fsSL "$1" | base64 -d > "$2"; else curl -fsSLo "$2" "$1"; fi
  [ "$(sha "$2")" = "$3" ] || { echo "SHA-256 mismatch: $2" >&2; exit 1; }
}
get https://dl.google.com/android/repository/android-16_r05.zip android-16_r05.zip fd7f269a423d1f1d079eabf9f918ceab49108702a1c6bb2589d57c23393503d3
unzip -o -q -j android-16_r05.zip 'android-4.1.2/android.jar' -d .
[ "$(sha android.jar)" = 05300b97ba88700b4514c8d4f689fcb0bf225ae8680c618a7c75512cd6795580 ] || { echo "SHA-256 mismatch: android.jar" >&2; exit 1; }
get https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar r8.jar 59753e70a74f918389cc87f1b7d66b5c0862932559167425708ded159e3de439
AOSP=https://android.googlesource.com/platform/build/+/android-4.2.2_r1/target/product/security
get "$AOSP/platform.pk8?format=TEXT" platform.pk8 1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875 base64
get "$AOSP/platform.x509.pem?format=TEXT" platform.x509.pem 9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14 base64
echo "build inputs ready in $OUT"
