#!/bin/bash
# 벨로스터 N 계기판 설치 — macOS·리눅스(라즈베리파이 포함). 맥은 Finder에서 더블클릭, 리눅스는 터미널에서  bash install.sh
# (리눅스 ZIP의 install.sh는 이 파일과 같다). 맥은  bash 설치.command 로도 된다.
# install.ps1(Windows)과 같은 일을 한다: adb 준비 → APK 준비 → 차 화면 안내 → 차(product:jsn_kr) 연결 대기 →
# 빌드 확인(검증 빌드가 아니면 멈춤) → 두 앱 설치(서명 충돌 시 묻고 재설치) → '내 앱' 실행. 차가 아닌 기기에는 아무것도 하지 않는다.
# i30 PD 포팅(sung9155/i30-pd-dashboard, MIT)의 설치기를 벨로스터 N JSN에 맞게 고친 것이다.
# 기본 macOS bash 3.2 에서 돌아가야 하므로 bash 4 기능(연관 배열 등)은 쓰지 않는다.
#
# 버전: 시작할 때 안정화(최신 정식 릴리스, 실차 확인) / 테스트('test' 사전 배포, 실차 검증 전) / 진단 기록 모으기 중 고른다.
# 테스트 인자: --adb <adb 경로>  --apk-base <URL>  --channel stable|test  --yes(확인 모두 예, 대기/프롬프트 생략, 안정화)  --uninstall(두 앱 삭제)
#              --diag(설치하지 않고 진단 기록 파일만 만든다)

ROOT="$(cd "$(dirname "$0")" && pwd)"; ME="$(basename "$0")"
# OS: Darwin(맥) / Linux. VN_OS로 바꿀 수 있다(시험 도구가 Windows Git Bash에서 리눅스 경로를 돌릴 때).
OS="${VN_OS:-$(uname -s)}"; [ "$OS" = Darwin ] || OS=Linux
INSTALLER_VERSION='2026-11-24'   # 화면 맨 위에 찍혀, 스크린샷만으로 옛 설치기인지 알 수 있다. 고칠 때마다 올린다.
PRODUCT='jsn_kr'                               # 벨로스터 N 국내 사양 내비
VERIFIED_BUILD='eng.vmadmin.20250902.081552'   # 공개 앱 설치·실행을 확인한 유일한 빌드(docs/INSTALL.md). 다르면 설치하지 않는다.
# Google platform-tools: 맥, 리눅스 x86_64만 있다. 라즈베리파이 같은 ARM 리눅스는 배포판 adb 패키지(apt)를 쓴다.
PT_URL=''
if [ "$OS" = Darwin ]; then PT_URL='https://dl.google.com/android/repository/platform-tools-latest-darwin.zip'
else case "$(uname -m)" in x86_64|amd64) PT_URL='https://dl.google.com/android/repository/platform-tools-latest-linux.zip' ;; esac; fi
# 안정화 = 최신 정식 릴리스, 테스트 = 'test' 사전 배포(덮어쓰며 갱신). --apk-base 를 주면 그 주소를 그대로 쓴다.
RELEASES='https://github.com/reikop/veloster-n-dashboard/releases'
RAW='https://raw.githubusercontent.com/reikop/veloster-n-dashboard'   # 새 설치기 확인용(main = 안정화, beta = 테스트)
APK_BASE=''
APKS='N-Dashboard-API17.apk My-Apps-API17.apk'   # 릴리스 에셋 이름(installer/README.md)
ADB=''; YES=''; UNINSTALL=''; CHANNEL=''; DIAG=''
while [ $# -gt 0 ]; do
  case "$1" in
    --adb) ADB="$2"; shift ;;
    --apk-base) APK_BASE="$2"; shift ;;
    --channel) CHANNEL="$2"; shift ;;
    --yes) YES=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --diag) DIAG=1 ;;
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
head_() { local t; t="$(date +%H:%M:%S)"; printf '\n\033[36m%s   [%s]\033[0m\n' "$1" "$t"; log "== $1 == $t"; }
ok()   { printf '\033[32m%s\033[0m\n' "$1"; printf '%s\n' "$1" >> "$LOG"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; printf '%s\n' "$1" >> "$LOG"; }
finish() { [ -z "$YES" ] && { echo; read -r -p '창을 닫으려면 Enter' _; }; exit "$1"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$1"; log "ERROR: $1"
         warn "기록 파일: $LOG"
         warn '문제를 알릴 때 이 기록 파일을 함께 보내 주세요.'
         [ -z "$YES" ] && reveal "$LOG"
         finish "$2"; }
ask()  { [ -n "$YES" ] && return 0; read -r -p "$1 [Y/n] " r; case "$r" in ''|[Yy]*) return 0 ;; *) return 1 ;; esac; }
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
command -v curl >/dev/null || die 'curl이 없습니다. 리눅스는  sudo apt install curl  로 설치한 뒤 다시 실행하세요.' 2
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

