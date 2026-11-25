package com.veloster.car3d;

import android.content.Context;
import android.view.View;
import com.veloster.neondrive.DriveFeed;

/**
 * 내 차 as a page of the N dashboard: its own full-screen view over the dashboard (like Neon Drive), fed by the
 * same passive hscand / gear listeners. The back button returns to the dashboard.
 */
public final class CarPage {
    private final DriveFeed feed;
    private final CarView view;
    private boolean running;

    public CarPage(Context context, Runnable onExit) {
        feed = new DriveFeed(context);
        view = new CarView(context, feed.signals(), DriveFeed.supported());
        view.setExit(onExit);
    }

    public View view() { return view; }

    public void start() { if (running) return; running = true; feed.start(); view.resume(); }

    public void stop() { if (!running) return; running = false; view.pause(); view.clearTests(); feed.stop(); }
}
