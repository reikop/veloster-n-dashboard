# Installation audit — 2026-10-26

This audit responds to reports that installers could not find **My Apps** on the factory screen. It reviews the public package, source, historical vehicle records, and installation tooling. It is not a new vehicle session or a new compatibility claim.

## Primary finding

The initial N Dashboard v5 and My Apps v8 APKs were the intended independent apps. The dashboard remains v5; My Apps v9 adds the new quick-entry service. Both declare launchable activities. My Apps explicitly launches `com.veloster.dashboard/.DashboardActivity`; it does not depend on the old button inside factory N Mode.

The research vehicle also had separately modified **AllMenu** and **DownloadMenu** apps. Those modifications supplied **HOME → All menus → Information → My Apps**. They were omitted from the initial public installation path. Installing the two independent APKs therefore did not reproduce the research vehicle's visible launch path. A missing icon alone does not establish that app installation failed.

## Changes to the installation workflow

| Gap | Revised workflow |
| --- | --- |
| App installation was presented without a complete screen entry path | The primary route is now [v9 Quick entry](QUICK_ENTRY.md): first ADB launch, explicit opt-in, then screen-only reopening. Standard overlays/notifications require no OEM menu patch. [OEM-menu instructions](MENU_ENTRY.md) are an optional fallback for matching originals; OEM APKs are generated locally rather than bundled publicly. |
| Repeated manual backups could overwrite fixed backup filenames | The basic installer creates a fresh backup directory each run; the advanced command rejects an existing destination. |
| Fixed remote temporary filenames could leave stale APKs after failed uploads | The guarded command verifies uploaded and installed APKs instead of relying only on an ADB exit code or a prior staged filename. |
| Target identity checks depended on manually reading properties | The guarded workflow checks the supported device/build; menu work additionally checks original APK/ODEX identities. |
| English README linked only to Korean operational installation instructions | [English installation and recovery](INSTALL_EN.md) accompanies the [Korean guide](INSTALL.md); menu instructions are also bilingual. |
| ADB launch success could be mistaken for a complete installation | Screen-only reopening is a separate acceptance step, followed by a separately observed normal reboot check on the actual vehicle. |

The supported installation target remains **`jsn_kr`, API 17, `eng.vmadmin.20250902.081552`**. A matching Android version or a similar vehicle name is insufficient. These changes do not remove the additional HSCAN build guard. The shortcut has a standard Android design; this does not establish all-model compatibility or expand the guarded installer target.

## Findings retained as limitations

- **Cold boot and extended operation:** v5 initial launch and reception were checked previously; new tooling and offline checks do not establish full cold-boot, prolonged-operation, or active ABS/TCS/ESC transition reliability.
- **PGS startup retry:** source review found that `VehicleDataClient` retries missing power/NMode/vehicle services, but not a failure limited to the PGS rear-view-status subscription. If only PGS is unavailable at startup, retry waits for the next Activity start. This is a static finding, not an observed vehicle failure. The installation/menu changes do not modify that APK behavior.
- **Backup and downgrade:** APK backup does not preserve app preferences. v3 is an older version; automatic downgrade or preservation of v5 settings under v3 is not established. Uninstallation loses app settings.
- **Signing:** distributed app APKs are already signed. Source builds are unsigned. The public AOSP test certificate is neither a private publisher identity nor an OTA signing key.
- **No missing custom HSCAN service:** `hscan_mux` is part of the observed OEM `hscand` path. The dashboard does not require a separately distributed custom daemon. It still depends on the existing OEM service and exact supported build.
- **Explicit shortcut restoration only:** v9 defaults to OFF. When enabled, boot/package replacement requests restoration of its shortcut service, not a full-screen app launch. Force-stop, permissions, and OEM policy can prevent access; this needs vehicle testing.

## Verification scope of the new work

The v9 source compiles against the API16 Android JAR into API17/DEX035 output and its APK is signed. Host checks cover quick-entry policy, the actual service/receiver with Android stubs, boot opt-in, screen/keyguard transitions, overlay/notification failures, teardown, dragging/rotation, component isolation, and guarded installation failure paths. These are host/offline checks, not emulator or vehicle execution. Existing telemetry host checks remain in place.

No vehicle/emulator test, actual new installation, or reboot was performed for v9. The `v2026.10.26` prerelease includes the new tooling and documentation; the original `v2026.10.23` bundle remains unchanged. Historical v8 preview images are labelled as such and do not depict v9's added Quick entry control.

Unchanged dashboard behavior and historical evidence must remain distinct from the new v9 shortcut and installation tooling. Refer to [VALIDATION.json](../VALIDATION.json) for recorded check scope and [release provenance](RELEASE.md) for the original app versions.

## 한국어 요약

‘내 앱’이 보이지 않았던 핵심 원인은 잘못된 APK가 아니라 **순정 메뉴 진입점의 공개 설치 절차 누락**이었습니다. 연구 차량에서는 전체 메뉴와 다운로드 메뉴를 별도로 수정했지만, 최초 공개 배포에는 독립 APK 두 개만 제공했습니다.

이제 설치·백업·대상 확인·APK 재조회 후 v9 [실행 바로가기](QUICK_ENTRY_ko.md)를 명시적으로 켜고 화면 터치로 재실행을 확인하도록 안내합니다. 표준 Android 방식이므로 순정 메뉴 수정이 필수는 아닙니다. 버튼·알림 모두 접근 불가능할 때 [선택형 메뉴 절차](MENU_ENTRY_ko.md)를 검토합니다. 영어 설치·ADB 안내도 추가했습니다. v5는 변경하지 않았고 v9는 호스트 검사·서명만 마친 실차 미검증 사전 배포입니다. 다른 차량 호환성이나 냉간 재부팅 검증 완료를 주장하지 않습니다. PGS 초기 연결 재시도 누락은 소스에서 확인한 미해결 항목으로 남깁니다.
