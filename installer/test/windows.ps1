# The Windows installer (install.ps1) without a car, against fakeadb.py, on Windows PowerShell 5.1 (GitHub Actions
# windows runner, or any Windows PC with Python). Mirrors run.sh: parse check of install.ps1 and gui.ps1, install, the
# same install again (skipped), an unverified build (refused), diagnostics (masked, read only), uninstall with the car
# gone and normally, -Gui waiting / ask no / ask yes / refusal (through redirected stdin, as gui.ps1 runs it), a tampered
# APK and a missing bundle.txt, and the update check / fetch / tampered fetch against fakerelease.py.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File installer\test\windows.ps1
$ErrorActionPreference = 'Continue'
$F = Split-Path -Parent $MyInvocation.MyCommand.Path; $I = Split-Path -Parent $F
$T = Join-Path ([IO.Path]::GetTempPath()) ("vn-wintest-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$Kit = Join-Path $T 'kit'; $Fake = Join-Path $T 'fake'; $Rel = Join-Path $T 'rel'
New-Item -ItemType Directory -Force $Kit, $Fake, $Rel | Out-Null
$PS = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$py = (Get-Command python -ErrorAction SilentlyContinue).Source; if(-not $py){ $py = (Get-Command py).Source }
$fails = 0; $script:out = ''; $script:rc = 0
function Ok([string]$name, [bool]$c){ if($c){ "PASS $name" } else { "FAIL $name"; $script:fails++; '----- out -----'; $script:out; '---------------'
  $lf = ([regex]::Match($script:out, '(?m)^@@FILE (.+)$')).Groups[1].Value.Trim(); if($lf -and (Test-Path -LiteralPath $lf)){ '----- log (asks) -----'; Get-Content -LiteralPath $lf -Encoding UTF8 | Where-Object { $_ -match '→' } } } }
function Calls(){ $f = Join-Path $Fake 'calls.log'; if(Test-Path $f){ @(Get-Content $f -Encoding UTF8) } else { @() } }
function Count($lines, [string]$pat){ @($lines | Where-Object { $_ -match $pat }).Count }
function Reset(){ foreach($p in 'inst','tmp','calls.log','clash.done'){ Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $Fake $p) } }
function Sha([string]$p){ (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() }

# the kit as a release ZIP holds it: the scripts, two APKs and bundle.txt naming their SHA-256
foreach($n in 'install.ps1','gui.ps1','N계기판설치.cmd','설치.cmd','삭제.cmd','진단기록.cmd'){ Copy-Item -LiteralPath (Join-Path $I $n) $Kit }
$rnd = New-Object Random
foreach($a in @(@('N-Dashboard-API17.apk', 170000), @('My-Apps-API17.apk', 9000))){ $b = New-Object byte[] $a[1]; $rnd.NextBytes($b); $b[0] = 0x50; $b[1] = 0x4B; [IO.File]::WriteAllBytes((Join-Path $Kit $a[0]), $b) }
$bundle = "# test bundle`n" + "N-Dashboard-API17.apk`tcom.veloster.dashboard`t6`t1.5-test`t$(Sha (Join-Path $Kit 'N-Dashboard-API17.apk'))`n" +
          "My-Apps-API17.apk`tcom.veloster.carplayhub`t10`t1.9-test`t$(Sha (Join-Path $Kit 'My-Apps-API17.apk'))`n"
[IO.File]::WriteAllText((Join-Path $Kit 'bundle.txt'), $bundle)
Copy-Item -LiteralPath (Join-Path $F 'fakeadb.py') $Fake
[IO.File]::WriteAllText((Join-Path $Fake 'adb.cmd'), "@`"$py`" -X utf8 `"%~dp0fakeadb.py`" %*`r`n")

# fake GitHub releases (latest = this installer's version unless a scenario says otherwise)
$cur = ([regex]::Match([IO.File]::ReadAllText((Join-Path $I 'install.ps1')), "\`$InstallerVersion = '([^']+)'")).Groups[1].Value
Set-Content -LiteralPath (Join-Path $Rel 'latest.txt') -Value $cur
$port = Get-Random -Minimum 20000 -Maximum 40000
$srv = Start-Process -FilePath $py -ArgumentList @('-I', "`"$(Join-Path $F 'fakerelease.py')`"", $port, "`"$Rel`"") -PassThru -WindowStyle Hidden
for($i = 0; $i -lt 50 -and -not (Test-Path (Join-Path $Rel 'server.log')); $i++){ Start-Sleep -Milliseconds 100 }
$env:VN_RELEASES = "http://127.0.0.1:$port"

function Run([string[]]$a, [string]$stdin = '', [hashtable]$envs = @{}, [string]$kit = $Kit){
  Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $Fake 'calls.log')
  foreach($k in 'FAKE_ABSENT','FAKE_BUILD','FAKE_GONE','FAKE_CLASH'){ Remove-Item "env:$k" -ErrorAction SilentlyContinue }
  foreach($k in $envs.Keys){ Set-Item "env:$k" $envs[$k] }
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $PS
  $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$kit\install.ps1`" -AdbPath `"$Fake\adb.cmd`" " + ($a -join ' ')
  $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.RedirectStandardInput = $true
  $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false); $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
  $psi.WorkingDirectory = $kit
  $p = [System.Diagnostics.Process]::Start($psi)
  $e = $p.StandardError.ReadToEndAsync()
  # answer each @@ASK only when it appears, as gui.ps1 does (an answer written up front can be read by an adb child)
  $answers = New-Object System.Collections.Queue; foreach($x in ($stdin -split "`n")){ if($x){ $answers.Enqueue($x) } }
  $sb = New-Object System.Text.StringBuilder
  while($null -ne ($l = $p.StandardOutput.ReadLine())){
    [void]$sb.AppendLine($l)
    if($l -match '^@@ASK '){ $ans = $(if($answers.Count){ $answers.Dequeue() } else { 'n' }); $p.StandardInput.WriteLine($ans); $p.StandardInput.Flush() }
  }
  try { $p.StandardInput.Close() } catch {}
  $script:out = $sb.ToString() + $e.Result
  $p.WaitForExit(); $script:rc = $p.ExitCode
}
function Last(){ ($script:out.TrimEnd() -split "`r?`n")[-1] }

foreach($f in "$Kit\install.ps1", "$Kit\gui.ps1"){
  $tok = $null; $errs = $null; [void][System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$tok, [ref]$errs)
  Ok "0 parse $(Split-Path -Leaf $f)" ($errs.Count -eq 0); if($errs){ $errs | ForEach-Object { $_.ToString() } }
}
Reset
Run @('-Yes'); Ok '1 install' ($rc -eq 0 -and (Count (Calls) 'pm install') -eq 2)
Run @('-Yes'); Ok '2 same install skipped' ($rc -eq 0 -and (Count (Calls) 'pm install') -eq 0 -and ([regex]::Matches($out, '건너뜀').Count -eq 2))
Run @('-Yes') -envs @{FAKE_BUILD='eng.vmadmin.20260114.000000'}; Ok '3 unverified build refused' ($rc -ne 0 -and (Count (Calls) 'pm install') -eq 0 -and $out -match '확인된 내비 빌드가 아닙니다')
Run @('-Yes','-Diag'); $d = ([regex]::Match($out, '만든 파일: (.+)')).Groups[1].Value.Trim()
Ok '4 diag file, read only' ($rc -eq 0 -and $d -and (Test-Path -LiteralPath $d) -and (Count (Calls) 'pm install|uninstall|service call|push') -eq 0)
if($d -and (Test-Path -LiteralPath $d)){ $dt = Get-Content -LiteralPath $d -Raw -Encoding UTF8
  Ok '4 diag masking' (-not ($dt -match '00:1A:7D|1234-5678|kim@example|FAKE123') -and $dt -match '<MAC>'); Remove-Item -LiteralPath $d }
Run @('-Yes','-Uninstall') -envs @{FAKE_GONE='1'}; Ok '5 uninstall with the car gone' ($rc -ne 0 -and $out -notmatch '삭제 완료')
Run @('-Yes','-Uninstall'); Ok '6 uninstall' ($rc -eq 0 -and $out -match '삭제 완료' -and (Count (Calls) 'service call') -eq 0)
Reset
Run @('-Gui') -envs @{FAKE_ABSENT='2'}
Ok '7 gui install' ($rc -eq 0 -and ([regex]::Matches($out, '(?m)^@@WAIT \d+ ').Count -ge 2) -and (Last) -eq '@@DONE ok' -and (Count (Calls) 'pm install') -eq 2 -and $out -notmatch 'Enter')
Reset
Run @('-Gui') "n`n" @{FAKE_CLASH='1'}; Ok '8 gui ask no' ($rc -ne 0 -and $out -match '(?m)^@@ASK ' -and (Count (Calls) 'uninstall') -eq 0 -and $out -match '(?m)^@@DONE fail')
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $Fake 'clash.done')
Run @('-Gui') "y`n" @{FAKE_CLASH='1'}; Ok '9 gui ask yes' ($rc -eq 0 -and (Count (Calls) 'uninstall com.veloster.dashboard') -ge 1 -and (Last) -eq '@@DONE ok')
Run @('-Gui') -envs @{FAKE_BUILD='eng.vmadmin.20260114.000000'}; Ok '10 gui refusal' ($rc -ne 0 -and $out -match '(?m)^@@DONE fail 확인된 내비 빌드가 아닙니다' -and $out -match '(?m)^@@FILE ')
$k2 = "$Kit-tamper"; Copy-Item -Recurse $Kit $k2
[IO.File]::AppendAllText("$k2\My-Apps-API17.apk", 'x')
Run @('-Yes') -kit $k2; Ok '11 tampered APK refused' ($rc -ne 0 -and $out -match 'SHA-256 불일치' -and (Count (Calls) 'push|pm ') -eq 0)
Remove-Item "$k2\bundle.txt"; Run @('-Yes') -kit $k2; Ok '12 missing bundle.txt refused' ($rc -ne 0 -and $out -match 'bundle.txt' -and (Count (Calls) 'push|pm ') -eq 0)
# update
Run @('-CheckUpdate'); Ok '13 no update when current' ($rc -eq 0 -and -not $out.Trim())
Set-Content -LiteralPath (Join-Path $Rel 'latest.txt') -Value '9.9.9'
Run @('-CheckUpdate'); Ok '14 newer release named' ($out.Trim() -eq '@@UPDATE 9.9.9')
$z = Join-Path $Rel 'vn-installer-windows.zip'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zs = [IO.Compression.ZipFile]::Open($z, 'Create'); $en = $zs.CreateEntry('N계기판설치.cmd'); $w = New-Object IO.StreamWriter($en.Open()); $w.Write("@echo new 9.9.9`r`n"); $w.Close(); $zs.Dispose()
Set-Content -LiteralPath (Join-Path $Rel 'SHA256SUMS.txt') -Value "$(Sha $z)  vn-installer-windows.zip"
Run @('-Gui','-Update'); $o = ([regex]::Match($out, '(?m)^@@OPEN (.+)$')).Groups[1].Value.Trim()
Ok '15 update fetched and named' ($rc -eq 0 -and $o -and (Test-Path -LiteralPath $o) -and (Get-Content -LiteralPath $o -Raw) -match '9\.9\.9' -and (Last) -eq '@@DONE ok')
$updDir = Join-Path $env:LOCALAPPDATA 'vn-installer\update\9.9.9'; Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $updDir
Set-Content -LiteralPath (Join-Path $Rel 'SHA256SUMS.txt') -Value ("0" * 64 + "  vn-installer-windows.zip")
Run @('-Gui','-Update'); Ok '16 tampered update refused' ($rc -ne 0 -and $out -match '(?m)^@@DONE fail 받은 파일의 SHA-256' -and $out -notmatch '(?m)^@@OPEN' -and -not (Test-Path -LiteralPath $updDir))

Stop-Process -Id $srv.Id -Force -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $T
"RESULT fails=$fails"
if($fails){ exit 1 }
