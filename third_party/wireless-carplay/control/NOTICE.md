# Control source and license

2026-11-05 telephony continuation: typed type100 mono8/16k PCM telephony
declarations share the existing authenticated SETUP/RECORD and DataStream input
path. Two bounded logical voice reservations use one physical capture lease;
preflight busy-state rejection does not terminate a healthy session. Capture
permission follows observed mainAudio/PhoneCall/Speech ownership with independent
owner revisions, not a request acknowledgement. No Enhanced Siri, auxiliary
streams, unknown codec, global Android audio route or new wire field is advertised.
The public Apple WWDC16/723 resource model and WWDC19/252/WWDC23/10150 distinction
between basic and Enhanced Siri were consulted; source findings and source hashes
are retained in the private telephony implementation evidence.

This Java implementation and its test tools are an isolated **GPL-3.0-only** module;
the complete license is [COPYING](../java/COPYING). Production sources are
included in the public Hub wireless preview as of 2026-11-05. Vehicle and
actual phone interoperability remain unverified.

Control wire behavior follows the retained Jheenz/DiPlay-5.1 commit
`5776c38910824267a561160457270748ac76f6db`, specifically `RtspMessage.kt`,
`AirPlaySession.kt`, `ControlCipher.kt`, `BplistCodec.kt`, `CarPlayMediaEngine.kt`,
`AirPlayIapTunnelStream.kt` and `NtpClock.kt` under
`shared/src/main/java/com/shilapi/xcertplay/airplay/`.
Original contributors retain their copyright.
Reference: https://github.com/Jheenz/DiPlay-5.1/tree/5776c38910824267a561160457270748ac76f6db

2026-11-05: typed Siri button-down2/button-up3 commands follow
`AirPlaySession.kt:360–363` at the revision above. The exact-session event
capability shares existing encryption, sender serialization, CSeq/ACK checks,
and deadlines. A matching ACK must finish processing before its fixed request
deadline; completion retires that deadline immediately, so a descheduled caller
cannot turn a timely ACK into a timeout. Late ACKs and owner revocation still
terminate the channel. Buffered plaintext parsing checks local channel closure;
raw record reads/authentication and the parsed-message dispatch boundary retain
the full session-owner checks. This avoids repeating the complete admission
chain for every header byte without admitting a revoked message to a callback.
Optional tracked session-control workers are independent of
touch HID declarations. Button-up is not a Siri dismiss or microphone teardown
command. No proprietary SDK implementation is copied; real phone behavior is
unverified. Fixed source hashes and raw-byte line references are retained in the
private analysis artifact `siri-next-plan/provenance.json`.

Modifications dated 2026-11-04: Java7/API17 bounded framing, strict headers and
lengths, exact pairing routes, socket/context ownership, single connection and
admission limits, explicit response-flush encryption transition, cancellation,
watchdog deadlines, asynchronous local approval capability, bounded binary plist
with lossless uint64, explicit UTF-16 validation for API17, real base/type130 SETUP,
session-owned encrypted event commands with matching response acknowledgement,
separate event keys/counters, UDP timing with peer/originate filtering, and runtime
iAP2 transport handoff. RuntimeControlHost now owns the post-SETUP worker and
binds its controller/resources to the exact verified session. Runtime protocol
attribution is documented in ../java/TRANSPORT-NOTICE.md. Actual receiver metadata,
media, hardware authentication verification, low-power keepalive and Android UI
still require integration.

The plist format was cross-checked against Apple's CFBinaryPList.c and Python
plistlib; those implementations are not copied into this module. Python plistlib
generates independent test fixtures and reads Java-encoded output.

