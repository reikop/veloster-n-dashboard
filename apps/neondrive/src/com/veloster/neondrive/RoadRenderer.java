package com.veloster.neondrive;

import android.graphics.Bitmap;
import java.util.Arrays;

/**
 * Old-school DOS/arcade pseudo-3D road, drawn one scanline at a time into a pixel buffer.
 *
 * Every screen row below the horizon is a slice of road at distance z = F*CAM_H/(row offset).
 * The road centre is offset sideways by 0.5*k*(z - carZ)^2, so it runs straight from the camera
 * to the car and only bends beyond it, exactly like the classic games. Asphalt, rumble strips,
 * lane dashes and the neon grid are all computed from the same per-row values, so they bend
 * together instead of being skewed. Stripes alternate with road distance for the sense of speed.
 */
final class RoadRenderer {
    /** Focal length (px) and camera height (m), derived from the car sprite: 10 deg chase camera, 3-lane road. */
    static final float F = 1068f, CAM_H = 2.69f;
    static final float ROAD_HALF = 5.4f, RUMBLE = 0.55f, EDGE = 0.12f, LANE_W = 0.13f;
    static final float STRIPE_M = 5f, DASH_M = 12f, DASH_ON = 5f, GRID_M = 10f;
    static final float Z_FAR = 1500f;

    static final int GRID_C = 0xffff4fd8;

    final int width, height;
    final float horizon, carZ, centreX;
    final int[] px;
    final Bitmap bitmap;
    private final float[] rowZ;
    private float k;

    RoadRenderer(int width, float horizon, float bottom, float carY) {
        this.width = width;
        this.height = Math.max(1, Math.round(bottom - horizon));
        this.horizon = horizon;
        this.centreX = width / 2f;
        this.carZ = F * CAM_H / (carY - horizon);
        px = new int[width * height];
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        rowZ = new float[height + 1];
        for (int r = 0; r <= height; r++) rowZ[r] = F * CAM_H / (r + 0.5f);
    }

    /** Lateral road-centre offset (m) at distance z for curvature k (1/m, + bends right). */
    float curve(float z) { float d = z - carZ; return d <= 0 ? 0 : 0.5f * k * d * d; }
    /** Screen x of a point `lateral` metres right of the road centre at distance z. */
    float x(float z, float lateral) { return centreX + F * (curve(z) + lateral) / z; }
    float yAt(float z) { return horizon + F * CAM_H / z; }
    float zAt(float y) { return F * CAM_H / Math.max(0.5f, y - horizon); }
    /** Where the road disappears: used for streaks and the light wash. */
    float vanishingX() { return x(600f, 0); }

    void setCurvature(float k) { this.k = k; }

    /** Renders the neon night ground (kept for callers and tests that do not pick a theme). */
    void render(double metres) { render(metres, Theme.ROAD[Theme.NIGHT], Theme.ROAD[Theme.NIGHT], 0f); }

    private final int[] pal = new int[15];

