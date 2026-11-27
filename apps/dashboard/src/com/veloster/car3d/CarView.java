package com.veloster.car3d;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.veloster.i18n.UiText;
import com.veloster.neondrive.CarPaint;
import com.veloster.neondrive.DriveSignals;
import java.io.InputStream;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 내 차: the 3D Veloster N over the whole screen, lamps / steering / wheels following the car (CarState), with a
 * HUD on top - back, view presets, factory colour, live readout and test buttons that light lamps by hand.
 * Drag to turn the car around, two fingers to zoom, tap to show / hide the test buttons.
 */
final class CarView extends FrameLayout implements Runnable {
    static final String PREFS = "car3d";
    static final long FRAME_MS = 33, IDLE_MS = 200;
    private static final float DEFAULT_YAW = 35, DEFAULT_PITCH = 5, DEFAULT_DIST = 6.0f;   // a low camera, like a car photo: the backdrop shows
    private static final float[][] VIEWS = {{35, 5, 6.0f}, {215, 6, 6.0f}, {90, 3, 6.4f}, {180, 82, 10.4f}};
    private static final String[] VIEW_NAMES = {"앞", "뒤", "옆", "위"};
    private static final int[] CHIP_TESTS = {CarState.TEST_LEFT, CarState.TEST_HAZARD, CarState.TEST_TAIL, CarState.TEST_HEAD,
        CarState.TEST_STOP, CarState.TEST_REVERSE, CarState.TEST_STEER, CarState.TEST_RIGHT};
    private static final String[] CHIP_NAMES = {"좌", "비상", "미등", "전조등", "브레이크", "후진", "핸들", "우"};
    /** Second row: open / shut each hinged part by hand (no car signal for them yet). */
    private static final int[] OPEN_TESTS = {CarState.TEST_DOOR_L, CarState.TEST_DOOR_RF, CarState.TEST_DOOR_RR, CarState.TEST_BONNET,
        CarState.TEST_HATCH, CarState.TEST_MIRRORS};
    private static final int[] OPEN_PART = {CarModel.DOOR_L, CarModel.DOOR_RF, CarModel.DOOR_RR, CarModel.BONNET, CarModel.HATCH, CarModel.MIRROR_L};
    private static final String[] OPEN_NAMES = {"운전석 문", "조수석 문", "뒷문", "후드", "트렁크", "미러 접기"};

    private final DriveSignals signals;
    private final DriveSignals.Snapshot snap = new DriveSignals.Snapshot();
    private final CarState state = new CarState();
    private final CarRenderer renderer = new CarRenderer();
    private final GLSurfaceView gl;
    private final Hud hud;
    private final SharedPreferences prefs;
    private Runnable onExit;
    private boolean running, feedAvailable;
    private volatile boolean loaded;
    private volatile String failure;
    private int tests, paint;
    private float gLong = Float.NaN, gLat = Float.NaN;
    private long lastFrame = Long.MIN_VALUE;
    /** The factory colour list under the swatch is open. */
    private boolean paletteOpen;
    /** Test buttons: hidden until a tap on the screen. The backdrop list is open. */
    private boolean chipsShown, sceneOpen;
    private int scene;
    // camera and its fling
    private float yaw, pitch, dist, yawVel, pitchVel;
    private float lastX, lastY, pinchStart, distStart, downX, downY;
    private long downAt;
    private boolean dragging, pinching, touchDown;
    private final float[] lastGlow = new float[CarState.GROUPS], lastOpen = new float[CarState.OPENINGS];
    private float lastWheel = Float.NaN, lastRoll = Float.NaN;

