# Wireless CarPlay receiver source

This directory contains the API17 receiver implementation used by the My Apps
wireless preview: Java control, pairing, iAP2, audio/video, input, discovery and
lifecycle code, JNI C sources, and attribution notices. `source-manifest.json`
pins the exported bytes. No OEM implementation, firmware, private signing key or
vehicle capture is included.

The native-mode observer reads a pinned JSN250902 Binder contract to recognize
qualified native screen choices. An optional, separately prepared OEM producer
also retains screen/music history while the app process is absent. Its exact JAR
hash and a real UID1000 Binder callback are required before restoring matching
app checkpoints. Missing, ambiguous or discontinuous history stays unobserved;
no OEM mode is written. The OEM implementation is not included here. Generic
Android Binder tests use a synthetic service and do not prove OEM boot/runtime
compatibility. A read-only wheel-speed source and PASCD encoder are also included,
but the default backend uses GPS only; unknown wheel slip is not invented. See the [current validation scope](../../docs/WIRELESS_CARPLAY.md).

The receiver module is GPL-3.0-only. JmDNS and its logging compatibility adapter
retain their Apache-2.0 notices. The MIT license of other project files does not
relicense this module. A combined Hub build includes GPLv3 code and its
corresponding source; preserve these notices when distributing that build.
Historical component notices describe when the code was still isolated. This
later integration remains a local preview without verified vehicle operation.

The JNI library links the pinned OpenSSL 3.5.9 under Apache-2.0. Reproduce its
installation with the [TLS recipe](../network/README.md), then run:

```sh
python3 scripts/build_carplay_crypto.py --ndk /path/to/android-ndk-r22b \
  --openssl-build /path/to/tls-build --out /path/to/new-carplay-native
```

Pass `libvn-carplay-crypto.so` and `build-manifest.json` to the Hub build using
`--carplay-crypto` and `--carplay-crypto-manifest`. The builder checks the library
hash and JNI source set and packages the full notices. A Hub build omitting this
pair has the Java/settings code but no usable receiver crypto module.

The AP manager/hostapd, USB driver and OEM authentication prerequisites are
separate installation components. Java/JNI packaging does not install them or
prove radio, phone, reconnect, OEM audio, microphone or call operation.

The analysis workspace's `tools/wireless-carplay/export_public_sources.py`
exports an explicit production source/notice allowlist and refuses to overwrite
changed public source. The public builder verifies the manifest and rejects
unlisted Java source files.
