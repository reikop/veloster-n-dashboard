#!/bin/bash
# 벨로스터 N 계기판 설치 — macOS·리눅스(라즈베리파이 포함). 맥은 Finder에서 더블클릭, 리눅스는 터미널에서  bash install.sh
# (리눅스 ZIP의 install.sh는 이 파일과 같다). 맥은  bash 설치.command 로도 된다.
# install.ps1(Windows)과 같은 일을 한다: adb 준비 → 동봉 APK 확인(bundle.txt의 SHA-256) → 차 화면 안내 → 차(product:jsn_kr) 연결 대기 →
# 빌드 확인(API 17이 아니면 멈춤, 검증 빌드가 아니면 경고만) → 두 앱 설치(서명 충돌 시 묻고 재설치) → '내 앱' 실행. 차가 아닌 기기에는 아무것도 하지 않는다.
# i30 PD 포팅(sung9155/i30-pd-dashboard, MIT)의 설치기를 벨로스터 N JSN에 맞게 고친 것이다.
# 기본 macOS bash 3.2 에서 돌아가야 하므로 bash 4 기능(연관 배열 등)은 쓰지 않는다.
#
# 앱 파일: 설치기 ZIP에 든 N-Dashboard-API17.apk · My-Apps-API17.apk 만 설치한다. 옆의 bundle.txt(scripts/apk_info.py가 만듦)에
# 적힌 패키지·SHA-256과 다르면 설치하지 않는다. 인터넷에서 앱을 받지 않으므로 인터넷이 없어도 된다(adb가 없을 때만 받음).
# 테스트 버전은 'test' 사전 배포의 다른 설치기 ZIP이다.
# 테스트 인자: --adb <adb 경로>  --yes(확인 모두 예, 대기/프롬프트 생략)  --uninstall(두 앱 삭제)
#              --diag(설치하지 않고 진단 기록 파일만 만든다)
# 창 설치기(N 계기판 설치.app)용: --gui  묻지 않고(Enter 대기 없음) 차를 스스로 기다리며, 줄 단위로 찍고 아래 상태 줄을 낸다.
#              @@STEP <제목>  @@WAIT <초> <안내>  @@ASK <질문>(표준 입력에서 y/n 한 줄을 읽음)  @@FILE <경로>  @@DONE ok|fail <내용>
# 업데이트: --check-update  최신 릴리스가 이 설치기보다 새것이면 '@@UPDATE <버전>' 한 줄만 찍는다(인터넷이 없으면 아무것도 안 찍음).
#           --update  최신 릴리스의 설치기 ZIP(맥 vn-installer-mac.zip, 리눅스 vn-installer-linux.zip)을 받아 SHA256SUMS.txt로 확인하고
#                     맥은 ~/Library/Application Support/vn-installer/update/<버전>, 리눅스는 ~/vn-installer-<버전>에 푼다.
#                     --gui면 '@@OPEN <새 설치기>'를 찍는다. 시험용: VN_RELEASES(릴리스 주소)

ROOT="$(cd "$(dirname "$0")" && pwd)"; ME="$(basename "$0")"
# OS: Darwin(맥) / Linux. VN_OS로 바꿀 수 있다(시험 도구가 Windows Git Bash에서 리눅스 경로를 돌릴 때).
OS="${VN_OS:-$(uname -s)}"; [ "$OS" = Darwin ] || OS=Linux
INSTALLER_VERSION='1.14.0'   # 릴리스 버전(MAJOR.MINOR.PATCH). 화면 맨 위에 찍혀, 스크린샷만으로 옛 설치기인지 알 수 있다. 고칠 때마다 올린다.
PRODUCT='jsn_kr'                               # 벨로스터 N 국내 사양 내비
VERIFIED_BUILD='eng.vmadmin.20250902.081552'   # 공개 앱 설치·실행을 확인한 빌드(docs/INSTALL.md). 다르면 경고만 하고 설치한다.
# Google platform-tools: 맥, 리눅스 x86_64만 있다. 라즈베리파이 같은 ARM 리눅스는 배포판 adb 패키지(apt)를 쓴다.
PT_URL=''
if [ "$OS" = Darwin ]; then PT_URL='https://dl.google.com/android/repository/platform-tools-latest-darwin.zip'
else case "$(uname -m)" in x86_64|amd64) PT_URL='https://dl.google.com/android/repository/platform-tools-latest-linux.zip' ;; esac; fi
RELEASES="${VN_RELEASES:-https://github.com/reikop/veloster-n-dashboard/releases}"; RELEASES="${RELEASES%/}"   # VN_RELEASES: 시험용 가짜 릴리스 서버
APKS='N-Dashboard-API17.apk My-Apps-API17.apk'   # 동봉 APK 이름(installer/README.md)
BUNDLE="$ROOT/bundle.txt"
ADB=''; YES=''; UNINSTALL=''; DIAG=''; GUI=''; CHECK_UPDATE=''; UPDATE=''
while [ $# -gt 0 ]; do
  case "$1" in
    --adb) ADB="$2"; shift ;;
    --yes) YES=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --diag) DIAG=1 ;;
    --gui) GUI=1 ;;
    --check-update) CHECK_UPDATE=1 ;;
    --update) UPDATE=1 ;;
  esac
  shift
