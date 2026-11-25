import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * The installers' one icon: a Veloster N side silhouette (pale body, dark glass, red rocker stripe, two wheels) on a
 * dark blue tile. One drawing, written out as
 *   installer/icon/veloster-n.svg            the master (108 x 108, the Android adaptive canvas)
 *   installer/icon/installer.ico             Windows (16..256 px, PNG entries): the window installer's window icon
 *   installer/icon/installer.icns            macOS (iconutil): the "N 계기판 설치.app" icon
 *   build/icon-preview.png                   a contact sheet of the sizes (not kept)
 *   installer/android/res/drawable/ic_launcher_fg.xml / ic_launcher_mono.xml   the phone installer's launcher icon
 * Paths use M L Q C Z only (absolute), so Java2D, SVG and Android vector drawables read the same strings.
 * usage (repo root, JDK 11+, macOS for the .icns):  java scripts/IconMaker.java
 */
public class IconMaker {
    // 108 x 108 canvas; the adaptive icon's safe circle is 66 across around (54, 54).
    static final String BODY = "M87,66 L89.6,62.2 Q89.8,59.4 88,58.6 L70.5,55.4 Q64.5,49.2 58.4,47.9 L47.4,47.1 Q40,47.3 34.2,48.5 "
        + "L30.4,48.7 L31.4,51.2 Q27,53 25.2,55.4 L24.2,61.2 Q24.2,64.6 25.6,66 "
        + "C25.6,61.4 29.4,57.6 34,57.6 C38.6,57.6 42.4,61.4 42.4,66 L66.6,66 C66.6,61.4 70.4,57.6 75,57.6 C79.6,57.6 83.4,61.4 83.4,66 Z";
    static final String GLASS = "M68.4,55.2 Q63.4,50.1 58,49.3 L47.6,48.7 Q41.2,48.9 37.2,50.1 L39.6,54.5 Z";
    static final String PILLAR = "M53.8,48.9 L55.2,49 L55.6,54.9 L54.2,54.9 Z";
    static final String STRIPE = "M44,63.2 L65,63.2 L65,64.8 L44,64.8 Z";
    static final String LAMP = "M24.6,56.2 L27.6,55.6 L27.3,57.9 L24.4,58.4 Z";
    static final double[][] WHEELS = {{34, 66}, {75, 66}};
    static final double TYRE = 6.2, RIM = 3.5;
    static final int BG_TOP = 0xff15325a, BG_BOTTOM = 0xff0a1424, BG_FLAT = 0xff0e1a2b;
    static final int CAR = 0xffe9eef4, GLASS_C = 0xff0e1a2b, RED = 0xffe5332a, TYRE_C = 0xff1d232b, RIM_C = 0xffa9b4c2, LAMP_C = 0xffe5332a;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Path icon = root.resolve("installer/icon"), res = root.resolve("installer/android/res/drawable");
        Files.createDirectories(icon); Files.createDirectories(res);
        int[] sizes = {16, 24, 32, 48, 64, 128, 256, 512, 1024};
        Map<Integer, BufferedImage> img = new TreeMap<>();
        for (int s : sizes) img.put(s, render(s, true));
        writeIco(icon.resolve("installer.ico"), img, new int[]{16, 24, 32, 48, 64, 128, 256});
        Path set = Files.createTempDirectory("vn-icon").resolve("installer.iconset"); Files.createDirectories(set);
        int[][] mac = {{16, 1}, {16, 2}, {32, 1}, {32, 2}, {128, 1}, {128, 2}, {256, 1}, {256, 2}, {512, 1}, {512, 2}};
        for (int[] m : mac) ImageIO.write(img.get(m[0] * m[1]), "png", set.resolve("icon_" + m[0] + "x" + m[0] + (m[1] == 2 ? "@2x" : "") + ".png").toFile());
        try {
            Process p = new ProcessBuilder("iconutil", "-c", "icns", set.toString(), "-o", icon.resolve("installer.icns").toString()).inheritIO().start();
            if (p.waitFor() != 0) System.err.println("iconutil failed; installer.icns not written");
        } catch (IOException e) { System.err.println("no iconutil (not macOS); installer.icns not written"); }
        Files.write(icon.resolve("veloster-n.svg"), svg().getBytes("UTF-8"));
        Files.write(res.resolve("ic_launcher_fg.xml"), vector(false).getBytes("UTF-8"));
        Files.write(res.resolve("ic_launcher_mono.xml"), vector(true).getBytes("UTF-8"));
        // preview: every size side by side, and the launcher's circle mask at 192
        BufferedImage sheet = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics(); g.setColor(new Color(0xf2f2f2)); g.fillRect(0, 0, 1200, 300);
        int x = 10; for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) { g.drawImage(img.get(s), x, 280 - s, null); x += s + 14; }
        BufferedImage round = render(192, false); BufferedImage m = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = m.createGraphics(); mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.fill(new Ellipse2D.Double(0, 0, 192, 192)); mg.setComposite(AlphaComposite.SrcIn); mg.drawImage(round, 0, 0, null); mg.dispose();
        g.drawImage(m, x + 10, 88, null); g.dispose();
        Files.createDirectories(root.resolve("build")); ImageIO.write(sheet, "png", root.resolve("build/icon-preview.png").toFile());
        System.out.println("icons written to " + icon + " and " + res);
    }

    /** The icon at s px. tile: the rounded dark-blue square (desktop icons); else the full adaptive background. */
    static BufferedImage render(int s, boolean tile) {
        BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double k = s / 108.0;
        Shape bg = tile ? new RoundRectangle2D.Double(s * 0.06, s * 0.06, s * 0.88, s * 0.88, s * 0.2, s * 0.2) : new Rectangle2D.Double(0, 0, s, s);
        g.setPaint(new GradientPaint(0, 0, new Color(BG_TOP, true), 0, s, new Color(BG_BOTTOM, true))); g.fill(bg);
        g.transform(AffineTransform.getScaleInstance(k, k));
        if (tile) { g.translate(54, 54); g.scale(1.26, 1.26); g.translate(-56.5, -60); }   // the tile has no safe-zone mask: a bigger car
        fill(g, BODY, CAR); fill(g, GLASS, GLASS_C); fill(g, PILLAR, CAR); fill(g, STRIPE, RED); fill(g, LAMP, LAMP_C);
        for (double[] w : WHEELS) { fill(g, circle(w[0], w[1], TYRE), TYRE_C); fill(g, circle(w[0], w[1], RIM), RIM_C); fill(g, circle(w[0], w[1], 1.2), TYRE_C); }
        g.dispose();
        return b;
    }
    static void fill(Graphics2D g, String d, int argb) { g.setColor(new Color(argb, true)); g.fill(path(d)); }

    /** A circle as four cubic arcs (M C C C C Z). */
    static String circle(double cx, double cy, double r) {
        double c = r * 0.5523;
        return String.format(Locale.US, "M%.2f,%.2f C%.2f,%.2f %.2f,%.2f %.2f,%.2f C%.2f,%.2f %.2f,%.2f %.2f,%.2f C%.2f,%.2f %.2f,%.2f %.2f,%.2f C%.2f,%.2f %.2f,%.2f %.2f,%.2f Z",
            cx + r, cy, cx + r, cy + c, cx + c, cy + r, cx, cy + r, cx - c, cy + r, cx - r, cy + c, cx - r, cy,
            cx - r, cy - c, cx - c, cy - r, cx, cy - r, cx + c, cy - r, cx + r, cy - c, cx + r, cy);
    }

    static Path2D path(String d) {
        Path2D.Double p = new Path2D.Double();
        Scanner sc = new Scanner(d.replace(",", " ").replaceAll("([MLQCZ])", " $1 ")).useLocale(Locale.US);
        while (sc.hasNext()) {
            String cmd = sc.next();
            switch (cmd) {
                case "M": p.moveTo(sc.nextDouble(), sc.nextDouble()); break;
                case "L": p.lineTo(sc.nextDouble(), sc.nextDouble()); break;
                case "Q": p.quadTo(sc.nextDouble(), sc.nextDouble(), sc.nextDouble(), sc.nextDouble()); break;
                case "C": p.curveTo(sc.nextDouble(), sc.nextDouble(), sc.nextDouble(), sc.nextDouble(), sc.nextDouble(), sc.nextDouble()); break;
                case "Z": p.closePath(); break;
                default: throw new IllegalArgumentException("path command " + cmd);
            }
        }
        return p;
    }

    static String hex(int argb) { return String.format("#%08X", argb); }
    static String svgHex(int argb) { return String.format("#%06X", argb & 0xffffff); }

    static String svg() {
        StringBuilder s = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\" width=\"108\" height=\"108\">\n");
        s.append("<defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"" + svgHex(BG_TOP) + "\"/><stop offset=\"1\" stop-color=\"" + svgHex(BG_BOTTOM) + "\"/></linearGradient></defs>\n");
        s.append("<rect width=\"108\" height=\"108\" fill=\"url(#bg)\"/>\n");
        for (String[] p : parts(false)) s.append("<path fill=\"" + svgHex((int) Long.parseLong(p[1].substring(1), 16)) + "\" d=\"" + p[0] + "\"/>\n");
        return s.append("</svg>\n").toString();
    }

    /** (path, colour) in drawing order; mono: one colour, glass and hubs cut out with evenOdd in the caller. */
    static java.util.List<String[]> parts(boolean mono) {
        java.util.List<String[]> l = new ArrayList<>();
        l.add(new String[]{BODY, hex(CAR)}); l.add(new String[]{GLASS, hex(GLASS_C)}); l.add(new String[]{PILLAR, hex(CAR)});
        l.add(new String[]{STRIPE, hex(RED)}); l.add(new String[]{LAMP, hex(LAMP_C)});
        for (double[] w : WHEELS) { l.add(new String[]{circle(w[0], w[1], TYRE), hex(TYRE_C)}); l.add(new String[]{circle(w[0], w[1], RIM), hex(RIM_C)}); l.add(new String[]{circle(w[0], w[1], 1.2), hex(TYRE_C)}); }
        return l;
    }

    static String vector(boolean mono) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated by scripts/IconMaker.java: "
            + (mono ? "the themed (monochrome) launcher icon, the car with its glass and hubs cut out." : "the launcher foreground, a Veloster N side silhouette (108 dp adaptive canvas).")
            + " Do not edit; change the drawing there. -->\n"
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:width=\"108dp\" android:height=\"108dp\" android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n");
        if (mono) {
            StringBuilder d = new StringBuilder(BODY).append(' ').append(GLASS);
            for (double[] w : WHEELS) d.append(' ').append(circle(w[0], w[1], TYRE)).append(' ').append(circle(w[0], w[1], RIM));
            s.append("    <path android:fillColor=\"#FFFFFFFF\" android:fillType=\"evenOdd\" android:pathData=\"" + d + "\"/>\n");
        } else for (String[] p : parts(false)) s.append("    <path android:fillColor=\"" + p[1] + "\" android:pathData=\"" + p[0] + "\"/>\n");
        return s.append("</vector>\n").toString();
    }

    /** ICO with PNG entries (Windows Vista and later read them at every size). */
    static void writeIco(Path out, Map<Integer, BufferedImage> img, int[] sizes) throws IOException {
        java.util.List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) { ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(img.get(s), "png", b); pngs.add(b.toByteArray()); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(o);
        le16(d, 0); le16(d, 1); le16(d, sizes.length);
        int off = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            d.writeByte(s >= 256 ? 0 : s); d.writeByte(s >= 256 ? 0 : s); d.writeByte(0); d.writeByte(0);
            le16(d, 1); le16(d, 32); le32(d, pngs.get(i).length); le32(d, off); off += pngs.get(i).length;
        }
        for (byte[] p : pngs) d.write(p);
        Files.write(out, o.toByteArray());
    }
    static void le16(DataOutputStream d, int v) throws IOException { d.writeByte(v & 255); d.writeByte((v >> 8) & 255); }
    static void le32(DataOutputStream d, int v) throws IOException { le16(d, v & 0xffff); le16(d, (v >>> 16) & 0xffff); }
}
