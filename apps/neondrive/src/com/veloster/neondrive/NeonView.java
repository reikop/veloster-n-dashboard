package com.veloster.neondrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen animation surface. A tap shows the settings (gear) button for a few seconds;
 * the gear opens the settings panel: HUD on/off, demo mode, time of day (auto: clock and tail lamps)
 * and the factory body colour.
 */
final class NeonView extends View implements Runnable {
    static final long FRAME_MS = 33; // ~30 fps keeps the head unit cool
    static final long GEAR_SHOW_MS = 4000, GEAR_FADE_MS = 600;
    static final String PREFS = "neon_drive";
    private final NeonRenderer renderer = new NeonRenderer();
    private final DriveModel model = new DriveModel();
    /** Now playing: the N dashboard's passive OEM media widget receiver and artwork decoder. */
    private final com.veloster.vehicle.MediaState media;
    /** Own receiver only when standalone; as a dashboard page the dashboard's state (already holding the track) is used. */
    private final com.veloster.vehicle.MediaReceiver mediaReceiver;
    private com.veloster.vehicle.ArtworkLoader artwork;
    private final DriveSignals signals;
    private final Bitmap baseSprites;
    private long lastFrame = -1, gearUntil = Long.MIN_VALUE;
    private float downX, downY;
    private boolean running, panelOpen, tracking;
    private int paint, themeMode;
    private boolean clockOn;
    private long clockSecond = Long.MIN_VALUE;
    private static final String[] DAY_KO = {"일", "월", "화", "수", "목", "금", "토"}, DAY_EN = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /** Formats the clock once a second, following the system 12/24-hour setting and language. */
    private void updateClock() {
        long wall = System.currentTimeMillis();
        if (wall / 1000 == clockSecond && clockOn == renderer.clock()) return;
        clockSecond = wall / 1000;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(wall);
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        boolean ko = "ko".equals(java.util.Locale.getDefault().getLanguage());
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY), m = cal.get(java.util.Calendar.MINUTE);
        String suffix = "";
        if (!h24) { suffix = h < 12 ? (ko ? "오전" : "AM") : (ko ? "오후" : "PM"); h = h % 12 == 0 ? 12 : h % 12; }
        String time = String.format(java.util.Locale.US, h24 ? "%02d:%02d" : "%d:%02d", h, m);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1, mon = cal.get(java.util.Calendar.MONTH) + 1, day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        String date = ko ? String.format(java.util.Locale.US, "%d월 %d일 (%s)", mon, day, DAY_KO[dow])
            : String.format(java.util.Locale.US, "%s %02d/%02d", DAY_EN[dow], mon, day);
        renderer.setClock(clockOn, time, suffix, date);
    }

    NeonView(Context context, DriveSignals signals, boolean feedAvailable) { this(context, signals, feedAvailable, null); }

    /** shared: the N dashboard's now-playing state, so the track playing when the page opens shows at once (null = own). */
    NeonView(Context context, DriveSignals signals, boolean feedAvailable, com.veloster.vehicle.MediaState shared) {
        super(context);
        this.signals = signals;
        media = shared != null ? shared : new com.veloster.vehicle.MediaState();
        mediaReceiver = shared != null ? null : new com.veloster.vehicle.MediaReceiver(media);
        model.setFeedAvailable(feedAvailable);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        renderer.setHud(prefs.getBoolean("hud", true));
        clockOn = prefs.getBoolean("clock", true);
        model.setDemoForced(prefs.getBoolean("demo", false));
        paint = Math.max(0, Math.min(CarPaint.COUNT - 1, prefs.getInt("paint", 0)));
        renderer.setGearMode(prefs.getInt("gearmode", prefs.getBoolean("dct8", false) ? NeonRenderer.GEARS_DCT : NeonRenderer.GEARS_MANUAL));
        model.setEightSpeed(renderer.gearMode() == NeonRenderer.GEARS_DCT);
        renderer.setMediaOn(prefs.getBoolean("media", true));
        renderer.setTripOn(prefs.getBoolean("trip", true));
        // v16 stored 0 auto / 1 day / 2 night; the five-way setting uses "tod" (0 auto, 1 morning, 2 day, 3 sunset, 4 night).
        themeMode = Math.max(0, Math.min(Theme.MODE_KO.length - 1, prefs.getInt("tod", Theme.MODE_AUTO)));
        if (prefs.contains("lat") && prefs.contains("lon")) {
            lat = prefs.getFloat("lat", (float) lat); lon = prefs.getFloat("lon", (float) lon); locationSource = 1;
        }
        setKeepScreenOn(true);
        baseSprites = loadSprites(context);
        applyPaint();
    }

    /** The public builder links resources without generating R.java, so look the sheet up by name. */
    private static Bitmap loadSprites(Context context) {
        int id = context.getResources().getIdentifier("car_sprites", "drawable", context.getPackageName());
        if (id == 0) { android.util.Log.e("NeonDrive", "car_sprites resource missing"); return null; }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return android.graphics.BitmapFactory.decodeResource(context.getResources(), id, o);
    }

    /** Repaints the body pixels of the base car row (row 0) with the chosen factory colour. */
    private void applyPaint() {
        if (baseSprites == null) { renderer.setCarSprites(null); return; }
        if (paint == 0) { renderer.setCarSprites(baseSprites); return; }
        Bitmap copy = baseSprites.copy(Bitmap.Config.ARGB_8888, true);
        int w = copy.getWidth(), rowH = copy.getHeight() / CarSprite.ROWS;
        int[] px = new int[w * rowH];
        copy.getPixels(px, 0, w, 0, 0, w, rowH);
        CarPaint.repaint(px, paint);
        copy.setPixels(px, 0, w, 0, 0, w, rowH);
        renderer.setCarSprites(copy);
    }

    private double sunriseMin = Double.NaN, sunsetMin = Double.NaN;
    private long sunAt = Long.MIN_VALUE;
    /** Where the sun is computed for: a GPS fix (live or saved), else Seoul. */
    private double lat = SolarClock.SEOUL_LAT, lon = SolarClock.SEOUL_LON;
    private int locationSource; // 0 Seoul default, 1 saved fix, 2 GPS fix this session
    private android.location.LocationManager locations;
    private final android.location.LocationListener locationListener = new Fix(this);
    /** The location listener holds the view weakly: the system keeps a registered listener's transport alive a while
     *  after removeUpdates (until its own collection drops the binder), and an inner class kept the whole view, its
     *  baked scenery included, after the dashboard let go of the page (emulator: ~5 MB of Java heap per visit). */
    private static final class Fix implements android.location.LocationListener {
        private final java.lang.ref.WeakReference<NeonView> view;
        Fix(NeonView v) { view = new java.lang.ref.WeakReference<NeonView>(v); }
        public void onLocationChanged(android.location.Location l) { NeonView v = view.get(); if (v != null) v.useLocation(l, true); }
        public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
        public void onProviderEnabled(String provider) {}
        public void onProviderDisabled(String provider) {}
    }

    /** Newest last-known fix from any provider, then passive updates (fixes other apps such as navigation request). */
    private void startLocation() {
        try {
            if (locations == null) locations = (android.location.LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (locations == null) return;
            android.location.Location best = null;
            for (String provider : locations.getAllProviders()) {
                android.location.Location l = locations.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) useLocation(best, true);
            locations.requestLocationUpdates(android.location.LocationManager.PASSIVE_PROVIDER, 60000, 1000, locationListener);
            // Nothing known yet (navigation not running): ask GPS for one fix, once per session, and give up after 2 min.
            if (best == null && !singleRequested && locations.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                singleRequested = true;
                locations.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, locationListener, null);
                postDelayed(cancelSingle, 120000);
            }
        } catch (RuntimeException e) {   // SecurityException / missing provider: keep the saved fix or Seoul
            android.util.Log.w("NeonDrive", "location unavailable: " + e);
        }
    }

    private boolean singleRequested;
    private final Runnable cancelSingle = new Runnable() {
        public void run() { stopLocation(); if (running) startPassiveOnly(); }
    };

    private void startPassiveOnly() {
        try { locations.requestLocationUpdates(android.location.LocationManager.PASSIVE_PROVIDER, 60000, 1000, locationListener); }
        catch (RuntimeException ignored) { }
    }

    private void stopLocation() {
        removeCallbacks(cancelSingle);
        try { if (locations != null) locations.removeUpdates(locationListener); } catch (RuntimeException ignored) { }
    }

    private void useLocation(android.location.Location l, boolean live) {
        double la = l.getLatitude(), lo = l.getLongitude();
        if (Math.abs(la) > 90 || Math.abs(lo) > 180 || (la == 0 && lo == 0)) return;
        lat = Math.round(la * 100) / 100.0; lon = Math.round(lo * 100) / 100.0;   // 0.01 deg is plenty for the sun
        locationSource = live ? 2 : 1;
        sunAt = Long.MIN_VALUE;
        getContext().getSharedPreferences(PREFS, 0).edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).apply();
    }

    /** Sun position from the wall clock (date and time), refreshed every 30 s; today's sunrise/sunset for the panel. */
    private void updateSun() {
        long wall = System.currentTimeMillis();
        if (sunAt != Long.MIN_VALUE && Math.abs(wall - sunAt) < 30000) return;
        sunAt = wall;
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        double[] rs = SolarClock.sunriseSunset(wall, zone, lat, lon);
        sunriseMin = rs[0]; sunsetMin = rs[1];
        renderer.setSunInfo(SolarClock.hhmm(rs[0]), SolarClock.hhmm(rs[1]), Double.NaN, locationSource, lat, lon);
    }

    private void save() {
        getContext().getSharedPreferences(PREFS, 0).edit()
            .putBoolean("hud", renderer.hud()).putBoolean("clock", clockOn).putBoolean("demo", model.demoForced()).putInt("paint", paint).putInt("tod", themeMode).putInt("gearmode", renderer.gearMode()).putBoolean("media", renderer.mediaOn()).putBoolean("trip", renderer.tripOn()).apply();
    }

    void resume() {
        running = true; lastFrame = -1; removeCallbacks(this); post(this); startLocation();
        if (artwork == null) artwork = new com.veloster.vehicle.ArtworkLoader(getContext(), this);
        artwork.open(); if (mediaReceiver != null) mediaReceiver.start(getContext());
    }
    void pause() { running = false; removeCallbacks(this); stopLocation(); if (mediaReceiver != null) mediaReceiver.stop(); if (artwork != null) artwork.close(); }

    public void run() {
        if (!running) return;
        invalidate();
        postDelayed(this, FRAME_MS);
    }

    protected void onDetachedFromWindow() { pause(); super.onDetachedFromWindow(); }

    /** Once per install: the car shows DCT signs (DriveSignals.dctSeen) while the gear display is the 6-speed gate, so it
     *  switches to the 8-speed DCT display. Unconfirmed on a DCT car; the setting can be changed back and stays. */
    private boolean dctChecked;
    private void dctAuto() {
        if (dctChecked || !signals.dctSeen()) return;
        dctChecked = true;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, 0);
        renderer.setDctGuessed(true);
        if (prefs.getBoolean("dct_auto_done", false)) return;
        prefs.edit().putBoolean("dct_auto_done", true).apply();
        if (renderer.gearMode() != NeonRenderer.GEARS_MANUAL) return;
        renderer.setGearMode(NeonRenderer.GEARS_DCT); model.setEightSpeed(true); save();
        android.util.Log.i("NeonDrive", "DCT signs seen (TCU frame or 7th/8th gear): gear display set to 8-speed DCT (unconfirmed)");
    }

    protected void onDraw(Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        dctAuto();
        float dt = lastFrame < 0 ? 0f : (now - lastFrame) / 1000f;
        lastFrame = now;
        DriveModel.State s = model.update(signals, now, dt);
        float gear = gearUntil == Long.MIN_VALUE || now >= gearUntil ? 0f : Math.min(1f, (gearUntil - now) / (float) GEAR_FADE_MS);
        renderer.setUi(gear, panelOpen, paint, model.demoForced(), themeMode);
        com.veloster.vehicle.MediaState.Snapshot track = media.snapshot(now);
        if (artwork != null) artwork.request(track);
        renderer.setMedia(track.title, track.artist, track.hasProgress() ? track.progress() : -1f, artwork == null ? null : artwork.bitmap());
        updateSun();
        updateClock();
        java.util.Calendar local = java.util.Calendar.getInstance();
        double nowMin = local.get(java.util.Calendar.HOUR_OF_DAY) * 60 + local.get(java.util.Calendar.MINUTE) + local.get(java.util.Calendar.SECOND) / 60.0;
        renderer.setTheme(Theme.resolve(themeMode, nowMin, sunriseMin, sunsetMin));
        renderer.draw(canvas, getWidth(), getHeight(), s);
    }

    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); tracking = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > 24 || Math.abs(e.getY() - downY) > 24) tracking = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking) tap(e.getX(), e.getY());
                tracking = false;
                return true;
            default:
                tracking = false;
                return true;
        }
    }

    /** Set when Neon Drive runs inside the N dashboard: its back button calls this. */
    private Runnable onExit;
    void setExit(Runnable exit) { onExit = exit; renderer.setExitButton(exit != null); }

    private void tap(float x, float y) {
        long now = SystemClock.elapsedRealtime();
        int hit = renderer.hit(x, y);
        if (!panelOpen && hit == NeonRenderer.HIT_EXIT && onExit != null) { onExit.run(); return; }
        if (!panelOpen) {
            if (hit == NeonRenderer.HIT_GEAR) { panelOpen = true; gearUntil = Long.MIN_VALUE; }
            else gearUntil = now + GEAR_SHOW_MS;
            return;
        }
        if (hit == NeonRenderer.HIT_HUD) renderer.setHud(!renderer.hud());
        else if (hit == NeonRenderer.HIT_CLOCK) { clockOn = !clockOn; clockSecond = Long.MIN_VALUE; }
        else if (hit == NeonRenderer.HIT_DEMO) model.setDemoForced(!model.demoForced());
        else if (hit == NeonRenderer.HIT_MEDIA) renderer.setMediaOn(!renderer.mediaOn());
        else if (hit == NeonRenderer.HIT_TRIP) renderer.setTripOn(!renderer.tripOn());
        else if (hit >= NeonRenderer.HIT_GEARMODE && hit < NeonRenderer.HIT_GEARMODE + NeonRenderer.GEARS_KO.length) {
            renderer.setGearMode(hit - NeonRenderer.HIT_GEARMODE); model.setEightSpeed(renderer.gearMode() == NeonRenderer.GEARS_DCT);
        }
        else if (hit >= NeonRenderer.HIT_THEME && hit < NeonRenderer.HIT_THEME + Theme.MODE_KO.length) themeMode = hit - NeonRenderer.HIT_THEME;
        else if (hit >= NeonRenderer.HIT_PAINT && hit < NeonRenderer.HIT_PAINT + CarPaint.COUNT) {
            paint = hit - NeonRenderer.HIT_PAINT;
            applyPaint();
        } else if (hit == NeonRenderer.HIT_CLOSE || hit == NeonRenderer.HIT_OUTSIDE) {
            panelOpen = false;
        }
        save();
    }
}
