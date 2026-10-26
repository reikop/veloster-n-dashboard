#!/usr/bin/env python3
"""Install the supported public apps, verify them, and open My Apps for entry setup.

Use --check for read-only checks and backups. Actual installation requires --parked.
The command does not enable the shortcut for you or claim that its screen was seen.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess
import sys
import uuid
import zipfile

import menu_entry

ROOT = Path(__file__).resolve().parents[1]
HUB_COMPONENT = "com.veloster.carplayhub/.HubActivity"


def new_backup(root=ROOT):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return root / "private-backups" / ("install-" + stamp + "-" + uuid.uuid4().hex[:12])


def launch_accepted(output):
    """Recognize the API17 am acknowledgement; this is not screen verification."""
    lines = [line.strip() for line in output.replace("\r", "").splitlines() if line.strip()]
    failure = re.compile(r"^(?:Error\b|Exception\b|Failure\b|SecurityException\b|java\.|android\.util\.AndroidException)", re.I)
    menu_entry.require(not any(failure.search(line) for line in lines),
                       "Android rejected opening My Apps: " + output.strip())
    component = re.compile(r"\bcmp=com\.veloster\.carplayhub/(?:\.HubActivity|com\.veloster\.carplayhub\.HubActivity)(?=[\s}])")
    starts = [line for line in lines if line.startswith("Starting: Intent {") and component.search(line)]
    menu_entry.require(len(starts) == 1, "Could not confirm My Apps launch request: " + output.strip())


def install(args, root=ROOT):
    menu_entry.require(args.parked or args.check, "Installation requires --parked; use --check for read-only checks")
    # The shared runner gates the exact tested target and validates public APKs
    # before touching the vehicle. No blind adb install/retry path is added here.
    profile = menu_entry.load_profile()
    apps = menu_entry.load_apps(root)
    adb = menu_entry.Adb(args.serial, args.adb)
    backup = new_backup(root)
    print("Private backup and operation receipt: " + str(backup))
    journal = menu_entry.change(adb, profile, "install-apps", backup, apps=apps, check=args.check)
    if args.check:
        menu_entry.require(journal.data["status"] == "checked_without_changes", "Read-only check did not finish")
        print("Checks passed. No installation, launch or shortcut setting was changed.")
        return journal
    menu_entry.require(journal.data["status"] == "verified", "Installation was not verified; My Apps was not launched")
    journal.step(package="com.veloster.carplayhub", stage="launch_requested", component=HUB_COMPONENT)
    try:
        output = adb.shell("am", "start", "-n", HUB_COMPONENT)
        launch_accepted(output)
    except (Exception, KeyboardInterrupt) as error:
        journal.data.update(status="apps_verified_launch_unconfirmed", launch_error=str(error))
        journal.step(package="com.veloster.carplayhub", stage="launch_unconfirmed", error=str(error))
        print("Apps remain installed and verified. Opening My Apps was not confirmed; no uninstall or rollback was attempted.", file=sys.stderr)
        raise
    journal.data.update(status="apps_verified_launch_accepted", entry_verified_on_screen=False,
                        reboot_entry_verified=False)
    journal.step(package="com.veloster.carplayhub", stage="launch_accepted", component=HUB_COMPONENT)
    print("Apps installed and read back successfully; Android accepted the My Apps launch request.")
    print("This is NOT yet confirmation that a usable shortcut is visible.")
    print("While parked, on the head-unit screen:")
    print("  1. Open 실행 바로가기 / Quick entry in My Apps.")
    print("  2. Select 가장자리 버튼 + 알림 / Floating + notification, then 저장 / Save.")
    print("  3. Go HOME; tap the small 앱 / Apps button and open N 계기판 / N Dashboard.")
    print("  4. Only after this works, disconnect the PC. Check the same route after a normal full reboot while parked.")
    print("If the button is missing, keep ADB connected and follow docs/QUICK_ENTRY.md; do not assume reinstalling adds a home icon.")
    print("No shortcut preference was enabled automatically. Receipt: " + str(journal.path))
    return journal


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact serial from adb devices -l")
    parser.add_argument("--adb", default="adb", help="ADB executable path")
    parser.add_argument("--parked", action="store_true", help="Confirm the vehicle is parked and its screen can be checked")
    parser.add_argument("--check", action="store_true", help="Read-only target/APK checks and backups; no install or launch")
    args = parser.parse_args(argv)
    try:
        install(args)
        return 0
    except (menu_entry.Stopped, OSError, ValueError, KeyError, zipfile.BadZipFile,
            subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        print("STOPPED: " + str(error), file=sys.stderr)
        print("Keep the private backup/receipt. Review the reported error before retrying; existing apps were not automatically removed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
