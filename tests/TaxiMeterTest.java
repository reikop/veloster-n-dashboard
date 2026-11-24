import com.veloster.vehicle.TaxiMeter;

public final class TaxiMeterTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** Runs the meter at a constant speed for the given seconds in 100 ms steps. */
    private static void drive(TaxiMeter m, float kmh, double seconds, int hour) {
        for (int i = 0; i < Math.round(seconds * 10); i++) m.update(kmh, 100, hour);
    }

    public static void main(String[] args) {
        TaxiMeter m = new TaxiMeter();
        check(m.state() == TaxiMeter.VACANT && m.fare() == 0, "vacant shows no fare");
        m.update(60, 100, 12);
        check(m.distanceKm() == 0, "vacant meter does not run");

        m.start(12);
        check(m.fare() == 4800, "day base fare 4,800");
        drive(m, 60, 96, 12);                       // 1,600 m at 60 km/h: still the base fare
        check(m.fare() == 4800 && Math.abs(m.distanceKm() - 1.6) < 0.01, "base covers 1.6 km: " + m.fare());
        drive(m, 60, 131 / (60 / 3.6), 12);          // one more 131 m step (plus rounding slack)
        drive(m, 60, 0.2, 12);
        check(m.fare() == 4900, "100 won per 131 m after the base: " + m.fare());

        TaxiMeter slow = new TaxiMeter();
        slow.start(12);
        drive(slow, 0, 1600 / 131.0 * 30, 12);      // standing still: base distance in time (about 6 min 6 s)
        drive(slow, 0, 30.2, 12);                   // then 30 s more
        check(slow.fare() == 4900 && slow.distanceKm() == 0, "100 won per 30 s below 15.72 km/h: " + slow.fare());
        check(slow.slowMs() > 360000, "slow time counted");

        TaxiMeter fast = new TaxiMeter();
        fast.start(12);
        drive(fast, 20, 60, 12);                    // above 15.72 km/h only distance counts: 333 m
        check(Math.abs(fast.distanceKm() - 0.333) < 0.002 && fast.slowMs() == 0, "distance mode above the slow limit");

        TaxiMeter night = new TaxiMeter();
        night.start(23);
        check(night.fare() == 6700, "23-02 h base fare +40 % = 6,700: " + night.fare());
        night.reset(); night.start(22);
        check(night.fare() == 5800, "22-23 h base fare +20 % = 5,800: " + night.fare());
        check(TaxiMeter.nightRate(3) == 0.2f && TaxiMeter.nightRate(1) == 0.4f && TaxiMeter.nightRate(4) == 0f && TaxiMeter.nightRate(21) == 0f, "night hours");

        TaxiMeter out = new TaxiMeter();
        out.toggleOutOfCity();
        out.start(12);
        check(out.fare() == 5800 && out.outOfCity(), "out-of-city +20 %: " + out.fare());

        m.stop();
        int held = m.fare();
        drive(m, 60, 60, 12);
        check(m.state() == TaxiMeter.PAID && m.fare() == held, "paid meter holds the fare");
        m.reset();
        check(m.state() == TaxiMeter.VACANT && m.fare() == 0 && m.distanceKm() == 0, "reset to vacant");

        TaxiMeter gap = new TaxiMeter();
        gap.start(12);
        gap.update(60, 5000, 12);
        gap.update(Float.NaN, 100, 12);
        check(gap.distanceKm() == 0 && gap.elapsedMs() == 0, "long gaps and unknown speed pause the meter");

        // Tokyo tariff: 500 yen for 1.096 km, 100 yen per 255 m, per 95 s at 10 km/h or below, night +20 % by shorter steps.
        TaxiMeter jp = new TaxiMeter();
        check(jp.setRegion(TaxiMeter.JAPAN) && jp.region() == TaxiMeter.JAPAN, "switch to Japan");
        jp.start(12, 1000L);
        check(jp.fare() == 500, "Tokyo base 500 yen: " + jp.fare());
        drive(jp, 36, 1096 / 10.0, 12);                 // 1,096 m at 10 m/s
        check(jp.fare() == 500, "base covers 1.096 km: " + jp.fare());
        drive(jp, 36, 25.6, 12);                        // 256 m more
        check(jp.fare() == 600, "100 yen per 255 m: " + jp.fare());
        TaxiMeter jpSlow = new TaxiMeter(); jpSlow.setRegion(TaxiMeter.JAPAN); jpSlow.start(12);
        drive(jpSlow, 5, 1096 / 255.0 * 95 + 95.5, 12); // base equivalent in time, then 95 s more
        check(jpSlow.fare() == 600, "100 yen per 95 s at 10 km/h or below: " + jpSlow.fare());
        TaxiMeter jpNight = new TaxiMeter(); jpNight.setRegion(TaxiMeter.JAPAN); jpNight.start(23);
        check(jpNight.fare() == 600, "late night base 500 x 1.2 = 600: " + jpNight.fare());
        drive(jpNight, 36, 1096 / 10.0 + 21.5, 23);     // 1,096 m + 215 m (255 / 1.2 = 212.5 m step)
        check(jpNight.fare() == 700, "late night steps of 212.5 m: " + jpNight.fare());
        jpNight.toggleOutOfCity();
        check(jpNight.rate(23) == 0.2f, "Japan: manual 割増 and night do not stack");
        jp.stop(5000L);
        check(jp.receiptNo() == 1, "receipt number counts paid rides");
        check(jp.startWall() == 1000L && jp.endWall() == 5000L && jp.baseCharged() == 500 && jp.stepsCharged() == 100, "receipt figures");
        TaxiMeter busy = new TaxiMeter(); busy.start(12);
        check(busy.setRegion(TaxiMeter.JAPAN) && busy.region() == TaxiMeter.JAPAN && busy.state() == TaxiMeter.VACANT, "region switch while hired clears the ride");

        check(TaxiMeter.gallopFps(0) == 0f && TaxiMeter.gallopFps(Float.NaN) == 0f, "horse stands when stopped");
        check(TaxiMeter.gallopFps(5) < 5f && TaxiMeter.gallopFps(100) > 18f && TaxiMeter.gallopFps(200) == TaxiMeter.gallopFps(100), "gallop rate spans slow to fast and caps at 100 km/h");
        System.out.println("PASS: taxi meter base, distance, time, night, out-of-city, payment and pauses");
    }
}
