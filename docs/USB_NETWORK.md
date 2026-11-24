# USB Wi-Fi and media / USB Wi-Fi·음악 동시 사용 준비

[Documentation](README.md) · [App updates](APP_UPDATES.md)

Reviewed 2026-11-04. The target is Korean JSN250902, Android 4.2.2/API17.
Vehicle connection remains excluded from this preparation. No physical adapter,
hub, iPod playback or radio operation has been tested by this work.

## Current implementation

My Apps v13 local preview adds **USB Wi-Fi**: save and connect to a WPA2 network,
reconnect to the saved network, disconnect, and check the connection. Initial
installation of the separate support kit is required. The screen does not start
a connection just by opening it. Passwords are passed through standard input to
the fixed connection manager, never command arguments or diagnostic records.
Saved credentials live in a root-only directory managed by that tool.

The updater accepts a current external Wi-Fi lease only when its boot identity,
expiry, interface address and default route match. A c-ares TLS helper binds both
DNS and HTTPS to that address. A detached interface or changed connection
invalidates the download. DHCP renewal with the same address, DNS and session
can extend its expiry. This updater transport does not change Android's global DNS.
The separate, explicit internet switch below can temporarily configure global DNS.

The support kit has been assembled and locally validated; it is not part of the
published v6/v10 release. A built driver or APK does not establish concurrent
operation on the vehicle.

## Password SSH terminal (local v13 preview)

After the separate remote-access addon is installed, use **USB Wi-Fi → Terminal
password → Save password → Enable terminal**. The app displays the address and
server fingerprint. From a computer on the same reachable network, run
`ssh -p 2222 root@ADDRESS`, compare the first-connection fingerprint with the app,
and enter the saved terminal password. A client key is not required.

There is **no 15-minute or idle-session limit**. Disable terminal ends access;
Wi-Fi disconnection, address/session change, manager exit or reboot also ends it.
Opening/closing the app does not automatically enable/disable an existing session.
After reconnecting or rebooting, enable it again. The password is retained for
later use; saving a replacement password first closes active terminal access.

This is a root terminal on the current USB Wi-Fi IPv4 address, TCP2222. Wireless
ADB is not enabled. The addon leaves the system password database unchanged and
stores a salted PBKDF2 verifier in its root-only directory. Passwords travel over
a local stdin pipe during setup and inside SSH during login, never command-line
arguments. The app's terminal password requires at least8 characters and at most
128 UTF-8 bytes. The first server host key is generated on the target.

The support addon is a local candidate, not included in the published APKs. Initial
support-tool and app installation are still required. Hotspot/client isolation
may prevent one device reaching another; actual vehicle reachability is untested.

## Internet for other apps (local v15 preview)

After connecting Wi-Fi, choose **Enable app internet**. This separate support
addon temporarily applies the hotspot's DNS servers for ordinary Android name
resolution. **Restore internet settings** disables it; **Internet status** shows
whether settings are applied or restoration is needed. A status of applied does
not prove upstream internet access. Opening the screen or installing the kit
never enables it automatically.

The addon requires the current manager, boot, lease, interface address and sole
USB Wi-Fi default route. Disconnect, expiry, manager death or session/DNS change
restores the previous values unless another network owner has superseded them.
After reconnecting, enable it again. A write-ahead receipt allows recovery after
an abrupt helper crash; failed restoration stays visible until retried. No
system partition or OEM Ethernet configuration is changed.

A generic ARM API17 emulator verified real system properties, ordinary UID10099
`getaddrinfo` through netd's DNS proxy, and TCP/HTTP to a controlled fixture.
Nine lifecycle/runtime scenarios,27 native transaction scenarios and8 addon
installer tests passed. The signed v15 screen also launched on that emulator.
This is not a vehicle, Wi-Fi radio, OEM root invocation or remote HTTPS test.

**Android framework network registration remains unresolved.** The inspected
OEM Ethernet setup also changes DHCP, addresses and routes, conflicting with
our current manager. A connected-state broadcast alone is insufficient. Apps
that require ConnectivityManager to report a connected default may still block
access; per-app DNS and old HTTPS implementations can also fail. The updater's
existing modern TLS transport remains independent.

**한국어:** Wi-Fi 연결 후 **다른 앱 인터넷 켜기**를 누르면 핫스팟 DNS를 시스템에
적용합니다. 해제·연결 변경 시 기존 값을 복원하며 **인터넷 설정 복원**으로 직접
끌 수도 있습니다. 일부 앱의 ‘오프라인’ 판정과 구형 HTTPS 문제까지 해결된 것은
아닙니다. 동글·차량 연결 시험은 하지 않았습니다.

## HTTPS compatibility (local v16 preview)

**USB Wi-Fi → HTTPS check** now compares the modern module and Android HTTPS.
Optional **Enable CA compatibility / Restore certificates** controls a separate
read-only certificate-store supplement for other apps. It is not needed by our
modern updater or future market client. See [implementation, use and limits](HTTPS_COMPATIBILITY.md).