# --- 릴리스 정보: 고를 버전의 번호·날짜와 노트 요약 (install.ps1 NotesSummary, 휴대폰 앱 Flow.summary와 같은 규칙) ---
API='https://api.github.com/repos/reikop/veloster-n-dashboard/releases'
# 첫 줄 = 화면용 제목, 나머지 = 노트 요약. 받지 못하면 아무것도 내지 않는다. JSON은 macOS 기본 JavaScript(osascript)로 읽는다.
rel_info() {   # $1 = latest | tags/test, $2 = 1 이면 테스트
  local f="$LOGDIR/release_$2.json"
  curl -fsS --max-time 8 -H 'User-Agent: vn-installer' -o "$f" "$API/$1" 2>>"$LOG" || { log "릴리스 정보 실패($1)"; return 0; }
  [ "$OS" = Linux ] && { rel_info_py "$f" "$2"; return 0; }
  osascript -l JavaScript - "$f" "$2" 2>>"$LOG" <<'JXA'
// RELEASE-JS-BEGIN (규칙은 휴대폰 앱 Flow.summary와 같다; 2026-11-04 실제 릴리스 응답 두 개에서 node로 돌려 Java 결과와 글자까지 같음을 확인)
function clean(t){return t.replace(/\[([^\]]+)\]\([^)]+\)/g,'$1').split('**').join('').split('`').join('');}
function shorten(t){var i=t.indexOf('다. ');if(i<0)i=t.indexOf('. ');if(i>=0)t=t.substring(0,i+2).trim();return t.length>90?t.substring(0,89)+'…':t;}
// 소제목(### 또는 **굵은 줄**)마다 첫 항목의 첫 문장, 설치·서명 절이나 테스트 노트의 지난 기록('> ')에서 멈춘다.
function summary(body,heads){var out=[],n=0,want=false,ls=body.split(/\r?\n/);
  for(var k=0;k<ls.length;k++){var l=ls[k].trim();
    if(n>0&&(l.indexOf('> ')===0||l.indexOf('### 설치')===0||l.indexOf('### 서명')===0))break;
    var h=null;if(l.indexOf('### ')===0)h=l.substring(4);else if(l.length>4&&l.indexOf('**')===0&&l.slice(-2)==='**')h=l.substring(2,l.length-2);
    if(h!==null){if(n===heads)break;n++;out.push('■ '+clean(h));want=true;continue;}
    if(want&&l.indexOf('- ')===0){out.push('   '+shorten(clean(l.substring(2))));want=false;}}
  return out;}
function info(r,test){var d=r.published_at||'';
  if(test)(r.assets||[]).forEach(function(a){if(a.name==='N-Dashboard-API17.apk')d=a.updated_at;});   // 테스트: APK를 마지막으로 바꾼 날
  d=d.substring(0,10);
  var title=test?(r.name||'test').replace(/^테스트\s*[—-]\s*/,'')+' · 갱신 '+d:r.tag_name+' · '+d;
  return [title].concat(summary(r.body||'',6)).join('\n');}
