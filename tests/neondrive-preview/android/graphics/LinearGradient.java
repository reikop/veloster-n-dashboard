package android.graphics;
public class LinearGradient extends Shader {
    public LinearGradient(float x0, float y0, float x1, float y1, int[] c, float[] p, TileMode t) {
        paint = new java.awt.LinearGradientPaint(new java.awt.geom.Point2D.Float(x0, y0), new java.awt.geom.Point2D.Float(x1, y1 == y0 && x1 == x0 ? y1 + 1 : y1), stops(p, c.length), colors(c));
    }
    public LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, TileMode t) { this(x0, y0, x1, y1, new int[] {c0, c1}, null, t); }
}
