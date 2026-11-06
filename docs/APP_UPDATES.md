# App updates / 앱 업데이트 준비

[Documentation](README.md) · [USB networking readiness](USB_NETWORK.md) · [Status](STATUS.md)

**Unreleased source candidate, 2026-11-02.** My Apps now has **App updates / 앱 업데이트**. The first installation of this updater still uses the existing ADB workflow. This change does not publish a new APK release or an online update feed.

## Intended flow

1. While parked, choose **Check N Dashboard updates**, **Check My Apps updates**, or **Find USB/SD updates**.
2. The app checks the signed release metadata, exact device identity, newer version, APK size/hash, V1 signatures and installed app signer. Files are copied into internal app storage before use.
3. Choose **Open installer** for the verified candidate. The existing Android system installer handles user confirmation and replacement; no `amossu`, root shell or hidden installation API is invoked.
4. On return, or when the updated My Apps is reopened, the updater compares the installed APK's version and full SHA256 to the pending receipt. An accepted launch or matching version alone is not reported as success.

The current preview source uses My Apps versionCode14 (saved updates and route checks) and N Dashboard versionCode7; locally signed candidates and authenticated USB bundles have been prepared. Published v10/v6 remain unchanged. Equal-version updates and downgrades are refused by this updater. The old ADB workflow remains available for deliberate recovery.

## Saved updates (local v14 preview)

A fully verified online download or USB import is retained in private app storage,
with one candidate per app. Cancelling the install offer keeps this copy.
**Find saved updates** lets you select an app and reopen its installer without
internet or the original USB drive. Every reuse verifies the original signed
release metadata, complete APK hash/V1 signature, current target and installed
version. Installer launch still needs an explicit foreground action.

Online checks reuse an identical saved APK. If the check/download fails and a
valid saved candidate exists, the screen explicitly offers that saved version;
it does not claim the online check succeeded. A replacement is published only
after verification and receipt persistence. A failed replacement retains the old
candidate. Older imports do not overwrite a newer saved candidate. Reopening the
screen removes obsolete/tampered candidates and abandoned partial files; an APK
still handed to the installer is retained until reconciliation. Saved files are
not a general backup or a guarantee of storage surviving a hard power loss.

The network gate also checks the actual default route before accepting an Android
Wi-Fi/Ethernet/Bluetooth label. A preferred cellular or unknown interface blocks
traffic even if that label is stale. The verified external USB Wi-Fi path remains
separate. Policy routing and target-specific networking remain unverified.

## Online and USB use

Online checks are explicit foreground actions, not boot-time polling. Each app has its own future feed:

```text
https://raw.githubusercontent.com/reikop/veloster-n-dashboard/main/updates/stable/dashboard/manifest.txt
https://raw.githubusercontent.com/reikop/veloster-n-dashboard/main/updates/stable/dashboard/manifest.sig
https://raw.githubusercontent.com/reikop/veloster-n-dashboard/main/updates/stable/hub/manifest.txt
https://raw.githubusercontent.com/reikop/veloster-n-dashboard/main/updates/stable/hub/manifest.sig
```

**Those feeds have not been published.** A 404 currently means that no update file is available. APK download URLs must point to this repository's GitHub release assets. HTTPS certificate and hostname checks remain enabled. A Hub built with the optional native module uses its APK-bundled public CA set; a build without it uses Android system trust. The generic API17 Java HTTPS path failed against GitHub even after enabling TLS1.2. The packaged native path succeeded in the same emulator. Actual OEM execution, networking and clock correctness remain untested. There is no insecure HTTP or certificate-bypass fallback.

The optional [native HTTPS module](../third_party/network/README.md) is curl/OpenSSL compiled for API17 ARMv7, packaged inside the APK and executed with the app's existing permissions. The app checks the extracted executable and CA hashes against metadata inside the signed APK. It does not replace system TLS libraries or add root privileges. The helper disables curl configuration, proxy use and automatic redirects; Java validates each redirect's HTTPS host. A watcher terminates the process on cancellation, deadline or network-policy failure. Response headers, body sizes and child exit status are checked before accepting data. Builds without this module reject API17's legacy SSLv3 retry by refusing a second socket; they may still be unable to contact GitHub. The [Android socket documentation](https://developer.android.com/reference/javax/net/ssl/SSLSocket) distinguishes TLS1.2 support from default enablement; enabling it alone is not proof of endpoint compatibility.