// RELEASE-JS-END
function run(argv){ObjC.import('Foundation');var s=$.NSString.stringWithContentsOfFileEncodingError(argv[0],$.NSUTF8StringEncoding,null).js;return info(JSON.parse(s),argv[1]==='1');}
JXA
}
# 리눅스: 위 JavaScript와 같은 규칙을 python3로(라즈베리파이 OS·우분투 기본). python3가 없으면 노트 안내만 빠진다.
rel_info_py() {
  command -v python3 >/dev/null || { log 'python3 없음: 노트 요약 생략'; return 0; }
  python3 -I - "$1" "$2" 2>>"$LOG" <<'PY'
# RELEASE-PY-BEGIN (RELEASE-JS와 같은 규칙; 2026-11-05 실제 릴리스 응답 두 개에서 node로 돈 JS 결과와 글자까지 같음을 확인)
import json, re, sys
def clean(t): return re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', t).replace('**', '').replace('`', '')
def shorten(t):
    i = t.find('다. ')
    if i < 0: i = t.find('. ')
    if i >= 0: t = t[:i + 2].strip()
    return t[:89] + '…' if len(t) > 90 else t
def summary(body, heads):
    out, n, want = [], 0, False
    for l in re.split(r'\r?\n', body):
        l = l.strip()
        if n > 0 and (l.startswith('> ') or l.startswith('### 설치') or l.startswith('### 서명')): break
        h = l[4:] if l.startswith('### ') else l[2:-2] if len(l) > 4 and l.startswith('**') and l.endswith('**') else None
        if h is not None:
            if n == heads: break
            n += 1; out.append('■ ' + clean(h)); want = True; continue
        if want and l.startswith('- '): out.append('   ' + shorten(clean(l[2:]))); want = False
    return out
r = json.load(open(sys.argv[1], encoding='utf-8')); test = sys.argv[2] == '1'; d = r.get('published_at') or ''
if test:
    for a in r.get('assets') or []:
        if a.get('name') == 'N-Dashboard-API17.apk': d = a.get('updated_at') or d   # 테스트: APK를 마지막으로 바꾼 날
d = d[:10]
title = re.sub(r'^테스트\s*[—-]\s*', '', r.get('name') or 'test') + ' · 갱신 ' + d if test else r['tag_name'] + ' · ' + d
sys.stdout.buffer.write(('\n'.join([title] + summary(r.get('body') or '', 6)) + '\n').encode('utf-8'))   # UTF-8 whatever the locale
# RELEASE-PY-END
PY
}
show_notes() {   # $1 = rel_info 결과, $2 = 전체 노트 주소
  if [ -z "$1" ]; then note "버전 정보를 받지 못했습니다(인터넷 확인). 설치는 그대로 계속합니다. 전체 설명: $2"; return; fi
  head_ "이번 버전에 들어 있는 것 ($(printf '%s\n' "$1" | head -1))"
  printf '%s\n' "$1" | tail -n +2 | while IFS= read -r l; do note "$l"; done
  note "전체 설명: $2"
}

# --- 버전 고르기 --------------------------------------------------------------
if [ -z "$UNINSTALL" ] && [ -z "$DIAG" ] && [ -z "$APK_BASE" ]; then
  REL_S="$(rel_info latest 0)"; REL_T="$(rel_info tags/test 1)"
  if [ "$CHANNEL" != stable ] && [ "$CHANNEL" != test ]; then
    if [ -n "$YES" ]; then CHANNEL=stable; else
      head_ '받을 버전'
      note "  1) 안정화 (권장)${REL_S:+ $(printf '%s\n' "$REL_S" | head -1)} — 실차에서 확인된 버전"
      note "  2) 테스트${REL_T:+ $(printf '%s\n' "$REL_T" | head -1)} — 새 기능, 아직 실차 검증 전. 문제가 있으면 안정화로 다시 설치하면 됩니다"
      note '  3) 진단 기록 모으기 — 설치하지 않고, 문제를 알릴 때 보낼 파일만 만듭니다'
      read -r -p '번호를 입력하고 Enter [1] ' r
      case "$(printf '%s' "$r" | tr -d ' ')" in 2) CHANNEL=test ;; 3) DIAG=1 ;; *) CHANNEL=stable ;; esac
    fi
  fi
  if [ -z "$DIAG" ]; then
    if [ "$CHANNEL" = test ]; then APK_BASE="$RELEASES/download/test"; note '버전: 테스트 (실차 검증 전)'; show_notes "$REL_T" "$RELEASES/tag/test"
    else APK_BASE="$RELEASES/latest/download"; note '버전: 안정화'; show_notes "$REL_S" "$RELEASES/latest"; fi
  fi
fi

# --- 새 설치기 확인 (막지는 않는다) ---------------------------------------------
# 레포에 올라간 설치.command의 INSTALLER_VERSION과 비교한다. 테스트를 고른 사람에게만 beta(테스트) 설치기도 알린다.
for b in main $([ "$CHANNEL" = test ] && echo beta); do
  v="$(curl -fsS --max-time 5 "$RAW/$b/installer/%EC%84%A4%EC%B9%98.command" 2>>"$LOG" | sed -n "s/^INSTALLER_VERSION='\([^']*\)'.*/\1/p" | head -1)"
  if [ -n "$v" ] && [ "$(printf '%s\n%s\n' "$v" "$INSTALLER_VERSION" | LC_ALL=C sort | tail -1)" != "$INSTALLER_VERSION" ]; then
    [ $b = main ] && where="안정화 $v — $RELEASES/latest" || where="테스트 $v — $RELEASES/tag/test"
    warn "새 설치기가 있습니다($where). 그 ZIP을 다시 받아 쓰세요. 이번에는 그대로 계속합니다."
  fi
done

# --- APK 준비 (폴더에 있으면 쓰고, 없으면 공개 레포에서 받기) -----------------
[ -z "$UNINSTALL$DIAG" ] && head_ '0) 앱 파일 준비'
for apk in $([ -z "$UNINSTALL$DIAG" ] && echo $APKS); do
  # 브라우저로 받은 GitHub 페이지(HTML)가 .apk 이름으로 저장된 경우가 있어, 'PK'로 시작할 때만 폴더 것을 쓴다.
  if [ -f "$ROOT/$apk" ] && [ "$(head -c 2 "$ROOT/$apk")" = 'PK' ]; then warn "$apk: 설치기 폴더에 있는 파일을 씁니다 (위에서 고른 버전과 상관없이 이 파일이 설치됩니다)"; continue; fi
  [ -f "$ROOT/$apk" ] && warn "$apk: 폴더 파일이 APK가 아니라 무시하고 내려받습니다."
  note "$apk: 폴더에 없어 내려받습니다..."
  curl -fsSL $SLOW -o "$LOGDIR/$apk" "$APK_BASE/$apk" || die "APK 다운로드 실패: $APK_BASE/$apk
인터넷 연결을 확인한 뒤 다시 실행하세요. 인터넷이 안 되는 곳이라면, APK 두 개(N-Dashboard-API17.apk · My-Apps-API17.apk)를 $ME 옆에 두고 실행하면 됩니다." 2
  # APK(=ZIP)는 'PK'로 시작. 404 안내문 등이 저장된 경우를 거른다.
  [ "$(head -c 2 "$LOGDIR/$apk")" = 'PK' ] || die "받은 파일이 APK가 아닙니다($apk): $APK_BASE/$apk" 2
  note "$apk: 받음 ($(( $(wc -c < "$LOGDIR/$apk") / 1024 )) KB)"
done

# --- 차 화면 안내 + 연결 대기 -------------------------------------------------
head_ '2) 차 화면에서 먼저 (사람이 직접)'
note '  (1) 설정 → SW 정보/업데이트 화면 (자세히: docs/ADB.md)'
note '  (2) 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5회 → 오른쪽 숨은 영역 1회 (10초 안에, 엔지니어링 모드)'
note '  (3) 암호(2026년 0567) → Diagnosis → Factory Test 에서  0A 01 → 65 01 → 0A 02  를 한 줄씩 SEND'
note "      ('내 앱'이 이미 깔려 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)"
if [ "$OS" = Darwin ]; then note '  (4) USB 케이블 연결 (USB-C 맥이면 USB-A 어댑터 필요)'
else note '  (4) USB 케이블 연결 (데이터가 되는 케이블. 라즈베리파이는 USB-A 포트에)'; fi
[ -z "$YES" ] && read -r -p '위 단계가 끝나고 USB를 연결했으면 Enter ' _

head_ '3) 차 연결 확인'
"$ADB" start-server >/dev/null 2>&1
SERIAL=''; tries=0
# 기다리는 동안 같은 안내를 5초마다 다시 찍지 않는다: 바뀔 때만 새 줄, 같으면 그 줄 끝의 경과 초만 고친다.
WAIT_MSG=''; WAIT_T0=$(date +%s)
wait_() { if [ "$1" != "$WAIT_MSG" ]; then [ -n "$WAIT_MSG" ] && echo; log "$1"; WAIT_MSG="$1"; fi
          printf '\r\033[%sm%s  %s초 \033[0m' "${2:-0}" "$1" $(( $(date +%s) - WAIT_T0 )); }
wait_end() { [ -n "$WAIT_MSG" ] && echo; WAIT_MSG=''; }
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
# 받을 버전을 바로 아래에: 안정화는 차의 대시보드와 이름이 같으면 그 사실을 알린다(테스트 빌드 이름은 릴리스 정보에 없다).
if [ -z "$UNINSTALL" ] && [ -z "$DIAG" ]; then
  if [ "$CHANNEL" = test ] && [ -n "${REL_T:-}" ]; then note "받을 버전: 테스트 ($(printf '%s\n' "$REL_T" | head -1))"
  elif [ "$CHANNEL" = stable ] && [ -n "${REL_S:-}" ]; then
    tag="$(printf '%s\n' "$REL_S" | head -1 | cut -d' ' -f1)"
    case " $cur " in *" $tag "*) note "받을 버전: $tag — 차에 이미 같은 버전이 있습니다. 그대로 다시 설치합니다." ;; *) note "받을 버전: $tag" ;; esac
  fi
fi

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
  [ -z "$YES" ] && reveal "$out"
  usb_off; finish 0
fi
# 이 저장소의 설치 도구와 같은 원칙: 확인한 대상(jsn_kr · API 17 · 검증 빌드)이 아니면 설치하지 않는다. 삭제는 막지 않는다.
if [ -z "$UNINSTALL" ] && { [ "$build" != "$VERIFIED_BUILD" ] || [ "$sdk" != 17 ]; }; then
  die "확인된 내비 빌드가 아닙니다(API $sdk / $build). 이 설치기는 API 17 · $VERIFIED_BUILD 에서만 설치합니다.
다른 빌드의 호환성은 확인되지 않았습니다(docs/INSTALL.md)." 1
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
  p="$ROOT/$apk"; [ "$(head -c 2 "$p" 2>/dev/null)" = 'PK' ] || p="$LOGDIR/$apk"; name="$(name_of $apk)"; remote="/data/local/tmp/$apk"
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
