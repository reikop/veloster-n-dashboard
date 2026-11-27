<#
  벨로스터 N 계기판 설치 — PowerShell. 사용자는 "설치.cmd"를 더블클릭한다(이 파일을 직접 열 필요 없음).
  연결 확인 · 설치 · '내 앱' 실행을 대신 한다. 엔지니어링 모드 · Factory Test는 차 화면에서 사람이 해야 하므로
  안내만 한다(docs/ADB.md). 차(product:jsn_kr)가 아닌 기기, API 17이 아닌 내비에는 설치하지 않는다(빌드 번호만 다르면 경고 후 설치).
  i30 PD 포팅(sung9155/i30-pd-dashboard, MIT)의 설치기를 벨로스터 N JSN에 맞게 고친 것이다.

  무서명 .exe 가 SmartScreen/백신에 막히던 문제를 없애려고 배치 + PowerShell 로 바꿨다. powershell.exe 는
  Microsoft 서명본이라 SmartScreen 평판 차단이 없고, .cmd 는 파란 차단창 없이 노란 경고 한 번만 뜬다.

  앱 파일: 설치기 ZIP에 든 N-Dashboard-API17.apk · My-Apps-API17.apk 만 설치한다. 옆의 bundle.txt(scripts/apk_info.py가 만듦)에
  적힌 패키지·SHA-256과 다르면 설치하지 않는다. 앱을 인터넷에서 받지 않는다(adb가 없을 때만 받음). 테스트 버전은 'test' 사전 배포의 다른 ZIP.
  테스트 인자: -AdbPath <adb 경로>  -Yes(확인 모두 예, 대기/프롬프트 생략)  -Uninstall(두 앱 삭제)
              -Diag(설치하지 않고 진단 기록 파일만 만든다)
  창 설치기(gui.ps1)용: -Gui  묻지 않고(Enter 대기 없음) 차를 스스로 기다리며, 줄 단위로 찍고 아래 상태 줄을 낸다.
                 @@STEP <제목>  @@WAIT <초> <안내>  @@ASK <질문>(표준 입력에서 y/n 한 줄을 읽음)  @@FILE <경로>  @@DONE ok|fail <내용>
  업데이트: -CheckUpdate  최신 릴리스가 이 설치기보다 새것이면 '@@UPDATE <버전>' 한 줄만 찍는다(인터넷이 없으면 아무것도 안 찍음).
            -Update  최신 릴리스의 vn-installer-windows.zip을 받아 SHA256SUMS.txt로 확인하고 %LOCALAPPDATA%\vn-installer\update\<버전>에
                     푼다. -Gui면 '@@OPEN <새 N계기판설치.cmd>'를 찍고, 콘솔이면 새 설치.cmd를 연다. 시험용: VN_RELEASES(릴리스 주소)
#>
param([string]$AdbPath, [switch]$Yes, [switch]$Uninstall, [switch]$Diag, [switch]$Gui, [switch]$CheckUpdate, [switch]$Update)

