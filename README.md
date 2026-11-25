# Veloster N Dashboard

**English** | [한국어](README_ko.md)

A standalone dashboard for the Veloster N factory head unit, with **My Apps** for opening your dashboard and installed apps. Korean and English included. Installs alongside factory N Mode.

[Download](#download) · [Install](docs/INSTALL_EN.md) · [Compatibility & status](docs/STATUS.md) · [Documentation](docs/README.md)

<img src="docs/images/dashboard-overview-in-car.jpg" alt="N Dashboard overview on the Korean Veloster N factory head unit" width="480">

Owner-provided in-car photo. Missing readings display a dash. Location/camera metadata has been removed; image pixels are unchanged.

## Features

| N Dashboard | My Apps |
| --- | --- |
| 11 screens: overview, F1, gauges, graphs, performance, taxi meter, records, race, Λ, tacho, Neon Drive | Dashboard and installed-app launcher |
| Screen list (show/hide/order), 20 themes, hold-to-change tiles, drive-mode colours and screens | Opt-in edge button and notification shortcut |
| Floating window over other apps, settings backup, records room (best runs kept), [system font changer](docs/FONTS.md) | Engineering mode, USB debugging and Android utilities |
| Taxi meter with 17 regional fares picked from GPS; engine sound, shift beep, speed chime | Bluetooth pairing and restart confirmation |
| RPM, speed, temperatures, TPMS, TCS/ESC/ABS; media controls and artwork | Editable app list and Korean/English selection |

Floating window, settings backup, records room, themes, screen list, race/Λ/tacho screens, sounds, regional taxi fares and the owner installers are adapted from the i30 PD port [sung9155/i30-pd-dashboard](https://github.com/sung9155/i30-pd-dashboard) (MIT). Owner install guide: [INSTALL.md](INSTALL.md).

Vehicle telemetry uses existing receive paths without adding ECU diagnostic requests. Missing or expired readings stay unknown. Bluetooth album-art reception, exhaust temperature and e-LSD remain unresolved. Wireless CarPlay and automatic updates are not provided. [Full limitations](docs/LIMITATIONS.md)

## Compatibility

The guarded installer supports **Korean Veloster N JSN**, product `jsn_kr`, **Android 4.2.2 / API 17**, incremental build `eng.vmadmin.20250902.081552` (`250902`, `STD_H`). The dashboard requires the factory `automotive` library. Other models, regions, firmware and ordinary Android devices are not validated.

**2026-10-27:** N Dashboard v6 and My Apps v10 were installed and their APKs read back with matching hashes. The owner confirmed reopening My Apps from the HOME edge button. **Restoration after full power-off and extended operation remain unverified.** Later private experimental builds are separate from these releases. [Evidence and current status](docs/STATUS.md)

## Download

**[Download the complete v2026.10.26-i18n ZIP](https://github.com/reikop/veloster-n-dashboard/releases/download/v2026.10.26-i18n/veloster-n-dashboard-v2026.10.26-i18n.zip)** — both signed apps, installer and guides. This remains a prerelease.

| Current APK | Version |
| --- | --- |
| [N Dashboard](apks/N-Dashboard-v6-API17.apk) | v6 · 1.5-i18n |
| [My Apps](apks/My-Apps-v10-API17.apk) | v10 · 1.9-i18n |

Use the complete ZIP or repository for installation. Copying an APK onto an SD card does not install it. The dated ZIP retains its original documentation; this repository records later verification. [Older versions and release history](docs/RELEASE.md) · [Hashes and package identities](release.json)

## Quick start

Install and operate the screen while parked. Initial installation requires Python 3.9+ and a working [ADB connection](docs/ADB_EN.md).

1. Extract the complete download and open a terminal in its project directory.
2. Verify the files, then install using the device serial shown by `adb devices -l`:

   ```sh
   python3 scripts/verify_release.py
   python3 scripts/install.py --serial YOUR_DEVICE_SERIAL --parked
   ```

3. In My Apps, choose **Quick entry → Floating + notification → Save**.
4. Return to HOME and check **edge button → My Apps → N Dashboard** before disconnecting.
5. Separately verify reopening after a full shutdown/restart on your vehicle.

The installer makes fresh APK backups and verifies installed bytes. Use `--check` instead of `--parked` for inspection/backups without installing or launching. An accepted launch request does not confirm the screen appeared. The shortcut defaults to OFF; factory menus are not modified. [Installation and recovery](docs/INSTALL_EN.md) · [Quick-entry troubleshooting](docs/QUICK_ENTRY.md)

Once installed, normal use does not require a PC. If neither the edge button nor notification is accessible, see the [optional, exact-build OEM menu procedure](docs/MENU_ENTRY.md).

## Documentation

| Guide | English | 한국어 |
| --- | --- | --- |
| 01 · Compatibility and verification | [Status](docs/STATUS.md) | [확인 범위](docs/STATUS.md#한국어) |
| 02 · ADB and USB connection | [ADB](docs/ADB_EN.md) | [ADB 연결](docs/ADB.md) |
| 03 · Install, update and recover | [Install](docs/INSTALL_EN.md) | [설치·복귀](docs/INSTALL.md) |
| 04 · My Apps and shortcuts | [My Apps](docs/MY_APPS.md) · [Quick entry](docs/QUICK_ENTRY.md) | [내 앱](docs/MY_APPS_ko.md) · [바로가기](docs/QUICK_ENTRY_ko.md) |
| 05 · Language settings | [Languages](docs/LANGUAGES.md) | [언어](docs/LANGUAGES.md#한국어) |
| 06 · Development and research | [Documentation index](docs/README.md#development-and-research) | [개발·연구 색인](docs/README.md#development-and-research) |

<a id="source-and-tests"></a>

## Development

[`apps/`](apps/) contains the two apps; [`shared/`](shared/) contains dashboard/UI/data code. [`compile-stubs/`](compile-stubs/) supplies build-only OEM declarations. [`scripts/`](scripts/) and [`tests/`](tests/) provide build, integrity and host checks. [`installer/`](installer/README.md) holds the owner installers (Windows, macOS, Linux).

[Build and test](docs/BUILD.md) · [Research walkthrough](docs/RESEARCH.md)

Project-authored code and documentation: [MIT](LICENSE). [Dependency and signing notices](NOTICE.md). The public AOSP test certificate is not a private publisher identity or an OTA key. Source builds are unsigned. OEM firmware/APKs, private logs, identifiers and signing keys are not distributed. Unofficial project; no manufacturer affiliation.
