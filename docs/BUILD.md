# 공개 소스 빌드와 테스트

[Documentation / 문서 안내](README.md) · [Status / 확인 범위](STATUS.md)

## 설치 파일과 빌드 결과 구분

바로 설치할 파일은 `apks/`의 기존 서명된 배포본입니다. 아래 공개 빌더는 **서명 전 APK**까지만 만듭니다. 개인키·OEM SDK 구현·순정 APK·차량 로그가 필요하거나 포함되지 않으며 차량에 연결하지 않습니다.

새 코드를 실제 차량에 설치하려면 해당 기기가 허용하는 서명과 Android 4.2 호환 V1 서명 형식이 필요합니다. 아무 로컬 키로 서명하면 되는 것으로 확인하지 않았습니다. 기존 설치본 업데이트에는 서명 일치도 필요합니다. 이 배포에는 키 발급·OTA 서명·차량 신뢰정책 변경 기능이 없습니다.

## 준비물

- Python 3.9 이상과 JDK 17 (`java`, 같은 디렉터리의 `javac`).
- 외부 Android SDK `android.jar` API 16 또는 17. 공개 준비 검증은 기존 API 16 JAR로 컴파일하고 min/target SDK는 17로 지정했습니다.
- AAPT2 실행 파일과 D8가 포함된 R8 JAR. 도구는 사용자 설치 경로를 인자로 전달하며 이 폴더에 배포하지 않습니다.

소스는 Java 7 언어/클래스 수준으로 컴파일하고 D8 `--min-api 17`을 사용합니다. 제조사 API 선언은 별도 클래스 디렉터리에 컴파일해 javac의 classpath에만 넣고, 앱 DEX에 패키징하지 않습니다. 실제 실행에는 차량의 OEM `automotive` 라이브러리가 필요합니다.

## 호스트 테스트

```sh
python3 scripts/test.py --java /path/to/jdk17/bin/java
python3 -m unittest discover -s tests -p 'test_*.py'
python3 scripts/verify_release.py
```

테스트는 임시 폴더에서 컴파일하고 끝나면 삭제합니다. Telemetry 유효성·60초 만료·TPMS·추가 신호·프레임 분할/오류 처리와 Bluetooth/무선 시험/재시작의 상태 정책을 검사합니다. 합성 입력만 사용하며 실차 로그, Android 실행 환경, ADB가 필요하지 않습니다. UI·실제 Bluetooth 하드웨어·실차 통신을 재현하는 테스트는 아닙니다.

## 빌드

공개 폴더에서 아래 `/path/to/...`를 본인의 도구 경로로 바꿉니다. 기존 출력 폴더는 덮어쓰지 않으므로 다시 빌드할 때 새 `--out`을 지정합니다.

```sh
python3 scripts/build.py dashboard \
  --java /path/to/jdk17/bin/java \
  --android-jar /path/to/android.jar \
  --aapt2 /path/to/aapt2 \
  --r8-jar /path/to/r8.jar \
  --out build/dashboard

python3 scripts/build.py hub \
  --java /path/to/jdk17/bin/java \
  --android-jar /path/to/android.jar \
  --aapt2 /path/to/aapt2 \
  --r8-jar /path/to/r8.jar \
  --out build/hub
```

결과는 각각 `dashboard-UNSIGNED.apk`, `hub-UNSIGNED.apk`입니다. APK의 리소스·DEX 구성은 확인하지만 ZIP 정렬과 서명은 수행하지 않습니다. 기존 배포본과 바이트 단위 재현을 보장하지 않습니다. 도구 버전·타임스탬프·서명 과정이 영향을 줍니다.