Network use permits an active Wi-Fi, Ethernet or Bluetooth network. Known cellular types are blocked. When the OEM does not expose a network agent, a bounded `/proc/net/route` check permits only an unambiguous lowest-metric default route named `wlanN`, `ethN`, `usbN`, `rndisN` or `bnepN`; an unknown or preferred `rmnetN` route is rejected. This is an interface policy, not proof of physical hardware or free data service. The policy is checked before each connection/redirect and each response read. API17 cannot bind this request to a specific Android Network; a routing change between checks or OEM policy routing can still escape this best-effort check. Zero cellular bytes are not guaranteed, and actual routing still requires target verification. The app does not enable radios, configure DHCP/DNS/routes or install a driver.

For offline updates, copy an authenticated bundle into either of these folders on USB/SD:

```text
vn-update/dashboard/manifest.txt
vn-update/dashboard/manifest.sig
vn-update/dashboard/package.apk

vn-update/hub/manifest.txt
vn-update/hub/manifest.sig
vn-update/hub/package.apk
```

The picker checks a fixed list of legacy USB/SD mount roots; it does not recursively search personal files. The same release signature, target, version, size, hash and APK signer checks apply to online and USB inputs. A plain APK without its signed metadata is not an update bundle.

## First installation of the updater

The signed My Apps v11 preview can be authenticated locally without touching a vehicle:

```sh
python3 scripts/bootstrap_updater.py --bundle /path/to/vn-update/hub \
  --java /path/to/jdk17/bin/java --aapt2 /path/to/aapt2
```

This checks the publisher signature, APK V1 signatures, full digest and actual package/version metadata from a frozen bundle snapshot. It accepts only My Apps successors after v10. Adding an exact `--serial SERIAL --adb /path/to/adb` performs read-only target checks and makes a private APK backup. Installation additionally requires `--install --parked`, and uses the existing supported-device, free-space, recognized-installed-hash, recheck-before-write and APK readback checks. **Only My Apps is selected**; an existing private dashboard trial is preserved. Unknown existing My Apps bytes cause a stop after backup, not an automatic replacement or uninstall.

The ordinary `scripts/install.py` still selects the published v6/v10 release. It does not automatically select these preview artifacts. After bootstrap, use USB/SD to test a newer authenticated Dashboard bundle separately. Do not attempt to reinstall the same My Apps v11 through its updater; equal versions are rejected. A later self-update needs a separately reviewed higher version.

## Connection diagnostics without ADB

**Save connection diagnostics / 연결 진단 저장** reads a bounded list of USB descriptor fields, interface names/states, the kernel release and whether a local default route is visible. It sends no network request and changes no settings. It does not read serial numbers, MAC addresses, SSIDs, credentials or IP/gateway addresses.

The app shows the saved folder: its external app-files directory, or internal files when external storage is unavailable, under `network-diagnostic/`. `usb-match-descriptors.stdout` preserves sysfs path/value pairs for later offline matching; `network-summary.txt` records the observation limits. Missing/inaccessible fields are unknown, not proof of absent hardware. USB enumeration or a matching ID rule does not prove networking. This is useful when plugging in a dongle occupies the port otherwise used for ADB. Nine synthetic filesystem checks cover field limits, interface association and privacy exclusions.

## Release authorization and preparation

The update authorization key is separate from the APK signing certificate. This distinction matters because the vehicle-compatible APK certificate is an OEM/AOSP test identity and is not sufficient proof that a file is an approved project release.

Only the **public** authorization key is in [updates/update-public.pem](../updates/update-public.pem) and `UpdateTrust.java`. The private key is external to this repository. Keep a private backup; changing the embedded trust key requires a planned app migration or another ADB installation.

Prepare a bundle from an already signed API17 APK:

```sh
python3 scripts/prepare_app_update.py \
  --apk /path/to/signed-new-app.apk \
  --key /private/path/update-signing-key.pem \
  --url https://github.com/reikop/veloster-n-dashboard/releases/download/TAG/APP.apk \
  --java /path/to/jdk17/bin/java \
  --aapt2 /path/to/aapt2 \
  --out /path/to/new-bundle
```

This takes one bounded snapshot of the input APK, then reads its identity through aapt2, checks all V1-signed payload entries using the same verifier as the app, and signs a strict ASCII manifest with RSA2048/SHA256. The embedded public key must verify the result. Every check and output copy uses that same snapshot; an output-copy failure removes the incomplete new directory. It creates a new local output directory; it does not sign an APK, copy private keys, fetch a URL, publish a release or contact a vehicle. Its child JVM permits legacy SHA1 APK signatures to model API17; no global Java security configuration changes.

Future publishing must upload the exact signed APK bytes to the manifest's release URL before making `manifest.txt` and `manifest.sig` available together at the matching feed. A partial feed update fails signature validation rather than installing mixed data. No updater certificate/key or firmware changes are pushed to the head unit by this tool.

