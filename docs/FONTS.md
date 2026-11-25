# 글꼴 관리 / Font manager

**N 계기판 → 설정(톱니) → 글꼴**에서 내비 전체의 기본 글꼴(순정 화면·내비·대부분의 앱)을 바꿉니다.

## 앱에 포함된 글꼴

글꼴 10종을 앱에 넣었습니다(파일 합계 약 53 MB, APK 안에서는 약 17 MB). 모두 현대 한글 11,172자를 모두 담고 있고 파일은 고치지 않았습니다(부산단디체·이서윤체는 파일 이름만 영문으로 바꿈).

| 글꼴 | 종류 | 굵기 | 크기 | 만든 곳 · 라이선스 |
| --- | --- | --- | --- | --- |
| Asta Sans (Pleos · 42dot) | 고딕 | Regular·Bold | 4.9 MB | 42dot(현대자동차그룹) · OFL |
| 프리텐다드 (Pretendard) | 고딕 | Regular·Bold | 5.1 MB | 길형진 · OFL |
| 나눔스퀘어 네오 | 고딕 | Regular·Bold | 4.2 MB | 네이버 · OFL |
| 부산단디고딕 | 고딕 | Medium·Bold | 5.2 MB | 부산광역시 · 부산시 전용서체 조건 |
| 부산단디명조 | 명조 | Medium·Bold | 13.4 MB | 부산광역시 · 부산시 전용서체 조건 |
| 나눔손글씨 펜 | 손글씨 | Regular | 3.1 MB | 네이버 · OFL |
| 나눔손글씨 붓 | 손글씨 | Regular | 3.4 MB | 네이버 · OFL |
| 서툰이야기 (Poor Story) | 손글씨 | Regular | 3.8 MB | 윤디자인 · OFL |
| 부산단디손글씨 | 손글씨 | Regular | 8.8 MB | 부산광역시 · 부산시 전용서체 조건 |
| 이서윤체 | 손글씨 | Regular | 1.3 MB | 흥국생명 · 흥국생명 서체 조건 |

부산단디체는 "영상, 인쇄, 출판, 웹, 모바일 등 … 무료로 자유롭게 사용"할 수 있고 판매나 배포 대가 청구는 금지됩니다. 이서윤체는 무료·상업 사용이 가능하고 판매와 수정 후 재배포가 금지됩니다. 두 조건 모두 앱에 함께 넣는 무료 배포를 명시적으로 다루지는 않습니다. 원문은 `apps/dashboard/assets/fonts/LICENSE-*.txt`에 있습니다.

목록에서 고르면 APK에서 바로 읽어 미리보기를 그립니다(한 번에 하나만 메모리에 둠). 적용할 때만 앱 저장소에 잠시 꺼내 SHA-256을 확인한 뒤 `/system/fonts/VN-*`로 복사하고, 꺼낸 파일은 지웁니다.

Asta Sans는 현대자동차그룹 계열 42dot이 만든 글꼴로, Pleos Connect 디자인 가이드의 타이포그래피가 지정한 서체입니다. 2026-10-28에 수동으로 넣은 `AstaSans-*.ttf`가 내비에 있으면 다시 복사하지 않고 그 파일을 씁니다. 현대자동차의 현대 산스(Hyundai Sans)·기아의 Kia Signature는 공개 배포 글꼴이 아니어서 넣지 않았습니다. 현대그룹(현대엘리베이터 등, 현대자동차와 별개)의 네오현대는 무료지만 사용 조건에 재배포 금지가 있어 앱에 넣을 수 없습니다.

## 사용법

1. 목록에는 순정 글꼴, 앱에 포함된 글꼴, 내비 `/system/fonts`에 이미 설치된 추가 글꼴만 나옵니다. USB나 내비 저장소의 글꼴 파일은 목록에 넣지 않습니다.
2. 줄마다 출처(앱에 포함·내비에 설치됨), 굵기(R·B·L), 한글 범위(한글 전체 = 현대 한글 11,172자)를 표시합니다.
3. 줄을 누르면 오른쪽에 그 글꼴로 미리보기를 그립니다. 내비 자체의 글꼴 엔진으로 그리므로 미리보기가 열리지 않는 글꼴은 적용할 수 없습니다.
4. **선택한 글꼴 적용** → 확인 → **지금 재부팅**. Android 4.2는 시작할 때만 글꼴 설정을 읽으므로 재부팅 후에 바뀝니다. 재부팅은 '내 앱' → 강제 재시작과 같은 Android 재부팅 요청이며, 나중에 '내 앱'에서 재시작해도 됩니다.
5. 처음 설정으로 돌아가려면 목록 맨 위 **순정 글꼴 (Mobis · Roboto)** 을 적용합니다.

