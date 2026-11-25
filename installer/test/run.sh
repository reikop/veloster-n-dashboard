#!/bin/bash
# The macOS / Linux installer (설치.command) without a car, against fakeadb.py. Runs on macOS or Linux.
# The installer carries its APKs: a kit folder holds 설치.command, the two APKs and bundle.txt (as a release ZIP does).
# Covers install, the same install again (skipped), a new bundle (installed), another head-unit build (warned, installed), API other than 17 (refused),
# diagnostics (masked, read only), uninstall with the car gone (not "삭제 완료"), uninstall, a tampered bundled APK and
# a missing bundle.txt (refused), and the window installer's --gui protocol: waiting for the car (@@WAIT), steps,
# @@ASK answered no / yes on stdin for a signature clash, @@DONE ok / fail.
# usage: bash run.sh mac|linux   (sets VN_OS, so either path runs on either host)
F="$(cd "$(dirname "$0")" && pwd)"; I="$(dirname "$F")"; WHO="${1:-mac}"; [ "$WHO" = linux ] && OSV=Linux || OSV=Darwin
K="$F/kit"
sha(){ if command -v shasum >/dev/null; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi; }
bundle(){ { printf '# test bundle\n'
  printf 'N-Dashboard-API17.apk\tcom.veloster.dashboard\t6\t1.5-test\t%s\n' "$(sha "$K/N-Dashboard-API17.apk")"
  printf 'My-Apps-API17.apk\tcom.veloster.carplayhub\t10\t1.9-test\t%s\n' "$(sha "$K/My-Apps-API17.apk")"; } > "$K/bundle.txt"; }
apks(){ mkdir -p "$K"; cp "$I/설치.command" "$K/"; python3 -c "
import os,sys
for n,k in (('N-Dashboard-API17.apk',170000),('My-Apps-API17.apk',9000)):
    open(os.path.join(sys.argv[1],n),'wb').write(b'PK'+os.urandom(k))" "$K"; bundle; }
reset(){ rm -rf "$F/inst" "$F/tmp" "$F/calls.log" "$F/home" "$F/clash.done"; mkdir -p "$F/home"; }
run(){ rm -f "$F/calls.log"; HOME="$F/home" VN_OS=$OSV bash "$K/설치.command" --adb "$F/adb" --yes "$@" > "$F/out.txt" 2>&1; echo $? > "$F/rc"; }
# --gui: no --yes; answers come from $ANSWER (one line per @@ASK), nothing else is read
gui(){ rm -f "$F/calls.log"; printf '%s' "${ANSWER:-}" | HOME="$F/home" VN_OS=$OSV bash "$K/설치.command" --adb "$F/adb" --gui "$@" > "$F/out.txt" 2>&1; echo $? > "$F/rc"; }
fail(){ echo "FAIL[$WHO]: $1"; cat "$F/out.txt"; exit 1; }
chmod +x "$F/adb"
# fake GitHub releases: by default the latest release is this installer's own version (no update offered)
CUR="$(sed -n "s/^INSTALLER_VERSION='\([^']*\)'.*/\1/p" "$I/설치.command")"
R="$F/rel"; rm -rf "$R"; mkdir -p "$R"; echo "$CUR" > "$R/latest.txt"
RPORT=$((20000 + RANDOM % 20000)); python3 -I "$F/fakerelease.py" $RPORT "$R" & RPID=$!; trap 'kill $RPID 2>/dev/null' EXIT
for _ in $(seq 50); do [ -f "$R/server.log" ] && break; sleep 0.1; done
export VN_RELEASES="http://127.0.0.1:$RPORT"
reset; apks
run; [ "$(cat $F/rc)" = 0 ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] && grep -q "이 설치기에 든 버전: N 계기판 1.5-test · '내 앱' 1.9-test" $F/out.txt || fail "1 install"
run; [ "$(cat $F/rc)" = 0 ] && ! grep -q 'pm install' $F/calls.log && [ "$(grep -c '건너뜀' $F/out.txt)" = 2 ] || fail "2 same install skipped"
apks; run; [ "$(cat $F/rc)" = 0 ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] || fail "3 new bundle installs"
apks; FAKE_BUILD=eng.vmadmin.20260114.000000 run; [ "$(cat $F/rc)" = 0 ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] && grep -q '검증한 빌드.*다른 빌드입니다(eng.vmadmin.20260114.000000)' $F/out.txt || fail "4 another build warns and installs"
apks; FAKE_SDK=19 run; [ "$(cat $F/rc)" != 0 ] && ! grep -q 'pm install' $F/calls.log && grep -q '지원하지 않는 내비입니다(API 19' $F/out.txt || fail "4b API 19 refused"
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
[ "$(cat $F/rc)" = 0 ] && grep -q '삭제 완료' $F/out.txt && [ -z "$(ls $F/inst)" ] && ! grep -q 'service call' $F/calls.log || fail "6 uninstall"
# a bundled APK that is not the one bundle.txt names, and a kit without bundle.txt: nothing reaches the car
printf 'x' >> "$K/My-Apps-API17.apk"; run
[ "$(cat $F/rc)" != 0 ] && grep -q 'SHA-256 불일치' $F/out.txt && ! grep -qE 'push|pm ' $F/calls.log 2>/dev/null || fail "7 tampered APK refused"
apks; rm "$K/bundle.txt"; run
[ "$(cat $F/rc)" != 0 ] && grep -q 'bundle.txt' $F/out.txt && ! grep -qE 'push|pm ' $F/calls.log 2>/dev/null || fail "8 missing bundle.txt refused"
# --gui: waits for the car on its own (no Enter), status lines, done
apks; reset; FAKE_ABSENT=2 gui
[ "$(cat $F/rc)" = 0 ] && [ "$(grep -c '^@@WAIT [0-9]* → 차를 기다리는 중' $F/out.txt)" -ge 2 ] && grep -q '^@@STEP 3) 차 연결 확인' $F/out.txt \
  && grep -q '^@@WAIT 0 ① 전송 (USB)' $F/out.txt && [ "$(tail -1 $F/out.txt)" = '@@DONE ok' ] && [ "$(grep -c 'pm install' $F/calls.log)" = 2 ] \
  && ! grep -q 'Enter' $F/out.txt || fail "9 gui install"
