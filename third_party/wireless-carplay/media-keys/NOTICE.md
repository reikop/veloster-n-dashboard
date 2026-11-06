# Consumer HID media key preparation

This independent device descriptor and sender are authored for this receiver. The
USB-IF [HID Usage Tables 1.21](https://www.usb.org/sites/default/files/hut1_21_0.pdf),
Consumer page 0x0C, define `0xB5` as Scan Next Track and `0xB6` as Scan Previous
Track, both One Shot Controls. Section 3.4.1 specifies that a relative
preferred-state OSC needs a 0-to-1 event followed by 1-to-0 reset before
another event. The USB-IF [HID 1.11 definition](https://www.usb.org/sites/default/files/hid1_11.pdf)
defines report descriptor items and byte-aligned reports. This device has one
Input report and no Report ID item: `01` is Next, `02` Previous, `00` release.

The separate `4d4b4559` HID UUID and one-byte report deliberately do not use
the receiver's touchscreen UUID or twelve-byte touch report. A metadata
instance explicitly constructed with `ConsumerHid` now includes a separate
`hidDevices` entry. `EncryptedEvents.ConsumerChannel` binds that exact
metadata declaration and `PairVerify.VerifiedSession`, validates reports,
uses the Consumer UUID in `hidSendReport`, and rechecks the provided `Current`
guard after acquiring the shared encrypted sender. `send` returns true only
after CSeq ACK, false only before any write, and throws on an unknown
write/ACK outcome. `AndroidReceiverSession.setHardwareConsumerSource` is an
explicit opt-in, and `IndependentSessionBackend` advertises this declaration
only for that configured attempt. The public Hub setter and concrete OEM
physical key provider remain unconnected.
The Consumer DOWN gate starts denied even though legacy headless audio output
starts enabled. It requires an explicit local media selection or an observed
CarPlay media choice, the same current mode owner, and observed remote PHONE
audio ownership without a call or Siri owner. A claimed UP retains its exact
session after a mode change.

`ConsumerKeySession` accepts one physical source object by identity. Callback
`down`/`up` only queue work; one tracked worker serializes press ACK before
release, including a release requested by `close`. It quarantines duplicate or
overlapping presses and keeps an unknown outcome marked for exact-session
retirement. The outer owner must retain/drain a blocked worker and must not
claim a successor source on this channel until `safeToReplace()` is true or the
old verified session has been fully retired. A stale source returns `STALE` and
must not be forwarded into a successor owner. The producer must latch a
`CLAIMED` DOWN so its matching UP is delivered to the same owner.

The USB specification establishes the descriptor's generic HID meaning. It
does not establish that an iPhone accepts this CarPlay `hidDevices` declaration
or encrypted `hidSendReport` event. The exact physical Play/Pause key route is
also unresolved. Host tests use the real encrypted PairVerify event sender
against a fixture peer; the optional product backend compiles and its session
wrapper runs in the encrypted host fixture. No OEM producer, public Hub,
vehicle, or phone integration is claimed.
