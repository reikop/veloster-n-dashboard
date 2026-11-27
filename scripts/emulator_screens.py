#!/usr/bin/env python3
"""README screenshots from the real dashboard on an Android emulator (800x480, mdpi), fed a synthetic drive.

The dashboard needs the head unit's car data, so this builds a separate SCREENSHOT-ONLY APK from a temporary copy of the
sources: the 'automotive' library is made optional, and tests/emulator-screens/DemoFeed.java replays a one-minute drive
(launch, bends, braking, a pull, then cruising) through the same public VehicleDash entry points the car data client
uses, and switches pages and language on a broadcast. Nothing of it enters the release sources or APKs.

    python3 scripts/emulator_screens.py --serial emulator-5600 --android-jar … --r8-jar … --aapt2 … \
        --key platform.pk8 --cert platform.x509.pem [--adb adb] [--out docs/images/screens]

The emulator should already be running (any arm64/x86 image API 24+, 800x480, density 160). The dashboard on it is
replaced by the screenshot build, its data is cleared and its settings backup on the emulator storage is deleted.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SLUGS = ["overview", "f1", "gauges", "graphs", "performance", "taxi", "records", "race", "lambda", "tacho", "neon",
         "night", "vfd", "hud", "gcircle", "warp", "drag", "cassette", "show", "circuit", "car3d", "retro"]
PATCHES = [
    ("apps/dashboard/AndroidManifest.xml",
     '<uses-library android:name="automotive" android:required="true" />',
     '<uses-library android:name="automotive" android:required="false" />'),
    ("apps/dashboard/src/com/veloster/dashboard/DashboardActivity.java",
     "client=VehicleDataClient.shared(this);",
     "client=VehicleDataClient.shared(this);\n        DemoFeed.start(this);   // SCREENSHOT BUILD ONLY"),
    ("shared/src/com/veloster/vehicle/VehicleDash.java",
     "    public static void ignition(boolean on) { DATA.clear(); }",
     "    /** SCREENSHOT BUILD ONLY: show a page (and redraw after a language change). */\n"
     "    public static void demoPage(final int p){final VehicleDash d=current.get();if(d==null)return;d.post(new Runnable(){public void run(){if(d.settings)d.closeSettings();d.setPage(p,true);d.invalidate();}});}\n"
     "    public static void ignition(boolean on) { DATA.clear(); }"),
]


def run(*cmd, **kw):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, **kw)
    if r.returncode:
        raise SystemExit(f"failed: {' '.join(map(str, cmd))}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--adb", default="adb")
    for a in ("--android-jar", "--r8-jar", "--aapt2", "--key", "--cert"):
        ap.add_argument(a, required=True)
    ap.add_argument("--java", default="java")
    ap.add_argument("--out", type=Path, default=ROOT / "docs/images/screens")
    args = ap.parse_args()
    adb = lambda *a, **kw: run(args.adb, "-s", args.serial, *a, **kw)
    tap = lambda x, y: adb("shell", "input", "tap", str(x), str(y))   # 800x480 screen points

    with tempfile.TemporaryDirectory(prefix="vn-emu-screens-") as tmp:
        src = Path(tmp) / "src"
        shutil.copytree(ROOT, src, ignore=shutil.ignore_patterns(".git", "build", "images"))
        for rel, old, new in PATCHES:
            p = src / rel
            text = p.read_text(encoding="utf-8")
            if old not in text:
                raise SystemExit("patch target moved: " + rel)
            p.write_text(text.replace(old, new, 1), encoding="utf-8")
        shutil.copyfile(ROOT / "tests/emulator-screens/DemoFeed.java", src / "apps/dashboard/src/com/veloster/dashboard/DemoFeed.java")
        out = Path(tmp) / "build"
        run(sys.executable, src / "scripts/build.py", "dashboard", "--android-jar", args.android_jar, "--r8-jar", args.r8_jar,
            "--aapt2", args.aapt2, "--java", args.java, "--out", out)
        apk = Path(tmp) / "dashboard-SCREENSHOTS-ONLY.apk"
        run(sys.executable, ROOT / "scripts/sign_apk_v1_legacy.py", "--key", args.key, "--cert", args.cert, out / "dashboard-UNSIGNED.apk", apk)
        subprocess.run([args.adb, "-s", args.serial, "uninstall", "com.veloster.dashboard"], capture_output=True)
        adb("install", "-r", apk)

    adb("shell", "rm", "-f", "/sdcard/vndashboard/settings.txt")   # no "restore saved settings?" question: start fresh
    adb("emu", "geo", "fix", "126.9779", "37.5663")   # Seoul City Hall: the taxi meter picks Seoul
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "am", "start", "-n", "com.veloster.dashboard/.DashboardActivity")
    start = time.time()
    time.sleep(3)
    adb("shell", "am", "broadcast", "-a", "com.hkmc.intent.action.MODE_CHANGED", "--ei", "com.hkmc.mode.EXTRA_MODE_TYPE", "54")
    adb("shell", "am", "broadcast", "-a", "com.hkmc.audio.btaudio.widgetupdate", "--es", "filename", "'Midnight Run'",
        "--es", "artist", "'Neon Drive Band'", "--el", "audio_current_time", "94000", "--el", "audio_total_time", "236000",
        "--ez", "playing_state", "true")
    time.sleep(max(0, 64 - (time.time() - start)))   # past the scripted minute: the runs are measured, then cruising
    for lang in ("ko", "en"):
        (args.out / lang).mkdir(parents=True, exist_ok=True)
        adb("shell", "am", "broadcast", "-a", "com.veloster.dashboard.DEMO", "--es", "lang", lang, "--ei", "page", "0")
        time.sleep(2)
        for i, slug in enumerate(SLUGS):
            adb("shell", "am", "broadcast", "-a", "com.veloster.dashboard.DEMO", "--ei", "page", str(i))
            time.sleep(7 if slug == "car3d" else 4)
            shot = lambda name: (args.out / lang / f"{i + 1:02d}-{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))
            if slug == "retro":   # all eight styles: a tap on the body moves to the next (after its name banner fades)
                for k in range(1, 9):
                    shot(f"retro-{k}"); tap(400, 300); time.sleep(2.7)
                continue
            shot(slug)
            if slug == "car3d":   # the parts that open, by the test buttons: driver door + bonnet, then (from behind) the right doors + hatch
                tap(400, 240); time.sleep(1)
                tap(73, 389); tap(465, 389); time.sleep(2.6); shot("car3d-doors")
                tap(73, 389); tap(465, 389); time.sleep(0.3); tap(601, 30); time.sleep(1)
                tap(204, 389); tap(335, 389); tap(596, 389); time.sleep(2.6); shot("car3d-hatch")
                tap(204, 389); tap(335, 389); tap(596, 389); time.sleep(1.2); tap(545, 30); time.sleep(0.5); tap(400, 240)
    print("wrote", args.out)


if __name__ == "__main__":
    main()
