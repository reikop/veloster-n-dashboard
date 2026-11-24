package android.graphics;
public class Canvas {
    final java.awt.Graphics2D g;
    final java.util.ArrayDeque<java.awt.geom.AffineTransform> transforms = new java.util.ArrayDeque<java.awt.geom.AffineTransform>();
    final java.util.ArrayDeque<java.awt.Shape> clips = new java.util.ArrayDeque<java.awt.Shape>();
    final java.awt.image.BufferedImage target;
    public Canvas(Bitmap b) {
        target = b.image; g = b.image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
    }
    public int save() { transforms.push(g.getTransform()); clips.push(g.getClip() == null ? new java.awt.Rectangle(-100000, -100000, 200000, 200000) : g.getClip()); return transforms.size(); }
    public void restore() { g.setTransform(transforms.pop()); g.setClip(clips.pop()); }
    public void restoreToCount(int n) { while (transforms.size() >= n) restore(); }
    public void translate(float x, float y) { g.translate(x, y); }
    public void scale(float x, float y) { g.scale(x, y); }
    public void scale(float x, float y, float px, float py) { g.translate(px, py); g.scale(x, y); g.translate(-px, -py); }
    public boolean clipRect(float l, float t, float r, float b) { g.clip(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t)); return true; }
    public void drawColor(int c) {
        java.awt.geom.AffineTransform old = g.getTransform(); g.setTransform(new java.awt.geom.AffineTransform());
        g.setComposite(java.awt.AlphaComposite.Src); g.setPaint(new java.awt.Color(c, true));
        g.fillRect(0, 0, target.getWidth(), target.getHeight()); g.setTransform(old);
    }
    private void shape(java.awt.Shape s, Paint p) {
        p.apply(g);
        if (p.style != Paint.Style.STROKE) g.fill(s);
        if (p.style != Paint.Style.FILL) g.draw(s);
    }
    public void drawRect(float l, float t, float r, float b, Paint p) { shape(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t), p); }
    public void drawRect(RectF r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawOval(RectF r, Paint p) { shape(new java.awt.geom.Ellipse2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top), p); }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { shape(new java.awt.geom.RoundRectangle2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top, rx * 2, ry * 2), p); }
    /** Android angles run clockwise from 3 o'clock; AWT arcs run counter-clockwise. */
    public void drawArc(RectF r, float start, float sweep, boolean useCenter, Paint p) { shape(new java.awt.geom.Arc2D.Float(r.left, r.top, r.right - r.left, r.bottom - r.top, -start, -sweep, useCenter ? java.awt.geom.Arc2D.PIE : java.awt.geom.Arc2D.OPEN), p); }
    public void drawCircle(float x, float y, float rad, Paint p) { shape(new java.awt.geom.Ellipse2D.Float(x - rad, y - rad, rad * 2, rad * 2), p); }
    public void drawPath(Path path, Paint p) { shape(path.path, p); }
    public void drawLine(float a, float b, float c, float d, Paint p) { p.apply(g); g.draw(new java.awt.geom.Line2D.Float(a, b, c, d)); }
    public void drawText(String s, float x, float y, Paint p) {
        p.apply(g);
        float w = p.measureText(s);
        float at = p.align == Paint.Align.CENTER ? x - w / 2 : p.align == Paint.Align.RIGHT ? x - w : x;
        g.drawString(s, at, y);
    }
    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        float alpha = p == null ? 1f : ((p.color >>> 24) & 255) / 255f;
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
        java.awt.image.BufferedImage img = src == null ? b.image : b.image.getSubimage(src.left, src.top, src.right - src.left, src.bottom - src.top);
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        at.translate(dst.left, dst.top);
        at.scale((dst.right - dst.left) / img.getWidth(), (dst.bottom - dst.top) / img.getHeight());
        Object hint = p != null && !p.filter() ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, hint);
        g.drawImage(img, at, null);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
    public void rotate(float degrees, float px, float py) { g.rotate(Math.toRadians(degrees), px, py); }
}
