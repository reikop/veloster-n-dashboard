# App-private error records / 앱 내부 오류 기록

[Documentation](README.md)

Unreleased source candidate, 2026-11-02. Both apps initialize [ErrorLog](../shared/src/com/veloster/diagnostics/ErrorLog.java) from their Application before Activities, Services and receivers start. Uncaught Java exceptions are passed to the previous Android exception handler after a best-effort write; logging failure does not suppress that handler. Vehicle subscription/cleanup failures and shortcut start/notification/overlay failures also write records.

Each app retains at most two files of 32 KiB each in its own internal files directory:

```text
files/diagnostics/errors.log
files/diagnostics/errors.previous.log
```

Records include a `VN-ERROR 1` header, wall-clock timestamp, fixed source label, exception class and bounded class/method/line frames (up to three causes and twelve frames per cause). Exception messages, media titles, device identifiers and telemetry payloads are not added to these files. Existing Android logcat behavior is separate. There is no public-storage copy, permission broadening, automatic upload or network endpoint.

A small append closes the file, and rotation retains the preceding file. This is best-effort diagnostic persistence, not a crash-proof transaction log: hard power loss, native crashes, early Application failures and resource exhaustion may leave no record. Device wall time can also be wrong.

For a deliberately connected test device, the debuggable candidate permits an attempted read via `run-as`. Replace `SERIAL` with the selected device, and substitute `errors.previous.log` to read the older file:

```sh
adb -s SERIAL shell run-as com.veloster.dashboard cat files/diagnostics/errors.log
adb -s SERIAL shell run-as com.veloster.carplayhub cat files/diagnostics/errors.log
```

These are read-only examples, not steps executed by the review. `run-as` availability on the target remains unverified. A missing file may mean no error was recorded; `run-as`/permission errors are not evidence of an empty log. Older Android ADB shell exit codes can be misleading: inspect the output for actual `VN-ERROR 1` records.

Host tests verify message omission, rotation bounds, recreation, unavailable storage, bounded stack formatting and forwarding the original error. Vehicle persistence across power-off remains unverified.

## 한국어

오류 종류·발생 위치를 앱 내부에 앱별 최대64 KiB로 남깁니다. 원래 Android 예외 처리를 유지하고, 기록 실패가 연결 재시도나 앱 시작을 막지 않게 했습니다. 차량 데이터나 예외 메시지를 파일에 담지 않습니다. 실제 차량의 `run-as` 읽기와 전원 종료 뒤 보존 여부는 아직 확인하지 않았습니다.
