# HTTPS compatibility and a future market

Local My Apps v16 preview,2026-11-04. No vehicle installation or public release.
The intended next product is an app market. Its catalog and APK transport can use
the shared modern client prepared here; a complete market is not implemented.

## Modern transport in our code

`shared/src/com/veloster/network/ModernHttps.java` is a Java7/API17 client over the
APK-authenticated ARM curl/OpenSSL helper. The existing updater now uses it via
`NativeUpdateTransport` while keeping its original three allowed GitHub hosts
and16MiB limit. A market can reuse `AndroidTls.modern(context, curatedHosts,
networkPolicy)`. It uses modern TLS and an explicit pinned Mozilla root bundle
without replacing Android TLS libraries or enabling system CA supplementation.

- TLS1.2 minimum, TLS1.3 negotiated when available; normal CA and hostname checks.
- Curated exact HTTPS hosts,443 only, no URL credentials/fragments, at most4
  redirects; every redirect is revalidated. No curl configuration, proxy,
  cookies, shell or plaintext/insecure fallback.
- Cancellation and connection-change checks destroy the child; bounded headers,
  connect/transfer/stall deadlines, response size and final child exit checks.
- `bytes()` caps in-memory catalog responses at1MiB. Streaming requests and
  downloads permit an explicit ceiling up to64MiB. Existing updater limits stay16MiB.
- `download()` requires a new app-private staging file, expected length and
  SHA256; it fsyncs successful bytes and deletes a partial file on any failure.
  This checks integrity, **not publisher authorization**: metadata must first be
  authenticated with a trusted catalog key, and APK signer/minSdk/package/version
  checks and explicit installation approval remain the market's responsibility.
- Failure reasons distinguish DNS, network change, connection, timeout,
  certificate/hostname, TLS negotiation, HTTP response, policy, size, malformed
  response, local-module errors and integrity mismatch. Raw server error text
  and full URLs are not copied into the failure message.

Example for a future catalog (domain is a placeholder, not a live service):

```java
ModernHttps net = AndroidTls.modern(context,
    Arrays.asList("market.example", "cdn.example"), networkPolicy);
byte[] catalog = net.bytes("https://market.example/catalog.json", 256 * 1024, cancel);
// Verify the catalog signature before using its file metadata.
net.download(approvedUrl, newPrivateStage, approvedSize, approvedSha256, cancel);
// Verify the APK signer, Android compatibility and package/version before offering installation.
```

Do not obtain the allowed-host list or process-binding arguments from unverified
catalog content. The API supplies GET/HEAD for public catalogs/downloads; it does
not implement account authentication, cookies, billing, catalog publication or
an installer for arbitrary third-party applications.

## On-device check

**USB Wi-Fi → HTTPS check** compares a HEAD request using the modern helper with
Android's original TLS1.2-enabled HTTPS path. Enter an HTTPS URL without credentials
or query parameters. No redirects are followed during this check: even HTTP404
or405 can establish successful TLS while saying nothing about catalog availability.
Device time is shown for manual correction; the checker does not set the clock.
Android's connected-state result is shown separately from HTTPS success.

The current build uses fixed lowercase ASCII host names; enter the server's
lowercase ASCII/punycode name when checking a custom address. App-specific
certificates, caches and network restrictions can differ from this comparison.

## Optional support for other applications

**Enable CA compatibility** adds missing roots from the pinned public bundle to
a merged read-only view of `/system/etc/security/cacerts`. **Restore certificates**
returns to the original view. The original files/filenames and user-disabled
system roots are preserved. A mount identity/boot/inode receipt prevents removal
of foreign mounts. Installation never activates the overlay; reboot removes it.
Reopen an affected app after enabling/restoring if it caches trust.

The separate support addon is built from `tools/https-ca` in the research
workspace and included in the local one-shot delivery. It supplements missing
root trust only. It does **not** upgrade third-party TLS protocols, change
certificate pinning, remove old/revoked OEM roots, register a ConnectivityManager
network, or repair a retired service API. Apps that ship their own trust store
may not use the supplement. The market's modern transport does not need it.

## Evidence and limits

-69 Python tests and13 Java host programs;38 shared HTTPS cases and49 fixed
  Wi-Fi/terminal/internet/CA process checks.
- Actual generic ARM API17 Java client and packaged helper at UID10099: TLS1.2
  and1.3 GET, HEAD404, length/SHA256 transfer, expired/untrusted/wrong-host
  rejection, TLS1.0-only refusal and cancellation:9 scenarios.
- These use controlled local TLS servers and test-only connection mapping.
  The three-byte fixture transfer tests the download path, not APK installation.
- CA addon:16 ASan/UBSan merge/recovery scenarios,9 installer cases;154 native
  imports present in captured250902 libc/libdl (static evidence).
- Generic API17 ordinary-app DefaultTrustManager:140 roots →247 →140, read-only
  overlay, user-disabled DER root excluded and unchanged original bytes:7 cases.
  This observes trust-list behavior, not an external TLS handshake with new roots.
- Signed v16 installed and its actual check screen launched on generic API17.
  Physical Wi-Fi/USB, OEM root invocation/mount policy, installed-app behavior and
  vehicle runtime remain untested. Framework connected-network registration is
  still unresolved; the independent updater/market lease path does not rely on it.

[Machine-readable validation](validation/https-compat-20261104.json) ·
[USB Wi-Fi setup and remaining limits](USB_NETWORK.md)

## 한국어

마켓의 목록·APK 다운로드에는 준비한 공통 최신 HTTPS 모듈을 재사용할 수 있습니다.
Android4.2에서도 TLS1.2/1.3과 인증서/서버 이름 검증이 실제 동작했습니다.
이는 마켓 화면·카탈로그 서명 체계·외부 앱 설치까지 완성했다는 뜻은 아닙니다.

다른 앱은 선택형 인증서 보강으로 누락된 공개 루트 문제를 줄일 수 있습니다.
원본과 사용자 비활성화 설정을 보존하고 원복·재부팅으로 해제합니다. 구형 암호화
구현, 자체 인증서 저장소, 서버 API 종료, Android 연결 상태 의존성은 별도입니다.
