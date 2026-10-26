# Installation, launch, and recovery

**English** | [한국어](INSTALL.md)

**Installing the apps and enabling a way to reopen them are separate steps.** The APKs do not automatically appear in the factory home menu. The recommended path is installation → first ADB launch → enable the v9 [quick-entry shortcut](QUICK_ENTRY.md) → verify reopening from HOME. OEM menu modification is an optional fallback, not a requirement for every user.

This procedure is limited to **product `jsn_kr`, Android API `17`, incremental `eng.vmadmin.20250902.081552`**. The new installation tool checks that identity; it does not provide a bypass for other builds. Factory-menu modification additionally requires matching original APK and ODEX files. Similar vehicle names, Android versions, or hardware do not establish compatibility.

The installation tooling and documentation were revised on **2026-10-26**. This is not a new vehicle-installation or cold-boot test. N Dashboard v5 retains its historical verification scope. My Apps v9 is a signed, host-checked prerelease with no vehicle or emulator test; see [release provenance (Korean)](RELEASE.md).

## 1. Prepare the computer and vehicle

1. Park the vehicle where you can inspect its screen, and establish ADB using the [connection guide](ADB_EN.md). Initial debugging access cannot be enabled through My Apps before My Apps is installed and reachable.
2. Download and extract the **complete latest repository or the `v2026.10.26` release bundle**, not only the APK files. Open a terminal in the project directory containing `README.md`, `scripts/`, and `apks/`. The original `v2026.10.23` ZIP predates the new installation/menu tooling.
3. Install Python 3.9+ and Android SDK Platform-Tools so that `adb` is available. Run these commands on the computer. Examples use macOS/Linux bash or zsh syntax. On Windows, you can use `py -3` instead of `python3` and replace `"$VN_SERIAL"` directly with your device serial. Do not paste POSIX variable assignments into PowerShell unchanged.

```sh
python3 scripts/verify_release.py
adb devices -l
VN_SERIAL='YOUR_DEVICE_SERIAL'
adb -s "$VN_SERIAL" shell getprop ro.product.name
adb -s "$VN_SERIAL" shell getprop ro.build.version.sdk
adb -s "$VN_SERIAL" shell getprop ro.build.version.incremental
```

Replace `YOUR_DEVICE_SERIAL` with the actual serial shown by `adb devices -l`. The device state must be `device`; the properties must be `jsn_kr`, `17`, and `eng.vmadmin.20250902.081552`, respectively. Resolve missing, `offline`, or `unauthorized` devices before attempting installation. Use ordinary `adb shell`; `adb shell -T` failed with this head unit's old adbd.

## 2. Install or update both apps

The distributed APKs are already signed. Basic installation requires Python and ADB, not Java, SDK build tools, or compiling the source.

```sh
python3 scripts/install.py --serial "$VN_SERIAL" --parked
```

The tool creates a fresh directory under `private-backups/` for each run and prints its location; it does not overwrite a previous backup. To inspect the device without changing it first:

```sh
python3 scripts/install.py --serial "$VN_SERIAL" --check
```

`--check` creates backups/inspection records on the PC but does not install, launch, or change shortcut settings on the vehicle.

`--parked` records your confirmation that the vehicle is parked; the tool does not sense the vehicle's motion.

This step checks release integrity and the target device, preserves any existing app APKs in the new backup directory, and installs N Dashboard v5 and My Apps v9. Uploaded and installed APKs are read back for verification; a successful PC-side exit code alone is not treated as installation success with old adbd. If the tool stops, preserve the error output and backups. Do not bypass failures by deleting existing apps or forcing unknown files over them. The two installations are not atomic: if the second fails, the first may already be updated.

An APK backup is **not an app-data backup**. Both apps declare `allowBackup=false`; restoring gauge, graph, or app-list preferences through ordinary ADB is not guaranteed. Keep the backup directory private.

After successful installation and readback verification, the tool requests opening My Apps. It does not replace factory N Mode, modify OEM menus, automatically enable Quick entry, or reboot the head unit. **Android accepting a launch request is not proof that the screen appeared.** Complete the screen checks below.

## 3. Launch while connected

First check the My Apps screen opened by the installer. If you closed it or need to open it manually after a successful installation:

```sh
adb -s "$VN_SERIAL" shell pm path com.veloster.carplayhub
adb -s "$VN_SERIAL" shell pm path com.veloster.dashboard
adb -s "$VN_SERIAL" shell am start -n com.veloster.carplayhub/.HubActivity
```

Both `pm path` commands should return a `package:...apk` path. In My Apps, tap **N 계기판** (N Dashboard). To open the dashboard directly:

```sh
adb -s "$VN_SERIAL" shell am start -n com.veloster.dashboard/.DashboardActivity
```

See the [My Apps guide](MY_APPS.md) for button meanings. USB debugging OFF, Restart, and the experimental hotspot screen are not required to verify installation. USB OFF or Restart can interrupt your PC connection.

## 4. Enable v9 Quick entry and check it on screen

1. In My Apps, tap **실행 바로가기 / Quick entry** at the top.
2. Select **가장자리 버튼 + 알림 / Floating + notification**, then **저장 / Save**. The default is OFF; installation does not silently enable it.
3. Return to HOME and tap the small **앱** (Apps) button at the edge to reopen My Apps. Drag the button to reposition it.
4. Tap **N 계기판** (N Dashboard) and check page navigation and available telemetry. Missing readings marked `-` are distinct from installation failure.
5. **Only disconnect after HOME → small Apps button → My Apps → N Dashboard works on your screen.** Then separately check the same route and reception after a normal full reboot while parked.
6. If overlay display is denied, the service retains a notification and records the error. Check whether that notification actually opens My Apps on your screen. If the factory UI hides notifications too, use an alternative entry below.

