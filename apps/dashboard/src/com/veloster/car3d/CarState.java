package com.veloster.car3d;

import com.veloster.neondrive.DriveSignals;

/**
 * What the 3D car shows, from Neon Drive's passive signals (DriveSignals) plus the screen's test buttons: lamp
 * brightness per lamp group (the numbers scripts/car3d_model.py writes), the front wheels' steer angle and the
 * wheels' roll. No Android: host-tested.
 *
 * Signals (DriveSignals, read-only hscand records): turn lamps follow the real lamp output while it is seen blinking
 * (CGW1 lamp bits), otherwise a stalk / hazard switch blinks on a 0.75 s timer; headlamps = light switch at
 * headlamps or the low beam on (auto); tail lamps = DriveSignals.lamps; stop lamps = brake switch; reversing lamps =
 * the rear camera view up (reverse, inferred); steering = SAS11 wheel angle.
 */
public final class CarState {
    /** Lamp groups (vertex attribute of the mesh). */
    public static final int NONE = 0, HEAD = 1, POSITION = 2, TAIL = 3, STOP = 4, TURN_L = 5, TURN_R = 6, REVERSE = 7, GROUPS = 9;
    /** Test buttons (bit mask). */
    public static final int TEST_LEFT = 1, TEST_RIGHT = 2, TEST_HAZARD = 4, TEST_TAIL = 8, TEST_HEAD = 16, TEST_STOP = 32,
        TEST_REVERSE = 64, TEST_STEER = 128;
    /** Steering wheel degrees per road-wheel degree (approximate rack ratio; unverified for the N). */
    public static final float STEER_RATIO = 13f;
    /** Front wheel lock, degrees. */
    public static final float MAX_WHEEL_DEG = 38f;
    /** Tyre radius from the model (235/35 R19: 0.3235 m). */
    public static final float TYRE_RADIUS_M = 0.3236f;
    /** Self-timed blink when only the switch is known (about 80 per minute). */
    public static final long BLINK_PERIOD_MS = 750, BLINK_ON_MS = 400;

    /** Lamp brightness 0..1 by group (smoothed, what the renderer uses). */
    public final float[] glow = new float[GROUPS];
    private final float[] target = new float[GROUPS];
    /** Road-wheel steer angle, degrees, positive = the wheels point left (rotation about +Y). */
    public float wheelDeg;
    /** Accumulated wheel roll of the drawn wheel mesh, degrees (positive = rolling forward); capped at ROLL_CAP_DPS. */
    public float rollDeg;
    /** Metres driven (the 야간 도로 lane marks scroll by it), wrapped at 1 km. */
    public float distanceM;
    /** Motion blur over the rims, 0..1: at ~30 frames a second a fast wheel would strobe (look still or backwards). */
    public float blur;
    /** Real wheel speed (deg/s) where the blur starts and where it is full (about 9 and 25 km/h). */
    public static final float BLUR_FROM_DPS = 450f, BLUR_FULL_DPS = 1250f;
    /** The drawn mesh never turns faster than this, so the spokes under the blur still read as rolling forwards. */
    public static final float ROLL_CAP_DPS = 600f;
    /** Shown values. */
    public boolean live, left, right, leftActive, rightActive, hazard, tail, head, stop, reverse;
    /** Steering wheel degrees (positive = right) and km/h; NaN when unknown. */
    public float steerDeg = Float.NaN, speedKmh = Float.NaN;
    private long last = Long.MIN_VALUE, testStart = Long.MIN_VALUE;
    /** Body on its springs: pitch (deg, positive = nose down) and roll (deg, positive = left side up), wheels stay put. */
    public float pitchDeg, rollBodyDeg;
    private float pitchVel, rollVel;
    /** G driving the body this frame (longitudinal: + accelerating, - braking; lateral: + = cornering left), 0 when unknown. */
    public float longG, latG;
    /** Flip if the car's lateral G sensor turns out to read + in right-hand bends (sign unverified on the car). */
    public static final float LAT_SIGN = 1f;
    /** Body angle per g (exaggerated a little so it reads on screen) and limits; a 1.6 Hz spring, damping 0.45. */
    public static final float PITCH_PER_G = 4.5f, ROLL_PER_G = 4.0f, MAX_PITCH = 3.5f, MAX_ROLL = 3.5f, WHEELBASE_M = 2.655f;
    private static final float OMEGA = 10f, ZETA = 0.45f;

    private int lastTests;

    /** Advance to `now` (ms, monotonic) from a snapshot (null = no car data) and the test buttons; no G sensor. */
    public void update(DriveSignals.Snapshot s, int tests, long now) { update(s, Float.NaN, Float.NaN, tests, now); }

    /**
     * Advance with the car's G sensor too (the dashboard's Telemetry: longitudinal + accelerating / - braking, lateral;
     * NaN = unknown): the body pitches and rolls with it.
     */
    public void update(DriveSignals.Snapshot s, float gLong, float gLat, int tests, long now) {
        float dt = last == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(0.25f, (now - last) / 1000f));
        last = now;
        if ((tests & TEST_STEER) != 0 && (lastTests & TEST_STEER) == 0) testStart = now;
        lastTests = tests;
        live = s != null && s.live;
        boolean timer = (now % BLINK_PERIOD_MS) < BLINK_ON_MS;

