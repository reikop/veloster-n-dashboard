package com.veloster.vehicle;

/**
 * Taxi fare meter for the dashboard's taxi page, with Korean regional tariffs (17 시·도, TaxiTariff) and a Japanese
 * (Tokyo) tariff. In Korea the trip keeps the table of the area where it started (place(): the GPS business area, else
 * the last known one); entering another area adds that table's out-of-area surcharge after 3 agreeing seconds, and the
 * meter starts on its own when the car moves off (autoStart). Regional rules from the i30 PD build
 * (sung9155/i30-pd-dashboard, MIT).
 * Pure Java so the fare rules are tested on the host. An informational display, not a certified meter.
 *
 * Distance and time are combined like real meters: below the tariff's slow speed, time advances the
 * meter (one step of time equals one step of distance), above it distance does. The meter only advances
 * while hired and only from reported speed; unknown speed or gaps over 2 s pause it.
 */
public final class TaxiMeter {
    public static final int VACANT = 0, HIRED = 1, PAID = 2;
    public static final int KOREA = 0, JAPAN = 1;

    /** Fare rules of one region. */
    public static final class Tariff {
        public final int baseFare, stepFare, round;
        public final float baseM, stepM, stepS, slowKmh, manualRate;
        /** true: surcharges shorten the step distance/time (Japan); false: they raise each step's fare (Korea). */
        public final boolean shortenSteps;
        /** true: night and manual surcharges add (Korea); false: the larger one applies (Japan). */
        public final boolean additive;
        private final int region;
        /** The Korean area table behind this tariff (night bands, combined rates, far steps); null for Tokyo. */
        public final TaxiTariff area;

        Tariff(int region, int baseFare, float baseM, int stepFare, float stepM, float stepS, float slowKmh,
               float manualRate, boolean shortenSteps, boolean additive, int round, TaxiTariff area) {
            this.region = region; this.baseFare = baseFare; this.baseM = baseM; this.stepFare = stepFare; this.stepM = stepM;
            this.stepS = stepS; this.slowKmh = slowKmh; this.manualRate = manualRate; this.shortenSteps = shortenSteps;
            this.additive = additive; this.round = round; this.area = area;
        }

        /** Night surcharge by wall-clock hour. */
        public float night(int hour) {
            if (area != null) return area.night(hour) / 100f;
            if (region == JAPAN) return hour >= 22 || hour < 5 ? 0.2f : 0f;           // 22:00-05:00 +20 %
            if (hour == 23 || (hour >= 0 && hour < 2)) return 0.4f;                    // Seoul 23-02 +40 %
            if (hour == 22 || (hour >= 2 && hour < 4)) return 0.2f;                    // 22-23, 02-04 +20 %
            return 0f;
        }
    }

    /**
     * Seoul mid-size taxi, effective 2023-02-01: 4,800 won for 1.6 km, then 100 won per 131 m or per 30 s
     * below 15.72 km/h; night surcharge by the clock; out-of-city +20 % (manual). Rounded to 100 won.
     */
    public static final Tariff SEOUL = korea("서울");
    /** A Korean area's mid-size table: base fare and distance, 100 won per step (제주 120 past 20 km), time steps below
     *  the speed where a time step equals a distance step (Seoul: 131 m / 30 s = 15.72 km/h). */
    public static Tariff korea(String name) {
        TaxiTariff t = TaxiTariff.of(name);
        return new Tariff(KOREA, t.base, t.baseM, 100, t.stepM, t.stepS, t.stepM / (float) t.stepS * 3.6f, t.out / 100f, false, true, 100, t);
    }
    /**
     * Tokyo special wards (Musashino, Mitaka), effective 2022-11-14: 500 yen for 1.096 km, then 100 yen per
     * 255 m or per 95 s at 10 km/h or below; late-night 22:00-05:00 +20 % (manual "割増" applies the same),
     * applied by shortening the steps like Japanese meters. Rounded to 10 yen.
     */
    public static final Tariff TOKYO = new Tariff(JAPAN, 500, 1096f, 100, 255f, 95f, 10.01f, 0.2f, true, false, 10, null);
    // Kept for callers that use the Seoul figures directly.
    public static final int BASE_FARE = 4800, STEP_FARE = 100;
    public static final float BASE_M = 1600f, STEP_M = 131f, STEP_S = 30f, SLOW_KMH = 15.72f, OUT_OF_CITY = 0.2f;

