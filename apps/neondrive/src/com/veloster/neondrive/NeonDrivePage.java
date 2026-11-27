package com.veloster.neondrive;

import android.content.Context;
import android.view.View;

/**
 * Neon Drive inside the N dashboard (a page of com.veloster.dashboard): the same view, hscand reader and passive gear
 * listeners as the standalone app, with a back button that returns to the dashboard. Settings stay in the
 * "neon_drive" preferences of the app that hosts it.
 */
public final class NeonDrivePage {
    private final DriveSignals signals = new DriveSignals();
    private final HscanFeed feed;
    private final GearFeed gear;
    private final NeonView view;
    private boolean running;

    public NeonDrivePage(Context context, Runnable onExit) { this(context, onExit, null); }

    /** media: the dashboard's now-playing state (its receiver keeps running while the page is up), or null for an own one. */
    public NeonDrivePage(Context context, Runnable onExit, com.veloster.vehicle.MediaState media) {
        feed = new HscanFeed(signals);
        gear = new GearFeed(context, signals);
        view = new NeonView(context, signals, HscanFeed.supported(), media);
        view.setExit(onExit);
    }
    public View view() { return view; }
    public void start() { if (running) return; running = true; feed.start(); gear.start(); view.resume(); }
    public void stop() { if (!running) return; running = false; view.pause(); gear.stop(); feed.stop(); }
}
