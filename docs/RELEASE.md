# Release provenance — 2026-10-26 prerelease

**v9 has not been tested on a vehicle or emulator.** The new shortcut and installation tooling passed host checks; actual HOME-screen visibility and restoration after a normal full reboot remain required. N Dashboard v5 is unchanged.

## Current files

| App | Package | versionCode | Evidence |
| --- | --- | --- | --- |
| My Apps v9 | `com.veloster.carplayhub` | 9 | New opt-in standard Android floating/notification shortcut; API16 compilation, DEX035, API17-compatible V1 signature and host lifecycle checks |
| N Dashboard v5 | `com.veloster.dashboard` | 5 | Unchanged release; installed and initial telemetry observed on 2026-10-22 |
| My Apps v8 | `com.veloster.carplayhub` | 8 | Historical vehicle-installed APK without Quick entry; not the current hub source |
| N Dashboard v3 | `com.veloster.dashboard` | 3 | Historical dashboard APK; not the current dashboard source |

Hashes, sizes and version names are in [release.json](../release.json) and [SHA256SUMS](../SHA256SUMS). Current source corresponds to dashboard v5 and hub v9. The v8 source remains in repository history at commit `c00c650`; its original APK bytes have not changed. Restoring an older APK may be blocked by version checks and can require data loss; historical files are not an automatic rollback mechanism.

## What changed

The old public package omitted the menu modifications used to make My Apps reachable on the research car. The new default route uses **My Apps → Quick entry → Floating + notification → Save**, followed by a screen-only HOME/reopen test. It does not require an OEM home modification and does not opt users in merely by installing the APK.

The [guided installer](INSTALL_EN.md) now checks the exact supported target, creates unique backups, verifies uploaded and installed APKs, and opens My Apps only after successful installation. It records launch acknowledgement separately from actual shortcut visibility and reboot acceptance. It does not report those screen checks as passed.

The [optional OEM-menu kit](MENU_ENTRY.md) rebuilds the known factory-menu additions from a user's privately captured, exact-match originals. Four generated candidate/fallback APKs were rebuilt offline, checked against pinned payloads, and compared with the historical reviewed code/resources. OEM APKs, framework implementations and keys are **not bundled**. The optional downloader retrieves external hash-pinned tools and the public AOSP test key into a private local build folder.

## Verification

- 10 Java host test programs, including telemetry and quick-entry state/position checks.
- 40 Python unittest cases: 28 guarded installer cases, 10 guided-install cases, and 2 quick-entry integration checks. The latter execute 14 scenarios against the actual service/receiver with small Android stubs and inspect manifest isolation.
- My Apps v9 unsigned build payload matches its signed APK, V1 signature is cryptographically checked, and defined classes remain project-authored `com.veloster` classes.
- Native menu output: original resources/instructions preserved except the reviewed hooks; DEX roundtrips, API17 signatures and payload identities checked. The new tools have not been installed on a vehicle.
- [Installation audit](INSTALL_AUDIT.md) records remaining gaps, including a static PGS startup-retry issue in unchanged dashboard v5.

These checks are not a real Android window manager, notification panel, boot delivery, vehicle display or long-duration telemetry test. Detailed records and source hashes are in [VALIDATION.json](../VALIDATION.json); earlier dated fields retain their initial-export meaning.

Common signing certificate SHA-256:

```text
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

This is a public AOSP platform test certificate, not a private publisher identity or OTA key. File hashes and valid V1 signatures establish consistency, not exclusive authorship or universal installation permission. Ordinary source app builds remain unsigned; no signing key is bundled.

## Archives and images

`v2026.10.26` contains a new complete ZIP with the current apps, sources, scripts and guides. The original `v2026.10.23` APKs and ZIP are retained unchanged and do not contain the new entry solution. Use the new complete archive for the guided installer rather than downloading only an APK.

The owner-provided overview photo is an actual car image. Its pixels/orientation were retained while GPS, camera/date metadata and auxiliary MPO images were removed. The My Apps v8 previews are historical browser renderings, not v9 or vehicle captures. The selected earlier car screenshot is dated 2026-10-20. No new screenshots were presented as evidence of v9 running on a vehicle.

## 한국어

이번 v9는 **실차 시험 전 사전 배포본**입니다. 설치만 하고 ‘내 앱’을 찾을 수 없었던 문제를 해결하기 위해 표준 Android 가장자리 버튼·알림을 추가하고, 백업·설치·재조회·첫 실행을 한 명령으로 묶었습니다. 바로가기는 사용자가 켜야 하며 홈에서 재실행·완전 재부팅 복원을 실제 차량에서 확인해야 합니다. 기존 계기판 v5는 변경하지 않았습니다.

순정 메뉴 경로는 별도 선택 사항입니다. 정확한 원본·빌드에만 적용하고, 제조사 APK는 각 사용자 PC에서 생성합니다. 새로운 앱의 실차 동작을 이전 v8 설치 이력으로 보증하지 않습니다. 원래 릴리스는 그대로 보존하고 새 전체 ZIP에 이번 도구·설치 안내를 포함합니다.
