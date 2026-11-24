# Veloster N Dashboard — 독립 N 계기판

[English](README.md) | **한국어**

벨로스터 N 순정 내비게이션용 독립 계기판과 앱 실행 도우미 **‘내 앱’**입니다. 한국어·영어를 지원하며 순정 N 모드와 별도로 설치합니다.

[다운로드](#다운로드) · [설치](docs/INSTALL.md) · [호환성·확인 범위](docs/STATUS.md#한국어) · [문서](docs/README.md)

<img src="docs/images/dashboard-overview-in-car.jpg" alt="국내 벨로스터 N 순정 내비에서 실행한 N 계기판 전체조망" width="480">

차주가 촬영한 실차 화면입니다. 수신하지 못한 값은 `-`로 표시합니다. 위치·촬영기기 메타데이터를 제거했으며 사진 픽셀은 변경하지 않았습니다.

## 기능

| N 계기판 | 내 앱 |
| --- | --- |
| 화면 11개: 전체 조망·F1·게이지·그래프·퍼포먼스·택시 미터기·기록·레이스·람다·타코·네온 드라이브 | 계기판·설치 앱 실행 |
| 화면 목록(보이기·숨기기·순서)·테마 20종·길게 눌러 칸 바꾸기·주행 모드 색/화면 | 선택형 가장자리 버튼·알림 바로가기 |
| 다른 앱 위 플로팅 창·설정 백업·기록실(가속 측정 상위 기록 보관) | 엔지니어링 모드·USB 디버깅·Android 도구 |
| GPS로 고르는 17개 시·도 택시 요금·엔진 사운드·변속 알림음·과속 차임 | Bluetooth 페어링·확인창을 거치는 재시작 |
| RPM·속도·온도·TPMS·TCS/ESC/ABS·미디어 제어·앨범아트 | 앱 목록 편집·한국어/영어 선택 |

플로팅 창·설정 백업·기록실·테마·화면 목록·레이스/람다/타코·소리·택시 지역 요금·차주용 설치기는 i30 PD 포팅 [sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard)(MIT)에서 가져와 벨로스터 N에 맞췄습니다. 차주용 설치 안내: [INSTALL.md](INSTALL.md).

차량 데이터는 기존 수신 경로를 사용하며 ECU 진단 요청을 새로 보내지 않습니다. 미수신·만료 값은 알 수 없음으로 남깁니다. Bluetooth 앨범아트 수신·배기온·e-LSD는 미해결이며 무선 CarPlay·자동 업데이트는 구현하지 않았습니다. [상세 한계](docs/LIMITATIONS.md)

## 호환성

설치기 지원 대상은 **국내 벨로스터 N JSN**, product `jsn_kr`, **Android 4.2.2 / API 17**, incremental `eng.vmadmin.20250902.081552` (`250902`, `STD_H`)입니다. 계기판 실행에는 순정 `automotive` 라이브러리가 필요합니다. 다른 차종·지역·펌웨어·일반 Android 기기의 호환성은 확인하지 않았습니다.

**2026-10-27:** 공개 N 계기판 v6·내 앱 v10 설치 후 APK를 다시 읽어 해시 일치를 확인했습니다. 사용자는 HOME 가장자리 버튼으로 내 앱을 다시 여는 것도 확인했습니다. **완전 전원 종료 후 복원·장시간 동작은 미검증**입니다. 이후의 비공개 시험본은 이 배포본과 구분합니다. [근거·현재 확인 범위](docs/STATUS.md#한국어)

## 다운로드

**[v2026.10.26-i18n 전체 ZIP 받기](https://github.com/reikop/veloster-n-dashboard/releases/download/v2026.10.26-i18n/veloster-n-dashboard-v2026.10.26-i18n.zip)** — 서명된 앱 두 개, 설치 도구, 안내를 포함합니다. 사전 배포 상태를 유지합니다.

| 현재 APK | 버전 |
| --- | --- |
| [N 계기판](apks/N-Dashboard-v6-API17.apk) | v6 · 1.5-i18n |
| [내 앱](apks/My-Apps-v10-API17.apk) | v10 · 1.9-i18n |

설치에는 전체 ZIP이나 저장소를 사용하세요. APK를 SD 카드에 복사하는 것만으로 설치되지 않습니다. 날짜가 붙은 ZIP의 문서는 배포 당시 상태이며 이후 검증은 이 저장소에 반영합니다. [이전 버전·배포 기록](docs/RELEASE.md) · [파일 해시·패키지 정보](release.json)

## 빠른 시작

설치·화면 조작은 정차 상태에서 진행합니다. 최초 설치에는 Python 3.9 이상과 동작하는 [ADB 연결](docs/ADB.md)이 필요합니다.

1. 전체 묶음을 풀고 프로젝트 폴더에서 터미널을 엽니다.
2. 파일을 검사하고 `adb devices -l`에 표시된 본인 기기의 일련번호로 설치합니다.

   ```sh
   python3 scripts/verify_release.py
   python3 scripts/install.py --serial YOUR_DEVICE_SERIAL --parked
   ```

3. 내 앱에서 **실행 바로가기 → 가장자리 버튼 + 알림 → 저장**을 선택합니다.
4. PC를 분리하기 전에 **HOME → 가장자리 버튼 → 내 앱 → N 계기판**을 확인합니다.
5. 해당 차량의 완전 종료·재시동 후 재실행은 따로 확인합니다.

설치기는 매번 새 APK 백업을 만들고 설치된 파일을 다시 검증합니다. 설치·실행 없이 점검·백업만 하려면 `--parked` 대신 `--check`를 사용합니다. 실행 요청 수락과 실제 화면 표시는 별개입니다. 바로가기 기본값은 OFF이며 순정 메뉴는 변경하지 않습니다. [설치·복귀](docs/INSTALL.md) · [바로가기 문제 해결](docs/QUICK_ENTRY_ko.md)

설치 후 평소 사용에는 PC가 필요하지 않습니다. 가장자리 버튼과 알림 모두 사용할 수 없다면 [지원 빌드 전용 OEM 메뉴 절차](docs/MENU_ENTRY_ko.md)를 선택적으로 확인하세요.

## 문서

| 순서 | 한국어 | English |
| --- | --- | --- |
| 01 · 호환성·검증 | [확인 범위](docs/STATUS.md#한국어) | [Status](docs/STATUS.md) |
| 02 · ADB·USB 연결 | [ADB 연결](docs/ADB.md) | [ADB](docs/ADB_EN.md) |
| 03 · 설치·업데이트·복귀 | [설치](docs/INSTALL.md) | [Install](docs/INSTALL_EN.md) |
| 04 · 내 앱·바로가기 | [내 앱](docs/MY_APPS_ko.md) · [바로가기](docs/QUICK_ENTRY_ko.md) | [My Apps](docs/MY_APPS.md) · [Quick entry](docs/QUICK_ENTRY.md) |
| 05 · 언어 설정 | [언어](docs/LANGUAGES.md#한국어) | [Languages](docs/LANGUAGES.md) |
| 06 · 개발·연구 | [개발·연구 색인](docs/README.md#development-and-research) | [Documentation index](docs/README.md#development-and-research) |

## 개발

[`apps/`](apps/)는 두 앱, [`shared/`](shared/)는 계기판 UI·데이터 처리 소스입니다. [`compile-stubs/`](compile-stubs/)는 빌드 전용 OEM 선언이고, [`scripts/`](scripts/)·[`tests/`](tests/)는 빌드·무결성·호스트 검사 도구입니다. [`installer/`](installer/README.md)는 차주용 설치기(Windows·macOS·리눅스)입니다.

[빌드·테스트](docs/BUILD.md) · [개발 과정](docs/RESEARCH_ko.md)

자체 코드·문서는 [MIT](LICENSE)입니다. [의존성·서명 안내](NOTICE.md)를 참고하세요. 공개 AOSP 시험 인증서는 독점 배포자 신원이나 OTA 키가 아닙니다. 소스 빌드 결과는 서명 전 APK입니다. 제조사 펌웨어·APK, 개인 로그·식별자·개인키는 배포하지 않습니다. 제조사와 무관한 비공식 프로젝트입니다.
