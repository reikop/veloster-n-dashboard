# Wireless CarPlay local preview / 무선 CarPlay 로컬 후보

[Documentation / 문서 안내](README.md) · [Status / 확인 범위](STATUS.md) · [Build / 빌드](BUILD.md)

**2026-11-07 — current development source after the frozen v24 candidate.**
The exported module contains 229 production Java files / 254 pinned files. It
includes screen-independent Siri, optional OEM history/PTT/audio adapters and
AAC-ELD codecs, plus optional Consumer HID Next/Previous input. ELD16k mono (bit26) is advertised only after input and output
probes for the current attempt. ELD48k stereo (bit25) is output-only and requires
a separate decoder proof. Static declarations cannot bypass these checks.

The refreshed unsigned Hub includes the confirmed Binder-death client cleanup
and passed 11 build-input, 11 selection/settings, 14 host program and 4 Consumer
composition checks. The separate actual API17 Consumer gate fixture passed 9
checks. A joined generic API17 fixture passed 44 checks using the production
producer and cross-UID client, actual PairVerify and encrypted Consumer HID
reports/ACKs, including wrong-ACK and service-death retirement. Its test-only
service triggered keys; the OEM JAR was only a pinned hash asset.
A separate runtime check copied the exact current unsigned Hub payload, added a
test-only signature, and ran the resulting APK on generic API17. All 28 original
payload entries matched byte-for-byte. The real Hub UI opened settings and the
receiver screen, retained its service when the screen closed, and stopped it on
explicit Stop. Seven in-app checks confirmed packaged crypto JNI and fail-closed
OEM Consumer availability; eight screenshots and exact test-package cleanup were
recorded. The original unsigned APK was not installed or product-signed.
A reordered full-factory API17 fixture passed
compressed speech/telephony and 48k output. A later diagnostic fixture also passed
the original order; the earlier failure did not reproduce and remains unexplained.
The full-factory and joined-key fixtures did not run the refreshed public Hub
APK; the test-key derivative ran its unchanged production payload without a
phone session. Vehicle, real iPhone, OEM boot/key dispatch, AP hardware and
acoustic operation remain unverified.

The current Hub pins the `03c4a611…` OEM composite JAR, built on the corrected
`a28006e0…` Hub-signer candidate. The earlier `a4bb6bf5…` candidate is superseded:
its producer could throw when a callback death link was removed twice after timeout.
The previous `a764bb42…` composite used a test
fixture signer and is not a compatible product candidate. The unsigned Bluetooth
v22 candidate combines EIR and guarded USB registration in one package. Installing
the old EIR-only and USB-only packages sequentially would replace one with the
other; the existing installer profile does not yet install the complete new set.
The latest inspection build retains the prior TLS helper, CA and JNI bytes. Its
new Consumer client uses an exact-JAR, boot and service check on a worker; absent
or unverified service leaves the Consumer device undeclared. The OEM producer is
not shipped in the app, and physical key/iPhone delivery is unverified.
These are development sources and local unsigned artifacts, not a new release.
[Current build hashes and validation scope](validation/wireless-carplay-consumer-deathlink-20261107.json) ·
[previous Consumer snapshot](validation/wireless-carplay-consumer-20261106.json) ·
[previous audio snapshot](validation/wireless-carplay-audio-integrated-20261106.json).

마지막 로컬 설치 후보는 v24 / `1.23`이며 APK SHA256은
`8cfd9fa5a18419f372122e84edd7900d1087da5e9e8c54b52ad21a74cd8e2bc9`다.
이후 소스 검증용 APK는 별도 보관하며 설치 후보를 덮어쓰지 않는다.
아래 사용 흐름은 **현재 개발 소스** 기준이다.

## 현재 범위

공개 `HubApplication`이 실제 선택 폰 설정, `WirelessCarPlayService`, `IndependentSessionBackend`, AP 준비, 영속 pairing/bootstrap/journal과 수신 화면을 조립한다. 기본 Java 빌드에는 화면과 수신기 소스가 포함되며 실제 연결 시도에는 아래 JNI 빌드 입력과 별도 AP/OEM 구성요소가 필요하다.

