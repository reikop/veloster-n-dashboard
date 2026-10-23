# From an OEM update download to a standalone dashboard

**English** | [한국어](RESEARCH_ko.md) | [Project](../README.md)

This project began with the Veloster N BlueLink update package. We identified its Android components, traced how N Mode receives vehicle data, compared the download against the actual head unit, and eventually built a separate app. This guide records that process and provides a `nudl` download procedure for researchers.

You do **not** need to download or install OEM firmware to build the public app sources or install the supplied APKs. See [building](BUILD.md) and [installation](INSTALL.md) instead. The steps below download and inspect files on a computer; they are not vehicle flashing instructions.

## 1. Obtain the original package with nudl

[nudl](https://github.com/chenxiaolong/nudl) is an open-source, unofficial downloader for the publicly available HMG update catalog. Its license is GPL-3.0-only. This does not make the downloaded OEM firmware MIT/GPL software; our own app code has a separate MIT license.

Get an appropriate executable from [nudl releases](https://github.com/chenxiaolong/nudl/releases), then put it on your PATH. In our retained research, the source revision was [`f278391851f52b7b79687317de7940b3e739c000`](https://github.com/chenxiaolong/nudl/tree/f278391851f52b7b79687317de7940b3e739c000). Select a tool release supported by your OS; the current release is not necessarily that historical revision.

From the project directory, query the Korean catalog and download into a **separate sibling directory**, outside this public repository:

```sh
nudl list -b hyundai -r KR -m JSNAS.S5AMC.KR

VN_FW_DIR='../veloster-n-firmware-research/raw/Veloster_N_BlueLink'
mkdir -p "$VN_FW_DIR"

# Reproduce the analyzed package only if it is still listed by the server.
nudl download -b hyundai -r KR -m JSNAS.S5AMC.KR \
  -v JSN.KOR.SOP.V141.260114.STD_H \
  -o "$VN_FW_DIR"

nudl verify -d "$VN_FW_DIR"
```

The `-m`, `-r`, `-v`, output, and verification options are defined in the [upstream CLI](https://github.com/chenxiaolong/nudl/blob/master/src/cli.rs). On Windows, use `nudl.exe` and Windows-compatible paths; the multiline example above uses a POSIX shell.

| Reference field | Value used in this project |
| --- | --- |
| Region / brand | KR / Hyundai |
| Head-unit model | `JSNAS.S5AMC.KR` |
| Catalog description | Veloster N, Standard Gen5 BlueLink |
| Catalog mcode | `386` |
| Package directory | `Veloster_N_BlueLink` |
| Analyzed version | `JSN.KOR.SOP.V141.260114.STD_H` |
| Media listed by the catalog | SD |

The local catalog query dated 2026-10-13 returned this version and did not list the vehicle's older `250902` version. That observation is historical, not a promise of current availability. `-v` filters available entries; it does not recover a removed version. Stop if the exact package is unavailable rather than silently treating a newer version as equivalent.

The retained acquisition records include `nudl` source/API-based catalog research and a Navigation Updater download log for the original local package. The commands above are a documented reproduction route, not a claim that an exact full-download CLI transcript was preserved or rerun during public documentation work. We did not download another full firmware package for this update.

For a different region, first query that region and choose its actual model. For example, `nudl list -b hyundai -r US` lists US entries. Do not apply the KR model identifier to a US head unit. The later Korean `JSNBS.S5BMC.KR` Gen5W entry is also a different platform, despite the similar vehicle name. Hyundai's [regional update website](https://update.hyundai.com/US/EN/home) is an alternative acquisition route, not the focus of this walkthrough.

## 2. Preserve and identify the input

Keep the download untouched and work on a copy. Retain the `.ver` manifest, selected model/version, file sizes, and SHA-256 hashes. `nudl verify` checks the downloaded package against its manifest; that is not a check of a modified OTA's signature or its compatibility with your car.

The original package contained two platform branches:

```text
Veloster_N_BlueLink/
  Veloster_N_BlueLink.ver
  DAUDIOPLUS/kor/jsn/update/system/update_package.zip
  STDGEN5/kor/hyundaiF/update/system/update_package.zip
  BIN/
  DATA/
  vr2_6/
```

Reference SHA-256 values, checked against our retained original files:

| Outer update package | Bytes | SHA-256 |
| --- | --- | --- |
| `DAUDIOPLUS/.../update_package.zip` | 246782903 | `0d0990ff2d553bcba5b9f93e2a8ca2a43b4c1c2b500def45ad94779e47245a77` |
| `STDGEN5/.../update_package.zip` | 285039846 | `f120aa8f67785ae90c8dd41e289484a999bed57729d5a851b71846942fdf27cf` |

A different hash is a different input until explained. These hashes describe our research input; they do not authorize installation. The package-level version also does not establish that both branches contain identical component builds.

## 3. Open the package and read the filesystem

Our recorded extraction proceeded as follows:

1. Inventory the outer `update_package.zip`. It contained encrypted `update.zip` and `otacerts.zip` entries using legacy ZipCrypto.
2. Use [bkcrack](https://github.com/kimci86/bkcrack), historically version 1.8.1, with known ZIP/certificate structure to recover the archive encryption state. The second branch was checked against certificate bytes recovered from the first.
3. Decrypt into new files and verify every extracted entry's CRC. Later, compare the recovered state with the updater's property-derived password calculation. This established extraction correctness, not OTA signing authority.
4. Unpack the inner update archive and identify `system.ext4`, `boot.img`, `recovery.img`, and sparse images by their actual formats. Convert Android sparse images to raw images before treating them as ext4 filesystems.
5. Read `build.prop`, `/app`, and `/framework` from ext4 images using read-only filesystem tooling such as `debugfs`. Work from the matching branch; do not mix similarly named files from different versions.

For example, once a raw ext4 image has been identified, a read-only inspection is:

```sh
debugfs -R 'cat /build.prop' work/system.ext4
```

This command assumes you have already produced that file. The public repository does not ship an automatic OEM decrypt/extract pipeline, recovered archive material, or vendor images. This section records the stages and checks we performed.

The head-unit OS was Android 4.2.2 / API 17 on `tcc893x`. Qualcomm MDM9607 files in the same download belonged to the modem; their presence did not identify the main head-unit CPU. That distinction corrected an early interpretation.

## 4. Reconstruct the application and service paths

The factory N Mode app was `HKMC_NModeApp`, package `com.hkmc.app.nmode.ui`. Important application code lived in paired APK/ODEX files. We collected the matching framework dependencies before deodexing and inspected resources, smali, and service implementations together.

We used [REA](https://github.com/morluto/rea), smali/baksmali, and native analysis where needed. The goal was to follow actual callers and data flow rather than infer behavior from menu labels or strings. The main paths investigated were:

- N Mode UI → OEM managers/listeners → telemetry callbacks.
- Engineering Mode → Factory Test → OEM USB-mode request.
- Update/recovery → archive validation and signature checks.
- Later, head-unit receive daemon → `hscan_mux` → additional telemetry records.

Finding `WIFI ADB` text did not establish a usable menu entry. Likewise, decrypting `update.zip` did not produce a key accepted for signing modified OTA updates. Those were separate dead ends, not completed installation methods.

## 5. Compare with the actual vehicle, then establish ADB

Before ADB was available, USB-exported logs helped distinguish requested debug settings from real USB device enumeration. Code inspection gave the Factory Test sequence, but the physical PC connection still had to work. A later cable configuration produced Telechips/DiPO USB enumeration and a usable ADB connection. [ADB.md](ADB.md) records the sequence, password candidates, and limitations.

After connecting, we read vehicle properties and copied relevant original APK/ODEX/framework files for hash comparison. The actual build was `eng.vmadmin.20250902.081552`; the download was the `260114` package. The N Mode APK/ODEX pair matched in our comparison, while 30 compared framework files differed. A matching app therefore did not make the entire downloaded system identical to the vehicle.

This comparison is why the additional receiver in the current app checks both product `jsn_kr` and the exact incremental build. Merely installing a later official firmware is not a verified way to enable this app or ADB.

## 6. Prototype the UI, then separate the app

Early work used emulation and synthetic inputs to understand the UI. We then tried adding our dashboard to the factory N Mode entry flow. The added button could disappear after a complete head-unit restart, making that route unreliable for this project.

We moved to `com.veloster.dashboard`, an independent package, with a separate My Apps launcher. The public app uses authored UI code and compile-only OEM API declarations; it does not embed the OEM framework implementations. The factory home screen still needs a working app entry point, as explained in [INSTALL.md](INSTALL.md).

The build targets API 17 and DEX 035. Signing compatibility also mattered: a generic locally signed My Apps build was rejected on the tested unit. The distributed versions use the verified public AOSP test certificate with legacy V1 signing. This is not an OTA key or a universal installation guarantee.

## 7. Validate vehicle values and add the receive-only path

We first used the existing NMode/Vehicle/BCM/PGS callbacks. Values were checked for units, invalid sentinels, and expiry instead of labeling every changing byte as a sensor. For example, the four TPMS readings were matched to the vehicle display and confirmed as psi.

An initial additional Binder-listener approach received readiness notifications but no telemetry during the observation window. Further tracing found the path used by an OEM consumer:

```text
vehicle-side input → hscand → hscan_mux → existing OEM consumer
                                   └→ independent receive-only dashboard client
```

The app reads this existing local socket without starting the OEM communication runner or sending ECU diagnostic requests. It validates the 264-byte framing, checksum, item lengths, and allowed functions. Bounded queues and reset handling prevent stale partial frames from being combined after a gap.

v5 added intake temperature, battery voltage/SOC, steering speed, TCS/ESC/ABS state, and wheel-speed decoding. Initial vehicle display was verified on 2026-10-22. Exhaust temperature and e-LSD remained unconfirmed; they were not assigned from unrelated raw numbers. Long-duration behavior, cold boot, and active intervention transitions remain separate validation tasks.

## 8. Reproduce the public part of the project

You can build and test the authored app without downloading any OEM images:

```sh
python3 scripts/test.py --java /path/to/jdk17/bin/java
python3 scripts/verify_release.py
```

Follow [BUILD.md](BUILD.md) or the [English README build example](../README.md#source-and-tests) for the external SDK tool paths. The public build emits unsigned APKs. During export, both rebuilt DEX/resource/manifest payloads matched the supplied signed releases; nine synthetic host test programs passed. That is distinct from vehicle validation.

For research on another head unit, record its region/model/build, keep its original files privately, and verify each ABI and data format independently. Share conclusions or appropriately redacted evidence rather than raw catalog authorization fields, vehicle identifiers, OEM binaries, or private logs. Nothing in the public MIT license grants rights to redistribute the manufacturer's firmware.
