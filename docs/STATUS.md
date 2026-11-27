# Compatibility and verification / 호환성·확인 범위

[Documentation](README.md) · [한국어](#한국어)

Reviewed **2026-11-25**. The latest vehicle session was **2026-11-25** (v25 / My Apps v11 from the Android installer on a tablet). Releases are named **MAJOR.MINOR.PATCH**; both apps and every installer carry the same version (now **1.11.0**).

## Supported target

| Item | Target |
| --- | --- |
| Vehicle | Korean Veloster N JSN |
| Software shown by the vehicle | `JSN.KOR.SOP.V141.250902.STD_H` |
| Installer identity | `ro.product.name=jsn_kr` |
| Android | 4.2.2 / API 17 |
| Incremental build | `eng.vmadmin.20250902.081552` |
| Platform | Telechips `tcc893x`, factory `automotive` library |

The installer checks the live product, SDK and incremental build. A generic firmware `build.prop`, matching Android version, or a Gen5W label does not establish compatibility. Other models/regions/builds are unverified; this project does not install NEMESIS.

## Current release

| Artifact | Vehicle evidence | Still unverified |
| --- | --- | --- |
| N Dashboard 1.11.0 (versionCode 26) | v20–v24 installed on 2026-11-24: GPS taxi fare region (Daejeon), drive-mode values N / N CUSTOM / NORMAL (6 / 4 / 0) and their labels, alert-channel sounds, F1 tile change saved, Neon Drive page and exit, neutral marker position. v25 (N CUSTOM purple) installed from the Android installer on a tablet over OTG on 2026-11-25; the owner confirmed the purple N CUSTOM colour. 1.11.0 adds 설정 → 정보 → USB 디버깅 (the same OEM request as My Apps). 설정 → 글꼴 ([font manager](FONTS.md)) is released from 1.11.1; 1.11.4 changes the bundled fonts to ten (Asta Sans, Pretendard, NanumSquare Neo, Busan Dandi Gothic/Myeongjo/Script, LeeSeoyun, Nanum Pen/Brush, Poor Story). Host tests only; the owner confirmed amossu root. | 1.11.0 on the car, the USB debugging switch in the dashboard, the font manager (apply from the app, font after the reboot), floating window, race/lambda/tacho screens, learned downshift target, settings restore, SPORT/ECO values, DCT estimate |
| My Apps 1.11.0 (versionCode 12) | v10 installed 2026-10-27 with readback hash match; owner confirmed HOME edge-button reopening. v11 installed 2026-11-25 (tablet over OTG); the owner confirmed ☆ → dashboard | 1.11.0 on the car (version name only) |
| Vehicle signals | RPM, temperatures, TPMS, NAdd gear/pedal/brake/steering, drive mode | Active ABS/TCS/ESC transitions, exhaust temperature, e-LSD; JSN has no clutch signal |

1.13.0 takes the i30 PD fork's v1.17.8–v1.18.0 updates: shapes are kept and reused instead of rebuilt every frame (the fork measured its overview going from 40 ms to 16–22 ms a frame in the car), the engine sound goes back to the earlier synthesiser with six more four-cylinder voices, optional ducking for navigation prompts and calls, and optional louder-with-speed; and Neon Drive shows the track already playing as soon as it opens. Checked on hosts only (tests and builds); not checked in the car.

1.12.0 adds the 내 차 (My Car) screen: a 3D Veloster N whose lamps, front wheels, wheel roll and body pitch/roll follow the car's turn lamps, light switch, brake, reverse, steering, speed and G sensor. Checked on hosts and an Android 7.0 emulator only; frame rate on the head unit's GPU, the turn-lamp bits and the steering / lateral-G signs on the car are unverified.

Releases ship only the one-click installers (Windows, macOS, Linux, Android phone); each carries both APKs. Older release metadata (`release.json`, `SHA256SUMS`, `VALIDATION.json`, `SOURCE_SHA256SUMS`) describes the 2026-10 snapshots.

## Removed work

The wireless CarPlay receiver preview, USB Wi-Fi, app self-update, HTTPS module, SSH terminal and system-DNS previews, and the AVRCP Cover Art / SystemUI experiments were removed from this repository on 2026-11-24. None of them was ever in a released APK. Their history remains in git.

## 한국어

점검일 **2026-11-25**, 최근 실차 확인 **2026-11-25**(태블릿 OTG로 폰 설치 앱 설치, 계기판 v25·'내 앱' v11). 릴리스 버전은 **MAJOR.MINOR.PATCH** 하나로 통일했고, 두 앱과 모든 설치기가 같은 버전(현재 **1.11.0**)을 씁니다.

- 대상은 국내 벨로스터 N JSN의 `250902 / STD_H`, Android 4.2.2/API17, `tcc893x`입니다. 설치기는 실차의 `jsn_kr`, API17을 확인하고, 빌드가 `eng.vmadmin.20250902.081552`와 다르면 경고 후 설치합니다(1.11.5).
- **실차 확인:** GPS 택시 요금 지역(대전), 주행 모드 값 N/N CUSTOM/NORMAL(6/4/0)과 글자, 경고 채널 소리, F1 칸 바꾸기 저장, 네온 드라이브 화면과 나가기, 중립 위치. 11/25 태블릿(폰 설치 앱, OTG)으로 v25·v11 설치, ☆ 버튼 → 계기판과 N CUSTOM 보라색 동작 확인(차주). 1.11.0은 계기판 설정 → 정보에 '내 앱'의 USB 디버깅 ON/OFF를 넣었습니다. 설정 → 글꼴([글꼴 관리](FONTS.md))은 1.11.1부터 배포했고, 1.11.4에서 앱 포함 글꼴을 10종(Asta Sans·프리텐다드·나눔스퀘어 네오·부산단디고딕/명조/손글씨·이서윤체·나눔손글씨 펜/붓·서툰이야기)으로 바꿨습니다. 호스트 시험만 했습니다. amossu 루트는 차주가 확인했고 이 화면에서의 적용·재부팅 후 표시는 실차 미확인입니다.
- **미확인:** 1.11.0 실차 설치와 계기판의 USB 디버깅 버튼, 플로팅 창, 레이스·람다·타코 화면, 다운시프트 학습 목표, 백업 복원, SPORT/ECO 값, DCT 추정, ABS/TCS/ESC 실제 개입, 배기온·e-LSD. JSN에는 클러치 신호가 없습니다.
- 릴리스에는 한방 설치기(Windows·맥·리눅스·안드로이드 폰)만 올리며, 설치기마다 두 APK가 들어 있습니다.
- 무선 CarPlay·USB Wi-Fi·앱 자동 업데이트·HTTPS·SSH 터미널·시스템 DNS 미리보기와 앨범아트·SystemUI 실험은 2026-11-24에 저장소에서 삭제했습니다. 배포 APK에 들어간 적은 없습니다.