| 항목 | 현재 동작과 한계 |
| --- | --- |
| 등록 전 발견 | 새 휴대폰 추가에서 시스템 검색 가능 승인을 요청한다(최대120초). 실제 AP·오디오·인증 준비 뒤 별도 v3 광고를 시작하고 종료를 추적한다. v3 carrier가 필요하며 실제 OEM 승인/SSP·EIR 수락은 미검증이다. |
| 화면/음악 선택 | 앱의 명시 선택을 따로 저장하고 휴대폰에 전송한다. 음악 끄기는 MEDIA만 제어한다. 250902 순정 요청·승인·최상위 화면이 확인되는 일부 사용자 선택을 관측한다. 선택형 고정 OEM producer가 있으면 검증된 순정 음악/화면 이력과 앱 checkpoint로 process 재시작 뒤 선택을 복원한다. 미설치·다른 부팅/producer 수명·불명확한 Home 변경은 재선택이 필요하다. OEM 후보는 미설치이며 실제 부팅 성공은 미확인이다. |
| 폰 선택 | 실제 bonded 목록에서 사용자가 직접 선택하고 앱 내부에 저장한다. 폰 변경 시 자동 연결은 꺼진다. 선택 변경·bond/BT 변경은 기존 연결 허가를 철회한다. |
| 자동 연결 | 사용자 opt-in과 선택 폰을 확인해 화면 없이 시도하고 Android 부팅 후 정책을 다시 읽는다. 같은 부팅·선택 revision에서3회 예산을 저장한다. 선택 폰의 새 Bluetooth 접속은 소진 예산을 재무장하지만 수동 중지는 취소하지 않는다. 명시 연결·새 부팅·새 선택 정책은 별도로 확인한다. Android 부팅을 차량 ignition과 동일시하지 않는다. |
| AP 준비 | worker가 이번 시도의 AP token으로 `usbwifi0` AP를 준비한다. SSID와 암호는 앱 내부에 생성·저장한다. 대역은 채널6 또는36을 선택할 수 있으나 실제 동글/AP 지원은 미검증이다. |
| 수신 화면 | Activity는 화면·입력 renderer를 붙이고 분리한다. 화면 닫기는 세션/오디오를 유지하며 연결 중지가 세션을 끝낸다. 새 폰 등록 승인에는 전경 화면이 필요하다. 서비스 실행은 실제 폰 연결 성공을 뜻하지 않는다. |
| 음량 | 재생 중인 미디어·안내/알림·Siri·벨/알림·통화 문맥의 음량을 저장한다. 실제 활성 문맥에서만 조절하며 차량 노브나 순정 전역 음량을 변경하지 않는다. |
| 정리와 복구 | 이전 worker/AP/폰 lease 해제가 완료되기 전 새 연결을 시작하지 않는다. 부분 준비·해제 실패는 복구 필요 상태로 남기며 사용자의 명시적 재시도를 요구한다. |

기존 배포본은 My Apps v10 / N Dashboard v6 그대로다. v24까지 별도 개발용 CarPlay 설치 프로필을 준비했다. 현재 후속 소스는 그 고정 후보와 별도다. 이 프로필은 기존 TLS·Wi-Fi·SSH·CA와 새 APK/JNI·AP manager pin을 함께 검증하며 이전 AP의 검증된 업그레이드·롤백 경로를 제공한다. 기존 v16 기본 설치기를 실행하는 것만으로 이 프로필이 선택되지는 않는다. 선택형 비공개 OEM 인증 APK도 같은 프로필에 연결했다. 원본7파일·현재 APK 확인 후 코드/native를 한 APK로 설치하며 명시적 원본 코드 fallback을 제공한다. 실제 OEM 인증 하드웨어·차량 설치 성공은 미확인이다.

## 후보 화면 사용 흐름

모듈과 별도 구성요소를 준비한 개발 후보의 화면 흐름이다. 현재 배포 APK를 설치하면 이 기능이 추가되는 절차는 아니다.

