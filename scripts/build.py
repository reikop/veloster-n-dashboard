#!/usr/bin/env python3
"""Build an unsigned API17 APK from public sources. Never connects to a vehicle."""
import argparse
import hashlib
import json
import os
import re
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
    parser.add_argument("app", choices=("dashboard", "hub", "neondrive"))
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--r8-jar", required=True, type=Path)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--tls-helper", type=Path, help="Optional API17 ARMv7 PIE curl executable (Hub only)")
    parser.add_argument("--tls-ca", type=Path, help="Public PEM CA bundle for the helper")
    parser.add_argument("--tls-notices", type=Path, help="Third-party license/notices to include with the helper")
    parser.add_argument("--tls-dns", choices=("system", "cares"), default="system", help="Declared resolver capability of the locally built TLS helper")
    parser.add_argument("--carplay-crypto", type=Path, help="Optional locally built ARM/API17 wireless receiver JNI library (Hub only)")
    parser.add_argument("--carplay-crypto-manifest", type=Path, help="Matching build_carplay_crypto.py manifest")
    parser.add_argument("--carplay-eld", type=Path, help="Optional locally built API17 AAC-ELD JNI shim (Hub only)")
    parser.add_argument("--carplay-eld-manifest", type=Path, help="Matching build_carplay_eld.py manifest")
    parser.add_argument("--oem-services-jar-sha256", help="Read-only OEM JAR expectation for an isolated Hub build; does not authorize installation")
    args = parser.parse_args()
    from oem_services_build import validate as validate_oem_pin
    try:
        validate_oem_pin(args.app, args.oem_services_jar_sha256)
    except ValueError as error:
        parser.error(str(error))
    if args.app != 'hub' and (args.carplay_crypto or args.carplay_crypto_manifest or args.carplay_eld or args.carplay_eld_manifest):
        parser.error('Wireless receiver JNI is only included in Hub builds')
    native_assets = {}
    if args.tls_dns == "cares" and not args.tls_helper:
        raise SystemExit("Isolated DNS requires a c-ares TLS helper")
    if any((args.tls_helper, args.tls_ca, args.tls_notices)):
        if args.app != "hub" or not all((args.tls_helper, args.tls_ca, args.tls_notices)):
            raise SystemExit("Hub TLS build requires --tls-helper, --tls-ca and --tls-notices together")
        limits = [(args.tls_helper,16*1024*1024),(args.tls_ca,512*1024),(args.tls_notices,2*1024*1024)]
        loaded = []
        for path,limit in limits:
            with path.open('rb') as stream:
                data = stream.read(limit+1)
            if not data or len(data)>limit:
                raise SystemExit("Missing or oversized TLS build input: " + str(path))
            loaded.append(data)
        helper,ca,notices = loaded
        if len(helper)<52 or helper[:6]!=b"\x7fELF\x01\x01" or helper[16:20]!=b"\x03\x00\x28\x00":
            raise SystemExit("TLS helper must be a little-endian ARM32 PIE ELF")
        if re.search(br'-----BEGIN [^-]*PRIVATE KEY-----',ca+notices):
            raise SystemExit("Private keys must never be packaged as TLS assets")
        if b'-----BEGIN CERTIFICATE-----' not in ca:
            raise SystemExit("TLS CA input must be a public PEM certificate bundle")
        metadata=("format=2\ndns=cares\n" if args.tls_dns == "cares" else "format=1\n")+"executable="+hashlib.sha256(helper).hexdigest()+"\nca="+hashlib.sha256(ca).hexdigest()+"\n"
        native_assets = {"lib/armeabi-v7a/libvnnet.so":helper, "assets/updates/ca.pem":ca,
                         "assets/updates/THIRD_PARTY_NOTICES.txt":notices,
                         "assets/updates/native.properties":metadata.encode('ascii')}

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
    if args.app in ("dashboard", "neondrive"):
        abi = out / "compile-only-abi"
        abi.mkdir()
        stubs = sorted((ROOT / "compile-stubs" / "src").rglob("*.java"))
        run("compile-abi", [javac, "-encoding", "UTF-8", "-source", "7", "-target", "7",
                            "-classpath", sdk, "-d", abi, *stubs])
        classpath = os.pathsep.join((str(sdk), str(abi)))
        if args.app == "dashboard":
            sources += sorted(p for p in shared.rglob("*.java") if p.name != "UsbDebugControl.java")
            # Neon Drive runs as a dashboard page too (NeonDrivePage); its standalone activity stays in its own app.
            sources += sorted(p for p in (ROOT / "apps/neondrive/src").rglob("*.java") if p.name != "NeonDriveActivity.java")
        else:
            # neondrive: passive hscand socket, compile-only OEM listener ABI (gear) and the shared
            # now-playing receiver (OEM media widget broadcasts) with its artwork decoder.
            sources += [shared / "com/veloster/vehicle" / (n + ".java") for n in ("MediaState", "MediaReceiver", "ArtworkLoader")]
    else:
        sources.append(shared / "com/veloster/vehicle/UsbDebugControl.java")
        sources += sorted((shared / "com/veloster/i18n").rglob("*.java"))
        sources += sorted((shared / "com/veloster/diagnostics").rglob("*.java"))
        sources += sorted((shared / "com/veloster/network").rglob("*.java"))
        from carplay_build_inputs import load as load_carplay
        wireless_sources, wireless_assets, wireless_metadata = load_carplay(ROOT, args.carplay_crypto, args.carplay_crypto_manifest,args.carplay_eld,args.carplay_eld_manifest)
        sources += wireless_sources
        native_assets.update(wireless_assets)
        (out / 'wireless-build.json').write_text(json.dumps(wireless_metadata, indent=2) + '\n')
    tracked = list(sources) + [app / 'AndroidManifest.xml', Path(__file__).resolve(),
                               ROOT / 'scripts/oem_services_build.py']
    generated_sources = {}
    oem_pin = None
    if args.app == 'hub':
        from oem_services_build import source as oem_source
        template = app / 'src/com/veloster/carplayhub/HubApplication.java'
        generated, oem_pin = oem_source(template.read_bytes(), args.oem_services_jar_sha256)
        if generated is not None:
            if sources.count(template) != 1:
                raise SystemExit('OEM service build source is not a unique Hub input')
            replacement = out / 'generated-source/com/veloster/carplayhub/HubApplication.java'
            replacement.parent.mkdir(parents=True)
            replacement.write_bytes(generated)
            sources[sources.index(template)] = replacement
            generated_sources[str(replacement.relative_to(out))] = hashlib.sha256(generated).hexdigest()
    tracked += sorted((app / 'res').rglob('*')) if (app / 'res').is_dir() else []
    if args.app == 'hub':
        tracked += [ROOT / 'scripts/carplay_build_inputs.py', ROOT / 'third_party/wireless-carplay/source-manifest.json']
    source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in tracked if path.is_file()}
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
        for name,data in native_assets.items():
            archive.writestr(name,data,compress_type=zipfile.ZIP_DEFLATED)
    for name, digest in source_hashes.items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise SystemExit('Source changed during build: ' + name)
    for name, digest in generated_sources.items():
        if hashlib.sha256((out / name).read_bytes()).hexdigest() != digest:
            raise SystemExit('Generated source changed during build: ' + name)
    report = {'passed': True, 'app': args.app, 'min_api': 17, 'source_hashes': source_hashes,
              'unsigned_apk_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
              'dex_sha256': hashlib.sha256(dex.read_bytes()).hexdigest(),
              'native_assets': {name: hashlib.sha256(data).hexdigest() for name, data in native_assets.items()},
              'runtime_verified_by_this_build': False, 'vehicle_accessed': False}
    if args.app == 'hub':
        report.update(oem_services_jar_sha256=oem_pin,
                      oem_services_jar_overridden=args.oem_services_jar_sha256 is not None,
                      generated_source_hashes=generated_sources,
                      oem_selection_authorizes_installation=False)
    (out / 'build-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print("Created unsigned APK: " + str(target))
    print("Not installable as-is. See docs/BUILD.md for API17 signing and vehicle compatibility limits.")


if __name__ == "__main__":
    main()