## 하는 일

- 순정 루트 도구 `/system/bin/amossu`(차주 확인 2026-11-25)로 `/system`을 잠시 쓰기 가능하게 하고, 글꼴 파일을 `/system/fonts/VN-<이름>-Regular.ttf` 등으로 복사한 뒤 `/system/etc/system_fonts.xml`을 바꾸고 다시 읽기 전용으로 되돌립니다.
- 바꾸는 계열: `devicedefault`, `sans-serif`, `Droid Sans`, `sans-serif-light/-thin/-condensed`. `serif`, `monospace`, 대체 글꼴(fallback) 목록은 그대로 둡니다. 한글이 모자란 글꼴은 빠진 글자가 기존 대체 글꼴로 보입니다.
- 파일을 모두 복사하고 확인한 다음 XML을 마지막에 바꾸므로 중간에 실패하면 기존 글꼴이 유지됩니다. 적용 후 XML과 복사한 파일(SHA-256)을 다시 읽어 대조합니다.
- 이 앱이 복사한 `VN-*` 파일 중 더 이상 쓰지 않는 것만 지웁니다. 순정 파일이나 다른 방법으로 넣은 글꼴은 지우지 않습니다. 지금 화면에서 쓰는 파일은 재부팅 전까지 남깁니다.
- 처음 바꾸기 전의 XML은 `/data/local/tmp/vn-fonts/original-system_fonts.xml`에, 직전 XML은 `previous-system_fonts.xml`에 보관합니다. 화면이 정상이 아니면 PC에서 `adb shell /system/bin/amossu /data/local/tmp/vn-fonts/restore.sh` 실행 후 재부팅하면 처음 상태로 돌아갑니다.

## 한계

- `/system` 남은 공간 안에서만 복사합니다(파일당 40 MB 이하). 글꼴 모음(`.ttc`)은 지원하지 않습니다. 가변 글꼴은 기본 굵기 하나만 쓰입니다.
- 자체 글꼴을 내장한 앱(이 계기판의 플로팅 창 등)은 바뀌지 않습니다.
- 실차 확인 범위는 [STATUS.md](STATUS.md)를 따릅니다. 2026-11-25 기준 `amossu` 루트는 차주가 확인했고, 이 화면에서의 적용·재부팅 후 표시는 실차 미확인입니다(같은 방식의 수동 Asta Sans 적용은 2026-10-28 확인).

---

**N Dashboard → Settings (gear) → Fonts** changes the head unit's system font (factory screens, navigation and most apps).

Ten fonts ship inside the app (unmodified, all 11,172 Hangul syllables, about 53 MB of files): Asta Sans (42dot, Hyundai Motor Group; the Pleos Connect typeface), Pretendard, NanumSquare Neo, Nanum Pen Script, Nanum Brush Script and Poor Story under OFL 1.1; Busan Dandi Gothic, Myeongjo and Script under the Busan city font terms (free use in any medium, no fee for distribution); LeeSeoyun under the Heungkuk Life terms (free use, no sale, no modified redistribution). The non-OFL terms do not expressly cover bundling in an app; they are quoted in assets/fonts/LICENSE-*.txt. Hyundai Sans and Kia Signature are not public fonts, and Hyundai Group's NeoHyundai forbids redistribution, so none of them is included. The list shows only the factory set, the in-app fonts and extra fonts already in /system/fonts; font files on a USB stick or the head unit storage are not offered. Pick a row to preview it with the head unit's own font engine, then **Apply selected font** → **Reboot now** (the same Android reboot request as My Apps → Restart head unit); Android 4.2 reads the font settings only at start-up. **Factory fonts (Mobis · Roboto)** at the top puts the original mapping back.

The change runs as root through the factory `/system/bin/amossu`: the files are copied to `/system/fonts/VN-*`, checked, and `/system/etc/system_fonts.xml` is swapped last (devicedefault, sans-serif, Droid Sans, sans-serif-light/-thin/-condensed; serif, monospace and the fallback chain stay). Both are read back and compared after the change. The XML from before the first change is kept in `/data/local/tmp/vn-fonts/`; `adb shell /system/bin/amossu /data/local/tmp/vn-fonts/restore.sh` and a reboot put it back. Only unused `VN-*` files are ever removed. Font collections (`.ttc`) are not supported; files over 40 MB are skipped. As of 2026-11-25 the owner confirmed amossu gives root; applying from this screen and the font after the reboot are unverified on the vehicle.
