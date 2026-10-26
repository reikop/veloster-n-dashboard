#!/usr/bin/env python3
"""Fetch hash-pinned external tools for optional LOCAL OEM menu reconstruction.

Downloads only to a new private build directory; never installs system software,
changes Java/Python configuration, or connects to a vehicle. Includes the public
AOSP test key needed for the exact supported OEM test-certificate build.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    for item in json.loads((ROOT / "menu-entry/tool-sources.json").read_text()):
        print("Fetching " + item["file"], flush=True)
        request = urllib.request.Request(item["url"], headers={"User-Agent": "Veloster-N-menu-builder/1"})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024:
            raise ValueError("Unexpected download size: " + item["file"])
        if item.get("encoding") == "base64":
            data = base64.b64decode(data, validate=True)
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError("Download hash mismatch: " + item["file"])
        output = args.out / item["file"]
        output.write_bytes(data)
        output.chmod(0o600)
    print("Verified tools saved locally. Do not publish this directory or generated OEM APKs.")


if __name__ == "__main__":
    main()
