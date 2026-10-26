# 공개 소스 빌드와 테스트

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

현재 Hub 소스는 v9이며 부팅 시 직접 켜 둔 바로가기만 복원합니다. 호스트 검사는 실제 서비스·리시버를 작은 Android 스텁과 실행하지만 실제 차량 표시/부팅 전달을 대신하지 않습니다.

[단일 설치 명령](INSTALL.md)은 Python 표준 라이브러리와 ADB만 사용합니다. 빌드 도구나 키가 필요하지 않습니다. [선택형 OEM 메뉴 재구성](MENU_ENTRY_ko.md)에만 별도 JDK·Androguard·smali·개인 원본이 필요합니다. 관련 소스는 `menu-entry/`와 `scripts/build_menu_entry.py`이며, 외부 키를 받아 해당 로컬 메뉴 APK를 서명하는 경로입니다. OEM 코드가 포함된 생성물을 공개하지 않습니다.
