import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * The installers' one icon: Neon Drive's pixel Veloster N on Neon Drive's dusk road (sky, striped sun, sea, neon road) (apps/neondrive/res/drawable-nodpi/car_sprites.png, the
 * same car the dashboard drives) on a dark tile, its pixels scaled up whole so the dot look stays crisp.
 * Frame FRAME of the sheet (CarSprite.ANGLE: 3 straight, 5 = 20 deg showing the left side) is drawn as Neon Drive draws it:
 * the body row, the first wheel phase and the tail-lamp row. Written out as
 *   installer/icon/installer.ico             Windows (16..256 px, PNG entries): the window installer's window icon
 *   installer/icon/installer.icns            macOS (iconutil): the "N 계기판 설치.app" icon
 *   installer/icon/installer.png             1024 px master
 *   installer/android/res/drawable-nodpi/ic_launcher_bg.png / ic_launcher_fg.png / ic_launcher_mono.png   the phone installer's
 *                                            launcher icon (the scene, the car, the themed mask)
 *   build/icon-preview.png                   a contact sheet of the sizes (not kept)
 * usage (repo root, JDK 11+, macOS for the .icns):  java scripts/IconMaker.java
 */
public class IconMaker {
    static final int CELL_W = 89, CELL_H = 74, FRAME = 5, WHEEL_ROW = 4, TAIL_ROW = 7;   // CarSprite
    static final int BG_TOP = 0xff2a1450, BG_BOTTOM = 0xff0b0a1e, GLOW = 0xffff3fb4;      // Neon Drive's dusk
    /** Horizon row of the 64 px scene: high enough that the whole car stands on the road below it, as in Neon Drive. */
    static final int HORIZON = 24;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Path icon = root.resolve("installer/icon"), res = root.resolve("installer/android/res/drawable-nodpi");
        Files.createDirectories(icon); Files.createDirectories(res);
        BufferedImage car = car(ImageIO.read(root.resolve("apps/neondrive/res/drawable-nodpi/car_sprites.png").toFile()), FRAME);
        int[] sizes = {16, 24, 32, 48, 64, 128, 256, 512, 1024};
        Map<Integer, BufferedImage> img = new TreeMap<>();
        for (int s : sizes) img.put(s, tile(car, s));
        ImageIO.write(img.get(1024), "png", icon.resolve("installer.png").toFile());
        writeIco(icon.resolve("installer.ico"), img, new int[]{16, 24, 32, 48, 64, 128, 256});
        Path set = Files.createTempDirectory("vn-icon").resolve("installer.iconset"); Files.createDirectories(set);
        int[][] mac = {{16, 1}, {16, 2}, {32, 1}, {32, 2}, {128, 1}, {128, 2}, {256, 1}, {256, 2}, {512, 1}, {512, 2}};
        for (int[] m : mac) ImageIO.write(img.get(m[0] * m[1]), "png", set.resolve("icon_" + m[0] + "x" + m[0] + (m[1] == 2 ? "@2x" : "") + ".png").toFile());
        try {
            Process p = new ProcessBuilder("iconutil", "-c", "icns", set.toString(), "-o", icon.resolve("installer.icns").toString()).inheritIO().start();
            if (p.waitFor() != 0) System.err.println("iconutil failed; installer.icns not written");
        } catch (IOException e) { System.err.println("no iconutil (not macOS); installer.icns not written"); }
        // Android adaptive icon: 108 dp canvas at xxxhdpi (432 px); the car inside the 66 dp safe circle.
        ImageIO.write(adaptive(car, 432, false), "png", res.resolve("ic_launcher_fg.png").toFile());
        { BufferedImage bg = new BufferedImage(432, 432, BufferedImage.TYPE_INT_ARGB); Graphics2D bg2 = bg.createGraphics();
          bg2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
          bg2.drawImage(scene(), -54, -54, 540, 540, null); bg2.dispose();   // 64 px scene over the 108 dp canvas, the horizon a little high in the circle
          ImageIO.write(bg, "png", res.resolve("ic_launcher_bg.png").toFile()); }
        ImageIO.write(adaptive(car, 432, true), "png", res.resolve("ic_launcher_mono.png").toFile());
        // preview: every size, and the launcher's circle mask
        BufferedImage sheet = new BufferedImage(1100, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics(); g.setColor(new Color(0xf2f2f2)); g.fillRect(0, 0, 1100, 300);
        int x = 10; for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) { g.drawImage(img.get(s), x, 280 - s, null); x += s + 14; }
        BufferedImage round = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
        Graphics2D rg = round.createGraphics(); rg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        rg.fill(new Ellipse2D.Double(0, 0, 192, 192)); rg.setComposite(AlphaComposite.SrcIn);
        rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); rg.drawImage(scene(), -24, -24, 240, 240, null);
        rg.setComposite(AlphaComposite.SrcAtop); rg.drawImage(adaptive(car, 192, false), 0, 0, null); rg.dispose();
        g.drawImage(round, x + 10, 88, null); g.dispose();
        Files.createDirectories(root.resolve("build")); ImageIO.write(sheet, "png", root.resolve("build/icon-preview.png").toFile());
        System.out.println("icons written to " + icon + " and " + res);
    }

    /** One sprite frame as Neon Drive draws it (body, wheels, tail lamps), cropped to its pixels. */
    static BufferedImage car(BufferedImage sheet, int frame) {
        BufferedImage c = new BufferedImage(CELL_W, CELL_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        for (int row : new int[]{0, WHEEL_ROW, TAIL_ROW})
            g.drawImage(sheet.getSubimage(frame * CELL_W, row * CELL_H, CELL_W, CELL_H), 0, 0, null);
        g.dispose();
        int x0 = CELL_W, y0 = CELL_H, x1 = -1, y1 = -1;
        for (int y = 0; y < CELL_H; y++) for (int x = 0; x < CELL_W; x++) if ((c.getRGB(x, y) >>> 24) > 16) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        return c.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** The car scaled by a whole number (nearest neighbour) to fit `box`, or smoothly when the box is smaller than the sprite. */
    static void put(Graphics2D g, BufferedImage car, double cx, double cy, double box) {
        double k = box / Math.max(car.getWidth(), car.getHeight());
        if (k >= 1) k = Math.floor(k);
        int w = (int) Math.round(car.getWidth() * k), h = (int) Math.round(car.getHeight() * k);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, k >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(car, (int) Math.round(cx - w / 2.0), (int) Math.round(cy - h / 2.0), w, h, null);
    }

    /** Neon Drive at dusk in fat pixels (64 x 64, each pixel 4 x 4 at 256): banded sky, a striped sun, the sea, the road
     *  running to the horizon with its neon edges and centre dashes. */
    static BufferedImage scene() {
        int n = 64, hz = HORIZON;   // horizon row
        BufferedImage p = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        int[] sky = {0xff1a0b3a, 0xff2b0f52, 0xff45145f, 0xff6a1a6b, 0xff962673, 0xffc23a74, 0xffe85d6e, 0xfff58a5c};
        for (int y = 0; y < hz; y++) { int band = Math.min(sky.length - 1, y * sky.length / hz); for (int x = 0; x < n; x++) p.setRGB(x, y, sky[band]); }
        for (int y = 0; y < 2; y++) for (int x = (y * 7) % 11; x < n; x += 13 + y) p.setRGB(x, y * 4 + 2, 0xffffe9f6);   // a few stars
        // sun: a disc on the horizon with the synthwave cuts in its lower half
        double cx = 32, cy = hz - 1, r = 11;
        for (int y = (int) (cy - r); y < hz; y++) for (int x = 0; x < n; x++) {
            double d = Math.hypot(x + .5 - cx, y + .5 - cy); if (d > r) continue;
            int rel = (int) (cy - y); boolean cut = y > cy - 6 && ((y - (int) (cy - 6)) % 3 == 2);
            if (!cut) p.setRGB(x, y, rel > 7 ? 0xffffe066 : rel > 2 ? 0xffffa94d : 0xffff6f61);
        }
        // sea
        int[] sea = {0xff2a1f6b, 0xff23185a, 0xff1c1349};
        for (int y = hz; y < hz + 4; y++) for (int x = 0; x < n; x++) p.setRGB(x, y, sea[Math.min(2, (y - hz) * 3 / 4)]);
        for (int x = 20; x < 44; x += 3) p.setRGB(x, hz + 1, 0xffff9f80);   // the sun on the water
        // ground and road (perspective from the vanishing point at the horizon centre)
        for (int y = hz + 4; y < n; y++) {
            double t = (y - hz) / (double) (n - hz);   // 0 at the horizon .. 1 at the bottom
            double half = 3 + t * 34, edge = Math.max(1, t * 2.2);
            for (int x = 0; x < n; x++) {
                double dx = Math.abs(x + .5 - 32);
                int c = 0xff120a2a;                                                 // dark ground
                if (dx < half) c = 0xff2a2440;                                      // asphalt
                if (dx >= half - edge && dx < half) c = 0xffff3fb4;                 // neon edge lines
                if (dx < Math.max(.6, t * 1.2) && ((int) (Math.pow(t, .55) * 14)) % 2 == 0) c = 0xfff4f0ff;   // centre dashes
                p.setRGB(x, y, c);
            }
        }
        return p;
    }

    /** The 256 px picture: the scene scaled x4 nearest, the car on the road scaled by a whole number. */
    static BufferedImage picture(BufferedImage car, boolean withCar) {
        BufferedImage b = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(scene(), 0, 0, 256, 256, null);
        if (withCar) {
            int k = 2, w = car.getWidth() * k, h = car.getHeight() * k, bottom = 246;   // the roof stays below the horizon (row HORIZON x 4)
            g.setColor(new Color(0x88000000, true)); g.fill(new Ellipse2D.Double(128 - w * 0.48, bottom - 12, w * 0.96, 14));   // shadow
            g.drawImage(car, 128 - w / 2, bottom - h, w, h, null);
        }
        g.dispose();
        return b;
    }

    /** Desktop icon: the picture in a rounded tile (nearest above 256 px so the pixels stay square, smooth below). */
    static BufferedImage tile(BufferedImage car, int s) {
        BufferedImage pic = picture(car, true);
        BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape bg = new RoundRectangle2D.Double(s * 0.06, s * 0.06, s * 0.88, s * 0.88, s * 0.2, s * 0.2);
        g.setClip(bg);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s >= 256 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double in = s * 0.88; g.drawImage(s >= 256 ? pic : shrink(pic, (int) Math.round(in)), (int) Math.round(s * 0.06), (int) Math.round(s * 0.06), (int) Math.round(in), (int) Math.round(in), null);
        g.dispose();
        return b;
    }
    /** Smooth downscale in halving steps (Java2D's single-step bicubic aliases at small sizes). */
    static BufferedImage shrink(BufferedImage src, int to) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= to) { BufferedImage h = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = h.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(cur, 0, 0, h.getWidth(), h.getHeight(), null); g.dispose(); cur = h; }
        return cur;
    }

    /** Android foreground (transparent; the adaptive background is the colour resource) or the monochrome mask. */
    static BufferedImage adaptive(BufferedImage car, int s, boolean mono) {
        BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        // the background puts the horizon at HORIZON*540/64-54 px of 432; the car stands on the road below it, inside the safe circle
        double k = 2 * s / 432.0; int w = (int) Math.round(car.getWidth() * k), h = (int) Math.round(car.getHeight() * k), bottom = (int) Math.round(340 * s / 432.0);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(car, s / 2 - w / 2, bottom - h, w, h, null);
        g.dispose();
        if (mono) for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) { int a = b.getRGB(x, y) >>> 24; b.setRGB(x, y, a << 24 | 0xffffff); }
        return b;
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
