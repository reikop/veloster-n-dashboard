# 설치 안내 (벨로스터 N 차주용)

> 현대자동차 공식 앱이 아닙니다. 순정 시스템에 앱을 설치하는 작업이라 보증·AS에서 불이익이 있을 수 있고, 책임은 본인에게 있습니다.
> 모든 작업은 정차, 시동 켠 상태에서 하세요. 주행 중에는 화면을 조작하지 마세요.

## 적용 조건

- 차량: 벨로스터 N (JS, 국내 사양). 2.0 T-GDi 6단 수동에서 확인했습니다. 8단 DCT는 확인하지 않았습니다(네온 드라이브의 DCT 표시는 "미확인" 자동 감지).
- 내비: 순정 내비, 기기 이름 `jsn_kr`, Android API 17.
- 내비 소프트웨어 빌드: `eng.vmadmin.20250902.081552`에서만 확인했습니다. **설치기는 다른 빌드에서는 설치하지 않고 멈춥니다.** 다른 빌드의 호환성은 알 수 없습니다.

## 준비물

- Windows 노트북, 맥, 또는 리눅스(라즈베리파이 포함)
- 차 USB 포트와 노트북을 잇는 데이터 케이블. 케이블을 바꿔서 연결된 사례가 있습니다.
- [Releases](https://github.com/reikop/veloster-n-dashboard/releases)의 설치기 ZIP 하나, 또는 직접 설치할 때는 APK 2개
  - `vn-installer-windows.zip` (Windows), `vn-installer-mac.zip` (맥), `vn-installer-linux.zip` (리눅스)
  - `N-Dashboard-API17.apk`: N 계기판
  - `My-Apps-API17.apk`: '내 앱'. 홈 화면 가장자리 버튼·알림으로 계기판을 여는 보조 앱
  - 릴리스에 적힌 SHA-256과 받은 파일이 같은지 확인하세요: Windows `certutil -hashfile 파일.apk SHA256`, 맥·리눅스 `shasum -a 256 파일.apk`

## 설치 프로그램으로 하기 (쉬운 방법)

### Windows

1. `vn-installer-windows.zip`을 받아 압축을 풉니다. 안에 `설치.cmd`, `삭제.cmd`, `진단기록.cmd`, `install.ps1`이 있습니다.
2. 아래 **1단계**(엔지니어링 모드)와 **2단계**(Factory Test)를 차 화면에서 합니다.
3. USB 케이블로 차와 노트북을 연결합니다.
4. `설치.cmd`를 더블클릭합니다. 노란 "보안 경고" 창이 뜨면 [실행]을 누릅니다.
   - 처음에 버전을 묻습니다. `1) 안정화`(Enter)는 최신 정식 릴리스, `2) 테스트`는 `test` 사전 배포입니다.
   - APK는 설치기 옆에 있으면 그것을 쓰고, 없으면 고른 버전의 릴리스에서 받습니다. ADB가 없으면 Google 공식 platform-tools를 받을지 묻습니다.
   - 차(`jsn_kr`)만 골라 빌드를 확인한 뒤 두 앱을 설치하고 차 화면에 '내 앱'을 엽니다. 휴대폰 등 다른 기기는 건드리지 않습니다.
   - 차에 이미 같은 버전이 있으면 그 앱은 건너뜁니다.
5. 차 화면에서 **5단계**를 합니다.

설치가 안 되면 `%LOCALAPPDATA%\vn-installer\`의 기록 파일을 함께 보내 주세요. "알 수 없는 장치"로 뜨면 설치기가 Google USB 드라이버 지정 순서를 안내합니다.

### 맥

1. `vn-installer-mac.zip`을 받아 풀면 `설치.command`, `삭제.command`, `진단기록.command`가 나옵니다.
2. 1~2단계를 한 뒤 USB를 연결하고 `설치.command`를 더블클릭합니다. 이후는 Windows와 같습니다.
   - "Apple에서 확인할 수 없습니다" 창이 뜨면 [완료]를 누르고 **시스템 설정 → 개인정보 보호 및 보안** 맨 아래 **[그래도 열기]**를 누릅니다(macOS 14 이하는 파일 우클릭 → [열기]).
   - 번거로우면 터미널에 `bash ` (뒤에 띄어쓰기 한 칸)를 치고 `설치.command`를 끌어다 놓은 뒤 Enter.
   - USB-C만 있는 맥은 USB-A 어댑터가 필요합니다.
   - 기록 파일: `~/Library/Logs/vn-installer/`

### 리눅스·라즈베리파이

```
unzip vn-installer-linux.zip -d vn && cd vn && bash install.sh
```

- `curl`이 필요합니다(`sudo apt install curl`).
- adb가 없으면 x86_64는 Google platform-tools를 받고, ARM(라즈베리파이 등)은 `sudo apt-get install adb`로 설치할지 묻습니다.
- 차가 `no permissions`로 보이면 차 장치(VID `18d1`, PID `d00d`)에만 규칙 한 줄(`/etc/udev/rules.d/51-vndashboard.rules`)을 넣을지 묻습니다. 넣은 뒤 케이블을 뺐다 다시 꽂습니다.
- 삭제 `bash uninstall.sh`, 진단 기록 `bash diag.sh`. 기록 파일: `~/.local/state/vn-installer/`

설치기 설명은 [`installer/README.md`](installer/README.md)에 있습니다.

### 지우기 (원래대로 되돌리기)

1. 설치할 때처럼 1~2단계를 하고 USB를 연결합니다.
2. `삭제.cmd`(맥 `삭제.command`, 리눅스 `bash uninstall.sh`)를 실행합니다. N 계기판과 '내 앱' 두 앱이 지워집니다.

이 앱들은 순정 시스템 파일을 바꾸지 않으므로 두 앱만 지우면 설치 전 상태입니다. N 계기판의 설정 백업 파일(`/sdcard/vndashboard/settings.txt`)은 남아 있어, 다시 설치하면 되돌릴지 한 번 묻습니다.

ADB로 직접: `adb uninstall com.veloster.dashboard` · `adb uninstall com.veloster.carplayhub`

### 문제 알리기 (진단 기록)

`진단기록.cmd`(맥 `진단기록.command`, 리눅스 `bash diag.sh`)를 실행하면 차에는 아무것도 바꾸지 않고, 정해 둔 내비 속성·두 앱의 버전·두 앱의 로그만 읽어 바탕화면에 `vn_진단기록_<시각>.txt`로 저장합니다. MAC 주소·전화번호·메일·기기 번호는 가립니다. 증상(언제, 어떤 화면에서, 무엇이)과 함께 [Issues](https://github.com/reikop/veloster-n-dashboard/issues)에 올려 주세요.

아래 1~2단계는 차 화면에서, 3~5단계는 설치기 없이 ADB 명령으로 직접 하는 방법입니다.

## 1단계 — 엔지니어링 모드 들어가기

1. 설정 → **SW 정보/업데이트** 화면으로 갑니다.
2. 아래 업데이트 버튼 줄의 **왼쪽 숨은 영역을 5번**, 이어서 **오른쪽 숨은 영역을 1번** 누릅니다. 첫 터치부터 10초 안에 해야 하고, 길게 누르는 것이 아닙니다.
   - 실패하면 10초 이상 기다렸다 다시 합니다. 왼쪽을 10번·15번 누르면 다른 기능이 켜지니 5번에서 멈추세요.
3. 암호 창이 뜨면 **2026년 `0567`** (2027년 `1278`)을 입력합니다. 내비 달력 연도에 따라 다릅니다.

엔지니어링 모드의 다른 메뉴는 누르지 마세요. 자세한 근거는 [ADB 연결 안내](docs/ADB.md)에 있습니다.

## 2단계 — USB 디버깅 켜기

1. 엔지니어링 모드 → **Diagnosis → Factory Test**로 들어갑니다(`DTC for developer`가 아닙니다).
2. 아래 세 줄을 **한 줄씩** 입력하고 매번 SEND를 누릅니다. 두 바이트 사이에 띄어쓰기 한 칸입니다.
   ```
   0A 01
   65 01
   0A 02
   ```
3. 화면에 아무 반응이 없어도 정상입니다. 반응은 노트북 쪽에서만 나타납니다.

'내 앱'이 이미 깔려 있으면 **'내 앱' → USB 디버깅 → ON**으로 대신할 수 있습니다.

### USB 디버깅 끄기

- **시동을 껐다 켭니다.** 재시동하면 꺼집니다.
- **Factory Test에서 끕니다.** 가운데 줄만 `65 00`으로 바꿔 한 줄씩 보냅니다.
  ```
  0A 01
  65 00
  0A 02
  ```
- **'내 앱' → USB 디버깅 → OFF.** 끄면 노트북 연결이 바로 끊깁니다.

## 3단계 — 노트북 연결 확인

[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)의 `adb`가 있는 폴더에서:

```
adb devices -l
adb shell getprop ro.product.name
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.incremental
```

목록에 `device`, `product:jsn_kr`이 보이고, 속성이 차례로 `jsn_kr`, `17`, `eng.vmadmin.20250902.081552`이면 됩니다. `unauthorized`면 차 화면의 "USB 디버깅 허용"에서 [항상 허용]을 누릅니다.

## 4단계 — 설치

```
adb install -r N-Dashboard-API17.apk
adb install -r My-Apps-API17.apk
```

`Success`가 나오면 됩니다. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`(서명이 다른 예전 앱)이 나오면 그 앱을 `adb uninstall`로 지운 뒤 다시 설치합니다. 이때 앱 설정은 지워지지만 설정 백업 파일이 있으면 다시 설치한 뒤 되돌릴 수 있습니다.

## 5단계 — '내 앱'과 계기판 열기

1. 차 화면에서 '내 앱'을 엽니다(설치기는 자동으로 엽니다). ADB로는 `adb shell am start -n com.veloster.carplayhub/.HubActivity`.
2. '내 앱'에서 **실행 바로가기**를 켜면 노트북 없이 HOME 가장자리 버튼·알림으로 N 계기판을 열 수 있습니다([실행 바로가기 안내](docs/QUICK_ENTRY_ko.md)).
3. USB 디버깅을 끕니다(위 "USB 디버깅 끄기").

## 계기판 쓰는 법

- **화면 바꾸기**: 위쪽 탭을 누르거나 탭 줄을 좌우로 끕니다. 탭 줄을 길게 누르면 화면 목록(보이기·숨기기·순서)이 열립니다.
- **설정**: 시계 왼쪽 톱니바퀴. 화면, 테마, 플로팅 창, 주행 모드(모드 색·모드별 화면), 사운드(엔진 사운드·변속 알림음·과속 차임, 모두 기본 꺼짐), 기록, 백업, 정보.
- **칸 바꾸기**: F1 화면의 아래 두 줄, 레이스 화면의 작은 칸 네 개를 길게 누르면 보일 값을 고릅니다. 게이지·그래프 칸도 길게 눌러 바꿉니다.
- **플로팅 창**: 설정 → 플로팅에서 켜면, 계기판을 나간 뒤 다른 화면 위에 값 창이 뜹니다. 창을 누르면 계기판, 길게 누르면 '내 앱', 끌면 이동합니다.
- **택시 미터기**: 내비 GPS로 지금 있는 사업구역의 중형 요금(17개 시·도)을 쓰고, 다른 시·군에 들어가면 시계외 할증이 자동으로 붙습니다. 움직이면 자동으로 운행이 시작됩니다. 참고용이며 검정받은 미터기가 아닙니다.
- **네온 드라이브**: 탭 줄 끝의 화면입니다. 화면을 누르면 오른쪽 위에 설정 톱니와 ← 돌아가기 버튼이 나옵니다.
- **기록**: 0→100, 60→100, 80→120 km/h를 자동으로 재고, 상위 5개를 날짜와 함께 보관합니다.

## 안 될 때

| 증상 | 할 일 |
| --- | --- |
| `adb devices`에 아무것도 없음 | Factory Test 세 줄을 띄어쓰기까지 정확히 한 줄씩 다시 보내고 케이블을 다시 꽂습니다. 케이블·포트를 바꿔 봅니다. |
| `unauthorized` | 차 화면의 "USB 디버깅 허용"에서 [항상 허용] |
| Windows "알 수 없는 장치" | 설치기가 안내하는 대로 Google USB 드라이버(`android_winusb.inf` → Android ADB Interface)를 직접 지정 |
| "확인된 내비 빌드가 아닙니다" | 이 설치기는 `eng.vmadmin.20250902.081552`에서만 설치합니다. 다른 빌드는 호환성이 확인되지 않았습니다. |
| 계기판 값이 "—" | 그 값을 차에서 받지 못한 것입니다(값을 지어내지 않습니다). 시동 후 잠시 기다리거나 [진단 기록](#문제-알리기-진단-기록)을 보내 주세요. |

## 출처

N 계기판의 플로팅 창, 설정 백업, 기록실, 테마, 화면 목록, 레이스·람다·타코 화면, 소리, 택시 지역 요금과 이 설치기는 i30 PD 포팅 [sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard)(MIT)에서 가져와 벨로스터 N에 맞춘 것입니다. 자세한 권리 표시는 [NOTICE.md](NOTICE.md)를 보세요.
