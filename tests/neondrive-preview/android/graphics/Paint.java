package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Join { MITER, ROUND, BEVEL }
    Join join = Join.MITER;
    public void setStrokeJoin(Join j) { join = j; }
    int color = 0xff000000; float width = 0, size = 12; Style style = Style.FILL; Cap cap = Cap.BUTT;
    Align align = Align.LEFT; Shader shader; Typeface typeface = Typeface.DEFAULT;
    public Paint() {}
    int flags;
    public Paint(int flags) { this.flags = flags; }
    boolean filter() { return (flags & FILTER_BITMAP_FLAG) != 0; }
    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
    public void setAlpha(int a) { color = (color & 0xffffff) | ((a & 255) << 24); }
    public void setStyle(Style s) { style = s; }
    public void setStrokeWidth(float w) { width = w; }
    public void setStrokeCap(Cap c) { cap = c; }
    public Shader setShader(Shader s) { shader = s; return s; }
    public void setTextSize(float s) { size = s; }
    public void setTypeface(Typeface t) { typeface = t; }
    public void setTextAlign(Align a) { align = a; }
    java.awt.Font font() { return new java.awt.Font(typeface.family, typeface.style & 3, 12).deriveFont(size); }
    public float measureText(String s) { return (float) font().getStringBounds(s, new java.awt.font.FontRenderContext(null, true, true)).getWidth(); }
    void apply(java.awt.Graphics2D g) {
        float alpha = ((color >>> 24) & 255) / 255f;
        if (shader != null) { g.setPaint(shader.paint); g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha)); }
        else { g.setPaint(new java.awt.Color(color, true)); g.setComposite(java.awt.AlphaComposite.SrcOver); }
        int c = cap == Cap.ROUND ? java.awt.BasicStroke.CAP_ROUND : cap == Cap.SQUARE ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT;
        g.setStroke(new java.awt.BasicStroke(Math.max(width, 0.0001f), c, join == Join.ROUND ? java.awt.BasicStroke.JOIN_ROUND : join == Join.BEVEL ? java.awt.BasicStroke.JOIN_BEVEL : java.awt.BasicStroke.JOIN_MITER));
        g.setFont(font());
    }
}
