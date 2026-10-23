# 설치·실행·복귀

이 절차는 PC 터미널에서 공개 폴더를 현재 디렉터리로 두고 실행합니다. 정차 상태에서 [ADB 연결과 대상 빌드](ADB.md)를 먼저 확인합니다. 자동 설치 스크립트나 SD 업데이트는 제공하지 않습니다.

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