The parser implements a restricted control profile informed by
[RFC9112](https://www.rfc-editor.org/rfc/rfc9112.html) message framing and
[RFC2326](https://www.rfc-editor.org/rfc/rfc2326.html) RTSP CSeq/response behavior.
It is not a claim of full HTTP/RTSP conformance or actual phone compatibility.
Cryptography uses the existing [pairing JNI](../pairing/NOTICE.md) and its
OpenSSL dependency. Redistribution requires corresponding source and notices.

2026-11-05: optional actual screen sink factory, validated batched/incremental
130/110/111 SETUP, response-flush activation, session-owned screen receivers,
shared output-key salt uniqueness and whole-session shutdown on screen failure.
The byte-checking test sinks are fixtures and do not render video.

2026-11-05: session-bound main/alternate forceKeyFrame commands, validated display
UUID, bounded coalescing recovery worker, cancellation before send, and shared
iAP/event CSeq and acknowledgement serialization. Video integration lives in
../media; receiver metadata and actual phone compatibility remain unverified.

2026-11-05: ReceiverMetadata shares persisted identity, display UUIDs and a live
listener endpoint across binary-plist info, DNS-SD TXT data and iAP metadata.
AirPlayInfoPlist.kt and network/CarPlayBonjour.kt at the above DiPlay revision
inform field layout and structural SETUP tokens. This adds strict input bounds,
single-listener/store binding and lifecycle rejection. It does not add mDNS
publication, audio/HID/resource modes or claim complete phone compatibility.
Primary protocol research used to cross-check data types and feature bits:
https://openairplay.github.io/airplay-spec/audio/rtsp_requests/get_info.html
https://openairplay.github.io/airplay-spec/features.html
https://github.com/lvalen91/carlink_linux/blob/main/docs/CARPLAY_CAPABILITIES.md
These are unofficial protocol research, not Apple certification.

2026-11-05: ModeControl, ModeState and ModeChanges add per-connection observed
ownership, atomic partial updates, typed outbound changes, ordered reply handling,
default host-UI callbacks and lifecycle rejection. DiPlay Legacy Android commit
`31b90f420a65ef34a3e20809762950612b1357da` (GPLv3), AirPlaySession.kt and
AirPlayInfoPlist.kt, were compared with the above DiPlay version. Its permissive
malformed-command acknowledgement and changeModes/modesChanged conflation were
not adopted.

Wire field names, directions and numeric constants were cross-checked against
AirPlayCommon.h and the mode dictionary reader in AirPlayReceiverSession.c at
https://github.com/45clouds/WirelessCarPlay/tree/51145ef55f8dd9f1cbadd58353cacb5e0ca215e9/source/Sources
Those files carry Apple MFi-specific headers, despite that repository's root MIT
license. No SDK implementation, header or binary is copied or bundled here.
The Java validation/state/lifecycle/transport implementation is independently
written. Public source availability is not certification or proof of compatibility
with a current phone. Actual UI/audio-focus integration remains separate work.

2026-11-05: AudioCapabilities adds immutable output-only format declarations,
shared info/TXT audio feature bits and exact SETUP factory validation. Field
layout was compared with AirPlayInfoPlist.kt at the pinned DiPlay revision;
Audio (9), PCM (18) and AAC-LC (20) bit numbers were cross-checked against
AirPlayCommon.h at the WirelessCarPlay revision above. The broader reference
voice, microphone, Opus and unmeasured zero-latency claims are not adopted.
No SDK implementation/header is copied. Configuration validation is not a
hardware codec probe, mDNS publication or proof of phone interoperability.

2026-11-05: declared audio RECORD validates session state and optional RTP-Info,
commits permission only after its control response, and passes it through the
verified session to the output worker. DiPlay's RECORD callback and permissive
default response were compared; the latter is not adopted for unsupported FLUSH.
Wire/state facts were cross-checked against AirPlayReceiverServer.c and
AirPlayReceiverSession.c at the WirelessCarPlay revision above, and the
OpenAirPlay RECORD/FLUSH research pages. No SDK implementation is copied.
Initial RTP metadata does not establish sync. Prepared single-main FLUSH and its
last-played packet timestamp response were subsequently connected and tested;
FLUSHBUFFERED, full reconfiguration and network A/V synchronization remain open.

2026-11-05: clock references preserve NTP64 bits, project bounded deadlines onto
the monotonic clock, expire stale measurements and track clock-step generations.
Timing responses with impossible remote processing intervals are rejected.
Screen configuration headers can now reach opted-in sinks through the exact
verified session; they remain cleartext metadata, not authenticated frame data.
RespectTimestamps (configuration header byte6 bit1) and frame NTP field at byte8
were cross-checked against AirPlayCommon.h and AirPlayReceiverSessionScreen.c at
the pinned WirelessCarPlay revision above. No SDK code is copied. These clock
and transport changes do not by themselves implement scheduled Surface rendering.

2026-11-05: optional Siri microphone input declaration is validated against its
actual factory and exact shared output profile. Authenticated SETUP dataPort and
framesPerPacket select only type100/speechRecognition/mono8k-or16k PCM; encrypted input
is paired with the output's never-reused salt under the separate input key label.
SETUP/RECORD response acknowledgement and verified downlink precede capture;
partial teardown and session revocation cancel both directions. The pinned
DiPlay MicrophonePacketizer.kt and CarPlayMediaEngine.kt informed wire facts;
OEM call routing, Opus input and microphone acoustics are not implied.
