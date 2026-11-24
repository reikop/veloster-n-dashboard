# Compatibility and verification / 호환성·확인 범위

[Documentation](README.md) · [한국어](#한국어)

Reviewed **2026-11-24**. The latest vehicle session was **2026-11-24** (N Dashboard v20–v24 on the owner's 6MT car).

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
| N Dashboard v25 (`1.24-ncustom-purple`) | v20–v24 installed on 2026-11-24: GPS taxi fare region (Daejeon), drive-mode values N / N CUSTOM / NORMAL (6 / 4 / 0) and their labels, alert-channel sounds, F1 tile change saved, Neon Drive page and exit, neutral marker position. v25 only changes the N CUSTOM mode colour to purple. | v25 itself, floating window, race/lambda/tacho screens, learned downshift targets, backup restore, SPORT/ECO values, DCT guess |
| My Apps v11 (`1.10-star`) | v10 installed 2026-10-27 with readback hash match; owner confirmed HOME edge-button reopening | v11's ☆ (user button) → dashboard switch: the JSN framework has the ModeManager custom-key API, but delivery of the button broadcast is untested |
| Vehicle signals | RPM, temperatures, TPMS, NAdd gear/pedal/brake/steering, drive mode | Active ABS/TCS/ESC transitions, exhaust temperature, e-LSD; JSN has no clutch signal |

Releases ship only the one-click installers (Windows, macOS, Linux, Android phone); each carries both APKs. Older release metadata (`release.json`, `SHA256SUMS`, `VALIDATION.json`, `SOURCE_SHA256SUMS`) describes the 2026-10 snapshots.

## Removed work

The wireless CarPlay receiver preview, USB Wi-Fi, app self-update, HTTPS module, SSH terminal and system-DNS previews, and the AVRCP Cover Art / SystemUI experiments were removed from this repository on 2026-11-24. None of them was ever in a released APK. Their history remains in git.

## 한국어

점검일 **2026-11-24**, 최근 실차 확인 **2026-11-24**(차주 6MT, N 계기판 v20–v24).

- 대상은 국내 벨로스터 N JSN의 `250902 / STD_H`, Android 4.2.2/API17, `tcc893x`입니다. 설치기는 실차의 `jsn_kr`, API17, `eng.vmadmin.20250902.081552`를 대조합니다.
- **실차 확인:** GPS 택시 요금 지역(대전), 주행 모드 값 N/N CUSTOM/NORMAL(6/4/0)과 글자, 경고 채널 소리, F1 칸 바꾸기 저장, 네온 드라이브 화면과 나가기, 중립 위치. v25는 N CUSTOM 모드 색만 보라로 바꿨습니다.
- **미확인:** v25 자체, 플로팅 창, 레이스·람다·타코 화면, 다운시프트 학습 목표, 백업 복원, SPORT/ECO 값, DCT 추정, '내 앱' v11의 ☆ 버튼(펌웨어에 API는 있으나 버튼 전달은 미시험), ABS/TCS/ESC 실제 개입, 배기온·e-LSD. JSN에는 클러치 신호가 없습니다.
- 릴리스에는 한방 설치기(Windows·맥·리눅스·안드로이드 폰)만 올리며, 설치기마다 두 APK가 들어 있습니다.
- 무선 CarPlay·USB Wi-Fi·앱 자동 업데이트·HTTPS·SSH 터미널·시스템 DNS 미리보기와 앨범아트·SystemUI 실험은 2026-11-24에 저장소에서 삭제했습니다. 배포 APK에 들어간 적은 없습니다.
