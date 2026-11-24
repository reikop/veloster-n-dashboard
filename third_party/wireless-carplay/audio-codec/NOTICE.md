# Optional AAC-ELD 16 kHz voice backend

The Java and JNI shim in this directory are project code under GPL-3.0-only.
The build contains **no FDK AAC source or binary**. At runtime the optional
backend opens Android's `/system/lib/libstagefright_soft_aacenc.so`. It checks
the exact executable mapping path, required symbols and local ABI sizes, then
opens AAC-ELD object type 39 at 16 kHz, mono, 480 samples per access unit. It
requires the exact AudioSpecificConfig `f8f03000` and complete frame input.
The system library's SHA-256 is **not pinned**. These checks prove only the
observed API/profile behavior in the tested generic Android 4.2.2 emulator;
the downloaded 260114 system codec and the vehicle system codec were not
available for comparison. A missing or incompatible library fails closed.

[Apple's public WWDC 2016 CarPlay audio presentation](https://developer.apple.com/videos/play/wwdc2016/722/)
requires compressed audio on wireless CarPlay and names AAC-LC for media and
Opus or AAC-ELD for other audio. It does not publish format bit values or the
voice packet layout. A third-party mirror of an apparent Apple SDK,
[`45clouds/WirelessCarPlay` commit `51145ef55f8dd9f1cbadd58353cacb5e0ca215e9`](https://github.com/45clouds/WirelessCarPlay/tree/51145ef55f8dd9f1cbadd58353cacb5e0ca215e9),
reports bit 25 (`0x02000000`) as AAC-ELD 48 kHz stereo and bit 26
(`0x04000000`) as AAC-ELD **16 kHz mono**, with 480 samples per access unit.
That mirror is not officially distributed Apple documentation and carries
its own access and redistribution restrictions. No mirror implementation
source is copied into this project. The separate CPC200 capture had described
bit 26 as stereo 48 kHz; the versioned SDK mirror corrects that interpretation
for this implementation, but is not proof of the target OEM's negotiated mask.

The raw access units and authenticated RTP path were exercised on a generic
API 17 emulator. Its `-no-audio` launch means this is an Android AudioRecord
API path test with emulator input, not evidence of physical microphone audio.
The selected phone, PairVerify and wireless peer were fixtures. Target OEM
codec availability, vehicle sound, iPhone negotiation and end-to-end wireless
CarPlay remain unverified. The optional profile must be advertised only after
owner-scoped input and separate output capability probes succeed; existing PCM
advertisements remain the default.

`Eld16kInputCapability` holds no codec handle. Its probe closes the temporary
encoder, checks the current microphone owner, and must be repeated for each
new attempt. Each capture phase opens and validates a fresh encoder instance.
