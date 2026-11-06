# Optional OEM alternate audio route

Original Java sources here are GPL-3.0-only. They are an offline candidate for a pinned
250902 Gen5 framework. They do not claim that generic Android `STREAM_MUSIC` mixes FM.

`OemAlternateAudioFactory` selects Android `AudioTrack` stream 11 for authenticated
CarPlay type-101 audio only after an OEM route gateway grants MICOM stream 19. A
gateway must implement exact caller/session authorization and an atomic route owner
under the original `MicomAudioService.mSettingsLock`. `OemAlternateRouteCore` models
that owner and requires before-start/end hooks for every OEM stream-19 call; do not
attach it to an uninstrumented service. Stream 11 and stream 19 belong to different
APIs. A physical FM/CarPlay mixing result requires vehicle validation and is outside
this offline candidate.
