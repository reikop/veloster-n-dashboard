import com.veloster.vehicle.Fmt;
import java.util.Locale;
import java.util.Random;

/** Fmt matches String.format(Locale.US, …) on the dashboard's formats, apart from rare last-digit ties. */
public final class FmtTest {
    public static void main(String[] a) {
        String[] f1 = {"%.0f", "%.1f", "%.2f", "%.3f", "%+.1f", "%+.2f", "%.2f g", " / %.1f bar", "GPS ±%.0f m", "PEAK %.2f g", "%.0f km/h", "%5.1f", "%-6.1f|", "%06.2f"};
        String[] fi = {"%d", "%02d", "%04d", "No.%04d", "%4d", "%,d", "%+,d rpm", "%02x", "%-4d|"};
        Random r = new Random(7);
        int n = 0, ties = 0;
        for (int i = 0; i < 20000; i++) {
            float v = (float) ((r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(6)));
            for (String f : f1) {
                String want = String.format(Locale.US, f, v), got = Fmt.f(f, v); n++;
                if (!want.equals(got)) {
                    // only a tie at the last digit may differ (Formatter rounds the shortest decimal digits)
                    int p = f.contains(".3") ? 3 : f.contains(".2") ? 2 : f.contains(".1") ? 1 : 0;
                    double scaled = Math.abs((double) v) * Math.pow(10, p), d = Math.abs(scaled - Math.floor(scaled) - 0.5);
                    if (d > 1e-6) throw new AssertionError(f + " " + v + ": " + got + " vs " + want);
                    ties++;
                }
            }
            long k = r.nextInt(2000000) - (r.nextBoolean() ? 1000000 : 0);
            for (String f : fi) {
                if (f.contains("x") && k < 0) continue;
                String want = String.format(Locale.US, f, k), got = Fmt.f(f, k); n++;
                if (!want.equals(got)) throw new AssertionError(f + " " + k + ": " + got + " vs " + want);
            }
        }
        check(Fmt.f("%d:%02d", 5, 7).equals("5:07") && Fmt.f("%d:%02d:%02d", 1, 2, 3).equals("1:02:03") && Fmt.f("%.0f→%.0f", 0, 100).equals("0→100"), "several values");
        check(Fmt.f("%.1f", Float.NaN).equals("NaN") && Fmt.f("%.0f", Double.POSITIVE_INFINITY).equals("Infinity"), "non-finite falls back");
        check(Fmt.f("%.1f", -0.04).equals(String.format(Locale.US, "%.1f", -0.04)), "negative rounding to zero keeps its sign as Formatter does");
        check(Fmt.f("%,d %s".substring(0, 3), 1234).equals("1,234"), "grouping");
        check(Fmt.f("% d", 5).equals(String.format(Locale.US, "% d", 5)), "a space flag falls back to Formatter");
        check(Fmt.f("100%% %.0f", 3).equals("100% 3"), "percent sign");
        System.out.println("PASS " + n + " format checks (" + ties + " last-digit ties)");
    }
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
}
