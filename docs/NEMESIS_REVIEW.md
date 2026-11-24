# NEMESIS review / 적용 가능성 검토

[Documentation](README.md) · [Compatibility](STATUS.md)

Initial review **2026-11-02**, upstream commit [`b0178ed`](https://github.com/xwtk/hmg_nemesis/tree/b0178ed95a217879e779750d7a28d9f6b677d06f), Manager **1.16.2**. The code adaptations below are implemented and host-tested. No vehicle installation or independent exploit reproduction was performed.

## Follow-up: 1.16.3–1.16.5 (2026-11-03)

Compared all48 changed-file entries across3 new commits from the original pin to
[`65c05f0` /1.16.5](https://github.com/xwtk/hmg_nemesis/compare/b0178ed95a217879e779750d7a28d9f6b677d06f...65c05f08def6c65b1f70c523a3a9fa68a423ad85).
Two further ideas are implemented independently in our **My Apps v14 local preview**:

- **Keep verified updates for later/offline installation.** AndroidUpdate retains
  one signed candidate per app with its original release metadata/signature.
  Cancelling an offer keeps it; **Find saved updates** works offline. Every reuse
  checks the signature, hash, APK signer, current installed version and exact
  target again. Replacement is published only after the new file is verified;
  failed downloads/writes preserve the old candidate. Changed/obsolete files and
  abandoned partials are cleaned up. Separate Activity offers use separate files.
  An online check can reuse the same candidate or explicitly report an offline
  fallback. There is no automatic installation or background polling.
- **Check the real default route despite a stale Wi-Fi label.** UpdateNetwork now
  checks the route before accepting ConnectivityManager's local-network label.
  A preferred cellular/unknown route or a mixed equal-metric route is rejected.
  The prior implementation failed the new Wi-Fi-label/rmnet0 regression test.
  The separately verified USB Wi-Fi lease remains an explicit path. We retain
  refusal of known cellular agents and do not copy the broader upstream prefix
  allowances for unknown `en*`/`ncm*` interfaces.

| New upstream area | Decision for this target |
| --- | --- |
| Dependency ordering, compatible replacement and `!id` conflicts | Our three-component installer already uses fixed ordering and exact manifest pins; no generic package/dependency engine added. Existing data-preserving APK replacement and readback remain. |
| Download cancellation, loading/retry/error UI | Existing foreground cancellation, bounded transfers, error handling and partial cleanup already cover the corresponding app workflow. Store-specific pages were not copied. |
| Saved core updates | Adapted as authenticated APK caching above; preserves old bytes until a replacement succeeds. |
| Network/modem and hotspot rules | Actual-route check adopted; no built-in modem opt-in added. |
| Built-in Wi-Fi tile | Upstream opens Android's existing Wi-Fi picker only when a module/feature is present. Our separate USB Wi-Fi screen remains; this update supplies no USB adapter driver. |
| Stopped/deferred service state and uninstall cleanup | Our Wi-Fi/SSH helpers are explicitly started and stopped with no auto-boot watchdog. No NEMESIS service manager to replace. |
| Manifest paths/IDs and nested ZIP descriptor selection | Existing strict signed metadata, exact archive member checks and fixed destinations retained. No basename-based descriptor lookup added. |
| USB handler use-after-free, mount wrapper, boot-hook wipe | Those NEMESIS native components are not in our project. No OEM boot/system or USB auto-execution code imported. |
| Update badges, dependency dialogs, resources and versioned media | Reviewed as parts of the package-store feature; not required by our two-app updater. Native/OTA payloads retained only in the private reference snapshot. |

Validation:68 Python tests,13 Java programs,19 signed-cache scenarios,
14 actual Activity lifecycle checks and11 actual network-gate scenarios. The last
three sets are within the Python suite. A further4 bootstrap tests and13 one-shot
orchestration tests passed. The Hub builds for API17/DEX035 and a signed v14 USB
bundle was prepared. The one-shot installer selects v14 and accepts only the
known exact v11/v12/v13 preview hashes in addition to the release/current candidate.
[Validation record](validation/nemesis-update-20261103.json).

No upstream source file was copied. The previously delivered v13 archive remains
unchanged. No vehicle install, new emulator execution or public push/release was
performed in this follow-up. The old API17 runtime evidence below is not a v14
runtime claim.

## Implemented in our code (unreleased)

Four engineering ideas were adapted to our existing API17 code. The source files below are project implementations; no upstream source file was copied.

1. **Display recovery:** [QuickEntryService](../apps/hub/src/com/veloster/carplayhub/QuickEntryService.java) now observes Mobis `SCREEN_OFF`/`SCREEN_ON` in addition to standard Android events. Each event source retains its own off state until its own on event. Service start requests, language changes and configuration refreshes cannot erase that state. PowerManager, keyguard and the saved shortcut mode must also allow visibility. Duplicate wake events leave one shortcut; they never launch an Activity. [QuickEntryReceiver](../apps/hub/src/com/veloster/carplayhub/QuickEntryReceiver.java) accepts `QUICKBOOT_POWERON` through its existing non-exported manifest receiver and restores only an opted-in service.
2. **Installation receipt persistence:** [Journal.save](../scripts/menu_entry.py) flushes and fsyncs the temporary receipt before replacement, then fsyncs its directory on POSIX hosts. A save failure propagates before the next install step. Failures before replacement retain the prior receipt; a failure syncing the directory after replacement is reported even though the new JSON is readable. Temporary files are cleaned up. This improves receipt persistence, not atomicity of a multi-package vehicle installation or guarantees against every storage failure.

3. **Late vehicle-service readiness:** [VehicleDataClient](../apps/dashboard/src/com/veloster/dashboard/VehicleDataClient.java) retries every missing subscriber, including PGS-only and HSCAN-construction failures. Retries use 2/4/8/16/30-second delays with one pending callback; successful registrations remain intact. Partial registration failures trigger cleanup, and callbacks from replaced listeners or old sessions are rejected. A failed BCM registration cannot enable telemetry based on its earlier ignition snapshot. Stop cancels pending retries; a new session resets the delay. This adapts the readiness/restart idea from `svc.c`, not its root watchdog implementation. It does not add drive-mode commands, process killing or a system service.
4. **Persistent bounded error records:** [ErrorLog](../shared/src/com/veloster/diagnostics/ErrorLog.java) and [DiagnosticApplication](../shared/src/com/veloster/diagnostics/DiagnosticApplication.java) capture Java failures before either app's UI or service starts, retaining the original Android exception handler. Both apps use private 32 KiB current/previous files; exception messages and payloads are excluded. Subscription and selected shortcut failures are also recorded. [Format, read-only retrieval and limits](DIAGNOSTICS.md).

Follow-up validation: **50 Python tests**, **13 Java host programs**, **9 subscription lifecycle scenarios** and **23 shortcut service/receiver scenarios** within those Python tests. Both apps build for API17 (API16 compile library, DEX035). The PGS-only regression test was also run against the previous HEAD source and failed at the expected missing retry. [Final validation record](validation/nemesis-deep-review-20261102.json)

Separately, the owner's navigation-info reference was implemented as an optional [Performance dashboard](PERFORMANCE.md). It is not an upstream NEMESIS UI or an additional decoding source. Sixteen language/size/data-state renders and four existing page renders passed host checks.

Initial adoption validation (before the follow-up): **48 Python tests**, **11 Java host programs**, including **23 actual service/receiver scenarios under Android stubs**, and an API17-targeted Hub build (API16 compile library, DEX035). New regression tests failed on the earlier code before these changes. [Validation record](validation/nemesis-adoption-20261102.json)

The original comparison builds were unsigned. The released v6/v10 APKs, `release.json` and installer-selected APKs remain unchanged. Subsequent [app update preparation](APP_UPDATES.md) advances the preview source to v7/v11 and prepares local signed candidates; these are not the vehicle-tested release.

Vehicle limitations: OEM event delivery/order, actual QuickBoot delivery to the private receiver and full power-off restoration still need target checks. A process started after an OEM off event cannot reconstruct that missed event from these in-memory flags; PowerManager/keyguard checks still apply. The QuickBoot broadcast handler does not enable QuickBoot or install a boot hook. NEMESIS's 3-second full-screen launch delay was not adopted because our service restores a shortcut rather than launching an app.

## Platform boundary

[NEMESIS](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/README.md) targets Android Gen5W **S5W_M with OTA capability**. Its documented development target is Kia EV6, **TCC803x / Android 4.4.2**, firmware family `260507`. It uses a helper executed before OTA metadata validation, rather than providing OEM signing keys or a universal firmware decryptor. Upstream also lists exceptions, including Elantra/Forte/Ioniq 6; OTA support alone is insufficient. [Upstream technical record](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/01-DSP-OTA-troubleShooter.md)

Our released apps target **STD_H / tcc893x / Android 4.2.2 / API17**. NEMESIS Manager declares **minSdk 19**, and its native build targets API19. Its ready APK/native binaries and OTA package are not drop-in components for this project. [Manager manifest](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/AndroidManifest.xml) · [Native build](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/Makefile)

## What is useful

| Upstream implementation | Potential use here | Adoption boundary |
| --- | --- | --- |
| [AutoLaunch](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/AutoLaunch.java), [EventReceiver](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/EventReceiver.java) | Distinguish Android boot from OEM screen-off/on recovery; consume a screen-off event once before handling wake | Implemented OEM display-event handling and independent off-state gates in the unreleased source; target delivery/order remains unverified. No automatic full-screen launch was added. |
| [Boot scripts](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/boot/install-recovery.sh), [QuickBoot hook](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/boot/qb_hook.sh) | Audit cold boot and QuickBoot independently | Our retained firmware text includes `flash_recovery`, but the inspected QuickBoot service reference is commented out and retained build properties say `ro.QB.enable=0`. Existence of a file is not evidence that it runs. Current vehicle boot hooks remain unverified. |
| [Update implementation](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/update.c) | Stage replacements, retain backup, distinguish update failure from restore failure | Applied flush/fsync-before-replace to our Python receipt writer, with POSIX directory sync and failure-path tests. System updater code was not copied. Existing APK backup/readback checks remain. |
| [Projection TBT checks](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/packages/projection-tbt/scripts/common.sh) | Require exact original APK **and ODEX** identities before applying a patch | Upstream payloads are pinned to `*.260507` and specific originals. They are not compatible replacements for our `250902` files. |
| [Package descriptors](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/04-Packages.md) | Explicit OS/model/dependency metadata and separated user/developer guides | Documentation organization adopted; no new package manager or installation path added. |
| [Service readiness / restart policy](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/svc.c) | Service readiness / restart policy | Our subscriber startup had a PGS/HSCAN-only retry gap. Adopted bounded retry delays, successful-peer retention and listener cleanup in VehicleDataClient; not a native process watchdog. |
| [Error persistence](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/CrashLog.java) | Error persistence | Adopted independently with Application-level initialization, two strictly bounded private files and bounded message-free formatting. No shared world-readable log path. |
| [Launcher listing](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/AppList.java) | Launcher listing | Already covered by HubActivity.launchable: package deduplication, label sorting and accessibility checks. No extra app-list layer added. |
| [Clock/network update gating](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/UpdateRule.java) | Clock/network update gating | Our telemetry and radio timeouts already use elapsedRealtime. The comparison initially retained existing clocks. Subsequent independently implemented [app update preparation](APP_UPDATES.md) adds user-initiated checks, elapsed-time deadlines and local-network policy; no upstream updater was copied. |
| [Bounded download / hash verification](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/Portal.java) | Bounded download / hash verification | Our prepare_menu_tools already limits downloads to 64 MiB, checks pinned SHA256 before writing, and uses a new local output directory. menu_entry verifies upload/readback hashes. No on-device store, CA bundle or HTTP update client added. |
| [Process execution](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/manager/src/com/nemesis/manager/Cli.java) | Process execution | Upstream reads all output then waits without a timeout. Our host ADB wrapper already has explicit timeouts and argument quoting. Not adopted as a replacement. |
| [Archive parsing](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/zip.c) | Archive parsing | Kept Python zipfile and our duplicate-member, aggregate-size, CRC, DEX and payload-hash checks in menu_entry. No custom native ZIP parser needed. |
| [Root broker](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/su.c) | Root broker | A new privileged daemon is outside the app task. Our target already has its separately verified OEM root path; no daemon/socket policy copied. |
| [USB-triggered package execution](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/Sources/native/usb.c) | USB-triggered package execution | Requires the NEMESIS runtime and package framework. Retained our explicit host installation and readback workflow; no removable-media auto-execution added. |

This comparison covers 14 implementation areas in the pinned revision. It is a source-level adoption review, not an exhaustive security audit or a runtime certification of NEMESIS.

The upstream screen recovery code also delays launch to account for CarPlay starting. Its timing is an observation from that platform, not a measured delay for our vehicle.

## What this does not solve

- Wireless projection scripts change feature/configuration flags; they do not supply missing Wi-Fi hardware. Our unsupported wireless CarPlay path remains out of scope.
- Cluster turn-by-turn replacements require the upstream exact firmware originals; matching APK names is insufficient.
- The reviewed source provides no solution to our unresolved Bluetooth Cover Art SDP negotiation, fixed-volume issue, ECU backup/recovery, or missing model-specific firmware keys.
- The OEM `persist.sys.swverify=nemesis` service name also appears in retained older firmware. Its name alone is not evidence that xwtk NEMESIS is installed or its OTA entry works.

## Source handling

Upstream is [AGPL-3.0](https://github.com/xwtk/hmg_nemesis/blob/b0178ed95a217879e779750d7a28d9f6b677d06f/LICENSE). A complete commit-pinned reference, original license and per-file SHA-256 manifest were retained in the private analysis workspace. No upstream code, OEM payload or installation media was added to this public MIT source tree. Links above preserve the reviewed revision. The source changes above have host/build validation only; target runtime verification remains pending.

## 한국어

**14개 구현 영역을 대조하고, 화면 복귀·연결 재시도·오류 기록·설치 기록 저장을 우리 코드에 반영했습니다.** 원본 소스와 라이선스는 비공개 분석 공간에 커밋을 고정해 보관했습니다.

NEMESIS의 루트 실행 방식은 OTA 검증 전에 실행되는 helper를 이용합니다. 우리 `STD_H / API17`에 바로 적용되는 `S5W_M / API19` 설치기가 아닙니다. 우리 차량은 기존 OEM `amossu`로 루트 실행을 이미 확인한 상태이므로 이 패키지가 루트 확보의 전제도 아닙니다.

화면 OFF→ON 처리와 QuickBoot **방송 수신**은 우리 내 앱 소스에 반영했습니다. OEM/Android의 꺼짐 상태를 따로 유지하며 언어 변경·서비스 재요청으로 버튼이 다시 나오지 않도록 합니다. 켜 둔 바로가기만 복원하고 앱 화면은 자동으로 열지 않습니다. 설치 기록도 임시 파일 동기화→교체→POSIX 디렉터리 동기화로 보강했습니다. 호스트 테스트·API17 대상 빌드는 통과했지만 새 소스의 실차 동작은 미검증이며 배포 APK는 그대로입니다. QuickBoot를 활성화하거나 시스템 시작 스크립트를 바꾸지는 않았습니다. 무선 CarPlay·앨범아트·ECU 복구가 해결된 것으로 표시하지 않습니다. 기존 Gen5W NMode 이식 중단 결정을 변경하는 조사도 아닙니다.

추가 비교에서 PGS/HSCAN만 준비가 늦는 경우의 재시도 누락을 수정했습니다. 등록 실패 정리·오래된 리스너 차단·BCM 실패 시 수신 보류도 검증했습니다. 앱 내부 오류 파일은 앱별 최대64 KiB로 제한하고 기존 Android 예외 처리를 유지합니다. 전체 Python50·Java13 검사와 두 앱 API17 대상 빌드가 통과했습니다. 선택형 퍼포먼스 계기판은 사용자 이미지에서 착안한 별도 작업이며 기존 수신값만 사용합니다. 모든 새 코드의 실차 설치·전원 종료 시험은 미실시입니다.

11/03에는1.16.5까지3커밋·48변경 파일을 추가 대조했습니다. 받은 업데이트를
나중에 오프라인으로 설치하는 기능과 실제 기본 경로 확인을 내 앱 v14에 반영했습니다.
Wi-Fi 버튼은 기존 모듈의 설정 화면을 여는 기능이며 USB 동글 드라이버 추가가 아닙니다.
