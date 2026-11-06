# Compatibility and verification / 호환성·확인 범위

[Documentation](README.md) · [한국어](#한국어)

Documentation reviewed **2026-11-06**. Vehicle evidence through **2026-10-28**. No new vehicle session was performed for this documentation update.

## Current wireless development snapshot (2026-11-06)

The latest source exports 229 Java / 254 pinned module files with conditional
ELD16k input/output, ELD48k output, owned cleanup and optional Consumer HID
Next/Previous input. The Hub pins the `a4bb6bf5…` composite OEM JAR; its
read-only availability check runs on a worker before Consumer HID is declared.
The current unsigned Hub build passed 11 build-input, 11 selection/settings,
14 host-program and 4 composition checks. A separate API17 Consumer gate
fixture passed 9 checks. The reordered full-factory API17 fixture passed, but
the first ordering's failure remains unexplained and neither fixture ran the
current Hub APK. OEM boot, physical keys, iPhone/radio operation and complete
installation/rollback remain unverified. See [the current wireless scope](WIRELESS_CARPLAY.md).
Historical version-specific results below apply only to their named snapshots.

## Supported target

| Item | Target |
| --- | --- |
| Vehicle | Korean Veloster N JSN |
| Software shown by the vehicle | `JSN.KOR.SOP.V141.250902.STD_H` |
| Installer identity | `ro.product.name=jsn_kr` |
| Android | 4.2.2 / API 17 |
| Incremental build | `eng.vmadmin.20250902.081552` |
| Platform | Telechips `tcc893x`, factory `automotive` library |

The installer checks the live product, SDK and incremental build. A generic firmware `build.prop`, matching Android version, or a Gen5W label does not establish compatibility. Other models/regions/builds are unverified; this project does not install NEMESIS.

## Released apps

| Artifact | Evidence | Still unverified |
| --- | --- | --- |
| N Dashboard v6 | 2026-10-27 guided install; readback SHA-256 matched the released APK | Full power-off restoration and extended reception |
| My Apps v10 | Same installation/readback check; owner separately confirmed HOME edge-button reopening | Shortcut restoration after full power-off |
| Vehicle signals | Earlier v5 initial telemetry; later research-car checks include RPM, temperature and TPMS reception | Active ABS/TCS/ESC transitions, exhaust temperature and e-LSD |

The private installer receipt records `apps_verified_launch_accepted`. Its screen/reboot flags remain false: the separate owner screen observation is not a machine-recorded acceptance. No emulator validation is claimed. Private device identifiers and logs are not published.

Readback hashes:

```text
N Dashboard v6  e2bf05c2fa955b7a60dc74705e48de67582956a9e3c4ee45473b88a9093830e2
My Apps v10     6351e5d0806fecf2cf5e5901a67c33a7a5ab337513dce1a62b30c9318d352680
```

These match [release.json](../release.json). The `v2026.10.26-i18n` archive remains unchanged and retains its original prerelease status and documentation. Later vehicle observations do not certify every feature.

## Unreleased source changes (2026-11-02)

[Code adapted after NEMESIS review](NEMESIS_REVIEW.md#implemented-in-our-code-unreleased): OEM screen-state handling and opt-in QuickBoot restoration in My Apps; synchronized installation receipt writes; complete missing-subscriber retries and bounded app-private error records. An optional [Performance page](PERFORMANCE.md) was also added from the owner’s visual reference. At the comparison stage, 50 Python tests, 13 Java host programs and unsigned API17-targeted builds of both apps passed. This is not a new vehicle-validated APK release. Published v6/v10 bytes and their verification scope above remain unchanged.

## App-update and USB-network preparation (2026-11-02)

The local preview is **My Apps v11 / N Dashboard v7**, with an explicit [HTTPS/USB updater](APP_UPDATES.md), connection diagnostics, authenticated bundle preparation and a Hub-only bootstrap tool. **64 Python tests and 13 Java host programs passed**; these include 41 core, 22 Android-adapter, 10 Activity, 31 native-process and 9 diagnostic checks. Local signed previews were prepared; existing released APKs and the public update feed were not changed.

The generic API17 ARM emulator ran the final Hub v11 with its normal app UID, verified signed bundles, fetched GitHub data with the packaged modern TLS backend and displayed the real update Activity. A separate v12 emulator fixture passed actual system-installer replacement and new-process receipt/hash confirmation, using documented test-only target substitution. This is not a vehicle installation, OEM-policy test or v12 release. [Detailed validation](validation/app-update-preconnection-20261102.json).

[USB-network investigation](USB_NETWORK.md) extracted conditional device/interface rules from downloaded260114, but actual250902 kernel configuration is still missing. No ordinary USB Wi-Fi dongle has been validated. USB host/power, actual descriptors/bind, IP/DNS/routes, OEM native execution/installation, self-update and full power-off behavior remain hardware checks. The updater does not add wireless CarPlay/Android Auto or manufacturer firmware OTA.

연결 전 소프트웨어·진단·서명된 USB 묶음 준비와 API17 에뮬레이터 시험을 마쳤습니다. 실제 동글·차량에는 연결하지 않았고, 온라인 피드와 새 APK를 공개 배포하지 않았습니다. 첫 설치는 내 앱만 선택해 기존 계기판 시험본을 보존할 수 있습니다.

## Experimental work

The research vehicle used a private Cover Art dashboard trial after the public v6 installation. Its later screenshots/logs must not be presented as an unchanged public v6 test. Bluetooth Cover Art remains blocked at SDP discovery; image transfer/display has not succeeded. Fixed-volume experiments failed and the prior Bluetooth trial was restored. SystemUI/fullscreen/bottom-bar candidates remain uninstalled. None is a released feature. [Experimental projects](../experimental/README.md)

## USB Wi-Fi local preview (2026-11-03)

My Apps v12 adds explicit USB Wi-Fi save/connect/disconnect controls and an external
lease-aware c-ares TLS transport. The source suite passes66 Python tests, including
30 lease/binding and19 credential/process checks in Java harnesses. A generic
API17 ARM emulator runs the actual screen and helper as the app UID; explicit
DNS/source-address HTTPS succeeds against a local fixture and a wrong hostname
is rejected. These are local tests. The separate driver/connection-manager/
bootstrap candidates are locally prepared, and neither required media pair
has been exercised on hardware. See [scope and device candidates](USB_NETWORK.md).

## Password terminal local preview (2026-11-03)

My Apps v13 adds a password-only SSH terminal switch and connection details. The
separate addon runs only when enabled; there is no 15-minute or idle-session limit.
Manual stop or loss/change of the Wi-Fi session closes access. Password changes
close existing sessions. The server key is generated on the target; no client key
setup is needed. A generic API17 emulator passed actual password login, PTY,
wrong-password rejection, active-session stop and network-state invalidation.
The real v13 screen was created as an ordinary app UID. The source suite passed
66 Python tests and13 Java programs, including37 Wi-Fi/terminal process checks;
addon10 and native21 sanitizer checks also passed.
[Validation and limits](validation/wifi-terminal-20261103.json).

The app-to-OEM-root path, physical USB Wi-Fi, network reachability and vehicle
installation remain untested. The emulator controller used eth0 with synthetic
lease/manager records; production is restricted to usbwifi0. Local signed v13 and
an addon were prepared, with no new public release, update feed or vehicle access.
[Use and initial setup](USB_NETWORK.md#password-ssh-terminal-local-v13-preview).

## Saved-update local preview (2026-11-03)

The NEMESIS1.16.5 follow-up adds authenticated saved/offline updates and a real-route
check to **My Apps v14**. The68-test Python suite and13 Java programs passed;
19 cache checks use real signed fixtures and the old route gate fails the new
regression test. A signed candidate and updated one-shot kit are local only.
No new vehicle or emulator runtime claim is made.
[Change review](NEMESIS_REVIEW.md) ·
[Validation](validation/nemesis-update-20261103.json).

## System DNS local preview (2026-11-04)

My Apps v15 adds explicit internet-settings controls for other apps. A separate
API17 native addon applies hotspot DNS with a persistent recovery receipt and
restores it on disconnect or stop while preserving newer system changes. The
68 Python tests and13 Java host programs pass; Wi-Fi process checks increased
to43. Native27, addon8 and one-shot14 checks pass. A generic ARM API17 run passed
nine DNS/lifecycle scenarios, including ordinary UID name resolution and HTTP,
and the signed v15 screen launched. Captured OEM libc exports satisfy all52
native imports; this is static compatibility evidence only.

This does not register a connected Android framework network. OEM Ethernet
configuration would compete for DHCP/routes, and a virtual-state broadcast is
insufficient. Physical Wi-Fi/USB, OEM root invocation, actual app compatibility
and old HTTPS remain unverified. [Use and limits](USB_NETWORK.md#internet-for-other-apps-local-v15-preview).

## HTTPS and market transport preview (2026-11-04)

My Apps v16 uses a shared modern HTTPS GET/download client and adds a connection
check screen plus an optional reversible system-CA supplement.69 Python/13 Java
programs pass; the shared client has38 host checks and9 actual generic API17
TLS scenarios. The CA addon has16 native/9 installer checks and7 generic runtime
trust-list/mount scenarios. None establishes OEM vehicle operation or completion
of an app market. [Details and limits](HTTPS_COMPATIBILITY.md).

## Earlier wireless CarPlay snapshot (2026-11-06)

The frozen local installation candidate was **v24 / `1.23`**. This earlier
214-Java/232-file snapshot added screen-independent Siri input,
an optional pinned OEM PTT source, pre-packet FLUSH support and an explicit
AAC-ELD 480-frame receive path. The latest unsigned APK builds successfully;
its entire binary was not executed and it is not a new published release.

The physical-source route passes host84, an independent 55-scenario/672-check
sequence audit and actual two-process API17 Binder30. Those fixtures do not run
the original OEM key-dispatch method or prove vehicle startup. Uncertain callback
completion keeps PTT quarantined until an exact completion ACK or Binder death.
Actual API17 product audio output decodes 60 AAC-ELD access units into 28,800
frames and passes the FLUSH cutoff. ELD remains unadvertised because target
negotiation/RTP and microphone uplink are not established.

Earlier 210-source full-factory and Hub49 tests remain valid for their recorded
snapshots. Wheel-speed/dead reckoning integration, native FM mixing, integrated
USB cancellation and the final complete package remain unfinished. Real radio,
iPhone, OEM authentication hardware and acoustic operation remain unverified.
Released APKs remain My Apps v10 / N Dashboard v6.
[Current workflow and exact validation scope](WIRELESS_CARPLAY.md).

`SOURCE_SHA256SUMS`, `SHA256SUMS` and `VALIDATION.json` describe earlier distribution
snapshots. Local development builds use separate manifests and test receipts.

## 한국어

**앞선 11/06 무선 CarPlay 소스 기록:** 아래는 당시214Java/232파일의 검증이며 현재 범위는 문서 상단과 연결된 안내를 따릅니다. 고정 v24 이후 생산214Java/공개232파일에 화면 독립 Siri·선택형 순정 PTT 연결·첫 RTP 이전 FLUSH 수정·AAC-ELD480 수신을 반영하고 미서명 빌드를 확인했습니다. 순정 route host84·독립55시나리오/672검사·실제 Binder30, API17 제품 AAC-ELD 출력60 AU/28,800프레임과 FLUSH 컷오프를 통과했습니다. ELD 협상/RTP·마이크 송신은 미완성이므로 광고하지 않습니다. 이전210Java 전체 factory·Hub49는 당시 소스의 결과이며 최신 APK 전체 실행으로 확대하지 않습니다. wheel/추측항법·순정 FM 혼합·USB 취소 통합·최종 묶음이 남았습니다. 차량 설치·실제 아이폰/무선·OEM 부팅/버튼·음향 성공은 미확인입니다. [검증 범위](WIRELESS_CARPLAY.md).

**11/02 코드 반영:** 내 앱의 OEM 화면 복귀·QuickBoot 방송 처리와 설치 기록 저장을 보강했습니다. 추가로 차량 구독 재시도 누락·실패 정리·앱 내부 오류 기록을 반영하고 선택형 퍼포먼스 화면을 추가했습니다. Python50·Java13 검사와 두 앱 API17 대상 미서명 빌드는 통과했으며 새 소스의 실차 시험은 미실시입니다. 배포 v6/v10 APK는 그대로입니다.

문서 점검일은 **2026-11-06**, 실차 근거는 **2026-10-28까지**입니다. 이번 문서 정리에서 차량을 새로 연결하거나 시험하지 않았습니다.

- 대상은 국내 벨로스터 N JSN의 `250902 / STD_H`, Android 4.2.2/API17, `tcc893x`입니다. 설치기는 실차의 `jsn_kr`, API17, `eng.vmadmin.20250902.081552`를 대조합니다. 공용 펌웨어의 `build.prop`만으로 차량을 식별하지 않습니다.
- **10/27 공개 v6·v10 설치 후 readback 해시 일치를 확인**했습니다. 사용자는 HOME 가장자리 버튼으로 내 앱 재실행을 별도로 확인했습니다. 설치 영수증의 화면·재부팅 확인 플래그는 false이므로 사용자 관찰과 자동 검사 결과를 합치지 않습니다.
- **완전 전원 종료 후 복원·장시간 수신·ABS/TCS/ESC 실제 개입 전환은 미검증**입니다. 배기온·e-LSD는 미확정입니다. 다른 차종·지역·빌드의 호환성을 추가하지 않습니다.
- 이후 연구 차량의 계기판은 비공개 Cover Art 시험본입니다. 해당 로그·화면을 공개 v6 그대로의 실차 검증으로 해석하지 않습니다.
- Bluetooth 앨범아트는 SDP 단계에서 미해결이며 고정 볼륨 시험도 실패했습니다. SystemUI·하단 바 후보는 미설치입니다. 공개 배포 기능과 구분합니다.
- 기존 ZIP·APK는 변경하지 않았습니다. 날짜가 붙은 배포 문서의 “미시험”은 배포 당시 상태이며 이후 확인은 이 문서를 기준으로 합니다. 개인 식별자·실차 원본 로그는 공개하지 않습니다.
