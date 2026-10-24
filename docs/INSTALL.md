# 설치·실행·복귀

처음 설치할 때는 PC 터미널에서 공개 폴더를 현재 디렉터리로 두고 명령을 실행합니다. 정차 상태에서 [ADB 연결과 대상 빌드](ADB.md)를 먼저 확인합니다. 이미 설치했다면 [케이블 분리·재부팅 후 실행](#after-reboot)부터 읽으세요. 자동 설치 스크립트나 SD 업데이트는 제공하지 않습니다.

## 무결성과 대상 확인

```sh
python3 scripts/verify_release.py
VN_SERIAL='YOUR_DEVICE_SERIAL'
adb -s "$VN_SERIAL" shell getprop ro.product.name
adb -s "$VN_SERIAL" shell getprop ro.build.version.incremental
adb -s "$VN_SERIAL" shell pm path com.veloster.dashboard
adb -s "$VN_SERIAL" shell pm path com.veloster.carplayhub
adb -s "$VN_SERIAL" shell df /data
```

파일 해시 검사 실패, 다른 기기, 저장공간 부족이면 설치를 진행하지 않습니다. 최초 설치라면 `pm path`에 해당 패키지 경로가 없을 수 있습니다. 다른 빌드에서는 v5 추가 수신 경로가 의도적으로 비활성화됩니다.

기존 앱이 있으면 출력된 `package:/data/app/...apk`에서 `package:`를 제외한 **실제 경로**를 사용해 PC로 복사해 둡니다. 아래 `REPLACE_WITH_PM_PATH`는 직접 바꿔야 합니다.

```sh
mkdir -p private-backups
adb -s "$VN_SERIAL" pull 'REPLACE_WITH_PM_PATH' private-backups/before-dashboard.apk
```

‘내 앱’도 업데이트한다면 그 패키지 경로에서 `before-hub.apk`로 따로 보관합니다. APK 복사는 앱 설정·사용자 데이터 백업이 아닙니다. `allowBackup=false`이며 일반 ADB로 설정까지 복구할 수 있다고 보장하지 않습니다. 이 개인 백업 폴더는 공개 저장소에 넣지 않습니다.

## N 계기판 설치

```sh
adb -s "$VN_SERIAL" push apks/N-Dashboard-v5-API17.apk /data/local/tmp/vn-public-dashboard-v5.apk
adb -s "$VN_SERIAL" shell pm install -r /data/local/tmp/vn-public-dashboard-v5.apk
```

**출력이 `Success`인지 확인한 뒤** 다음 단계로 갑니다. 구형 adbd에서는 PC 명령의 종료 코드만으로 설치 성공을 판정하면 안 됩니다. 실패하면 오류 문구를 확인하고 이후 실행·업데이트를 멈춥니다. 성공·실패와 무관하게 이 절차에서 올린 임시 APK는 다음 명령으로 정리할 수 있습니다.

```sh
adb -s "$VN_SERIAL" shell rm /data/local/tmp/vn-public-dashboard-v5.apk
adb -s "$VN_SERIAL" shell am start -n com.veloster.dashboard/.DashboardActivity
```

## ‘내 앱’ 설치 — 선택

```sh
adb -s "$VN_SERIAL" push apks/My-Apps-v8-API17.apk /data/local/tmp/vn-public-hub-v8.apk
adb -s "$VN_SERIAL" shell pm install -r /data/local/tmp/vn-public-hub-v8.apk
```

`Success` 확인 후:

```sh
adb -s "$VN_SERIAL" shell rm /data/local/tmp/vn-public-hub-v8.apk
adb -s "$VN_SERIAL" shell am start -n com.veloster.carplayhub/.HubActivity
```

이후 ‘내 앱’의 **N 계기판** 버튼으로 계기판을 엽니다. USB 디버깅 OFF·강제 재시작은 연결을 끊을 수 있으며 설치 확인에 필요한 동작이 아닙니다. ‘연결 점검’에는 기존 핫스팟 실험 화면이 남아 있지만 계기판 설치 절차와 무관합니다.

## 설치 후 확인과 홈 진입점

1. `adb -s "$VN_SERIAL" shell dumpsys package com.veloster.dashboard`에서 versionCode 5를 확인합니다. ‘내 앱’은 versionCode 8입니다.
2. 필요하면 `pm path`로 설치 경로를 다시 얻어 PC로 pull한 파일과 배포 APK의 SHA-256을 비교합니다. 다른 해시라면 같은 배포본이라고 판정하지 않습니다.
3. 정차 상태에서 값 표시, 화면 전환, 앱을 닫았다가 다시 열기를 확인합니다. 재시동 후 유지와 장시간 동작은 별도 검증 과제입니다.

두 APK 모두 LAUNCHER 인텐트를 선언하지만 순정 홈은 고정 메뉴여서 새 아이콘을 자동으로 보여 주지 않을 수 있습니다. 이 배포에는 OEM 홈 수정본·자동 시작 서비스가 없습니다. **ADB로 한 번 실행하는 것과 PC 없이 재부팅 후 다시 여는 것은 별개**입니다. 기기에서 동작하는 런처/앱 목록 진입점이 이미 없다면 현재 공개 패키지만으로 일상 실행 경로가 완성되지는 않습니다.

<a id="after-reboot"></a>

## 케이블을 빼고 재부팅한 뒤 실행하기

**일상 사용에는 PC와 USB 디버깅 연결이 필요하지 않습니다.** 계기판 앱은 내비에 설치되어 있고, 차량 데이터는 내비 내부 서비스에서 받습니다. USB 디버깅은 설치·점검에 쓰는 연결입니다. 정상 설치된 앱은 단순 케이블 분리나 재부팅 때문에 다시 설치할 필요가 없습니다.

### 이미 ‘정보 → 내 앱’ 진입점이 있는 차량

1. 내비가 부팅되어 홈 화면과 메뉴가 조작 가능한 상태가 될 때까지 기다립니다.
2. **홈(HOME) → 전체 메뉴 → 2페이지 → 정보 → 내 앱**을 엽니다.
3. ‘내 앱’에서 **N 계기판** 버튼을 누릅니다. 순정 N 모드 안의 예전 `N 계기판` 버튼을 찾는 절차가 아닙니다.
4. 이미 호환 런처나 앱 목록에 **N 계기판** 아이콘이 보이는 구성이라면 그 아이콘으로 직접 실행해도 됩니다.

위 홈 경로는 연구 차량에서 별도 OEM 메뉴 수정 후 2026-10-20에 진입을 확인한 경로입니다. **공개 APK 두 개만 설치하면 이 메뉴까지 자동으로 생긴다는 뜻은 아닙니다.** 메뉴 배치가 다른 기기는 기존에 설정한 ‘내 앱’ 바로가기를 사용합니다. 이 경로를 기록하는 것이 v5의 냉간 재부팅·수신 동작 검증을 완료했다는 의미는 아닙니다.

케이블만 분리했고 계기판이 이미 열려 있다면 그대로 사용할 수 있습니다. 앱을 닫았거나 내비가 완전히 재부팅되었다면 위 메뉴에서 다시 엽니다. 현재 공개 앱에는 부팅 후 자동 실행 기능이 없습니다. 앱 데이터 삭제·제거를 하지 않았다면 저장한 게이지·그래프 등 표시 설정을 다시 사용합니다.

### ‘정보’ 또는 ‘내 앱’ 메뉴가 없는 경우

먼저 전체 메뉴의 다음 페이지와 기존 앱 목록을 확인합니다. 순정 홈에 이 진입점이 없고 다른 런처에서도 앱을 열 수 없다면 **현재 공개 패키지만으로는 PC 없이 다시 실행할 경로가 없습니다.** 동일 APK를 재설치하거나 USB 디버깅을 켜는 것만으로 홈 아이콘이 생기지는 않습니다.

이때는 [ADB 연결 안내](ADB.md)에 따라 PC를 다시 연결하고, `adb devices -l`에서 본인 기기가 `device` 상태인지 확인합니다. 터미널을 새로 열었다면 일련번호 변수도 다시 지정합니다.

```sh
adb devices -l
VN_SERIAL='YOUR_DEVICE_SERIAL'

# 설치 여부 확인: 각 패키지의 APK 경로가 출력되어야 합니다.
adb -s "$VN_SERIAL" shell pm path com.veloster.carplayhub
adb -s "$VN_SERIAL" shell pm path com.veloster.dashboard

# 설치가 확인되면 재설치 없이 ‘내 앱’을 엽니다.
adb -s "$VN_SERIAL" shell am start -n com.veloster.carplayhub/.HubActivity
```

계기판만 설치한 경우에는 다음 명령으로 직접 엽니다.

```sh
adb -s "$VN_SERIAL" shell am start -n com.veloster.dashboard/.DashboardActivity
```

실행한 뒤 케이블을 분리해도 됩니다. 다만 다음 재부팅 후에도 메뉴가 없다면 같은 문제가 반복됩니다. PC 없는 반복 실행을 완성하려면 해당 기기에서 실제로 열 수 있는 앱 목록/런처 진입점이 필요합니다. OEM 홈 수정 APK는 이 공개 배포에 포함하지 않습니다.

### 재부팅 뒤 증상 구분

| 증상 | 확인할 것 |
| --- | --- |
| 순정 N 모드의 옛 `N 계기판` 버튼만 사라짐 | 독립 앱은 별도입니다. 위의 ‘내 앱 → N 계기판’으로 실행합니다. |
| ‘내 앱’은 열리지만 계기판 실행 실패 | ADB에서 `pm path com.veloster.dashboard`로 설치 여부를 확인합니다. 바로 삭제하거나 재설치하지 않습니다. |
| 앱은 열리지만 값이 `-` | USB 케이블이 필요한 것은 아닙니다. 내비 서비스 준비·지원 빌드·실제 수신 여부를 구분합니다. 부팅 직후 수신과 냉간 동작은 아직 별도 검증 대상입니다. |
| ADB에도 장치가 안 보임 | 앱 실행 문제와 USB 연결 문제를 구분해 [ADB 안내](ADB.md)의 열거·모드·케이블 순서를 확인합니다. |
| `pm path`에 패키지가 없음 | 대상 기기와 설치 기록을 먼저 확인한 뒤 이 문서의 설치 절차를 사용합니다. 메뉴 부재만으로 앱이 지워졌다고 판단하지 않습니다. |

## 오류와 복귀

| 오류/증상 | 대응 |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 등 서명 불일치 | 기존 APK를 보존하고 중단. 서명을 바꾸거나 자동 삭제해서 덮어쓰지 않음 |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | 필요한 OEM `automotive` 라이브러리 없음. 일반 기기에 프레임워크를 복사해 해결하는 절차는 미제공 |
| 공간 부족 | 이 절차의 임시 업로드 파일부터 정리. 시스템 앱·순정 데이터 임의 삭제 금지 |
| 실행되지만 추가 값이 `-` | 지원 빌드·현재 수신 여부·60초 만료 확인. ECU 요청을 보내 채우지 않음 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 이전 버전으로 내려가는 조건 확인. 설정 손실 없는 복귀를 보장하지 않음 |

문제가 생기면 먼저 아래처럼 **독립 계기판만** 정지할 수 있습니다. 순정 N 모드는 별도 패키지입니다.

```sh
adb -s "$VN_SERIAL" shell am force-stop com.veloster.dashboard
```

동일 서명·동일 또는 높은 버전인 본인 백업은 위와 같은 `push` → `pm install -r` 방식으로 복귀할 수 있습니다. v3는 v5보다 낮은 버전이므로 단순 `-r` 설치가 거절될 수 있습니다. `pm install -r -d` 지원·허용 여부와 v5 설정을 유지한 v3 실행은 이 공개 패키지에서 실차 검증하지 않았으므로 자동 복귀 명령으로 제공하지 않습니다.

**설정 소실을 받아들이고 독립 앱을 새로 설치하려는 경우에만**, 본인 백업을 확보한 뒤 제거하고 v3를 설치할 수 있습니다. 제거하면 게이지·그래프·목록 등의 앱 설정이 지워집니다. 다음 명령은 복귀를 선택한 경우에만 사용합니다.

```sh
adb -s "$VN_SERIAL" shell pm uninstall com.veloster.dashboard
# Success 확인 후, 위 설치 단계에서 파일만 다음 것으로 바꿉니다:
# apks/rollback/N-Dashboard-v3-API17.apk
```

프로젝트를 완전히 제거하려면 독립 패키지 `com.veloster.dashboard`, `com.veloster.carplayhub`만 대상으로 합니다. ‘내 앱’을 지우기 전 필요한 USB/무선 상태를 화면에서 복구합니다. 앱 제거가 바꾼 기기 설정까지 자동으로 되돌려 주지는 않습니다. 본 배포는 순정 N 모드를 수정하지 않지만, 과거에 별도 순정 수정본을 설치했다면 그 수정까지 복구하는 패키지는 아닙니다.