1. 내 앱의 **무선 CarPlay**에서 이미 페어링된 휴대폰을 직접 선택한다. 새 폰은 **새 휴대폰 추가 (120초)**의 시스템 승인을 사용한다. 실제 페어링 완료와 등록 광고 정리 뒤 직접 선택하며 목록 첫 기기를 자동 선택하지 않는다. 시스템 승인 화면이 없으면 차량 Bluetooth 설정이 필요하다.
2. 필요하면 **선택한 휴대폰에 자동 연결**을 켠다. 설정 저장 자체는 연결 완료가 아니다.
3. 수신 화면은 **수신 화면 열기**로 표시한다. 자동 연결이 꺼져 있으면 **선택한 휴대폰 연결**로 한 번 시도한다. 새 폰의 등록 승인은 전경 화면에서 진행한다.
4. **CarPlay 음악 사용/끄기**는 화면과 별개다. 화면을 닫아도 음악·통화 연결은 유지된다. 앱 process 재시작 후 검증 가능한 선택 이력이 없으면 화면과 음악 사용을 다시 선택한다.
5. **Wi-Fi 대역 선택**은 기존 연결을 종료하고 채널6/36을 저장한다. 바꾼 뒤 다시 연결한다.
6. **연결 중지**로 정리를 요청한다. 정리 실패 상태에서는 **정리 다시 시도**로 복구한다. 복구가 끝나도 폰 연결을 자동으로 시작하지 않는다. **화면 닫기**는 연결과 소리를 유지한다. 수동 중지는 같은 Android 부팅 동안 유지하며 직접 연결하거나 재부팅할 때 다시 정책을 확인한다.
7. 소리가 재생 중이면 **음량**에서 현재 문맥의 음량을 조절한다. 저장 중/실패 표시는 실제 저장 상태에 따른다.

JNI를 넣지 않은 빌드는 설정 화면을 열 수 있지만 연결 시 모듈이 없는 빌드임을 표시한다. 단순히 연결 버튼을 반복해서 눌러 불확실한 정리를 건너뛰지 않는다.

## 앞선 11/06 Siri·오디오 소스 검증

생산214Java/공개232파일의 미서명 빌드 APK는 `4aed7cc2…`이며 실제 설치용으로 서명하거나
차량에서 실행하지 않았다. 공개 소스 manifest는 `0cf2a6bc…`다. 기존 고정 v24와 별도다.

- 화면과 독립된 Siri 누름/뗌·출처 수명·이전 연결 콜백을 검증했다. 순정 PTT를 받는
  선택형 OEM 후보는 기존 이력 producer를 보존하며, 앱이 동일한 합성 JAR pin을 사용한다.
- 순정 route host84, 독립 입력 순서55시나리오/672검사, 실제 API17 Binder30을 통과했다.
  통화·후방카메라·VoiceMemo 상태가 바뀌어도 이미 보낸 DOWN의 UP을 원래 출처에서 처리한다.
  응답이 불확실하면 종료 ACK나 Binder death 확인까지 새 PTT 전달을 보류한다.
- 첫 RTP 전 FLUSH를 수정했고 PCM/AAC를 시험했다. AAC-ELD480은 실제 제품 오디오 출력에서
  AU60개/28,800프레임과 FLUSH 컷오프를 확인했다. 협상 mask는 여전히 거절하고 광고하지 않는다.
- 실제 순정 버튼 처리 메서드·OEM 부팅·아이폰·마이크 송신·차량 음향 성공은 검증하지 않았다.
  살아 있는 Binder가 계속 멈추면 worker/소유권과 PTT 보류를 유지한다.

선택형 OEM 패키지와 서명·property 구성이 별도로 필요하다. 공개 소스 export에는 OEM
바이너리나 인증 자료가 없다. 이전 설치기를 실행하는 것만으로 이 후보가 설치되지는 않는다.
[이번 빌드와 검증 범위](validation/wireless-carplay-siri-eld-20261106.json).

## 앞선 11/06 이력·위치 검증

앞선 생산210Java/227파일의 시험 APK는 `e743e092…`이며 고정 v24 설치 후보와 별개다.
Hub49·일반 UI·실제 Home 복귀는 앞선 `57d0b039…` APK에서 실행했다. 이후 변경은
GPS 문장 검사 한 파일이며 해당 Hub 검사 대상204개 bytecode class는 모두 동일하다.
새 APK 전체를 실행한 결과와 이 집중 검증 재사용을 구분한다.
순정 이력 코어·저장38+100검사, 실제 Binder/process 재시작13단계·prefs12검사,
원본/후보 Runnable48조합과 최종 앱49검사가 generic API17에서 통과했다.
Binder producer·OEM 외부 호출·폰 상대는 명시적인 대역이며 OEM 부팅을 검증하지 않는다.
이력 관측 시간 초과가 복원 권한을 철회하고 새 선택이 늦은 응답보다 우선하도록 했다.

