#!/usr/bin/env python3
"""Pack the owner installers (installer/) into the release assets. Every installer carries both app APKs.

Release assets (written to --out): vn-installer-windows.zip, vn-installer-mac.zip (with "N 계기판 설치.app"),
vn-installer-linux.zip, optionally vn-installer-android.apk (--android-apk, built by installer/android/build.sh), and
SHA256SUMS.txt over all of them. The raw app APKs are not release assets.

--apks DIR is required: N-Dashboard-API17.apk and My-Apps-API17.apk are checked (an APK, the expected package) and go
into every ZIP with bundle.txt (scripts/apk_info.py: file, package, versionCode, versionName, sha256); the installers
install only those bytes. The mac .app needs macOS (osacompile, codesign).

Scripts keep their line endings, the shell scripts get mode 755 inside the ZIP (Finder double-click and `bash
install.sh` need it; Windows Compress-Archive cannot set it). Adapted from the i30 PD port's release tooling
(sung9155/i30-pd-dashboard, MIT). Never connects to a vehicle.
"""
import argparse
import hashlib
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk_info  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
I = ROOT / "installer"
SETS = {
    "windows": [("N계기판설치.cmd", "N계기판설치.cmd", 0o644), ("gui.ps1", "gui.ps1", 0o644),
                ("설치.cmd", "설치.cmd", 0o644), ("삭제.cmd", "삭제.cmd", 0o644), ("진단기록.cmd", "진단기록.cmd", 0o644),
                ("install.ps1", "install.ps1", 0o644), ("icon/installer.ico", "installer.ico", 0o644)],
    "mac": [("설치.command", "설치.command", 0o755), ("삭제.command", "삭제.command", 0o755), ("진단기록.command", "진단기록.command", 0o755)],
    "linux": [("설치.command", "install.sh", 0o755), ("linux/uninstall.sh", "uninstall.sh", 0o755), ("linux/diag.sh", "diag.sh", 0o755)],
}


def add(z, data, name, mode):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.external_attr = (0o100000 | mode) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, data)


def add_tree(z, top, prefix):
    """A directory (the .app) with its files' modes; symlinks stay symlinks."""
    for dirpath, dirnames, filenames in os.walk(top):
        dirnames.sort()
        for name in sorted(filenames) + [d for d in dirnames if os.path.islink(os.path.join(dirpath, d))]:
            path = Path(dirpath) / name
            arc = prefix + "/" + path.relative_to(top).as_posix()
            if path.is_symlink():
                info = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
                info.external_attr = (0o120755) << 16
                z.writestr(info, os.readlink(path))
            else:
                add(z, path.read_bytes(), arc, path.stat().st_mode & 0o777)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def package(apks, out, android_apk=None):
    apks, out = Path(apks), Path(out)
    bundle = apk_info.bundle(apks).encode("utf-8")   # refuses before anything is written
    if android_apk is not None:
        info = apk_info.manifest(android_apk)
        if info.get("package") != "com.veloster.installer":
            raise ValueError("not the phone installer: %s is %s" % (android_apk, info.get("package")))
    out.mkdir(parents=True, exist_ok=True)
    assets = []
    with tempfile.TemporaryDirectory(prefix="vn-mac-app-") as tmp:
        import build_mac_app
        app = build_mac_app.build(apks, tmp)
        for os_name, files in SETS.items():
            target = out / ("vn-installer-" + os_name + ".zip")
            with zipfile.ZipFile(target, "w") as z:
                for src, name, mode in files:
                    add(z, (I / src).read_bytes(), name, mode)
                add(z, (I / "README.md").read_bytes(), "README.md", 0o644)
                for name, _ in apk_info.BUNDLE:
                    add(z, (apks / name).read_bytes(), name, 0o644)
                add(z, bundle, "bundle.txt", 0o644)
                if os_name == "mac":
                    add_tree(z, app, app.name)
            assets.append(target)
    if android_apk is not None:
        target = out / "vn-installer-android.apk"
        if Path(android_apk).resolve() != target.resolve():
            shutil.copyfile(android_apk, target)
        assets.append(target)
    sums = out / "SHA256SUMS.txt"
    sums.write_bytes("".join("%s  %s\n" % (sha256(a), a.name) for a in assets).encode("utf-8"))
    return assets + [sums]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--apks", type=Path, required=True, help="folder with N-Dashboard-API17.apk and My-Apps-API17.apk (always bundled)")
    p.add_argument("--android-apk", type=Path, help="the signed vn-installer-android.apk to list as a release asset")
    a = p.parse_args()
    for path in package(a.apks, a.out, a.android_apk):
        print(path)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
