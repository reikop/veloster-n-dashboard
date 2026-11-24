package com.veloster.neondrive;

/**
 * Turns vehicle observations (or the demo script) into smooth animation state.
 * Pure Java so the host tests and the preview renderer share it with the APK.
 */
public final class DriveModel {
    public static final int LIVE = 0, DEMO = 1, WAITING = 2;
    /** One world unit along the road, in metres. */
    public static final float METRES_PER_UNIT = 5f;
    /** Road curvature (1/m) at full bend: a 500 m radius bend. */
    public static final float CURVE_MAX = 0.002f;
    static final long SELF_BLINK_PERIOD_MS = 700, SELF_BLINK_ON_MS = 380;
    static final float CRUISE_KMH = 60f;
    /** Wheel angle that maps to a full bend on screen. */
    static final float FULL_STEER_DEG = 150f;
    public static final long DEMO_LOOP_MS = 40000;

    public static final class State {
        public int mode = WAITING;
        /** Smoothed values used for drawing. */
        public float speedKmh, throttle, brake, pitch, bob;
        /** HUD brake gauge 0..1 from the master cylinder pressure (the lamps use the on/off switch). */
        public float brakeGauge;
        /** Gear for the HUD shift pattern ({@link DriveSignals#GEAR_UNKNOWN}, neutral 0, 1..8, {@link DriveSignals#GEAR_R}). */
        public int gear = DriveSignals.GEAR_UNKNOWN;
        /** World position in units, grows forever (wrapped by the renderer). */
        public double distance;
        public boolean left, right, leftActive, rightActive, lowBeam, mph;
        /** Tail lamps on (drives the automatic day/night scene). Unknown or demo counts as on (night). */
        public boolean lamps = true, lampsKnown;
        /** Raw readings for the HUD; NaN when unknown. */
        public float shownSpeed = Float.NaN, shownPedal = Float.NaN;
        /** 0..1 while a lift-off backfire is showing. */
        public float pop;
        /** Seconds at standstill (0 while moving or without data); drives the stop card and idle scenery. */
        public float stoppedFor;
        /** Parking brake applied, and the eased 0..1 move to the shoulder. */
        public boolean parked;
        public float park;
        /** 0..1: showroom sweep of the car angles after a few seconds at standstill. */
        public float showroom;
        /** Trip since the app started: elapsed and moving time (ms), distance (km), top speed (km/h). */
        public double tripMs, movingMs, tripKm;
        public float maxKmh;
        public float avgKmh() { return movingMs > 1000 ? (float) (tripKm / (movingMs / 3600000.0)) : 0f; }
        /** Steering, -1 (full left) .. 1 (full right), smoothed. */
        public float steer;
        /** Road bend toward the steering side (grows with speed) and car yaw cue. */
        public float bend, yaw;
        /** Accumulated heading change along the bends (radians, + right); scrolls the sky. */
        public double heading;
        /** Wheel animation position; the renderer shows phase floor(wheel) mod 3. */
        public double wheel;
        /** Steering wheel degrees for the HUD, positive = right; NaN when unknown. */
        public float shownSteer = Float.NaN;
        public long now;
    }

    private final State state = new State();
    private final DriveSignals.Snapshot snap = new DriveSignals.Snapshot();
    private boolean demoForced, feedAvailable = true;
    private long highAt = Long.MIN_VALUE, popUntil = Long.MIN_VALUE, popStart;
    private float demoSpeed = 70f;
    private long demoOrigin = Long.MIN_VALUE;

    public void setDemoForced(boolean on) { demoForced = on; demoOrigin = Long.MIN_VALUE; }
    public boolean demoForced() { return demoForced; }
    private boolean eightSpeed;
    /** 8-speed DCT setting: only changes the demo's gear steps (live gears come from the car). */
    public void setEightSpeed(boolean on) { eightSpeed = on; }
    /** False when this device cannot provide the hscand stream at all (not the supported build). */
    public void setFeedAvailable(boolean available) { feedAvailable = available; }

    public State state() { return state; }