GPS fallback은 측정된 속도·방향이 있을 때만 RMC를 만든다. 실제 API17 GPS probe는
RMC 수신만 확인했으며 원본 GGA 수신 성공으로 확대하지 않는다. 원본 GPS 문장의
정상 fix에서도 높이/속도/방향 누락을 차단하는 후속31검사는 host와 실제 API17에서
기존21묶음과 함께 통과했다. no-fix GGA0/RMC V는 유지한다. 바퀴 속도 공급원은
host23/API17 26검사와 보관 정차115레코드 검사를 통과했다. 변속/미끄러짐은 미확정이고
기본 backend는 계속 GPS만 사용한다. PASCD 인코더와 종류별 광고/요청 경로가 있어도
실제 바퀴 데이터·추측항법 지원이 켜졌다는 뜻은 아니다.
종류별 GPS/PASCD 암호화 전송은 host와 실제 API17에서6세대·update9개·중지/EOF
정리를 통과했다. 센서·OEM 인증·폰 상대는 대역이다.
당시 raw 검사까지 포함한210Java 전체 factory도 명시 선택·영상/음악/터치·Siri3쌍·
source6/7 실제8k 음성 캡처·부분 종료/EOF 정리를 통과했다. 공개 소스 해시 전부와
일치하며 소유한 TAP/helper/APK/forward를 제거했다. 별도 factory APK 시험이다.
[11/06 소스·검증 요약](validation/wireless-carplay-history-location-20261106.json).

## 재현 빌드

공개 저장소 루트에서 실행한다. JDK17, Android API16/17 JAR, AAPT2와 R8은 [기본 빌드 준비물](BUILD.md)을 따른다. 네이티브 입력은 [고정 TLS/OpenSSL 재현 절차](../third_party/network/README.md)로 먼저 만든 API17 ARMv7 출력이다. 새 출력 디렉터리를 사용한다.

```sh
python3 scripts/build_carplay_crypto.py \
  --ndk /path/to/android-ndk-r22b \
  --openssl-build /path/to/tls-build \
  --out /path/to/new-carplay-native

python3 scripts/build.py hub \
  --java /path/to/jdk17/bin/java \
  --android-jar /path/to/android.jar \
  --aapt2 /path/to/aapt2 \
  --r8-jar /path/to/r8.jar \
  --carplay-crypto /path/to/new-carplay-native/libvn-carplay-crypto.so \
  --carplay-crypto-manifest /path/to/new-carplay-native/build-manifest.json \
  --out build/hub-wireless-preview
```