    private Tariff tariff = SEOUL;
    private int state = VACANT;
    private double meterM, distanceM, fareRaw, stepProgress;
    private long elapsedMs, slowMs;
    private int steps;
    private boolean manual;
    private long startWall, endWall;
    private int receiptNo;
    private double baseCharged;
    /** Korean area: the table name in use, the GPS area seen last (-1 none), the trip's starting area, out of it. */
    private String area = "서울";
    private int zone = -1, startZone = -1, flip;
    private boolean zoneFresh, autoOut, waitStop;

    /** Horse animation rate (frames per second) for a speed: 0 when stopped, about 4 at 5 km/h, 19 from 100 km/h. */
    public static float gallopFps(float kmh) {
        if (Float.isNaN(kmh) || kmh < 1f) return 0f;
        return 3.2f + Math.min(kmh, 100f) * 0.158f;
    }

    /** Seoul night surcharge for an hour of the day (0..23). */
    public static float nightRate(int hour) { return SEOUL.night(hour); }

    public synchronized Tariff tariff() { return tariff; }
    public synchronized int region() { return tariff == TOKYO ? JAPAN : KOREA; }
    /** Switches region at any time; the meter returns to vacant (a running ride is cleared). */
    public synchronized boolean setRegion(int region) {
        tariff = region == JAPAN ? TOKYO : korea(area);
        manual = false;
        reset();
        return true;
    }
    /** The Korean table name in use (서울, 부산, …). */
    public synchronized String area() { return area; }
    /** Sets the Korean table outside a trip (a saved choice at start); a running trip keeps its table. */
    public synchronized boolean setArea(String name) {
        String n = TaxiTariff.of(name).region;
        if (state == HIRED || n.equals(area)) return false;
        area = n;
        if (tariff != TOKYO) tariff = korea(n);
        return true;
    }
    /**
     * Once a second: the GPS business area (TaxiZones index, -1 without a fresh fix or outside every area) and its
     * table name. Outside a trip the meter takes that area's table; during one, being in another area than the start
     * for 3 agreeing calls adds the out-of-area surcharge (the borders are simplified to 200 m). Returns true when the
     * table changed (the caller saves it).
     */
    public synchronized boolean place(int z, String table) {
        zoneFresh = z >= 0;
        if (z < 0) return false;
        zone = z;
        if (state != HIRED) return table != null && setArea(table);
        if (startZone < 0) startZone = z;   // started before the first fix: this is the trip's area
        boolean o = z != startZone;
        if (o == autoOut) flip = 0; else if (++flip >= 3) { autoOut = o; flip = 0; }
        return false;
    }
    /** In another area than the trip's start (from the GPS). */
    public synchronized boolean autoOutside() { return autoOut; }
    /** The car has a fresh GPS area. */
    public synchronized boolean located() { return zoneFresh; }
    /**
     * Starts a trip when the car moves off (over 3 km/h). After a trip ends or the meter is cleared while moving, the
     * car must stop (3 km/h or less) first, so a receipt stays up until the next departure. Returns true when it started.
     */
    public synchronized boolean autoStart(float kmh, int hour) {
        if (Float.isNaN(kmh)) return false;
        if (kmh <= 3) { waitStop = false; return false; }
        if (state == HIRED || waitStop) return false;
        start(hour);
        return true;
    }

    public synchronized float nightNow(int hour) { return tariff.night(hour); }
    public synchronized float rate(int hour) {
        if (tariff.area != null) return tariff.area.surcharge(hour, manual || autoOut);
        float night = tariff.night(hour), extra = manual ? tariff.manualRate : 0f;
        return tariff.additive ? night + extra : Math.max(night, extra);
    }

    /** Starts a ride (from vacant or paid): fare = surcharged base fare. */
    public synchronized void start(int hour) { start(hour, System.currentTimeMillis()); }
    public synchronized void start(int hour, long wallMs) {
        meterM = distanceM = stepProgress = 0; elapsedMs = slowMs = 0; steps = 0;
        baseCharged = fareRaw = tariff.baseFare * (1 + rate(hour));
        startWall = wallMs; endWall = 0;
        startZone = zoneFresh ? zone : -1; autoOut = false; flip = 0;
        state = HIRED;
    }

