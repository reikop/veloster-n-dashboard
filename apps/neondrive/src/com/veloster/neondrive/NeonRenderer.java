package com.veloster.neondrive;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.Random;

/**
 * Neon city-pop highway, drawn in an 800x480 logical space.
 * Static scenery is baked once into bitmaps (software canvas, so clipping and
 * gradients behave the same everywhere); each frame only moves lines, sprites
 * and lights, which keeps the API17 hardware renderer on its fast paths.
 *
 * The ground is an old-school scanline road ({@link RoadRenderer}): straight up to the car,
 * bending beyond it with the steering. The skyline wraps and scrolls with the heading.
 */
public final class NeonRenderer {
    static final float W = 800, H = 480, HY = 280, VPX = 400;
    static final float CAR_X = 400, CAR_Y = 468;
    /** Logical pixels per sprite pixel. */
    static final float SPRITE_K = 2f;
    /** Roadside objects: spacing along the road and offset from its centre (m), heights (m). */
    static final float OBJECT_GAP_M = 16f, OBJECT_LAT_M = 7.4f, PALM_M = 8f, LAMP_M = 6.5f, OBJECT_FAR_M = 420f;
    /** The sun only drifts a little toward the outside of a bend; the skyline scrolls freely. */
    static final float SUN_PAN = 70f;

