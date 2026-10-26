# 선택 사항: 순정 메뉴에 ‘내 앱’ 추가

[English](MENU_ENTRY.md) | **한국어** | [설치 안내](INSTALL.md)

**먼저 [내 앱 v9의 실행 바로가기](QUICK_ENTRY_ko.md)를 사용하세요.** 화면 가장자리 버튼·알림은 표준 Android 기능을 이용하며 순정 메뉴를 수정하지 않습니다. 아래는 기존 연구 차량에서 확인했던 **홈 → 전체 메뉴 2페이지 → 정보 → 내 앱** 경로를 만드는 고급 대안입니다. 모든 차량에 통하는 패치가 아닙니다.

기존 경로는 2026-10-20 실차에서 확인했습니다. 이번 공개 도구는 2026-10-26에 PC에서 재구성·검증했고, 새 도구로 실차 설치·냉간 재부팅을 다시 시험한 것은 아닙니다. 첫 메뉴 갱신 때 항목 수가 달라져 순정 메뉴 정렬이 기본 순서로 재생성될 수 있습니다.

## 준비

- 먼저 [앱 설치](INSTALL.md)를 완료하고 ADB로 최신 ‘내 앱’과 N 계기판이 열리는지 확인합니다.
- 대상은 `jsn_kr`, API 17, `eng.vmadmin.20250902.081552`입니다. 원본 APK·ODEX·프레임워크도 [프로필](../menu-entry/profile.json) 해시와 일치해야 합니다. 다른 버전이면 중단하며 해시를 바꿔 우회하지 않습니다.
- PC에 Python 3.9 이상, JDK 17, ADB가 필요합니다. 단계마다 새 출력·백업 폴더를 지정합니다.
- 예시는 bash/zsh입니다. Windows는 `py -3`과 실제 일련번호를 직접 사용하고, 가상환경 Python 경로를 `.menu-venv\Scripts\python.exe`로 바꿉니다.

## 1. 차량 원본 읽기 — 변경 없음

```sh
adb devices -l
VN_SERIAL='YOUR_DEVICE_SERIAL'
python3 scripts/menu_entry.py capture --serial "$VN_SERIAL" --out private-backups/menu-originals-01
```

순정/활성 메뉴 APK, ODEX, 필요한 프레임워크를 읽어 확인합니다. 알 수 없는 기존 메뉴 수정본은 백업 후 중단합니다. 본인이 이전에 만든 묶음을 설치했다면 `--bundle`로 같은 묶음을 지정합니다. 연구 차량에서 검토한 과거 수정본은 해시로 별도 인식합니다.

## 2. PC 빌드 도구 준비

```sh
python3 -m venv .menu-venv
.menu-venv/bin/python -m pip install -r menu-entry/requirements.txt
python3 scripts/prepare_menu_tools.py --out build/menu-tools-01
```

다운로더는 고정 해시의 외부 JAR 4개와 **공개 AOSP 테스트 키·인증서**를 로컬 폴더에 받습니다. 시스템 전체에 도구를 설치하거나 차량에 연결하지 않습니다. 출처·해시는 [tool-sources.json](../menu-entry/tool-sources.json)에 있습니다. JDK는 별도 준비하며 아래 실제 경로를 지정합니다.

## 3. 로컬 수정본·복구본 생성 — ADB 사용 안 함

```sh
.menu-venv/bin/python scripts/build_menu_entry.py \
  --capture private-backups/menu-originals-01 \
  --tools build/menu-tools-01 \
  --java /path/to/jdk17/bin/java \
  --key build/menu-tools-01/platform.pk8 \
  --cert build/menu-tools-01/platform.x509.pem \
  --out build/menu-bundle-01
```

원본·도구·인증서 해시를 확인하고 원래 코드와 리소스를 유지한 채 검토한 메뉴 호출만 추가합니다. DEX 왕복 변환, 원래 명령 보존, API17 서명, 승인된 전체 payload 해시까지 확인합니다.

결과는 `downloadmenu-candidate.apk`, `allmenu-candidate.apk`와 각각의 `fallback.apk`, 검증 기록 `receipt.json`입니다. 복구본은 원래 코드·리소스를 같은 versionCode 2로 재구성한 것으로, 공장 APK와 바이트까지 같지는 않으며 이전 메뉴 정렬까지 복구하지 않습니다. **생성 결과에는 제조사 코드가 들어가므로 공개 업로드하지 않습니다.**

## 4. 백업 후 적용

정차·화면 확인 상태에서 같은 차량에 연결합니다. `--check`를 추가하면 백업·확인만 하며, 실제 적용 때는 또 다른 새 백업 폴더를 사용합니다.

```sh
python3 scripts/menu_entry.py install --serial "$VN_SERIAL" \
  --bundle build/menu-bundle-01 --backup private-backups/menu-install-01 --parked
```

대상과 순정 APK·ODEX, 기존 설치본, 현재 보조 앱, 수정본·복구본 4개의 payload, 저장공간을 먼저 확인합니다. 백업 → 고유 임시 경로 업로드 → 다시 읽어 해시 확인 → `pm install` 성공 확인 → 설치된 APK 재확인 순서입니다. DownloadMenu 뒤 AllMenu를 적용합니다. `/system` 쓰기·데이터 삭제·앱 제거·강제 종료·재부팅은 수행하지 않습니다.

두 앱 변경은 원자적이지 않습니다. 두 번째 단계 실패 시 첫 번째는 적용됐을 수 있으므로 오류 출력·묶음·백업을 보관하고 아래 복구를 사용합니다.

## 5. 화면 확인

1. 메뉴를 나와 홈으로 돌아간 뒤 전체 메뉴를 다시 엽니다.
2. **정보 → 내 앱**을 엽니다. 연구 차량에서는 2페이지였으며 위치가 달라질 수 있습니다.
3. **N 계기판**을 열고, 홈에 돌아온 후 PC 없이 다시 엽니다.
4. 정차 상태에서 자연스러운 완전 종료·재시동 후 같은 경로를 확인합니다. 이번 도구 공개만으로 다른 차량의 재부팅 시험이 완료된 것은 아닙니다.

갱신이 안 됐다고 추측으로 강제 재부팅하지 말고 ADB와 복구 경로를 유지합니다. 켜 둔 [실행 바로가기](QUICK_ENTRY_ko.md)는 순정 메뉴와 별개로 사용할 수 있습니다.

## 복구

**적용에 쓴 같은 묶음**과 **새 백업 폴더**를 지정합니다.

```sh
python3 scripts/menu_entry.py restore --serial "$VN_SERIAL" \
  --bundle build/menu-bundle-01 --backup private-backups/menu-restore-01 --parked
```

알려진 설치본인지 검사하고 같은 versionCode 2의 원래 코드 복구본을 AllMenu → DownloadMenu 순서로 설치합니다. ‘내 앱’·N 계기판이 남아 있을 필요는 없습니다. 순정 앱 제거·데이터 삭제·versionCode 1로 강제 다운그레이드는 하지 않습니다. 중간에 연결이 끊기면 미완료로 기록하며, 재연결 후 기록을 확인하고 새 백업 폴더로 다시 진행합니다. 알 수 없는 설치본이나 대상 불일치가 나오면 중단하고 분석용 백업을 보존합니다.
