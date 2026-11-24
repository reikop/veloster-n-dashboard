package com.veloster.neondrive;

/**
 * Decodes the four hscand MICOM function records Neon Drive mirrors.
 * Read-only observations; no Android dependency, no CAN writes.
 *
 * Function bytes come from the captured 250902 Can2B20JSNMessage table and bit
 * layouts from the OEM telematics parsers (Can2BCsm, Can2ACsmPHEV parseCGW1):
 *   0x09 CGW1  (0x541, 8 bytes)  turn lamps, hazard switch, low beam, light switch
 *   0x10 CLU11 (0x4F1, 4 bytes)  cluster speed, unit
 *   0x30 ESP12 (0x220, 8 bytes)  master cylinder pressure
 *   0x73 EMS12 (0x329, 8 bytes)  brake switch, accelerator pedal
 *   0x53 SAS11 (0x2B0, 5 bytes)  steering wheel angle (raw SAS: positive = left)
 */
public final class DriveSignals {
    public static final int CGW1 = 0x09, CLU11 = 0x10, ESP12 = 0x30, EMS12 = 0x73, SAS11 = 0x53;
    /** TCU11 / TCU12 (CAN 0x111 / 0x112 on the JSN map): all zero on the owner's 6MT (10/21, 10/28 captures); a DCT is
     *  expected to fill them. Used only to guess the gearbox (dctSeen), not decoded; unconfirmed on a DCT car. */
    public static final int TCU11 = 0x5e, TCU12 = 0x5f;
    /** Records arrive about every 90 ms; anything older is not mirrored. */
    public static final long FRESH_MS = 1500;
    /** A turn lamp counts as blinking for this long after it was last lit. */
    public static final long BLINK_HOLD_MS = 1100;
    /** Snapshot gear values besides 1..6. The last received gear is held when packets stop. */
    public static final int GEAR_UNKNOWN = -1, GEAR_NEUTRAL = 0, GEAR_R = 10;

    public static boolean accepts(int function, int size) {
        return (function == CGW1 && size == 8) || (function == CLU11 && size == 4)
            || (function == ESP12 && size == 8) || (function == EMS12 && size == 8)
            || (function == SAS11 && size == 5) || ((function == TCU11 || function == TCU12) && size == 8);
    }

    public static final class Snapshot {
        /** km/h, NaN when unknown or stale. */
        public float speedKmh = Float.NaN;
        public boolean mph;
        /** Accelerator pedal 0..100 %, NaN when unknown. */
        public float pedal = Float.NaN;
        /** -1 unknown, 0 released, 1 pressed. */
        public int brake = -1;
        /** Master cylinder bar, NaN when unknown. */
        public float brakeBar = Float.NaN;
        /** Steering wheel degrees, positive = right (the dashboard's corrected display sign); NaN when unknown. */
        public float steerDeg = Float.NaN;
        public boolean leftActive, rightActive, leftLit, rightLit;
        /** True when the lit flags mirror the real lamp output, false when only a switch is known. */
        public boolean lampPhaseLive;
        public boolean hazardSwitch, lowBeam;
        /** Light switch (CF_Gway_LightSwStat): 0 off, 1 tail lamps, 2 headlamps, 3 auto; -1 unknown. */
        public int lightSwitch = -1;
        /** Tail lamps lit: switch at tail/headlamps, or the low beam is on (auto). Valid only when lampsKnown. */
        public boolean lamps, lampsKnown;
        /**
         * Gear: 1..8 (7 and 8 only on the 8-speed DCT, unverified), {@link #GEAR_R} while the rear camera view is up (reverse, inferred), {@link #GEAR_NEUTRAL}
         * when the last packet carried no forward gear, {@link #GEAR_UNKNOWN} until the first packet.
         */
        public int gear = GEAR_UNKNOWN;
        /** Parking brake switch (CGW1 ParkBrakeSw, b7 bit 4; 1 on the 10/28 parked capture). */
        public boolean parkBrake;
        /** Any accepted record within FRESH_MS. */
        public boolean live;
        public long records;
    }

    private long speedAt = Long.MIN_VALUE, pedalAt = Long.MIN_VALUE, brakeAt = Long.MIN_VALUE,
        cylinderAt = Long.MIN_VALUE, cgwAt = Long.MIN_VALUE, lastAt = Long.MIN_VALUE, steerAt = Long.MIN_VALUE;
    private long leftOnAt = Long.MIN_VALUE, rightOnAt = Long.MIN_VALUE;
    private float speedKmh, pedal, cylinder, steerRaw;
    private boolean mph, speedValid, cylinderValid;
    private int brakeAct, lampLeft, lampRight, hazard, lowBeam, stalkLeft, stalkRight, lightSw, parkSw;
    private long records;
    private long gearAt = Long.MIN_VALUE;
    private int gearRaw, gearFlag, rearView = -1;

    /**
     * OEM NModeListener.onNAddInfoReceived payload (19 or 40 bytes): byte 4 gear, byte 5 = 1 marks no valid
     * gear (same rule as the N dashboard's Telemetry.additional). Read-only listener data. Manual 1..6 is the
     * verified range; 7..8 are accepted for the DCT on the assumption that it uses the same byte (not verified).
     */
    public synchronized void gearInfo(byte[] b, long now) {
        if (b == null || (b.length != 19 && b.length != 40)) return;
        gearRaw = b[4]; gearFlag = b[5]; gearAt = now;
        if (gearRaw == 7 || gearRaw == 8) dctSeen = true;   // a 6MT never reports 7th or 8th
    }

