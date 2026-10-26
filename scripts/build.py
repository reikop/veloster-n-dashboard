#!/usr/bin/env python3
"""Build an unsigned API17 APK from public sources. Never connects to a vehicle."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def executable(value):
    found = shutil.which(str(value))
    if not found:
        raise SystemExit("Executable not found: " + str(value))
    return Path(found).resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", choices=("dashboard", "hub"))
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--r8-jar", required=True, type=Path)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / "scripts/generate_translations.py"), "--check"], check=True)
    java = executable(args.java)
    javac = executable(java.with_name("javac.exe" if os.name == "nt" else "javac"))
    aapt2 = executable(args.aapt2)
    sdk, r8 = args.android_jar.resolve(), args.r8_jar.resolve()
    for path in (sdk, r8):
        if not path.is_file():
            raise SystemExit("Required external tool file not found: " + str(path))
    out = (args.out or ROOT / "build" / args.app).resolve()
    out.mkdir(parents=True, exist_ok=False)

    def run(label, command):
        result = subprocess.run([str(part) for part in command], capture_output=True)
        log = out / (label + ".log")
        log.write_bytes(result.stdout + result.stderr)
        if result.returncode:
            raise SystemExit(label + " failed; see " + str(log))

    classes = out / "classes"
    classes.mkdir()
    app = ROOT / "apps" / args.app
    shared = ROOT / "shared" / "src"
    sources = sorted((app / "src").rglob("*.java"))
    classpath = str(sdk)
    if args.app == "dashboard":
        abi = out / "compile-only-abi"
        abi.mkdir()
        stubs = sorted((ROOT / "compile-stubs" / "src").rglob("*.java"))
        run("compile-abi", [javac, "-encoding", "UTF-8", "-source", "7", "-target", "7",
                            "-classpath", sdk, "-d", abi, *stubs])
        classpath = os.pathsep.join((str(sdk), str(abi)))
        sources += sorted(p for p in shared.rglob("*.java") if p.name != "UsbDebugControl.java")
    else:
        sources.append(shared / "com/veloster/vehicle/UsbDebugControl.java")
        sources += sorted((shared / "com/veloster/i18n").rglob("*.java"))
    run("javac", [javac, "-encoding", "UTF-8", "-source", "7", "-target", "7",
                  "-classpath", classpath, "-d", classes, *sources])
    # Only app classes enter D8; compile-only OEM declarations never enter the APK.
    run("d8", [java, "-cp", r8, "com.android.tools.r8.D8", "--min-api", "17",
               "--lib", sdk, "--output", out, *sorted(classes.rglob("*.class"))])
    dex = out / "classes.dex"
    if dex.read_bytes()[:8] != b"dex\n035\0":
        raise SystemExit("Unexpected DEX version; API17 requires DEX035")
    if list(out.glob("classes[2-9]*.dex")):
        raise SystemExit("Multidex output is not supported by this build")
    resources = []
    if (app / "res").is_dir():
        run("resources", [aapt2, "compile", "--dir", app / "res", "-o", out / "resources.zip"])
        resources = ["--auto-add-overlay", "-R", out / "resources.zip"]
    run("manifest", [aapt2, "link", "-I", sdk, "--manifest", app / "AndroidManifest.xml", *resources,
                     "--min-sdk-version", "17", "--target-sdk-version", "17", "-o", out / "base.apk"])
    target = out / (args.app + "-UNSIGNED.apk")
    with zipfile.ZipFile(out / "base.apk") as source, zipfile.ZipFile(target, "w") as archive:
        for entry in source.infolist():
            archive.writestr(entry, source.read(entry))
        archive.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    print("Created unsigned APK: " + str(target))
    print("Not installable as-is. See docs/BUILD.md for API17 signing and vehicle compatibility limits.")


if __name__ == "__main__":
    main()
