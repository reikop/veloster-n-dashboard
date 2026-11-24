# Touch and Siri input source and protocol evidence

This is an isolated **GPL-3.0-only** component, linked to the existing control
module. License: [COPYING](../java/COPYING). Original DiPlay contributors retain
their copyright. Production sources are included in the public Hub wireless
preview; vehicle and actual phone interoperability remain unverified.

The two-finger descriptor/report shape, virtual HID UUID `2a2a2a2a`, and
`hidSendReport` event dictionary derive from the retained Jheenz/DiPlay-5.1 source,
commit `5776c38910824267a561160457270748ac76f6db`:

- [AirPlayHid.kt](https://github.com/Jheenz/DiPlay-5.1/blob/5776c38910824267a561160457270748ac76f6db/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayHid.kt)
- [AirPlaySession.kt](https://github.com/Jheenz/DiPlay-5.1/blob/5776c38910824267a561160457270748ac76f6db/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt)
- [AirPlayInfoPlist.kt](https://github.com/Jheenz/DiPlay-5.1/blob/5776c38910824267a561160457270748ac76f6db/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt)
- `AirPlayConfig.kt` at the same revision supplies the touchscreen primary-input
  value. Display high-fidelity-touch flag `0x08` is declared only when this input
  implementation is explicitly supplied; knob/media/telephony devices are not declared.

2026-11-05 changes: Java7/API17 implementation; shared immutable declaration;
exact receiver/session ownership; 0..width-1 and 0..height-1 limits; explicit
transducer logical range0..1 for each finger; stable Android pointer-ID slots;
content-rectangle/rotation mapping; two-contact isolation; bounded transition
queue with MOVE coalescing; per-view-generation cancellation; release retained
across view invalidation; shared authenticated encrypted event serialization;
actual API17 MotionEvent and View adapter. The virtual declaration does not copy
the reference's arbitrary vendor/product IDs or claim a physical USB manufacturer.

The digitizer usages (Touch Screen, Finger, Touch and Transducer Index) and generic
desktop X/Y were cross-checked against the [USB-IF HID Usage Tables1.5](https://usb.org/sites/default/files/hut1_5.pdf).
The document is referenced, not copied/bundled. Android pointer-ID/index semantics
were checked against [Android's multi-touch guide](https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi).

Public implementation compatibility and HID structure checks do not prove Apple
specification conformance, certification or interoperability with an actual phone.
No Apple proprietary SDK implementation/header/binary is copied in this component.

2026-11-05 Siri button integration: `AirPlaySession.kt:360–363` at the pinned
DiPlay revision above supplies the `requestSiri` event with nested `siriAction`
button-down2 and button-up3. Java7/API17 changes add exact-session capability
ownership, a bounded nonblocking UI producer, tracked sender workers, same-session
release after pause/focus loss, and rejection of stale queued input. It does not
map Hyundai hardware keys, implement a Siri dismiss command, or start microphone
capture on a button request. Phone-negotiated speech input remains independently
gated by SETUP, RECORD, authenticated media, and session admission. Compatibility
fixtures exercise the real encrypted event connection, not an actual iPhone.
