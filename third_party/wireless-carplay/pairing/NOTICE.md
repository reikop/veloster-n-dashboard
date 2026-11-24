# Pairing source and license

The Java/C implementation and test tools in this directory are an isolated
**GPL-3.0-only** module; see [COPYING](../java/COPYING). Production sources are
included in the public Hub wireless preview; vehicle and phone
interoperability remain unverified.

2026-11-05 telephony continuation: at most one logical input per
speechRecognition/telephony category, paired to an existing output salt, with a
shared physical capture lease. Pausing does not reopen or reset an input key.
Used salts remain non-reusable, exact verified-session ownership remains checked,
and pending cleanup cannot be stolen by a successor. This does not add a codec,
new wire field or proprietary SDK implementation.

PairVerify, identity/store, TLV8 and control wire behavior are adapted from
Jheenz/DiPlay-5.1 commit `5776c38910824267a561160457270748ac76f6db`, files
`PairVerify.kt`, `PairingStore.kt`, `Tlv8Codec.kt`, `AirPlayCrypto.kt`,
`AirPlayIdentity.kt`, and `ControlCipher.kt` under
`shared/src/main/java/com/shilapi/xcertplay/airplay/`.
`PairSetup.kt` and `Srp6a.kt` also inform the implemented enrollment wire behavior.
Original contributors retain their copyright.

Reference: https://github.com/Jheenz/DiPlay-5.1/tree/5776c38910824267a561160457270748ac76f6db

Modifications dated 2026-11-04: Java7/API17 and OpenSSL JNI, strict singleton TLV
validation, one handshake per connection, final-response flush gate, private
durable identity/controller store, explicit enrollment boundary, selected-phone
and owner/revision checks, control-record limits, and one verified tunnel per
session. Enrollment uses OpenSSL BN for the fixed RFC5054 3072-bit group with
SHA512, constant-time modular exponentiation for secret exponents, RAND-generated
salt/private exponent, constant-time proof comparison, M5 signature validation,
local approval before durable registration, and response-flush state gates.
The fixed setup code is public and does not authenticate the selected phone.
The actual approval UI and peer/phone correlation still need integration.
These changes do not certify actual phone interoperability.

2026-11-05: stream shutdown delivers the original failure to the nonblocking sink
before retiring its parent, preserving the cause against parent-driven cleanup.
Parent retirement is guaranteed in finally, including callback failure.

The shared JNI library links OpenSSL 3.5.9 under Apache License 2.0; copyright
the OpenSSL Project Authors and contributors. The build copies its complete
license and GPLv3 alongside the library and records dependency/source hashes.
Redistribution requires corresponding sources, modifications and notices; see
also the [tunnel notice](../tunnel/NOTICE.md).

Test vectors: [RFC8032 §7.1](https://www.rfc-editor.org/rfc/rfc8032.html#section-7.1)
and [RFC7748 §6.1](https://www.rfc-editor.org/rfc/rfc7748.html#section-6.1).
The independent peer uses the locally installed Python cryptography package;
it is a host test dependency, not bundled vehicle code.
