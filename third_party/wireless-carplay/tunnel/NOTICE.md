# DataStream tunnel source and license

The Java/C sources and tests in this directory are an isolated **GPL-3.0-only**
module; the complete license is [COPYING](../java/COPYING). Production sources
are included in the public Hub wireless preview as of 2026-11-05; this does not
claim vehicle installation or phone interoperability.

2026-11-05 telephony continuation: `MicrophoneCaptureLease` separates logical
input reservations from one process-owned physical capture allocation.
`AudioInputStream` can stop/release and later recreate its capture source while
preserving the negotiated input socket, AEAD nonce and RTP counters. Relevant
observed-owner revisions fence capture before/after preparation, pacing and send;
one tracked stop worker cancels blocked reads. A successor cannot allocate until
actual stop/release completes; failed cleanup retains ownership and retries with
bounded delay. Input/output encryption and native code are unchanged. Real
paired UDP tests use explicit capture/owner fixtures; actual phone calls and
acoustic echo quality are unverified.

Wire behavior is adapted from Jheenz/DiPlay-5.1 commit
`5776c38910824267a561160457270748ac76f6db`:
`AirPlayCrypto.kt`, `IapTunnel.kt`, `AirPlayIapTunnelStream.kt`, and
`CarPlayMediaEngine.kt` under `shared/src/main/java/com/shilapi/xcertplay/airplay/`.
The 2026-11-05 `ScreenDecoder` wire profile follows `ScreenStream.kt` in that tree:
128-byte little-endian size header, clear VideoConfig, and header-authenticated
ChaCha20-Poly1305 video packets using a per-stream counter. This implementation
requires tagged frames, rejects partial/oversized packets, retires keys on EOF,
and leaves codec configuration parsing and compressed video decoding to the media layer.
The 2026-11-05 audio data endpoint follows `AudioStream.kt` and
`CarPlayMediaEngine.kt` at that same commit: fixed RTP header, timestamp/SSRC AAD,
tag16 plus transmitted nonce8, and the DataStream output HKDF label. PCM format
bits and big-endian sample conversion follow `AudioStreamCodec` and
`media/AndroidMediaSink.kt`. Independent Java7 implementation adds a 64-nonce
authenticated replay window, unsigned counters, exact session/interface/peer
ownership, bounded packet sizes and per-stream key erasure. These are data
endpoint/format primitives, not an implemented audio SETUP, RTCP, jitter buffer,
AAC decoder, AudioTrack output or microphone path. Unsupported format bits are
rejected rather than guessed as PCM. No SDK mirror headers or code are included.
The runtime adapter also follows `transport/Iap2LinkChannel.kt` and
`Iap2LinkEngine.kt` and `iap2/session/Iap2Session.kt` in the same pinned source tree.
Original contributors retain their copyright. Modified implementation dated
2026-11-04: Java7/API17, OpenSSL JNI, bounded incremental buffers, exact local
interface/peer binding, one TCP connection per key, owner/session revocation
contract, post-SETUP activation, terminal authentication failures, and bounded
event/receive queues. This does not certify upstream or actual phone compatibility.

Reference source:
https://github.com/Jheenz/DiPlay-5.1/tree/5776c38910824267a561160457270748ac76f6db

The ARM JNI library statically links **OpenSSL 3.5.9**, Apache License 2.0,
copyright the OpenSSL Project Authors and its contributors. The build copies the
full `LICENSE-OpenSSL.txt` and `COPYING-GPL-3.0` alongside the generated library.
Redistribution requires the corresponding sources, modifications, and license
notices. The retained TLS recipe, dependency source archive hash, static-library
hash, and headers are recorded in the build manifest. No license restrictions
of other tools are bypassed.

The host test uses the locally installed Homebrew OpenSSL static library; its
hash is recorded separately. It is not the ARM library. Only the explicit API17
runtime test verifies execution of the built ARM JNI library.

2026-11-05: optional negotiated controlPort audio loss repair uses bounded
RTCP-like D5 requests (8 bytes) and D6 replies (4-byte prefix plus the original
AEAD audio packet). Wire facts were cross-checked against the primary
Shairport Sync implementation, rtp_request_resend and RTP receiver:
https://github.com/mikebrady/shairport-sync/blob/master/rtp.c
The Java state machine is independently implemented. It requires authenticated
nonce-consistent gap observations, limits pending requests/retries/lifetime,
pins the negotiated peer endpoint, and keeps the existing replay window.
Sequence headers alone never authorize payload or advance a clock. This is
not RTCP clock synchronization, and late repairs may miss the playback window.

2026-11-05: selected-session microphone input adds the separate
DataStream-Input-Encryption-Key HKDF direction and PCM16 RTP sealing. Wire facts
were compared with MicrophonePacketizer.kt and CarPlayMediaEngine.kt at the
retained DiPlay revision5776c38910824267a561160457270748ac76f6db:
https://github.com/Jheenz/DiPlay-5.1/tree/5776c38910824267a561160457270748ac76f6db/shared/src/main/java/com/shilapi/xcertplay/airplay
This Java7/API17 implementation independently adds strict explicitly negotiated mono8k/16k Siri input,
nonce nonreuse, per-attempt input leases, response/RECORD/verified-output/media
admission gates, bounded pacing and cancellation. OpenSSL performs authenticated
encryption with the same existing dependency/license; no plaintext fallback or
SDK implementation is included. PCM/Opus phone interoperability and OEM voice
routing are separate claims; only the configured LPCM profile is implemented.