# 'Continue' (not 'Stop'): adb writes its progress to stderr, and under 'Stop' a native
# command's stderr turns into a terminating error (NativeCommandError) that aborts the
# script mid-install. Cmdlets that need try/catch get -ErrorAction Stop explicitly.
$ErrorActionPreference = 'Continue'
# 한글이 두 번씩 찍히던 문제(2026-11-04 사진, Windows 터미널): Invoke-WebRequest의 진행률 상자가 창 위쪽을 덮었다가
# 원래 글자를 다시 써 넣을 때, 2칸 글자(한글)의 오른쪽 반 칸이 글자로 한 번 더 그려진다. 진행률 상자를 띄우지 않는다
# (받는 중 안내는 이 스크립트가 직접 찍고, PowerShell 5.1에서는 받기도 훨씬 빨라진다).
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
# 긴 한글 줄이 겹쳐 찍히던 문제: UTF-8 코드 페이지의 구형 Windows 콘솔은 창 끝에서 줄을 넘길 때 한글(2칸)을 1칸으로 세어,
# 넘어간 부분을 앞 글자 위에 겹쳐 찍고 다음 줄까지 밀어 겹친다(2026-11-03 Windows 10 PC 사진, 노란 경고 두 줄).
# 그래서 이 스크립트의 Write-Host는 모두 아래를 거친다. 창 폭보다 긴 줄은 화면 칸(한글·기호 2칸)으로 세어 띄어쓰기에서
# 직접 나눈 뒤 찍으므로 콘솔이 줄을 넘길 일이 없다. -NoNewline(진행 표시)은 그대로 넘긴다.
function CellWidth([char]$ch){
  $n = [int]$ch
  if($n -eq 0xB7){ return 2 }   # 가운뎃점: 한국어 콘솔 글꼴에서 2칸
  if($n -lt 0x1100){ return 1 }
  if($n -le 0x115F -or ($n -ge 0x2010 -and $n -le 0x2BFF) -or ($n -ge 0x2E80 -and $n -le 0xA4CF) -or ($n -ge 0xAC00 -and $n -le 0xD7A3) -or
     ($n -ge 0xF900 -and $n -le 0xFAFF) -or ($n -ge 0xFE30 -and $n -le 0xFE4F) -or ($n -ge 0xFF00 -and $n -le 0xFF60) -or ($n -ge 0xFFE0 -and $n -le 0xFFE6)){ return 2 }
  return 1
}
function Fit([string]$m, [int]$max = 0){
  if($Gui){ return ,@($m) }   # 창 설치기가 줄을 직접 나눈다
  if($max -le 0){ $max = 78; try { $w = [Console]::WindowWidth; if($w -ge 40){ $max = $w - 2 } } catch {} }
  $out = New-Object System.Collections.Generic.List[string]
  $indent = ''; if($m -match '^( +)'){ $indent = $Matches[1] + '  ' }   # 이어지는 줄은 원래 들여쓰기 + 2칸
  $line = ''; $cells = 0; $space = -1
  foreach($ch in $m.ToCharArray()){
    $cw = CellWidth $ch
    if($cells + $cw -gt $max -and $line.Length -gt $indent.Length){
      if($space -gt $indent.Length){ $out.Add($line.Substring(0, $space)); $line = $indent + $line.Substring($space + 1) }
      else { $out.Add($line); $line = $indent }
      $cells = 0; foreach($c in $line.ToCharArray()){ $cells += CellWidth $c }
      $space = $line.LastIndexOf(' '); if($space -le $indent.Length){ $space = -1 }
    }
    if($ch -eq ' ' -and $line.Length -gt $indent.Length){ $space = $line.Length }
    $line += $ch; $cells += $cw
  }
  $out.Add($line)
  return ,$out.ToArray()
}
function Write-Host {
  param([Parameter(Position=0)][object]$Object = '', [ConsoleColor]$ForegroundColor, [switch]$NoNewline)
  $p = @{}; if($PSBoundParameters.ContainsKey('ForegroundColor')){ $p['ForegroundColor'] = $ForegroundColor }
  if($NoNewline){ Microsoft.PowerShell.Utility\Write-Host $Object -NoNewline @p; return }
  foreach($part in ([string]$Object -split "`r?`n")){ foreach($l in (Fit $part)){ Microsoft.PowerShell.Utility\Write-Host $l @p } }
}
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallerVersion = '1.13.0'   # 릴리스 버전(MAJOR.MINOR.PATCH). 화면 맨 위에 찍혀, 스크린샷만으로 옛 설치기인지 알 수 있다. 고칠 때마다 올린다.
$Product = 'jsn_kr'                              # 벨로스터 N 국내 사양 내비
$VerifiedBuild = 'eng.vmadmin.20250902.081552'   # 공개 앱 설치·실행을 확인한 빌드(docs/INSTALL.md). 다르면 경고만 하고 설치한다.
$PT_URL = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$USB_URL = 'https://dl.google.com/android/repository/usb_driver_r13-windows.zip'
# APK를 폴더에서 못 찾으면 공개 배포 레포에서 받는다: 안정화 = 최신 정식 릴리스, 테스트 = 'test' 사전 배포(덮어쓰며 갱신).
$Releases = 'https://github.com/reikop/veloster-n-dashboard/releases'
if($env:VN_RELEASES){ $Releases = $env:VN_RELEASES.TrimEnd('/') }   # 시험용: 가짜 릴리스 서버
$Apps = @(
  [pscustomobject]@{ apk='N-Dashboard-API17.apk'; pkg='com.veloster.dashboard'; name='N 계기판' },
  [pscustomobject]@{ apk='My-Apps-API17.apk';     pkg='com.veloster.carplayhub'; name="'내 앱'" }
)
$script:adbExe = $null
# 차에 깔린 두 앱의 버전 한 줄.
function Versions {
  ($Apps | ForEach-Object {
    $n = if([string](Adb '-s' $serial 'shell' 'dumpsys' 'package' $_.pkg) -match 'versionName=(\S+)'){ $Matches[1] } else { '없음' }
    "$($_.name) " + $n
  }) -join ' · '
}
$LogDir = Join-Path $env:LOCALAPPDATA 'vn-installer'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Log = Join-Path $LogDir ("install_{0}.log" -f (Get-Date -Format yyyyMMdd_HHmmss))