    public State update(DriveSignals signals, long now, float dt) {
        if (signals != null) signals.snapshot(now, snap); else snap.live = false;
        dt = Math.max(0f, Math.min(dt, 0.1f));
        State s = state;
        s.now = now;
        float targetSpeed, targetThrottle, targetBrake, targetSteer, targetGauge;
        boolean parked = false;
        float realSpeed = Float.NaN;
        boolean leftActive, rightActive, leftLit, rightLit, lowBeam = true, lamps = true;
        boolean selfBlink = (now % SELF_BLINK_PERIOD_MS) < SELF_BLINK_ON_MS;
        if (!demoForced && snap.live) {
            s.mode = LIVE;
            demoOrigin = Long.MIN_VALUE;
            targetSpeed = Float.isNaN(snap.speedKmh) ? s.speedKmh : snap.speedKmh;
            targetThrottle = Float.isNaN(snap.pedal) ? 0f : snap.pedal / 100f;
            // Brake lamps follow the pedal switch like the real car: pressed = full brightness.
            // Pressure is only a fallback when the switch is unknown (resting pressure reads 0.2-0.4 bar).
            if (snap.brake == 1) targetBrake = 1f;
            else if (snap.brake == 0) targetBrake = 0f;
            else targetBrake = !Float.isNaN(snap.brakeBar) && snap.brakeBar >= 1.5f ? 1f : 0f;
            targetGauge = Float.isNaN(snap.brakeBar) ? targetBrake * 0.3f : brakeGauge(snap.brakeBar);
            leftActive = snap.leftActive; rightActive = snap.rightActive;
            leftLit = leftActive && (snap.lampPhaseLive ? snap.leftLit : selfBlink);
            rightLit = rightActive && (snap.lampPhaseLive ? snap.rightLit : selfBlink);
            lowBeam = snap.lowBeam;
            lamps = !snap.lampsKnown || snap.lamps;
            s.lampsKnown = snap.lampsKnown;
            s.gear = snap.gear;
            parked = snap.parkBrake;
            realSpeed = snap.speedKmh;
            s.mph = snap.mph;
            s.shownSpeed = snap.speedKmh;
            s.shownPedal = snap.pedal;
            s.shownSteer = snap.steerDeg;
            targetSteer = Float.isNaN(snap.steerDeg) ? 0f : clamp(snap.steerDeg / FULL_STEER_DEG, -1f, 1f);
        } else if (demoForced || !feedAvailable) {
            s.mode = DEMO;
            s.lampsKnown = false;
            if (demoOrigin == Long.MIN_VALUE) demoOrigin = now;
            long t = (now - demoOrigin) % DEMO_LOOP_MS;
            float[] d = demo(t);
            targetThrottle = d[0]; targetBrake = d[1];
            targetGauge = d[1];
            float accel = targetThrottle * 5.5f - targetBrake * 9f - 0.25f - demoSpeed * 0.004f;
            targetBrake = targetBrake > 0 ? 1f : 0f; // lamps: on/off at full brightness
            demoSpeed = clamp(demoSpeed + accel * 3.6f * dt, 0f, 180f);
            targetSpeed = demoSpeed;
            s.gear = eightSpeed ? demoGear8(demoSpeed) : demoGear(demoSpeed);
            parked = d[5] > 0;
            realSpeed = demoSpeed;
            leftActive = d[2] > 0; rightActive = d[3] > 0;
            leftLit = leftActive && selfBlink; rightLit = rightActive && selfBlink;
            s.mph = false;
            s.shownSpeed = demoSpeed;
            s.shownPedal = targetThrottle * 100f;
            s.shownSteer = d[4];
            targetSteer = clamp(d[4] / FULL_STEER_DEG, -1f, 1f);
        } else {
            // Supported build but nothing received yet (ignition off, stream closed).
            s.mode = WAITING;
            s.lampsKnown = false;
            targetSpeed = CRUISE_KMH; targetThrottle = 0.12f; targetBrake = 0f; targetGauge = 0f;
            leftActive = rightActive = leftLit = rightLit = false;
            s.shownSpeed = Float.NaN; s.shownPedal = Float.NaN; s.shownSteer = Float.NaN;
            s.gear = snap.gear;   // gear comes from its own OEM listener; keep showing the last one
            targetSteer = 0f;
        }
        s.speedKmh += (targetSpeed - s.speedKmh) * Math.min(1f, dt * 4f);
        if (s.speedKmh < 0.2f && targetSpeed <= 0f) s.speedKmh = 0f;
        s.throttle += (targetThrottle - s.throttle) * Math.min(1f, dt * 10f);
        // On instantly, off with a ~0.1 s LED-like fade.
        if (targetBrake >= s.brake) s.brake = targetBrake;
        else s.brake += (targetBrake - s.brake) * Math.min(1f, dt * 30f);
        s.brakeGauge += (targetGauge - s.brakeGauge) * Math.min(1f, dt * 12f);
        float targetPitch = s.throttle * 1f - s.brake * 1.2f;
        s.pitch += (targetPitch - s.pitch) * Math.min(1f, dt * 6f);
        s.distance += s.speedKmh / 3.6f * dt / METRES_PER_UNIT;
        s.steer += (targetSteer - s.steer) * Math.min(1f, dt * 6f);
        // The road only bends while moving; the car still shows the wheel input when parked.
        s.bend = s.steer * Math.min(1f, s.speedKmh / 25f);
        s.yaw = s.steer * (0.35f + 0.65f * Math.min(1f, s.speedKmh / 40f));
        s.heading += s.speedKmh / 3.6f * s.bend * CURVE_MAX * dt;
        // Wheels: phase steps track speed, capped so the 3-phase cycle stays readable at 30 fps.
        s.wheel += Math.min(s.speedKmh, 60f) / 60f * 14f * dt;
        float rough = Math.min(1f, s.speedKmh / 120f);
        s.bob = (float) (Math.sin(s.distance * 2.3) * 0.6 + Math.sin(s.distance * 5.1) * 0.35) * rough;
        s.left = leftLit; s.right = rightLit;
        s.leftActive = leftActive; s.rightActive = rightActive;
        s.lowBeam = lowBeam;
        s.lamps = lamps;
        // Lift-off pops: pedal released quickly from a strong pull while moving.
        if (targetThrottle > 0.55f) highAt = now;
        if (targetThrottle < 0.08f && highAt != Long.MIN_VALUE && now - highAt < 350 && s.speedKmh > 15f) {
            popStart = now; popUntil = now + 900; highAt = Long.MIN_VALUE;
        }
        s.pop = now < popUntil ? 1f - (now - popStart) / 900f : 0f;
        // Standstill, parking and the trip, from the reported speed (not the smoothed drawing speed).
        boolean data = s.mode != WAITING && !Float.isNaN(realSpeed);
        if (data && realSpeed < 1f) s.stoppedFor += dt; else s.stoppedFor = 0f;
        s.parked = data && parked;
        s.park += ((s.parked ? 1f : 0f) - s.park) * Math.min(1f, dt * 0.9f);
        s.showroom += ((s.stoppedFor > SHOWROOM_AFTER_S ? 1f : 0f) - s.showroom) * Math.min(1f, dt * 1.2f);
        if (data) {
            s.tripMs += dt * 1000.0;
            if (realSpeed >= 1f) { s.movingMs += dt * 1000.0; s.tripKm += realSpeed / 3600.0 * dt; }
            s.maxKmh = Math.max(s.maxKmh, realSpeed);
        }
        return s;
    }