배포 APK는 API 17, DEX 035, V1/JAR 서명 형식입니다. V2 전용 서명은 Android 4.2에 맞지 않습니다. 서명 배경은 [AOSP 앱 서명 문서](https://source.android.com/docs/security/features/apksigning)를 참고하세요. 새 서명본의 설치 성공과 차량 동작은 별도 검증해야 합니다.

## 공개 폴더를 유지할 때

개인키, 원본 차량 로그/스크린샷, VIN·기기 일련번호·페어링 주소, 순정 APK/ODEX/펌웨어, 개인 백업은 추가하지 않습니다. `build/`, `private-backups/`, 키 확장자는 `.gitignore`에 제외되어 있습니다. 새 바이너리를 배포하면 `release.json`, `SHA256SUMS`, `VALIDATION.json`과 확인 범위를 함께 갱신합니다.

## 실행 바로가기와 선택형 메뉴 도구

현재 소스는 내 앱 v24 / `1.23-wireless-preview`·계기판 v7의 미배포 preview이며 OEM 화면 복귀·QuickBoot·앱 업데이트·연결 진단과 [무선 수신기 조립](WIRELESS_CARPLAY.md)을 포함합니다. 기존 배포 v10/v6와 버전 및 해시로 구분하며, 로컬 서명 후보도 실차 검증본이 아닙니다. 부팅 시 직접 켜 둔 바로가기를 복원하며, CarPlay는 선택 폰과 사용자 opt-in·재시도 예산을 확인해 자동 연결을 시도합니다. 실제 차량 부팅 전달은 미검증입니다. 호스트 검사는 실제 서비스·리시버를 작은 Android 스텁과 실행하지만 실제 차량 표시/부팅 전달을 대신하지 않습니다.

[단일 설치 명령](INSTALL.md)은 Python 표준 라이브러리와 ADB만 사용합니다. 빌드 도구나 키가 필요하지 않습니다. [선택형 OEM 메뉴 재구성](MENU_ENTRY_ko.md)에만 별도 JDK·Androguard·smali·개인 원본이 필요합니다. 관련 소스는 `menu-entry/`와 `scripts/build_menu_entry.py`이며, 외부 키를 받아 해당 로컬 메뉴 APK를 서명하는 경로입니다. OEM 코드가 포함된 생성물을 공개하지 않습니다.

다국어 문자열은 `translations/en.json`, `ko.json`을 수정하고 `python3 scripts/generate_translations.py`로 Java 카탈로그를 갱신합니다. 빌더는 카탈로그 일치 여부를 검사하고 `res/values`, `res/values-ko`의 앱 이름 리소스도 컴파일합니다. 두 앱 소스에는 [v6/v10 이후 변경](NEMESIS_REVIEW.md#implemented-in-our-code-unreleased)이 포함됩니다. 배포 APK는 v6/v10 그대로입니다.

## 앱 업데이트 준비

[앱 업데이트 문서](APP_UPDATES.md)에 서명된 APK로 온라인/USB 공용 묶음을 만드는 절차가 있습니다. 별도 업데이트 승인 키를 사용하며 개인키는 저장소 밖에 둡니다. 도구는 로컬 파일만 만들고 배포·설치하지 않습니다.

## Removed experiments / 정리된 실험

The AVRCP Cover Art and SystemUI experiment sources were removed from this repository (2026-11-24); they were never part of the released APKs.

앨범아트(AVRCP)·SystemUI 실험 소스는 이 저장소에서 삭제했습니다(2026-11-24). 배포 APK에는 포함된 적이 없습니다.

<a id="offline-oem-system-experiments--오프라인-시스템-수정본"></a>
<a id="native-transport-and-private-trial-apks--native-전송부와-비공개-시험-apk"></a>

## Optional wireless CarPlay preview

The Hub builder includes the exported receiver Java sources. Add both
`--carplay-crypto /path/to/libvn-carplay-crypto.so` and
`--carplay-crypto-manifest /path/to/build-manifest.json` to package the locally
built API17 ARMv7 JNI module. Without that pair, settings remain available but
connection attempts report the missing module. See the
[wireless preview guide](WIRELESS_CARPLAY.md#재현-빌드) for the native recipe,
combined-build license and validation limits. AP/OEM installation is separate;
the old v16 default path does not select the new explicit CarPlay installation profile.
The local profile validates APK/JNI/AP upgrades and can include a private OEM
auth APK with explicit original-code fallback. OEM hardware authentication and
vehicle installation remain unverified.

For a locally reviewed OEM service candidate, add
`--oem-services-jar-sha256 <64 lowercase hex>` to a Hub build. The builder writes
an isolated `HubApplication.java` under the new output directory, changing only
the read-only JAR expectation; the default source keeps its current `03c4…` pin.
`build-manifest.json` records the resolved pin and generated source hashes. Pair
the APK with the matching framework candidate in its installation profile. This
option packages no OEM code and grants no installation or native admission authority.

`SOURCE_SHA256SUMS`, `SHA256SUMS` and `VALIDATION.json` remain earlier build/distribution snapshots. A new preview uses its own local build manifest and
separate validation receipts; the distribution snapshots do not certify the current preview.

Optional AAC-ELD packaging uses `scripts/build_carplay_eld.py` and the paired
`--carplay-eld` / `--carplay-eld-manifest` options alongside the crypto pair.
See the same wireless guide for the recipe and runtime capability limits.

## Optional API17 HTTPS module

The source-only Hub build uses system Java TLS; the generic API17 runtime could not connect to GitHub through that path. To include the separately built modern transport, provide all three files:

```sh
python3 scripts/build.py hub \
  --java /path/to/jdk17/bin/java --android-jar /path/to/android.jar \
  --aapt2 /path/to/aapt2 --r8-jar /path/to/r8.jar \
  --tls-helper /path/to/native-build/bin/vn-curl-api17-armv7 \
  --tls-ca /path/to/native-build/cacert.pem \
  --tls-notices /path/to/native-build/THIRD_PARTY_NOTICES.txt \
  --out build/hub-with-https
```

The [pinned offline recipe](../third_party/network/README.md) uses NDK r22b and externally supplied, hash-checked source archives. The app builder accepts an ARM32 PIE ELF and public CA material, rejects private-key PEM input and records actual hashes in APK assets. It does not fetch a helper or modify system libraries. The helper is placed in the standard ARMv7 native-library directory for installation-time extraction; its licenses are packaged in assets. `scripts/bootstrap_updater.py` authenticates a prepared Hub bundle locally by default and can later preflight/install only My Apps, preserving an existing dashboard trial. See [the bootstrap steps](APP_UPDATES.md#first-installation-of-the-updater).

For the USB Wi-Fi preview, build the native helper with `scripts/build_tls_helper.py --dns cares` and the additional pinned c-ares source archive. Package it with `--tls-dns cares`; the default remains the system resolver for older builds. The capability flag must describe the actual binary. Without the isolated resolver metadata, the updater refuses external USB Wi-Fi leases. See [USB Wi-Fi preparation](USB_NETWORK.md).
