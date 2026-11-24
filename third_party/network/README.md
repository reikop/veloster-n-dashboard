# Optional API17 HTTPS transport

My Apps can package an ARMv7 curl executable statically linked to OpenSSL, plus an explicit Mozilla CA bundle. These are used by a bounded, no-shell download adapter; they do not replace system libraries or change vehicle firmware. The helper, CA and metadata are authenticated as part of the APK. No helper executable is downloaded at runtime.

Pinned source URLs, SHA256 and validation limits are in [sources.json](sources.json). Full third-party notices are in [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt); the larger NDK/LLVM notice set includes more than the linked runtime subset. No private key, OEM component or binary is committed here. Source archives are obtained separately from the listed official URLs; the build checks their hashes and does not download anything. The curl archive was fetched over official HTTPS and locally pinned; its saved detached signature was not verified with GPG.

```sh
python3 scripts/build_tls_helper.py --ndk /path/to/android-ndk-r22b \
  --sources /path/to/pinned-source-files --out /path/to/new-native-build
```

Use `bin/vn-curl-api17-armv7`, `cacert.pem` and `THIRD_PARTY_NOTICES.txt` from that output with the [Hub build flags](../../docs/BUILD.md#optional-api17-https-module). Different build paths can change binary bytes; each app build embeds and checks the hashes of its actual inputs. Keep the pinned TLS libraries and CA bundle maintained when preparing a future app release.

The generic API17 emulator verified authenticated HTTPS, invalid-CA/hostname rejection and app-process execution. This does not establish OEM Bionic/USB/network policy compatibility; see [update validation](../../docs/APP_UPDATES.md).

For USB Wi-Fi, supply the pinned c-ares archive and add `--dns cares` to the native build. Add `--tls-dns cares` when packaging that helper into the Hub. This enables per-request DNS and IPv4 source binding without changing system DNS. The c-ares detached signature was not verified; its downloaded archive is pinned by SHA256.

The API17 Java adapter is shared with the future-market client. Actual TLS1.2 and
TLS1.3 fixture tests and optional system-CA supplementation are documented in
[HTTPS compatibility](../../docs/HTTPS_COMPATIBILITY.md). The bundled helper and
CA bytes remain the pinned inputs; the system supplement is a separate addon.
