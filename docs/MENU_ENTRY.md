# Optional factory-menu entry — exact supported build only

**English** | [한국어](MENU_ENTRY_ko.md) | [Installation](INSTALL_EN.md)

**Try [My Apps v9 Quick entry](QUICK_ENTRY.md) first.** Its floating button and notification use standard Android APIs and do not need OEM menu modifications. This advanced alternative recreates the factory **HOME → All menus → page 2 → Information → My Apps** route previously checked on the research vehicle. It is not a universal menu patch.

The menu route was checked on the car on 2026-10-20. The public reconstruction and guarded installation tools were checked offline on 2026-10-26; they have not been newly installed or cold-boot tested on a vehicle. Menu labels/page positions may vary. The first menu refresh may reset the OEM menu order because its saved item count changes.

## Requirements

- The [app installation](INSTALL_EN.md) must succeed first: current N Dashboard and My Apps installed, with My Apps opening through ADB.
- Product `jsn_kr`, API 17, incremental `eng.vmadmin.20250902.081552`; exact factory APK, ODEX and framework identities in [profile.json](../menu-entry/profile.json). A mismatched build stops the procedure. Never edit the hashes to force compatibility.
- Python 3.9+, JDK 17 and ADB on the computer. New output/backup directories at every step. Keep all captured originals, keys and generated OEM APKs private.
- The generated updates use the same public AOSP platform test certificate as these exact factory APKs. This does not establish signing compatibility for other vehicles or OTA updates.

Commands below use bash/zsh. On Windows use `py -3` and substitute the serial directly rather than using POSIX variables; the venv Python is `.menu-venv\Scripts\python.exe`.

## 1. Capture your originals — read-only

```sh
adb devices -l
VN_SERIAL='YOUR_DEVICE_SERIAL'
python3 scripts/menu_entry.py capture --serial "$VN_SERIAL" --out private-backups/menu-originals-01
```

This reads the installed/system menu APKs, paired ODEX files and the pinned framework files. It makes no vehicle changes. Unknown active menu updates are backed up but not replaced. If a previous local bundle is already installed, pass `--bundle` pointing to that same bundle to recognize it. The original research vehicle's reviewed historical menu updates are also explicitly recognized by hash.

## 2. Prepare local build tools

```sh
python3 -m venv .menu-venv
.menu-venv/bin/python -m pip install -r menu-entry/requirements.txt
python3 scripts/prepare_menu_tools.py --out build/menu-tools-01
```

The downloader fetches four hash-pinned external JAR files and the **public AOSP test certificate/key** into the specified local folder. It verifies every download, installs no global software, and makes no vehicle connection. Their upstream URLs and hashes are in [tool-sources.json](../menu-entry/tool-sources.json). Supply your own JDK 17 path below; Java is not installed by these scripts.

## 3. Build and verify locally — no ADB

```sh
.menu-venv/bin/python scripts/build_menu_entry.py \
  --capture private-backups/menu-originals-01 \
  --tools build/menu-tools-01 \
  --java /path/to/jdk17/bin/java \
  --key build/menu-tools-01/platform.pk8 \
  --cert build/menu-tools-01/platform.x509.pem \
  --out build/menu-bundle-01
```

All original identities and the signing certificate must match. The builder reconstructs original code, adds only the reviewed menu hooks, checks instruction preservation and DEX roundtrips, preserves resources, verifies API17-compatible signatures, and matches all generated payloads against the reviewed profile. It creates:

- `downloadmenu-candidate.apk`, `allmenu-candidate.apk`: menu entry additions.
- `downloadmenu-fallback.apk`, `allmenu-fallback.apk`: original code/resources rebuilt at the same versionCode 2 for recovery.
- `receipt.json`: checked artifact identities.

Fallback APKs preserve original code/resources but are **not byte-identical factory APKs**, and do not restore a previous user-specific menu order. The entire output contains OEM code and stays on your computer; do not upload it to this repository or forum.

## 4. Install with a fresh backup

Reconnect the same head unit if needed, park, and verify the screen is available. Optional `--check` performs preflight/backups only; use another new backup directory for the subsequent real installation.

```sh
python3 scripts/menu_entry.py install --serial "$VN_SERIAL" \
  --bundle build/menu-bundle-01 --backup private-backups/menu-install-01 --parked
```

The tool checks the target, original APK/ODEX identities, current apps, known menu updates, all four bundle payloads and free space. It backs up first, uses unique upload paths, verifies uploaded bytes, requires `pm install` Success, and reads each installed APK back. DownloadMenu is updated before AllMenu. It does not write `/system`, clear data, uninstall apps, force-stop menus or reboot.

**Installation is not atomic.** If the second update fails, the first may be installed. Preserve the bundle and backup receipt and follow recovery below; an error is not a completed installation.

## 5. Screen and restart acceptance

1. Leave the menu and return HOME, then reopen All menus.
2. Find **Information (정보)**, previously page 2 on the research car, and open **My Apps (내 앱)**.
3. Tap **N Dashboard (N 계기판)**, return HOME, and repeat using screen controls only.
4. During a normal parked shutdown/restart, verify the same route again. This is a required per-vehicle check, not a result already established by the new tooling.

If a menu has not refreshed, retain ADB access and the original route. Do not force-reboot merely to guess whether installation worked. The [Quick entry](QUICK_ENTRY.md) path is independent and can still be used if enabled.

## Recovery

Use the **same bundle** and a **new backup directory**:

```sh
python3 scripts/menu_entry.py restore --serial "$VN_SERIAL" \
  --bundle build/menu-bundle-01 --backup private-backups/menu-restore-01 --parked
```

Restore checks known installed versions and installs the original-code fallback at versionCode 2, AllMenu first and DownloadMenu second. My Apps/N Dashboard need not still be installed. It does not uninstall factory apps, remove their data, or downgrade to the old versionCode 1 APK. A disconnect during recovery is recorded as incomplete; reconnect and rerun with a new backup directory after inspecting the receipt. If an unknown update or target mismatch is found, stop and retain the backups for analysis.
