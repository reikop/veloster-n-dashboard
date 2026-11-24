import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Splits a generated row of glowing horse frames into an aligned, alpha-keyed sprite strip.
 * Used: java apps/dashboard/art/HorseSlicer.java apps/dashboard/art/taxi-horse-gallop8-qwen.png \
 *   apps/dashboard/res/drawable-nodpi/taxi_horse.png 8 0.62 1   (8-frame sheet taxi-horse-gallop8-qwen.png)
 */
public class HorseSlicer {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int frames = Integer.parseInt(a[2]); double scale = Double.parseDouble(a[3]);
        int mergeGap = a.length > 4 ? Integer.parseInt(a[4]) : 6;   // blank columns that still belong to one frame
        int w = src.getWidth(), h = src.getHeight(), thr = 40;
        boolean[] col = new boolean[w];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) if (lum(src.getRGB(x, y)) > thr) { col[x] = true; break; }
        // Runs of content columns, merged across small gaps, keep the `frames` widest.
        java.util.List<int[]> runs = new ArrayList<>();
        int s = -1, gap = 0;
        for (int x = 0; x <= w; x++) {
            boolean on = x < w && col[x];
            if (on) { if (s < 0) s = x; gap = 0; }
            else if (s >= 0 && ++gap > mergeGap) { runs.add(new int[] {s, x - gap}); s = -1; gap = 0; }
        }
        runs.sort((p, q) -> (q[1] - q[0]) - (p[1] - p[0]));
        runs = new ArrayList<>(runs.subList(0, frames));
        runs.sort(Comparator.comparingInt(p -> p[0]));
        int[][] box = new int[frames][]; double[][] anchor = new double[frames][];
        for (int i = 0; i < frames; i++) {
            int x0 = runs.get(i)[0], x1 = runs.get(i)[1], y0 = h, y1 = 0;
            for (int x = x0; x <= x1; x++) for (int y = 0; y < h; y++) if (lum(src.getRGB(x, y)) > thr) { y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
            box[i] = new int[] {x0, y0, x1, y1};
            // Anchor: brightness centroid of the upper 55% (head, neck, back): steadier than the legs.
            double sx = 0, sy = 0, sw = 0; int cut = y0 + (int) ((y1 - y0) * 0.55);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= cut; y++) { int l = lum(src.getRGB(x, y)); if (l > thr) { sx += x * l; sy += y * l; sw += l; } }
            anchor[i] = new double[] {sx / sw, sy / sw};
            System.out.printf("frame %d: box %d,%d-%d,%d anchor %.0f,%.0f%n", i, x0, y0, x1, y1, anchor[i][0], anchor[i][1]);
        }
        // Common cell around the anchor.
        double l = 0, r = 0, t = 0, b = 0;
        for (int i = 0; i < frames; i++) {
            l = Math.max(l, anchor[i][0] - box[i][0]); r = Math.max(r, box[i][2] - anchor[i][0]);
            t = Math.max(t, anchor[i][1] - box[i][1]); b = Math.max(b, box[i][3] - anchor[i][1]);
        }
        int pad = 8, cw = (int) Math.ceil((l + r) + pad * 2), ch = (int) Math.ceil((t + b) + pad * 2);
        int ow = (int) Math.round(cw * scale), oh = (int) Math.round(ch * scale);
        BufferedImage out = new BufferedImage(ow * frames, oh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int i = 0; i < frames; i++) {
            BufferedImage cell = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            int ox = (int) Math.round(anchor[i][0] - l - pad), oy = (int) Math.round(anchor[i][1] - t - pad);
            for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
                int sxp = ox + x, syp = oy + y;
                // Stop halfway to a neighbouring frame so its glow does not bleed into this cell.
                int lo = i > 0 ? (box[i - 1][2] + box[i][0]) / 2 : box[i][0] - pad;
                int hi = i < frames - 1 ? (box[i][2] + box[i + 1][0]) / 2 : box[i][2] + pad;
                if (sxp < Math.max(lo, box[i][0] - pad) || sxp > Math.min(hi, box[i][2] + pad) || sxp < 0 || syp < 0 || sxp >= w || syp >= h) continue;
                int p = src.getRGB(sxp, syp), al = Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255));
                if (al < 14) continue;
                int rr = ((p >> 16) & 255) * 255 / al, gg = ((p >> 8) & 255) * 255 / al, bb = (p & 255) * 255 / al;
                cell.setRGB(x, y, al << 24 | rr << 16 | gg << 8 | bb);
            }
            g.drawImage(cell, i * ow, 0, ow, oh, null);
        }
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println("cell " + ow + "x" + oh + " frames " + frames);
    }
    static int lum(int p) { return Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)); }
}