done

pkg_of()  { case "$1" in N-Dashboard-*) echo com.veloster.dashboard ;; *) echo com.veloster.carplayhub ;; esac; }
name_of() { case "$1" in N-Dashboard-*) echo 'N 계기판' ;; *) echo "'내 앱'" ;; esac; }

if [ "$OS" = Darwin ]; then LOGDIR="$HOME/Library/Logs/vn-installer"; else LOGDIR="${XDG_STATE_HOME:-$HOME/.local/state}/vn-installer"; fi
mkdir -p "$LOGDIR"
LOG="$LOGDIR/install_$(date +%Y%m%d_%H%M%S).log"

log()  { printf '%s\n' "$1" >> "$LOG"; }   # 기록 파일에만 (문제 제보용)
# 파일을 파일 관리자에서 보여 준다: 맥 Finder, 리눅스는 화면이 있을 때 그 폴더를 연다(라즈베리파이 SSH 등 화면 없으면 경로만).
reveal() { if [ "$OS" = Darwin ]; then open -R "$1" 2>/dev/null
           elif [ -n "$DISPLAY$WAYLAND_DISPLAY" ] && command -v xdg-open >/dev/null; then xdg-open "$(dirname "$1")" >/dev/null 2>&1 & fi; }
os_name() { if [ "$OS" = Darwin ]; then echo "macOS $(sw_vers -productVersion 2>/dev/null)"
            else (. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Linux}"); fi; }
note() { printf '%s\n' "$1"; log "$1"; }
head_() { local t; t="$(date +%H:%M:%S)"; [ -n "$GUI" ] && printf '@@STEP %s\n' "$1"; printf '\n\033[36m%s   [%s]\033[0m\n' "$1" "$t"; log "== $1 == $t"; }
ok()   { printf '\033[32m%s\033[0m\n' "$1"; printf '%s\n' "$1" >> "$LOG"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; printf '%s\n' "$1" >> "$LOG"; }
finish() { if [ -n "$GUI" ]; then [ "$1" = 0 ] && echo '@@DONE ok'; exit "$1"; fi
           [ -z "$YES" ] && { echo; read -r -p '창을 닫으려면 Enter' _; }; exit "$1"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$1"; log "ERROR: $1"
         warn "기록 파일: $LOG"
         warn '문제를 알릴 때 이 기록 파일을 함께 보내 주세요.'
         if [ -n "$GUI" ]; then printf '@@FILE %s\n@@DONE fail %s\n' "$LOG" "$(printf '%s\n' "$1" | head -1)"; exit "$2"; fi
         [ -z "$YES" ] && reveal "$LOG"
         finish "$2"; }
# --gui: 창 설치기가 예/아니오 창을 띄우고 답 한 줄(y/n)을 표준 입력으로 보낸다. 답이 없으면(창이 닫힘) 아니오.
ask()  { if [ -n "$GUI" ]; then printf '@@ASK %s\n' "$1"; r=''; read -r r; log "$1 → $r"; case "$r" in [Yy]*) return 0 ;; *) return 1 ;; esac; fi
         [ -n "$YES" ] && return 0; read -r -p "$1 [Y/n] " r; case "$r" in ''|[Yy]*) return 0 ;; *) return 1 ;; esac; }
usb_off() { note 'USB 디버깅 끄기: 시동을 껐다 켜면 저절로 꺼집니다. 바로 끄려면 Factory Test에서  0A 01 → 65 00 → 0A 02'
            note "  (또는 '내 앱' → USB 디버깅 → OFF). 끄면 노트북 연결이 끊깁니다."; }