    /**
     * Renders the ground for the car at `metres` along the road with palette `from` blending into `to`
     * by u (0..1), then uploads it to {@link #bitmap}. Palettes are {@link Theme#ROAD} rows.
     */
    void render(double metres, int[] from, int[] to, float u) {
        for (int i = 0; i < pal.length; i++) {
            boolean number = i == Theme.GRASS_W || i == Theme.GRID;
            pal[i] = number ? Math.round(from[i] + (to[i] - from[i]) * u) : NeonRenderer.mix(from[i], to[i], u);
        }
        float grassW = pal[Theme.GRASS_W] / 255f, gridA = pal[Theme.GRID] / 255f;
        int haze = pal[Theme.HAZE];
        for (int r = 0; r < height; r++) {
            int row = r * width;
            float z = rowZ[r], zNext = rowZ[r + 1];
            float tr = r / (float) height;
            int base = tr < 0.35f ? NeonRenderer.mix(pal[Theme.G_TOP], pal[Theme.G_MID], tr / 0.35f)
                : NeonRenderer.mix(pal[Theme.G_MID], pal[Theme.G_BOT], (tr - 0.35f) / 0.65f);
            if (z > Z_FAR) {
                Arrays.fill(px, row, row + width, NeonRenderer.mix(base, haze, 0.8f));
                continue;
            }
            double world = metres + (z - carZ), worldNext = metres + (zNext - carZ);
            float span = z - zNext;
            boolean stripe = (((long) Math.floor(world / STRIPE_M)) & 1) == 0;
            int fill = base;
            if (gridA > 0.004f) {
                if (span >= GRID_M) fill = NeonRenderer.mix(base, GRID_C, gridA * Math.min(0.55f, 0.55f * GRID_M / span));
                else if (Math.floor(world / GRID_M) != Math.floor(worldNext / GRID_M)) fill = NeonRenderer.mix(base, GRID_C, gridA * 0.85f);
            }
            if (grassW > 0.004f) {
                // Classic alternating grass bands (wider than the road stripes), hazier with distance.
                boolean band = (((long) Math.floor(world / (STRIPE_M * 2))) & 1) == 0;
                int grass = NeonRenderer.mix(band ? pal[Theme.GRASS_A] : pal[Theme.GRASS_B], pal[Theme.GRASS_HAZE], Math.min(0.85f, z / 900f));
                fill = NeonRenderer.mix(fill, grass, grassW);
            }
            Arrays.fill(px, row, row + width, fill);
            if (gridA > 0.004f) {
                // Neon grid lines running along the road, bending with it.
                float fade = Math.max(0.12f, 1f - z / 700f) * gridA;
                float gw = Math.max(1f, F * 0.10f / z);
                for (int i = 0; i < 26; i++) {
                    float lat = ROAD_HALF + 3f + i * 5.5f;
                    blendSpan(row, x(z, -lat) - gw / 2, x(z, -lat) + gw / 2, GRID_C, 0.8f * fade);
                    blendSpan(row, x(z, lat) - gw / 2, x(z, lat) + gw / 2, GRID_C, 0.8f * fade);
                }
            }
            float cx = x(z, 0), half = F * ROAD_HALF / z, rum = Math.max(1f, F * RUMBLE / z);
            int asphalt = stripe ? pal[Theme.ASPH_A] : pal[Theme.ASPH_B], rumble = stripe ? pal[Theme.RUMB_A] : pal[Theme.RUMB_B];
            fillSpan(row, cx - half - rum, cx - half, rumble);
            fillSpan(row, cx + half, cx + half + rum, rumble);
            fillSpan(row, cx - half, cx + half, asphalt);
            float ew = Math.max(1f, F * EDGE / z);
            fillSpan(row, cx - half, cx - half + ew, pal[Theme.EDGE]);
            fillSpan(row, cx + half - ew, cx + half, pal[Theme.EDGE]);
            double m = world - Math.floor(world / DASH_M) * DASH_M;
            if (m < DASH_ON) {
                float lw = Math.max(1f, F * LANE_W / z);
                for (int side = -1; side <= 1; side += 2) {
                    float lx = cx + side * F * (ROAD_HALF / 3f) / z;
                    fillSpan(row, lx - lw / 2, lx + lw / 2, pal[Theme.LANE]);
                }
            }
            if (r < 14) { // distance haze right under the horizon
                float h = (14 - r) / 14f * 0.65f;
                for (int i = row; i < row + width; i++) px[i] = NeonRenderer.mix(px[i], haze, h);
            }
        }
        bitmap.setPixels(px, 0, width, 0, 0, width, height);
    }

    private void fillSpan(int row, float x0, float x1, int color) {
        int a = Math.max(0, Math.round(x0)), b = Math.min(width, Math.round(x1));
        if (b > a) Arrays.fill(px, row + a, row + b, color);
    }

    private void blendSpan(int row, float x0, float x1, int color, float alpha) {
        int a = Math.max(0, Math.round(x0)), b = Math.min(width, Math.max(Math.round(x0) + 1, Math.round(x1)));
        for (int i = row + a; i < row + b; i++) px[i] = NeonRenderer.mix(px[i], color, alpha);
    }
}
