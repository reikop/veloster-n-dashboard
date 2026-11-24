package android.graphics;
public class Shader {
    public enum TileMode { CLAMP }
    java.awt.Paint paint;
    static java.awt.Color[] colors(int[] c) { java.awt.Color[] r = new java.awt.Color[c.length]; for (int i = 0; i < c.length; i++) r[i] = new java.awt.Color(c[i], true); return r; }
    static float[] stops(float[] p, int n) {
        float[] r = new float[n];
        for (int i = 0; i < n; i++) r[i] = p == null ? i / (float) (n - 1) : p[i];
        for (int i = 1; i < n; i++) if (r[i] <= r[i - 1]) r[i] = Math.min(1f, r[i - 1] + 1e-4f);
        return r;
    }
}
