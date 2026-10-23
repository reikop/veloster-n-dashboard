#!/usr/bin/env python3
"""Check release file integrity; this is not certificate authentication."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(ok, message):
    if not ok:
        raise ValueError(message)


def main():
    manifest = json.loads((ROOT / "release.json").read_text(encoding="utf-8"))
    for entry in manifest["apks"]:
        name = entry["file"]
        path = (ROOT / name).resolve()
        require(ROOT in path.parents, "Path outside release: " + name)
        data = path.read_bytes()
        require(len(data) == entry["bytes"], "Size mismatch: " + name)
        require(hashlib.sha256(data).hexdigest() == entry["sha256"], "SHA256 mismatch: " + name)
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate ZIP entry: " + name)
            require(archive.testzip() is None, "ZIP CRC failure: " + name)
            require(archive.read("classes.dex")[:8] == b"dex\n035\0", "Wrong DEX version: " + name)
            require("AndroidManifest.xml" in names, "Missing manifest: " + name)
            require("META-INF/CERT.RSA" in names, "Missing signature block: " + name)
        print("PASS " + name)
    print("Integrity matches release.json. This command does not cryptographically verify the signer or prove vehicle compatibility.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