# 진단 기록에서 사람을 가릴 수 있는 것: MAC 주소, 휴대폰 번호, 메일 주소, adb 기기 번호, 경로 속 사용자 이름($HOME). perl은 macOS·데비안 계열 기본, 바이트 단위(ASCII 패턴만이라 충분).
mask() { S="$SERIAL" perl -pe 's/\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b/<MAC>/g; s/\b01[016789][-. ]?\d{3,4}[-. ]?\d{4}\b/<전화번호>/g;
  s/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/<메일>/g; s/\Q$ENV{S}\E/<기기번호>/g if length $ENV{S}; s/\Q$ENV{HOME}\E/~/g if length $ENV{HOME}' ; }
adb_() { local o; o="$("$ADB" "$@" 2>&1)"; printf '> adb %s\n%s\n' "$*" "$o" >> "$LOG"; printf '%s' "$o"; }
# 오래 걸릴 수 있는 adb(전송·설치)는 제한 시간 안에서 돌리고 기다리는 동안 경과 초를 같은 줄에 찍는다:
# 멈춘 화면의 스크린샷만 봐도 어느 단계에서 몇 초째 멈췄는지 보이게. 결과는 $OUT, 시간 초과면 return 1.
adb_wait() {
  local sec="$1" what="$2" f="$LOGDIR/adb_out.txt" t=0 pid; shift 2
  log "> adb $*  (최대 ${sec}초)"
  [ -n "$GUI" ] && printf '@@WAIT 0 %s\n' "$what"
  "$ADB" "$@" > "$f" 2>&1 & pid=$!
  printf '  %s ' "$what"
  while kill -0 $pid 2>/dev/null; do
    sleep 1; t=$((t + 1)); [ $((t % 5)) -eq 0 ] && printf '\033[90m%ss\033[0m ' $t
    if [ $t -ge $sec ]; then
      kill $pid 2>/dev/null; printf '\033[31m멈춤(%s초)\033[0m\n' $sec; log "  → ${sec}초 넘게 응답 없음, 중단"; OUT=''; return 1
    fi
  done
  wait $pid; OUT="$(cat "$f")"; printf '\033[90m(%s초)\033[0m\n' $t; log "$OUT  (${t}초)"; return 0
}
# 전송·설치가 멈췄을 때: 멈춘 곳과 할 일, 내비의 설치 로그 끝부분을 화면에 띄우고 끝낸다.
stuck() {
  printf '\033[31m멈춘 곳: %s\033[0m\n' "$1"; log "멈춘 곳: $1"; warn "  $2"
  if adb_wait 15 '내비 설치 로그 읽는 중' -s "$SERIAL" logcat -d -t 300 -s 'PackageManager:*' 'PackageParser:*' 'installd:*' 'DefContainerService:*' 'dalvikvm:E' && [ -n "$OUT" ]; then
    note '  설치 로그(마지막 줄):'; printf '%s\n' "$OUT" | tr -d '\r' | grep . | tail -12 | while IFS= read -r l; do note "    $l"; done
  fi
  die "$1 단계에서 멈췄습니다. 이 화면을 찍어 보내 주시면 원인을 볼 수 있습니다." 1
}
PM_HINT='내비가 설치를 끝내지 않았습니다. 차 화면에 확인 창이 떠 있으면 누르고, 없으면 시동을 껐다 켠 뒤 다시 실행하세요.'

# --- 업데이트: 최신 정식 릴리스(releases/latest가 가리키는 태그 vX.Y.Z)와 비교하고, 새 설치기를 받아 확인 후 푼다 -------------
sha256_() { if command -v shasum >/dev/null; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi; }
# 숫자로 비교한다(1.11.0 > 1.9.0). MAJOR.MINOR.PATCH가 아닌 값은 -1.
vnum() { case "$1" in *[!0-9.]*|'') echo -1 ;; *) echo "$1" | awk -F. 'NF==3{printf "%d\n", $1*1000000+$2*1000+$3; next}{print -1}' ;; esac; }
latest_version() {
  command -v curl >/dev/null || return 0
  local u; u="$(curl -fsS --max-time 8 -o /dev/null -w '%{redirect_url}' "$RELEASES/latest" 2>>"$LOG")"
  case "$u" in */tag/v[0-9]*) u="${u##*/tag/v}"; [ "$(vnum "$u")" -ge 0 ] && echo "$u" ;; *) log "최신 릴리스 주소를 읽지 못함: $u" ;; esac
}
newer() { [ "$(vnum "$1")" -gt "$(vnum "$INSTALLER_VERSION")" ]; }
do_update() {
  local v="$1" asset dir z s want got target
  head_ "새 설치기 $v 받기"
  if [ "$OS" = Darwin ]; then asset=vn-installer-mac.zip; dir="$HOME/Library/Application Support/vn-installer/update/$v"
  else asset=vn-installer-linux.zip; dir="$HOME/vn-installer-$v"; fi
  z="$LOGDIR/update-$v.zip"; s="$LOGDIR/SHA256SUMS-$v.txt"
  note "받는 중: $RELEASES/download/v$v/$asset"
  curl -fsSL --max-time 300 -o "$z" "$RELEASES/download/v$v/$asset" 2>>"$LOG" && curl -fsSL --max-time 30 -o "$s" "$RELEASES/download/v$v/SHA256SUMS.txt" 2>>"$LOG" \
    || die '새 설치기를 받지 못했습니다. 인터넷 연결을 확인하세요.' 2
  want="$(awk -v f="$asset" '$2==f{print $1; exit}' "$s")"; got="$(sha256_ "$z")"
  if [ -z "$want" ] || [ "$got" != "$want" ]; then rm -f "$z"; die "받은 파일의 SHA-256이 릴리스 목록과 다릅니다(받은 것 $got). 설치기를 바꾸지 않았습니다." 2; fi
  note "SHA-256 확인: $got"
  rm -rf "$dir"; mkdir -p "$dir"
  if [ "$OS" = Darwin ]; then ditto -x -k "$z" "$dir" 2>>"$LOG"
  elif command -v unzip >/dev/null; then unzip -q -o "$z" -d "$dir" 2>>"$LOG"
  else python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$z" "$dir" 2>>"$LOG"; fi || die "압축 해제 실패: $z" 2
  if [ "$OS" = Darwin ]; then target="$dir/N 계기판 설치.app"; else target="$dir/install.sh"; chmod +x "$dir"/*.sh 2>/dev/null; fi
  [ -e "$target" ] || die "받은 설치기에서 $(basename "$target")을 찾지 못했습니다: $dir" 2
  ok "새 설치기: $target"
  note '예전 설치기는 지워도 됩니다.'
  if [ -n "$GUI" ]; then printf '@@OPEN %s\n' "$target"; finish 0; fi
  if [ "$OS" = Darwin ]; then open "$target"; else note "새 설치기 실행:  bash \"$target\""; fi
  exit 0
}
if [ -n "$CHECK_UPDATE" ]; then v="$(latest_version)"; [ -n "$v" ] && newer "$v" && printf '@@UPDATE %s\n' "$v"; exit 0; fi
if [ -n "$UPDATE" ]; then
  note "설치기 $INSTALLER_VERSION — 업데이트 확인"
  v="$(latest_version)"
  [ -n "$v" ] || die "최신 버전을 확인하지 못했습니다. 인터넷 연결을 확인하세요($RELEASES/latest)." 2
  if ! newer "$v"; then ok "이미 최신 설치기입니다($INSTALLER_VERSION)."; finish 0; fi
  do_update "$v"
fi

echo
if [ -n "$UNINSTALL" ]; then echo "  벨로스터 N 계기판 삭제   (설치기 $INSTALLER_VERSION)"
elif [ -n "$DIAG" ]; then echo "  벨로스터 N 계기판 진단 기록   (설치기 $INSTALLER_VERSION)"
else echo "  벨로스터 N 계기판 설치   (설치기 $INSTALLER_VERSION)"; fi
echo '  ----------------------'
warn '현대자동차 공식 앱이 아닙니다. 순정 시스템에 앱을 설치하는 작업이라 보증/AS 불이익이 있을 수 있고,'
warn '책임은 본인에게 있습니다. 모든 작업은 정차 · 시동 켠 상태에서 하세요.'
note "기록: $LOG"

# --- adb 찾기/받기 ------------------------------------------------------------
head_ '1) adb 준비'
if [ -z "$ADB" ]; then
  for c in "$ROOT/platform-tools/adb" "$ROOT/adb"; do [ -x "$c" ] && { ADB="$c"; break; }; done
fi
[ -z "$ADB" ] && ADB="$(command -v adb)"
# curl은 adb(platform-tools)를 받을 때만 필요하다(앱 APK는 설치기에 들어 있다).
[ -z "$ADB" ] && [ -n "$PT_URL" ] && ! command -v curl >/dev/null && die 'curl이 없습니다. 리눅스는  sudo apt install curl  로 설치한 뒤 다시 실행하세요.' 2
SLOW='--connect-timeout 15 --speed-limit 1000 --speed-time 30'   # 내려받기: 연결 15초, 30초 동안 1 KB/s도 안 오면 그만둔다(끊긴 인터넷에서 멈춘 듯 보이지 않게)
if [ -z "$ADB" ] && [ -n "$PT_URL" ]; then
  note 'adb(platform-tools)가 없습니다.'
  ask 'Google 공식 주소에서 받을까요?' || die 'adb가 없어 중단합니다.' 2
  note '내려받는 중...'
  curl -fsSL $SLOW -o "$LOGDIR/platform-tools.zip" "$PT_URL" || die "다운로드 실패: $PT_URL
인터넷 연결을 확인한 뒤 다시 실행하세요." 2
  # unzip이 없는 리눅스도 있다: python3의 zipfile로 대신 푼다(그때는 실행 권한을 붙인다).
  if command -v unzip >/dev/null; then unzip -qo "$LOGDIR/platform-tools.zip" -d "$ROOT" || die '압축 해제 실패' 2
  else python3 -I -m zipfile -e "$LOGDIR/platform-tools.zip" "$ROOT" || die '압축 해제 실패 (unzip 또는 python3 필요)' 2
       chmod +x "$ROOT/platform-tools/adb" 2>/dev/null; fi
  ADB="$ROOT/platform-tools/adb"
  [ -x "$ADB" ] || die 'adb를 준비하지 못했습니다.' 2
elif [ -z "$ADB" ]; then
  # ARM 리눅스(라즈베리파이 등): Google이 리눅스 ARM용 adb를 내지 않는다. 배포판 패키지는 USB 권한 규칙(udev)도 함께 깐다.
  note "adb가 없습니다. 이 기기($(uname -m))용 adb는 배포판 패키지로 설치합니다."
  command -v apt-get >/dev/null || die 'adb를 직접 설치한 뒤 다시 실행하세요(데비안·우분투·라즈베리파이 OS:  sudo apt install adb ).' 2
  ask 'sudo apt-get install adb 로 설치할까요? (관리자 암호를 물을 수 있습니다)' || die 'adb가 없어 중단합니다.  sudo apt install adb  로 설치한 뒤 다시 실행하세요.' 2
  sudo apt-get install -y adb >>"$LOG" 2>&1 || sudo apt-get install -y android-tools-adb >>"$LOG" 2>&1 || die 'adb 설치 실패. 기록 파일을 확인하세요.' 2
  ADB="$(command -v adb)"; [ -n "$ADB" ] || die 'adb를 준비하지 못했습니다.' 2
fi
note "adb: $ADB"
log "환경: $(os_name) / $(uname -m) / $("$ADB" version 2>&1 | head -1)"

# --- 새 설치기 확인 (막지는 않는다, 인터넷이 없으면 조용히 넘어간다) -----------------
# 최신 정식 릴리스가 더 새것이면 알리고, 터미널에서는 지금 받을지 묻는다(창 설치기는 [업데이트] 단추가 따로 있다).
v="$(latest_version)"
if [ -n "$v" ] && newer "$v"; then
  warn "새 설치기 $v 가 있습니다(지금 $INSTALLER_VERSION)."
  if [ -z "$GUI" ] && [ -z "$YES" ] && ask "새 설치기 $v 를 받아 쓸까요? (아니오: 이번에는 그대로 계속)"; then do_update "$v"; fi
fi

# --- 동봉 APK 확인: bundle.txt에 적힌 패키지·SHA-256과 같은 파일만 설치한다 ------------
field_() { awk -F'\t' -v f="$1" -v n="$2" '$1==f {print $n; exit}' "$BUNDLE" 2>/dev/null; }   # bundle.txt: 파일 패키지 versionCode versionName sha256
if [ -z "$UNINSTALL$DIAG" ]; then
  head_ '0) 앱 파일 확인'
  [ -f "$BUNDLE" ] || die "이 설치기에 든 앱 목록(bundle.txt)이 없습니다. 릴리스에서 설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요." 2
  for apk in $APKS; do
    [ "$(field_ $apk 2)" = "$(pkg_of $apk)" ] || die "bundle.txt에 $apk 이(가) 없거나 패키지가 다릅니다. 설치기 ZIP을 다시 받으세요." 2
    [ -f "$ROOT/$apk" ] || die "$apk 이(가) 설치기 폴더에 없습니다. 설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요." 2
    got="$(sha256_ "$ROOT/$apk")"; log "$apk sha256 $got"
    [ "$got" = "$(field_ $apk 5)" ] || die "$apk 이(가) 이 설치기에 든 파일과 다릅니다(SHA-256 불일치). 설치하지 않습니다.
설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요." 2
  done
  BUNDLED="N 계기판 $(field_ N-Dashboard-API17.apk 4) · '내 앱' $(field_ My-Apps-API17.apk 4)"
  ok "이 설치기에 든 버전: $BUNDLED"
fi

# --- 차 화면 안내 + 연결 대기 -------------------------------------------------
head_ '2) 차 화면에서 먼저 (사람이 직접)'
note '  (1) 설정 → SW 정보/업데이트 화면 (자세히: docs/ADB.md)'
note '  (2) 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5회 → 오른쪽 숨은 영역 1회 (10초 안에, 엔지니어링 모드)'
note '  (3) 암호(2026년 0567) → Diagnosis → Factory Test 에서  0A 01 → 65 01 → 0A 02  를 한 줄씩 SEND'
note "      ('내 앱'이 이미 깔려 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)"
if [ "$OS" = Darwin ]; then note '  (4) USB 케이블 연결 (USB-C 맥이면 USB-A 어댑터 필요)'
else note '  (4) USB 케이블 연결 (데이터가 되는 케이블. 라즈베리파이는 USB-A 포트에)'; fi
[ -z "$YES$GUI" ] && read -r -p '위 단계가 끝나고 USB를 연결했으면 Enter ' _

head_ '3) 차 연결 확인'
"$ADB" start-server >/dev/null 2>&1
SERIAL=''; tries=0
# 기다리는 동안 같은 안내를 5초마다 다시 찍지 않는다: 바뀔 때만 새 줄, 같으면 그 줄 끝의 경과 초만 고친다.
WAIT_MSG=''; WAIT_T0=$(date +%s)
wait_() { if [ -n "$GUI" ]; then [ "$1" != "$WAIT_MSG" ] && { log "$1"; WAIT_MSG="$1"; }; printf '@@WAIT %s %s\n' $(( $(date +%s) - WAIT_T0 )) "$1"; return; fi
          if [ "$1" != "$WAIT_MSG" ]; then [ -n "$WAIT_MSG" ] && echo; log "$1"; WAIT_MSG="$1"; fi
          printf '\r\033[%sm%s  %s초 \033[0m' "${2:-0}" "$1" $(( $(date +%s) - WAIT_T0 )); }
wait_end() { [ -n "$WAIT_MSG" ] && [ -z "$GUI" ] && echo; WAIT_MSG=''; }
udev_fix() {
  warn '이 PC가 USB 장치(차)를 읽을 권한이 없습니다(리눅스 udev 규칙 없음).'
  note '  /etc/udev/rules.d/51-vndashboard.rules 에 차(VID 18d1, PID d00d) 한 줄만 넣으면 됩니다. 다른 기기는 건드리지 않습니다.'
  if ask '규칙을 넣을까요? (sudo 관리자 암호를 물을 수 있습니다)'; then
    if printf '%s\n' 'SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", ATTR{idProduct}=="d00d", MODE="0666"' | sudo tee /etc/udev/rules.d/51-vndashboard.rules >>"$LOG" 2>&1 \
       && sudo udevadm control --reload-rules >>"$LOG" 2>&1 && sudo udevadm trigger >>"$LOG" 2>&1; then
      "$ADB" kill-server >/dev/null 2>&1; ok '규칙을 넣었습니다. USB 케이블을 뺐다가 다시 꽂으세요.'
    else warn '규칙을 넣지 못했습니다.  sudo adb kill-server; sudo adb devices  로 차가 보이는지 확인하세요.'; fi
  else note '  직접 하려면:  sudo adb kill-server; sudo adb devices  (또는 위 규칙 파일을 넣고 다시 꽂기)'; fi
}
while [ -z "$SERIAL" ]; do
  dl="$("$ADB" devices -l 2>&1)"; printf '%s\n' "$dl" >> "$LOG"
  SERIAL="$(printf '%s\n' "$dl" | awk -v p="product:$PRODUCT " '$2=="device" && index($0" ",p) {print $1; exit}')"   # exactly jsn_kr, not jsn_kr2
  [ -n "$SERIAL" ] && break
  [ -n "$YES" ] && die "차($PRODUCT)를 찾지 못했습니다." 1
  if printf '%s' "$dl" | grep -q unauthorized; then wait_ '→ 차 화면에 "USB 디버깅 허용" 창이 뜨면 [항상 허용] 하세요.' 33
  elif printf '%s\n' "$dl" | awk '$2=="device"' | grep -q .; then wait_ '→ 차가 아닌 기기만 보입니다(휴대폰 등). 그 기기는 건드리지 않습니다.' 33
  elif printf '%s' "$dl" | grep -q 'no permissions'; then
    # 리눅스: USB 장치를 읽을 권한이 없다(udev 규칙 없음). 처음 한 번 규칙을 넣을지 묻는다.
    if [ -z "$UDEV_ASKED" ] && [ "$OS" = Linux ]; then UDEV_ASKED=1; wait_end; udev_fix; fi
    wait_ '→ USB 권한이 없어 차를 읽지 못합니다. 케이블을 뺐다가 다시 꽂으세요.' 33
  else wait_ '→ 차를 기다리는 중... USB 디버깅/Factory Test(0A 01/65 01/0A 02) 다시 확인.'; fi
  tries=$((tries + 1))
  # 5초 간격으로 1분. 차 화면에서 허용을 눌러야 하는 상태면 3분까지 기다린다.
  limit=12; printf '%s' "$dl" | grep -q unauthorized && limit=36
  [ -n "$GUI" ] && limit=60   # 창 설치기: Enter로 준비를 알리지 않으므로 차 화면 단계를 할 시간까지 5분 기다린다
  if [ $tries -ge $limit ]; then
    wait_end
    # 못 찾음: 이 PC가 본 USB 장치 목록을 기록에 남긴다(차가 USB에 아예 안 나타났는지 가르는 근거).
    if [ "$OS" = Darwin ]; then log "== USB 장치 목록 (system_profiler) =="; system_profiler SPUSBDataType 2>/dev/null >> "$LOG"
    else log "== USB 장치 목록 (lsusb) =="; lsusb 2>/dev/null >> "$LOG"; fi
    if printf '%s' "$dl" | grep -q unauthorized; then warn '마지막 상태: 차 화면에서 USB 디버깅 허용을 누르지 않음'
    elif printf '%s\n' "$dl" | awk '$2=="device"' | grep -q .; then warn "마지막 상태: $PRODUCT 이 아닌 기기만 보임"
    elif printf '%s' "$dl" | grep -q 'no permissions'; then warn '마지막 상태: USB 권한 없음 — 위 udev 규칙을 넣고 케이블을 다시 꽂거나, sudo 로 실행'
    else warn '마지막 상태: 이 PC에 차가 adb 기기로 나타나지 않음 — Factory Test(0A 01/65 01/0A 02)·케이블·데이터 포트 확인'
         warn '가장 흔한 원인은 Factory Test 입력 실수입니다: 케이블을 빼고 0A 01 / 65 01 / 0A 02 를 띄어쓰기까지 정확히, 한 줄씩 SEND 한 뒤 다시 꽂으세요.'
         warn '케이블을 바꿔서 된 사례가 있습니다(데이터가 되는 케이블인지 확인).'; fi
    die "시간 초과($((limit * 5))초). 차를 찾지 못했습니다. 위 상태를 고친 뒤 $ME 를 다시 실행하세요." 1
  fi
  sleep 5
done
wait_end
ok "차 연결됨: $SERIAL"

# --- 내비 정보 · 빌드 확인 -------------------------------------------------------
# 정해 둔 항목만 읽는다: getprop 전체에는 시리얼·블루투스 주소 등 개인 정보가 있어, 공개 Issues에 올라갈 기록 파일에 넣지 않는다.
prop() { adb_ -s "$SERIAL" shell getprop "$1" | tr -d '\r\n'; }
for k in ro.product.name ro.build.product ro.build.fingerprint sys.usb.config persist.sys.usb.config persist.sys.dipo.state; do prop $k >/dev/null; done
device="$(prop ro.product.device)"; build="$(prop ro.build.version.incremental)"; usbst="$(prop sys.usb.state)"; sdk="$(prop ro.build.version.sdk)"
note "내비: $PRODUCT ($device) / API $sdk / 빌드 $build / USB $usbst"
# 차에 깔린 두 앱의 버전 한 줄. 1.5-i18n = 릴리스 이름을 넣기 전(v1.16.0까지)의 대시보드.
versions() {
  local apk n o=''
  for apk in $APKS; do
    n="$(adb_ -s "$SERIAL" shell dumpsys package "$(pkg_of $apk)" | tr -d '\r' | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)"
    o="$o${o:+ · }$(name_of $apk) ${n:-없음}"
  done
  printf '%s' "$o"
}
cur="$(versions)"
note "차에 깔린 버전: $cur"
[ -n "${BUNDLED:-}" ] && note "설치할 버전: $BUNDLED"

# --- 진단 기록 모드: 읽기만 한다 -------------------------------------------------
# 정해 둔 속성, 두 앱의 버전, 우리 앱 태그·충돌·설치 기록만. 차 기록 전체에는 블루투스로 붙은 폰 이름·번호·연락처가 섞인다.
if [ -n "$DIAG" ]; then
  head_ '진단 기록 모으기 (차에는 아무것도 바꾸지 않습니다)'
  TAGS='VehicleDash:V VNCarPlayHub:V VNQuickEntry:V NDashboard:V AndroidRuntime:E PackageManager:I'
  desk="$HOME/Desktop"; [ "$OS" = Linux ] && desk="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
  [ -d "$desk" ] || desk="$HOME"; [ -d "$desk" ] || desk="$LOGDIR"
  out="$desk/vn_진단기록_$(date +%Y%m%d_%H%M%S).txt"
  {
    echo "벨로스터 N 계기판 진단 기록 — $(date '+%Y-%m-%d %H:%M') / PC 설치기 $INSTALLER_VERSION ($(os_name))"
    echo; echo '[내비]'
    for k in ro.product.name ro.product.device ro.build.product ro.build.version.incremental ro.build.fingerprint \
             sys.usb.config sys.usb.state persist.sys.usb.config persist.sys.dipo.state; do echo "$k: $(prop $k)"; done
    echo "시각: $(adb_ -s "$SERIAL" shell date | tr -d '\r')"
    echo; echo '[앱]'
    for apk in $APKS; do
      echo "$(name_of $apk) ($(pkg_of $apk))"
      v="$(adb_ -s "$SERIAL" shell dumpsys package "$(pkg_of $apk)" | tr -d '\r' | grep -E '(versionCode|versionName|firstInstallTime|lastUpdateTime|codePath)=' | sed 's/^ */  /' | awk '!seen[$0]++')"
      [ -n "$v" ] && printf '%s\n' "$v" || echo '  설치돼 있지 않음'
    done
    echo; echo "[기록: $TAGS]"
    "$ADB" -s "$SERIAL" shell logcat -d -v time -s $TAGS 2>&1 | tr -d '\r' | tail -3000   # 설치기 기록에는 안 남긴다(아래에 그 기록을 붙임)
    echo; echo '[이 PC 설치기 기록]'; cat "$LOG"
  } | mask > "$out"
  ok "만든 파일: $out"
  note '문제를 알릴 때 이 파일을 보내 주세요. 증상(언제, 어떤 화면에서, 무엇이)도 함께 알려 주세요.'
  if [ -n "$GUI" ]; then printf '@@FILE %s\n' "$out"; elif [ -z "$YES" ]; then reveal "$out"; fi
  usb_off; finish 0
