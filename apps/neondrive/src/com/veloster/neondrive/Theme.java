package com.veloster.neondrive;

/**
 * Times of day for the scene and the automatic choice between them. Pure Java (host tests).
 *
 * Auto follows the clock against today's sunrise and sunset times ({@link SolarClock}, from the date and
 * the GPS position); the sun's altitude is not used:
 *   sunrise - 30 min .. sunrise + 90 min: morning;
 *   until sunset - 60 min: day;
 *   sunset - 60 min .. sunset + 30 min: sunset;
 *   otherwise night.
 * The tail lamp signal is not used either: on the car the CGW1 low-beam bit read 1 in broad daylight with
 * the switch on AUTO (10/21 08:41 capture; 11/24 report of night shown well after sunrise).
 */
public final class Theme {
    public static final int NIGHT = 0, MORNING = 1, DAY = 2, SUNSET = 3, COUNT = 4;
    /** Settings values: 0 auto, then 1 + theme in display order morning, day, sunset, night. */
    public static final int MODE_AUTO = 0;
    public static final int[] MODE_THEME = {-1, MORNING, DAY, SUNSET, NIGHT};
    public static final String[] MODE_KO = {"자동", "아침", "주간", "석양", "야간"};
    public static final String[] MODE_EN = {"Auto", "Morning", "Day", "Sunset", "Night"};

    /** Minutes around sunrise/sunset. */
    public static final int MORNING_BEFORE = 30, MORNING_AFTER = 90, SUNSET_BEFORE = 60, SUNSET_AFTER = 30;
    /** Used when sunrise/sunset are unknown (NaN). */
    static final double FALLBACK_SUNRISE = 6 * 60, FALLBACK_SUNSET = 18 * 60;

    private Theme() {}

    /**
     * Resolves the theme for a settings mode from the local time and today's sunrise/sunset,
     * all in minutes after local midnight.
     */
    public static int resolve(int mode, double nowMin, double sunriseMin, double sunsetMin) {
        if (mode > 0 && mode < MODE_THEME.length) return MODE_THEME[mode];
        double rise = Double.isNaN(sunriseMin) ? FALLBACK_SUNRISE : sunriseMin;
        double set = Double.isNaN(sunsetMin) ? FALLBACK_SUNSET : sunsetMin;
        if (nowMin < rise - MORNING_BEFORE) return NIGHT;
        if (nowMin < rise + MORNING_AFTER) return MORNING;
        if (nowMin < set - SUNSET_BEFORE) return DAY;
        if (nowMin < set + SUNSET_AFTER) return SUNSET;
        return NIGHT;
    }

    // Road palette entries.
    static final int G_TOP = 0, G_MID = 1, G_BOT = 2, GRASS_A = 3, GRASS_B = 4, GRASS_W = 5, ASPH_A = 6, ASPH_B = 7,
        RUMB_A = 8, RUMB_B = 9, EDGE = 10, LANE = 11, HAZE = 12, GRID = 13, GRASS_HAZE = 14;

    /**
     * Per theme: ground gradient (neon style), shoulder bands (grass/sand) with weight 0..255, road colours, haze,
     * grid alpha 0..255. Night and sunset are the neon city; morning and day the coast (sand shoulders, light asphalt).
     */
    static final int[][] ROAD = {
        // night: neon grid on dark ground, cyan rumble
        {0xff2a0a40, 0xff12042a, 0xff07020f, 0, 0, 0, 0xff1d0c35, 0xff26113f, 0xff19e6ff, 0xff0d5c78, 0xffd8fbff, 0xfff4e6fb, 0xff7a2276, 255, 0},
        // morning: pale sand, soft grey road, peach mist
        {0xffe8d6b8, 0xffe2cfae, 0xffd8c4a0, 0xffecdab6, 0xffe3cea6, 255, 0xff8a8a96, 0xff9494a0, 0xffe85a4a, 0xfff6f0ea, 0xffffffff, 0xffffffff, 0xfff3dccb, 0, 0xfff0e2d0},
        // day: bright sand, light asphalt, white/grey rumble
        {0xfff0dcae, 0xffe6cf9c, 0xffdcc490, 0xfff2deb0, 0xffe7d09e, 255, 0xff8e8e98, 0xff9a9aa4, 0xfff4f4f4, 0xffb4b4be, 0xffffffff, 0xffffffff, 0xffd6ecf6, 0, 0xffeee6d4},
        // sunset: dim warm grass, dusky road, orange haze, faint grid
        {0xff5a2a50, 0xff2e1636, 0xff120814, 0xff415c36, 0xff38522f, 210, 0xff3e3846, 0xff48414f, 0xffd8402e, 0xffd9c8c0, 0xffffe0d0, 0xfffff0e0, 0xffff9a6a, 60, 0xffc8705a},
    };

    /** How strongly night-time lights show per theme: lamp glow, headlight wash, stars, building beacons. */
    static final float[] GLOW = {1f, 0.1f, 0f, 0.65f};
    static final float[] STARS = {1f, 0f, 0f, 0.25f};
    /** Morning and day show the coast (sea, sand, tropical palms); night and sunset the neon city skyline. */
    static boolean daylitProps(int theme) { return theme == MORNING || theme == DAY; }
}
