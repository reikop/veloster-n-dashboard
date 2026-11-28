# USB 업데이트 / USB update

## 한국어

컴퓨터나 폰 없이 USB 메모리로 N 계기판과 '내 앱'을 업데이트합니다. **이 기능이 들어간 버전을 처음 한 번은 설치기(ADB)로 설치해야** 하고, 그다음 버전부터 USB로 됩니다.

1. [릴리스](https://github.com/reikop/veloster-n-dashboard/releases/latest)에서 `vn-usb-update.zip`을 받습니다.
2. 압축을 풀어 나온 **`vn-update` 폴더를 USB 메모리 맨 위**에 넣습니다(FAT32/exFAT). 폴더 안에는 `manifest.txt`, `manifest.sig`, `dashboard.apk`, `hub.apk`가 있어야 합니다.
3. 시동을 켠 차에 USB 메모리를 꽂습니다. 설치된 것보다 새 버전이면 **USB 업데이트** 창이 뜹니다. 뜨지 않으면 **'내 앱' → USB 업데이트**를 누릅니다.
4. **[설치]** → 확인. 계기판이 먼저, '내 앱'이 나중에 바뀝니다. 그동안 두 앱이 잠시 닫히고, 끝나면 "업데이트 완료" 알림이 뜹니다. 계기판은 다시 열면 됩니다.

안전장치
- 두 앱은 누구나 구할 수 있는 AOSP 플랫폼 시험 키로 서명되어 있어, APK 서명만으로는 우리 파일인지 알 수 없습니다. 그래서 `manifest.txt`는 별도의 **업데이트 키**(RSA 2048)로 서명하고, '내 앱'은 이 서명과 각 APK의 크기·SHA-256·서명자·패키지·버전을 모두 확인한 뒤에만 설치합니다. 하나라도 다르면 설치하지 않습니다.
- 벨로스터 N 헤드유닛(`jsn_kr`, API 17)용 번들만, 이미 설치된 앱보다 **새 버전**일 때만 받습니다. 다른 앱은 설치할 수 없습니다.
- 화면에서 [설치]를 누르기 전에는 아무것도 설치하지 않습니다. USB를 꽂기만 해서 설치되는 일은 없습니다.
- '내 앱'이 설치 권한(`INSTALL_PACKAGES`, 플랫폼 서명 앱에 주어짐)을 받지 못하는 헤드유닛에서는 순정 설치 화면이 앱마다 한 번씩 열립니다. 거기서 [설치]를 누르세요.

확인 범위: 호스트 시험(번들 생성·서명·거부 경우), 안드로이드 에뮬레이터(API 24)에서 USB 장착 신호 → 창 → 설치 화면 없이 두 앱 교체 → 완료 알림 → 같은 버전 다시 꽂으면 창 안 뜸. **벨로스터 N 실차에서는 아직 확인하지 않았습니다**(USB 장착 신호를 앱이 받는지, 설치 권한이 주어지는지).

## English

Update the N Dashboard and My Apps from a USB stick, without a computer or phone. **The first version with this feature goes in with an installer (ADB) once**; later versions can come by USB.

1. Get `vn-usb-update.zip` from the [release](https://github.com/reikop/veloster-n-dashboard/releases/latest).
2. Unzip it and put the **`vn-update` folder at the top of a USB stick** (FAT32/exFAT): `manifest.txt`, `manifest.sig`, `dashboard.apk`, `hub.apk`.
3. Plug the stick into the car with the ignition on. When it holds newer versions than installed, **USB update** opens; otherwise use **My Apps → USB update**.
4. **Install** → confirm. The dashboard is replaced first, My Apps last; both close for a moment and "Update complete" shows at the end.

Safeguards: `manifest.txt` is signed with a separate **update key** (the APKs carry the public AOSP platform test key, which proves nothing); My Apps checks that signature and every APK's size, SHA-256, signer, package and version, only for this head unit (`jsn_kr`, API 17), only newer versions of the two apps, and only after the tap. Without `INSTALL_PACKAGES` it falls back to the system installer screen, one per app.

Checked: host tests and an Android emulator (API 24). **Not yet checked in the car.**

For developers: `scripts/usb_update.py` makes the bundle; the release workflow adds it when the `USB_UPDATE_KEY` secret holds the update key's private half. Format: `apps/hub/src/com/veloster/carplayhub/usb/UsbBundle.java`.