## Intended device pairs

- Actual **iPod classic + USB Wi-Fi**, preserving OEM music playback and controls.
- **USB mass storage + USB Wi-Fi**, preserving OEM storage playback.

Three devices at once are not required. Wireless CarPlay/Android Auto and vehicle
firmware OTA are separate work; this connection is intended for app updates.

| Requested adapter | Locally prepared driver candidate | Limit |
| --- | --- | --- |
| ipTIME A3000mini | RTL8812BU / `88x2bu` | Retail hardware revision and descriptors unobserved |
| ipTIME AX900mini | RTL8851BU / `8851bu` | Wi-Fi candidate, actual RF operation untested |
| TP-Link Archer TX10UB Nano | RTL8851BU / `8851bu` | Wi-Fi only; USB Bluetooth interface excluded |
| FineDrive owned USB `0bda:8176` | RTL8188CUS / `8192cu` | 2.4 GHz; USB ID observed on Mac, radio/vehicle untested |
| NEXT NEXT-202N MINI | RTL8188EU / `8188eu` | 2.4 GHz only; retail descriptors unobserved |

The FineDrive addition is in the local v16 FineDrive installation bundle. The
app APK remains v16; driver, loader and manager updates are installed through
USB ADB. Only the observed ID/interface is enabled, not every FineDrive model.

The earlier RTL8812AU candidate is also retained. These are conditional module
candidates, not a claim that any arbitrary revision of a listed product works.
Loader matching uses full interface descriptors and refuses ambiguous adapters.

## Hub evidence and limits

The downloaded260114 kernel has USB host, storage and module support. Its external
hub blacklist is disabled; the inspected Android USB path blacklist is empty.
A reference kernel source raised an Apple sibling-device rejection concern.
Later bounded replay of the actual260114 ARM instructions showed the normal
success path sets the relevant flag and permits a modeled iAP1 sibling. A
separate iAP2 condition rejects the modeled sibling. This does not justify a
mandatory kernel patch for iPod classic, and no kernel replacement is included.

The inspected260114 vold path predicate uses controller-prefix matching, which
allows nested hub paths. The inspected iPod finder traverses buses and devices;
no hub-depth limit was found in that function. This is static/bounded evidence,
not complete USB or media execution. The reported hub failure remains unexplained.

The exact current250902 kernel/config is unavailable. Its captured `bcmdhd.ko`
fingerprint and exact product/build identity are checked by the support tools;
matching them is a prerequisite, not proof of module compatibility. Power,
USB host role, enumeration, playback and sustained networking need hardware
verification when a separate vehicle session is authorized.

## Initial setup and later use

The local support installer verifies a pinned manifest and the exact target
before placing its own files. It does not replace boot/system images, start
Wi-Fi during installation, or install the app itself. My Apps is installed
through the separate authenticated app bootstrap. After that initial setup,
use **USB Wi-Fi → Save and connect**, then **App updates**. The network must be
WPA2-PSK with AES/CCMP; WPA3-only, enterprise and captive-portal login are outside
this connection manager's current scope. Reconnect after reboot using the saved
network button; automatic boot connection is not implemented.

The app source and TLS build recipe are public-source candidates. The OEM-specific
support bundle remains local to the analysis workspace and has not been published.
The ordinary Android Wi-Fi toggle does not manage this separate USB interface.

## Connection diagnostics

**App updates → Save connection diagnostics** saves bounded USB descriptor and
network evidence without serials, MACs, SSIDs or IP addresses. Unreadable fields
remain unknown. It does not configure a device or start DHCP. See the
[diagnostic scope](APP_UPDATES.md#connection-diagnostics-without-adb).

## 한국어

요청한 두 조합을 위한 드라이버 후보와 앱의 USB Wi-Fi 연결 화면을 준비하고
연결 관리자·설치/복구 도구의 로컬 통합 검증을 마쳤습니다. 드라이버 빌드 완료와 실제
동시 사용 성공은 구분합니다. 현재 차량 커널 전체가 없고 동글·허브·iPod의
실제 전원·인식·재생·무선 통신은 확인하지 않았습니다. 참고 소스에서 발견한
차단 우려만으로 커널 패치를 필수 설치로 넣지 않습니다.

앱 v13에는 비밀번호 SSH 터미널을 추가했습니다. 앱에서 비밀번호를 저장하고
터미널을 켜면 접속할 수 있으며, **15분 제한은 없습니다**. 직접 끄거나 Wi-Fi가
끊기면 종료됩니다. 첫 접속의 서버 지문을 앱과 대조하고 비밀번호를 입력합니다.
별도 접속 키 준비는 필요 없으며 초기 지원 도구 설치와 실차 확인은 남아 있습니다.
