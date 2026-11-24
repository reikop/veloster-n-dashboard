# Audio/video receiver

2026-11-05 telephony continuation: VOICE_COMMUNICATION is selected for explicitly
negotiated telephony input, while speechRecognition retains VOICE_RECOGNITION.
Only owned AudioRecord-session AEC/NS handles are queried/configured; unavailable
or failed processing is observable and is not an acoustic-quality claim. Failed
release retains handles for tracked cleanup. Independent main/alternate output
readiness and per-category local gains preserve alternate prompts while mainAudio
belongs to the accessory; focus uses scoped media/call/alternate roles. No global
mode, SCO, speaker routing, microphone mute or stream-volume setting is changed.
Reference behavior: pinned DiPlay 5776c389, MicrophoneUplink.kt and
AudioChannelMapping.kt. Resource behavior was cross-checked against Apple's
[Developing CarPlay Systems Part2](https://developer.apple.com/videos/play/wwdc2016/723/).
Enhanced Siri's always-on ECNR/history/detectors and Aux input/output architecture
is distinct and remains unadvertised. Actual phone and acoustic quality are
unverified; individual generic API17 capture/track tests have their own evidence.

Input declarations require the owned `MicrophoneCapabilityProbe` result. Each
published source6/source7 and mono8k/16k format requires actual initialization,
the requested source and initialized state, and release before publication.
This worker never starts recording; buffer-size queries are only a
prefilter. Probe cancellation and failed release retain the physical capture
lease until actual cleanup. A result stays scoped to its original live receiver
owner. Legacy ownerless capability methods return no positive input formats.

License: GPL-3.0-only, consistent with the isolated wireless receiver components.

The avcC/CSD and frame-conversion behavior was compared with DiPlay 5.1
commit `5776c38910824267a561160457270748ac76f6db`, particularly
`shared/src/main/java/com/shilapi/xcertplay/media/MediaCodecSupport.kt`,
`AndroidMediaSink.kt` and `airplay/ScreenStream.kt` (GPL-3.0).
This Java7/API17 implementation adds independent bounded parsing, worker ownership,
Surface replacement and session failure handling. It is not a port of newer
Android codec APIs. Modified/added 2026-11-05.

MediaCodec CSD/API contracts: https://developer.android.com/reference/android/media/MediaCodec
Test-only FFmpeg binaries remain in local artifacts and are not bundled with the receiver.

2026-11-05: PresentationPolicy and ModePresentation are independently written
Java7/API17 adapters for observed mode ownership, main-thread view visibility,
media-focus acquisition/release, duck/pause/gain, and exact-session output gates.
Android's legacy requestAudioFocus/abandonAudioFocus contracts were checked at:
https://developer.android.com/media/optimize/audio-focus
https://developer.android.com/reference/android/media/AudioManager
No audio decoder, global stream-volume override, OEM call routing or Apple SDK
implementation is included by this addition.

2026-11-05 audio addition: independently written AudioSink, Api17AudioOutput and
AudioStreamFactory use the same pinned DiPlay audio format/profile references.
The implementation uses API16/17 synchronous MediaCodec buffers, raw AAC-LC CSD,
PCM16 AudioTrack, bounded reorder/output queues, exact stream ownership and
per-track gain. It does not copy the newer AndroidMediaSink implementation.
Android API contracts were checked at:
https://developer.android.com/reference/android/media/MediaCodec

2026-11-05 audio feedback: AudioHead, AudioFeedback and physical-head accumulation
across output replacement are independently written. Protocol field meanings were
checked against 45clouds/WirelessCarPlay commit
`51145ef55f8dd9f1cbadd58353cacb5e0ca215e9`, AirPlayCommon.h and
AirPlayReceiverSession.c (feedback and output sample-clock observations), and
compared with the pinned DiPlay CarPlayMediaEngine.kt onFeedback implementation.
The primary reference uses `sr`; the DiPlay implementation uses `sampleRate`.
No Apple SDK implementation is copied or bundled. API17 getPlaybackHeadPosition
supplies an unsigned, wrapping physical sample counter. Query midpoint is an
approximate observation time, not a hardware speaker timestamp. Idle time and
discarded input do not create played samples. The initial feedback change used
only nominal rate. API contract:
https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition()

2026-11-05 rate estimation: independently written AudioRateEstimator fits actual
physical head observations against monotonic time in a bounded 8–32 second
window. It preserves every advancing observation to avoid periodic subsampling
aliasing, limits fit computation frequency, and rejects interruptions, underruns,
resets, stale/slow observations and implausible/noisy fits. The result can replace
nominal `sr` in feedback. Guard values are local receiver policy, not Apple limits
or a promise of hardware clock accuracy. This adds no sample resampler, actual
drift compensation or A/V synchronization. No SDK estimator code is copied.
https://developer.android.com/reference/android/media/AudioTrack

Independent FFmpeg-generated tones are local test fixtures; FFmpeg is not linked
or bundled into the receiver. No Apple SDK implementation is included.

2026-11-05 AAC startup and finite-tail changes are independently written.
The old platform's first-AU duplication was identified in AOSP's upstream fix:
https://android.googlesource.com/platform/frameworks/av/+/f3bd1972e039c6ded5154db715e5a32f1813a239
The local workaround primes with two generated zero AAC-LC stereo AUs and
removes only their synthetic PCM. The common ICS window_shape field uses sine;
its bit position was checked against FFmpeg n7.1 aacenc.c put_ics_info.
The test generator proves prefix equivalence with an independent decoder.
No AOSP/FFmpeg/FDK decoder implementation is copied or bundled by this change.
API17 AudioFlinger Track::isReady establishes the finite short-track filling
boundary; the output appends counted silence only after confirmed EOS:
https://android.googlesource.com/platform/frameworks/av/+/android-4.2.2_r1/services/audioflinger/AudioFlinger.cpp

2026-11-05 scheduled video: independently written VideoPresentation retains one
dequeued output buffer until its authenticated frame timestamp, mapped through
the fresh session clock, becomes due. API17 uses releaseOutputBuffer(index,
boolean); the worker polls without blocking socket callbacks. Configuration,
Surface, clock-generation and session changes retire old pending output.
Observer callbacks report decoder-buffer readiness and release, not hardware
display timestamps. This is not an audio clock or an A/V synchronization claim.
The timestamp flag/header interpretation is documented with pinned source
provenance in the clock-reference knowledge log; no Apple SDK implementation
is copied or bundled. The Android buffer-ownership contract is documented at:
https://developer.android.com/reference/android/media/MediaCodec

2026-11-05: Api17MicrophoneInput and MicrophoneStreamFactory add actual
AudioRecord VOICE_RECOGNITION capture for explicitly negotiated mono8k/16k LPCM Siri
input. MicrophoneUplink.kt at pinned DiPlay5776c389 was consulted for source,
frame and cancellation behavior. Android's AudioRecord API documentation was
cross-checked; API23-only recording/routing methods are not used. Capture runs
only on its owned worker after permission, authenticated setup and media gates;
no global AudioManager/SCO routing or synthetic capture fallback is added.

2026-11-05 peer-phase addition: AudioSyncPacket, AudioSyncAnchor and
AudioPhaseDiscipline are independently written; the optional sink/output path
maps RTP to the session NTP clock, gates initial playback and corrects phase
using actually consumed mapped frames. PT84/D4 wire offsets were checked against
the primary Shairport Sync RTP implementation, and the RTP/NTP association
against RFC3550 section6.4.1. No reference implementation code was copied.
Plain peer-bound UDP sync is not cryptographically authenticated. API17's mixer
counter is not a DAC/speaker timestamp; simulated residuals and actual mixer
observations are separate evidence. Scope, policy bounds, unsupported profiles
and provenance are recorded in AUDIO_PEER_PHASE.md.
https://github.com/mikebrady/shairport-sync/blob/master/rtp.c
https://www.rfc-editor.org/rfc/rfc3550.html#section-6.4.1
