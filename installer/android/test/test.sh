#!/usr/bin/env bash
# Phone installer without a phone or a car: Flow + Adb on the desktop JDK (17) against fake_adbd.py. Runs on macOS / Linux.
# Covers new-key prompt, known-key signature, signature clash reinstall, uninstall (two apps only, ☆ never changed),
# a non-jsn_kr navi, a jsn_kr navi on another build, skipping an identical install, the masked diagnostics file, and a
# tampered bundled APK / a missing bundle.txt (refused before the car is touched). The APKs are bundled (no download).
# Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android/test/test.sh, MIT).
set -euo pipefail
cd "$(dirname "$0")"
T=$(mktemp -d); FAKE=''; trap '[ -n "$FAKE" ] && { kill $FAKE; wait $FAKE; } 2>/dev/null; rm -rf "$T"' EXIT
mkdir -p "$T/server" "$T/key" "$T/cls"
# the bundle dir (what MainActivity extracts from assets/bundle): two APKs + bundle.txt naming their SHA-256
apks(){ python3 -I -c "
import hashlib,os,sys
rows=['# test bundle']
for n,p,k in (('N-Dashboard-API17.apk','com.veloster.dashboard',170000),('My-Apps-API17.apk','com.veloster.carplayhub',9000)):
    b=b'PK'+os.urandom(k); open(os.path.join(sys.argv[1],n),'wb').write(b)
    rows.append('\t'.join((n,p,'7','1.0-test',hashlib.sha256(b).hexdigest())))
open(os.path.join(sys.argv[1],'bundle.txt'),'w').write('\n'.join(rows)+'\n')" "$T/server"; }
apks
S=../src/com/veloster/installer
javac -encoding UTF-8 -d "$T/cls" $S/Adb.java $S/Flow.java FlowTest.java
java -ea -Dsun.stdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$T/cls" com.veloster.installer.FlowTest unit || { echo "FAIL: unit"; exit 1; }
PORT=$((20000 + RANDOM % 20000))
python3 -X utf8 fake_adbd.py $PORT "$T/fake.log" "$T/server" & FAKE=$!
for _ in $(seq 50); do grep -q ready "$T/fake.log" 2>/dev/null && break; sleep 0.1; done
BUNDLE="$T/server"
run(){ java -Dsun.stdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$T/cls" com.veloster.installer.FlowTest $PORT "$BUNDLE" "$T/key" "$1" > "$T/out$2.txt"; }
fail(){ echo "FAIL: $1"; echo "--- fake log"; cat "$T/fake.log"; for f in "$T"/out*.txt; do echo "--- $f"; cat "$f"; done; exit 1; }
stop(){ set +e; run install $1; rc=$?; set -e; [ $rc -eq 3 ] || fail "$1 should stop (rc=$rc)"; }
conns(){ grep -c '^== connection' "$T/fake.log"; }

run install 1 || fail "1 new key install"
apks   # a new release: different bytes, so 2 really installs
run install 2 || fail "2 clash reinstall"
run uninstall 3 || fail "3 uninstall"
stop 4
stop 5
run install 6 || fail "6 install"
run install 7 || fail "7 same install again"
run diag 8 || fail "8 diagnostics"
before=$(conns); printf 'x' >> "$T/server/My-Apps-API17.apk"; stop 9
[ "$(conns)" = "$before" ] && grep -q 'SHA-256 불일치' "$T/out9.txt" || fail "9 tampered APK must stop before the car"
apks; rm "$T/server/bundle.txt"; stop 10
[ "$(conns)" = "$before" ] && grep -q 'bundle.txt' "$T/out10.txt" || fail "10 missing bundle.txt must stop before the car"

L="$T/fake.log"
grep -q 'FAKE ERROR' "$L" && fail "fake adbd rejected something"
c(){ awk -v n="== connection $1" '$0==n{on=1;next} /^== connection/{on=0} on' "$L"; }
c 1 | head -1 | grep -qx 'auth pubkey' || fail "1 should send the public key"
[ "$(c 1 | grep -c '^push ')" = 2 ] && c 1 | grep -q 'push /data/local/tmp/N-Dashboard-API17.apk 170002' && c 1 | grep -q 'push /data/local/tmp/My-Apps-API17.apk 9002' || fail "1 pushes"
[ "$(c 1 | grep -c 'pm install')" = 2 ] && c 1 | grep -q 'am start -n com.veloster.carplayhub/.HubActivity' || fail "1 installs + 내 앱"
grep -q "이 설치 앱에 든 버전: N 계기판 1.0-test · '내 앱' 1.0-test" "$T/out1.txt" || fail "1 bundled versions line"
c 2 | head -1 | grep -qx 'auth signature' || fail "2 should sign with the saved key"
c 2 | grep -q 'pm uninstall com.veloster.dashboard' && [ "$(c 2 | grep -c 'pm install')" = 3 ] || fail "2 clash → uninstall → reinstall"
grep -q '다른 서명' "$T/out2.txt" || fail "2 should ask before uninstalling"
[ "$(c 3 | grep -c 'pm uninstall')" = 2 ] && c 3 | grep -q 'pm uninstall com.veloster.dashboard' && c 3 | grep -q 'pm uninstall com.veloster.carplayhub' && ! c 3 | grep -qE '^push |pm install' || fail "3 uninstall only"
grep -q '삭제 완료' "$T/out3.txt" && grep -q '☆를 길게 눌러' "$T/out3.txt" || fail "3 uninstall messages"
! grep -q 'service call' "$L" || fail "no installer step may run service call (☆ is never changed)"
! c 4 | grep -qE 'push|pm ' && grep -q 'jsn_kr2' "$T/out4.txt" || fail "4 must not touch a non-jsn_kr navi"
! c 5 | grep -qE 'push|pm |am start' && grep -q '확인된 내비 빌드가 아닙니다' "$T/out5.txt" && grep -q 'eng.vmadmin.20260114.084130' "$T/out5.txt" || fail "5 must not install on another build"
[ "$(c 6 | grep -c '^push ')" = 2 ] || fail "6 installs both"
! c 7 | grep -qE '^push |pm install' && [ "$(c 7 | grep -c '^pull ')" = 2 ] && [ "$(grep -c '건너뜀' "$T/out7.txt")" = 2 ] || fail "7 skips identical apps"
! c 8 | grep -qE '^push |pm ' || fail "8 diagnostics must not change the car"
D=$(sed -n 's/^DIAG //p' "$T/out8.txt"); [ -f "$D" ] || fail "8 no diagnostics file"
case "$(basename "$D")" in vn_진단기록_[0-9]*_[0-9]*.txt) ;; *) fail "8 diagnostics file name: $D" ;; esac
grep -q 'ro.product.name: jsn_kr' "$D" && grep -q 'persist.sys.key.custom: com.mobis.intent.action.CUSTOM_SHORTCUT' "$D" && grep -q 'versionName=1.9' "$D" && grep -q 'draw avg' "$D" || fail "8 diagnostics content"
grep -q 'VN-ERROR 1 1794880000000 rear_view' "$D" && grep -q 'errors.previous.log: 없음' "$D" && grep -q "읽지 못함 (run-as: Package" "$D" || fail "8 error records"
! grep -qE '00:1A:7D|1234-5678|kim@example|FAKESERIAL42' "$D" && grep -q '<MAC>' "$D" && grep -q '<전화번호>' "$D" && grep -q '<메일>' "$D" && grep -q '<기기번호>' "$D" || fail "8 diagnostics must be masked"
[ "$(grep -c 'draw avg' "$D")" -le 1000 ] || fail "8 log tail is capped"
echo "OK: phone installer flow (10 scenarios)"