    /** Ends the ride and holds the fare for payment (the receipt). */
    public synchronized void stop() { stop(System.currentTimeMillis()); }
    public synchronized void stop(long wallMs) { if (state == HIRED) { state = PAID; endWall = wallMs; receiptNo++; waitStop = true; } }
    /** Receipt number, counting paid rides since the app started. */
    public synchronized int receiptNo() { return receiptNo; }

    /** Receipt figures: wall-clock start/end (ms, 0 unknown), surcharged base fare, distance/time steps charged. */
    public synchronized long startWall() { return startWall; }
    public synchronized long endWall() { return endWall; }
    public synchronized int baseCharged() { return (int) (Math.round(baseCharged / tariff.round) * tariff.round); }
    public synchronized int stepsCharged() { return fare() - baseCharged(); }

    /** Clears the meter to vacant. The surcharge toggle and region are kept. */
    public synchronized void reset() {
        state = VACANT; meterM = distanceM = fareRaw = stepProgress = baseCharged = 0; elapsedMs = slowMs = 0; steps = 0;
        startWall = endWall = 0; autoOut = false; flip = 0; waitStop = true;
    }

    /** Manual surcharge: out-of-city in Korea, "割増" in Japan. */
    public synchronized void toggleOutOfCity() { manual = !manual; }
    /** The out-of-area (Japan: 割増) surcharge is on: by the button or, in Korea, from the GPS. */
    public synchronized boolean outOfCity() { return manual || autoOut; }
    /** That surcharge's rate on its own (the area table's out-of-area %, 0 where there is none). */
    public synchronized float outRate() { return tariff.manualRate; }

    /** Step length (m) at the current surcharge. */
    private double stepLength(float r) { return tariff.shortenSteps ? tariff.stepM / (1 + r) : tariff.stepM; }

    /**
     * Advances a running meter by dtMs at the reported speed (km/h, NaN when unknown) and the current
     * wall-clock hour. Gaps over 2 s (paused screen, stalled stream) are ignored rather than guessed.
     */
    public synchronized void update(float kmh, long dtMs, int hour) {
        if (state != HIRED || dtMs <= 0 || dtMs > 2000 || Float.isNaN(kmh) || kmh < 0) return;
        double seconds = dtMs / 1000.0;
        double moved = kmh / 3.6 * seconds;
        distanceM += moved;
        elapsedMs += dtMs;
        boolean slow = kmh < tariff.slowKmh;
        if (slow) slowMs += dtMs;
        double progress = slow ? seconds * tariff.stepM / tariff.stepS : moved;   // in distance-equivalent metres
        double before = meterM;
        meterM += progress;
        double beyond = meterM - Math.max(before, tariff.baseM);
        if (beyond <= 0) return;
        float r = rate(hour);
        stepProgress += beyond / stepLength(r);
        while (stepProgress >= 1) {
            stepProgress -= 1;
            int won = tariff.area != null ? tariff.area.stepWon(tariff.baseM + tariff.stepM * steps) : tariff.stepFare;   // 제주: 120 past 20 km
            steps++;
            fareRaw += won * (tariff.shortenSteps ? 1 : 1 + r);
        }
    }

    public synchronized int state() { return state; }
    /** Fare rounded to the tariff's unit (0 while vacant). */
    public synchronized int fare() { return state == VACANT ? 0 : (int) (Math.round(fareRaw / tariff.round) * tariff.round); }
    public synchronized double distanceKm() { return distanceM / 1000.0; }
    public synchronized long elapsedMs() { return elapsedMs; }
    public synchronized long slowMs() { return slowMs; }
    /** Distance-equivalent metres until the next step (base distance first), at the given hour's surcharge. */
    public synchronized float toNextStepM(int hour) {
        double step = stepLength(rate(hour));
        if (meterM < tariff.baseM) return (float) (tariff.baseM - meterM + step);
        return (float) Math.max(0, (1 - stepProgress) * step);
    }
    /** Same, at no surcharge. */
    public synchronized float toNextStepM() { return toNextStepM(12); }
    public synchronized int steps() { return steps; }
}
