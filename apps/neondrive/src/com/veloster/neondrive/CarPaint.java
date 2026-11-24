package com.veloster.neondrive;

/**
 * Factory exterior colours of the Korean Veloster N (Hyundai catalogue "veloster-n-catalog-03", Feb 2020):
 * Chalk White P6W, Lake Silver SS7, Performance Blue SFB, Ignite Flame MFR, Shooting Star T5R, Phantom Black MZH.
 * RGB values are screen approximations of the paints, not official colour data.
 * Pure Java so the repaint can be tested on the host.
 */
public final class CarPaint {
    public static final String[] NAME_KO = {"퍼포먼스 블루", "초크 화이트", "레이크 실버", "슈팅 스타", "팬텀 블랙", "이그나이트 플레임"};
    public static final String[] NAME_EN = {"Performance Blue", "Chalk White", "Lake Silver", "Shooting Star", "Phantom Black", "Ignite Flame"};
    public static final String[] CODE = {"SFB", "P6W", "SS7", "T5R", "MZH", "MFR"};
    /** Swatch / target body colour. Index 0 keeps the sprite's own blue. */
    public static final int[] RGB = {0xff6f9fd8, 0xffeceae4, 0xffaab1b8, 0xff6b6f74, 0xff1b1d22, 0xffcf3a22};
    public static final int COUNT = RGB.length;

    private CarPaint() {}

    static float[] hsv(int p) {
        float r = ((p >> 16) & 255) / 255f, g = ((p >> 8) & 255) / 255f, b = (p & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h = 0;
        if (d > 0) h = max == r ? ((g - b) / d + 6) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new float[] {h * 60, max == 0 ? 0 : d / max, max};
    }

    static int rgb(float h, float s, float v, int alpha) {
        h = ((h % 360) + 360) % 360 / 60f;
        int i = (int) h; float f = h - i;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q;
        }
        return alpha << 24 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    /** Body paint pixels of the sprite: blue, saturated and lit (dark navy outlines and glass are excluded). */
    static boolean isBody(int p) {
        if ((p >>> 24) == 0) return false;
        float[] c = hsv(p);
        return c[0] >= 190 && c[0] <= 240 && c[1] >= 0.22f && c[2] >= 0.42f;
    }

    /**
     * Repaints body pixels in place, keeping each pixel's brightness relative to the sprite's typical
     * body brightness so highlights and shadows survive. Other pixels are untouched.
     */
    public static void repaint(int[] px, int index) {
        if (index <= 0 || index >= COUNT) return;
        // Reference brightness: mean value of the body pixels.
        double sum = 0; int n = 0;
        for (int p : px) if (isBody(p)) { sum += hsv(p)[2]; n++; }
        if (n == 0) return;
        float ref = (float) (sum / n);
        float[] target = hsv(RGB[index]);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (!isBody(p)) continue;
            float t = hsv(p)[2] / ref;                       // ~0.6 shadow .. ~1.2 highlight
            float v = target[2] * t + (t - 1f) * 0.28f;      // keep visible shading on very dark/light paints
            v = Math.max(0.05f, Math.min(1f, v));
            float s = target[1] * (t > 1.08f ? 0.75f : 1f);  // highlights lose a little saturation
            px[i] = rgb(target[0], s, v, p >>> 24);
        }
    }
}