    /** OEM PgsStatusListener rear view status: 1 and 3 show the reverse camera (N dashboard rule). */
    public synchronized void rearView(int view) { rearView = view; }

    public synchronized void clear() {
        speedAt = pedalAt = brakeAt = cylinderAt = cgwAt = lastAt = leftOnAt = rightOnAt = steerAt = gearAt = Long.MIN_VALUE;
        rearView = -1;
        records = 0;
    }

    private boolean dctSeen;
    /** Signs of the 8-speed DCT this session: a non-zero TCU frame or a 7th / 8th gear. A guess, unconfirmed on a DCT car. */
    public synchronized boolean dctSeen() { return dctSeen; }

    public synchronized void record(int function, byte[] b, long now) {
        if (b == null || !accepts(function, b.length)) return;
        if (function == TCU11 || function == TCU12) { for (byte v : b) if (v != 0) dctSeen = true; return; }
        records++; lastAt = now;
        if (function == CLU11) {
            int raw = ((b[2] & 1) << 8) | (b[1] & 255);
            mph = ((b[2] >> 1) & 1) == 1;
            speedValid = raw != 511;
            speedKmh = mph ? raw * 0.5f * 1.609344f : raw * 0.5f;
            speedAt = now;
        } else if (function == EMS12) {
            brakeAct = b[4] & 3;
            pedal = Math.min(100f, (b[6] & 255) * 0.3906f);
            brakeAt = pedalAt = now;
        } else if (function == SAS11) {
            // Signed little-endian x0.1 deg (OEM parseSAS11); 0x7fff is the invalid marker.
            int raw = (short) (((b[1] & 255) << 8) | (b[0] & 255));
            steerRaw = raw == 0x7fff ? Float.NaN : raw * 0.1f;
            steerAt = now;
        } else if (function == ESP12) {
            int raw = ((b[4] & 0x3f) << 6) | ((b[3] >> 2) & 0x3f);
            cylinderValid = raw != 0xfff;
            cylinder = raw * 0.1f;
            cylinderAt = now;
        } else {
            lampLeft = (b[2] >> 3) & 3;
            lampRight = (b[7] >> 6) & 3;
            hazard = (b[4] >> 1) & 3;
            lowBeam = (b[3] >> 7) & 1;
            lightSw = (b[4] >> 6) & 3;
            stalkLeft = (b[5] >> 1) & 1;
            stalkRight = (b[5] >> 2) & 1;
            parkSw = (b[7] >> 4) & 1;
            if (lampLeft == 1) leftOnAt = now;
            if (lampRight == 1) rightOnAt = now;
            cgwAt = now;
        }
    }

    private static boolean fresh(long at, long now) { return at != Long.MIN_VALUE && now - at >= 0 && now - at < FRESH_MS; }

    public synchronized Snapshot snapshot(long now) { return snapshot(now, new Snapshot()); }

    public synchronized Snapshot snapshot(long now, Snapshot s) {
        s.records = records;
        s.live = fresh(lastAt, now);
        s.speedKmh = fresh(speedAt, now) && speedValid ? speedKmh : Float.NaN;
        s.mph = mph;
        s.pedal = fresh(pedalAt, now) ? pedal : Float.NaN;
        s.brake = !fresh(brakeAt, now) ? -1 : brakeAct == 2 ? 1 : brakeAct == 1 ? 0 : -1;
        s.brakeBar = fresh(cylinderAt, now) && cylinderValid ? cylinder : Float.NaN;
        s.steerDeg = fresh(steerAt, now) && !Float.isNaN(steerRaw) ? (steerRaw == 0 ? 0f : -steerRaw) : Float.NaN;
        boolean cgw = fresh(cgwAt, now);
        boolean leftBlink = cgw && leftOnAt != Long.MIN_VALUE && now - leftOnAt < BLINK_HOLD_MS;
        boolean rightBlink = cgw && rightOnAt != Long.MIN_VALUE && now - rightOnAt < BLINK_HOLD_MS;
        s.hazardSwitch = cgw && hazard == 1;
        s.lowBeam = cgw && lowBeam == 1;
        s.lightSwitch = cgw ? lightSw : -1;
        s.lampsKnown = cgw;
        s.parkBrake = cgw && parkSw == 1;
        s.lamps = cgw && (lowBeam == 1 || lightSw == 1 || lightSw == 2);
        s.leftActive = leftBlink || (cgw && stalkLeft == 1) || s.hazardSwitch;
        s.rightActive = rightBlink || (cgw && stalkRight == 1) || s.hazardSwitch;
        // Mirror the real lamp only while it is demonstrably blinking; otherwise the view self-times.
        s.lampPhaseLive = cgw && (leftBlink || rightBlink);
        s.leftLit = cgw && lampLeft == 1;
        s.rightLit = cgw && lampRight == 1;
        if (rearView == 1 || rearView == 3) s.gear = GEAR_R;
        else if (gearAt == Long.MIN_VALUE) s.gear = GEAR_UNKNOWN;   // never received; afterwards hold the last gear
        else s.gear = gearRaw >= 1 && gearRaw <= 8 && gearFlag != 1 ? gearRaw : GEAR_NEUTRAL;
        return s;
    }
}
