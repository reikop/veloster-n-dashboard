# My Apps v10: reopen without modifying the factory home menu

See [Language settings](LANGUAGES.md) for Korean/English selection. Button labels follow the chosen app language.

**English** | [한국어](QUICK_ENTRY_ko.md) | [Installation](INSTALL_EN.md)

My Apps v10 adds an **optional edge button and persistent notification**. These use standard Android services/windows rather than vehicle-specific OEM menu hooks. They do not replace the factory home screen. “Generic entry” describes this design; it does **not** establish compatibility with every head unit or Android version.

**v10 is a prerelease:** compilation, signatures and host lifecycle tests passed, but actual vehicle overlay visibility and delivery of the boot broadcast have not been checked for this version. Do not describe a host test as a successful vehicle installation. The dashboard's vehicle interfaces still have their own compatibility requirements.

## First installation — finish before disconnecting

1. Follow [Installation](INSTALL_EN.md). The guided installer opens My Apps after checking the installed APKs. If needed, open it explicitly:

   ```sh
   adb -s YOUR_DEVICE_SERIAL shell am start -n com.veloster.carplayhub/.HubActivity
   ```

2. Tap **실행 바로가기 / Quick entry** near the top of My Apps.
3. Select **가장자리 버튼 + 알림 / Floating + notification**, then **저장 / Save**. It is OFF by default; APK installation alone does not enable it.
4. Return to the factory HOME screen. A small **앱** button should appear near the screen edge. Tap it to reopen My Apps, then tap **N 계기판** to open the dashboard.
5. Drag the small button to a free position; it snaps to the left/right edge and remembers its relative height. Verify it does not cover controls you need, including the actual reversing-camera display before regular use.
6. Only after you can reopen the apps using the screen should you disconnect the computer. During a subsequent normal shutdown/reboot while parked, check the edge button again. Installation is not fully accepted until this restart test passes on your head unit.

The app's **설정 / Setting** text reports the selected mode, not proof that an OEM screen is showing the button. Inspect any failure message beneath it and perform the HOME-screen test.

## Other choices

- **알림만 / Notification only:** opens My Apps from the persistent Android notification, without an overlay. Use this only if your factory UI actually exposes the notification panel.
- **사용 안 함 / Off:** removes the shortcut service, button and notification, and disables future automatic shortcut restoration. Keep another working entry before selecting it.
- The notification also has **바로가기 끄기** (turn shortcut off). It has the same persistent OFF effect.

After explicitly enabling a mode, boot or app replacement requests restoration of the **shortcut only**. It does not automatically open the full My Apps or dashboard screen. Screen-off and keyguard hide the floating button; unlocking returns it. No wake lock, periodic boot polling, wireless activation or OEM menu change is used.

## If it does not appear

| Observation | Action |
| --- | --- |
| My Apps still shows OFF | Select a mode and Save; installation does not opt you in. |
| Overlay error shown, notification remains | The system denied or could not create the overlay. If notifications are accessible, use that entry. On systems offering “display over other apps,” check that permission. |
| Neither overlay nor notification is accessible | Keep ADB connected. The standard route is not usable on this UI; inspect the error and use a compatible launcher or the exact-build [optional OEM menu route](MENU_ENTRY.md). |
| Disappears after force-stop | Android can suppress startup for stopped apps. Reopen My Apps explicitly; its existing enabled setting is restored. Force-stop is not the OFF setting. |
| Disappears only after a full reboot | Reopen My Apps via ADB and confirm its saved mode. Record whether the service/notification appears. OEM boot/service restrictions remain a possible limit; do not reinstall repeatedly. |
| My Apps opens, but dashboard fails | Check the dashboard installation and OEM library compatibility separately. A working shortcut does not establish vehicle telemetry support. |

Diagnostic commands, while connected:

```sh
adb -s YOUR_DEVICE_SERIAL shell pm path com.veloster.carplayhub
adb -s YOUR_DEVICE_SERIAL shell dumpsys activity services com.veloster.carplayhub
adb -s YOUR_DEVICE_SERIAL shell am start -n com.veloster.carplayhub/.HubActivity
```

Share only relevant error text, model/software version and app version after removing device identifiers. Do not post full private captures. To disable, use the visible Quick entry OFF choice; do not clear app data, as it also removes app-list settings.
