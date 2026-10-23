# 순정 업데이트 다운로드부터 독립 N 계기판까지

[English](RESEARCH.md) | **한국어** | [프로젝트](../README_ko.md)

이 프로젝트는 `Veloster_N_BlueLink` 순정 업데이트 패키지에서 시작했습니다. Android 앱·서비스를 분석하고, 실차 원본과 대조하고, 차량 값 수신을 확인한 뒤 독립 N 계기판을 만들었습니다. 아래는 **오픈소스 다운로더 `nudl`로 원본을 확보하는 방법**과 실제 연구 과정입니다.

공개 앱의 빌드·설치에는 순정 펌웨어를 새로 다운로드하거나 차량을 업데이트할 필요가 없습니다. 앱만 사용하려면 [설치 안내](INSTALL.md)를 따르세요. 이 문서의 명령은 PC에서 파일을 받거나 확인하는 절차입니다.

## 1. nudl로 원본 확보하기

[nudl](https://github.com/chenxiaolong/nudl)은 공개 HMG 업데이트 목록을 이용하는 비공식 오픈소스 다운로더이며 GPL-3.0-only 라이선스입니다. 다운로드한 제조사 펌웨어의 권리와, 우리가 만든 앱의 MIT 라이선스는 각각 별개입니다.

[nudl 릴리스](https://github.com/chenxiaolong/nudl/releases)에서 운영체제에 맞는 실행파일을 받아 PATH에 둡니다. 조사 당시 보관한 소스 커밋은 [`f278391851f52b7b79687317de7940b3e739c000`](https://github.com/chenxiaolong/nudl/tree/f278391851f52b7b79687317de7940b3e739c000)입니다. 현재 배포 버전이 이 커밋과 같다고 가정하지 않습니다.

공개 프로젝트 폴더에서 다음처럼 조회합니다. 다운로드 위치는 공개 저장소 밖의 형제 폴더로 지정합니다.

```sh
nudl list -b hyundai -r KR -m JSNAS.S5AMC.KR

VN_FW_DIR='../veloster-n-firmware-research/raw/Veloster_N_BlueLink'
mkdir -p "$VN_FW_DIR"

# 조회 결과에 아래 버전이 남아 있는 경우에만 동일 원본을 받습니다.
nudl download -b hyundai -r KR -m JSNAS.S5AMC.KR \
  -v JSN.KOR.SOP.V141.260114.STD_H \
  -o "$VN_FW_DIR"

nudl verify -d "$VN_FW_DIR"
```

옵션은 [nudl CLI 소스](https://github.com/chenxiaolong/nudl/blob/master/src/cli.rs)를 기준으로 확인했습니다. 위 줄바꿈·변수 문법은 macOS/Linux 셸용입니다. Windows에서는 `nudl.exe`와 해당 셸의 경로·변수 문법을 사용합니다.

| 항목 | 우리가 분석한 대상 |
| --- | --- |
| 지역·제조사 | KR·Hyundai |
| 모델 코드 | `JSNAS.S5AMC.KR` |
| 표시 이름 | 벨로스터 N [표준형 5세대 블루링크] |
| mcode | `386` |
| 패키지 이름 | `Veloster_N_BlueLink` |
| 버전 | `JSN.KOR.SOP.V141.260114.STD_H` |
| 카탈로그 저장매체 | SD |

2026-10-13에 보관한 공개 목록에는 이 버전이 있었고, 실차의 이전 `250902` 버전은 없었습니다. **현재 서버에서도 그대로 받을 수 있다는 보장은 없습니다.** `-v`는 서버에 있는 항목을 고르는 옵션이며 삭제된 구버전을 복원하지 않습니다. 해당 버전이 사라졌다면 최신 파일을 같은 원본으로 취급하지 않습니다.

기록상 `nudl` 소스/API를 이용한 공개 목록 조사와 최초 로컬 패키지에 연결되는 Navigation Updater 다운로드 로그가 함께 남아 있습니다. 위 명령은 재현용으로 정리한 다운로드 경로입니다. 과거 전체 다운로드의 동일 CLI 실행 로그가 보존돼 있다거나, 이번 문서 작업에서 대용량 원본을 다시 받았다고 주장하지 않습니다.

해외 사용자는 먼저 해당 지역 목록을 조회해야 합니다. 예를 들어 미국은 `nudl list -b hyundai -r US`입니다. KR 모델을 해외 내비에 적용하거나, 같은 벨로스터 N으로 표시되는 `JSNBS.S5BMC.KR` 표준형 Gen5W를 이번 표준형 Gen5와 혼동하지 마세요. [현대 공식 업데이트 사이트](https://update.hyundai.com/KR/KO/home)는 별도의 원본 확보 경로입니다.

## 2. 원본 보존과 패키지 식별

다운로드 폴더는 원본으로 남기고 복사본에서 분석했습니다. `.ver`의 모델·버전·파일 크기·CRC와 별도로 계산한 SHA-256을 기록했습니다. `nudl verify`의 파일 검증은 수정 OTA의 서명 검증이나 실차 설치 호환성 검증과 다릅니다.

패키지에는 지도·음성 데이터뿐 아니라 두 펌웨어 분기가 있었습니다.

```text
Veloster_N_BlueLink/
  Veloster_N_BlueLink.ver
  DAUDIOPLUS/kor/jsn/update/system/update_package.zip
  STDGEN5/kor/hyundaiF/update/system/update_package.zip
  BIN/
  DATA/
  vr2_6/
```

보관 원본과 다시 대조한 해시입니다.

| 외부 업데이트 ZIP | 크기(byte) | SHA-256 |
| --- | --- | --- |
| `DAUDIOPLUS/.../update_package.zip` | 246782903 | `0d0990ff2d553bcba5b9f93e2a8ca2a43b4c1c2b500def45ad94779e47245a77` |
| `STDGEN5/.../update_package.zip` | 285039846 | `f120aa8f67785ae90c8dd41e289484a999bed57729d5a851b71846942fdf27cf` |

다른 해시의 파일은 원인을 확인하기 전 같은 입력으로 취급하지 않습니다. 두 분기가 같은 최상위 패키지에 있다고 내부 구성요소의 빌드까지 같지는 않습니다.

## 3. 암호화 ZIP과 시스템 이미지 분석

1. 외부 `update_package.zip`에서 `update.zip`, `otacerts.zip`을 확인했습니다. 암호화는 ZipCrypto였습니다.
2. 당시 [bkcrack](https://github.com/kimci86/bkcrack) 1.8.1로 알려진 ZIP·인증서 구조를 이용해 암호화 상태를 복구했습니다. 첫 분기에서 얻은 인증서 바이트를 다른 분기와도 대조했습니다.
3. 원본을 덮어쓰지 않고 복호화 파일을 따로 생성한 뒤 항목별 CRC를 검사했습니다. 이후 업데이터의 속성 기반 비밀번호 계산과 복구 결과를 교차 검증했습니다. **압축을 푸는 능력이 OTA 서명 권한을 뜻하지는 않습니다.**
4. 내부 업데이트에서 `system.ext4`, `boot.img`, `recovery.img`, sparse 이미지를 식별했습니다. Android sparse 형식은 raw로 변환한 뒤 파일시스템으로 읽었습니다.
5. `debugfs` 등 읽기 전용 도구로 `build.prop`, `/app`, `/framework`를 추출했습니다. 분기·버전별 파일을 섞지 않고 분석했습니다.

raw ext4 파일을 확보한 이후의 읽기 예시는 다음과 같습니다.

```sh
debugfs -R 'cat /build.prop' work/system.ext4
```

이 명령은 해당 파일이 이미 준비되어 있다고 가정합니다. 공개 폴더에는 OEM 자동 복호화·추출 전체 도구나 복구한 아카이브 자료, 제조사 이미지가 포함되지 않습니다. 여기서는 실제 수행한 분석 단계와 검증 기준을 기록합니다.

헤드유닛은 Android 4.2.2/API 17, `tcc893x` 플랫폼으로 확인했습니다. 같은 패키지의 Qualcomm MDM9607 파일은 모뎀용이어서, 이를 내비 CPU라고 보았던 초기 해석을 바로잡았습니다.

## 4. N 모드와 시스템 서비스 연결 추적

순정 N 모드는 `HKMC_NModeApp`, 패키지 `com.hkmc.app.nmode.ui`였습니다. APK만 보지 않고 짝인 ODEX와 같은 버전의 프레임워크를 모아 deodex하고 리소스·smali·서비스 구현을 함께 확인했습니다.

[REA](https://github.com/morluto/rea), smali/baksmali와 필요한 네이티브 분석을 사용했습니다. 조사한 주요 연결은 다음과 같습니다.

- N 모드 UI → OEM Manager/Listener → 차량 데이터 콜백.
- 엔지니어링 모드 → Factory Test → OEM USB 모드 요청.
- 업데이트/recovery → 무결성·서명 검증.
- 이후 추가 데이터 조사: 내비 수신 데몬 → `hscan_mux` → 수신 레코드.

`WIFI ADB` 문자열만으로 실제 메뉴 진입을 보장할 수 없었고, ZIP을 풀었다고 수정 OTA를 서명할 수 있는 것도 아니었습니다. 두 경로를 성공한 설치 방법으로 기록하지 않았습니다.

## 5. USB 연결과 실차 원본 대조

ADB 연결 전에는 USB로 내보낸 로그를 확인했습니다. 디버그 설정 요청과 PC에서 USB 장치가 나타나는 것을 구분했고, 코드에서 찾은 Factory Test 입력만으로 물리 연결이 성공한다고 보지 않았습니다. 이후 케이블 구성을 바꾼 연결에서 Telechips/DiPO 열거와 ADB 접속에 성공했습니다. 메뉴·암호·입력 순서·케이블 한계는 [ADB 안내](ADB.md)에 정리돼 있습니다.

연결 후 실차 속성과 APK/ODEX/프레임워크 원본을 읽어 해시를 대조했습니다. 실차는 `eng.vmadmin.20250902.081552`, 다운로드 분석본은 `260114`였습니다. N 모드 APK/ODEX는 일치했지만 비교한 프레임워크 30개 파일은 달랐습니다. **앱 한 쌍이 같다는 것으로 전체 시스템을 동일시할 수 없었습니다.**

현재 추가 수신기가 product `jsn_kr`와 정확한 incremental 빌드를 확인하는 이유입니다. 최신 공식 펌웨어로 업데이트하면 ADB나 앱 호환성이 해결된다는 근거는 없습니다.

## 6. UI 시제품에서 독립 앱으로 전환

처음에는 에뮬레이터와 합성 값으로 화면을 검증하고, 순정 N 모드 진입 흐름에 자체 계기판을 붙이는 방법을 시험했습니다. 완전 재부팅 뒤 추가 버튼이 사라지는 현상이 반복돼 최종 배포 방향을 바꿨습니다.

자체 UI를 `com.veloster.dashboard` 독립 패키지로 만들고 ‘내 앱’에서 실행하도록 구성했습니다. OEM API는 컴파일 선언만 사용하고 제조사 클래스 구현은 APK에 포함하지 않았습니다. 순정 홈에 새 앱이 자동으로 나타나는 문제까지 해결한 것은 아니므로 [설치 안내](INSTALL.md)의 진입점 한계는 남아 있습니다.

API 17/DEX 035에 맞춰 빌드했습니다. 일반 로컬 서명의 ‘내 앱’은 실차에서 거절된 이력이 있어, 실제 설치된 배포본은 확인된 공개 AOSP 테스트 인증서와 V1 서명을 사용합니다. 이것이 OTA 키나 모든 기기에서의 설치 허가를 뜻하지는 않습니다.

## 7. 차량 값 검증과 추가 수신 경로

우선 기존 NMode/Vehicle/BCM/PGS 콜백을 연결했습니다. 단위·미수신 기본값·무효값·60초 만료를 구분했고, TPMS는 실제 계기판의 네 숫자를 비교한 뒤 psi 단위를 확인했습니다.

추가 정보용 Binder 리스너에서는 준비 알림만 오고 관찰 구간의 차량 데이터는 0건이었습니다. 상류를 더 추적해 순정 소비자가 쓰는 아래 경로를 찾았습니다.

```text
차량 측 입력 → hscand → hscan_mux → 기존 순정 소비자
                              └→ 독립 계기판의 수신 전용 연결
```

OEM 통신 초기화 runner를 실행하거나 ECU 진단 요청을 보내지 않고, 기존 로컬 소켓을 추가 수신자로 읽었습니다. 264바이트 프레임의 헤더·체크섬·항목 길이·허용 function을 검증하고, 제한된 큐와 연결 단절 처리를 넣었습니다.

v5에 흡기온·배터리 전압/SOC·조향속도·TCS/ESC/ABS·바퀴 속도 해석을 연결했고, 2026-10-22 최초 실차 표시를 확인했습니다. 배기온과 e-LSD는 확정하지 못해 미확인 숫자로 채우지 않았습니다. 냉간 재부팅·장시간 운행·실제 개입 상태 전환은 별도 검증 과제입니다.

## 8. 공개 코드 재현

공개된 자체 앱은 OEM 이미지를 받지 않고도 빌드·테스트할 수 있습니다.

```sh
python3 scripts/test.py --java /path/to/jdk17/bin/java
python3 scripts/verify_release.py
```

[빌드 안내](BUILD.md)에 따라 외부 SDK 도구 경로를 지정하면 서명 전 APK가 생성됩니다. 공개 준비 시 두 앱의 DEX·리소스·매니페스트가 배포 APK와 일치했고 합성 입력 테스트 9묶음이 통과했습니다. 이 검사는 실차 시험과 구분합니다.

다른 지역·기기로 확장하려면 모델·빌드와 ABI·데이터 형식을 각각 확인해야 합니다. 원본·개인 로그는 로컬에 보관하고, 공개 카탈로그의 인증 필드나 기기 식별자를 그대로 게시하지 마세요. 공개 MIT 코드는 제조사 펌웨어의 재배포 권한을 포함하지 않습니다.
