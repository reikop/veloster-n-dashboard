#!/usr/bin/env python3
"""One release version for everything: both app manifests (versionName), the phone installer (Flow.VERSION) and the
PC installers (install.ps1 $InstallerVersion, 설치.command INSTALLER_VERSION) must name the same MAJOR.MINOR.PATCH.
With --tag vX.Y.Z (the release workflow passes the pushed tag) the tag must name it too. Prints the version.
Local files only."""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "apps/dashboard/AndroidManifest.xml": r'android:versionName="([^"]+)"',
    "apps/hub/AndroidManifest.xml": r'android:versionName="([^"]+)"',
    "installer/android/src/com/veloster/installer/Flow.java": r'static final String VERSION = "([^"]+)"',
    "installer/install.ps1": r"\$InstallerVersion = '([^']+)'",
    "installer/설치.command": r"^INSTALLER_VERSION='([^']+)'",
}


def versions():
    found = {}
    for rel, pattern in SOURCES.items():
        m = re.search(pattern, (ROOT / rel).read_text(encoding="utf-8-sig"), re.M)
        found[rel] = m.group(1) if m else None
    return found


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--tag")
    args = p.parse_args()
    found = versions()
    names = set(found.values())
    bad = [f"{rel}: {v}" for rel, v in found.items() if v is None or not re.fullmatch(r"\d+\.\d+\.\d+", v)]
    if bad or len(names) != 1:
        sys.exit("versions differ or are not MAJOR.MINOR.PATCH:\n  " + "\n  ".join(f"{r}: {v}" for r, v in found.items()))
    version = names.pop()
    if args.tag is not None and args.tag != "v" + version:
        sys.exit(f"tag {args.tag} does not match the source version {version} (expected v{version})")
    print(version)


if __name__ == "__main__":
    main()
