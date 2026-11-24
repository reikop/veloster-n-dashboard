# 벨로스터 N 계기판 설치기 (Windows · macOS · 리눅스)

차주가 노트북에서 더블클릭으로 실행하는 설치기입니다. **N 계기판**과 **‘내 앱’** 두 앱을 설치·삭제하고, 문제를 알릴 때 쓸 진단 기록을 만듭니다. i30 PD 포팅([sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard), MIT)의 설치기를 벨로스터 N JSN에 맞게 고친 것입니다.

> 현대자동차 공식 앱이 아닙니다. 정차·시동 켠 상태에서 하세요. 설치는 **product `jsn_kr` · API 17 · `eng.vmadmin.20250902.081552`** 내비에서만 진행하고, 다른 빌드에서는 설치하지 않고 멈춥니다([설치 안내](../docs/INSTALL.md)와 같은 원칙). 삭제와 진단 기록은 빌드와 상관없이 됩니다.

## 쓰는 법

1. ZIP을 풉니다(`vn-installer-windows.zip` / `-mac.zip` / `-linux.zip`).
2. 실행합니다.
   - Windows: `설치.cmd` 더블클릭. 노란 보안 경고가 뜨면 [실행].
   - macOS: `설치.command` 더블클릭. “확인되지 않은 개발자” 창이 뜨면 시스템 설정 → 개인정보 보호 및 보안 → [그래도 열기]. 또는 터미널에서 `bash 설치.command`.
   - 리눅스: `bash install.sh`.
3. 화면 안내대로 차에서 엔지니어링 모드 → Diagnosis → Factory Test에서 `0A 01` → `65 01` → `0A 02`를 한 줄씩 보내고 USB를 연결한 뒤 Enter([ADB 연결 안내](../docs/ADB.md)). ‘내 앱’이 이미 있으면 ‘내 앱’ → USB 디버깅 → ON으로 대신할 수 있습니다.
4. 설치기가 차(`product:jsn_kr`, 상태 `device`)만 골라 두 앱을 설치하고 ‘내 앱’을 엽니다. 다른 기기(휴대폰 등)는 건드리지 않습니다.

- **삭제**: `삭제.cmd` / `삭제.command` / `bash uninstall.sh`. 두 앱만 지웁니다(순정 시스템은 설치 때도 건드리지 않음).
- **진단 기록**: `진단기록.cmd` / `진단기록.command` / `bash diag.sh`. 차에는 아무것도 쓰지 않고 정해 둔 속성, 두 앱의 버전, 우리 앱 로그만 읽어 MAC·전화번호·메일·기기번호를 가린 뒤 바탕화면에 저장합니다.
- **USB 디버깅 끄기**: 시동을 껐다 켜면 꺼집니다. 바로 끄려면 Factory Test에서 `0A 01` → `65 00` → `0A 02`, 또는 ‘내 앱’ → USB 디버깅 → OFF.

## 동작

- **APK**: 설치기 옆에 `N-Dashboard-API17.apk` · `My-Apps-API17.apk`가 있으면(파일이 `PK`로 시작할 때만) 그것을 쓰고, 없으면 이 저장소 릴리스(`releases/latest/download/…`, 테스트는 `test` 태그)에서 받습니다. **릴리스에 이 이름의 에셋이 올라가기 전에는 받기가 실패하므로 APK를 옆에 두고 실행하세요.**
- **adb**: 옆의 `platform-tools`, `PATH` 순서로 찾고, 없으면 묻고 나서 Google 공식 platform-tools를 받습니다(ARM 리눅스는 `apt`).
- **같은 버전 건너뛰기**: 차의 APK를 받아 비교해 같으면 설치하지 않습니다.
- **서명 충돌**(`INCONSISTENT_CERTIFICATES`): 묻고 나서 지우고 다시 설치합니다. 이때 앱 설정이 지워지지만 N 계기판의 [설정 백업](../docs/INSTALL.md)이 내비 저장소에 남아 있으면 새로 설치한 뒤 되돌릴지 묻습니다.
- 설치는 ① 전송(`adb push`) ② 설치(`pm install -r`)로 나눠 멈춘 단계를 보여 줍니다.
- 기록: Windows `%LOCALAPPDATA%\vn-installer\`, macOS `~/Library/Logs/vn-installer/`, 리눅스 `~/.local/state/vn-installer/`.

## 배포 (관리자)

```sh
python3 scripts/package_installer.py --out build/installer            # 스크립트만 (APK는 실행 때 릴리스에서)
python3 scripts/package_installer.py --out build/installer --apks DIR # APK 동봉(오프라인)
```

릴리스에는 APK를 `N-Dashboard-API17.apk` · `My-Apps-API17.apk` 이름으로 올립니다. `install.ps1`은 UTF-8 BOM + CRLF로 유지합니다(Windows PowerShell 5.1).

## 시험 (차 없이)

```sh
bash installer/test/run.sh mac     # 또는 linux
```

가짜 adb(`test/fakeadb.py`)로 설치, 같은 버전 건너뛰기, 새 버전 설치, 검증되지 않은 빌드 거부, 진단 기록(가림·읽기만), 연결 끊김 중 삭제, 삭제를 확인합니다. 2026-11-24 macOS에서 두 경로 모두 통과했습니다. `install.ps1`(Windows)은 같은 규칙으로 고쳤지만 이 환경에 PowerShell이 없어 실행 시험은 하지 않았습니다. 실제 차에서는 아직 이 설치기로 설치해 보지 않았습니다.
