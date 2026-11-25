<#
  벨로스터 N 계기판 창 설치기 — Windows. 사용자는 "N계기판설치.cmd"를 더블클릭한다.
  창 하나(콘솔·Enter 없음)에서 [설치] / [삭제] / [진단 기록]을 누르면, 같은 폴더의 install.ps1을 -Gui로 숨은 창에서 돌리고
  그 출력을 줄 단위로 읽어 기록 칸에 보여 준다. 설치 규칙은 모두 install.ps1에 있고 이 파일은 화면만 맡는다.
  install.ps1 -Gui 상태 줄: @@STEP <제목>  @@WAIT <초> <안내>  @@ASK <질문>(예/아니오 창 → y/n을 표준 입력으로)  @@FILE <경로>  @@DONE ok|fail <내용>
  업데이트: 창이 뜨면 install.ps1 -CheckUpdate를 숨은 창에서 돌려 '@@UPDATE <버전>'이 오면 [업데이트] 단추를 보인다. 누르면 -Gui -Update가
  새 설치기를 받아 확인하고 '@@OPEN <새 N계기판설치.cmd>'를 알리며, 이 창은 그것을 열고 닫힌다.
  Windows 기본 powershell.exe(5.1) + WinForms만 쓴다. 이 파일은 UTF-8 BOM + CRLF로 둔다(5.1이 한글을 읽도록).
#>
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$Script = Join-Path $ROOT 'install.ps1'
$UsbOff = "USB 디버깅 끄기: 시동을 껐다 켜면 저절로 꺼집니다. 바로 끄려면 Factory Test에서 0A 01 → 65 00 → 0A 02 (또는 '내 앱' → USB 디버깅 → OFF)"

# 이 설치기에 든 버전 (bundle.txt: 파일 패키지 versionCode versionName sha256)
$bundled = '앱 목록(bundle.txt)이 없습니다. 설치기 ZIP을 다시 받아 압축을 푸세요.'
$bf = Join-Path $ROOT 'bundle.txt'
if(Test-Path -LiteralPath $bf){
  $v = @{}
  foreach($l in (Get-Content -LiteralPath $bf -Encoding UTF8)){ if($l -and -not $l.StartsWith('#')){ $f = $l -split "`t"; if($f.Count -ge 5){ $v[$f[0]] = $f[3] } } }
  $bundled = "N 계기판 $($v['N-Dashboard-API17.apk']) · '내 앱' $($v['My-Apps-API17.apk'])"
}

$font = New-Object System.Drawing.Font('Malgun Gothic', 10)
$bold = New-Object System.Drawing.Font('Malgun Gothic', 10, [System.Drawing.FontStyle]::Bold)
$form = New-Object System.Windows.Forms.Form
$form.Text = '벨로스터 N 계기판 설치'
$form.Font = $font
$form.ClientSize = New-Object System.Drawing.Size(720, 760)
$form.MinimumSize = New-Object System.Drawing.Size(600, 600)
$form.StartPosition = 'CenterScreen'

$layout = New-Object System.Windows.Forms.TableLayoutPanel
$layout.Dock = 'Fill'; $layout.Padding = New-Object System.Windows.Forms.Padding(12); $layout.ColumnCount = 1
$form.Controls.Add($layout)
function Row($control, [string]$sizeType = 'AutoSize', [float]$h = 0){
  $control.Dock = 'Fill'; $control.Margin = New-Object System.Windows.Forms.Padding(0, 4, 0, 4)
  $style = New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::$sizeType, $h)
  # 줄 번호를 정해 넣는다. 자동 배치에 맡기면 처음에 숨긴 완료 칸($banner)이 자리를 못 받고, 나중에 보일 때
  # 기록 칸 뒤의 '남은 공간 전부' 줄로 밀려 기록 칸이 한 줄로 찌그러진다(2026-11-24 Windows 실기 화면).
  $layout.RowCount = $layout.RowStyles.Add($style) + 1; $layout.Controls.Add($control, 0, $layout.RowCount - 1)
}
function Label([string]$t, $f = $font, $color = [System.Drawing.Color]::Black){
  $l = New-Object System.Windows.Forms.Label; $l.Text = $t; $l.Font = $f; $l.ForeColor = $color; $l.AutoSize = $true
  $l.MaximumSize = New-Object System.Drawing.Size(690, 0); return $l
}