        hazard = (tests & TEST_HAZARD) != 0 || live && s.hazardSwitch;
        leftActive = hazard || (tests & TEST_LEFT) != 0 || live && s.leftActive;
        rightActive = hazard || (tests & TEST_RIGHT) != 0 || live && s.rightActive;
        boolean testBlink = (tests & (TEST_LEFT | TEST_RIGHT | TEST_HAZARD)) != 0;
        if (live && s.lampPhaseLive && !testBlink) { left = s.leftLit; right = s.rightLit; }   // the real lamp phase
        else { left = leftActive && timer; right = rightActive && timer; }

        head = (tests & TEST_HEAD) != 0 || live && (s.lightSwitch == 2 || s.lowBeam);
        tail = head || (tests & TEST_TAIL) != 0 || live && s.lamps;
        stop = (tests & TEST_STOP) != 0 || live && s.brake == 1;
        reverse = (tests & TEST_REVERSE) != 0 || live && s.gear == DriveSignals.GEAR_R;

        float speed = live && !Float.isNaN(s.speedKmh) ? s.speedKmh : 0f;
        float steer = live ? s.steerDeg : Float.NaN;
        if ((tests & TEST_STEER) != 0) {   // a slow lock-to-lock sweep while the speed swings 5..60 km/h (shows the blur)
            double t = (now - testStart) / 1000.0;
            steer = (float) (Math.sin(t * Math.PI / 3.0) * 420.0);
            speed = Math.max(speed, (float) (32.5 - 27.5 * Math.cos(t * Math.PI / 5.0)));
        }
        steerDeg = steer; speedKmh = live || (tests & TEST_STEER) != 0 ? speed : Float.NaN;

        target[NONE] = 0;
        target[HEAD] = head ? 1f : 0f;
        target[POSITION] = tail ? 0.85f : 0f;
        target[TAIL] = stop ? 1f : tail ? 0.45f : 0f;
        target[STOP] = stop ? 1f : 0f;
        target[TURN_L] = left ? 1f : 0f;
        target[TURN_R] = right ? 1f : 0f;
        target[REVERSE] = reverse ? 1f : 0f;
        target[8] = 0;
        for (int g = 0; g < GROUPS; g++) {
            float tau = target[g] > glow[g] ? 0.04f : 0.10f;   // quick on, a bulb's short afterglow off
            glow[g] = dt <= 0 ? target[g] : glow[g] + (target[g] - glow[g]) * Math.min(1f, dt / tau);
        }

        float want = Float.isNaN(steer) ? 0f : clamp(-steer / STEER_RATIO, -MAX_WHEEL_DEG, MAX_WHEEL_DEG);
        wheelDeg = dt <= 0 ? want : wheelDeg + (want - wheelDeg) * Math.min(1f, dt / 0.08f);
        float dps = (float) Math.toDegrees(speed / 3.6f / TYRE_RADIUS_M);
        float drawn = Math.min(dps, ROLL_CAP_DPS) * (reverse ? -1f : 1f);
        rollDeg = (float) ((rollDeg + drawn * dt) % 360.0);
        distanceM = (distanceM + speed / 3.6f * (reverse ? -1f : 1f) * dt + 1000f) % 1000f;
        float b = clamp((dps - BLUR_FROM_DPS) / (BLUR_FULL_DPS - BLUR_FROM_DPS), 0f, 1f);
        blur = b * b * (3 - 2 * b);
        suspension(gLong, gLat, dt);   // the body follows the G sensor only (test buttons never tilt it)
    }

    private void suspension(float gLong, float gLat, float dt) {
        longG = clamp(Float.isNaN(gLong) ? 0f : gLong, -1.5f, 1.5f);
        latG = clamp(Float.isNaN(gLat) ? 0f : gLat * LAT_SIGN, -1.5f, 1.5f);
        float pt = clamp(-longG * PITCH_PER_G, -MAX_PITCH, MAX_PITCH);   // braking: nose down
        float rt = clamp(latG * ROLL_PER_G, -MAX_ROLL, MAX_ROLL);        // turning left leans the body right: left side up
        // Damped spring in 10 ms steps (stable even at the 5 Hz idle tick).
        for (float left = dt; left > 1e-4f; left -= 0.01f) {
            float h = Math.min(0.01f, left);
            pitchVel += (OMEGA * OMEGA * (pt - pitchDeg) - 2 * ZETA * OMEGA * pitchVel) * h; pitchDeg += pitchVel * h;
            rollVel += (OMEGA * OMEGA * (rt - rollBodyDeg) - 2 * ZETA * OMEGA * rollVel) * h; rollBodyDeg += rollVel * h;
        }
        if (dt <= 0) { pitchDeg = pt; rollBodyDeg = rt; pitchVel = rollVel = 0; }
    }

    /** Anything still moving on screen (blinking, fading, rolling, steering) - the view keeps drawing frames. */
    public boolean animating() {
        if (leftActive || rightActive || Math.abs(speedKmh) > 0.1f) return true;
        if (Math.abs(pitchVel) > 0.02f || Math.abs(rollVel) > 0.02f || Math.abs(longG) > 0.01f || Math.abs(latG) > 0.01f) return true;
        for (int g = 0; g < GROUPS; g++) if (Math.abs(glow[g] - target[g]) > 0.002f) return true;
        float want = Float.isNaN(steerDeg) ? 0f : clamp(-steerDeg / STEER_RATIO, -MAX_WHEEL_DEG, MAX_WHEEL_DEG);
        return Math.abs(want - wheelDeg) > 0.01f;
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
