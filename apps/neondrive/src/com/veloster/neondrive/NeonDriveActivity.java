package com.veloster.neondrive;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Own package and task; reads vehicle state through the passive hscand socket and passive OEM gear listeners. */
public final class NeonDriveActivity extends Activity {
    private final DriveSignals signals = new DriveSignals();
    private HscanFeed feed;
    private GearFeed gear;
    private NeonView view;

    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        feed = new HscanFeed(signals);
        gear = new GearFeed(this, signals);
        view = new NeonView(this, signals, HscanFeed.supported());
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        setContentView(view);
    }

    protected void onResume() { super.onResume(); feed.start(); gear.start(); view.resume(); }
    protected void onPause() { view.pause(); gear.stop(); feed.stop(); super.onPause(); }
    protected void onDestroy() { if (feed != null) feed.stop(); if (gear != null) gear.stop(); super.onDestroy(); }
}