    CarView(Context context, DriveSignals signals, boolean feedAvailable) {
        super(context);
        this.signals = signals; this.feedAvailable = feedAvailable;
        prefs = context.getSharedPreferences(PREFS, 0);
        yaw = prefs.getFloat("yaw", DEFAULT_YAW); pitch = prefs.getFloat("pitch", DEFAULT_PITCH); dist = prefs.getFloat("dist", DEFAULT_DIST);
        paint = Math.max(0, Math.min(CarPaint.COUNT - 1, prefs.getInt("paint", 0)));
        applyPaint();
        scene = CarScene.valid(prefs.getInt("scene", CarScene.STUDIO));
        renderer.setScene(scene);
        loadBackdrop(context.getApplicationContext(), scene);
        renderer.setCamera(yaw, pitch, dist);
        gl = new GLSurfaceView(context);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new CarRenderer.ConfigChooser());
        gl.setPreserveEGLContextOnPause(true);
        gl.setRenderer(new Safe());
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        addView(gl, new LayoutParams(-1, -1));
        hud = new Hud(context);
        addView(hud, new LayoutParams(-1, -1));
        setKeepScreenOn(true);
        load(context.getApplicationContext());
    }

    void setExit(Runnable exit) { onExit = exit; }

    /** The 3D view draws at 80 % of the screen in each direction (64 % of the pixels) and the display scales it up; the
     *  HUD on top stays sharp. The GPU's per-pixel work (the backdrop, the floor, the car's reflections) is most of a frame. */
    static final float RENDER_SCALE = 0.8f;
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) gl.getHolder().setFixedSize(Math.round(w * RENDER_SCALE), Math.round(h * RENDER_SCALE));
    }

    /** Shooting Star (T5R) is the matte factory colour. */
    private void applyPaint() { renderer.setPaint(paint == 0 ? 0 : CarPaint.RGB[paint], "T5R".equals(CarPaint.CODE[paint])); }

    /** Reads the mesh and lamp atlas off the UI thread. */
    private void load(final Context app) {
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                InputStream in = app.getAssets().open("car3d/veloster_n.bin");
                CarModel m;
                try { m = CarModel.read(in); } finally { in.close(); }
                Bitmap base = bitmap(app, "car3d/lamp_base.png"), glow = bitmap(app, "car3d/lamp_glow.png");
                renderer.setModel(m, base, glow);
                loaded = true;
                android.util.Log.i("Car3D", "mesh " + m.chunks.length + " chunks, " + m.triangles + " triangles");
            } catch (Exception e) {
                failure = UiText.t("3D 모델을 불러오지 못했습니다");
                com.veloster.diagnostics.ErrorLog.record("car3d_load", e);
                android.util.Log.e("Car3D", "model load failed", e);
            }
            post(new Runnable() { public void run() { gl.requestRender(); hud.invalidate(); } });
        }}, "car3d-load");
        t.setDaemon(true);
        t.start();
    }

    /** Decodes a backdrop panorama off the UI thread, plus a 512 x 128 blurred copy (halved step by step) for reflections. */
    private void loadBackdrop(final Context app, final int which) {
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                Bitmap sharp = bitmap(app, CarScene.FILES[which]), small = sharp;
                while (small.getWidth() > 512) small = Bitmap.createScaledBitmap(small, small.getWidth() / 2, small.getHeight() / 2, true);
                if (which == scene) { renderer.setScene(which); renderer.setBackdrop(sharp, small); }
            } catch (Exception e) {
                com.veloster.diagnostics.ErrorLog.record("car3d_backdrop", e);
                android.util.Log.e("Car3D", "backdrop load failed", e);
            }
            post(new Runnable() { public void run() { gl.requestRender(); } });
        }}, "car3d-backdrop");
        t.setDaemon(true);
        t.start();
    }

    private static Bitmap bitmap(Context app, String name) throws java.io.IOException {
        InputStream in = app.getAssets().open(name);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } finally { in.close(); }
    }

    void resume() {
        if (running) return;
        running = true; lastFrame = Long.MIN_VALUE;
        gl.onResume();
        removeCallbacks(this); post(this);
    }

    void pause() {
        if (!running) return;
        running = false; removeCallbacks(this);
        gl.onPause();
        prefs.edit().putFloat("yaw", yaw).putFloat("pitch", pitch).putFloat("dist", dist).putInt("paint", paint).apply();
    }

    @Override protected void onDetachedFromWindow() { pause(); super.onDetachedFromWindow(); }

    @Override public void run() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == Long.MIN_VALUE ? 0 : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        signals.snapshot(now, snap);
        float[] g = com.veloster.vehicle.VehicleDash.gSensor();   // the dashboard's own G sensor feed (OEM listener)
        gLong = g[0]; gLat = g[1];
        state.update(feedAvailable ? snap : null, g[0], g[1], tests, now);
        boolean moving = false;
        if (!touchDown && (Math.abs(yawVel) > 0.5f || Math.abs(pitchVel) > 0.5f)) {   // fling
            yaw += yawVel * dt; pitch = clampPitch(pitch + pitchVel * dt);
            float decay = (float) Math.exp(-dt * 3.5);
            yawVel *= decay; pitchVel *= decay; moving = true;
        }
        renderer.setCamera(yaw, pitch, dist);
        renderer.setState(state);
        boolean changed = moving || dragging || pinching || state.animating() || stateChanged();
        if (changed) gl.requestRender();
        hud.invalidate();
        postDelayed(this, changed || touchDown ? FRAME_MS : IDLE_MS);
    }

    private boolean stateChanged() {
        boolean c = state.wheelDeg != lastWheel || state.rollDeg != lastRoll;
        lastWheel = state.wheelDeg; lastRoll = state.rollDeg;
        for (int g = 0; g < CarState.GROUPS; g++) if (state.glow[g] != lastGlow[g]) { c = true; lastGlow[g] = state.glow[g]; }
        for (int h = 0; h < CarState.OPENINGS; h++) if (state.open[h] != lastOpen[h]) { c = true; lastOpen[h] = state.open[h]; }
        return c;
    }

    private static float clampPitch(float p) { return Math.max(2f, Math.min(82f, p)); }

    /** The renderer behind a guard: a GPU or shader failure shows a message instead of taking the dashboard down. */
    private final class Safe implements GLSurfaceView.Renderer {
        private boolean broken;
        @Override public void onSurfaceCreated(GL10 g, EGLConfig c) { if (!broken) try { renderer.onSurfaceCreated(g, c); } catch (RuntimeException e) { fail(e); } }
        @Override public void onSurfaceChanged(GL10 g, int w, int h) { if (!broken) try { renderer.onSurfaceChanged(g, w, h); } catch (RuntimeException e) { fail(e); } }
        @Override public void onDrawFrame(GL10 g) {
            if (broken) { android.opengl.GLES20.glClearColor(0.035f, 0.04f, 0.055f, 1); android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT); return; }
            try { renderer.onDrawFrame(g); } catch (RuntimeException e) { fail(e); }
        }
        private void fail(RuntimeException e) {
            broken = true;
            failure = UiText.t("3D 화면을 그릴 수 없습니다");
            com.veloster.diagnostics.ErrorLog.record("car3d_gl", e);
            android.util.Log.e("Car3D", "GL failure", e);
            post(new Runnable() { public void run() { hud.invalidate(); } });
        }
    }

    // ---- HUD -----------------------------------------------------------------------------------------------------
    private static final int WHITE = 0xffffffff, MUTED = 0xff8d97a8, BLUE = 0xff4aa3ff, PANEL = 0xb0101620, EDGE = 0x40ffffff;
    private static final int AMBER = 0xffffa41a, RED = 0xffff3b30, TAILC = 0xffff6a4d, HEADC = 0xffd6e8ff, REVC = 0xfff2f2f2;
    private static final float CHIP_Y = 420, CHIP_H = 46, CHIP_GAP = 8, CHIP_L = 12;
    private static final float BACK_L = 12, BACK_T = 10, BACK_W = 48, BACK_H = 40;
    private static final float BG_X = 458, BG_W = 56, BGL_L = 400, BGL_R = 570;
    private static final float PAL_L = 540, PAL_R = 790, PAL_T = 58, PAL_ROW = 44;
    private static float paletteRow(int i) { return PAL_T + 6 + i * PAL_ROW; }
    private static final float VIEW_L = 520, VIEW_T = 10, VIEW_W = 50, VIEW_H = 40, SWATCH_X = 762, SWATCH_Y = 30, SWATCH_R = 18;

    private static float chipW() { return (800 - 2 * CHIP_L - 7 * CHIP_GAP) / 8f; }
    private static float chipX(int i) { return CHIP_L + i * (chipW() + CHIP_GAP); }
    /** The second row (doors, bonnet, hatch, mirrors) above the lamp row. */
    private static final float OPEN_Y = CHIP_Y - CHIP_H - CHIP_GAP;
    private static float openW() { return (800 - 2 * CHIP_L - (OPEN_TESTS.length - 1) * CHIP_GAP) / (float) OPEN_TESTS.length; }
    private static float openX(int i) { return CHIP_L + i * (openW() + CHIP_GAP); }

    private final class Hud extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final android.graphics.LinearGradient top = new android.graphics.LinearGradient(0, 0, 0, 90, 0x99000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP);
        private final android.graphics.LinearGradient bottom = new android.graphics.LinearGradient(0, 380, 0, 480, 0x00000000, 0x88000000, android.graphics.Shader.TileMode.CLAMP);
        Hud(Context c) { super(c); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); }

        @Override protected void onDraw(Canvas c) {
            float s = getWidth() / 800f;
            c.save(); c.scale(s, getHeight() / 480f);
            // scrims so the text reads over a bright sky / floor
            p.setShader(top); c.drawRect(0, 0, 800, 90, p);
            if (chipsShown) { p.setShader(bottom); c.drawRect(0, 320, 800, 480, p); }
            p.setShader(null);
            // back + title
            panel(c, BACK_L, BACK_T, BACK_W, BACK_H, false);
            p.setColor(WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.5f); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(BACK_L + 28, BACK_T + 10, BACK_L + 19, BACK_T + 20, p); c.drawLine(BACK_L + 19, BACK_T + 20, BACK_L + 28, BACK_T + 30, p);
            p.setStyle(Paint.Style.FILL);
            text(c, UiText.t("내 차"), 72, 32, 22, WHITE, Paint.Align.LEFT);
            text(c, status(), 72, 52, 13, state.live ? BLUE : MUTED, Paint.Align.LEFT);
            // backdrop
            panel(c, BG_X, VIEW_T, BG_W, VIEW_H, sceneOpen);
            text(c, UiText.t("배경"), BG_X + BG_W / 2, VIEW_T + 26, 15, WHITE, Paint.Align.CENTER);
            // view presets
            for (int i = 0; i < VIEWS.length; i++) {
                float x = VIEW_L + i * (VIEW_W + 6);
                panel(c, x, VIEW_T, VIEW_W, VIEW_H, false);
                text(c, UiText.t(VIEW_NAMES[i]), x + VIEW_W / 2, VIEW_T + 26, 15, WHITE, Paint.Align.CENTER);
            }
            // factory colour swatch
            p.setColor(PANEL); c.drawCircle(SWATCH_X, SWATCH_Y, SWATCH_R + 4, p);
            p.setColor(CarPaint.RGB[paint]); c.drawCircle(SWATCH_X, SWATCH_Y, SWATCH_R, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(EDGE); c.drawCircle(SWATCH_X, SWATCH_Y, SWATCH_R + 4, p); p.setStyle(Paint.Style.FILL);
            if (paletteOpen) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setColor(BLUE); c.drawCircle(SWATCH_X, SWATCH_Y, SWATCH_R + 4, p); p.setStyle(Paint.Style.FILL); }
            // message (loading / failure)
            String msg = failure != null ? failure : !loaded ? UiText.t("불러오는 중…") : null;
            if (msg != null) text(c, msg, 400, 250, 18, failure != null ? RED : MUTED, Paint.Align.CENTER);
            // chips
            if (chipsShown) { for (int i = 0; i < CHIP_TESTS.length; i++) chip(c, i); for (int i = 0; i < OPEN_TESTS.length; i++) openChip(c, i); }
            if (paletteOpen) palette(c);
            if (sceneOpen) scenes(c);
            c.restore();
        }

        /** The backdrops in a list under the 배경 button. */
        private void scenes(Canvas c) {
            r.set(BGL_L, PAL_T, BGL_R, PAL_T + 12 + CarScene.COUNT * PAL_ROW);
            p.setColor(0xf0101620); c.drawRoundRect(r, 12, 12, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(EDGE); c.drawRoundRect(r, 12, 12, p); p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < CarScene.COUNT; i++) {
                float top = paletteRow(i), cy = top + PAL_ROW / 2;
                if (i == scene) {
                    r.set(BGL_L + 6, top + 2, BGL_R - 6, top + PAL_ROW - 2);
                    p.setColor(0x334aa3ff); c.drawRoundRect(r, 9, 9, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(BLUE); c.drawRoundRect(r, 9, 9, p); p.setStyle(Paint.Style.FILL);
                }
                float[] top3 = CarScene.SKY_TOP[i], hor = CarScene.SKY_HORIZON[i], fl = CarScene.FLOOR[i];   // a small sky / floor swatch
                r.set(BGL_L + 16, cy - 13, BGL_L + 44, cy); p.setColor(rgb(top3)); c.drawRect(r, p);
                r.set(BGL_L + 16, cy - 4, BGL_L + 44, cy + 2); p.setColor(rgb(hor)); c.drawRect(r, p);
                r.set(BGL_L + 16, cy + 2, BGL_L + 44, cy + 13); p.setColor(rgb(fl)); c.drawRect(r, p);
                text(c, UiText.t(CarScene.NAMES[i]), BGL_L + 56, cy + 6, 16, WHITE, Paint.Align.LEFT);
            }
        }

        private int rgb(float[] c) { return 0xff000000 | Math.round(Math.min(1f, c[0]) * 255) << 16 | Math.round(Math.min(1f, c[1]) * 255) << 8 | Math.round(Math.min(1f, c[2]) * 255); }

        /** The six factory colours in a list under the swatch; the current one is outlined. */
        private void palette(Canvas c) {
            r.set(PAL_L, PAL_T, PAL_R, PAL_T + 12 + CarPaint.COUNT * PAL_ROW);
            p.setColor(0xf0101620); c.drawRoundRect(r, 12, 12, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(EDGE); c.drawRoundRect(r, 12, 12, p); p.setStyle(Paint.Style.FILL);
            boolean ko = "ko".equals(UiText.language());
            for (int i = 0; i < CarPaint.COUNT; i++) {
                float top = paletteRow(i), cy = top + PAL_ROW / 2;
                if (i == paint) {
                    r.set(PAL_L + 6, top + 2, PAL_R - 6, top + PAL_ROW - 2);
                    p.setColor(0x334aa3ff); c.drawRoundRect(r, 9, 9, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(BLUE); c.drawRoundRect(r, 9, 9, p); p.setStyle(Paint.Style.FILL);
                }
                p.setColor(CarPaint.RGB[i]); c.drawCircle(PAL_L + 30, cy, 13, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(EDGE); c.drawCircle(PAL_L + 30, cy, 13, p); p.setStyle(Paint.Style.FILL);
                text(c, ko ? CarPaint.NAME_KO[i] : CarPaint.NAME_EN[i], PAL_L + 54, cy + 6, 16, WHITE, Paint.Align.LEFT);
                text(c, CarPaint.CODE[i] + ("T5R".equals(CarPaint.CODE[i]) ? (ko ? " · 무광" : " · matte") : ""), PAL_R - 14, cy + 5, 12, MUTED, Paint.Align.RIGHT);
            }
        }

        private String status() {
            String g = Float.isNaN(gLong) || Float.isNaN(gLat) ? "" : String.format(java.util.Locale.US, " · G %+.2f / %+.2f", gLong, gLat);
            if (!state.live) return (feedAvailable ? UiText.t("차량 신호 없음 · 화면을 누르면 시험 버튼") : UiText.t("미리보기 · 화면을 누르면 시험 버튼")) + g;
            StringBuilder b = new StringBuilder(UiText.t("실시간"));
            if (!Float.isNaN(state.steerDeg)) b.append(" · ").append(UiText.t("핸들")).append(' ').append(signed(state.steerDeg)).append('°');
            b.append(" · ").append(UiText.t("앞바퀴")).append(' ').append(String.format(java.util.Locale.US, "%+.1f°", -state.wheelDeg));
            if (!Float.isNaN(state.speedKmh)) b.append(" · ").append(Math.round(state.speedKmh)).append(" km/h");
            return b.append(g).toString();
        }

        private String signed(float v) { int r = Math.round(v); return r > 0 ? "+" + r : String.valueOf(r); }

        private void chip(Canvas c, int i) {
            float x = chipX(i), w = chipW();
            int test = CHIP_TESTS[i];
            boolean on; int color;
            switch (test) {
                case CarState.TEST_LEFT: on = state.left; color = AMBER; break;
                case CarState.TEST_RIGHT: on = state.right; color = AMBER; break;
                case CarState.TEST_HAZARD: on = state.hazard && (state.left || state.right); color = AMBER; break;
                case CarState.TEST_TAIL: on = state.tail; color = TAILC; break;
                case CarState.TEST_HEAD: on = state.head; color = HEADC; break;
                case CarState.TEST_STOP: on = state.stop; color = RED; break;
                case CarState.TEST_REVERSE: on = state.reverse; color = REVC; break;
                default: on = Math.abs(state.wheelDeg) > 0.5f; color = BLUE;
            }
            boolean active = test == CarState.TEST_LEFT ? state.leftActive : test == CarState.TEST_RIGHT ? state.rightActive
                : test == CarState.TEST_HAZARD ? state.hazard : on;
            r.set(x, CHIP_Y, x + w, CHIP_Y + CHIP_H);
            p.setColor(on ? (color & 0x00ffffff) | 0x38000000 : PANEL); c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(active ? 2.5f : 1.2f); p.setColor(active ? color : EDGE);
            c.drawRoundRect(r, 10, 10, p); p.setStyle(Paint.Style.FILL);
            int fg = on ? color : active ? color : MUTED;
            float cy = CHIP_Y + CHIP_H / 2;
            if (test == CarState.TEST_LEFT || test == CarState.TEST_RIGHT) {
                boolean left = test == CarState.TEST_LEFT;
                float ax = left ? x + 20 : x + w - 20, d = left ? -1 : 1;
                path.reset(); path.moveTo(ax + d * 9, cy); path.lineTo(ax - d * 6, cy - 10); path.lineTo(ax - d * 6, cy + 10); path.close();
                p.setColor(fg); c.drawPath(path, p);
                fit(c, UiText.t(CHIP_NAMES[i]), left ? x + w / 2 + 10 : x + w / 2 - 10, cy + 6, w - 34, fg);
            } else fit(c, UiText.t(CHIP_NAMES[i]), x + w / 2, cy + 6, w - 10, fg);
            if ((tests & test) != 0) {   // a test button held on
                p.setColor(BLUE); c.drawCircle(x + w - 9, CHIP_Y + 9, 4, p);
            }
        }

        /** A door / bonnet / hatch / mirror button: lit while that part is open (or folded), outlined while it moves. */
        private void openChip(Canvas c, int i) {
            float x = openX(i), w = openW();
            float o = state.open[OPEN_PART[i]];
            boolean on = o > 0.5f, held = (tests & OPEN_TESTS[i]) != 0, moving = o > 0 && o < 1;
            r.set(x, OPEN_Y, x + w, OPEN_Y + CHIP_H);
            p.setColor(on ? 0x384aa3ff : PANEL); c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(held || moving ? 2.5f : 1.2f); p.setColor(held || moving ? BLUE : EDGE);
            c.drawRoundRect(r, 10, 10, p); p.setStyle(Paint.Style.FILL);
            fit(c, UiText.t(OPEN_NAMES[i]), x + w / 2, OPEN_Y + CHIP_H / 2 + 6, w - 10, on ? BLUE : held ? WHITE : MUTED);
        }

        private void panel(Canvas c, float x, float y, float w, float h, boolean lit) {
            r.set(x, y, x + w, y + h);
            p.setColor(PANEL); c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(lit ? BLUE : EDGE); c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.FILL);
        }

        /** Centred chip label, shrunk to fit its width. */
        private void fit(Canvas c, String t, float x, float y, float width, int color) {
            float size = 16; p.setTextSize(size);
            while (size > 10 && p.measureText(t) > width) { size -= 0.5f; p.setTextSize(size); }
            text(c, t, x, y, size, color, Paint.Align.CENTER);
        }

        private void text(Canvas c, String t, float x, float y, float size, int color, Paint.Align align) {
            p.setTextSize(size); p.setColor(color); p.setTextAlign(align);
            p.setShadowLayer(3, 0, 1, 0xc0000000);
            c.drawText(t, x, y, p);
            p.clearShadowLayer();
        }

        @Override public boolean onTouchEvent(MotionEvent e) { return touch(e, getWidth() / 800f, getHeight() / 480f); }
    }

    // ---- touch -------------------------------------------------------------------------------------------------------
    private boolean touch(MotionEvent e, float sx, float sy) {
        long now = SystemClock.elapsedRealtime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true; dragging = false; pinching = false; yawVel = pitchVel = 0;
                lastX = downX = e.getX(); lastY = downY = e.getY(); downAt = now;
                removeCallbacks(this); post(this);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) { pinching = true; pinchStart = spread(e); distStart = dist; }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float sp = spread(e);
                    if (pinchStart > 10) dist = Math.max(4.0f, Math.min(12f, distStart * pinchStart / sp));
                    return true;
                }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * sx && !onControl(downX / sx, downY / sy)) dragging = true;
                if (dragging) {
                    float dts = Math.max(0.008f, (now - downAt) / 1000f); downAt = now;
                    float dyaw = -dx / sx * 0.45f, dpitch = dy / sy * 0.3f;
                    yaw += dyaw; pitch = clampPitch(pitch + dpitch);
                    yawVel = yawVel * 0.5f + dyaw / dts * 0.5f; pitchVel = pitchVel * 0.5f + dpitch / dts * 0.5f;
                }
                lastX = e.getX(); lastY = e.getY();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP:
                touchDown = false;
                if (!dragging && !pinching) {
                    tap(e.getX() / sx, e.getY() / sy, now);
                    yawVel = pitchVel = 0;
                } else if (now - downAt > 80) { yawVel = pitchVel = 0; }   // released after holding still: no fling
                dragging = pinching = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchDown = dragging = pinching = false; yawVel = pitchVel = 0;
                return true;
        }
        return false;
    }

    private static float spread(MotionEvent e) { return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }

    private boolean onControl(float x, float y) {
        if (paletteOpen && x >= PAL_L && x <= PAL_R && y >= PAL_T && y <= PAL_T + 12 + CarPaint.COUNT * PAL_ROW) return true;
        if (sceneOpen && x >= BGL_L && x <= BGL_R && y >= PAL_T && y <= PAL_T + 12 + CarScene.COUNT * PAL_ROW) return true;
        return chipsShown && y >= OPEN_Y - 4 || (y <= VIEW_T + VIEW_H + 4 && (x <= BACK_L + BACK_W + 4 || x >= BG_X - 4));
    }

    private void tap(float x, float y, long now) {
        if (sceneOpen) {   // pick a backdrop, or tap anywhere else to close the list
            sceneOpen = false;
            if (x >= BGL_L && x <= BGL_R) for (int i = 0; i < CarScene.COUNT; i++) {
                float top = paletteRow(i);
                if (y >= top && y < top + PAL_ROW) {
                    scene = i; loadBackdrop(getContext().getApplicationContext(), i);
                    prefs.edit().putInt("scene", i).apply();
                    gl.requestRender();
                    return;
                }
            }
            if (x >= BG_X && x <= BG_X + BG_W && y >= VIEW_T && y <= VIEW_T + VIEW_H) return;
            if (x >= BGL_L && x <= BGL_R && y >= PAL_T && y <= PAL_T + 12 + CarScene.COUNT * PAL_ROW) return;
            return;
        }
        if (x >= BG_X && x <= BG_X + BG_W && y >= VIEW_T && y <= VIEW_T + VIEW_H) { paletteOpen = false; sceneOpen = true; return; }
        if (paletteOpen) {   // pick a colour, or tap anywhere else to close the list
            paletteOpen = false;
            if (x >= PAL_L && x <= PAL_R) for (int i = 0; i < CarPaint.COUNT; i++) {
                float top = paletteRow(i);
                if (y >= top && y < top + PAL_ROW) {
                    paint = i; applyPaint();
                    prefs.edit().putInt("paint", paint).apply();
                    gl.requestRender();
                    return;
                }
            }
            if (Math.hypot(x - SWATCH_X, y - SWATCH_Y) <= SWATCH_R + 8) return;
            return;   // a tap elsewhere only closes the list
        }
        if (x >= BACK_L && x <= BACK_L + BACK_W && y >= BACK_T && y <= BACK_T + BACK_H) { if (onExit != null) onExit.run(); return; }
        for (int i = 0; i < VIEWS.length; i++) {
            float vx = VIEW_L + i * (VIEW_W + 6);
            if (x >= vx && x <= vx + VIEW_W && y >= VIEW_T && y <= VIEW_T + VIEW_H) { yaw = VIEWS[i][0]; pitch = VIEWS[i][1]; dist = VIEWS[i][2]; return; }
        }
        if (Math.hypot(x - SWATCH_X, y - SWATCH_Y) <= SWATCH_R + 8) {
            paletteOpen = true; sceneOpen = false;
            return;
        }
        if (chipsShown && y >= OPEN_Y && y <= OPEN_Y + CHIP_H) {
            for (int i = 0; i < OPEN_TESTS.length; i++) if (x >= openX(i) && x <= openX(i) + openW()) { tests ^= OPEN_TESTS[i]; return; }
            return;
        }
        if (chipsShown && y >= CHIP_Y && y <= CHIP_Y + CHIP_H) {
            for (int i = 0; i < CHIP_TESTS.length; i++) if (x >= chipX(i) && x <= chipX(i) + chipW()) {
                int t = CHIP_TESTS[i];
                tests ^= t;
                if ((tests & t) != 0) {   // one turn signal at a time, like the stalk
                    if (t == CarState.TEST_LEFT) tests &= ~CarState.TEST_RIGHT;
                    if (t == CarState.TEST_RIGHT) tests &= ~CarState.TEST_LEFT;
                }
                return;
            }
            return;
        }
        chipsShown = !chipsShown;   // a tap on the car / empty screen shows or hides the test buttons
    }

    /** Test buttons are for this visit only. */
    void clearTests() { tests = 0; }
}
