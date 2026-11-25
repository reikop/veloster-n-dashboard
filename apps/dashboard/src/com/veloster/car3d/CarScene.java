package com.veloster.car3d;

/**
 * Backdrops of the 내 차 screen: the sky / floor around the car and what the paint reflects. No Android: host-tested.
 * Colours are linear 0..1 RGB triples.
 */
public final class CarScene {
    public static final int STUDIO = 0, SUNSET = 1, GARAGE = 2, NIGHT_ROAD = 3, COUNT = 4;
    public static final String[] NAMES = {"스튜디오", "노을", "차고", "야간 도로"};

    /** The 360-degree panorama of each backdrop (scripts/car3d_backdrops.py), drawn behind the car and reflected by it. */
    public static final String[] FILES = {"car3d/bg_studio.jpg", "car3d/bg_sunset.jpg", "car3d/bg_garage.jpg", "car3d/bg_night.jpg"};
    /** List swatch: sky, horizon, floor. */
    static final float[][] SKY_TOP = {{0.03f, 0.035f, 0.05f}, {0.10f, 0.07f, 0.22f}, {0.09f, 0.09f, 0.10f}, {0.008f, 0.012f, 0.035f}};
    static final float[][] SKY_HORIZON = {{0.5f, 0.5f, 0.52f}, {1.0f, 0.50f, 0.25f}, {0.36f, 0.36f, 0.38f}, {0.9f, 0.6f, 0.3f}};
    /** Floor near the car. */
    static final float[][] FLOOR = {{0.10f, 0.105f, 0.125f}, {0.075f, 0.06f, 0.07f}, {0.24f, 0.24f, 0.25f}, {0.045f, 0.045f, 0.05f}};
    /** How mirror-like the floor is (polished studio floor, dusty road, epoxy, wet asphalt) and the reflection strength on the car. */
    static final float[] FLOOR_REFLECT = {0.35f, 0.12f, 0.16f, 0.55f};
    static final float[] ENV_GAIN = {1.0f, 1.0f, 0.9f, 1.5f};
    /** Key light colour (its direction stays) and how much ambient light there is. */
    static final float[][] KEY = {{1f, 1f, 1f}, {1.0f, 0.72f, 0.5f}, {1.0f, 0.97f, 0.9f}, {0.32f, 0.35f, 0.48f}};
    static final float[] AMBIENT = {1f, 0.9f, 1.1f, 0.7f};

    private CarScene() {}

    public static int valid(int scene) { return scene >= 0 && scene < COUNT ? scene : STUDIO; }
}