Row (Label '벨로스터 N 계기판 설치' (New-Object System.Drawing.Font('Malgun Gothic', 15, [System.Drawing.FontStyle]::Bold)))
Row (Label "이 설치기에 든 버전: $bundled" $bold ([System.Drawing.Color]::FromArgb(0, 90, 140)))
Row (Label '현대자동차 공식 앱이 아닙니다. 보증/AS 불이익이 있을 수 있고 책임은 본인에게 있습니다. 정차 · 시동 켠 상태에서 하세요.' $font ([System.Drawing.Color]::FromArgb(150, 90, 0)))

# 1·2단계: 차 화면에서 사람이 직접
$steps = New-Object System.Windows.Forms.GroupBox; $steps.Text = '차 화면에서 먼저 (1·2단계, 직접)'; $steps.AutoSize = $true
$sl = New-Object System.Windows.Forms.TableLayoutPanel; $sl.Dock = 'Fill'; $sl.AutoSize = $true; $sl.ColumnCount = 2
[void]$sl.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
[void]$sl.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::AutoSize)))
$stepText = Label ("1. 설정 → SW 정보/업데이트 → 아래 업데이트 버튼 줄의 왼쪽 숨은 영역 5번 → 오른쪽 1번 (10초 안에)`n" +
  "   암호: 2026년 0567 (2027년 1278)`n" +
  "2. Diagnosis → Factory Test 에서 오른쪽 세 줄을 한 줄씩 입력하고 매번 SEND`n" +
  "3. USB 케이블로 차와 이 PC를 연결한 뒤 아래 [설치]`n" +
  "   ('내 앱'이 이미 있으면 '내 앱' → USB 디버깅 → ON 으로 대신할 수 있습니다)")
$stepText.MaximumSize = New-Object System.Drawing.Size(520, 0)
$codes = New-Object System.Windows.Forms.TextBox
$codes.Multiline = $true; $codes.ReadOnly = $true; $codes.Text = "0A 01`r`n65 01`r`n0A 02"
$codes.Font = New-Object System.Drawing.Font('Consolas', 13, [System.Drawing.FontStyle]::Bold); $codes.Size = New-Object System.Drawing.Size(110, 80)
$copy = New-Object System.Windows.Forms.Button; $copy.Text = '복사'; $copy.AutoSize = $true
$copy.Add_Click({ [System.Windows.Forms.Clipboard]::SetText("0A 01`r`n65 01`r`n0A 02"); $copy.Text = '복사됨' })
$codeBox = New-Object System.Windows.Forms.FlowLayoutPanel; $codeBox.FlowDirection = 'TopDown'; $codeBox.AutoSize = $true
$codeBox.Controls.Add($codes); $codeBox.Controls.Add($copy)
$sl.Controls.Add($stepText, 0, 0); $sl.Controls.Add($codeBox, 1, 0)
$steps.Controls.Add($sl)
Row $steps

# 단추
$buttons = New-Object System.Windows.Forms.FlowLayoutPanel; $buttons.AutoSize = $true
function Btn([string]$t){ $b = New-Object System.Windows.Forms.Button; $b.Text = $t; $b.Size = New-Object System.Drawing.Size(120, 40); $buttons.Controls.Add($b); return $b }
$installBtn = Btn '설치'; $installBtn.Font = $bold; $installBtn.BackColor = [System.Drawing.Color]::FromArgb(61, 220, 132)
$removeBtn = Btn '삭제'; $diagBtn = Btn '진단 기록'; $cancelBtn = Btn '취소'; $cancelBtn.Enabled = $false
$fileBtn = Btn '파일 보기'; $fileBtn.Visible = $false
$updateBtn = Btn '업데이트'; $updateBtn.Visible = $false; $updateBtn.BackColor = [System.Drawing.Color]::FromArgb(255, 214, 102)
Row $buttons