`--carplay-crypto`와 `--carplay-crypto-manifest`는 함께 지정한다. 빌더는 공개 소스 manifest, JNI C 소스와 ARM/API17 라이브러리 해시를 대조하고 라이브러리와 고지를 APK에 넣는다. 기존 HTTPS 모듈도 넣으려면 [HTTPS 빌드 옵션](BUILD.md#optional-api17-https-module)을 추가한다. 결과 `hub-UNSIGNED.apk`는 서명 전 APK이며 별도 서명·설치 확인이 필요하다. 빌드가 AP/OEM 구성요소를 설치하거나 차량에 연결하지 않는다.

선택형 ELD JNI도 재현하려면 다음을 실행하고, 위 Hub 명령에 두 입력을 함께 추가한다.

```sh
python3 scripts/build_carplay_eld.py \
  --ndk /path/to/android-ndk-r22b \
  --out /path/to/new-carplay-eld
```

추가 옵션은 `--carplay-eld /path/to/new-carplay-eld/libvn-carplay-eld16.so`와
`--carplay-eld-manifest /path/to/new-carplay-eld/build-manifest.json`이다.
crypto 입력도 필요하다. 빌더는 C 소스·헤더·ARM/API17 출력 해시를 확인한다.
이 라이브러리는 자체 JNI이며 FDK 구현을 번들하지 않는다. 시스템 인코더의 실제
지원 여부를 실행 시 확인하며 OEM codec 바이너리의 정확한 버전·동작은 미검증이다.

수신기 모듈은 GPL-3.0-only이며 JmDNS·OpenSSL의 별도 고지도 유지한다. 결합 Hub 빌드를 배포할 때는 해당 라이선스와 대응 소스를 함께 보존한다. [모듈·소스 및 의존성 안내](../third_party/wireless-carplay/README.md) · [전체 고지](../NOTICE.md).

## 앞선 검증과 남은 범위

최신 추가분은 등록 전 typed EIR/실제 bond 판정 host검사, iAP 부분 종료 host70·독립
암호화 재설정2세대, 명시 선택 저장38·실제 암호화 sender32검사 및 공개 전체 빌드를 통과했다.
후속 generic API17에서 등록 backend7·저장38·암호화 sender32와 독립 iAP 인증2세대를
실행했다. 최신 소스에는 읽기 전용 순정 화면 observer가 연결됐고 actual API17 Binder25
검사와 최종 시험 APK의 계측38·일반 화면8개·실제 process 재시작·Home/동일 Activity
복귀/Back을 통과했다. 저장 선호만으로 재시작 후 화면/음악을 가져오지는 않는다.
이전204Java 전체 factory도 실제 API17에서 명시 선택·영상/음악/터치·두 음성 경로와
EOF 정리를 통과했다. AP/BT/OEM 인증·아이폰 상대는 대역이며 VM 마이크는 무음이다.
[11/05 검증 요약](validation/wireless-carplay-native-modes-20261105.json) ·
[이전 등록/모드 묶음](validation/wireless-carplay-onboarding-modes-20261105.json).

아래 실제 API17 결과는 이전199Java 변경 묶음의 근거다.

문맥별 음량은 host/API17각74검사, 선택된 화면만 종료·새 ID로 재설정하는 경로는
각50검사를 통과했다. 백그라운드 Hub8·Service8은 실제 API17에서 확인했으며,
프로세스3회 재실행에서 예산과 수동 중지의 영속성을 확인했다. 같은 변경 묶음의
실제 Hub26도 통과했다. 선택 폰·bond·AP·OEM/아이폰 상대는 시험 대역이다.
최종 화면 수명·서비스 종료 경합 및 생산199Java 전체 factory 회귀도 통과했다.
영상 픽셀·AudioTrack·터치·Siri/통화 전송·EOF 종료를 확인했으며 VM 마이크 입력은 무음이다.
최종 Hub/factory 재실행은 새 userdata와 `int:fast` 환경을 사용했다. 이전 VM 오류와 실패는
보존하며 JIT 단일 원인으로 단정하지 않는다.
[최종 검증 요약·시험 APK와 고정 후보 해시](validation/wireless-carplay-background-20261105.json).

공식 요구사항 대조에는 Apple의 [무선 시스템 발표](https://developer.apple.com/videos/play/wwdc2017/717/)와
[차량 시스템 최적화](https://developer.apple.com/videos/play/wwdc2023/10150/)를 사용했다.
앱용 CarPlay framework 문서를 차량 수신기의 전체 프로토콜 명세로 취급하지 않는다.
MFi 회원용 전체 사양·최신 인증 적합성은 확보·확인하지 않았다.
등록 전 발견 backend와 iAP type130 부분 종료는 대역 상대를 사용한 실제 API17 검사를 통과했다.
USB 최초 등록·실제 OEM 환경의 선택 이력 연속성·차량 속도/추측항법 통합은 미완료다. 선택형 이력 producer 후보와 앱 복원은 별도 개발·검증했으며 차량 설치/부팅 성공을 뜻하지 않는다.
일부 FLUSH/FLUSHBUFFERED 제한도 유지한다.
실제 OS boot/sticky 재시작·OEM 환경·무선/아이폰 성공은 별도 검증이 필요하다.

## 보존된 v19/v20 검증 기록

아래 수치와 당시 미완료 항목은 해당 고정 후보의 기록이다. 현재 판정은 위 요약을 따른다.

v19 r3 서명 APK를 generic API17에 실제 설치하고 APK readback, Hub 계측8상황,
정상 화면5개, JNI 로딩과 종료 후 Service/패키지 정리를 확인했다. 계측은 실제 앱의
Handler/worker를 쓰며 선택 폰 설정과 bond 관측은 fixture다. 정상 화면 시험은 실제
AP·Bluetooth 연결을 시작하지 않았다. 서명 APK SHA256은
`5af80496fb9d3be59a33a064dfa68dadbf398b5608d6fa5216b91ec10a62b88d`이며 미배포다.

마이크는 녹음 권한과 실제 지원 형식을 확인해 PCM mono8k/16k 중 지원되는 것만
광고한다. 실제 협상된 sample rate를 사용하며 몰래 변환하지 않는다. 인증된 선택 폰,
SETUP 응답 완료, RECORD와 음성 downlink 조건을 모두 만족해야 녹음을 시작한다.
전송·종료 host/API17 각각53묶음과 실제8k AudioRecord→암호화 UDP→복호화·종료를
검증했다. VM의 입력은 무음이며16k 녹음은 VM HAL이 거부했다. 실제 음향/Siri 성공을
뜻하지 않는다.

폰 RTP/NTP 기준 오디오 위상 제어와 제한된 재생률 보정을 구현했다. 최신 소스에서
host/API17 앵커605·정책19·sink6·UDP7검사와 실제 AudioTrack4상황을 통과했다.
peer clock은 시험 입력이며 sync UDP 자체에는 암호학적 인증이 없다. 실제 DAC 지연과
영상/소리의 물리적 동기화 성능은 미검증이다. 패킷 재전송 구현·검증은 아래 v18 기록을
참조한다.

한방 설치29검사는 설치 경계 fixture이며, 실제 서명 APK와 OEM 재료의 오프라인
검증도 통과했다. OEM loader의4경로 시험은 API17 모델 라이브러리로 수행했으며
실제 OEM native·인증 칩을 실행한 결과가 아니다. v19 전체 factory의 사용자 중지는 통과했으나 control EOF에서 정리 경합이 발견됐다.
Coordinator 단일 수정 후 실제 EOF→UI/양 journal 정리를 확인했다. Siri까지 포함한
v20 전체 통합 시험은 별도 진행 중이다.

실제 iPhone·OEM 출력·차량·동글 무선 성능은 미확인이다. Interworking 정보는 구현했지만 Apple/BT 광고 정보는 별도 조사 중이다.
실제 앱 버튼의 Siri 요청 전송은 시험 상대와 검증했으며, 전체 연결·마이크 통합 시험이 남아 있다. USB 등록 지원,
통화 경로, 전체 재협상·부팅 연결 정책도 남아 있다.
일부 FLUSH455/FLUSHBUFFERED501 및 영상 profile 제한을 유지한다. 차량 연결은
현재 개발 목표에서 제외했다. 이 후보는 무선 CarPlay 완성본이 아니다.

루트 `SOURCE_SHA256SUMS`, `SHA256SUMS`, `VALIDATION.json`은 이전 배포 스냅샷이다.
v19와 v20 근거는 각각의 로컬 빌드 manifest와 검사 영수증으로 추적한다.

### v18 복구·전환 검증

코어 host47/API17 49묶음과 실제 API17 프로세스 종료3시점 복구를 확인했다. boot ID/PID/starttime/UID가 이전 앱의 종료를 입증하고 같은 AP token의 해제가 확인돼야 journal을 정리한다. AP/root/OEM은 fixture다. 선택한 폰의 ACL만 제거하는 OEM API를 정적으로 확인했고 core11·반영 API fixture10·wireless40상황을 검증했다. 2.4GHz에서는 인증된 Wi-Fi·iAP2·수신 세션 관측 후 전환하며, 5GHz는 명시적인 요청이 없으면 Bluetooth 연결을 유지한다. 이것은 실제 폰 연결이나 전체 factory 검증을 대신하지 않는다.

개발용 설치기22·한방22검사와 실제 파일시스템의 AP 교체/롤백·비밀번호 보존을 검증했다. 서명된 후보에 대한 오프라인 설치 계획 인증도 수행했다. 오디오 재전송은 별도 기능/성능 검증 대상으로, 네트워크 패킷 복구와 실제 재생 기한 준수는 구분한다.

최종 v18 r3의 [검증 요약과 소스 해시](validation/wireless-carplay-v18-20261105.json)를 기록했다. 실제 Hub 계측7묶음과 정상 UI를 검증했고, 최종 APK도 API17에 설치하여 readback·화면·JNI·종료를 확인했다. 오디오 패킷 재전송은 호스트/API17 각각49묶음(무예열 포함)을 통과했지만 실제 재생 지연 검증은 아니다. 최종 서명 APK SHA256은 `502d02cc2d415cdd0de9106cfeed2a11ce67d3e09991e80d05df804a8c1675f5`이며 공개 다운로드 릴리스는 만들지 않았다.