fi
# jsn_kr · API 17이 아니면 설치하지 않는다. 빌드 번호만 다르면 경고하고 설치한다(앱은 API 17 공개 API만 쓴다). 삭제는 막지 않는다.
if [ -z "$UNINSTALL" ] && [ "$sdk" != 17 ]; then
  die "지원하지 않는 내비입니다(API $sdk / $build). 이 설치기는 API 17 내비에만 설치합니다." 1
fi
if [ -z "$UNINSTALL" ] && [ "$build" != "$VERIFIED_BUILD" ]; then
  warn "주의: 검증한 빌드($VERIFIED_BUILD)와 다른 빌드입니다($build). 호환성은 확인되지 않았지만 설치를 계속합니다."
fi

# --- 삭제 모드 --------------------------------------------------------------
if [ -n "$UNINSTALL" ]; then
  head_ '앱 삭제'
  for apk in $APKS; do
    adb_wait 60 "$(name_of $apk) 삭제" -s "$SERIAL" uninstall "$(pkg_of $apk)" || stuck "$(name_of $apk) 삭제(내비)" "$PM_HINT"
    if printf '%s' "$OUT" | grep -q Success; then ok "$(name_of $apk): 삭제됨"; continue; fi
    # Not Success: never installed, or the uninstall failed. pm path tells the two apart.
    u="$OUT"
    # pm path prints nothing for an app that is not there. Anything else (an adb error, no answer) is a lost connection, not a removed app.
    if ! adb_wait 30 "$(name_of $apk) 확인" -s "$SERIAL" shell pm path "$(pkg_of $apk)" || { ! printf '%s' "$OUT" | grep -q 'package:' && [ -n "$(printf '%s' "$OUT" | tr -d '[:space:]')" ]; }; then
      die "차와 연결이 끊긴 것 같습니다. $(name_of $apk) 삭제를 확인하지 못했습니다.