function Log($m){ Add-Content -LiteralPath $Log -Value $m -Encoding UTF8 }   # 기록 파일에만 (문제 제보용)
function Note($m,[string]$c='Gray'){ Write-Host $m -ForegroundColor $c; Log $m }
function Head($m){ $t = Get-Date -Format HH:mm:ss; Write-Host ''; if($Gui){ Write-Host "@@STEP $m" }; Write-Host "$m   [$t]" -ForegroundColor Cyan; Log "== $m == $t" }
function Die($m,$code){
  Write-Host ''; Write-Host $m -ForegroundColor Red; Log "ERROR: $m"
  Write-Host "기록 파일: $Log" -ForegroundColor Yellow
  Write-Host '문제를 알릴 때 이 기록 파일을 함께 보내 주세요.' -ForegroundColor Yellow
  if($Gui){ Write-Host "@@FILE $Log"; Write-Host ('@@DONE fail ' + ([string]$m -split "`r?`n")[0]) }
  elseif(-not $Yes){ try { Start-Process explorer.exe "/select,`"$Log`"" } catch {} }
  exit $code
}
function Done(){ if($Gui){ Write-Host '@@DONE ok' }; exit 0 }
function UsbOffNote(){
  Note 'USB 디버깅 끄기: 시동을 껐다 켜면 저절로 꺼집니다. 바로 끄려면 Factory Test에서  0A 01 → 65 00 → 0A 02' Gray
  Note "  (또는 '내 앱' → USB 디버깅 → OFF). 끄면 노트북 연결이 끊깁니다." Gray
}
# 진단 기록에서 사람을 가릴 수 있는 것: MAC 주소, 휴대폰 번호, 메일 주소, adb 기기 번호, 경로 속 Windows 사용자 이름.
function Mask([string]$t){
  $t = $t -replace '\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b','<MAC>'
  $t = $t -replace '\b01[016789][-. ]?\d{3,4}[-. ]?\d{4}\b','<전화번호>'
  $t = $t -replace '[\w.+-]+@[\w-]+(?:\.[\w-]+)+','<메일>'
  if($serial){ $t = $t.Replace($serial,'<기기번호>') }
  if($env:USERPROFILE){ $t = $t.Replace($env:USERPROFILE,'%USERPROFILE%') }
  return $t
}
# -Gui: 창 설치기가 예/아니오 창을 띄우고 답 한 줄(y/n)을 표준 입력으로 보낸다. 답이 없으면(창이 닫힘) 아니오.
function Ask($m){
  # 답 앞에 BOM이 붙어 올 수 있다: 콘솔 입력 인코딩이 UTF-8인 Windows("UTF-8 사용" 설정, GitHub Actions)에서 예전 gui.ps1의 첫 쓰기에
  # BOM이 붙고, 이쪽은 OEM 코드 페이지로 읽어 '∩╗┐y'가 된다. 답은 y/n뿐이니 앞의 ASCII 아닌 글자를 뗀다(2026-11-25 windows-latest에서 발견).
  if($Gui){ Write-Host "@@ASK $m"; $r = (([string][Console]::In.ReadLine()) -replace '^[^\x21-\x7E]+', '').Trim(); Log "$m → $r"; return ($r -match '^[Yy]') }
  if($Yes){ return $true }; $r = Read-Host "$m [Y/n]"; return ($r -eq '' -or $r -match '^[Yy]') }
function Adb([Parameter(ValueFromRemainingArguments=$true)]$a){
  $o = & $script:adbExe @a 2>&1 | Out-String
  Add-Content -LiteralPath $Log -Value ("> adb " + ($a -join ' ') + "`n" + $o) -Encoding UTF8
  return $o
}
# 오래 걸릴 수 있는 adb(전송·설치)는 제한 시간 안에서 돌리고, 기다리는 동안 경과 초를 같은 줄에 찍는다:
# 멈춘 화면의 스크린샷만 봐도 어느 단계에서 몇 초째 멈췄는지 보이게. 시간 초과면 $null.
function AdbWait([int]$sec,[string]$what,[string[]]$a){
  $out = Join-Path $LogDir 'adb_out.txt'; $err = Join-Path $LogDir 'adb_err.txt'
  $line = ($a | ForEach-Object { if($_ -match '\s'){ '"' + $_ + '"' } else { $_ } }) -join ' '
  Log "> adb $line  (최대 ${sec}초)"
  $p = Start-Process -FilePath $script:adbExe -ArgumentList $line -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
  if($Gui){ Write-Host "@@WAIT 0 $what" }
  Write-Host "  $what " -NoNewline
  $t0 = Get-Date; $shown = 0
  while(-not $p.WaitForExit(1000)){
    $s = [int]((Get-Date) - $t0).TotalSeconds
    if($s -ge $shown + 5){ $shown = $s - $s % 5; Write-Host "${shown}s " -NoNewline -ForegroundColor DarkGray }
    if($s -ge $sec){
      & taskkill.exe /T /F /PID $p.Id *> $null   # 하위 프로세스까지 끝내야 창이 바로 닫힌다
      Write-Host "멈춤(${sec}초)" -ForegroundColor Red; Log "  → ${sec}초 넘게 응답 없음, 중단"; return $null
    }
  }
  $o = [string](Get-Content -Raw $out -ErrorAction SilentlyContinue) + [string](Get-Content -Raw $err -ErrorAction SilentlyContinue)
  $s = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
  Write-Host "(${s}초)" -ForegroundColor DarkGray
  Log ("$o  (${s}초)")
  return $o
}
# 전송·설치가 멈췄을 때: 멈춘 곳과 할 일, 내비의 설치 로그 끝부분을 화면에 띄우고 끝낸다.
function Stuck($where,$hint){
  Note "멈춘 곳: $where" Red
  Note "  $hint" Yellow
  $lc = AdbWait 15 '내비 설치 로그 읽는 중' @('-s',$serial,'logcat','-d','-t','300','-s','PackageManager:*','PackageParser:*','installd:*','DefContainerService:*','dalvikvm:E')
  if($lc){ Note '  설치 로그(마지막 줄):' DarkGray; ($lc -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 12) | ForEach-Object { Note "    $_" DarkGray } }
  Die "$where 단계에서 멈췄습니다. 이 화면을 찍어 보내 주시면 원인을 볼 수 있습니다." 1
}

# --- 업데이트: 최신 정식 릴리스(releases/latest가 가리키는 태그 vX.Y.Z)와 비교하고, 새 설치기를 받아 확인 후 푼다 -------------
function LatestVersion(){
  try {
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}
    $r = [Net.HttpWebRequest]::Create("$Releases/latest"); $r.AllowAutoRedirect = $false; $r.Timeout = 8000; $r.UserAgent = 'vn-installer'
    $resp = $r.GetResponse(); $loc = [string]$resp.Headers['Location']; $resp.Close()
    if($loc -match '/tag/v(\d+\.\d+\.\d+)$'){ return $Matches[1] }
    Log "최신 릴리스 주소를 읽지 못함: $loc"
  } catch { Log "최신 릴리스 확인 실패: $($_.Exception.Message)" }
  return $null
}
# 숫자로 비교한다(1.11.0 > 1.9.0).
function Newer([string]$v){ $a = $null; $b = $null; return ([version]::TryParse($v, [ref]$a) -and [version]::TryParse($InstallerVersion, [ref]$b) -and $a -gt $b) }
function DoUpdate([string]$v){
  Head "새 설치기 $v 받기"
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}
  $up = Join-Path $LogDir 'update'; New-Item -ItemType Directory -Force -Path $up | Out-Null
  $zip = Join-Path $up "vn-installer-windows-$v.zip"; $sums = Join-Path $up "SHA256SUMS-$v.txt"; $dir = Join-Path $up $v
  Note "받는 중: $Releases/download/v$v/vn-installer-windows.zip" Gray
  try {
    Invoke-WebRequest -Uri "$Releases/download/v$v/vn-installer-windows.zip" -OutFile $zip -UseBasicParsing -TimeoutSec 120 -ErrorAction Stop
    Invoke-WebRequest -Uri "$Releases/download/v$v/SHA256SUMS.txt" -OutFile $sums -UseBasicParsing -TimeoutSec 30 -ErrorAction Stop
  } catch { Die "새 설치기를 받지 못했습니다: $($_.Exception.Message)" 2 }
  $want = (Get-Content -LiteralPath $sums | Where-Object { $_ -match '^([0-9a-f]{64})\s+vn-installer-windows\.zip$' } | Select-Object -First 1) -replace '\s.*$',''
  $got = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
  if(-not $want -or $got -ne $want){ Remove-Item -LiteralPath $zip -ErrorAction SilentlyContinue; Die "받은 파일의 SHA-256이 릴리스 목록과 다릅니다(받은 것 $got). 설치기를 바꾸지 않았습니다." 2 }
  Note "SHA-256 확인: $got" DarkGray
  if(Test-Path -LiteralPath $dir){ Remove-Item -LiteralPath $dir -Recurse -Force }
  try { Expand-Archive -LiteralPath $zip -DestinationPath $dir -Force -ErrorAction Stop } catch { Die "압축 해제 실패: $($_.Exception.Message)" 2 }
  $gui = Join-Path $dir 'N계기판설치.cmd'
  if(-not (Test-Path -LiteralPath $gui)){ Die "받은 설치기에 N계기판설치.cmd가 없습니다: $dir" 2 }
  Note "새 설치기: $dir" Green
  Note '이제 새 설치기를 엽니다. 예전 설치기 폴더는 지워도 됩니다.' Gray
  if($Gui){ Write-Host "@@OPEN $gui"; Done }
  try { Start-Process -FilePath (Join-Path $dir '설치.cmd') -WorkingDirectory $dir } catch { try { Start-Process explorer.exe "`"$dir`"" } catch {} }
  exit 0
}
if($CheckUpdate){ $v = LatestVersion; if($v -and (Newer $v)){ Write-Host "@@UPDATE $v" }; exit 0 }
if($Update){
  Note "Windows 설치기 $InstallerVersion — 업데이트 확인" White
  $v = LatestVersion
  if(-not $v){ Die "최신 버전을 확인하지 못했습니다. 인터넷 연결을 확인하세요($Releases/latest)." 2 }
  if(-not (Newer $v)){ Note "이미 최신 설치기입니다($InstallerVersion)." Green; Done }
  DoUpdate $v
}

Write-Host ''
try { $Host.UI.RawUI.WindowTitle = "벨로스터 N 계기판 — Windows 설치기 $InstallerVersion" } catch {}   # 창 제목이 cmd 경로 대신 이 이름으로
Write-Host $(if($Uninstall){ "  벨로스터 N 계기판 삭제   (Windows 설치기 $InstallerVersion)" } elseif($Diag){ "  벨로스터 N 계기판 진단 기록   (Windows 설치기 $InstallerVersion)" } else { "  벨로스터 N 계기판 설치   (Windows 설치기 $InstallerVersion)" }) -ForegroundColor White
Write-Host '  ----------------------' -ForegroundColor DarkGray
Note '현대자동차 공식 앱이 아닙니다. 순정 시스템에 앱을 설치하는 작업이라 보증/AS 불이익이 있을 수 있고,' Yellow
Note '책임은 본인에게 있습니다. 모든 작업은 정차 · 시동 켠 상태에서 하세요.' Yellow
Note "기록: $Log" DarkGray

# --- adb 찾기/받기 ------------------------------------------------------------
Head '1) adb 준비'
if($AdbPath){ $script:adbExe = $AdbPath }
if(-not $script:adbExe){
  foreach($c in @((Join-Path $ROOT 'platform-tools\adb.exe'), (Join-Path $ROOT 'adb.exe'))){
    if(Test-Path $c){ $script:adbExe = $c; break }
  }
}
if(-not $script:adbExe){
  $w = Get-Command adb.exe -ErrorAction SilentlyContinue
  if($w){ $script:adbExe = $w.Source }
}
if(-not $script:adbExe){
  Note 'adb(platform-tools)가 없습니다.' Gray
  if(-not (Ask 'Google 공식 주소에서 받을까요?')){ Die 'adb가 없어 중단합니다.' 2 }
  $zip = Join-Path $LogDir 'platform-tools.zip'
  Note '내려받는 중...' Gray
  try { Invoke-WebRequest -Uri $PT_URL -OutFile $zip -UseBasicParsing -ErrorAction Stop } catch { Die "다운로드 실패: $($_.Exception.Message)" 2 }
  try { Expand-Archive -LiteralPath $zip -DestinationPath $ROOT -Force -ErrorAction Stop } catch { Die "압축 해제 실패: $($_.Exception.Message)" 2 }
  $script:adbExe = Join-Path $ROOT 'platform-tools\adb.exe'
  if(-not (Test-Path $script:adbExe)){ Die 'adb를 준비하지 못했습니다.' 2 }
}
Note "adb: $script:adbExe" DarkGray
Log ("환경: Windows $([Environment]::OSVersion.Version) / PowerShell $($PSVersionTable.PSVersion) / " + ((& $script:adbExe version 2>&1 | Select-Object -First 1) -join ''))

# --- 새 설치기 확인 (막지는 않는다, 인터넷이 없으면 조용히 넘어간다) -----------------
# 최신 정식 릴리스가 더 새것이면 알리고, 콘솔에서는 지금 받을지 묻는다(창 설치기는 [업데이트] 단추가 따로 있다).
$latest = LatestVersion
if($latest -and (Newer $latest)){
  Note "새 설치기 $latest 가 있습니다(지금 $InstallerVersion)." Yellow
  if(-not $Gui -and -not $Yes -and (Ask "새 설치기 $latest 를 받아 쓸까요? (아니오: 이번에는 그대로 계속)")){ DoUpdate $latest }
}

# --- 동봉 APK 확인: bundle.txt에 적힌 패키지·SHA-256과 같은 파일만 설치한다 ------------
$Bundle = Join-Path $ROOT 'bundle.txt'   # 파일 패키지 versionCode versionName sha256 (탭 구분)
$bundled = $null
if(-not $Uninstall -and -not $Diag){
  Head '0) 앱 파일 확인'
  if(-not (Test-Path -LiteralPath $Bundle)){ Die '이 설치기에 든 앱 목록(bundle.txt)이 없습니다. 릴리스에서 설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요.' 2 }
  $rows = @{}
  foreach($l in (Get-Content -LiteralPath $Bundle -Encoding UTF8)){ if($l -and -not $l.StartsWith('#')){ $f = $l -split "`t"; if($f.Count -ge 5){ $rows[$f[0]] = $f } } }
  foreach($app in $Apps){
    $r = $rows[$app.apk]
    if(-not $r -or $r[1] -ne $app.pkg){ Die "bundle.txt에 $($app.apk) 이(가) 없거나 패키지가 다릅니다. 설치기 ZIP을 다시 받으세요." 2 }
    $path = Join-Path $ROOT $app.apk
    if(-not (Test-Path -LiteralPath $path)){ Die "$($app.apk) 이(가) 설치기 폴더에 없습니다. 설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요." 2 }
    $h = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower(); Log "$($app.apk) sha256 $h"
    if($h -ne $r[4].Trim().ToLower()){ Die "$($app.apk) 이(가) 이 설치기에 든 파일과 다릅니다(SHA-256 불일치). 설치하지 않습니다.`n설치기 ZIP을 다시 받아 압축을 푼 뒤 실행하세요." 2 }
    $app | Add-Member -NotePropertyName path -NotePropertyValue $path -Force
    $app | Add-Member -NotePropertyName ver -NotePropertyValue $r[3] -Force
  }
  $bundled = ($Apps | ForEach-Object { "$($_.name) $($_.ver)" }) -join ' · '
  Note "이 설치기에 든 버전: $bundled" Green
}

# --- 차 화면 안내 + 연결 대기 -------------------------------------------------
Head '2) 차 화면에서 먼저 (사람이 직접)'
Note '  (1) 설정 → SW 정보/업데이트 화면 (자세히: docs/ADB.md)' Gray
Note '  (2) 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5회 → 오른쪽 숨은 영역 1회 (10초 안에, 엔지니어링 모드)' Gray
Note '  (3) 암호(2026년 0567) → Diagnosis → Factory Test 에서  0A 01 → 65 01 → 0A 02  를 한 줄씩 SEND' Gray
Note "      ('내 앱'이 이미 깔려 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)" Gray
Note '  (4) USB 케이블 연결' Gray
if(-not $Yes -and -not $Gui){ Read-Host '위 단계가 끝나고 USB를 연결했으면 Enter' | Out-Null }

Head '3) 차 연결 확인'
& $script:adbExe start-server *> $null

# adb에 안 보일 때 Windows 장치 목록으로 원인을 가른다. 차는 USB 디버깅을 켜면 Google VID 18D1로 잡힌다(검증 차:
# PID D00D = adb 단독; init.rc상 mass_storage,adb는 DEED, accessory,adb는 2D01 — 블루링크 없는 모델 등 기기가 다르면
# 다를 수 있다). Google USB 드라이버 INF에 이 PID들이 없어 드라이버가 자동으로 안 붙는다("알 수 없는 장치").
# VID_0000&PID_0002는 "장치 설명자 요청 실패"(코드 43) = USB 연결 자체가 안 된 것이라 드라이버로는 못 고친다.
# 오류 상태인 USB 장치 ID는 화면·기록에 남겨, 모르는 기기라도 사용자가 그대로 알려 줄 수 있게 한다.
# 기록 파일에는 USB 장치 목록 전체를 바뀔 때마다 시각과 함께 남긴다(케이블을 꽂는 순간 무엇이 나타났는지 보이게).
$script:badIds = ''; $script:usbLast = ''
function UsbProblem(){
  $usbAll = @(Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue | Where-Object { $_.PNPDeviceID -match '^USB\\' } | Sort-Object PNPDeviceID)
  $txt = ($usbAll | ForEach-Object { "$($_.PNPDeviceID) [$($_.Name)] $($_.Status) 코드 $($_.ConfigManagerErrorCode)" }) -join "`n  "
  if($txt -ne $script:usbLast){ Log ("[{0:HH:mm:ss}] USB 장치 목록:`n  {1}" -f (Get-Date), $txt); $script:usbLast = $txt }
  $bad = @($usbAll | Where-Object { $_.ConfigManagerErrorCode -ne 0 })
  $script:badIds = ($bad | ForEach-Object { "$($_.PNPDeviceID) [$($_.Name)] 코드 $($_.ConfigManagerErrorCode)" }) -join "`n  "
  if(@($bad | Where-Object { $_.PNPDeviceID -match 'VID_18D1&PID_' }).Count){ return 'driver' }
  if(@($bad | Where-Object { $_.PNPDeviceID -match 'VID_0000&PID_0002' }).Count){ return 'cable' }
  if($bad.Count){ return 'other' }
  return $null
}
function DriverHelp(){
  Note '→ 차는 연결됐지만 Windows에 드라이버가 없습니다(장치 관리자에 "알 수 없는 장치"/"DiPO"/"Telechips").' Yellow
  Note "  오류 장치: $script:badIds" DarkGray
  Note '  Google 드라이버 목록에 이 장치 번호가 없어 자동으로 안 잡힙니다. 한 번만 직접 지정하면 됩니다:' Yellow
  Note '  장치 관리자 → 그 장치 우클릭 → 드라이버 업데이트 → 내 컴퓨터에서 드라이버 찾아보기' Gray
  Note '  → 컴퓨터의 사용 가능한 드라이버 목록에서 직접 선택 → (모든 장치 표시) → 디스크 있음' Gray
  Note '  → 아래 android_winusb.inf → Android ADB Interface → "호환되지 않을 수 있음" 경고에 예' Gray
  Note '  (하드웨어 ID 끝에 &MI_01 처럼 MI_ 가 붙어 있으면 Android Composite ADB Interface 를 고릅니다.)' Gray
  Note '  (INF 파일에 장치 번호를 직접 추가하지 마세요 — 서명이 깨져 Windows가 설치를 거부합니다.)' DarkGray
  $inf = Join-Path $LogDir 'usb_driver\android_winusb.inf'
  if(-not (Test-Path $inf) -and (Ask 'Google 공식 USB 드라이버를 받을까요?')){
    $zip = Join-Path $LogDir 'usb_driver.zip'
    try { Invoke-WebRequest -Uri $USB_URL -OutFile $zip -UseBasicParsing -ErrorAction Stop; Expand-Archive -LiteralPath $zip -DestinationPath $LogDir -Force -ErrorAction Stop }
    catch { Note "드라이버 다운로드 실패: $($_.Exception.Message)" Yellow }
  }
  if(Test-Path $inf){ Note "  드라이버 파일: $inf" White }
  if(Ask '장치 관리자를 열까요?'){ Start-Process devmgmt.msc }
}

$serial = $null
$tries = 0
$hinted = $null
# 기다리는 동안 같은 안내를 5초마다 다시 찍지 않는다: 바뀔 때만 새 줄, 같으면 그 줄 끝의 경과 초만 고친다.
$script:waitMsg = $null; $waitT0 = Get-Date
function Wait($m,[string]$c='Gray'){
  if($Gui){ if($m -ne $script:waitMsg){ Log $m; $script:waitMsg = $m }; Write-Host ("@@WAIT {0} {1}" -f [int]((Get-Date) - $waitT0).TotalSeconds, $m); return }
  if($m -ne $script:waitMsg){ if($script:waitMsg){ Write-Host '' }; Log $m; $script:waitMsg = $m }
  Write-Host ("`r{0}  {1}초 " -f $m, [int]((Get-Date) - $waitT0).TotalSeconds) -NoNewline -ForegroundColor $c
}
function WaitEnd(){ if($script:waitMsg){ if(-not $Gui){ Write-Host '' }; $script:waitMsg = $null } }
while(-not $serial){
  $dl = & $script:adbExe devices -l 2>&1 | Out-String
  Add-Content -LiteralPath $Log -Value $dl -Encoding UTF8
  $pd = @(); $others = @()
  foreach($line in ($dl -split "`r?`n")){
    if($line -match '^(\S+)\s+device\b'){
      $sn = $Matches[1]   # capture before the next -match overwrites $Matches
      if($line -match ('product:' + $Product + '(\s|$)')){ $pd += $sn } else { $others += $sn }   # exactly jsn_kr, not jsn_kr2
    }
  }
  if($pd.Count -gt 0){ $serial = $pd[0]; break }
  if($Yes){ Die "차($Product)를 찾지 못했습니다." 1 }
  $usb = UsbProblem
  if($dl -match 'unauthorized'){ Wait '→ 차 화면에 "USB 디버깅 허용" 창이 뜨면 [항상 허용] 하세요.' Yellow }
  elseif($usb -eq 'driver'){ if($hinted -ne 'driver'){ WaitEnd; DriverHelp; $hinted='driver' } else { Wait '→ 드라이버 지정을 기다리는 중... (위 안내 참고)' } }
  elseif($usb -eq 'cable' -and $hinted -ne 'cable'){
    WaitEnd
    Note '→ USB 장치 하나가 연결에 실패했습니다("알 수 없는 USB 장치(장치 설명자 요청 실패)").' Yellow
    Note '  가장 흔한 원인은 Factory Test 입력 실수입니다: 케이블을 빼고  0A 01 / 65 01 / 0A 02  를 띄어쓰기까지 정확히,' Yellow
    Note '  한 줄씩 지우고 입력해 SEND 한 뒤 다시 꽂으세요(실제 사례: 코드를 고쳐 입력하자 바로 설치됨).' Yellow
    Note '  차라면 드라이버 문제가 아니라 연결 문제입니다. 케이블(데이터 지원, 성공 사례는 A-A)이나 PC 포트를 바꿔 보세요.' Yellow
    Note '  차 쪽도 확인: 음악 USB가 재생되는 데이터 포트인지, 폰(카플레이)을 빼고 재시동 후 Factory Test를 다시 했는지.' Yellow
    Note '  같은 케이블·PC로 다른 차는 된다면 이 차의 USB 포트·배선 쪽입니다(블루링크 없는 내비도 설치된 사례가 있습니다).' Yellow
    $hinted='cable'
  }
  elseif($usb -eq 'other' -and $hinted -ne 'other'){
    WaitEnd
    Note '→ 오류 상태인 USB 장치가 있습니다. 차를 꽂은 뒤 생긴 장치라면 아래 ID를 Issues에 알려 주세요(차종·블루링크 유무와 함께).' Yellow
    Note "  $script:badIds" DarkGray
    $hinted='other'
  }
  elseif($others.Count -gt 0){
    if($hinted -ne 'others'){
      WaitEnd
      Note "  차인데 여기 보인다면 기기 이름(product:)이 검증된 $Product 과 다른 내비입니다. 이 설치기로는 설치할 수 없습니다:" Gray
      foreach($line in ($dl -split "`r?`n")){ if($line -match '^\S+\s+device\b'){ Note "  $line" DarkGray } }
      $hinted='others'
    }
    Wait '→ 차가 아닌 기기만 보입니다(휴대폰 등). 그 기기는 건드리지 않습니다.' Yellow
  }
  else { Wait '→ 차를 기다리는 중... USB 디버깅/Factory Test(0A 01/65 01/0A 02) 다시 확인.' }
  $tries++
  # 5초 간격으로 1분. 사람이 할 일이 남았으면(드라이버 지정, 차 화면 허용) 3분까지 기다린다.
  $limit = if($hinted -eq 'driver' -or $dl -match 'unauthorized'){ 36 } else { 12 }
  if($Gui){ $limit = 60 }   # 창 설치기: Enter로 준비를 알리지 않으므로 차 화면 단계를 할 시간까지 5분 기다린다
  if($tries -ge $limit){
    WaitEnd
    # 못 찾음: 마지막 상태를 한눈에 요약한다(자세한 시간순 기록은 기록 파일에).
    $why = if($dl -match 'unauthorized'){ '차 화면에서 USB 디버깅 허용을 누르지 않음' }
      elseif($usb -eq 'driver'){ '차는 보였지만 Windows 드라이버가 지정되지 않음' }
      elseif($usb -eq 'cable'){ 'USB 연결 실패(장치 설명자 요청 실패) — Factory Test 입력부터 다시, 그다음 케이블·포트' }
      elseif($others.Count -gt 0){ "$Product 이 아닌 기기만 보임" }
      elseif($usb -eq 'other'){ '오류 상태인 USB 장치만 있음' }
      else { 'PC에 차가 전혀 나타나지 않음 — Factory Test(0A 01/65 01/0A 02)·케이블·포트 확인' }
    Note "마지막 상태: $why" Yellow
    if($script:badIds){ Note "  오류 장치: $script:badIds" DarkGray }
    Die "시간 초과($($limit*5)초). 차를 찾지 못했습니다. 위 상태를 고친 뒤 설치.cmd를 다시 실행하세요." 1
  }
  Start-Sleep -Seconds 5
}
WaitEnd
Note "차 연결됨: $serial" Green

# --- 내비 정보 · 빌드 확인 -------------------------------------------------------
# 정해 둔 항목만 읽는다: getprop 전체에는 시리얼·블루투스 주소 등 개인 정보가 있어, 공개 Issues에 올라갈 기록 파일에 넣지 않는다.
$pv = @{}
foreach($k in 'ro.product.name','ro.product.device','ro.build.product','ro.build.version.incremental','ro.build.fingerprint',
              'sys.usb.config','sys.usb.state','persist.sys.usb.config','persist.sys.dipo.state','ro.build.version.sdk'){
  $pv[$k] = (Adb '-s' $serial 'shell' 'getprop' $k).Trim()
}
$build = $pv['ro.build.version.incremental']; $device = $pv['ro.product.device']
$sdk = $pv['ro.build.version.sdk']
Note "내비: $Product ($device) / API $sdk / 빌드 $build / USB $($pv['sys.usb.state'])" DarkGray
$cur = Versions
Note "차에 깔린 버전: $cur" DarkGray
if($bundled){ Note "설치할 버전: $bundled" DarkGray }

# --- 진단 기록 모드: 읽기만 한다 -------------------------------------------------
# 정해 둔 속성, 두 앱의 버전, 우리 앱 태그·충돌·설치 기록만. 차 기록 전체에는 블루투스로 붙은 폰 이름·번호·연락처가 섞인다.
if($Diag){
  Head '진단 기록 모으기 (차에는 아무것도 바꾸지 않습니다)'
  $sb = New-Object Text.StringBuilder
  [void]$sb.AppendLine("벨로스터 N 계기판 진단 기록 — $(Get-Date -Format 'yyyy-MM-dd HH:mm') / PC 설치기 $InstallerVersion (Windows)").AppendLine().AppendLine('[내비]')
  foreach($k in ($pv.Keys | Sort-Object)){ [void]$sb.AppendLine("${k}: $($pv[$k])") }
  [void]$sb.AppendLine("시각: $((Adb '-s' $serial 'shell' 'date').Trim())").AppendLine().AppendLine('[앱]')
  foreach($app in $Apps){
    $v = @((Adb '-s' $serial 'shell' 'dumpsys' 'package' $app.pkg) -split "`r?`n" | Where-Object { $_ -match '(versionCode|versionName|firstInstallTime|lastUpdateTime|codePath)=' } | ForEach-Object { '  ' + $_.Trim() } | Select-Object -Unique)
    [void]$sb.AppendLine("$($app.name) ($($app.pkg))")
    if($v.Count){ $v | ForEach-Object { [void]$sb.AppendLine($_) } } else { [void]$sb.AppendLine('  설치돼 있지 않음') }
  }
  Note '기록 읽는 중...' Gray
  $tags = @('VehicleDash:V','VNCarPlayHub:V','VNQuickEntry:V','NDashboard:V','AndroidRuntime:E','PackageManager:I')
  $lc = & $script:adbExe -s $serial shell logcat -d -v time -s @tags 2>&1 | Out-String   # 설치기 기록에는 안 남긴다(아래에 그 기록을 붙임)
  [void]$sb.AppendLine().AppendLine("[기록: $($tags -join ' ')]").AppendLine(((($lc -split "`r?`n") | Select-Object -Last 3000) -join "`r`n"))
  [void]$sb.AppendLine().AppendLine('[이 PC 설치기 기록]').Append((Get-Content -Raw -LiteralPath $Log -Encoding UTF8))
  $desk = [Environment]::GetFolderPath('Desktop'); if(-not $desk -or -not (Test-Path $desk)){ $desk = $LogDir }
  $out = Join-Path $desk ("vn_진단기록_{0}.txt" -f (Get-Date -Format yyyyMMdd_HHmmss))
  [IO.File]::WriteAllText($out, (Mask $sb.ToString()), (New-Object Text.UTF8Encoding $true))
  Note "만든 파일: $out" Green
  Note '문제를 알릴 때 이 파일을 보내 주세요. 증상(언제, 어떤 화면에서, 무엇이)도 함께 알려 주세요.' White
  if($Gui){ Write-Host "@@FILE $out" } elseif(-not $Yes){ Start-Process explorer.exe "/select,`"$out`"" }
  UsbOffNote; Done
}
# jsn_kr · API 17이 아니면 설치하지 않는다. 빌드 번호만 다르면 경고하고 설치한다(앱은 API 17 공개 API만 쓴다). 삭제는 막지 않는다.
if(-not $Uninstall -and $sdk -ne '17'){
  Die "지원하지 않는 내비입니다(API $sdk / $build). 이 설치기는 API 17 내비에만 설치합니다." 1
}
if(-not $Uninstall -and $build -ne $VerifiedBuild){
  Note "주의: 검증한 빌드($VerifiedBuild)와 다른 빌드입니다($build). 호환성은 확인되지 않았지만 설치를 계속합니다." Yellow
}

# --- 삭제 모드 --------------------------------------------------------------
if($Uninstall){
  Head '앱 삭제'
  foreach($app in $Apps){
    $o = AdbWait 60 "$($app.name) 삭제" @('-s',$serial,'uninstall',$app.pkg)
    if($null -eq $o){ Stuck "$($app.name) 삭제(내비)" '차 화면에 확인 창이 떠 있으면 누르고, 없으면 시동을 껐다 켠 뒤 다시 실행하세요.' }
    if($o -match 'Success'){ Note "$($app.name): 삭제됨" Green; continue }
    # Not Success: either it was never installed or the uninstall failed. pm path tells the two apart.
    $p = AdbWait 30 "$($app.name) 확인" @('-s',$serial,'shell','pm','path',$app.pkg)
    if($null -ne $p -and $p -match 'package:'){ Die "$($app.name) 삭제 실패 (앱이 아직 차에 있습니다):`n$($o.Trim())" 1 }
    # pm path prints nothing for an app that is not there. Anything else (an adb error, no answer) is a lost connection, not a removed app.
    if($null -eq $p -or $p.Trim() -ne ''){ Die "차와 연결이 끊긴 것 같습니다. $($app.name) 삭제를 확인하지 못했습니다.`n케이블을 확인하고 다시 실행하세요.`n$("$p".Trim())" 1 }
    Note "$($app.name): 설치돼 있지 않음" Gray
  }
  Note '삭제 완료. 순정 상태로 돌아갔습니다(순정 시스템은 원래 건드리지 않습니다).' Green; UsbOffNote; Done
}

# --- 설치 -------------------------------------------------------------------
Head '4) 앱 설치'
# adb install 대신 ① 전송(push) ② 설치(pm install) 두 단계로 나눠, 멈추면 USB 쪽인지 내비 쪽인지 바로 갈린다.
$pushHint = 'USB로 파일을 보내다 멈췄습니다. 케이블·노트북 포트(USB 2.0 권장)를 바꾸고 Factory Test부터 다시 한 뒤 실행하세요.'
$pmHint = '내비가 설치를 끝내지 않았습니다. 차 화면에 확인 창이 떠 있으면 누르고, 없으면 시동을 껐다 켠 뒤 다시 실행하세요.'
foreach($app in $Apps){
  $apk = $app.path; $remote = "/data/local/tmp/$($app.apk)"
  # 내비는 설치한 APK를 바이트 그대로 보관한다: 받아 와서 같으면 같은 버전이니 건너뛴다.
  $pp = ((Adb '-s' $serial 'shell' 'pm' 'path' $app.pkg) -replace '\s','') -replace '^package:',''
  $have = Join-Path $LogDir "installed_$($app.apk)"; Remove-Item -LiteralPath $have -ErrorAction SilentlyContinue
  if($pp -match '^/.+\.apk$' -and $null -ne (AdbWait 60 "$($app.name) 설치된 파일 비교" @('-s',$serial,'pull',$pp,$have)) -and
     (Test-Path $have) -and (Get-FileHash $have).Hash -eq (Get-FileHash $apk).Hash){
    Note "$($app.name): 이미 같은 버전이 설치돼 있습니다 — 건너뜀" Green; continue
  }
  Note ("{0} ({1} KB)" -f $app.name, [math]::Round((Get-Item $apk).Length / 1KB)) White
  $o = AdbWait 60 '① 전송 (USB)' @('-s',$serial,'push',$apk,$remote)
  if($null -eq $o){ Stuck "$($app.name) ① 전송" $pushHint }
  if($o -match 'error|failed|No such'){ Die "$($app.name) 전송 실패:`n$($o.Trim())" 1 }
  $o = AdbWait 120 '② 설치 (내비)' @('-s',$serial,'shell','pm','install','-r',$remote)
  if($null -eq $o){ Stuck "$($app.name) ② 설치" $pmHint }
  # Only a signature clash is fixed by uninstalling; any other failure (storage full, ...) must not remove the working app.
  if($o -match 'INCONSISTENT_CERTIFICATES|UPDATE_INCOMPATIBLE'){
    Note "$($app.name): 기존에 다른 서명으로 설치돼 있습니다." Yellow
    if(Ask '지우고 다시 설치할까요? (앱 설정도 지워집니다)'){
      $u = AdbWait 60 '기존 앱 삭제' @('-s',$serial,'uninstall',$app.pkg)
      if($null -eq $u){ Stuck "$($app.name) 기존 앱 삭제" $pmHint }
      if($u -notmatch 'Success'){ Die "$($app.name) 기존 앱 삭제 실패:`n$($u.Trim())" 1 }
      $o = AdbWait 120 '② 설치 (내비)' @('-s',$serial,'shell','pm','install','-r',$remote)
      if($null -eq $o){ Stuck "$($app.name) ② 설치" $pmHint }
    }
  }
  AdbWait 15 '정리' @('-s',$serial,'shell','rm','-f',$remote) | Out-Null
  if($o -match 'Success'){ Note "$($app.name) 설치 완료" Green }
  else { Die "$($app.name) 설치 실패:`n$($o.Trim())" 1 }
}
Note "지금 차에 깔린 버전: $(Versions)" Green

# --- '내 앱' 실행 -----------------------------------------------------------
Head "5) '내 앱' 실행"
Adb '-s' $serial 'shell' 'am' 'start' '-n' 'com.veloster.carplayhub/.HubActivity' | Out-Null
Write-Host ''
Note "완료! 차 화면 '내 앱'에서 실행 바로가기를 켜면(docs/QUICK_ENTRY_ko.md)" Green
Note "노트북 없이 HOME 가장자리 버튼으로 다시 열 수 있습니다." Green
UsbOffNote
Done
