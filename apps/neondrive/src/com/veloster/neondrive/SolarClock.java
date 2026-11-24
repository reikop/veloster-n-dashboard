package com.veloster.neondrive;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sun position from the date and time (NOAA "General Solar Position Calculations":
 * fractional year, equation of time, declination, hour angle). Accurate to a few minutes,
 * which is plenty for picking a time of day. Pure Java so it is tested on the host.
 *
 * The location is fixed to Seoul; across mainland Korea sunrise/sunset differ by roughly
 * 10-15 minutes from it. No location permission is used.
 */
public final class SolarClock {
    public static final double SEOUL_LAT = 37.5665, SEOUL_LON = 126.9780;
    /** Apparent sunrise/sunset: centre of the sun 0.833 deg below the horizon (refraction + radius). */
    static final double SUNRISE_ALT = -0.833;

    private SolarClock() {}

    /** Fractional year (radians) for a UTC instant. */
    static double gamma(long utcMillis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(utcMillis);
        int days = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        double hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0;
        return 2 * Math.PI / days * (c.get(Calendar.DAY_OF_YEAR) - 1 + (hour - 12) / 24);
    }

    /** Equation of time in minutes. */
    static double eqTime(double g) {
        return 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
            - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
    }

    /** Solar declination in radians. */
    static double declination(double g) {
        return 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
            + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
    }

    /** {altitude deg, hour angle deg (negative before solar noon)} at a UTC instant. */
    public static double[] position(long utcMillis, double lat, double lon) {
        double g = gamma(utcMillis);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(utcMillis);
        double minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double trueSolar = minutes + eqTime(g) + 4 * lon;
        double ha = trueSolar / 4 - 180;
        while (ha < -180) ha += 360;
        while (ha > 180) ha -= 360;
        double phi = Math.toRadians(lat), d = declination(g);
        double cosZen = Math.sin(phi) * Math.sin(d) + Math.cos(phi) * Math.cos(d) * Math.cos(Math.toRadians(ha));
        double alt = 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZen))));
        return new double[] {alt, ha};
    }

    /**
     * Sunrise and sunset as minutes after local midnight for the local date containing `utcMillis`
     * in `zone`. Returns {NaN, NaN} for polar day/night (never happens in Korea).
     */
    public static double[] sunriseSunset(long utcMillis, TimeZone zone, double lat, double lon) {
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(utcMillis);
        local.set(Calendar.HOUR_OF_DAY, 12); local.set(Calendar.MINUTE, 0); local.set(Calendar.SECOND, 0); local.set(Calendar.MILLISECOND, 0);
        long noonUtc = local.getTimeInMillis();
        double g = gamma(noonUtc), d = declination(g), phi = Math.toRadians(lat);
        double cosH = (Math.sin(Math.toRadians(SUNRISE_ALT)) - Math.sin(phi) * Math.sin(d)) / (Math.cos(phi) * Math.cos(d));
        if (cosH < -1 || cosH > 1) return new double[] {Double.NaN, Double.NaN};
        double h = Math.toDegrees(Math.acos(cosH));
        double solarNoonUtc = 720 - 4 * lon - eqTime(g);               // minutes after UTC midnight
        double offset = zone.getOffset(noonUtc) / 60000.0;
        return new double[] {wrap(solarNoonUtc - 4 * h + offset), wrap(solarNoonUtc + 4 * h + offset)};
    }

    static double wrap(double m) { m %= 1440; return m < 0 ? m + 1440 : m; }

    public static String hhmm(double minutes) {
        if (Double.isNaN(minutes)) return "--:--";
        int m = (int) Math.round(minutes) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }
}
