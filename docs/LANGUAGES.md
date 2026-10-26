# Korean and English UI

My Apps **v10** and N Dashboard **v6** support Korean and English. The default is **System**: Korean when the head-unit system language is Korean, English for all other system languages. This changes only our apps' display text; it does not change the vehicle/system language, numeric formatting, data decoding, units, or stored gauge/graph layouts.

- **My Apps:** open **Language / 언어** in the top bar. Select System, 한국어, or English, then Save. An enabled edge button and notification refresh in the selected language.
- **N Dashboard:** tap **KO** or **EN** in the top bar, just left of the clock, and choose a language.
- Each app saves its own preference. Setting English in My Apps does not overwrite a language explicitly chosen in N Dashboard.
- Android launcher/app-list names use system-language resources: **My Apps / 내 앱**, **N Dashboard / N 계기판**. An in-app override does not rename the icon in another app's launcher.
- Media titles/artists, Bluetooth device names, other installed apps' labels and OEM screens are not translated. Diagnostic JSON keys and technical units remain stable.

The ADB/engineering-mode entry and installation command are unchanged:

```sh
python3 scripts/install.py --serial YOUR_DEVICE_SERIAL --parked
```

Use the new complete `v2026.10.26-i18n` ZIP. The installer selects v6/v10, preserves existing APK backups and performs an in-place `-r` update; it does not uninstall the old apps or clear their settings. Exact target and known installed-file checks remain in force. Open My Apps and verify Quick entry on HOME before disconnecting; a language update does not establish the still-pending vehicle/reboot test.

**Checks:** both API17 APKs build against API16, V1 signatures and packaged English/Korean labels are verified, 11 Java test programs and 43 Python tests pass. The service harness covers 16 scenarios including saved language, corrupt preference fallback, live overlay refresh and persisted error translation. Vehicle data/subscription/layout methods (137) are instruction-identical to v5; language choice leaves `Locale.getDefault()` untouched. These are offline/host checks, not emulator or vehicle execution.

## 한국어

‘내 앱’ **v10**, ‘N 계기판’ **v6**에서 한국어·영어를 지원합니다. 기본값은 **System / 시스템**이며 내비 시스템 언어가 한국어이면 한국어, 그 외에는 영어로 표시합니다.

- **내 앱:** 상단 **Language / 언어** → 시스템·한국어·English 선택 → 저장.
- **N 계기판:** 시계 왼쪽 상단 **KO / EN** 버튼에서 선택.
- 언어는 앱별로 따로 저장합니다. 차량 전체 언어나 다른 앱 설정을 변경하지 않습니다.
- 메뉴·차량 항목·게이지/그래프 편집·오류 안내·바로가기·알림을 번역했습니다. 음악 제목, 기기 이름, 순정 설정 화면은 그대로입니다.
- 런처에 보이는 앱 이름은 시스템 언어를 따릅니다. 앱 안에서 선택한 언어가 외부 런처 이름까지 바꾸지는 않습니다.

**ADB 진입·설치 명령은 그대로입니다.** 새 전체 ZIP을 사용하면 기존 앱을 삭제하지 않고 v6/v10으로 업데이트합니다. 게이지 배치·그래프·앱 목록·켜 둔 바로가기 설정은 지우지 않습니다. 이전 설치본이 알려진 해시와 다르면 설치 도구는 중단합니다. 실차 화면과 완전 재부팅 후 복원은 여전히 확인이 필요합니다.