    /** Pressure at which the HUD gauge is full (bar), the same scale as the N dashboard's brake bar. */
    static final float GAUGE_FULL_BAR = 100f;

    /** Master cylinder pressure to the HUD gauge: linear 0..100 bar like the N dashboard (PitRenderer). */
    public static float brakeGauge(float bar) {
        return clamp(bar / GAUGE_FULL_BAR, 0f, 1f);
    }

    /** Demo gear from speed: neutral when stopped, then 1..6. */
    public static int demoGear(float kmh) {
        if (kmh < 1f) return DriveSignals.GEAR_NEUTRAL;
        float[] up = {20, 40, 65, 95, 125};
        int g = 1;
        for (float u : up) if (kmh >= u) g++;
        return g;
    }

    /** Demo gear for the 8-speed DCT: neutral when stopped, then 1..8. */
    public static int demoGear8(float kmh) {
        if (kmh < 1f) return DriveSignals.GEAR_NEUTRAL;
        float[] up = {15, 30, 48, 68, 90, 112, 135};
        int g = 1;
        for (float u : up) if (kmh >= u) g++;
        return g;
    }

    /** Standstill seconds before the showroom sweep starts. */
    static final float SHOWROOM_AFTER_S = 4f;

    /** Demo loop: {throttle, brake, left, right, steering deg (+right), parked}. Every effect shows within 40 s. */
    static float[] demo(long t) {
        float s = t / 1000f;
        if (s < 4) return new float[] {0.28f, 0, 0, 0, (float) Math.sin(s * 1.6) * 40f, 0}; // cruise, weaving
        if (s < 7.5f) return new float[] {0.95f, 0, 0, 0, 0, 0};    // full throttle
        if (s < 8.5f) return new float[] {0f, 0, 0, 0, 0, 0};       // lift-off pops
        if (s < 10.5f) return new float[] {0f, 0.7f, 0, 0, 0, 0};   // brake
        if (s < 14) return new float[] {0.25f, 0, 1, 0, s < 11.5f ? 0 : -110f, 0}; // signal, then turn left
        if (s < 17.5f) return new float[] {0.25f, 0, 0, 1, s < 15f ? 0 : 110f, 0}; // signal, then turn right
        if (s < 21) return new float[] {0f, 0.55f, 1, 1, 0, 0};     // hazard while stopping
        if (s < 27) return new float[] {0f, 0.8f, 0, 0, 0, 0};      // waiting at a light: stop card, showroom
        if (s < 35) return new float[] {0f, 0f, 0, 0, 0, 1};        // parking brake: shoulder, idle scenery
        return new float[] {0.7f, 0, 0, 0, 0, 0};                   // pull away
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
