#!/usr/bin/env python3
"""Build the macOS window installer "N 계기판 설치.app" from installer/mac/gui.js (JXA, osacompile, stay-open applet).

The app carries its own kit in Contents/Resources/kit/: 설치.command, N-Dashboard-API17.apk, My-Apps-API17.apk and
bundle.txt (scripts/apk_info.py). It is ad-hoc signed (codesign -s -); Gatekeeper asks once ("그래도 열기").
macOS only (osacompile, plutil, codesign). Never connects to a vehicle.

usage: build_mac_app.py --apks DIR --out DIR
"""
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk_info  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
I = ROOT / "installer"
APP_NAME = "N 계기판 설치.app"


def build(apks, out):
    apks, out = Path(apks), Path(out)
    bundle = apk_info.bundle(apks)          # refuses missing / wrong APKs before anything is built
    version = re.search(r"^INSTALLER_VERSION='([^']+)'", (I / "설치.command").read_text(encoding="utf-8"), re.M).group(1)
    out.mkdir(parents=True, exist_ok=True)
    app = out / APP_NAME
    shutil.rmtree(app, ignore_errors=True)
    subprocess.run(["osacompile", "-l", "JavaScript", "-s", "-o", str(app), str(I / "mac" / "gui.js")], check=True)
    kit = app / "Contents" / "Resources" / "kit"
    kit.mkdir()
    shutil.copy2(I / "설치.command", kit / "설치.command")
    (kit / "설치.command").chmod(0o755)
    for name, _ in apk_info.BUNDLE:
        shutil.copy2(apks / name, kit / name)
    (kit / "bundle.txt").write_bytes(bundle.encode("utf-8"))
    plist = app / "Contents" / "Info.plist"
    for key, kind, value in (("CFBundleName", "string", "N 계기판 설치"), ("CFBundleDisplayName", "string", "N 계기판 설치"),
                             ("CFBundleIdentifier", "string", "com.veloster.installer.mac"),
                             ("CFBundleShortVersionString", "string", version), ("LSMinimumSystemVersion", "string", "11.0")):
        subprocess.run(["plutil", "-replace", key, "-" + kind, value, str(plist)], check=True)
    subprocess.run(["plutil", "-lint", str(plist)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["xattr", "-cr", str(app)], check=False)
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app)], check=True, stderr=subprocess.DEVNULL)
    subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
    return app


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apks", type=Path, required=True, help="folder with N-Dashboard-API17.apk and My-Apps-API17.apk")
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    print(build(a.apks, a.out))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
