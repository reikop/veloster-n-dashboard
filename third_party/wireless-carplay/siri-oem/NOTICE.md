# Optional vehicle Siri input

The Java sources in `com/` are original integration code under GPL-3.0-only.
They implement an explicit Binder lease and a route state machine; they do not
contain an OEM APK/JAR, proprietary SDK implementation, or Apple authentication
material. The public exporter includes only these sources and this notice.
The OEM producer, patch builder, fixture applications and locally derived OEM
candidate remain outside that public source export.

System behavior is informed by Apple's public presentation:
https://developer.apple.com/videos/play/wwdc2016/722/

The candidate OEM key mapping and original policy checks are separately pinned
in private analysis artifacts. A matching file hash and controlled runtime tests
do not establish actual vehicle installation, steering-wheel delivery, Apple
certification or acoustic response time.

The `requestSiri` event encoding is implemented in the existing control/input
modules; their notices continue to apply. The optional physical source uses the
same authenticated session channel and does not invent a separate Siri wire API.
