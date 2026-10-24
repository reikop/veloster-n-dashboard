# My Apps — launcher and head-unit shortcuts

**English** | [한국어](MY_APPS_ko.md) | [Project](../README.md)

**My Apps (내 앱)** is a separate companion app for opening N Dashboard, other installed apps, and selected head-unit settings. It is not required for telemetry decoding, and it does not replace the factory home screen. The public release is **v8 / `1.7-reboot-button`**, package `com.veloster.carplayhub`. The historical package name does not mean that wireless CarPlay is implemented.

[Download My Apps v8](../apks/My-Apps-v8-API17.apk) · [Installation guide](INSTALL.md) · [Reopening after reboot](INSTALL.md#after-reboot)

## Current layout

![My Apps v8 PC preview, not an in-car capture](images/my-apps-v8-main-preview.png)

**This is a browser-rendered preview of the v8 layout, not an Android or vehicle screenshot.** Button labels come from the released `HubActivity` source; app names are examples. Native widgets, fonts, system bars, and available apps can differ. An earlier real vehicle capture is shown at the end of this guide.

## What each button does

| Label in the app | Function |
| --- | --- |
| **내 앱 — My Apps** | Returns to the shortcut and app-list page. |
| **N 계기판 — N Dashboard** | Opens the separately installed `com.veloster.dashboard` app. It does not depend on the old button inside the factory N Mode app. |
| **USB 디버깅 — USB debugging** | Displays the requested OEM USB mode and offers ON/OFF. ON requests debug mode; OFF requests normal USB mode and can disconnect ADB. This differs from the generic Android developer-setting toggle. |
| **엔지니어링 모드 — Engineering mode** | Opens the existing OEM engineering screen if it is installed and accessible. It does not add missing OEM menus. |
| **안드로이드 기본앱 — Android utilities** | Opens available settings, app management, storage, developer settings, and other accessible system apps. Availability depends on the head unit. |
| **블루투스 연결 설정 — Bluetooth settings** | Opens our Classic Bluetooth discovery/pairing panel using the installed Android stack. It is not an imported Android Settings app. |
| **강제 재시작 — Restart head unit** | Shows a confirmation dialog, then requests a head-unit restart if permission allows. It is not a factory reset. |
| **목록 편집 — Edit app list** | Selects which launchable installed apps appear in My Apps and saves the selection. Hiding an app does not uninstall it. |
| **연결 점검 — Connection checks** | Opens the legacy status/hotspot experiment page. This is not needed for normal dashboard use and does not provide wireless CarPlay. |
| **닫기 — Close** | Closes My Apps and returns to the previous screen. |

## Open it without a computer

On the research vehicle with its separately configured OEM entry, use **HOME → All menus → page 2 → Information (정보) → My Apps (내 앱)**, then tap **N 계기판**. A compatible launcher can also open either app directly.

The public APK does **not** create that OEM menu entry. If there is no accessible launcher or shortcut, use the [ADB fallback](INSTALL.md#after-reboot). Installing My Apps does not by itself solve how to open My Apps after reboot. Neither app auto-starts on boot; an installed app does not need a PC cable for normal operation.

## Customize the app list

1. Install the desired compatible app separately; My Apps is not an app store or downloader.
2. Open My Apps again. Launchable ordinary apps appear by default unless previously hidden.
3. Tap **목록 편집**, select or clear the app checkboxes, then tap **저장** (Save).
4. Tap the app name to launch it. The selection is stored locally and reused after relaunch.

![My Apps list editor, PC preview with example applications](images/my-apps-v8-edit-preview.png)

Only enabled, exported activities that this app is allowed to launch are listed. Packages without a launchable activity may not appear. Available system apps can be selected through the editor; this does not grant them new permissions. The fixed dashboard/engineering shortcuts remain separate from the editable app list. Example applications in these previews are not bundled with the release.

## USB, Bluetooth, and restart

For USB, choose ON and then follow the [ADB connection checks](ADB.md). A displayed ON setting does not prove that the PC enumerated the head unit. This shortcut also requires My Apps to be installed and reachable already, so it is not the initial ADB entry method.

For Bluetooth, open **블루투스 연결 설정**, choose **주변 기기 검색** (Search nearby devices), select a device, and confirm pairing. The panel does not scan or pair automatically when opened. GPS/audio use still requires the relevant app and supported protocol. BLE scanning and XGPS160 data reception are not implemented here; pairing alone does not enable them.

For a restart, tap **강제 재시작** and then explicitly confirm **재시작**, or select **취소** (Cancel). Display, music, and PC connectivity are interrupted by a real restart; perform this action while parked. A permission error is possible on unsupported head units.

![Head-unit restart confirmation, PC preview](images/my-apps-v8-reboot-preview.png)

The **연결 점검** page retains hotspot test, credentials display, and settings-restore controls. Viewing status does not start the hotspot. Starting or restoring it requires an explicit action. Wi-Fi activation failed on the research vehicle; do not interpret this page or the `carplayhub` package name as a working wireless CarPlay feature.

## Earlier real vehicle screenshot

![Earlier My Apps build running on the actual head unit](images/my-apps-earlier-in-car.png)

This is an actual head-unit capture from **2026-10-20**, showing an earlier build. Its **새 N모드** label predates the separate **N 계기판** shortcut, and Bluetooth/restart controls had not yet been added to this screen. It illustrates the native appearance, not the exact v8 feature set. This curated image contains no visible map, device addresses, or personal app list; the raw session logs are not published.

The v8 guide and previews were prepared on 2026-10-25 from the current public source. No vehicle connection, new installation, radio action, or reboot was performed to create them.