    private Bitmap palm, lamp, carSheet;
    private final Bitmap[] skyT = new Bitmap[Theme.COUNT], sunT = new Bitmap[Theme.COUNT], cityT = new Bitmap[Theme.COUNT];
    /** Coast scenery (morning, day): sea and islands band, tropical palms. City (night, sunset): skyline, neon palms, lamps. */
    private final Bitmap[] coastT = new Bitmap[Theme.COUNT];
    private Bitmap coastPalm;
    /** Sea strip height above the horizon (logical px). */
    static final float SEA_H = 16;
    /** Coast palms: spacing (m), offset from the road centre (m), base height (m). */
    static final float COAST_GAP_M = 11f, COAST_LAT_M = 7.6f, COAST_PALM_M = 8f;
    /** Time-of-day blend: showing themeFrom fading into themeTo by themeU (0..1, about 1.8 s). */
    private int themeFrom = -1, themeTo, themeTarget;
    private float themeU = 1f;
    /** Theme being baked (selects colours in the bake methods). */
    private int bakeTheme;
    private long lastNow = -1;
    private int themeMode;
    private RoadRenderer road;
    private Bitmap glowRed, glowAmber, glowFlame, glowCore, glowWhite, glowPink;
    private float baked = -1;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** No FILTER_BITMAP_FLAG: nearest-neighbour scaling keeps sprite pixels square. */
    private final Paint pixel = new Paint();
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final float[] starX = new float[90], starY = new float[90], starPhase = new float[90], starRate = new float[90];
    private final float[] beaconX = new float[32], beaconY = new float[32];
    private int beacons;
    private final Random flicker = new Random(7);
    private boolean hud = true;
    // Settings UI state (owned by the view, drawn here so it shares the 800x480 layout).
    private float gearAlpha;
    private boolean panelOpen, demoOn;
    private int paint;
    private float viewScale = 1, viewOffX, viewOffY;
    private final boolean korean = "ko".equals(java.util.Locale.getDefault().getLanguage());
    public static final int HIT_NONE = 0, HIT_GEAR = 1, HIT_HUD = 2, HIT_DEMO = 3, HIT_CLOSE = 4, HIT_OUTSIDE = 5, HIT_PANEL = 6, HIT_CLOCK = 7, HIT_MEDIA = 8, HIT_TRIP = 9, HIT_EXIT = 10, HIT_PAINT = 100, HIT_THEME = 200, HIT_GEARMODE = 300;
    static final float PX0 = 170, PY0 = 24, PX1 = 630, PY1 = 414, GEAR_X = 766, GEAR_Y = 30, SWATCH_Y = 334;
    private final Typeface retro = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC);

    public NeonRenderer() {
        Random rnd = new Random(1984);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat() * W;
            starY[i] = 6 + (float) Math.pow(rnd.nextFloat(), 1.4) * 150;
            starPhase[i] = rnd.nextFloat() * 6.28f;
            starRate[i] = 0.6f + rnd.nextFloat() * 2.2f;
        }
        text.setTypeface(retro);
    }

    public void setHud(boolean on) { hud = on; }

    // Now playing (right side): set by the view each frame from the OEM media widget broadcasts.
    private String mediaTitle = "", mediaArtist = "";
    private float mediaProgress = -1f;
    private Bitmap mediaArt, mediaArtSource;
    /** Album art is shown pixelated to match the sprites: downsampled to ART_PX and drawn nearest-neighbour. */
    static final int ART_PX = 32;
    static final float ART_SIZE = 64;
    public void setMedia(String title, String artist, float progress, Bitmap art) {
        mediaTitle = title == null ? "" : title; mediaArtist = artist == null ? "" : artist; mediaProgress = progress;
        if (art != mediaArtSource) {
            mediaArtSource = art;
            mediaArt = null;
            if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
                Bitmap small = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888);
                Canvas sc = new Canvas(small);
                int side = Math.min(art.getWidth(), art.getHeight());   // centre square crop
                Rect from = new Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2, (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
                // Own opaque filtering paint: `sprite` carries whatever alpha the last scenery layer used.
                sc.drawBitmap(art, from, new RectF(0, 0, ART_PX, ART_PX), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                mediaArt = small;
            }
        }
    }
    /** Gear display: off, 6-speed manual H gate, or 8-speed DCT (P R N D + gear). */
    public static final int GEARS_OFF = 0, GEARS_MANUAL = 1, GEARS_DCT = 2;
    public static final String[] GEARS_KO = {"끄기", "수동 6단", "자동 8단"}, GEARS_EN = {"Off", "6-spd MT", "8-spd DCT"};
    private int gearMode = GEARS_MANUAL;
    /** Shown under 기어 표시: the DCT was guessed from the car (TCU frames / 7th-8th gear), which is unconfirmed. */
    private boolean dctGuessed;
    public void setDctGuessed(boolean on) { dctGuessed = on; }
    public void setGearMode(int mode) { gearMode = mode < GEARS_OFF || mode > GEARS_DCT ? GEARS_MANUAL : mode; }
    public int gearMode() { return gearMode; }
    private boolean mediaOn = true;
    private boolean tripOn = true;
    /** Trip bar (time, distance) at the top on/off. */
    public void setTripOn(boolean on) { tripOn = on; }
    public boolean tripOn() { return tripOn; }
    /** Now playing card on/off. */
    public void setMediaOn(boolean on) { mediaOn = on; }
    public boolean mediaOn() { return mediaOn; }

    private boolean clockOn;
    private String clockTime = "", clockSuffix = "", clockDate = "";
    /** Clock in the top-left corner, independent of the HUD. Strings are formatted by the view. */
    public void setClock(boolean on, String time, String suffix, String date) {
        clockOn = on; clockTime = time; clockSuffix = suffix; clockDate = date;
    }
    public boolean clock() { return clockOn; }

    /** Inside the N dashboard: a back button left of the gear (shown with it) returns to the dashboard. */
    static final float EXIT_X = GEAR_X, EXIT_Y = GEAR_Y + 56;   // under the gear, clear of the HUD numbers
    private boolean exitButton;
    public void setExitButton(boolean on) { exitButton = on; }
    public void setUi(float gearAlpha, boolean panelOpen, int paint, boolean demo, int themeMode) {
        this.gearAlpha = gearAlpha; this.panelOpen = panelOpen; this.paint = paint; this.demoOn = demo; this.themeMode = themeMode;
    }

    private String sunrise = "--:--", sunset = "--:--";
    private double sunAlt = Double.NaN, sunLat, sunLon;
    private int sunSource;
    /** Today's sunrise/sunset (local HH:mm), sun altitude and where it was computed (0 Seoul, 1 saved fix, 2 GPS). */
    public void setSunInfo(String sunrise, String sunset, double altitude, int source, double lat, double lon) {
        this.sunrise = sunrise; this.sunset = sunset; this.sunAlt = altitude; this.sunSource = source; this.sunLat = lat; this.sunLon = lon;
    }

    /** Scene time of day ({@link Theme}), resolved by the view from the setting and the clock against sunrise/sunset. */
    public void setTheme(int theme) { themeTarget = Math.max(0, Math.min(Theme.COUNT - 1, theme)); }
    /** Weight of a theme in the current blend. */
    float weight(int theme) {
        if (themeFrom == themeTo) return theme == themeTo ? 1f : 0f;
        return (theme == themeFrom ? 1f - themeU : 0f) + (theme == themeTo ? themeU : 0f);
    }
    float mixed(float[] perTheme) { return perTheme[themeFrom] * (1f - themeU) + perTheme[themeTo] * themeU; }
    private boolean dayProps() { return Theme.daylitProps(bakeTheme); }

    /** Maps a touch in view pixels to a settings control. */
    public int hit(float vx, float vy) {
        float x = (vx - viewOffX) / viewScale, y = (vy - viewOffY) / viewScale;
        if (!panelOpen) {
            if (exitButton && gearAlpha > 0.05f && Math.abs(x - EXIT_X) < 30 && Math.abs(y - EXIT_Y) < 26) return HIT_EXIT;
            return gearAlpha > 0.05f && Math.abs(x - GEAR_X) < 30 && Math.abs(y - GEAR_Y) < 30 ? HIT_GEAR : HIT_NONE;
        }
        if (x < PX0 || x > PX1 || y < PY0 || y > PY1) return HIT_OUTSIDE;
        if (x > 584 && y < 80) return HIT_CLOSE;
        if (y >= 76 && y < 116) return x < 390 ? HIT_HUD : HIT_CLOCK;
        if (y >= 116 && y < 156) return x < 390 ? HIT_DEMO : HIT_MEDIA;
        if (y >= 156 && y < 196) return x < 390 ? HIT_TRIP : HIT_PANEL;
        if (y >= 196 && y < 236) {
            for (int i = 0; i < GEARS_KO.length; i++) if (x >= 330 + i * 94 && x < 330 + (i + 1) * 94) return HIT_GEARMODE + i;
            return HIT_PANEL;
        }
        if (y >= 236 && y < 276) {
            for (int i = 0; i < Theme.MODE_KO.length; i++) if (x >= 330 + i * 56 && x < 330 + (i + 1) * 56) return HIT_THEME + i;
            return HIT_PANEL;
        }
        for (int i = 0; i < CarPaint.COUNT; i++) {
            float sx = swatchX(i);
            if ((x - sx) * (x - sx) + (y - SWATCH_Y) * (y - SWATCH_Y) < 32 * 32) return HIT_PAINT + i;
        }
        return HIT_PANEL;
    }

    static float swatchX(int i) { return 220 + i * 72; }
    public boolean hud() { return hud; }

    // ---------------------------------------------------------------- projection
    static float frac(double v) { return (float) (v - Math.floor(v)); }
    static int argb(float a, int rgb) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xffffff); }
    // ---------------------------------------------------------------- frame
    public void draw(Canvas c, int width, int height, DriveModel.State s) {
        float scale = Math.min(width / W, height / H);
        if (scale != baked) bake(scale);
        c.drawColor(0xff05010d);
        int save = c.save();
        viewScale = scale; viewOffX = (width - W * scale) / 2; viewOffY = (height - H * scale) / 2;
        c.translate(viewOffX, viewOffY);
        c.scale(scale, scale);
        c.clipRect(0, 0, W, H);
        float t = s.now / 1000f;
        road.setCurvature(s.bend * DriveModel.CURVE_MAX);
        if (themeFrom < 0 || lastNow < 0) { themeFrom = themeTo = themeTarget; themeU = 1f; }
        else if (themeTarget != themeTo) {
            themeFrom = themeU < 0.5f ? themeFrom : themeTo;   // continue from what is mostly on screen
            themeTo = themeTarget; themeU = 0f;
        } else if (themeU < 1f) themeU = Math.min(1f, themeU + (s.now - lastNow) / 1800f);
        if (themeU >= 1f) themeFrom = themeTo;
        lastNow = s.now;
        float u = themeFrom == themeTo ? 0f : themeU;
        float glowLevel = mixed(Theme.GLOW), starLevel = mixed(Theme.STARS);
        road.render(s.distance * DriveModel.METRES_PER_UNIT, Theme.ROAD[themeFrom], Theme.ROAD[themeTo], u);
        // Heading turns the whole sky: one full screen per F px of rotation, wrapped.
        float scroll = (float) (-s.heading * RoadRenderer.F);
        scroll -= (float) Math.floor(scroll / W) * W;
        float sunX = -s.bend * SUN_PAN;
        drawImage(c, skyT[themeFrom], 0, 0, W, HY, 1f);
        if (u > 0) drawImage(c, skyT[themeTo], 0, 0, W, HY, u);
        if (starLevel > 0.01f) drawStars(c, t, scroll, starLevel);
        drawImage(c, sunT[themeFrom], sunX, 0, W + sunX, HY, 1f - u);
        if (u > 0) drawImage(c, sunT[themeTo], sunX, 0, W + sunX, HY, u);
        drawBand(c, themeFrom, scroll, 1f - u);
        if (u > 0) drawBand(c, themeTo, scroll, u);
        if (Theme.daylitProps(themeFrom)) drawSunGlint(c, themeFrom, sunX, t, 1f - u);
        if (u > 0 && Theme.daylitProps(themeTo)) drawSunGlint(c, themeTo, sunX, t, u);
        float beaconLevel = weight(Theme.NIGHT) + 0.6f * weight(Theme.SUNSET);
        if (beaconLevel > 0.01f) drawBeacons(c, t, scroll, beaconLevel);
        drawIdleScenery(c, s, t);   // ambient sky and sea, behind the road and palms
        r.set(0, HY, W, H);
        pixel.setAlpha(255);
        c.drawBitmap(road.bitmap, null, r, pixel);
        drawHorizon(c, weight(Theme.NIGHT) + 0.5f * weight(Theme.SUNSET));
        drawRoadside(c, s);
        drawStreaks(c, s, t);
        drawCar(c, s, t);
        if (hud) { drawHud(c, s); if (tripOn) drawTripBar(c, s); }
        if (clockOn) drawClock(c);
        if (!clockOn && !hud) drawModeLabel(c, s, 24);
        drawUi(c);
        c.restoreToCount(save);
    }

    /** Horizon band of a theme (coast or city), wrapped with the heading. */
    private void drawBand(Canvas c, int theme, float scroll, float alpha) {
        Bitmap b = Theme.daylitProps(theme) ? coastT[theme] : cityT[theme];
        drawImage(c, b, scroll - W, 0, scroll, HY, alpha);
        drawImage(c, b, scroll, 0, scroll + W, HY, alpha);
    }

    private void drawImage(Canvas c, Bitmap b, float l, float top, float rr, float bottom, float alpha) {
        r.set(l, top, rr, bottom);
        sprite.setAlpha((int) (255 * Math.max(0, Math.min(1, alpha))));
        c.drawBitmap(b, null, r, sprite);
    }

    private void glow(Canvas c, Bitmap b, float cx, float cy, float w, float h, float alpha) {
        if (alpha <= 0.01f) return;
        drawImage(c, b, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, alpha);
    }

    private void drawStars(Canvas c, float t, float pan, float vis) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < starX.length; i++) {
            float a = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * starRate[i] + starPhase[i]));
            a *= (1f - starY[i] / 190f) * vis;
            p.setColor(argb(a, i % 7 == 0 ? 0x9ff6ff : 0xffe6ff));
            float sz = i % 11 == 0 ? 2.2f : 1.3f;
            float sx = starX[i] + pan;
            sx -= (float) Math.floor(sx / W) * W;
            c.drawRect(sx, starY[i], sx + sz, starY[i] + sz, p);
        }
    }

    private void drawBeacons(Canvas c, float t, float pan, float vis) {
        for (int i = 0; i < beacons; i++) {
            float phase = frac(t * 0.8 + i * 0.37);
            float a = (phase < 0.18f ? 1f : 0.15f) * vis;
            float bx = beaconX[i] + pan;
            bx -= (float) Math.floor(bx / W) * W;
            glow(c, glowRed, bx, beaconY[i], 14, 14, a);
            p.setColor(argb(a, 0xff5a6e));
            c.drawRect(bx - 1, beaconY[i] - 1, bx + 1, beaconY[i] + 1, p);
        }
    }

    private void drawHorizon(Canvas c, float night) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(7); p.setColor(argb(0.33f * night, 0xff3d9a)); c.drawLine(0, HY, W, HY, p);
        p.setStrokeWidth(2); p.setColor(mix(0x66ffffff, 0xffff8ad0, night)); c.drawLine(0, HY, W, HY, p);
    }

    /** Palms and lamp posts every OBJECT_GAP_M metres, projected with the same curve as the road. */
    private void drawRoadside(Canvas c, DriveModel.State s) {
        float d = weight(Theme.MORNING) + weight(Theme.DAY);
        if (d > 0f) drawCoastPalms(c, s, d);
        if (d >= 1f) return;
        float city = 1f - d;
        double metres = s.distance * DriveModel.METRES_PER_UNIT;
        float zNear = road.zAt(H + 40);
        long first = (long) Math.ceil((metres - road.carZ + zNear) / OBJECT_GAP_M);
        long last = (long) Math.floor((metres - road.carZ + OBJECT_FAR_M) / OBJECT_GAP_M);
        for (long n = last; n >= first; n--) {
            for (int side = -1; side <= 1; side += 2) {
                float z = (float) (n * OBJECT_GAP_M + (side > 0 ? OBJECT_GAP_M / 2 : 0) - metres + road.carZ);
                if (z < zNear || z > OBJECT_FAR_M) continue;
                long id = n * 2 + (side > 0 ? 1 : 0);
                boolean isLamp = (id % 3) == 0;
                Bitmap b = isLamp ? lamp : palm;
                float glowLevel = mixed(Theme.GLOW);
                float baseY = road.yAt(z), baseX = road.x(z, side * OBJECT_LAT_M);
                float hgt = RoadRenderer.F * (isLamp ? LAMP_M : PALM_M) / z;
                if (hgt < 4) continue;
                float wid = hgt * (isLamp ? 70f / 300f : 220f / 360f);
                if (baseX + wid < -20 || baseX - wid > W + 20) continue;
                float fade = Math.min(1f, (baseY - HY) / 30f);
                boolean mirror = side < 0;
                // Lamp heads sit on the road side of the pole; palm trunks lean away from the road (as on the coast).
                float off = isLamp ? -side * wid * 0.32f : wid * 0.1f;
                int save = c.save();
                if (mirror) c.scale(-1, 1, baseX + off, baseY);
                fade *= city;
                drawImage(c, b, baseX + off - wid / 2, baseY - hgt, baseX + off + wid / 2, baseY, fade);
                c.restoreToCount(save);
                if (isLamp && glowLevel > 0.01f) {
                    fade *= glowLevel;
                    float hx = baseX + off + (mirror ? 1 : -1) * wid * 0.30f, hy = baseY - hgt * 0.94f;
                    glow(c, glowPink, hx, baseY - 2, wid * 3.2f, hgt * 0.12f, 0.35f * fade);
                    glow(c, glowPink, hx, hy, wid * 1.6f, wid * 1.6f, 0.65f * fade);
                }
            }
        }
    }

    /** Sun position on screen per theme (x, before the bend pan) and its colour on the water. */
    static final float[] GLINT_X = {VPX, VPX - 230, VPX + 210, VPX};
    static final int[] GLINT_C = {0xff5fb8, 0xfff0d0, 0xffffff, 0xffb050};
    static final float[] GLINT_A = {0.75f, 0.7f, 0.55f, 0.9f};

    /** Shimmering reflection of the sun on the sea strip under the horizon. */
    private void drawSunGlint(Canvas c, int theme, float pan, float t, float alpha) {
        if (alpha <= 0.01f) return;
        float x = GLINT_X[theme] + pan, top = HY - SEA_H;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 7; i++) {
            float y = top + 1.5f + i * (SEA_H - 2f) / 7f;
            float w = 10 + i * 6 + 6 * (float) Math.sin(t * 2.3 + i * 1.7);
            float jitter = 3 * (float) Math.sin(t * 1.3 + i * 2.1);
            p.setColor(argb(alpha * GLINT_A[theme] * (0.55f + 0.45f * (float) Math.sin(t * 3.1 + i)), GLINT_C[theme]));
            c.drawRect(x - w / 2 + jitter, y, x + w / 2 + jitter, y + 1.4f, p);
        }
    }

    /** Coast: tall tropical palms close to both road edges, a second row further out, varied heights. */
    private void drawCoastPalms(Canvas c, DriveModel.State s, float alpha) {
        double metres = s.distance * DriveModel.METRES_PER_UNIT;
        float zNear = road.zAt(H + 60);
        long first = (long) Math.ceil((metres - road.carZ + zNear) / COAST_GAP_M) - 1;
        long last = (long) Math.floor((metres - road.carZ + OBJECT_FAR_M) / COAST_GAP_M);
        for (long n = last; n >= first; n--) {
            for (int k = 0; k < 4; k++) {
                int side = (k & 1) == 0 ? -1 : 1;
                boolean outer = k >= 2;
                long id = n * 4 + k;
                int h = hash(id);
                if (outer && (h & 3) != 0) continue;            // the second row is sparse
                float z = (float) (n * COAST_GAP_M + (side > 0 ? COAST_GAP_M * 0.5f : 0) + ((h >> 4) & 7) * 0.4f - metres + road.carZ);
                if (z < zNear || z > OBJECT_FAR_M) continue;
                float lat = outer ? COAST_LAT_M + 5f + ((h >> 8) & 7) * 0.5f : COAST_LAT_M + ((h >> 8) & 3) * 0.25f;
                float baseY = road.yAt(z), baseX = road.x(z, side * lat);
                float hgt = RoadRenderer.F * COAST_PALM_M * (0.9f + ((h >> 12) & 15) / 15f * 0.55f) / z;
                if (hgt < 4) continue;
                float wid = hgt * (220f / 360f);
                if (baseX + wid < -20 || baseX - wid > W + 20) continue;
                float fade = Math.min(1f, (baseY - HY) / 30f);
                // Trunks lean away from the road, like palms bent by the sea wind.
                boolean mirror = side < 0;
                int save = c.save();
                if (mirror) c.scale(-1, 1, baseX, baseY);
                float x0 = baseX - wid * 0.4f;
                drawImage(c, coastPalm, x0, baseY - hgt, x0 + wid, baseY, fade * alpha);
                c.restoreToCount(save);
            }
        }
    }

    static int hash(long v) {
        long x = v * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 32;
        return (int) x & 0x7fffffff;
    }

    private void drawStreaks(Canvas c, DriveModel.State s, float t) {
        float a = Math.min(1f, (s.speedKmh - 80f) / 70f);
        if (a <= 0) return;
        float vx = road.vanishingX();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 22; i++) {
            double angle = (i * 0.618034 % 1.0) * Math.PI * 2;
            float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
            if (sa > 0.25f && Math.abs(ca) < 0.55f) continue; // keep the car clear
            float u = frac(t * (0.5f + s.speedKmh / 140f) + i * 0.137);
            float r0 = 60 + u * u * 520, len = 18 + u * 110 * (0.5f + a);
            p.setStrokeWidth(0.8f + u * 2.2f);
            p.setColor(argb(a * u * 0.55f, i % 3 == 0 ? 0x7df9ff : 0xffd9f4));
            c.drawLine(vx + ca * r0, HY + sa * r0 * 0.75f, vx + ca * (r0 + len), HY + sa * (r0 + len) * 0.75f, p);
        }
    }

    // ---------------------------------------------------------------- car
    /** Pixel-art sprite sheet: rows base, brake lamps, left turn, right turn, wheel phases 0..2 x 7 angles. */
    public void setCarSprites(Bitmap sheet) { carSheet = sheet; }

    /**
     * Turning left swings the nose left, so the car's LEFT flank faces the camera (frames 4..6);
     * turning right shows the right flank (frames 0..2). yaw is +right.
     */
    static int frameFor(float yaw) { return frameForAngle(-yaw * 35f); }

    /** Sprite frame closest to a view angle in degrees (negative shows the right flank). */
    static int frameForAngle(float angle) {
        int best = 0;
        for (int i = 1; i < CarSprite.FRAMES; i++)
            if (Math.abs(CarSprite.ANGLE[i] - angle) < Math.abs(CarSprite.ANGLE[best] - angle)) best = i;
        return best;
    }

    private void drawCar(Canvas c, DriveModel.State s, float t) {
        float sway = (float) Math.sin(t * 0.45) * 2.5f;
        float yaw = s.yaw;
        // Stays in the centre lane; the gentle sway and steering offset fade out at standstill.
        float still = Math.min(1f, s.stoppedFor / 0.8f);
        float cx = CAR_X + (sway + yaw * 12f) * (1f - still);
        // Our own low beams wash the road ahead (toward the bend).
        float gap = CAR_Y - HY, yA = HY + gap * 0.59f, yB = HY + gap * 0.5f, gs = gap / 236f;
        float shiftA = road.x(road.zAt(yA), 0) - VPX, shiftB = road.x(road.zAt(yB), 0) - VPX;
        float night = mixed(Theme.GLOW);
        if (s.lowBeam) glow(c, glowWhite, cx + shiftA, yA, 430 * gs, 120 * gs, 0.16f * night);
        if (s.lamps) glow(c, glowWhite, cx + shiftB, yB, 210 * gs, 50 * gs, 0.08f * night);
        // Showroom: after a few seconds at standstill the view sweeps slowly across all the angles.
        float angle = -yaw * 35f;
        if (s.showroom > 0.01f) angle = angle * (1f - s.showroom) + 38f * (float) Math.sin(t * 0.45) * s.showroom;
        int f = frameForAngle(angle);
        int[] pt = CarSprite.POINTS[f];
        float k = SPRITE_K;
        float carW = CarSprite.CELL_W * k;
        // Idle: a one-pixel engine shake while stopped (not when parked).
        float shake = s.stoppedFor > 0.3f && !s.parked ? (((int) (t * 18)) & 1) : 0;
        float bodyDy = Math.round(s.pitch * 3f + s.bob) + shake;
        float left = Math.round(cx - carW / 2), top = Math.round(CAR_Y - pt[9] * k + bodyDy);
        // Ground shadow and light spill toward the camera.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99000000);
        r.set(cx - carW * 0.46f, CAR_Y - 12, cx + carW * 0.46f, CAR_Y + 10);
        c.drawOval(r, p);
        glow(c, glowRed, cx, CAR_Y + 10, carW * 1.5f, 50, (s.lamps ? 0.14f * (0.3f + 0.7f * night) : 0f) + 0.55f * s.brake);
        float lx = left + pt[0] * k, ly = top + pt[1] * k, rx = left + pt[2] * k, ry = top + pt[3] * k;
        if (s.left) glow(c, glowAmber, lx - 20, CAR_Y + 4, 230, 62, 0.75f);
        if (s.right) glow(c, glowAmber, rx + 20, CAR_Y + 4, 230, 62, 0.75f);
        if (carSheet == null) return;
        int cw = carSheet.getWidth() / CarSprite.FRAMES, ch = carSheet.getHeight() / CarSprite.ROWS;
        spriteRow(c, f, 0, cw, ch, left, top, k, 1f);
        spriteRow(c, f, CarSprite.WHEEL_ROW + (int) (((long) Math.floor(s.wheel)) % CarSprite.WHEEL_PHASES), cw, ch, left, top, k, 1f);
        // Lamps: unlit (dark lens) or tail lamps on (medium red, no high-mount stop lamp), following the real switch.
        // Brake: full brightness including the high-mount stop lamp.
        if (s.brake < 0.98f) spriteRow(c, f, s.lamps ? CarSprite.TAIL_ROW : CarSprite.UNLIT_ROW, cw, ch, left, top, k, 1f);
        if (s.brake > 0.01f) spriteRow(c, f, 1, cw, ch, left, top, k, s.brake);
        if (s.left) spriteRow(c, f, 2, cw, ch, left, top, k, 1f);
        if (s.right) spriteRow(c, f, 3, cw, ch, left, top, k, 1f);
        // Lamp glows and the spoiler stop lamp.
        float glowA = ((s.lamps ? 0.75f * (0.4f + 0.6f * night) : 0f) + 0.85f * s.brake) * (0.45f + 0.55f * s.brake);
        glow(c, glowRed, lx, ly, 120 + 60 * s.brake, 56 + 26 * s.brake, glowA);
        glow(c, glowRed, rx, ry, 120 + 60 * s.brake, 56 + 26 * s.brake, glowA);
        // High-mount stop lamp: its pixels are part of the brake layer; add a glow where the sprite has it.
        if (s.brake > 0.02f && pt[10] >= 0) glow(c, glowRed, left + pt[10] * k, top + pt[11] * k, 90, 26, s.brake);
        if (s.left) { glow(c, glowAmber, lx, ly, 150, 96, 0.85f); glow(c, glowWhite, lx, ly, 22, 12, 0.8f); }
        if (s.right) { glow(c, glowAmber, rx, ry, 150, 96, 0.85f); glow(c, glowWhite, rx, ry, 22, 12, 0.8f); }
        drawExhaust(c, s, left + pt[4] * k, top + pt[5] * k, left + pt[6] * k, top + pt[7] * k);
        drawIdleExhaust(c, s, t, left + pt[4] * k, top + pt[5] * k, left + pt[6] * k, top + pt[7] * k);
    }

    /** One sprite cell scaled without filtering so the pixels stay crisp. */
    private void spriteRow(Canvas c, int frame, int row, int cw, int ch, float left, float top, float k, float alpha) {
        if (alpha <= 0.01f) return;
        src.set(frame * cw, row * ch, (frame + 1) * cw, (row + 1) * ch);
        r.set(left, top, left + CarSprite.CELL_W * k, top + CarSprite.CELL_H * k);
        pixel.setAlpha((int) (255 * Math.min(1f, alpha)));
        c.drawBitmap(carSheet, src, r, pixel);
    }

    private void drawExhaust(Canvas c, DriveModel.State s, float x0, float y0, float x1, float y1) {
        float f = Math.max(0f, (s.throttle - 0.55f) / 0.45f);
        for (int side = 0; side < 2; side++) {
            float ex = side == 0 ? x0 : x1, ey = side == 0 ? y0 : y1;
            if (f > 0.02f) {
                float k = 0.75f + flicker.nextFloat() * 0.5f;
                glow(c, glowFlame, ex, ey + 6, (40 + 46 * f) * k, (30 + 30 * f) * k, 0.55f + 0.45f * f);
                glow(c, glowCore, ex, ey + 2, (16 + 12 * f) * k, (10 + 7 * f) * k, 0.8f * f);
            }
            if (s.pop > 0) {
                float burst = s.pop * (0.4f + flicker.nextFloat() * 0.8f);
                if (flicker.nextFloat() < 0.55f) {
                    glow(c, glowFlame, ex, ey + 8, 84 * burst + 20, 60 * burst + 14, Math.min(1f, burst + 0.2f));
                    glow(c, glowWhite, ex, ey + 4, 28 * burst, 18 * burst, burst);
                }
            }
        }
    }

    // ---------------------------------------------------------------- standstill
    /** Fade-in of the standstill effects: starts after 1.5 s stopped, full after 3 s. */
    static float idleLevel(DriveModel.State s) { return Math.max(0f, Math.min(1f, (s.stoppedFor - 1.5f) / 1.5f)); }

    /**
     * Ambient scenery behind the road and palms, always on: gulls and rolling foam on the coast by day,
     * shooting stars by night; fireworks over the city only while parked after dark.
     */
    private void drawIdleScenery(Canvas c, DriveModel.State s, float t) {
        float amb = 1f;
        float day = weight(Theme.MORNING) + weight(Theme.DAY), dark = 1f - day;
        p.setStyle(Paint.Style.FILL);
        if (day > 0.01f) {
            // Foam lines rolling across the sea strip toward the beach.
            float top = HY - SEA_H;
            for (int j = 0; j < 4; j++) {
                float u = frac(t * 0.22 + j / 4.0);
                float y = top + 2 + u * (SEA_H - 3);
                p.setColor(argb(amb * day * 0.55f * (0.3f + 0.7f * u), 0xffffff));
                float off = (j * 53 + t * 9) % 30;
                for (float x = -30 + off; x < W; x += 30) {
                    if (((int) ((x - off) / 30) + j) % 3 == 0) continue;
                    c.drawRect(x, y, x + 12 + 6 * u, y + 1.2f, p);
                }
            }
            // Gulls gliding across the sky: pixel "v" shapes with flapping wings.
            for (int i = 0; i < 3; i++) {
                float gx = -40 + frac(t / (16 + i * 3) + i * 0.37) * (W + 80);
                float gy = 74 + i * 26 + (float) Math.sin(t * 1.3 + i) * 6;
                float flap = (float) Math.sin(t * 7 + i * 2) * 3;
                p.setColor(argb(amb * day * 0.9f, 0x3a3a4c));
                for (int w = 0; w < 5; w++) {
                    float dy = w * 0.8f - (w == 4 ? flap * 0.3f : 0) - flap * w / 5f;
                    c.drawRect(gx - 2 - w * 2, gy - dy, gx - w * 2, gy - dy + 2, p);
                    c.drawRect(gx + w * 2, gy - dy, gx + 2 + w * 2, gy - dy + 2, p);
                }
            }
        }
        if (dark > 0.01f) {
            // A shooting star every few seconds.
            float period = 5.5f, ph = frac(t / period);
            if (ph < 0.22f) {
                int id = (int) Math.floor(t / period);
                float u = ph / 0.22f, sx = 120 + (hash(id) % 560), sy = 30 + (hash(id * 7 + 1) % 90);
                float hx = sx - u * 150, hy = sy + u * 60;
                for (int k = 0; k < 10; k++) {
                    float tx = hx + k * 9, ty = hy - k * 3.6f;
                    p.setColor(argb(amb * dark * (1f - k / 10f) * (1f - u * 0.6f), k < 2 ? 0xffffff : 0xffb8f0));
                    c.drawRect(tx, ty, tx + (k < 2 ? 3 : 2), ty + (k < 2 ? 3 : 2), p);
                }
            }
            if (s.park > 0.5f) drawFireworks(c, t, idleLevel(s) * dark * (s.park - 0.5f) * 2f);
        }
    }

    /** Pixel fireworks over the skyline: a burst every 1.8 s at a hashed spot left or right, falling sparks fading out. */
    private void drawFireworks(Canvas c, float t, float alpha) {
        int[] colours = {0xff4fd8, 0x4ff2ff, 0xffe24a, 0xff8a5a};
        float period = 1.8f;
        for (int b = 0; b < 2; b++) {
            float tt = t - b * period * 0.5f;
            int id = (int) Math.floor(tt / period);
            float u = frac(tt / period);
            // Left or right of the stop card so the bursts stay visible.
            float bx = (hash(id * 7) & 1) == 0 ? 60 + hash(id * 3 + 11) % 150 : 590 + hash(id * 3 + 11) % 150, by = 50 + hash(id * 5 + 3) % 90;
            int col = colours[hash(id) % colours.length];
            float rad = 54 * (float) Math.sqrt(u), drop = 26 * u * u, a = alpha * (1f - u);
            if (a <= 0.01f) continue;
            glow(c, glowPink, bx, by, rad * 2.4f, rad * 2.4f, a * 0.25f);
            p.setColor(argb(a, col));
            for (int i = 0; i < 20; i++) {
                double ang = i * Math.PI * 2 / 20;
                float px0 = bx + (float) Math.cos(ang) * rad, py0 = by + (float) Math.sin(ang) * rad + drop;
                c.drawRect(px0 - 1.5f, py0 - 1.5f, px0 + 1.5f, py0 + 1.5f, p);
            }
        }
    }

    /** Exhaust heat at idle: soft puffs rising and swaying from each tailpipe. */
    private void drawIdleExhaust(Canvas c, DriveModel.State s, float t, float x0, float y0, float x1, float y1) {
        float amb = Math.min(1f, s.stoppedFor / 1.5f);
        if (amb <= 0.01f) return;
        for (int side = 0; side < 2; side++) {
            float ex = side == 0 ? x0 : x1, ey = side == 0 ? y0 : y1;
            for (int k = 0; k < 3; k++) {
                float u = frac(t * 0.55 + k / 3.0 + side * 0.17);
                float px0 = ex + (float) Math.sin(t * 2.6 + k * 2 + side) * 5 * u + (side == 0 ? -1 : 1) * 6 * u;
                glow(c, glowWhite, px0, ey + 2 - u * 26, 10 + 26 * u, 8 + 18 * u, amb * 0.22f * (1f - u));
            }
        }
    }

    /**
     * Trip bar across the top (HUD), arcade style: each item is a neon-outlined label box followed by
     * two-tone digits. Trip time and distance since the app started; fixed slots so
     * the items do not shift as the numbers change. Sits right of the clock.
     */
    private void drawTripBar(Canvas c, DriveModel.State s) {
        long secs = (long) (s.tripMs / 1000);
        String time = secs >= 3600 ? (secs / 3600) + ":" + two((secs / 60) % 60) + ":" + two(secs % 60) : (secs / 60) + ":" + two(secs % 60);
        String dist = String.format(java.util.Locale.US, "%.1f", s.tripKm);
        String[] labels = {"TIME", "DIST"};
        String[] values = {time, dist};
        int[] tones = {HUD_PINK, HUD_CYAN};
        float[] slot = {150, 140};
        float lc = 1.8f, vc = 2.4f, y = 16, x = W - 20;
        for (float w : slot) x -= w;
        x -= (slot.length - 1) * 14;
        for (int i = 0; i < labels.length; i++) {
            float lw = PixelFont.widthCells(labels[i]) * lc + 10, bh = PixelFont.ROWS * vc;
            p.setStyle(Paint.Style.FILL);
            p.setColor(HUD_SHADOW); c.drawRect(x - 1.5f, y - 1.5f, x + lw + 3, y + bh + 3, p);
            p.setColor(0xcc120626); c.drawRect(x, y, x + lw, y + bh, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(tones[i]); c.drawRect(x + 1, y + 1, x + lw - 1, y + bh - 1, p);
            p.setStyle(Paint.Style.FILL);
            pixelText(c, labels[i], x + 5, y + (bh - PixelFont.ROWS * lc) / 2, lc, tones[i], tones[i]);
            float vx = x + lw + 7;
            float vw = pixelText(c, values[i], vx, y, vc, HUD_YELLOW, HUD_ORANGE);
            if (i == 1) pixelText(c, "KM", vx + vw + 4, y + bh - PixelFont.ROWS * 1.6f, 1.6f, HUD_PINK, HUD_PINK);
            x += slot[i] + 14;
        }
    }

    private static String two(long v) { return v < 10 ? "0" + v : String.valueOf(v); }
    private static int fade(int colour, float a) { return argb(((colour >>> 24) / 255f) * a, colour); }

    // ---------------------------------------------------------------- HUD
    // ---------------------------------------------------------------- arcade HUD
    static final int HUD_YELLOW = 0xffffe24a, HUD_ORANGE = 0xffff8a1e, HUD_PINK = 0xffff5fb8, HUD_CYAN = 0xff4ff2ff,
        HUD_WHITE = 0xfffff8ec, HUD_SHADOW = 0xcc12061e;

    /**
     * Draws s in the 5x7 pixel font with its top-left at (x, y), cell px per font pixel. The upper rows use `top`,
     * the lower rows `bottom` (two-tone arcade lettering), over a dark drop shadow and outline. Returns the width.
     */
    private float pixelText(Canvas c, String s, float x, float y, float cell, int top, int bottom) {
        p.setStyle(Paint.Style.FILL);
        float o = Math.max(1f, cell * 0.4f);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                float gx = x + i * (PixelFont.COLS + 1) * cell;
                for (int row = 0; row < PixelFont.ROWS; row++) {
                    if (pass == 1) p.setColor(row < 4 ? top : bottom);
                    for (int col = 0; col < PixelFont.COLS; col++) {
                        if (!PixelFont.lit(ch, col, row)) continue;
                        float px0 = gx + col * cell, py0 = y + row * cell;
                        if (pass == 0) {
                            p.setColor(HUD_SHADOW);
                            c.drawRect(px0 - o, py0 - o, px0 + cell + o + o, py0 + cell + o + o, p);
                        } else c.drawRect(px0, py0, px0 + cell, py0 + cell, p);
                    }
                }
            }
        }
        return PixelFont.widthCells(s) * cell;
    }

    /** Right-aligned variant. */
    private float pixelTextRight(Canvas c, String s, float right, float y, float cell, int top, int bottom) {
        return pixelText(c, s, right - PixelFont.widthCells(s) * cell, y, cell, top, bottom);
    }

    private void drawHud(Canvas c, DriveModel.State s) {
        // Speed: big two-tone digits bottom left with the unit, the accelerator as a block row under them.
        String speed = Float.isNaN(s.shownSpeed) ? "--" : String.valueOf(Math.round(s.mph ? s.shownSpeed / 1.609344f : s.shownSpeed));
        // Speed, unit and accelerator row share one right edge.
        float cell = 6f, barY = H - 22, y = barY - 10 - PixelFont.ROWS * cell, edge = 20 + ACC_W;
        String unit = s.mph ? "MPH" : "KM/H";
        float unitW = PixelFont.widthCells(unit) * 2.5f;
        pixelText(c, unit, edge - unitW, y + PixelFont.ROWS * cell - 7 * 2.5f, 2.5f, HUD_PINK, HUD_PINK);
        pixelTextRight(c, speed, edge - unitW - 10, y, cell, HUD_YELLOW, HUD_ORANGE);
        accRow(c, edge - ACC_W, barY, s.throttle);
        // Right column, bottom up: now playing card, then the gear above it.
        float colBottom = H - 16;
        if (mediaOn && mediaTitle.length() > 0) { drawNowPlaying(c, colBottom); colBottom -= ART_SIZE + 22; }
        if (gearMode == GEARS_DCT) drawDctGear(c, W - 20, colBottom, s.gear);
        else if (gearMode == GEARS_MANUAL) drawShiftPattern(c, W - 20, colBottom, s.gear);
        if (!clockOn) pixelText(c, "NEON DRIVE", 18, 14, 2.2f, HUD_PINK, 0xffc04ad8);
        drawModeLabel(c, s, clockOn ? 84 : 36);
    }

    static final int ACC_N = 18;
    static final float ACC_BW = 8, ACC_GAP = 2.5f, ACC_W = ACC_N * (ACC_BW + ACC_GAP) - ACC_GAP;

    /** Accelerator: a row of small square blocks, pale lavender, then green, pink at full pedal. */
    private void accRow(Canvas c, float x, float y, float v) {
        int n = ACC_N;
        float bw = ACC_BW, gap = ACC_GAP, level = Math.max(0f, Math.min(1f, v));
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float x0 = x + i * (bw + gap);
            int col = i < 12 ? 0xffc8d2ff : i < 16 ? 0xff5cff8a : 0xffff6ab4;
            boolean on = level * n > i + 0.15f;
            p.setColor(HUD_SHADOW); c.drawRect(x0 - 1.5f, y - 1.5f, x0 + bw + 2.5f, y + bw + 2.5f, p);
            p.setColor(on ? col : (col & 0xffffff) | 0x38000000); c.drawRect(x0, y, x0 + bw, y + bw, p);
            if (on) { p.setColor(0x88ffffff); c.drawRect(x0, y, x0 + bw, y + 2, p); }
        }
    }

    /**
     * Gear in the HUD style: the 6-speed gate (R 1 3 5 over 2 4 6, reverse left of first) drawn as chunky
     * cyan pixel lines, the current slot lit by a yellow block and its number in yellow. Neutral sits on the
     * 3-4 plane, mid-height; before any gear has been received the gate is dim. `right`/`bottom` bound the gate.
     */
    private void drawShiftPattern(Canvas c, float right, float bottom, int gear) {
        boolean known = gear != DriveSignals.GEAR_UNKNOWN;
        float colW = 22, gx1 = right - 6, gx0 = gx1 - colW * 3, top = bottom - 46, bot = bottom - 16, mid = (top + bot) / 2;
        float[] colX = {gx0, gx0 + colW, gx0 + colW * 2, gx1};
        int line = known ? HUD_CYAN : 0x5588a0b0;
        float lw = 3;
        // Shadow pass, then colour pass, so joints stay clean.
        for (int pass = 0; pass < 2; pass++) {
            float o = pass == 0 ? 1.5f : 0;
            p.setStyle(Paint.Style.FILL); p.setColor(pass == 0 ? HUD_SHADOW : line);
            c.drawRect(colX[0] - lw / 2 - o, mid - lw / 2 - o, colX[3] + lw / 2 + o, mid + lw / 2 + o, p);
            c.drawRect(colX[0] - lw / 2 - o, top + 5 - o, colX[0] + lw / 2 + o, mid + o, p);
            for (int i = 1; i < 4; i++) c.drawRect(colX[i] - lw / 2 - o, top - o, colX[i] + lw / 2 + o, bot + o, p);
        }
        String[] up = {"R", "1", "3", "5"}, down = {"", "2", "4", "6"};
        float lc = 1.8f, lwid = PixelFont.COLS * lc;
        for (int i = 0; i < 4; i++) {
            boolean upOn = known && (i == 0 ? gear == DriveSignals.GEAR_R : gear == i * 2 - 1);
            boolean downOn = known && i > 0 && gear == i * 2;
            int dim = known ? 0xffd8f8ff : 0x88b8c8d0;
            pixelText(c, up[i], colX[i] - lwid / 2, top - 17, lc, upOn ? HUD_YELLOW : dim, upOn ? HUD_ORANGE : dim);
            if (i > 0) pixelText(c, down[i], colX[i] - lwid / 2, bot + 5, lc, downOn ? HUD_YELLOW : dim, downOn ? HUD_ORANGE : dim);
        }
        if (!known) return;
        float bx, by;
        if (gear == DriveSignals.GEAR_R) { bx = colX[0]; by = top + 9; }
        else if (gear >= 1 && gear <= 6) { bx = colX[(gear + 1) / 2]; by = gear % 2 == 1 ? top + 4 : bot - 4; }
        else { bx = colX[2]; by = mid; }   // neutral rests in the 3-4 plane, where a real H gate centres
        float k = 4.5f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(HUD_SHADOW); c.drawRect(bx - k - 1.5f, by - k - 1.5f, bx + k + 2.5f, by + k + 2.5f, p);
        p.setColor(gear == DriveSignals.GEAR_NEUTRAL ? HUD_WHITE : HUD_YELLOW); c.drawRect(bx - k, by - k, bx + k, by + k, p);
        p.setColor(gear == DriveSignals.GEAR_NEUTRAL ? 0xffb8c8d0 : HUD_ORANGE); c.drawRect(bx - k, by + 1, bx + k, by + k, p);
        p.setColor(0xccffffff); c.drawRect(bx - k, by - k, bx + k, by - k + 2, p);
    }

    /**
     * 8-speed DCT in the HUD style: P R N D letters with the current one lit and underlined, and while in
     * drive the gear number (1..8) after the D. P is never lit (no park signal is decoded).
     */
    private void drawDctGear(Canvas c, float right, float bottom, int gear) {
        boolean known = gear != DriveSignals.GEAR_UNKNOWN;
        boolean drive = gear >= 1 && gear <= 8;
        float cell = 3f, step = (PixelFont.COLS + 2) * cell, top = bottom - 12 - PixelFont.ROWS * cell;
        String num = drive ? String.valueOf(gear) : "";
        float numW = PixelFont.COLS * cell, x0 = right - numW - 6 - 4 * step;
        String[] letters = {"P", "R", "N", "D"};
        int lit = !known ? -1 : gear == DriveSignals.GEAR_R ? 1 : gear == DriveSignals.GEAR_NEUTRAL ? 2 : drive ? 3 : -1;
        for (int i = 0; i < 4; i++) {
            float lx = x0 + i * step;
            boolean on = i == lit;
            int dim = known ? 0xccd8f8ff : 0x66b8c8d0;
            pixelText(c, letters[i], lx, top, cell, on ? HUD_YELLOW : dim, on ? HUD_ORANGE : dim);
            if (on) {
                float w = PixelFont.COLS * cell;
                p.setStyle(Paint.Style.FILL);
                p.setColor(HUD_SHADOW); c.drawRect(lx - 1.5f, bottom - 7.5f, lx + w + 2.5f, bottom - 1.5f + 2.5f, p);
                p.setColor(HUD_CYAN); c.drawRect(lx, bottom - 6, lx + w, bottom - 2, p);
            }
        }
        if (drive) pixelText(c, num, right - numW, top, cell, HUD_YELLOW, HUD_ORANGE);
    }

    /**
     * Now playing card on the right, at the bottom of the column (gear above it): pixelated album art at the right edge, "NOW PLAYING",
     * title and artist right-aligned beside it, and a block progress row when the source reports times.
     * Hidden while nothing is playing.
     */
    private void drawNowPlaying(Canvas c, float bottom) {
        if (mediaTitle.length() == 0) return;
        float right = W - 20, artTop = bottom - ART_SIZE, artL = right - ART_SIZE, textR = artL - 12;
        p.setStyle(Paint.Style.FILL);
        p.setColor(HUD_SHADOW); c.drawRect(artL - 3, artTop - 3, right + 4, artTop + ART_SIZE + 4, p);
        if (mediaArt != null) {
            r.set(artL, artTop, right, artTop + ART_SIZE);
            pixel.setAlpha(255);
            c.drawBitmap(mediaArt, null, r, pixel);
        } else {
            // No cover: a small pixel note on a dark tile.
            p.setColor(0xff2a1a44); c.drawRect(artL, artTop, right, artTop + ART_SIZE, p);
            p.setColor(HUD_PINK);
            float u = ART_SIZE / 16f;
            c.drawRect(artL + 6 * u, artTop + 3 * u, artL + 7 * u, artTop + 11 * u, p);
            c.drawRect(artL + 7 * u, artTop + 3 * u, artL + 11 * u, artTop + 4 * u, p);
            c.drawRect(artL + 10 * u, artTop + 4 * u, artL + 11 * u, artTop + 6 * u, p);
            c.drawRect(artL + 3 * u, artTop + 9 * u, artL + 7 * u, artTop + 12 * u, p);
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(HUD_CYAN);
        c.drawRect(artL - 1, artTop - 1, right + 1, artTop + ART_SIZE + 1, p);
        p.setStyle(Paint.Style.FILL);
        pixelTextRight(c, "NOW PLAYING", textR, artTop + 2, 1.6f, HUD_PINK, HUD_PINK);
        float maxW = 200;   // keeps long titles clear of the car
        shadowTextRight(c, fit(mediaTitle, 17, maxW), textR, artTop + 34, 17, HUD_WHITE);
        if (mediaArtist.length() > 0) shadowTextRight(c, fit(mediaArtist, 13, maxW), textR, artTop + 53, 13, HUD_CYAN);
        if (mediaProgress >= 0) {
            int n = 20;
            float bw = 6, gap = 2, x0 = textR - n * (bw + gap) + gap, y = artTop + ART_SIZE - 5;
            for (int i = 0; i < n; i++) {
                boolean on = mediaProgress * n > i + 0.5f;
                float bx = x0 + i * (bw + gap);
                p.setColor(HUD_SHADOW); c.drawRect(bx - 1, y - 1, bx + bw + 2, y + 5, p);
                p.setColor(on ? HUD_YELLOW : 0x40ffffff); c.drawRect(bx, y, bx + bw, y + 4, p);
            }
        }
    }

    /** s shortened with an ellipsis to fit width w at the given text size. */
    private String fit(String s, float size, float w) {
        text.setTextSize(size);
        if (text.measureText(s) <= w) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s.substring(0, n) + "…") > w) n--;
        return s.substring(0, n) + "…";
    }

    private void shadowTextRight(Canvas c, String s, float right, float y, float size, int color) {
        text.setTextSize(size);
        shadowText(c, s, right - text.measureText(s), y, size, color);
    }

    private void drawModeLabel(Canvas c, DriveModel.State s, float y) {
        if (s.mode == DriveModel.DEMO) pixelText(c, "DEMO", 18, y, 2f, HUD_PINK, HUD_PINK);
        else if (s.mode == DriveModel.WAITING) pixelText(c, "NO SIGNAL", 18, y, 2f, HUD_CYAN, HUD_CYAN);
    }

    /** Clock top left in the pixel font; the date (may be Korean) falls back to the regular face when needed. */
    private void drawClock(Canvas c) {
        float x = 18, y = 14, cell = 4f;
        float w = pixelText(c, clockTime, x, y, cell, HUD_WHITE, HUD_CYAN);
        if (clockSuffix.length() > 0) {
            if (PixelFont.supports(clockSuffix)) pixelText(c, clockSuffix, x + w + 8, y + 14, 2f, HUD_PINK, HUD_PINK);
            else shadowText(c, clockSuffix, x + w + 8, y + 26, 14, HUD_PINK);
        }
        if (PixelFont.supports(clockDate)) pixelText(c, clockDate, x, y + 36, 2f, HUD_PINK, HUD_PINK);
        else shadowText(c, clockDate, x, y + 48, 13, HUD_PINK);
    }

    private void shadowText(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(size);
        text.setColor(HUD_SHADOW); c.drawText(s, x + 1.5f, y + 1.5f, text);
        text.setColor(color); c.drawText(s, x, y, text);
    }

    // ---------------------------------------------------------------- settings UI
    private String t(String ko, String en) { return korean ? ko : en; }

    private void drawUi(Canvas c) {
        if (!panelOpen && gearAlpha > 0.01f) drawGear(c, GEAR_X, GEAR_Y, gearAlpha);
        if (exitButton && !panelOpen && gearAlpha > 0.01f) drawExit(c, EXIT_X, EXIT_Y, gearAlpha);
        if (!panelOpen) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66000000);
        c.drawRect(0, 0, W, H, p);
        r.set(PX0, PY0, PX1, PY1);
        p.setColor(0xee0c0522); c.drawRoundRect(r, 14, 14, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0xccff4fd8); c.drawRoundRect(r, 14, 14, p);
        p.setStrokeWidth(1); p.setColor(0x6635f2ff); r.set(PX0 + 4, PY0 + 4, PX1 - 4, PY1 - 4); c.drawRoundRect(r, 11, 11, p);
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(20); text.setColor(0xffaefbff);
        c.drawText(t("설정", "SETTINGS"), PX0 + 22, PY0 + 32, text);
        // Close (X)
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xffff7ad9);
        c.drawLine(598, 40, 612, 54, p); c.drawLine(612, 40, 598, 54, p);
        toggle(c, PX0 + 24, 300, 96, t("HUD 표시", "Show HUD"), hud);
        toggle(c, 404, 532, 96, t("시계", "Clock"), clockOn);
        toggle(c, PX0 + 24, 300, 136, t("데모 모드", "Demo mode"), demoOn);
        toggle(c, 404, 532, 136, t("음원 정보", "Now playing"), mediaOn);
        toggle(c, PX0 + 24, 300, 176, t("트립 정보", "Trip"), tripOn);
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(15); text.setColor(0xffd8d0f0);
        c.drawText(t("기어 표시", "Gear"), PX0 + 24, 216, text);
        text.setTextSize(10); text.setColor(dctGuessed ? 0xffffd23f : 0xff8a82a8);
        c.drawText(dctGuessed ? t("DCT 감지 (미확인)", "DCT guessed (unconfirmed)") : t("DCT 자동 감지 (미확인)", "DCT auto-detect (unconfirmed)"), PX0 + 24, 231, text);
        for (int i = 0; i < GEARS_KO.length; i++) {
            float x0 = 330 + i * 94;
            r.set(x0 + 2, 203, x0 + 90, 229);
            boolean on = gearMode == i;
            p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xff1aa6c8 : 0xff2a2442); c.drawRoundRect(r, 8, 8, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f); p.setColor(on ? 0xff7df9ff : 0xff6a6290); c.drawRoundRect(r, 8, 8, p);
            text.setTextAlign(Paint.Align.CENTER); text.setTextSize(13); text.setColor(on ? 0xffffffff : 0xffb8b0d8);
            c.drawText(korean ? GEARS_KO[i] : GEARS_EN[i], x0 + 46, 221, text);
        }
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(15); text.setColor(0xffd8d0f0);
        c.drawText(t("시간대", "Time of day"), PX0 + 24, 261, text);
        for (int i = 0; i < Theme.MODE_KO.length; i++) {
            float x0 = 330 + i * 56;
            r.set(x0 + 2, 243, x0 + 54, 269);
            boolean on = themeMode == i;
            p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xff1aa6c8 : 0xff2a2442); c.drawRoundRect(r, 8, 8, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f); p.setColor(on ? 0xff7df9ff : 0xff6a6290); c.drawRoundRect(r, 8, 8, p);
            text.setTextAlign(Paint.Align.CENTER); text.setTextSize(13); text.setColor(on ? 0xffffffff : 0xffb8b0d8);
            c.drawText(korean ? Theme.MODE_KO[i] : Theme.MODE_EN[i], x0 + 28, 261, text);
        }
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(15); text.setColor(0xffd8d0f0);
        c.drawText(t("차량 색상", "Body colour"), PX0 + 24, 302, text);
        for (int i = 0; i < CarPaint.COUNT; i++) {
            float sx = swatchX(i), sy = SWATCH_Y;
            if (i == paint) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0xff35f2ff);
                c.drawCircle(sx, sy, 25, p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(CarPaint.RGB[i]); c.drawCircle(sx, sy, 19, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(0x99ffffff); c.drawCircle(sx, sy, 19, p);
        }
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(15); text.setColor(0xffffffff);
        c.drawText((korean ? CarPaint.NAME_KO[paint] : CarPaint.NAME_EN[paint]) + "  (" + CarPaint.CODE[paint] + ")", 400, 384, text);
        text.setTextSize(11); text.setColor(0x88d8d0f0);
        String where = sunSource == 0 ? t("서울 기준", "Seoul")
            : String.format(java.util.Locale.US, "%s %.2f, %.2f", sunSource == 2 ? "GPS" : t("마지막 GPS", "last GPS"), sunLat, sunLon);
        String sun = String.format(java.util.Locale.US, korean ? "오늘 일출 %s · 일몰 %s (%s)" : "Sunrise %s · sunset %s (%s)",
            sunrise, sunset, where);
        c.drawText(themeMode == Theme.MODE_AUTO ? sun : t("화면 밖을 누르면 닫힙니다", "Tap outside to close"), 400, 404, text);
    }

    private void settingRow(Canvas c, float y, String label, boolean on) { toggle(c, PX0 + 24, 532, y, label, on); }

    /** Label at labelX and a 68 px switch starting at switchX. */
    private void toggle(Canvas c, float labelX, float switchX, float y, String label, boolean on) {
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(15); text.setColor(0xffd8d0f0);
        c.drawText(label, labelX, y + 5, text);
        r.set(switchX, y - 13, switchX + 68, y + 13);
        p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xff1aa6c8 : 0xff2a2442); c.drawRoundRect(r, 13, 13, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(on ? 0xff7df9ff : 0xff6a6290); c.drawRoundRect(r, 13, 13, p);
        p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xffffffff : 0xffa49cc4);
        c.drawCircle(on ? switchX + 55 : switchX + 13, y, 10, p);
    }

    /** Back to the N dashboard: a left arrow in the gear's style (drawn, so no font glyph is needed). */
    private void drawExit(Canvas c, float x, float y, float alpha) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb(0.55f * alpha, 0x0c0522));
        c.drawCircle(x, y, 22, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.5f); p.setColor(argb(alpha, 0xaefbff));
        c.drawLine(x - 9, y, x + 9, y, p);
        c.drawLine(x - 9, y, x - 2, y - 7, p);
        c.drawLine(x - 9, y, x - 2, y + 7, p);
        p.setStrokeWidth(1.5f); p.setColor(argb(0.8f * alpha, 0xff4fd8));
        c.drawCircle(x, y, 22, p);
        p.setStyle(Paint.Style.FILL);
    }
    private void drawGear(Canvas c, float x, float y, float alpha) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb(0.55f * alpha, 0x0c0522));
        c.drawCircle(x, y, 22, p);
        p.setColor(argb(alpha, 0xaefbff));
        int save = c.save();
        for (int i = 0; i < 8; i++) {
            c.rotate(45, x, y);
            c.drawRect(x - 3, y - 15, x + 3, y - 9, p);
        }
        c.restoreToCount(save);
        c.drawCircle(x, y, 10.5f, p);
        p.setColor(argb(alpha, 0x0c0522));
        c.drawCircle(x, y, 4.5f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(argb(0.8f * alpha, 0xff4fd8));
        c.drawCircle(x, y, 22, p);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = (a >>> 24);
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = (b >>> 24);
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
            | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ---------------------------------------------------------------- baking
    private static Bitmap layer(float w, float h, float scale) {
        return Bitmap.createBitmap(Math.max(1, (int) Math.ceil(w * scale)), Math.max(1, (int) Math.ceil(h * scale)), Bitmap.Config.ARGB_8888);
    }
    private static Canvas canvas(Bitmap b, float w) {
        Canvas c = new Canvas(b);
        float k = b.getWidth() / w;
        c.scale(k, k);
        return c;
    }

    private void bake(float scale) {
        baked = scale;
        float k = Math.max(1f, scale);
        // The scanline road renders at logical resolution (800 px wide on the head unit).
        road = new RoadRenderer((int) W, HY, H, CAR_Y);
        for (int th = 0; th < Theme.COUNT; th++) {
            bakeTheme = th;
            skyT[th] = layer(W, HY, k); bakeSky(canvas(skyT[th], W));
            sunT[th] = layer(W, HY, k); bakeSun(canvas(sunT[th], W));
            if (Theme.daylitProps(th)) { coastT[th] = layer(W, HY, k); bakeCoast(canvas(coastT[th], W)); }
            else { cityT[th] = layer(W, HY, k); bakeCity(canvas(cityT[th], W)); }
        }
        bakeTheme = Theme.NIGHT;
        palm = layer(220, 360, k); bakeCoastPalm(canvas(palm, 220));   // same palm shape as the coast, neon colours
        lamp = layer(70, 300, k); bakeLamp(canvas(lamp, 70));
        bakeTheme = Theme.DAY;
        coastPalm = layer(220, 360, k); bakeCoastPalm(canvas(coastPalm, 220));
        bakeTheme = Theme.NIGHT;
        glowRed = glowSprite(0xff1f3d, 0.25f);
        glowAmber = glowSprite(0xffb21a, 0.22f);
        glowFlame = flameSprite();
        glowCore = glowSprite(0x9fd8ff, 0.35f);
        glowWhite = glowSprite(0xfff3e6, 0.15f);
        glowPink = glowSprite(0xff4fd8, 0.2f);
    }

    private static Bitmap glowSprite(int rgb, float core) {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(48, 48, 48, new int[] {argb(1f, rgb), argb(0.55f, rgb), argb(0.18f, rgb), argb(0f, rgb)},
            new float[] {0f, core, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(48, 48, 48, g);
        return b;
    }

    private static Bitmap flameSprite() {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setShader(new RadialGradient(48, 48, 48, new int[] {0xfffff2c0, 0xffffb340, 0xccff5a10, 0x55ff2a00, 0x00ff0000},
            new float[] {0f, 0.18f, 0.4f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(48, 48, 48, g);
        return b;
    }

    private void bakeSky(Canvas c) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (bakeTheme == Theme.MORNING || bakeTheme == Theme.SUNSET) {
            boolean morning = bakeTheme == Theme.MORNING;
            g.setShader(new LinearGradient(0, 0, 0, HY, morning
                ? new int[] {0xff5f97d2, 0xff9cc2e6, 0xffe9d6d6, 0xffffdcb0}
                : new int[] {0xff2a1f5a, 0xff7a2f78, 0xffe0566e, 0xffffa04a},
                new float[] {0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, HY, g);
            g.setShader(null);
            // Long flat cloud streaks lit from the low sun.
            Random rnd = new Random(morning ? 515 : 1919);
            for (int i = 0; i < 11; i++) {
                float cx = rnd.nextFloat() * W, cy = 20 + rnd.nextFloat() * (HY * 0.62f), len = 40 + rnd.nextFloat() * 120, th = 3 + rnd.nextFloat() * 5;
                int lit = morning ? 0xfffff1ea : 0xffffb07a, shade = morning ? 0xffe9c6cf : 0xff8a3a6a;
                g.setColor(shade); c.drawRect(cx - len / 2, cy, cx + len / 2, cy + th, g);
                g.setColor(lit); c.drawRect(cx - len / 2 + 6, cy - th * 0.6f, cx + len / 2 - 10, cy + th * 0.35f, g);
            }
            return;
        }
        if (bakeTheme == Theme.DAY) {
            g.setShader(new LinearGradient(0, 0, 0, HY, new int[] {0xff2f74d0, 0xff5c9ee6, 0xff9fcdf2, 0xffd9eef8},
                new float[] {0f, 0.45f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, HY, g);
            g.setShader(null);
            // Chunky pixel clouds: stacked blocks with a soft grey belly.
            Random rnd = new Random(2027);
            for (int i = 0; i < 9; i++) {
                float cx = rnd.nextFloat() * W, cy = 24 + rnd.nextFloat() * (HY * 0.5f), sz = 10 + rnd.nextFloat() * 16;
                int puffs = 3 + rnd.nextInt(4);
                for (int j = 0; j < puffs; j++) {
                    float px0 = cx + (j - puffs / 2f) * sz * 1.1f, py0 = cy - (j % 2) * sz * 0.5f;
                    g.setColor(0xffdfe9f2); c.drawRect(px0 - sz, py0, px0 + sz, py0 + sz * 0.9f, g);
                    g.setColor(0xffffffff); c.drawRect(px0 - sz, py0 - sz * 0.6f, px0 + sz, py0 + sz * 0.5f, g);
                }
            }
            return;
        }
        g.setShader(new LinearGradient(0, 0, 0, HY, new int[] {0xff07021a, 0xff1d0842, 0xff4a1068, 0xffa3247e, 0xffff4f8b},
            new float[] {0f, 0.38f, 0.66f, 0.88f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, HY, g);
        g.setShader(new LinearGradient(0, HY - 40, 0, HY, new int[] {0x00ff4f8b, 0x55ff6fa5},
            null, Shader.TileMode.CLAMP));
        c.drawRect(0, HY - 40, W, HY, g);
    }

    private void bakeSun(Canvas c) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (bakeTheme == Theme.MORNING) {   // low in the east, pale with a warm halo
            float sx = VPX - 230, sy = HY - 112, sr = 34;
            g.setShader(new RadialGradient(sx, sy, sr * 4f, new int[] {0x99fff0d0, 0x33ffd7a8, 0x00ffffff},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, sr * 4f, g);
            g.setShader(null);
            g.setColor(0xfffff6e2); c.drawCircle(sx, sy, sr, g);
            return;
        }
        if (bakeTheme == Theme.SUNSET) {    // big striped sun sinking into the horizon
            stripedSun(c, g, VPX, HY - 22, 150, new int[] {0xffffef9a, 0xffffbf4a, 0xffff7a3a, 0xffff4a5a}, new int[] {0x99ff9a5a, 0x33ff6a4a, 0x00ff6a4a});
            return;
        }
        if (bakeTheme == Theme.DAY) {   // high, small and white-hot
            float sx = VPX + 210, sy = 64, sr = 30;
            g.setShader(new RadialGradient(sx, sy, sr * 3.4f, new int[] {0xaafff8e0, 0x33fff4c8, 0x00ffffff},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, sr * 3.4f, g);
            g.setShader(null);
            g.setColor(0xfffff9e6); c.drawCircle(sx, sy, sr, g);
            return;
        }
        stripedSun(c, g, VPX, HY - 66, 116, new int[] {0xfffff27a, 0xffffb347, 0xffff5f7e, 0xffff2d95}, new int[] {0x88ff5fa8, 0x33ff3d8b, 0x00ff3d8b});
    }

    /** Synthwave sun: halo, gradient disc, horizontal gaps widening toward the bottom. */
    private void stripedSun(Canvas c, Paint g, float sx, float sy, float sr, int[] disc, int[] halo) {
        g.setShader(new RadialGradient(sx, sy, sr * 2.1f, halo, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, sr * 2.1f, g);
        g.setShader(new LinearGradient(0, sy - sr, 0, sy + sr, disc,
            new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        float cut = sy - sr * 0.12f;
        int save = c.save();
        c.clipRect(0, 0, W, cut);
        c.drawCircle(sx, sy, sr, g);
        c.restoreToCount(save);
        float yy = cut, band = 10f, gap = 1.6f;
        while (yy < sy + sr) {
            save = c.save();
            c.clipRect(0, yy + gap, W, Math.min(yy + gap + band, sy + sr));
            c.drawCircle(sx, sy, sr, g);
            c.restoreToCount(save);
            yy += gap + band;
            gap += 1.3f; band = Math.max(3f, band - 0.9f);
        }
    }

    /** Skyline band exactly W wide that tiles seamlessly: buildings crossing the edge are drawn on both sides. */
    private void bakeCity(Canvas c) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(1987);
        if (bakeTheme == Theme.NIGHT) beacons = 0;
        for (int layer = 0; layer < 2; layer++) {
            float xx = 0;
            while (xx < W) {
              long seed = rnd.nextLong();
              float bwStart = xx;
              for (int copy = -1; copy <= 1; copy++) {
                Random br = new Random(seed);
                float xx0 = bwStart + copy * W;
                if (copy != 0 && xx0 > W + 60) continue;
                float bwv = layer == 0 ? 16 + br.nextInt(30) : 14 + br.nextInt(34);
                drawBuilding(c, g, br, layer, xx0, bwv, copy == 0);
              }
              Random br = new Random(seed);
              xx += (layer == 0 ? 16 + br.nextInt(30) : 14 + br.nextInt(34)) + (layer == 0 ? rnd.nextInt(4) : 1 + rnd.nextInt(8));
            }
        }
    }

    private void drawBuilding(Canvas c, Paint g, Random rnd, int layer, float xx, float bw, boolean primary) {
        {
            {
                float center = xx + bw / 2;
                float f = 0.32f + 0.48f * (float) Math.abs(Math.sin(center * 0.011 + layer));
                float bh = (layer == 0 ? 34 + rnd.nextInt(86) : 18 + rnd.nextInt(62)) * f;
                float top = HY - bh;
                g.setStyle(Paint.Style.FILL);
                int[] pastel = {0xfff2cdc6, 0xffc4e6e0, 0xfff6e8cc, 0xffdcd2ee, 0xfffafafa, 0xffbcd4ec};
                int dayColour = layer == 0 ? 0xffaab8cc : pastel[(int) (Math.abs(center * 7.3f)) % pastel.length];
                int nightColour = layer == 0 ? 0xff2c0f55 : 0xff140632;
                int sunsetColour = layer == 0 ? 0xff5a2e5e : 0xff2c1736;
                int colour = bakeTheme == Theme.DAY ? dayColour : bakeTheme == Theme.MORNING ? mix(dayColour, 0xfff3dccb, layer == 0 ? 0.5f : 0.3f)
                    : bakeTheme == Theme.SUNSET ? sunsetColour : nightColour;
                g.setColor(colour);
                c.drawRect(xx, top, xx + bw, HY, g);
                if ((bakeTheme == Theme.DAY || bakeTheme == Theme.MORNING) && layer == 1) {   // shaded side for a little depth
                    g.setColor(0x22000000); c.drawRect(xx + bw * 0.7f, top, xx + bw, HY, g);
                }
                if (layer == 0 && rnd.nextInt(3) == 0) {
                    g.setStyle(Paint.Style.STROKE); g.setStrokeWidth(1);
                    g.setColor(colour);
                    c.drawLine(center, top, center, top - 10 - rnd.nextInt(14), g);
                }
                if (layer == 1) {
                    for (float wy = top + 5; wy < HY - 4; wy += 7) {
                        for (float wx = xx + 3; wx < xx + bw - 4; wx += 6) {
                            if (rnd.nextFloat() < 0.24f) {
                                int[] colors = {0xffd36b, 0x7df9ff, 0xff6ad5, 0xfff4e0};
                                g.setStyle(Paint.Style.FILL);
                                int lit = colors[rnd.nextInt(colors.length)];
                                float alpha = 0.55f + rnd.nextFloat() * 0.45f;
                                // Daytime: blue glass; sunset: fewer, warm lights; night: neon-coloured windows.
                                int window = bakeTheme == Theme.DAY ? argb(0.75f, 0x5d84ad) : bakeTheme == Theme.MORNING ? argb(0.6f, 0x9ab4d0)
                                    : bakeTheme == Theme.SUNSET ? ((wx + wy) % 3 < 1.2f ? argb(alpha, (lit & 1) == 0 ? 0xffc36b : 0xff9a5a) : 0) : argb(alpha, lit);
                                g.setColor(window);
                                c.drawRect(wx, wy, wx + 2.4f, wy + 3.2f, g);
                            }
                        }
                    }
                    if (rnd.nextInt(4) == 0) {
                        g.setStyle(Paint.Style.STROKE); g.setStrokeWidth(1.6f);
                        boolean pink = rnd.nextBoolean();
                        g.setColor(bakeTheme == Theme.NIGHT ? (pink ? 0xddff4fd8 : 0xdd35f2ff) : 0x00000000);
                        c.drawLine(xx + 1, top + 0.8f, xx + bw - 1, top + 0.8f, g);
                    }
                }
                if (primary && bakeTheme == Theme.NIGHT && bh > 58 && beacons < beaconX.length && rnd.nextInt(2) == 0) {
                    beaconX[beacons] = center; beaconY[beacons] = top - 3; beacons++;
                }
            }
        }
    }

    // Coast band colours per theme (night, morning, day, sunset).
    static final int[] ISLE_FAR = {0xff2c1552, 0xffb4b8cc, 0xff9ab8d6, 0xff5a3060};
    static final int[] ISLE_NEAR = {0xff1c0c3c, 0xff8a9cbc, 0xff6f9cc4, 0xff3c1c46};
    static final int[] SEA_TOP = {0xff4a1a6a, 0xff9cbadb, 0xff4f9ee0, 0xffd27080};
    static final int[] SEA_BOT = {0xff1c0838, 0xff6290c6, 0xff2468c0, 0xff6a2a62};
    static final int[] GLITTER = {0x99ff7ad0, 0xccfff4e8, 0xddffffff, 0xccffc080};

    /** Islands on the far horizon over a sea strip; tiles seamlessly across W (all shapes periodic in W). */
    private void bakeCoast(Canvas c) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        int th = bakeTheme;
        float sea = HY - SEA_H;
        for (int layer = 0; layer < 2; layer++) {
            Path path = new Path();
            path.moveTo(0, sea + 1);
            for (int x = 0; x <= W; x += 2) path.lineTo(x, sea + 1 - isle(x, layer));
            path.lineTo(W, sea + 1); path.close();
            g.setColor(layer == 0 ? ISLE_FAR[th] : ISLE_NEAR[th]);
            c.drawPath(path, g);
        }
        g.setShader(new LinearGradient(0, sea, 0, HY, new int[] {SEA_TOP[th], SEA_BOT[th]}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, sea, W, HY, g);
        g.setShader(null);
        Random rnd = new Random(77);
        g.setColor(GLITTER[th]);
        for (int i = 0; i < 80; i++) {
            float y = sea + 1 + rnd.nextFloat() * (SEA_H - 2), depth = (y - sea) / SEA_H;
            float x = rnd.nextFloat() * W, len = 2 + rnd.nextFloat() * 9 * depth;
            c.drawRect(x, y, x + len, y + 1, g);
            if (x + len > W) c.drawRect(x - W, y, x + len - W, y + 1, g);
        }
        g.setColor(th == Theme.DAY || th == Theme.MORNING ? 0x66ffffff : 0x33ffffff);
        c.drawRect(0, sea, W, sea + 1, g);   // bright far edge of the water
    }

    /** Island silhouette height (px) at x for the far (0) or near (1) ridge; zero leaves open sea. */
    static float isle(float x, int layer) {
        double a = 2 * Math.PI * x / W;
        double h = layer == 0
            ? 14 * Math.sin(a * 2 + 0.5) + 8 * Math.sin(a * 5 + 1.3) + 3 * Math.sin(a * 13 + 0.4) - 6
            : 9 * Math.sin(a * 3 + 2.2) + 5 * Math.sin(a * 7 + 0.2) + 2 * Math.sin(a * 17) - 7;
        return (float) Math.max(0, h * (layer == 0 ? 1.5 : 1.2));
    }

    /** Tropical palm: ringed curved trunk, a full crown of arching, drooping fronds with hanging leaflets. */
    private void bakeCoastPalm(Canvas c) {
        boolean day = dayProps();
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bx = 88, by = 360, cx = bx - 8, cy = by - 180, tx = bx + 34, ty = by - 300;
        int segs = 22;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs, t1 = (i + 1) / (float) segs;
            float x0 = quad(bx, cx, tx, t0), y0 = quad(by, cy, ty, t0), x1 = quad(bx, cx, tx, t1), y1 = quad(by, cy, ty, t1);
            float w0 = 10 - 4.5f * t0, w1 = 10 - 4.5f * t1;
            Path seg = new Path();
            seg.moveTo(x0 - w0, y0); seg.lineTo(x0 + w0, y0); seg.lineTo(x1 + w1, y1 + 0.6f); seg.lineTo(x1 - w1, y1 + 0.6f); seg.close();
            g.setStyle(Paint.Style.FILL);
            g.setColor(day ? (i % 2 == 0 ? 0xff9a6a3e : 0xff7c4e2c) : (i % 2 == 0 ? 0xff1a0836 : 0xff12052a));
            c.drawPath(seg, g);
            g.setColor(day ? 0x40000000 : 0x00000000);
            c.drawRect(Math.min(x0, x1) + w1 * 0.35f, y1, Math.max(x0, x1) + w0, y0, g);   // shaded side
            if (!day) {
                g.setStyle(Paint.Style.STROKE); g.setStrokeWidth(1.6f); g.setColor(0xccff4fd8);
                c.drawLine(x0 + w0, y0, x1 + w1, y1, g);
            }
        }
        // Back fronds first (darker), then the front ones.
        float[][] back = {{-150, 78}, {-112, 70}, {-70, 72}, {-32, 80}, {-188, 74}, {12, 76}};
        float[][] front = {{-168, 92}, {-135, 86}, {-92, 70}, {-50, 88}, {-14, 94}, {168, 80}, {24, 84}, {150, 70}, {48, 72}};
        drawFronds(c, g, tx, ty, back, day ? 0xff2a7a34 : 0xff0c0220, day ? 0xff1f5e28 : 0x88ff4fd8);
        drawFronds(c, g, tx, ty, front, day ? 0xff3cae46 : 0xff120428, day ? 0xff2a8a36 : 0xccff4fd8);
        if (day) {
            g.setStyle(Paint.Style.FILL);
            g.setColor(0xff6a4a1e); c.drawCircle(tx - 5, ty + 6, 4.5f, g); c.drawCircle(tx + 4, ty + 8, 4.5f, g); c.drawCircle(tx, ty + 11, 4f, g);
        }
    }

    private static float quad(float a, float b, float c, float t) { float u = 1 - t; return u * u * a + 2 * u * t * b + t * t * c; }

    /** Each frond: an arching spine (up, then drooping down toward the tip) with leaflets hanging below it. */
    private static void drawFronds(Canvas c, Paint g, float hx, float hy, float[][] fronds, int fill, int edge) {
        for (float[] f : fronds) {
            double a = Math.toRadians(f[0]);
            float len = f[1], dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float horiz = 1f - Math.abs(dy);                      // near-horizontal fronds droop most
            float ex = hx + dx * len, ey = hy + dy * len * 0.55f + len * (0.25f + 0.45f * horiz);
            float mx = hx + dx * len * 0.55f, my = hy + dy * len * 0.55f - len * 0.22f;
            g.setStyle(Paint.Style.STROKE); g.setStrokeCap(Paint.Cap.ROUND);
            int n = 13;
            for (int i = 1; i <= n; i++) {
                float t = i / (float) n;
                float sx = quad(hx, mx, ex, t), sy = quad(hy, my, ey, t);
                float tdx = quad(hx, mx, ex, Math.min(1, t + 0.05f)) - sx, tdy = quad(hy, my, ey, Math.min(1, t + 0.05f)) - sy;
                float tl = (float) Math.max(0.001, Math.sqrt(tdx * tdx + tdy * tdy));
                float nx = -tdy / tl, ny = tdx / tl;
                if (ny < 0) { nx = -nx; ny = -ny; }                // leaflets hang downward
                float leaf = (1 - t * 0.75f) * len * 0.30f;
                g.setStrokeWidth(3.2f - t * 1.6f); g.setColor(fill);
                c.drawLine(sx, sy, sx + nx * leaf + tdx / tl * leaf * 0.35f, sy + ny * leaf, g);
                c.drawLine(sx, sy, sx - nx * leaf * 0.45f + tdx / tl * leaf * 0.3f, sy - ny * leaf * 0.25f + leaf * 0.35f, g);
            }
            Path spine = new Path(); spine.moveTo(hx, hy); spine.quadTo(mx, my, ex, ey);
            g.setStrokeWidth(3.4f); g.setColor(fill); c.drawPath(spine, g);
            g.setStrokeWidth(1.2f); g.setColor(edge); c.drawPath(spine, g);
        }
        g.setStrokeCap(Paint.Cap.BUTT);
    }

    private void bakeLamp(Canvas c) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bx = 50, by = 300;
        g.setStyle(Paint.Style.FILL); g.setColor(dayProps() ? 0xff8a92a4 : 0xff1d0d3a);
        c.drawRect(bx - 2.5f, by - 280, bx + 2.5f, by, g);
        c.drawRect(bx - 5, by - 14, bx + 5, by, g);
        Path arm = new Path();
        arm.moveTo(bx, by - 276); arm.quadTo(bx - 4, by - 292, bx - 22, by - 290);
        g.setStyle(Paint.Style.STROKE); g.setStrokeWidth(3.5f); c.drawPath(arm, g);
        g.setStrokeWidth(1); g.setColor(dayProps() ? 0xffc8ced8 : 0x9935f2ff);
        c.drawLine(bx + 2.5f, by - 276, bx + 2.5f, by, g);
        g.setStyle(Paint.Style.FILL); g.setColor(dayProps() ? 0xfff2f2f2 : 0xffffd8f4);
        RectF head = new RectF(bx - 32, by - 293, bx - 12, by - 286);
        c.drawOval(head, g);
    }
}
