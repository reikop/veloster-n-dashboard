# Neon Drive / 네온 드라이브 (preview)

80년대 네온 시티팝 분위기의 직선 고속도로를 달리는 스크린세이버형 앱입니다. 게임이 아니며, 화면 속 벨로스터 N이 실제 차량의 **가속페달·브레이크·좌우 방향지시등·비상등·조향**과 속도를 따라 움직입니다. 핸들을 돌리면 도로가 그쪽으로 휘고(주행 중) 차체가 기웁니다.

- 입력: 계기판과 같은 수신 전용 `hscan_mux` 소켓만 사용합니다. OEM `automotive` 라이브러리, 진단 요청, 소켓 쓰기가 없습니다. 지원 빌드(`jsn_kr`, `250902`)가 아니면 소켓을 열지 않고 데모를 재생합니다.
- 신호: CLU11 속도, EMS12 페달·브레이크 스위치, ESP12 제동 압력, SAS11 조향각, CGW1 방향지시등·비상등 스위치·하향등. 바이트/비트 배치는 `src/com/veloster/neondrive/DriveSignals.java` 주석에 있습니다.
- 조작: 탭=HUD 켜기/끄기, 1.2초 길게 누르기=데모 강제 켜기/끄기, 뒤로=종료.
- 빌드: `python3 scripts/build.py neondrive ...`(서명 전 APK). 테스트: `python3 scripts/test.py`(`tests/NeonDriveTest.java`). 미리보기: `tests/neondrive-preview/`의 Java2D 대역으로 실제 렌더러를 PNG/GIF로 그립니다.

확인 범위: 호스트 테스트와 범용 Android 4.2.2 에뮬레이터 실행까지 확인했습니다. 실차 설치·동작은 확인하지 않았습니다. 기존 캡처에는 방향지시등을 켠 구간이 없어 깜빡이 비트 해석과 조향 좌/우 방향은 실차에서 확인이 필요합니다.

## 차 스프라이트

`art/veloster-n-sheet-qwen.png`는 Higgsfield API(Qwen Image 3)로 생성한 7방향 시트입니다(실제 사진이 아닌 AI 생성물). `art/SpriteForge.java`가 이를 공통 팔레트 도트 스프라이트 `res/drawable-nodpi/car_sprites.png`와 좌표 소스 `src/.../CarSprite.java`로 변환합니다.

```sh
java apps/neondrive/art/SpriteForge.java apps/neondrive/art/veloster-n-sheet-qwen.png \
  apps/neondrive/res/drawable-nodpi/car_sprites.png apps/neondrive/src/com/veloster/neondrive/CarSprite.java 0.32 32
```
