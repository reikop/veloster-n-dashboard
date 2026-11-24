package android.graphics;
public class RadialGradient extends Shader {
    public RadialGradient(float x, float y, float r, int[] c, float[] p, TileMode t) {
        paint = new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(x, y), r, stops(p, c.length), colors(c));
    }
}