$status = Label '' $bold ([System.Drawing.Color]::FromArgb(180, 100, 0))
Row $status
$banner = Label '' $bold; $banner.Visible = $false; $banner.Padding = New-Object System.Windows.Forms.Padding(8)
Row $banner

$log = New-Object System.Windows.Forms.TextBox
$log.Multiline = $true; $log.ReadOnly = $true; $log.ScrollBars = 'Vertical'; $log.WordWrap = $true
$log.Font = New-Object System.Drawing.Font('Malgun Gothic', 9); $log.BackColor = [System.Drawing.Color]::White
Row $log 'Percent' 100
$log.AppendText("[설치]는 차(jsn_kr · 검증 빌드)에만 두 앱을 설치합니다. 차가 아닌 기기·다른 빌드에는 아무것도 하지 않습니다.`r`n" +
  "[진단 기록]은 차를 읽기만 해서 문제를 알릴 때 보낼 파일을 바탕화면에 만듭니다.`r`n")

# --- 자식 프로세스 (install.ps1 -Gui) ---------------------------------------------
$script:proc = $null; $script:outTask = $null; $script:errTask = $null; $script:done = $false; $script:file = $null
$script:waitMsg = $null; $script:waitBase = 0; $script:waitAt = [Diagnostics.Stopwatch]::new()

