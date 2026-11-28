package com.veloster.carplayhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.veloster.carplayhub.usb.UsbBundle;
import com.veloster.carplayhub.usb.UsbInstaller;
import com.veloster.i18n.UiText;
import java.util.List;

/**
 * USB 업데이트: shows what a stick's vn-update/ folder would install (from 내 앱 → USB 업데이트, or by itself when a stick
 * with a newer bundle is mounted), installs on [설치], reports. Nothing is installed without the tap.
 */
public final class UsbUpdateActivity extends Activity {
    public static final String EXTRA_ROOT = "root";
    private UsbInstaller installer;
    private UsbInstaller.Offer offer;
    private TextView status;
    private Button install;
    private boolean busy;
    /** Fallback (silent install refused): the apps still to hand to the system installer, one per return here. */
    private int fallbackNext = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installer = new UsbInstaller(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 24, 32, 24);
        box.setBackgroundColor(0xff101418);
        TextView title = new TextView(this);
        title.setText(UiText.t("USB 업데이트")); title.setTextSize(26); title.setTextColor(0xffffffff);
        box.addView(title);
        status = new TextView(this); status.setTextSize(18); status.setTextColor(0xffd8e2ea); status.setPadding(0, 16, 0, 16);
        box.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.END);
        install = new Button(this); install.setText(UiText.t("설치")); install.setTextSize(18);
        Button close = new Button(this); close.setText(UiText.t("닫기")); close.setTextSize(18);
        row.addView(close); row.addView(install);
        box.addView(row);
        setContentView(box);
        install.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirm(); } });
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        scan(getIntent().getStringExtra(EXTRA_ROOT));
    }

    @Override protected void onResume() {
        super.onResume();
        if (fallbackNext >= 0 && !busy) nextFallback();   // back from the system installer: the next app, or the result
    }

    private void scan(final String root) {
        status.setText(UiText.t("USB 메모리를 확인하는 중…")); install.setEnabled(false);
        new Thread(new Runnable() { public void run() {
            final UsbInstaller.Offer o = installer.find(root);
            runOnUiThread(new Runnable() { public void run() { show(o); } });
        }}, "usb-update-scan").start();
    }

    private void show(UsbInstaller.Offer o) {
        offer = o;
        if (o == null) {
            status.setText(installer.lastError != null ? UiText.t("USB 업데이트 파일을 쓸 수 없습니다.") + "\n" + installer.lastError
                : UiText.t("새 업데이트가 있는 USB 메모리가 없습니다.\nUSB 메모리 맨 위에 vn-update 폴더를 넣고 다시 꽂으세요."));
            install.setEnabled(false);
            return;
        }
        StringBuilder s = new StringBuilder(UiText.t("업데이트")).append(' ').append(o.bundle.release).append('\n');
        for (int i = 0; i < o.apps.size(); i++)
            s.append("\n· ").append(label(o.apps.get(i).packageName)).append("  v").append(versionName(o.apps.get(i).packageName)).append(" → ").append(o.bundle.release);
        s.append("\n\n").append(UiText.t("설치하는 동안 계기판과 내 앱이 잠시 닫힙니다."));
        status.setText(s);
        install.setEnabled(true);
    }

    private String label(String pkg) { return UsbBundle.DASHBOARD.equals(pkg) ? UiText.t("N 계기판") : UiText.t("내 앱"); }

    private String versionName(String pkg) {
        try { return getPackageManager().getPackageInfo(pkg, 0).versionName; } catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    private void confirm() {
        if (offer == null || busy) return;
        new AlertDialog.Builder(this).setTitle(UiText.t("USB 업데이트"))
            .setMessage(UiText.t("업데이트를 설치할까요?"))
            .setPositiveButton(UiText.t("설치"), new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) { run(); } })
            .setNegativeButton(UiText.t("취소"), null).show();
    }

    private void run() {
        busy = true; install.setEnabled(false);
        final UsbInstaller.Offer o = offer;
        new Thread(new Runnable() { public void run() {
            String result;
            try {
                List<String> done = installer.install(o, new UsbInstaller.Progress() { public void step(final String m) {
                    runOnUiThread(new Runnable() { public void run() { status.setText(UiText.t("설치하는 중…") + "\n" + m); } });
                }});
                result = UiText.t("업데이트 완료") + "\n" + join(done);
            } catch (Exception e) {
                if (UsbInstaller.refused(e)) {   // no silent install on this unit: one system installer screen per app
                    runOnUiThread(new Runnable() { public void run() { busy = false; fallbackNext = 0; nextFallback(); } });
                    return;
                }
                com.veloster.diagnostics.ErrorLog.record("usb_update", e);
                result = UiText.t("업데이트하지 못했습니다.") + "\n" + e.getMessage();
            }
            final String r = result;
            runOnUiThread(new Runnable() { public void run() { busy = false; status.setText(r); } });
        }}, "usb-update-install").start();
    }

    /** Hands the next app (dashboard first) to the system installer; after the last, rescans to show what is left. */
    private void nextFallback() {
        if (offer == null || fallbackNext >= offer.apps.size()) { fallbackNext = -1; scan(null); return; }
        UsbBundle.App a = offer.apps.get(fallbackNext++);
        try {
            status.setText(UiText.t("설치 화면에서 [설치]를 누르세요.") + "\n" + label(a.packageName));
            startActivity(installer.installerIntent(offer, a));
        } catch (Exception e) {
            com.veloster.diagnostics.ErrorLog.record("usb_update_installer", e);
            fallbackNext = -1;
            status.setText(UiText.t("업데이트하지 못했습니다.") + "\n" + e.getMessage());
        }
    }

    private static String join(List<String> lines) { StringBuilder s = new StringBuilder(); for (String l : lines) s.append("\n· ").append(l); return s.toString(); }
}
