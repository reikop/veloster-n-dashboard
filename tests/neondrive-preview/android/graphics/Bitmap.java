package android.graphics;
/** Host preview double backed by Java2D. Never packaged. */
public class Bitmap {
    public enum Config { ARGB_8888 }
    final java.awt.image.BufferedImage image;
    private Bitmap(int w, int h) { image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB); }
    public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w, h); }
    /** Preview-only: wrap a decoded host image (stands in for BitmapFactory). */
    public static Bitmap fromImage(java.awt.image.BufferedImage src) {
        Bitmap b = new Bitmap(src.getWidth(), src.getHeight());
        b.image.getGraphics().drawImage(src, 0, 0, null);
        return b;
    }
    public int getWidth() { return image.getWidth(); }
    public void getPixels(int[] px, int offset, int stride, int x, int y, int w, int h) { image.getRGB(x, y, w, h, px, offset, stride); }
    public Bitmap copy(Config c, boolean mutable) { return fromImage(image); }
    public void setPixels(int[] px, int offset, int stride, int x, int y, int w, int h) { image.setRGB(x, y, w, h, px, offset, stride); }
    public int getHeight() { return image.getHeight(); }
    public java.awt.image.BufferedImage image() { return image; }
}
