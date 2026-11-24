#!/usr/bin/env python3
"""Pack the owner installers (installer/) into the Windows, macOS and Linux ZIPs a release attaches.

Scripts keep their line endings, the shell scripts get mode 755 inside the ZIP (Finder double-click and `bash
install.sh` need it; Windows Compress-Archive cannot set it). APKs are optional: --apks DIR adds
N-Dashboard-API17.apk and My-Apps-API17.apk from DIR for an offline bundle. Adapted from the i30 PD port's release
tooling (sung9155/i30-pd-dashboard, MIT). Never connects to a vehicle.
"""
import argparse
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I = ROOT / "installer"
APKS = ("N-Dashboard-API17.apk", "My-Apps-API17.apk")
SETS = {
    "windows": [("설치.cmd", "설치.cmd", 0o644), ("삭제.cmd", "삭제.cmd", 0o644), ("진단기록.cmd", "진단기록.cmd", 0o644), ("install.ps1", "install.ps1", 0o644)],
    "mac": [("설치.command", "설치.command", 0o755), ("삭제.command", "삭제.command", 0o755), ("진단기록.command", "진단기록.command", 0o755)],
    "linux": [("설치.command", "install.sh", 0o755), ("linux/uninstall.sh", "uninstall.sh", 0o755), ("linux/diag.sh", "diag.sh", 0o755)],
}


def add(z, data, name, mode):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.external_attr = (0o100000 | mode) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, data)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--apks", type=Path, help="folder with N-Dashboard-API17.apk and My-Apps-API17.apk (offline bundle)")
    a = p.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    for os_name, files in SETS.items():
        target = a.out / ("vn-installer-" + os_name + ".zip")
        with zipfile.ZipFile(target, "w") as z:
            for src, name, mode in files:
                add(z, (I / src).read_bytes(), name, mode)
            add(z, (I / "README.md").read_bytes(), "README.md", 0o644)
            if a.apks:
                for apk in APKS:
                    data = (a.apks / apk).read_bytes()
                    if data[:2] != b"PK":
                        raise SystemExit("Not an APK: " + str(a.apks / apk))
                    add(z, data, apk, 0o644)
        print(target)


if __name__ == "__main__":
    main()
