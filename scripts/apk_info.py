#!/usr/bin/env python3
"""Read package, versionCode and versionName from an APK's binary AndroidManifest.xml, and write the installers'
bundle manifest (bundle.txt). No Android SDK needed.

bundle.txt: one line per bundled app APK, tab separated: file, package, versionCode, versionName, sha256.
The owner installers (install.ps1, 설치.command, the phone app) install only APKs whose SHA-256 matches this file.

usage: apk_info.py APK...                 print the fields
       apk_info.py --bundle DIR OUT       check DIR's two APKs and write OUT
Never connects to a vehicle.
"""
import hashlib
import struct
import sys
import zipfile
from pathlib import Path

# The two release APKs every installer carries, and the package each must be.
BUNDLE = (("N-Dashboard-API17.apk", "com.veloster.dashboard"), ("My-Apps-API17.apk", "com.veloster.carplayhub"))
ANDROID_NS_ATTRS = {0x0101021B: "versionCode", 0x0101021C: "versionName"}


def _strings(data, at):
    _, header, size, count, _, flags, start, _ = struct.unpack_from("<HHIIIIII", data, at)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % count, data, at + header)
    out = []
    for off in offsets:
        p = at + start + off
        if utf8:
            n = data[p]; p += 2 if n & 0x80 else 1   # utf-16 length, skipped
            n = data[p]
            if n & 0x80: n = ((n & 0x7F) << 8) | data[p + 1]; p += 2
            else: p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]; p += 2
            if n & 0x8000: n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]; p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out, size


def manifest(apk):
    """{'package', 'versionCode', 'versionName'} from the <manifest> element."""
    with zipfile.ZipFile(apk) as z:
        data = z.read("AndroidManifest.xml")
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise ValueError("not a binary AndroidManifest.xml: " + str(apk))
    at, strings, resmap = 8, [], []
    while at < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, at)
        if kind == 0x0001:
            strings, _ = _strings(data, at)
        elif kind == 0x0180:
            resmap = list(struct.unpack_from("<%dI" % ((size - header) // 4), data, at + header))
        elif kind == 0x0102:
            name = struct.unpack_from("<I", data, at + 20)[0]
            if strings[name] == "manifest":
                start, asize, count = struct.unpack_from("<HHH", data, at + 24)
                out = {}
                for i in range(count):
                    a = at + 16 + start + i * asize
                    _, aname, raw, _, _, dtype, value = struct.unpack_from("<IIIHBBI", data, a)
                    key = ANDROID_NS_ATTRS.get(resmap[aname]) if aname < len(resmap) else None
                    key = key or strings[aname]
                    if key not in ("package", "versionCode", "versionName"):
                        continue
                    if dtype == 0x03 or raw != 0xFFFFFFFF:
                        out[key] = strings[raw if raw != 0xFFFFFFFF else value]
                    else:
                        out[key] = str(value)
                return out
        at += size
    raise ValueError("no <manifest> element: " + str(apk))


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bundle(directory):
    """Checks DIR's two release APKs (present, an APK, the expected package) and returns bundle.txt's text."""
    directory = Path(directory)
    lines = ["# file\tpackage\tversionCode\tversionName\tsha256 (scripts/apk_info.py; the installers refuse other bytes)"]
    for name, package in BUNDLE:
        path = directory / name
        if not path.is_file():
            raise ValueError("missing bundled APK: " + str(path))
        if path.read_bytes()[:2] != b"PK":
            raise ValueError("not an APK: " + str(path))
        info = manifest(path)
        if info.get("package") != package:
            raise ValueError("%s is %s, expected %s" % (name, info.get("package"), package))
        lines.append("\t".join((name, package, info.get("versionCode", ""), info.get("versionName", ""), sha256(path))))
    return "\n".join(lines) + "\n"


def main(argv):
    if argv[:1] == ["--bundle"] and len(argv) == 3:
        Path(argv[2]).write_bytes(bundle(argv[1]).encode("utf-8"))
        print(argv[2])
        return
    if not argv:
        raise SystemExit(__doc__)
    for apk in argv:
        i = manifest(apk)
        print("\t".join((apk, i.get("package", ""), i.get("versionCode", ""), i.get("versionName", ""), sha256(apk))))


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