This feature uses standard Android service, overlay, and notification facilities without modifying OEM menus. Device permissions and UI can still prevent access. **Saving the setting or starting a service is not a successful vehicle test: verify reopening with actual screen taps.** See [Quick entry](QUICK_ENTRY.md) for behavior, disabling it, and restoration limits.

### Alternative: an existing launcher or optional OEM menu

An existing compatible launcher or app list may also open either app. If neither the v9 button nor its notification is accessible, consider the [factory-menu procedure](MENU_ENTRY.md). This is optional and limited to builds whose original APK and ODEX identities match the supported originals.

The menu tool checks originals captured from your vehicle and builds the modification and recovery APKs locally. OEM APKs are not publicly distributed. It stops for unsupported originals; do not install another vehicle's modification. Reinstalling the ordinary app APKs does not add a factory-menu icon.

<a id="after-reboot"></a>

## After disconnecting or rebooting

Once running, the dashboard obtains vehicle data from services inside the head unit. A PC cable is not needed to keep it running. Disconnecting the cable or normally rebooting does not require reinstalling the apps. **Only the explicitly enabled v9 shortcut service requests restoration at boot; it does not open My Apps or the dashboard full-screen.** Tap its button/notification to open the app. Force-stop or device policy can prevent restoration until you open My Apps manually. OFF does not request restoration.

The previously checked OEM-menu route on the research vehicle is below. You do not need this menu modification when the v9 shortcut works:

1. Wait until startup completes and the home menu responds.
2. Open **HOME → All menus → page 2 → Information (정보) → My Apps (내 앱)**.
3. Tap **N Dashboard (N 계기판)**. The old button inside factory N Mode belongs to the earlier integrated approach, not the current independent app.

This menu route was checked by touching the vehicle screen after separate OEM-menu modifications on 2026-10-20. Menu position/order can differ with device state. Publishing the new tool does not establish v5 cold-boot/extended telemetry reliability or v9 vehicle shortcut restoration. On each newly configured vehicle, separately check the menu route and reception after a normal shutdown/reboot while parked.

If neither shortcut nor menu is available, reconnect the computer and use step 3. Check [quick-entry settings and errors](QUICK_ENTRY.md) first, then the optional [menu guide](MENU_ENTRY.md) if needed. A returned `pm path` means the app is installed even if its icon is absent. Do not immediately uninstall or reinstall it.

## Symptoms and errors

| Symptom | Next check |
| --- | --- |
| Installation succeeds but My Apps is not visible | Launch using step 3, then configure and test [v9 Quick entry](QUICK_ENTRY.md). |
| `pm path` returns no package path | Verify the target serial and installation-tool result. |
| My Apps opens but N Dashboard does not | Check `pm path com.veloster.dashboard` and the launch error. |
| App opens but extra readings show `-` | Check the exact supported build, ignition/service readiness, actual reception, and 60-second expiry. Do not send ECU requests to fill missing values. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Preserve the existing APK and stop. Do not automatically delete or re-sign it to bypass the mismatch. |
| `INSTALL_FAILED_MISSING_SHARED_LIBRARY` | The required OEM `automotive` library is missing. Copying another head unit's framework is not a supported remedy. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | A higher version may be installed; see recovery limitations below. |
| Insufficient storage | Inspect the tool result and this session's temporary files. Do not arbitrarily delete system apps or factory data. |
| No ADB device | Resolve USB enumeration, mode, and cable issues before investigating the apps. See the [connection guide](ADB_EN.md). |

## Advanced recovery and removal

To stop only the standalone dashboard:

```sh
adb -s "$VN_SERIAL" shell am force-stop com.veloster.dashboard
```

`scripts/install.py` and the advanced `menu_entry.py install-apps` command install the current v5/v9 releases; it is not an arbitrary-backup or v3 rollback command. For an advanced run with an explicit backup location, use `python3 scripts/menu_entry.py install-apps --serial "$VN_SERIAL" --backup private-backups/apps-01 --parked`, where the directory does not yet exist. That lower-level command is separate from the basic helper's automatic launch request. Restore menu modifications using the separate [menu recovery procedure](MENU_ENTRY.md). Uninstalling the dashboard does not restore modified OEM menus.

A personal backup APK with the same signer and the same or a higher version may be restored manually using `push`, uploaded-file readback/hash comparison, `pm install -r`, and installed-file readback/hash comparison. Use a new temporary filename and a fresh backup directory every time, verify each step, and never overwrite the only original backup.

v3 has a lower version than v5, so ordinary `pm install -r` may reject it. Support for `-d` and retaining v5 preferences while running v3 have not been vehicle-validated for this public package. No automatic downgrade is provided.

**Only if you deliberately accept losing the app's saved settings**, preserve a backup and use `adb -s "$VN_SERIAL" shell pm uninstall com.veloster.dashboard` before separately installing the signed v3 APK at `apks/rollback/N-Dashboard-v3-API17.apk`. Removing the dashboard deletes its saved gauge/graph preferences. Check its entry path and operation again after installing v3.

The independent packages are `com.veloster.dashboard` and `com.veloster.carplayhub`. If you used the menu tooling, review menu recovery before removing the project. Restore any needed USB/radio state before removing My Apps. Uninstalling an app does not automatically restore changed device settings or an older modified factory N Mode APK.