케이블을 확인하고 다시 실행하세요.
$OUT" 1
    fi
    if printf '%s' "$OUT" | grep -q 'package:'; then
      die "$(name_of $apk) 삭제 실패 (앱이 아직 차에 있습니다):
$u" 1
    fi
    note "$(name_of $apk): 설치돼 있지 않음"
  done
  ok '삭제 완료. 순정 상태로 돌아갔습니다(순정 시스템은 원래 건드리지 않습니다).'; usb_off; finish 0
fi

# --- 설치 -------------------------------------------------------------------
head_ '4) 앱 설치'
# adb install 대신 ① 전송(push) ② 설치(pm install) 두 단계로 나눠, 멈추면 USB 쪽인지 내비 쪽인지 바로 갈린다.
PUSH_HINT='USB로 파일을 보내다 멈췄습니다. 케이블·포트를 바꾸고 Factory Test부터 다시 한 뒤 실행하세요.'
for apk in $APKS; do
  p="$ROOT/$apk"; name="$(name_of $apk)"; remote="/data/local/tmp/$apk"
  # 내비는 설치한 APK를 바이트 그대로 보관한다: 받아 와서 같으면 같은 버전이니 건너뛴다.
  have="$LOGDIR/installed_$apk"; rm -f "$have"
  pp="$(adb_ -s "$SERIAL" shell pm path "$(pkg_of $apk)" | tr -d '\r\n ')"; pp="${pp#package:}"
  if [ "${pp%.apk}" != "$pp" ] && adb_wait 60 "$name 설치된 파일 비교" -s "$SERIAL" pull "$pp" "$have" && cmp -s "$p" "$have"; then
    ok "$name: 이미 같은 버전이 설치돼 있습니다 — 건너뜀"; continue
  fi
  printf '%s (%s KB)\n' "$name" $(( $(wc -c < "$p") / 1024 )); log "$name"
  adb_wait 60 '① 전송 (USB)' -s "$SERIAL" push "$p" "$remote" || stuck "$name ① 전송" "$PUSH_HINT"
  printf '%s' "$OUT" | grep -qiE 'error|failed|No such' && die "$name 전송 실패:
$OUT" 1
  adb_wait 120 '② 설치 (내비)' -s "$SERIAL" shell pm install -r "$remote" || stuck "$name ② 설치" "$PM_HINT"
  # Only a signature clash is fixed by uninstalling; any other failure (storage full, ...) must not remove the working app.
  if printf '%s' "$OUT" | grep -qE 'INCONSISTENT_CERTIFICATES|UPDATE_INCOMPATIBLE'; then
    warn "$name: 기존에 다른 서명으로 설치돼 있습니다."
    if ask '지우고 다시 설치할까요? (앱 설정도 지워집니다)'; then
      adb_wait 60 '기존 앱 삭제' -s "$SERIAL" uninstall "$(pkg_of $apk)" || stuck "$name 기존 앱 삭제" "$PM_HINT"
      printf '%s' "$OUT" | grep -q Success || die "$name 기존 앱 삭제 실패:
$OUT" 1
      adb_wait 120 '② 설치 (내비)' -s "$SERIAL" shell pm install -r "$remote" || stuck "$name ② 설치" "$PM_HINT"
    fi
  fi
  res="$OUT"; adb_wait 15 '정리' -s "$SERIAL" shell rm -f "$remote" >/dev/null
  printf '%s' "$res" | grep -q Success && ok "$name 설치 완료" || die "$name 설치 실패:
$res" 1
done
ok "지금 차에 깔린 버전: $(versions)"

# --- '내 앱' 실행 -----------------------------------------------------------
head_ "5) '내 앱' 실행"
adb_ -s "$SERIAL" shell am start -n com.veloster.carplayhub/.HubActivity >/dev/null
echo
ok "완료! 차 화면 '내 앱'에서 실행 바로가기를 켜면(docs/QUICK_ENTRY_ko.md)"
ok "노트북 없이 HOME 가장자리 버튼으로 다시 열 수 있습니다."
usb_off
finish 0