# --gui signature clash: @@ASK, answered n (keep the app, fail) then y (uninstall + reinstall)
apks; rm -f "$F/clash.done"; FAKE_CLASH=1 ANSWER=$'n\n' gui
[ "$(cat $F/rc)" != 0 ] && grep -q '^@@ASK 지우고 다시 설치할까요' $F/out.txt && ! grep -q 'uninstall' $F/calls.log && grep -q '^@@DONE fail N 계기판 설치 실패' $F/out.txt || fail "10 gui ask → no"
rm -f "$F/clash.done"; FAKE_CLASH=1 ANSWER=$'y\n' gui
[ "$(cat $F/rc)" = 0 ] && grep -q '^@@ASK ' $F/out.txt && grep -q 'uninstall com.veloster.dashboard' $F/calls.log && [ "$(tail -1 $F/out.txt)" = '@@DONE ok' ] || fail "11 gui ask → yes"
# --gui refusal still stops, as a fail line
apks; FAKE_SDK=19 gui
[ "$(cat $F/rc)" != 0 ] && grep -q '^@@DONE fail 지원하지 않는 내비입니다' $F/out.txt && grep -q '^@@FILE ' $F/out.txt && ! grep -q 'pm install' $F/calls.log || fail "12 gui refusal"
FAKE_GONE=1 gui --uninstall; grep -q '^@@DONE fail ' $F/out.txt || fail "13 gui uninstall failure"
# update: --check-update is silent when the latest release is this version, names a newer one
gui --check-update; [ "$(cat $F/rc)" = 0 ] && [ ! -s $F/out.txt ] || fail "14 no update when current"
echo 9.9.9 > "$R/latest.txt"; gui --check-update; [ "$(cat $F/out.txt)" = '@@UPDATE 9.9.9' ] || fail "15 newer release named"
# --update: the release ZIP is fetched, checked against SHA256SUMS.txt and unpacked; the new installer is named by @@OPEN
[ "$OSV" = Darwin ] && { ASSET=vn-installer-mac.zip; NEW='N 계기판 설치.app/Contents/Info.plist'; } || { ASSET=vn-installer-linux.zip; NEW=install.sh; }
python3 -I -c "
import sys,zipfile
z=zipfile.ZipFile(sys.argv[1],'w'); z.writestr(sys.argv[2],'new installer 9.9.9\n'); z.close()" "$R/$ASSET" "$NEW"
printf '%s  %s\n' "$(sha "$R/$ASSET")" "$ASSET" > "$R/SHA256SUMS.txt"
gui --update; O="$(sed -n 's/^@@OPEN //p' $F/out.txt)"
[ "$(cat $F/rc)" = 0 ] && [ -n "$O" ] && [ -e "$O" ] && [ "$(tail -1 $F/out.txt)" = '@@DONE ok' ] || fail "16 update fetched and opened"
# a release ZIP whose hash is not the listed one is refused and nothing is unpacked
printf '%064d  %s\n' 0 "$ASSET" > "$R/SHA256SUMS.txt"; rm -rf "$F/home"; mkdir -p "$F/home"
gui --update; [ "$(cat $F/rc)" != 0 ] && grep -q '^@@DONE fail 받은 파일의 SHA-256' $F/out.txt && ! grep -q '^@@OPEN' $F/out.txt \
  && [ -z "$(ls "$F/home/Library/Application Support/vn-installer/update" "$F/home"/vn-installer-* 2>/dev/null)" ] || fail "17 tampered update refused"
echo "$CUR" > "$R/latest.txt"
reset; rm -rf "$K" "$F/home" "$F/out.txt" "$F/rc" "$R"
echo "OK[$WHO]: install, skip identical, new bundle, another build warned, API 19 refused, diag (masked, read only), uninstall, tampered/missing bundle refused, --gui wait/ask/done, update check/fetch/tampered"
