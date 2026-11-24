# iAP2 transport candidate (GPL-3.0-only)

The new Java files in `com/veloster/carplay/` and the two transport/onboarding
tests in `../java-tests/com/veloster/carplay/` are an isolated GPL-3.0-only module. They are not merged into the public dashboard app.
The complete license is in [COPYING](COPYING).

Protocol/state-machine behavior was adapted from
[Jheenz/DiPlay-5.1, commit5776c38910824267a561160457270748ac76f6db](https://github.com/Jheenz/DiPlay-5.1/tree/5776c38910824267a561160457270748ac76f6db),
especially `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkEngine.kt`
`iap2/wire/Iap2CsmFramer.kt`, `Iap2IdentificationClient.kt`,
`Iap2WirelessControlClient.kt` and `iap2/message/Iap2Messages.kt`.
Original project contributors retain their
copyright. Modifications in this repository are dated2026-11-04.

Changes: Java7/API17-compatible language/library subset; explicit negotiated
session checks; exact outstanding-window and cumulative-ACK validation;
per-packet retry deadlines; bounded input/output/queues; a separate real
InputStream/OutputStream worker with owner-controlled socket closure; focused
wire-vector, failure, and loopback tests. This is a modified implementation and
not an endorsement or compatibility certification by upstream.

`Iap2Link` is single-thread-owned. Call `start(monotonicMillis)`, feed arbitrary
received fragments, advance `tick` using a monotonic clock, drain output to the
socket, and consume session events. Only control session10 is advertised.
`Iap2Csm` incrementally decodes control messages and ordered TLVs, preserving
repeated field IDs. It never formats payloads for logs.

`Iap2Stream` wraps already connected secure RFCOMM streams. Pass a `Closeable`
which closes the owning Bluetooth socket, so closing interrupts blocked I/O.
Use an explicit wrapper calling `socket.close()`; do not assume old Android
socket classes implement the same Closeable interfaces as a modern desktop JDK.
It owns one reader and one protocol/writer worker. `send` means the complete
CSM fragments have all reached the output stream and been flushed. It does
not mean every fragment has been ACKed or processed by the peer. A per-request
flush flag distinguishes completed sends from later peer EOF. Operation timeouts do not impose a lifetime limit on the session.
The caller must close after a failed `awaitReady` or when cancelling a session.

`Iap2Link.Mode.WIFI_TUNNEL` and `Iap2Stream.openTunnel` add the reference's
accessory-initiated type130 runtime handshake. The default Bluetooth constructor
is unchanged. Tunnel mode emits the marker and SYN immediately, advertises
65535-byte frames and zero ACK/retransmission parameters, and still requires
the peer to acknowledge our actual SYN sequence. Peer nonzero ACK/retry requests
remain effective; zero-ACK is not permission to skip authentication.
Only control session10 is advertised. EA/file-transfer remain unimplemented.
The [tunnel adapter](../tunnel/README.md) connects this link to real authenticated
DataStream reads and the same-session event writer, with scoped cancellation.
`Iap2RuntimeController` adds runtime identification, injected OEM certificate/challenge
exchange, the AA05 gate, and five subscriptions. Link readiness alone does not prove
those steps, media activity, or a successful selected-phone handoff.

The runtime implementation also follows `Iap2MfiAuthenticationClient.kt` and the
runtime tunnel orchestration in `CarPlayController.kt` at the same pinned commit.
It declares the implemented now-playing, route, power, communications and call-state
updates, plus wireless endpoint request/reply messages. It does not declare EA,
location, EV status or artwork file transfer. The RouteGuidance component uses
identifier42 and the reference's road-name/maneuver limits. Requested Wi-Fi/start
responses require current AP/receiver data; no Bluetooth teardown is performed on
the runtime link. After authentication, there is no overall idle lifetime limit.
Malformed, unexpected negotiation messages, revoked owners and changed AP/selected
phone observations close the owned session. The runtime worker is integrated by
`control/RuntimeControlHost`; actual OEM binding/AP adapters are in
`coordinator/RuntimeAdapters`. Hardware authentication and application integration
still require verification. Test certificate/signature data are explicit fixtures.

`Api17RfcommConnector.create()` uses `BluetoothAdapter.getDefaultAdapter()` and
secure outbound RFCOMM with the iAP2 service UUID. `RfcommConnector.connect`
requires the explicitly selected device to be bonded before AND after connecting.
It neither scans nor pairs, does not switch Bluetooth on, and closes its owned
socket on connection timeout or cancellation. The application needs manifest
`android.permission.BLUETOOTH`; it must offer a way to select an existing bond.

`Iap2WirelessController` owns one bootstrap session. It sends identification,
requests a certificate/signature through the injected `Authentication` provider,
waits for the phone's actual AA05 after a response has been flushed, and gates
Wi-Fi credentials on app consent plus the current owned AP session. There is no
fake authentication provider or receiver identity in production code. Test doubles
are explicitly marked and live only in test sources. Wrap `OemAuthClient` in
`Authentication` at the coordinator boundary; its real20byte challenge requirement
and checked errors must be preserved.

The `Environment` supplies private credentials, session liveness, real receiver
metadata, selected-phone transport identifiers and actual handoff observations.
It must not map `hostapd ENABLED`, native startup, a credential send or a transport
identifier notification to completed Wi-Fi/iAP2 handoff. For2.4GHz, handoff also
requires an actual disable-Bluetooth command or observed profile disconnection;
remaining profiles must be disconnected for that selected phone and verified.
The controller never globally toggles Bluetooth.

`StartMode.CSM_START_SESSION` advertises4300/4301 and sends4301 only with real
receiver port/device identifier/public key/source version. Missing metadata holds
that message while5703 can proceed. Explicit `StartMode.BONJOUR_CONTROL` excludes
4300/4301 from identification and never emits4301; it is a candidate for the
observed legacy210.81 engine with Bonjour/control. No automatic downgrade exists.
The retained DiPlay0.2.21 identification retry source permits omitting the pair
when an older iPhone rejects those messages, which supports investigating this
profile; it does not prove our engine/current iPhone accepts it.

Pairing/discovery UI, SDP/EIR advertising, root/AP/receiver integration, a verified
real MFi exchange, video/audio/location and actual phone reconnection remain
separate integration/validation work. `Result.wirelessCarPlayVerified` is always
false: HANDOFF denotes a verified transport migration, not observed decoded media.
Successful host/Android loopback tests do not prove iPhone/CarPlay compatibility.

Build and host verification (new output directory):

```sh
python3 tools/wireless-carplay/test_transport.py --out artifacts/iap2-transport-local
```

The build emits `transport-tests.jar` for an explicitly selected generic API17
emulator. It does not start ADB. DEX conversion alone is not runtime validation.