function Say([string]$m){ $log.AppendText($m + "`r`n") }
function Idle([bool]$on){ $installBtn.Enabled = $on; $removeBtn.Enabled = $on; $diagBtn.Enabled = $on; $updateBtn.Enabled = $on; $cancelBtn.Enabled = -not $on }
function Finish([bool]$ok, [string]$msg){
  if($script:done){ return }; $script:done = $true
  $status.Text = ''; $script:waitMsg = $null
  $banner.BackColor = $(if($ok){ [System.Drawing.Color]::FromArgb(210, 245, 220) } else { [System.Drawing.Color]::FromArgb(250, 215, 210) })
  $banner.Text = $(if($ok){ "완료했습니다. $msg" } else { "중단했습니다: $msg`n기록 칸 내용과 기록 파일을 함께 보내 주시면 원인을 볼 수 있습니다." }) + "`n$UsbOff"
  $banner.Visible = $true
  Idle $true
  # 업데이트를 받았으면 새 설치기를 열고 이 창은 닫는다.
  if($ok -and $script:mode -eq 'update' -and $script:openPath){
    try { Start-Process -FilePath $script:openPath -WorkingDirectory (Split-Path -Parent $script:openPath); $form.Close() } catch { Say "새 설치기를 열지 못했습니다: $($_.Exception.Message)`r`n직접 여세요: $($script:openPath)" }
  }
}
function Line([string]$l){
  $l = $l.TrimStart([char]0xFEFF).TrimEnd("`r")
  if($l -match '^@@STEP (.*)$'){ $status.Text = $Matches[1]; $script:waitMsg = $null; return }
  if($l -match '^@@WAIT (\d+) (.*)$'){ $script:waitBase = [int]$Matches[1]; $script:waitMsg = $Matches[2]; $script:waitAt.Restart(); return }
  if($l -match '^@@ASK (.*)$'){
    $r = [System.Windows.Forms.MessageBox]::Show($form, $Matches[1], '확인', 'YesNo', 'Question')
    $a = $(if($r -eq 'Yes'){ 'y' } else { 'n' }); Say "$($Matches[1]) → $(if($a -eq 'y'){ '예' } else { '아니오' })"
    # ASCII 바이트로 직접 쓴다: StandardInput(StreamWriter)은 콘솔 입력 인코딩이 UTF-8이면 첫 쓰기에 BOM을 붙인다.
    try { $b = [Text.Encoding]::ASCII.GetBytes("$a`r`n"); $script:proc.StandardInput.BaseStream.Write($b, 0, $b.Length); $script:proc.StandardInput.BaseStream.Flush() } catch {}
    return
  }
  if($l -match '^@@FILE (.*)$'){ $script:file = $Matches[1]; $fileBtn.Visible = $true; return }
  if($l -match '^@@OPEN (.*)$'){ $script:openPath = $Matches[1]; return }
  if($l -match '^@@DONE ok'){ Finish $true $(if($script:mode -eq 'diag'){ '진단 기록 파일을 만들었습니다([파일 보기]).' } elseif($script:mode -eq 'uninstall'){ '두 앱을 지웠습니다.' } elseif($script:mode -eq 'update'){ if($script:openPath){ '새 설치기를 엽니다.' } else { '이미 최신 설치기입니다.' } } else { "차 화면에 '내 앱'이 열립니다." }); return }
  if($l -match '^@@DONE fail ?(.*)$'){ Finish $false $Matches[1]; return }
  $script:waitMsg = $null
  Say $l
}
function Start-Run([string]$mode){
  $script:mode = $mode; $script:done = $false; $script:file = $null; $script:openPath = $null; $fileBtn.Visible = $false; $banner.Visible = $false
  $copy.Text = '복사'; $log.Clear(); Idle $false
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
  $extra = $(if($mode -eq 'uninstall'){ ' -Uninstall' } elseif($mode -eq 'diag'){ ' -Diag' } elseif($mode -eq 'update'){ ' -Update' } else { '' })
  $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Script`" -Gui$extra"
  $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.RedirectStandardInput = $true
  $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
  $psi.WorkingDirectory = $ROOT
  try { $script:proc = [System.Diagnostics.Process]::Start($psi) } catch { Say "install.ps1을 시작하지 못했습니다: $($_.Exception.Message)"; Finish $false '시작 실패'; return }
  $script:outTask = $script:proc.StandardOutput.ReadLineAsync(); $script:errTask = $script:proc.StandardError.ReadLineAsync()
  $timer.Start()
}

# 화면 스레드에서 0.2초마다: 다 읽힌 줄을 처리하고 다음 줄을 기다린다(창이 멈추지 않게). 기다리는 줄에는 경과 초를 붙인다.
$timer = New-Object System.Windows.Forms.Timer; $timer.Interval = 200
# @@ASK의 예/아니오 창이 떠 있는 동안에도 타이머는 돈다. 다시 들어오지 않게 막고, 줄을 처리하기 전에 다음 줄 읽기를 건다.
# (막지 않으면 같은 @@ASK 줄로 질문 창이 계속 겹쳐 뜨다 '호출 수준 오버플로'로 죽었다 — 2026-11-24 Windows 실기 화면)
$script:inTick = $false
$timer.Add_Tick({
  if($script:inTick){ return }; $script:inTick = $true
  try {
  if($script:proc){
    for($i = 0; $i -lt 200 -and $script:outTask -and $script:outTask.IsCompleted; $i++){
      $l = $script:outTask.Result
      if($null -eq $l){ $script:outTask = $null; break }
      $script:outTask = $script:proc.StandardOutput.ReadLineAsync(); Line $l
      if(-not $script:proc){ break }
    }
    if(-not $script:proc){ return }
    for($i = 0; $i -lt 200 -and $script:errTask -and $script:errTask.IsCompleted; $i++){
      $l = $script:errTask.Result
      if($null -eq $l){ $script:errTask = $null; break }
      if($l.Trim()){ Say $l }; $script:errTask = $script:proc.StandardError.ReadLineAsync()
    }
    if(-not $script:outTask -and -not $script:errTask -and $script:proc.HasExited){
      # 타이머는 멈추지 않는다(창이 뜰 때 시작한 새 설치기 확인도 이 타이머가 본다).
      if(-not $script:done){ Finish $false "설치기가 끝났습니다(종료 코드 $($script:proc.ExitCode))." }
      $script:proc = $null
    }
  }
  if($script:waitMsg){ $status.Text = "⏳ $($script:waitMsg)  $($script:waitBase + [int]$script:waitAt.Elapsed.TotalSeconds)초" }
  # 새 설치기 확인(창이 뜰 때 한 번): 끝나면 '@@UPDATE <버전>'이 있는지 본다.
  if($script:chk -and $script:chk.HasExited){
    $o = ''; try { $o = $script:chkOut.Result } catch {}
    $script:chk = $null
    if($o -match '@@UPDATE (\S+)'){ $script:newVersion = $Matches[1]; $updateBtn.Text = "업데이트 $($Matches[1])"; $updateBtn.Visible = $true
      Say "새 설치기 $($Matches[1])이(가) 있습니다. [업데이트 $($Matches[1])]를 누르면 받아서 확인한 뒤 새 설치기를 엽니다." }
  }
  } finally { $script:inTick = $false }
})

$installBtn.Add_Click({ Start-Run 'install' })
$updateBtn.Add_Click({
  $q = "새 설치기 $($script:newVersion)을(를) 받습니다.`n`n릴리스 목록(SHA256SUMS.txt)으로 파일을 확인한 뒤 새 설치기를 열고 이 창은 닫습니다. 차에는 아무것도 하지 않습니다.`n`n받을까요?"
  if([System.Windows.Forms.MessageBox]::Show($form, $q, '업데이트', 'YesNo', 'Question') -eq 'Yes'){ Start-Run 'update' }
})
# 창이 뜨면 숨은 창에서 새 설치기를 확인한다(인터넷이 없으면 아무 일도 없다).
$script:chk = $null; $script:chkOut = $null; $script:newVersion = ''
$form.Add_Shown({
  try {
    $c = New-Object System.Diagnostics.ProcessStartInfo
    $c.FileName = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $c.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Script`" -CheckUpdate"
    $c.UseShellExecute = $false; $c.CreateNoWindow = $true; $c.RedirectStandardOutput = $true
    $c.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false); $c.WorkingDirectory = $ROOT
    $script:chk = [System.Diagnostics.Process]::Start($c); $script:chkOut = $script:chk.StandardOutput.ReadToEndAsync()
  } catch {}
  $timer.Start()
})
$diagBtn.Add_Click({ Start-Run 'diag' })
$removeBtn.Add_Click({
  $q = "차에서 N 계기판과 '내 앱', 그 설정을 지웁니다.`n`n'내 앱'에서 ☆ 버튼을 계기판으로 써 왔다면 지우기 전에 '내 앱'에서 ☆를 끄세요. " +
       "이미 지웠다면 ☆를 길게 눌러 나오는 순정 설정에서 원래 기능을 고르면 됩니다.`n`n지울까요?"
  if([System.Windows.Forms.MessageBox]::Show($form, $q, '삭제', 'YesNo', 'Warning') -eq 'Yes'){ Start-Run 'uninstall' }
})
function Stop-Child(){ if($script:proc -and -not $script:proc.HasExited){ & taskkill.exe /T /F /PID $script:proc.Id *> $null } }
$cancelBtn.Add_Click({ Stop-Child; Say '취소했습니다. 설치 중이었다면 차에서는 그 단계가 끝날 수 있습니다. 다시 실행하면 이어서 맞춥니다.'; Finish $false '취소' })
$fileBtn.Add_Click({ if($script:file){ Start-Process explorer.exe "/select,`"$($script:file)`"" } })
$form.Add_FormClosing({ Stop-Child })

if($env:VN_GUI_SMOKE){ $form.Add_Shown({ $t = New-Object System.Windows.Forms.Timer; $t.Interval = 1500; $t.Add_Tick({ $form.Close() }); $t.Start() }) }   # 시험: 창이 뜨면 닫는다
[void]$form.ShowDialog()
