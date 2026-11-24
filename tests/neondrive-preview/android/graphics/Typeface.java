package android.graphics;
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
    public static final Typeface DEFAULT = new Typeface("Dialog", 0), DEFAULT_BOLD = new Typeface("Dialog", 1), MONOSPACE = new Typeface("Monospaced", 0);
    final String family; final int style;
    private Typeface(String f, int s) { family = f; style = s; }
    public static Typeface create(Typeface base, int style) { return new Typeface(base.family, style); }
}
