package com.veloster.vehicle;

/**
 * String.format(Locale.US, …) for the numbers the screens draw every frame, without java.util.Formatter: on the head
 * unit each Formatter call builds a parser, a StringBuilder and, for %f, goes through ICU's decimal formatter; the
 * dashboard made dozens of them a frame. Handles literal text, %% and %[flags][width][.precision] with conversions
 * f, d and x and the flags + 0 , - (a space flag or anything else falls back to String.format). Rounding is half up at
 * the last digit (Formatter rounds the decimal digits of the value, so a tie like 0.125 can differ in the last place).
 * Formats are parsed once and kept. No Android: host-tested (FmtTest).
 */
public final class Fmt {
    private Fmt() {}
    private static final java.util.HashMap<String, Object[]> PARSED = new java.util.HashMap<String, Object[]>();
    private static final long[] POW = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000L};
    private static final StringBuilder OUT = new StringBuilder(32);
    private static final char[] DIG = new char[24];

    /** A spec: flags, width, precision (-1 none), conversion. */
    private static final class Spec { boolean plus, zero, group, left; int width, prec = -1; char conv; }

    public static String f(String format, double a) { return run(format, a, 0, 0, 1); }
    public static String f(String format, double a, double b) { return run(format, a, b, 0, 2); }
    public static String f(String format, double a, double b, double c) { return run(format, a, b, c, 3); }

    private static synchronized String run(String format, double a, double b, double c, int n) {
        Object[] parts = parse(format);
        if (parts == null || count(parts) != n) return fallback(format, a, b, c, n);
        StringBuilder o = OUT; o.setLength(0);
        int k = 0;
        for (Object p : parts) {
            if (p instanceof String) { o.append((String) p); continue; }
            double v = k == 0 ? a : k == 1 ? b : c; k++;
            if (!put(o, (Spec) p, v)) return fallback(format, a, b, c, n);
        }
        return o.toString();
    }

    private static int count(Object[] parts) { int n = 0; for (Object p : parts) if (p instanceof Spec) n++; return n; }

    private static Object[] parse(String f) {
        Object[] got = PARSED.get(f);
        if (got != null) return got.length == 0 ? null : got;
        java.util.ArrayList<Object> parts = new java.util.ArrayList<Object>();
        StringBuilder lit = new StringBuilder();
        boolean ok = true;
        for (int i = 0; i < f.length() && ok; ) {
            char ch = f.charAt(i);
            if (ch != '%') { lit.append(ch); i++; continue; }
            if (i + 1 < f.length() && f.charAt(i + 1) == '%') { lit.append('%'); i += 2; continue; }
            if (lit.length() > 0) { parts.add(lit.toString()); lit.setLength(0); }
            Spec s = new Spec(); i++;
            for (; i < f.length(); i++) {
                char g = f.charAt(i);
                if (g == '+') s.plus = true; else if (g == '0') s.zero = true; else if (g == ',') s.group = true; else if (g == '-') s.left = true;
                else break;
            }
            while (i < f.length() && Character.isDigit(f.charAt(i))) s.width = s.width * 10 + (f.charAt(i++) - '0');
            if (i < f.length() && f.charAt(i) == '.') { i++; s.prec = 0; while (i < f.length() && Character.isDigit(f.charAt(i))) s.prec = s.prec * 10 + (f.charAt(i++) - '0'); }
            if (i >= f.length()) { ok = false; break; }
            s.conv = f.charAt(i++);
            if (s.conv == 'f') { if (s.prec < 0) s.prec = 6; if (s.prec >= POW.length) ok = false; }
            else if (s.conv == 'd' || s.conv == 'x') { if (s.prec >= 0 || (s.conv == 'x' && (s.plus || s.group))) ok = false; }
            else ok = false;
            parts.add(s);
        }
        if (lit.length() > 0) parts.add(lit.toString());
        Object[] r = ok ? parts.toArray() : new Object[0];
        PARSED.put(f, r);
        return ok ? r : null;
    }

    /** Appends one value; false when it needs the real Formatter (not finite, too large, an integer conversion of a fraction). */
    private static boolean put(StringBuilder o, Spec s, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        boolean neg = v < 0 || (v == 0 && 1 / v < 0);
        double av = Math.abs(v);
        long whole, frac = 0;
        int prec = 0;
        if (s.conv == 'f') {
            prec = s.prec;
            double scaled = av * POW[prec];
            if (scaled >= 9e15) return false;
            long r = (long) Math.floor(scaled + 0.5);
            whole = r / POW[prec]; frac = r % POW[prec];
        } else {
            if (av != Math.floor(av) || av >= 9e15) return false;
            whole = (long) av;
            if (s.conv == 'x' && neg) return false;
        }
        if (s.conv == 'd' && whole == 0) neg = false;   // %d of -0
        // digits of the whole part, right to left, with grouping
        int n = 0;
        if (s.conv == 'x') { do { DIG[n++] = Character.forDigit((int) (whole & 15), 16); whole >>>= 4; } while (whole != 0); }
        else { int g = 0; do { if (s.group && g == 3) { DIG[n++] = ','; g = 0; } DIG[n++] = (char) ('0' + whole % 10); whole /= 10; g++; } while (whole != 0); }
        int len = n + (prec > 0 ? prec + 1 : 0) + (neg || s.plus ? 1 : 0);
        int pad = Math.max(0, s.width - len);
        if (!s.left && !s.zero) for (int i = 0; i < pad; i++) o.append(' ');
        if (neg) o.append('-'); else if (s.plus) o.append('+');
        if (!s.left && s.zero) for (int i = 0; i < pad; i++) o.append('0');
        for (int i = n - 1; i >= 0; i--) o.append(DIG[i]);
        if (prec > 0) {
            o.append('.');
            for (long p = POW[prec - 1]; p > 0; p /= 10) { o.append((char) ('0' + frac / p)); frac %= p; }
        }
        if (s.left) for (int i = 0; i < pad; i++) o.append(' ');
        return true;
    }

    private static String fallback(String format, double a, double b, double c, int n) {
        Object[] args = new Object[n];
        int k = 0;
        for (int i = 0; i < format.length() && k < n; i++) {   // box each value for its conversion: an integer one takes a Long
            if (format.charAt(i) != '%') continue;
            int j = i + 1;
            while (j < format.length() && "+-0, #.123456789".indexOf(format.charAt(j)) >= 0) j++;
            if (j >= format.length()) break;
            char conv = format.charAt(j); i = j;
            if (conv == '%' || conv == 'n') continue;
            double v = k == 0 ? a : k == 1 ? b : c;
            args[k++] = "dxXoc".indexOf(conv) >= 0 && v == Math.floor(v) && !Double.isInfinite(v) ? (Object) (long) v : (Object) v;
        }
        for (; k < n; k++) args[k] = k == 0 ? a : k == 1 ? b : c;
        return String.format(java.util.Locale.US, format, args);
    }

}