## Verification and remaining target checks

- 41 real-cryptography/staging/transport/route host checks, using temporary signed synthetic APKs.
- 22 checks against the actual Android adapter with PackageManager/preferences doubles: device/signer restrictions, system-only installer, receipt failure, cancellation, version-plus-hash readback, replacement-receipt races and cleanup across Activity instances.
- 31 native-process adapter checks cover bounded headers/body, redirect policy, nonzero exits, cancellation while blocked and process cleanup.
- 10 checks against the actual Activity with deterministic UI/backend doubles: duplicate requests, user confirmation, cancellation/pause, late results and no automatic relaunch on return.
- The preparation tool also authenticated a local bundle of the unchanged released v6 APK. This validates authoring/cryptography only; the updater rejects it on an already-v6 dashboard because its version is not newer.
- Existing captured package inventory contains `com.android.packageinstaller`. AOSP4.2.2 PackageInstaller accepts `file:`/`package:` URIs, so the adapter hands off a read-only-readable copy of the verified public APK in app-owned storage. It does not use a modern `content:` installation API. Actual OEM access, unknown-source policy, installer UI and self-update survival remain unverified; generic emulator tests are listed separately below.

Generic Android4.2.2 ARM emulator checks additionally exercised the final Hub v11 candidate with its normal app UID: both real signed bundles passed RSA/V1/full-file checks, private staging and cleanup worked, and the actual update Activity opened. The packaged native backend fetched GitHub/raw content and handled missing feeds as HTTP404. Independent native tests rejected an unrelated CA, a hostname mismatch and oversized content. The unmodified emulator was rejected by the production target gate.

A separate **emulator-only v12 fixture** exercised My Apps self-update through the real Android installer. The instrumentation temporarily substituted product/build fields inside its own process, restored them before handoff, and enabled unknown-source installation in the fresh emulator. After UI confirmation and package replacement, a new process read the persisted receipt, verified installed version plus SHA256, removed the staged file and returned no pending result on a second check. This test did not weaken production checks, change firmware properties, validate OEM installer policy or publish v12. Transport probes used a test network policy; production local-network selection still requires the vehicle. No fixture or test instrumentation is included in the preview kit.

Manifest size is limited to4 KiB, APK to16 MiB, expanded ZIP contents to128 MiB and entry count to4,096. Redirects are bounded and restricted to GitHub HTTPS hosts; downloads have per-operation deadlines and I/O timeouts. Partial copies are removed. A pending installation candidate is retained for confirmation, and abandoned candidates are cleaned on reopening. Process-wide stage leases protect active downloads from another Activity’s cleanup. Reconciliation hashes outside the state lock and clears a receipt only if its identity still matches, preserving a newer install request. Cancellation/pause prevents a late installer launch; it does not reverse an installation already handed to Android. A failed/unconfirmed install leaves the existing app state to the platform and does not initiate a downgrade.

Host tests use synthetic fixtures; generic API17 emulator observations are recorded separately. The Wi-Fi dongle, vehicle network path, OEM package installation, power loss and OEM TLS execution still require vehicle tests. The public builder emits unsigned APKs. Locally signed preview candidates remain outside the published release. See the [initial validation](validation/app-update-prep-20261102.json) and [preconnection follow-up](validation/app-update-preconnection-20261102.json).

## 한국어

동글 없이 준비 가능한 업데이트 화면·온라인/USB 공통 검증·배포 묶음 생성 도구를 구현했습니다. 첫 설치는 ADB가 필요합니다. 이후 승인된 새 파일을 다운로드하거나 USB에서 읽어 Android 설치 화면으로 넘기고, 돌아오면 실제 설치 파일의 버전과 해시를 확인합니다.

내 앱 v11·계기판 v7의 로컬 서명 후보와 USB 묶음은 준비했으며, 새 APK 공개 배포와 온라인 피드는 아직 게시하지 않았습니다. Wi-Fi 인식만으로 자동 설치되는 기능이 아니며, 네트워크·순정 설치 화면·자기 업데이트·전원 종료 뒤 상태는 실차 확인이 남아 있습니다. 순정 펌웨어 전체 업데이트나 무선 CarPlay/Android Auto 활성화는 이 기능에 포함하지 않습니다.

## Shared HTTPS client (local v16)

The native updater now delegates to the reusable market/download client while
retaining the existing GitHub host policy,16MiB limits and signed-update checks.
See [HTTPS compatibility](HTTPS_COMPATIBILITY.md). This does not publish a market
or relax update authorization.
