# 벨로스터 N 계기판 설치기 (Windows · macOS · 리눅스 · 안드로이드)

차주가 더블클릭으로 실행하는 설치기입니다. **N 계기판**과 **‘내 앱’** 두 앱을 설치·삭제하고, 문제를 알릴 때 쓸 진단 기록을 만듭니다. 두 앱은 **설치기 안에 들어 있어** 따로 받지 않고, 인터넷이 없어도 됩니다. i30 PD 포팅([sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard), MIT)의 설치기를 벨로스터 N JSN에 맞게 고친 것입니다.

> 현대자동차 공식 앱이 아닙니다. 정차·시동 켠 상태에서 하세요. 설치는 **product `jsn_kr` · API 17 · `eng.vmadmin.20250902.081552`** 내비에서만 진행하고, 다른 빌드에서는 설치하지 않고 멈춥니다([설치 안내](../docs/INSTALL.md)와 같은 원칙). 삭제와 진단 기록은 빌드와 상관없이 됩니다.

## 받기

[Releases](https://github.com/reikop/veloster-n-dashboard/releases/latest)에서 내 기기에 맞는 것 **하나만** 받습니다. 릴리스에는 설치기만 올라가고 앱 APK는 따로 올라가지 않습니다.

| 파일 | 기기 | 여는 것 |
| --- | --- | --- |
| `vn-installer-windows.zip` | Windows | `N계기판설치.cmd` (창 설치기), 콘솔은 `설치.cmd` |
| `vn-installer-mac.zip` | 맥 | `N 계기판 설치.app` (창 설치기), 콘솔은 `설치.command` |
| `vn-installer-linux.zip` | 리눅스·라즈베리파이 | `bash install.sh` (콘솔) |
| `vn-installer-android.apk` | 안드로이드 폰 8.0+ | 앱 **N 계기판 설치** |
| `SHA256SUMS.txt` | | 위 파일들의 SHA-256 |

테스트 버전(실차 검증 전)은 `test` 사전 배포에 있는 다른 설치기입니다. 설치기는 안에 든 버전만 설치합니다.

## 쓰는 법 (창 설치기: Windows · 맥)

1. ZIP을 풉니다(ZIP 안에서 바로 실행하지 마세요).
2. Windows는 `N계기판설치.cmd`(노란 보안 경고가 뜨면 [실행]), 맥은 `N 계기판 설치.app`을 엽니다. 맥은 처음 한 번 “Apple에서 확인할 수 없습니다” 창이 뜹니다(임시 서명). 시스템 설정 → 개인정보 보호 및 보안 → [그래도 열기].
3. 창 위쪽에 `이 설치기에 든 버전: N 계기판 … · '내 앱' …`이 나옵니다. 창의 안내대로 차에서 엔지니어링 모드 → Diagnosis → Factory Test에서 `0A 01` → `65 01` → `0A 02`를 한 줄씩 보내고([ADB 연결 안내](../docs/ADB.md); 세 줄은 [복사]로 옮길 수 있음) USB를 연결합니다. ‘내 앱’이 이미 있으면 ‘내 앱’ → USB 디버깅 → ON으로 대신할 수 있습니다.
4. [설치] / [삭제] / [진단 기록] 중 하나를 누릅니다. 차를 5분까지 기다리며 경과 초를 보여 주고, 기록 칸에 진행이 나옵니다. 설치기가 차(`product:jsn_kr`, 상태 `device`)만 골라 두 앱을 설치하고 ‘내 앱’을 엽니다. 다른 기기(휴대폰 등)는 건드리지 않습니다.
5. 끝나면 초록(완료)·빨강(중단) 띠에 결과와 USB 디버깅 끄는 법이 나옵니다. [취소]로 언제든 멈출 수 있습니다.

- **삭제**: 두 앱만 지웁니다(순정 시스템은 설치 때도 건드리지 않음). ‘내 앱’에서 ☆ 버튼을 계기판으로 써 왔다면 지우기 전에 ‘내 앱’에서 ☆를 끄세요. 이미 지웠다면 ☆를 길게 눌러 나오는 순정 설정에서 원래 기능을 고르면 됩니다.
- **진단 기록**: 차에는 아무것도 쓰지 않고 정해 둔 속성, 두 앱의 버전, 우리 앱 로그만 읽어 MAC·전화번호·메일·기기번호를 가린 뒤 바탕화면에 `vn_진단기록_<시각>.txt`로 저장합니다([파일 보기]).
- **USB 디버깅 끄기**: 시동을 껐다 켜면 꺼집니다. 바로 끄려면 Factory Test에서 `0A 01` → `65 00` → `0A 02`, 또는 ‘내 앱’ → USB 디버깅 → OFF.

## 콘솔 설치기 (대체 방법, 리눅스는 이것만)

- Windows: `설치.cmd` / `삭제.cmd` / `진단기록.cmd`. macOS: `설치.command` / `삭제.command` / `진단기록.command`(또는 터미널에서 `bash 설치.command`). 리눅스: `bash install.sh` / `bash uninstall.sh` / `bash diag.sh`.
- 창 설치기와 같은 스크립트(`install.ps1`, `설치.command`)를 터미널에서 직접 돌리는 것입니다. 단계마다 Enter와 Y/n을 묻습니다.

## 휴대폰으로 하기 (안드로이드)

`installer/android/`는 노트북 대신 안드로이드 폰(8.0 이상)이 OTG로 차의 adb 호스트가 되는 설치 앱입니다(i30 PD 포팅의 휴대폰 설치기를 고친 것). 두 앱 APK와 `bundle.txt`가 앱 안(`assets/bundle/`)에 들어 있고, 노트북 설치기와 같은 조건(`jsn_kr` · API 17 · 검증 빌드, 삭제는 빌드 무관)을 씁니다.

- 준비물: OTG 어댑터(USB-C 수 → USB-A 암) + USB A–A 케이블. 앱 설치에는 "출처를 알 수 없는 앱" 허용이 필요합니다.
- [설치] / [삭제] / [진단 기록]. 폰의 USB 접근 허용 창은 [확인], 차의 "USB 디버깅 허용" 창은 [항상 허용].
- [삭제]는 두 앱만 지웁니다. 진단 기록은 `vn_진단기록_<시각>.txt`로 같은 항목을 가린 뒤 공유 화면을 띄웁니다.
- 새 휴대폰 설치 앱이 나오면(레포 `main`의 `Flow.java` `VERSION`이 더 새것) 위쪽 주황 띠의 [업데이트]가 최신 릴리스의 `vn-installer-android.apk`를 받아 폰의 설치 화면을 띄웁니다. 받은 APK의 versionCode가 더 크지 않으면 설치하지 않습니다. 릴리스마다 `VERSION`을 올립니다.

## 동작

- **동봉 APK**: 설치기 옆(맥 창 설치기는 앱 안 `Contents/Resources/kit/`, 폰은 `assets/bundle/`)의 `N-Dashboard-API17.apk` · `My-Apps-API17.apk`만 설치합니다. 함께 든 `bundle.txt`(파일·패키지·versionCode·versionName·SHA-256, `scripts/apk_info.py`가 만듦)와 패키지나 SHA-256이 다르면 차에 아무것도 하지 않고 멈춥니다.
- **adb**: 옆의 `platform-tools`, `PATH` 순서로 찾고, 없으면 묻고 나서 Google 공식 platform-tools를 받습니다(ARM 리눅스는 `apt`). 인터넷은 이때만 필요합니다.
- **같은 버전 건너뛰기**: 차의 APK를 받아 비교해 같으면 설치하지 않습니다.
- **서명 충돌**(`INCONSISTENT_CERTIFICATES`): 묻고 나서 지우고 다시 설치합니다(창 설치기는 예/아니오 창). 이때 앱 설정이 지워지지만 N 계기판의 [설정 백업](../docs/INSTALL.md)이 내비 저장소에 남아 있으면 새로 설치한 뒤 되돌릴지 묻습니다.
- 설치는 ① 전송(`adb push`) ② 설치(`pm install -r`)로 나눠 멈춘 단계를 보여 줍니다.
- **새 설치기 알림**: 인터넷이 되면 레포 `main`의 설치기 버전과 비교해 더 새것이면 릴리스 주소만 알립니다(자동으로 받지 않음).
- 기록: Windows `%LOCALAPPDATA%\vn-installer\`, macOS `~/Library/Logs/vn-installer/`, 리눅스 `~/.local/state/vn-installer/`.

### 창 설치기의 구조

창 설치기는 화면만 맡고 설치 규칙은 콘솔 스크립트에 그대로 있습니다. `gui.ps1`(Windows 기본 `powershell.exe` + WinForms, 외부 모듈 없음)과 `mac/gui.js`(JXA, `osacompile`로 만든 stay-open 앱)는 `install.ps1 -Gui` / `설치.command --gui`를 숨은 프로세스로 돌리고 출력을 줄 단위로 읽습니다. `-Gui`/`--gui`는 Enter를 기다리지 않고 차를 스스로 기다리며 아래 상태 줄을 냅니다.

| 줄 | 뜻 |
| --- | --- |
| `@@STEP <제목>` | 단계 시작 |
| `@@WAIT <초> <안내>` | 기다리는 중(창이 경과 초를 이어서 셈) |
| `@@ASK <질문>` | 예/아니오 창을 띄우고 `y`/`n` 한 줄을 표준 입력으로 보냄(adb 받기, 서명 충돌 재설치 등). 답이 없으면 아니오 |
| `@@FILE <경로>` | 진단 기록·기록 파일 |
| `@@DONE ok` / `@@DONE fail <내용>` | 끝 |

`jsn_kr`이 아닌 기기·다른 빌드·동봉 APK 불일치는 `-Gui`에서도 그대로 멈춥니다.

## 배포 (관리자)

```sh
python3 scripts/package_installer.py --apks DIR --out DIR/assets [--android-apk build/vn-installer-android.apk]
```

- `--apks`(필수): `N-Dashboard-API17.apk` · `My-Apps-API17.apk`가 있는 폴더. 없거나 패키지가 다르면 아무것도 만들지 않습니다. 세 ZIP에 두 APK와 `bundle.txt`가 들어가고, 맥 ZIP에는 `N 계기판 설치.app`(맥에서만 만들 수 있음: `osacompile`, `codesign -s -`)이 들어갑니다.
- 휴대폰 설치 앱은 먼저 따로 빌드합니다: `ANDROID_HOME`(build-tools 34.0.0 · platforms android-34), `JAVA_HOME`(JDK 17), `APKS`(위 폴더), `KS`(저장소 밖 릴리스 키, PKCS12)와 `KS_PASS` 또는 `KS_PASS_FILE`을 주고 `bash installer/android/build.sh` → `build/vn-installer-android.apk`. 모든 버전을 같은 키로 서명해야 폰이 업데이트를 받습니다.
- 결과: `vn-installer-windows.zip`, `vn-installer-mac.zip`, `vn-installer-linux.zip`, (`vn-installer-android.apk`), `SHA256SUMS.txt`. 이것만 릴리스에 올립니다.
- `install.ps1`·`gui.ps1`은 UTF-8 BOM + CRLF로 유지합니다(Windows PowerShell 5.1).

## 시험 (차 없이)

```sh
bash installer/test/run.sh mac            # 또는 linux. 콘솔과 --gui 상태 줄
bash installer/android/test/test.sh       # 휴대폰 설치 앱(JDK 17)
python3 -m unittest tests/test_installer_packaging.py
```

- `run.sh`: 가짜 adb(`test/fakeadb.py`)로 설치, 같은 버전 건너뛰기, 새 버전 설치, 검증되지 않은 빌드 거부, 진단 기록(가림·읽기만), 연결 끊김 중 삭제, 삭제, 바뀐 동봉 APK·`bundle.txt` 없음 거부, `--gui`(차 기다림 `@@WAIT`, `@@ASK`에 아니오/예, `@@DONE`)를 확인합니다.
- `android/test/test.sh`: 가짜 adbd로 새 키 설치, 서명 충돌 재설치, 삭제, `jsn_kr`이 아닌 기기·다른 빌드에서 멈춤, 같은 버전 건너뛰기, 가린 진단 기록, 바뀐 동봉 APK·`bundle.txt` 없음 거부를 확인합니다.
- `test_installer_packaging.py`: APK 없이는 포장하지 않음, 패키지가 다른 APK 거부, `bundle.txt` 내용, (맥에서) `.app` 구조·서명·`plutil`과 창을 띄웠다 닫는 시험.
- 2026-11-24 macOS에서 위 시험이 통과했고, 맥 창 설치기는 가짜 adb로 설치·건너뛰기·진단 기록·서명 충돌(아니오/예)·삭제를 끝까지 확인했습니다. `install.ps1 -Gui`와 `gui.ps1`(Windows)은 이 환경에 PowerShell이 없어 실행·구문 검사를 하지 못했습니다. 실제 차·폰·Windows에서는 아직 이 설치기로 설치해 보지 않았습니다.
