#!/bin/bash
# The macOS / Linux installer (설치.command) without a car, against fakeadb.py. Runs on macOS or Linux.
# Covers install, the same install again (skipped), a new release (installed), an unverified head-unit build (refused),
# diagnostics (masked, read only), uninstall with the car gone (not "삭제 완료") and uninstall.
# usage: bash run.sh mac|linux   (sets VN_OS, so either path runs on either host)
F="$(cd "$(dirname "$0")" && pwd)"; I="$(dirname "$F")"; WHO="${1:-mac}"; [ "$WHO" = linux ] && OSV=Linux || OSV=Darwin
SRV="$F/server"
apks(){ mkdir -p "$SRV"; python3 -c "
import os,sys
for n,k in (('N-Dashboard-API17.apk',170000),('My-Apps-API17.apk',9000)):
    open(os.path.join(sys.argv[1],n),'wb').write(b'PK'+os.urandom(k))" "$SRV"; }
reset(){ rm -rf "$F/inst" "$F/tmp" "$F/calls.log" "$F/home"; mkdir -p "$F/home"; }
run(){ rm -f "$F/calls.log"; HOME="$F/home" VN_OS=$OSV bash "$I/설치.command" --adb "$F/adb" --yes "$@" > "$F/out.txt" 2>&1; echo $? > "$F/rc"; }
fail(){ echo "FAIL[$WHO]: $1"; cat "$F/out.txt"; exit 1; }
chmod +x "$F/adb"
reset; apks; A=(--apk-base "file://$SRV")
run "${A[@]}"; [ "$(cat $F/rc)" = 0 ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] || fail "1 install"
run "${A[@]}"; [ "$(cat $F/rc)" = 0 ] && ! grep -q 'pm install' $F/calls.log && [ "$(grep -c '건너뜀' $F/out.txt)" = 2 ] || fail "2 same install skipped"
apks; run "${A[@]}"; [ "$(cat $F/rc)" = 0 ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] || fail "3 new release installs"
apks; FAKE_BUILD=eng.vmadmin.20260114.000000 run "${A[@]}"; [ "$(cat $F/rc)" != 0 ] && ! grep -q 'pm install' $F/calls.log && grep -q '확인된 내비 빌드가 아닙니다' $F/out.txt || fail "4 unverified build refused"
run --diag
D="$(sed -n 's/.*만든 파일: //p' $F/out.txt | sed 's/\x1b\[[0-9;]*m//g' | tr -d '\r')"
[ "$(cat $F/rc)" = 0 ] && [ -f "$D" ] || fail "5 diag file ($D)"
! grep -qE 'pm install|uninstall|service call|push' $F/calls.log || fail "5 diag must not change the car"
grep -q 'versionName=1.9' "$D" && grep -q 'draw avg' "$D" && grep -q '이 PC 설치기 기록' "$D" || fail "5 diag content"
! grep -qE '00:1A:7D|1234-5678|kim@example|FAKE123' "$D" && grep -q '<MAC>' "$D" && grep -q '<전화번호>' "$D" && grep -q '<메일>' "$D" && grep -q '<기기번호>' "$D" || { cp "$D" "$F/diag_$WHO.txt"; fail "5 diag masking"; }
rm -f "$D"
FAKE_GONE=1 run --uninstall
[ "$(cat $F/rc)" != 0 ] && grep -q '연결이 끊긴' $F/out.txt && ! grep -q '삭제 완료' $F/out.txt || fail "6 uninstall with the car gone"
run --uninstall
[ "$(cat $F/rc)" = 0 ] && grep -q '삭제 완료' $F/out.txt && [ -z "$(ls $F/inst)" ] || fail "6 uninstall"
reset; rm -rf "$SRV" "$F/home" "$F/out.txt" "$F/rc"
echo "OK[$WHO]: install, skip identical, new release, unverified build refused, diag (masked, read only), uninstall"
