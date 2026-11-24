# 권리 및 외부 의존성

별도 라이선스를 명시한 모듈을 제외하고, 이 폴더에서 직접 작성한 Java/Python 코드와 문서는 MIT 라이선스로 배포합니다. `compile-stubs/`는 호환성 컴파일을 위해 작성한 최소 클래스·메서드 선언이며 제조사 구현을 제공하지 않습니다. 기존 배포 APK의 정의된 클래스는 `com.veloster` 네임스페이스에 한정됩니다. 새 무선 수신기 preview에는 아래 JmDNS 클래스도 포함됩니다.

[연구 과정](docs/RESEARCH_ko.md)에 소개한 외부 도구 `nudl`은 GPL-3.0-only이며 별도로 설치합니다. 해당 도구 소스를 이 MIT 프로젝트로 복사하거나 라이선스를 변경해 배포하지 않습니다. 다운로더가 오픈소스라는 것과 내려받은 제조사 펌웨어의 권리는 구분합니다.

차량에 이미 설치된 OEM `automotive` 프레임워크와 Android 시스템, Android SDK/JDK/D8/AAPT2 등 외부 도구의 권리는 각 권리자에게 있습니다. MIT 허가는 해당 소프트웨어·펌웨어를 재배포할 권한을 부여하지 않습니다. 외부 SDK·도구·DBC 데이터베이스와 제조사 구현은 이 폴더에 배포하지 않습니다.

배포 APK에는 호환성을 위해 공개 AOSP platform 테스트 인증서의 서명이 들어 있습니다. 개인키 파일은 포함하지 않습니다. 공개 테스트 인증서는 비밀인 배포자 신원 증명이 아니며, OTA 업데이트 서명 권한을 의미하지 않습니다. 기존 설치본과의 서명 일치는 확인하되 신뢰할 수 있는 배포 경로와 파일 해시도 확인해야 합니다.

Hyundai, Veloster N, Android, CarPlay 등 명칭은 기기·호환 대상 설명에 사용합니다. 해당 회사의 공식 앱이나 승인된 제품이라는 뜻이 아닙니다. `com.veloster.carplayhub`는 초기 실험에서 남은 패키지 이름이며, 무선 CarPlay 기능 제공을 뜻하지 않습니다.

선택형 `menu-entry/`에는 직접 작성한 메뉴 연결 helper, 컴파일 선언, 원본 식별 해시만 제공합니다. 빌드 스크립트는 사용자가 제공한 정확한 OEM 원본을 로컬에서 재구성합니다. 생성 APK와 디컴파일 트리는 제조사 코드를 포함하므로 MIT 프로젝트 산출물로 재배포하지 않습니다. `prepare_menu_tools.py`가 받는 외부 JAR·공개 AOSP 테스트 키·인증서는 출처별 권리가 적용되며 저장소/릴리스에 번들하지 않습니다.

## i30 PD 포팅에서 가져온 부분

N 계기판의 플로팅 창(`FloatPanel`·`FloatSkin`·`FloatLayouts`·`FloatMusic`·`FloatService`), 설정 백업(`SettingsBackup`), 기록실(`Records`·`RecordsDialog`·`DriveStats`), 레이스·람다·타코 화면(`RaceRenderer`·`LambdaCluster`·`TachoCluster`·`ClusterKit`·`RevMatch`·`ShiftCoach`·`GearRatios`·`MilLamp`), 환영 화면, 주행 모드 화면 전환(`DriveModes`), 소리(`EngineSound`·`EngineSynth`·`ShiftBeep`·`SpeedChime`), 테마(`Theme`·`ThemeShapes`), 화면 목록 개념, 주야간 판단(`DayNight`·`DayPhase`), 신호 목록(`Tiles`·`TilePicker`), 소유자용 설치기(`installer/`)는 [sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard)(이 저장소의 i30 PD 포크, MIT)에서 가져와 벨로스터 N JSN에 맞게 고쳤습니다. `LICENSE`의 두 저작권 표시는 MIT 조건이므로 지우지 마세요.

택시 미터기의 시·도별 중형 요금(`TaxiTariff`)은 i30 PD 포팅에서 가져왔고, 그 숫자는 전국개인택시운송사업조합연합회 [시, 도별 개인택시 요금현황](https://gntx.kr/information/information_02_1.php)(2026-11-04에 읽음)에서 옮긴 것입니다. 사업구역 경계(`TaxiZoneData`, `scripts/taxi_zones.py`로 생성)는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)가 공공누리 제1유형으로 개방한 행정구역 경계를 [vuski/admdongkor](https://github.com/vuski/admdongkor)가 가공한 것(CC BY 4.0, `sgg_20261001`)을 시·군 단위로 합치고 200 m로 단순화한 것입니다. GPS 위치는 요금 지역 판정에만 쓰고 앱 밖으로 보내거나 기록하지 않습니다(로그에는 1분마다 수신 개수·정확도·요금표 이름만 남김). 참고용 표시이며 검정받은 미터기가 아닙니다.

플로팅 창 글자는 Chakra Petch(Medium·Bold)로 그립니다. Copyright 2018 The Chakra Petch Project Authors (https://github.com/m4rc1e/Chakra-Petch), SIL Open Font License 1.1. 글꼴 파일은 고치지 않고 `apps/dashboard/res/raw/`에 넣었고, 라이선스 전문은 [`third_party/chakra-petch/OFL.txt`](third_party/chakra-petch/OFL.txt)에 있습니다.

## Optional native HTTPS build

New My Apps preview builds may include separately built curl, OpenSSL, optional c-ares and a Mozilla CA bundle. These components retain their upstream licenses; the project MIT license covers project-authored code. [Source pins, full notices and reproduction](third_party/network/README.md) describe this optional build. Existing published v6/v10 APKs are unchanged. No private key or OEM component is distributed with these TLS inputs.

## Wireless receiver preview

The [wireless receiver module](third_party/wireless-carplay/README.md) is GPL-3.0-only,
with attributed Apache-2.0 JmDNS/compatibility components and optional Apache-2.0
OpenSSL JNI. Its complete corresponding Java/C sources and notices are retained
separately. The root MIT license does not relicense that module; combined Hub
preview builds contain GPLv3 code. Existing published APKs remain unchanged.
No Apple SDK implementation, OEM code or private key is bundled. This integration
does not imply Apple certification or verified wireless CarPlay operation.
