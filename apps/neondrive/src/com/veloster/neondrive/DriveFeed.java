package com.veloster.neondrive;

import android.content.Context;

/**
 * Neon Drive's passive car signals for another screen of the N dashboard (the 내 차 3D page): the read-only hscand
 * subscriber (lamps, steering, speed, pedals) and the passive OEM gear / rear-view listeners, into one DriveSignals.
 * Nothing is written to the car.
 */
public final class DriveFeed {
    private final DriveSignals signals = new DriveSignals();
    private final HscanFeed feed;
    private final GearFeed gear;
    private boolean running;

    public DriveFeed(Context context) {
        feed = new HscanFeed(signals);
        gear = new GearFeed(context, signals);
    }

    /** The Veloster N head unit (jsn_kr); elsewhere start() does nothing and the signals stay unknown. */
    public static boolean supported() { return HscanFeed.supported(); }

    public DriveSignals signals() { return signals; }

    public synchronized void start() { if (running) return; running = true; feed.start(); gear.start(); }

    public synchronized void stop() { if (!running) return; running = false; gear.stop(); feed.stop(); }
}
