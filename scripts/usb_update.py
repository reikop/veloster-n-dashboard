#!/usr/bin/env python3
"""Make the USB update bundle (vn-usb-update.zip) from the two signed release APKs and the update key.

    python3 scripts/usb_update.py --dashboard N-Dashboard-API17.apk --hub My-Apps-API17.apk \
        --key update-signing-key.pem --cert platform.x509.pem --out vn-usb-update.zip

The zip holds one folder, vn-update/, to unzip onto the top of a USB stick: manifest.txt (the release, the head unit
it is for, the APK signer and each app's versionCode / size / SHA-256 / file name), manifest.sig (RSA SHA-256 over
manifest.txt with the update key, via openssl) and the APKs. My Apps checks all of it before installing
(apps/hub/.../usb/UsbBundle.java has the format). Version numbers come from the app manifests in this tree, which
scripts/check_versions.py keeps equal to the release; the APKs must be the ones built from it.

The update key's private half is not in the repository (the release workflow takes it from the USB_UPDATE_KEY
secret); its public half is UsbUpdateKey.java. Never connects to a vehicle.
"""
import argparse
import base64
import hashlib
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPS = (("com.veloster.dashboard", "apps/dashboard/AndroidManifest.xml", "dashboard.apk"),
        ("com.veloster.carplayhub", "apps/hub/AndroidManifest.xml", "hub.apk"))
PRODUCT, SDK = "jsn_kr", 17


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def manifest_version(rel):
    text = (ROOT / rel).read_text(encoding="utf-8")
    code = re.search(r'android:versionCode="(\d+)"', text)
    name = re.search(r'android:versionName="([0-9]+\.[0-9]+\.[0-9]+)"', text)
    if not code or not name:
        raise SystemExit("no version in " + rel)
    return int(code.group(1)), name.group(1)


def cert_sha256(pem_path):
    text = Path(pem_path).read_text(encoding="ascii")
    body = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S)
    if not body:
        raise SystemExit("not a PEM certificate: " + str(pem_path))
    return hashlib.sha256(base64.b64decode("".join(body.group(1).split()))).hexdigest()


def manifest_text(release, signer, apps, product=PRODUCT, sdk=SDK):
    lines = ["VNUSB1", "release=" + release, "product=" + product, "sdk=%d" % sdk, "signer=" + signer]
    for package, version, size, digest, name in apps:
        lines.append("app=%s,%d,%d,%s,%s" % (package, version, size, digest, name))
    return ("\n".join(lines) + "\n").encode("ascii")


def build(dashboard, hub, key, cert, out, product=PRODUCT, sdk=SDK):
    releases = set()
    apps = []
    for (package, rel, name), apk in zip(APPS, (dashboard, hub)):
        code, release = manifest_version(rel)
        releases.add(release)
        apps.append((package, code, Path(apk).stat().st_size, sha256(apk), name))
    if len(releases) != 1:
        raise SystemExit("the two apps have different releases: %s" % sorted(releases))
    release = releases.pop()
    text = manifest_text(release, cert_sha256(cert), apps, product, sdk)
    with tempfile.TemporaryDirectory(prefix="vn-usb-") as tmp:
        m, s = Path(tmp) / "manifest.txt", Path(tmp) / "manifest.sig"
        m.write_bytes(text)
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(s), str(m)], check=True)
        signature = s.read_bytes()
    if len(signature) != 256:
        raise SystemExit("the update key must be RSA 2048 (signature of %d bytes)" % len(signature))
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:   # APKs are already compressed
        z.writestr("vn-update/manifest.txt", text)
        z.writestr("vn-update/manifest.sig", signature)
        z.write(dashboard, "vn-update/dashboard.apk")
        z.write(hub, "vn-update/hub.apk")
    return release, text


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dashboard", type=Path, required=True)
    p.add_argument("--hub", type=Path, required=True)
    p.add_argument("--key", type=Path, required=True, help="the update key's private half (PEM)")
    p.add_argument("--cert", type=Path, required=True, help="the APK signing certificate (platform.x509.pem)")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--product", default=PRODUCT, help=argparse.SUPPRESS)   # emulator tests only: the head unit is jsn_kr / API 17
    p.add_argument("--sdk", type=int, default=SDK, help=argparse.SUPPRESS)
    a = p.parse_args()
    release, text = build(a.dashboard, a.hub, a.key, a.cert, a.out, a.product, a.sdk)
    sys.stdout.write("vn-usb-update %s -> %s\n%s" % (release, a.out, text.decode("ascii")))


if __name__ == "__main__":
    main()
