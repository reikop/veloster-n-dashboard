# ADB access and USB connection

**English** | [한국어](ADB.md)

## What has actually been established

| Item | Evidence and limit |
| --- | --- |
| ADB connection and app installation | Successful on the Korean JSN head unit, API 17, build `eng.vmadmin.20250902.081552`. |
| Hidden menu, annual passwords, Factory Test commands | Code-path analysis of downloaded build 260114; not proof that every installed firmware has the same screen or password. |
| USB debug mode change | OEM `MiscManager.setSystem(5/6)` path identified. An ON setting alone does not prove USB enumeration. |
| Cable | Connection succeeded after a cable change; no universally validated product model or electrical wiring specification is provided. |

Work while parked with the screen accessible. Installation does not require Variant Coding, CPU Variant Coding, firmware updates, or a factory reset.

## 1. Candidate initial entry: Engineering Mode

This sequence was identified in downloaded build 260114. If your screen differs, do not guess coordinates or substitute unrelated menus.

1. Open **Settings → SW information/update**.
2. On the lower update-button row, tap the **left hidden area five times, then the right hidden area once**, within ten seconds of the first tap. These are taps, not long presses.
3. The code describes 60×60dp touch views at the bottom of the end containers. Exact vehicle-screen pixel coordinates are not established; they are not necessarily the full screen's corners. After a failed attempt, wait more than ten seconds before retrying. Do not increase the count: ten/fifteen left taps have separate actions.
4. The password selection uses the **device's calendar year**, not the update filename date. Analyzed candidates are **2026: `0567`; 2027: `1278`**. A different date, firmware, or uninitialized clock can change the result. Separate retained evidence does not establish that these particular passwords were successfully entered on the vehicle.

The analyzed path opens `InputPasswordExt` from `SystemInfo`; `getKeyString` reads `Calendar.YEAR`. OEM source is not distributed.

## 2. Request USB debug in Factory Test

If the menu exists, open **Diagnosis → Factory Test**. This is not `DTC for developer`. The analyzed `sendCmd` path expects:

| Order | Enter exactly | Then |
| --- | --- | --- |
| 1 | `0A 01` | Press SEND to enter factory mode. |
| 2 | `65 01` | Replace the previous text and press SEND to request USB debug. |
| 3 | `0A 02` | Replace the text and press SEND to exit factory mode. |

Each entry is **two hexadecimal bytes separated by one space**. Do not enter `6501`, send multiple rows together, or press SEND after each byte. The analyzed `65` handler requires factory mode; its exit path does not revert USB state. This code path and successful PC USB enumeration are separate facts.

To request normal USB mode through the same path, use `0A 01` SEND → `65 00` SEND → `0A 02` SEND. This guide does not suggest probing other command numbers.

If My Apps is already installed **and you can open it**, **USB 디버깅 → ON** offers the existing OEM request. A displayed ON state still does not prove physical connectivity. This button cannot supply initial access before the app is installed.

## 3. Check USB enumeration separately from ADB

Prepare official [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools). Run commands on the PC; see the [official ADB guide](https://developer.android.com/tools/adb) for device selection.

```sh
adb devices -l
# macOS only: inspect physically enumerated USB devices
ioreg -p IOUSB -l -w 0
```

The successful case reported **Telechips / DiPO**, VID `0x18d1`, PID `0xd00d`. Use your actual serial from the ADB listing; do not leave `YOUR_DEVICE_SERIAL` unchanged.

| Observation | Next check |
| --- | --- |
| Device absent from USB enumeration | Check cable, port, data capability, USB roles, and actual mode switching. APK reinstallation or an ADB authorization prompt cannot fix this stage. |
| Present in USB but absent from ADB | Check the PC ADB server/driver and interface. You may restart the PC server with `adb kill-server`, then `adb start-server`, and recheck. |
| `offline` | Reconnect the cable and restart the ADB server, then recheck. |
| `unauthorized` | Accept an authorization dialog if the head unit actually shows one. Do not assume an old OEM build always displays it. |
| `device` | Read the target properties below before installation. |

These examples use bash/zsh syntax. On Windows PowerShell, substitute the serial directly for `"$VN_SERIAL"` instead of using this POSIX assignment.

```sh
VN_SERIAL='YOUR_DEVICE_SERIAL'
adb -s "$VN_SERIAL" shell getprop ro.product.name
adb -s "$VN_SERIAL" shell getprop ro.build.version.sdk
adb -s "$VN_SERIAL" shell getprop ro.build.version.incremental
```

The supported target returns `jsn_kr`, `17`, and `eng.vmadmin.20250902.081552`, respectively. Use ordinary `adb shell`: `adb shell -T` failed with this old adbd. Continue with [installation and first launch](INSTALL_EN.md), then configure [Quick entry](QUICK_ENTRY.md) so you can reopen My Apps without ADB.

## Cables and repeated connection failures

The successful vehicle USB-A female-port setup used an A-A cable/adapter arrangement. An A-C cable plus a C-to-A female HOST adapter can have different USB-role behavior. **The A-A connector shape alone does not establish suitability or safe wiring.** This guide does not prescribe arbitrary host-to-host power connections or cutting wires.

Keep a known working cable/adapter/port combination and check USB enumeration. A Mac charging, or no longer charging, is not evidence of ADB. If debug is ON but no device appears, one check is debug OFF → disconnect → ON → reconnect; it is not a universal fix.

## Hidden ADB items

The `com.hkmc.software.engineermode.adb_hide` feature hides the Ethernet ADB tab. A WIFI ADB string exists, but the analyzed main-menu handler did not expose its screen. Related broadcasts require an already running sender inside Android; they are not packets you can send directly over Wi-Fi to gain initial access. Wi-Fi driver/module availability is a separate question.

The downloaded firmware and vehicle build differ. No single password, menu path, cable, or wireless ADB claim in this guide should be treated as a universal entry method for every vehicle.
