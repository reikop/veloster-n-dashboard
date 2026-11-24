import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Host-only asset tool: turns a generated 7-angle car sheet on a flat key colour
 * into a pixel-art sprite sheet for Neon Drive.
 *
 *   java SpriteForge.java SHEET.png OUT.png OUT_CarSprite.java [scale] [colours]
 *
 * Steps: flood-fill the key colour from the border, split the cars by empty
 * columns, downscale every car with the same factor (area average, alpha by
 * coverage), quantise all frames to one shared median-cut palette, then find the
 * tail lamps (red, upper half) and exhaust tips (bright grey, bottom) per frame.
 * Output rows: 0 base car, 1 brake-lit lamps (with the high-mount stop lamp), 2 left turn lamp, 3 right turn lamp,
 * 4..6 wheel phases, 7 tail lamps only (row 1 without the high-mount stop lamp, medium red),
 * 8 unlit lamps (dark red, drawn over the source photo's already bright lamps when the tail lamps are off).
 */
public final class SpriteForge {
    static final int FRAMES = 7;
    /** Rows: base, brake, left turn, right turn, wheel phase 0..2, tail lamps. */
    static final int ROWS = 9, WHEEL_PHASES = 3, TAIL_ROW = 7, UNLIT_ROW = 8;
    /** Vertical bands (fraction of car height) for lamps and exhaust tips; set by the 6th argument "top,bottom,pipe". */
    static float LAMP_TOP = 0.12f, LAMP_BOTTOM = 0.48f, PIPE_TOP = 0.74f;
    /** false when the input is already snapped pixel art (7th argument "keep"): keep its palette. */
    static boolean STYLISE = true;

    public static void main(String[] args) throws Exception {
        BufferedImage sheet = ImageIO.read(new File(args[0]));
        float scale = args.length > 3 ? Float.parseFloat(args[3]) : 0.32f;
        int colours = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        if (args.length > 6) STYLISE = !"keep".equals(args[6]);
        if (args.length > 5) { String[] v = args[5].split(","); LAMP_TOP = Float.parseFloat(v[0]); LAMP_BOTTOM = Float.parseFloat(v[1]); PIPE_TOP = Float.parseFloat(v[2]); }
        int w = sheet.getWidth(), h = sheet.getHeight();
        int key = borderAverage(sheet);
        boolean[] fg = foreground(sheet, key);

        // Each car is one of the 7 largest 8-connected shapes, ordered left to right.
        int[] label = new int[w * h];
        List<int[]> comps = new ArrayList<int[]>(); // {id, size, sumX}
        int next = 0;
        for (int s0 = 0; s0 < w * h; s0++) {
            if (!fg[s0] || label[s0] != 0) continue;
            next++; long sx = 0; int n = 0;
            ArrayDeque<Integer> q = new ArrayDeque<Integer>(); q.add(s0); label[s0] = next;
            while (!q.isEmpty()) {
                int k = q.poll(); n++; int kx = k % w, ky = k / w; sx += kx;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = kx + dx, ny = ky + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nk = ny * w + nx;
                    if (fg[nk] && label[nk] == 0) { label[nk] = next; q.add(nk); }
                }
            }
            comps.add(new int[] {next, n, (int) (sx / n)});
        }
        comps.sort((a, b) -> b[1] - a[1]);
        if (comps.size() < FRAMES) throw new IllegalStateException("Expected 7 separate cars, found " + comps.size());
        List<int[]> cars = new ArrayList<int[]>(comps.subList(0, FRAMES));
        cars.sort((a, b) -> a[2] - b[2]);
        if (cars.get(FRAMES - 1)[1] < cars.get(0)[1] / 4) throw new IllegalStateException("Cars touch each other; regenerate with wider spacing");
        final int[] ids = new int[FRAMES];
        for (int i = 0; i < FRAMES; i++) ids[i] = cars.get(i)[0];
        int[][] box = new int[FRAMES][];
        int maxW = 0, maxH = 0;
        for (int i = 0; i < FRAMES; i++) {
            int x0 = w, x1 = 0, y0 = h, y1 = 0;
            for (int k = 0; k < w * h; k++) if (label[k] == ids[i]) { int x = k % w, y = k / w; x0 = Math.min(x0, x); x1 = Math.max(x1, x + 1); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
            box[i] = new int[] {x0, y0, x1, y1 + 1};
            maxW = Math.max(maxW, x1 - x0); maxH = Math.max(maxH, y1 + 1 - y0);
        }
        int cw = (int) Math.ceil(maxW * scale) + 4, ch = (int) Math.ceil(maxH * scale) + 4;
        int[][] px = new int[FRAMES][cw * ch]; // ARGB, 0 = transparent
        for (int i = 0; i < FRAMES; i++) {
            int bw = box[i][2] - box[i][0], bh = box[i][3] - box[i][1];
            int ow = Math.round(bw * scale), oh = Math.round(bh * scale);
            int ox = (cw - ow) / 2, oy = ch - 2 - oh; // shared baseline
            for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
                int sx0 = box[i][0] + (int) (x / scale), sx1 = box[i][0] + Math.max((int) ((x + 1) / scale), (int) (x / scale) + 1);
                int sy0 = box[i][1] + (int) (y / scale), sy1 = box[i][1] + Math.max((int) ((y + 1) / scale), (int) (y / scale) + 1);
                long r = 0, g = 0, b = 0; int n = 0, total = 0;
                for (int sy = sy0; sy < Math.min(sy1, h); sy++) for (int sx = sx0; sx < Math.min(sx1, w); sx++) {
                    total++;
                    if (label[sy * w + sx] != ids[i]) continue;
                    int p = sheet.getRGB(sx, sy); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
                }
                if (total > 0 && n >= total * 0.5f) px[i][(oy + y) * cw + ox + x] = 0xff000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        // Remove leftover key-colour fringe (pink, strong red+blue, weak green) after averaging.
        for (int[] frame : px) for (int k = 0; k < frame.length; k++) {
            int p = frame[k]; if (p == 0) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (b > 70 && r > 120 && b > g * 1.35f && r > g * 1.6f) frame[k] = 0;
        }
        int[] palette = STYLISE ? stylise(px, cw, ch) : distinct(px);

        BufferedImage out = new BufferedImage(cw * FRAMES, ch * ROWS, BufferedImage.TYPE_INT_ARGB);
        StringBuilder meta = new StringBuilder();
        int[][] lamps = new int[FRAMES][], pipes = new int[FRAMES][];
        for (int i = 0; i < FRAMES; i++) {
            int[] f = px[i];
            int top = ch, bottom = 0, left = cw, right = 0;
            for (int k = 0; k < f.length; k++) if (f[k] != 0) { int x = k % cw, y = k / cw; top = Math.min(top, y); bottom = Math.max(bottom, y); left = Math.min(left, x); right = Math.max(right, x); }
            int carH = bottom - top + 1;
            boolean[] lamp = new boolean[f.length], pipe = new boolean[f.length];
            for (int k = 0; k < f.length; k++) {
                if (f[k] == 0) continue;
                int y = k / cw, r = (f[k] >> 16) & 255, g = (f[k] >> 8) & 255, b = f[k] & 255;
                float rel = (y - top) / (float) carH;
                boolean red = STYLISE ? (f[k] == RED[0] || f[k] == RED[1] || f[k] == RED[2]) : (r > 110 && r > g * 1.9f && r > b * 1.7f);
                if (rel > LAMP_TOP && rel < LAMP_BOTTOM && red) lamp[k] = true;
                boolean grey = STYLISE ? (f[k] == GREY[0] || f[k] == GREY[1] || f[k] == GREY[2]) : (r + g + b > 420 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40);
                if (rel > PIPE_TOP && rel < 0.99f && grey) pipe[k] = true;
            }
            keepLampBlobs(lamp, cw, ch);
            // Split left/right lamps halfway between the two largest lamp shapes (not the overall extent,
            // which side-skirt stripes on turned cars would stretch).
            List<int[]> lb = blobs(lamp, cw, ch, 2);
            lb.sort((p1, p2) -> p2[3] - p1[3]);
            int split = lb.size() >= 2 ? (lb.get(0)[0] + lb.get(1)[0]) / 2 : cw / 2;
            int lx0 = cw, lx1 = 0;
            for (int k = 0; k < f.length; k++) if (lamp[k]) { lx0 = Math.min(lx0, k % cw); lx1 = Math.max(lx1, k % cw); }
            final int[] side = {split, lx0, lx1};
            int[] l = sideCentres(lamp, cw, side[0]);
            List<int[]> round = new ArrayList<int[]>();
            for (int[] bl : blobs(pipe, cw, ch, 3)) if (bl[2] <= 7) round.add(bl);
            int[] e = pickApart(round, Math.max(10, (right - left + 1) / 3));
            // High-mount stop lamp: red pixels in the spoiler, above the tail lamps and near their midpoint.
            boolean[] hmsl = new boolean[f.length];
            long hsx = 0, hsy = 0; int hn = 0;
            int mid = l != null ? (l[0] + l[3]) / 2 : (left + right) / 2;
            for (int k = 0; k < f.length; k++) {
                if (f[k] == 0) continue;
                int x = k % cw, y = k / cw, r = (f[k] >> 16) & 255, g = (f[k] >> 8) & 255, b = f[k] & 255;
                float rel = (y - top) / (float) carH;
                boolean red = r > 110 && r > g * 1.9f && r > b * 1.7f;
                if (red && rel > 0.1f && rel < LAMP_TOP && Math.abs(x - mid) <= 11) { hmsl[k] = true; hsx += x; hsy += y; hn++; }
            }
            int[] stop = hn > 0 ? new int[] {(int) (hsx / hn), (int) (hsy / hn)} : null;
            if (e == null && l != null) {
                int span = l[3] - l[0];
                e = new int[] {l[0] + span / 8, bottom - 8, 0, l[3] - span / 8, bottom - 8, 0};
            }
            lamps[i] = l; pipes[i] = e;
            // Whole lamp housings for the tail/unlit rows: any reddish pixel inside each side's lamp box
            // (grown by 7 px, kept inside the lamp band), including the darker reds the brake mask leaves out.
            boolean[] housing = new boolean[f.length];
            for (int sd = 0; sd < 2; sd++) {
                int bx0 = cw, bx1 = -1, by0 = ch, by1 = -1;
                for (int k = 0; k < f.length; k++) if (lamp[k] && ((k % cw) < side[0]) == (sd == 0)) {
                    bx0 = Math.min(bx0, k % cw); bx1 = Math.max(bx1, k % cw); by0 = Math.min(by0, k / cw); by1 = Math.max(by1, k / cw);
                }
                if (bx1 < 0) continue;
                for (int y = Math.max(top + (int) (carH * 0.1f), by0 - 7); y <= Math.min(top + (int) (carH * LAMP_BOTTOM), by1 + 3); y++)
                    for (int x = Math.max(0, bx0 - 7); x <= Math.min(cw - 1, bx1 + 7); x++) {
                        int q = f[y * cw + x]; if (q == 0) continue;
                        int r = (q >> 16) & 255, g = (q >> 8) & 255, b = q & 255;
                        if (r > 60 && r > g * 1.6f && r > b * 1.4f) housing[y * cw + x] = true;
                    }
            }
            for (int k = 0; k < f.length; k++) {
                int x = k % cw, y = k / cw;
                out.setRGB(i * cw + x, y, f[k]);
                if (hmsl[k]) out.setRGB(i * cw + x, ch + y, 0xffff4058);
                if (!lamp[k]) continue;
                int lum = ((f[k] >> 16) & 255) + ((f[k] >> 8) & 255) + (f[k] & 255);
                boolean hiLamp = STYLISE ? f[k] == RED[0] : lum > 330, midLamp = STYLISE ? f[k] == RED[1] : lum > 200;
                int lit = hiLamp ? 0xffffe6dc : midLamp ? 0xffff6a72 : 0xffff2a40;
                out.setRGB(i * cw + x, ch + y, lit);
                // Turn signal: outer 45% of each side's lamp extent (sides split at the lamp midpoint).
                boolean leftSide = x < side[0];
                int lo = leftSide ? side[1] : side[0], hi = leftSide ? side[0] : side[2];
                int span = Math.max(1, hi - lo);
                int amber = hiLamp ? 0xfffff0b0 : midLamp ? 0xffffc23a : 0xffff9a10;
                if (leftSide && x <= lo + span * 0.45f) out.setRGB(i * cw + x, ch * 2 + y, amber);
                if (!leftSide && x >= hi - span * 0.45f) out.setRGB(i * cw + x, ch * 3 + y, amber);
            }
            // Tail lamps: medium red without the white-hot core; unlit: dark red lens. Shading follows the source.
            for (int k = 0; k < f.length; k++) {
                if (!housing[k] || hmsl[k]) continue;
                int x = k % cw, y = k / cw;
                int lum = ((f[k] >> 16) & 255) + ((f[k] >> 8) & 255) + (f[k] & 255);
                out.setRGB(i * cw + x, ch * TAIL_ROW + y, lum > 330 ? 0xffff9090 : lum > 200 ? 0xffff4050 : lum > 120 ? 0xffe8202e : 0xffb8101e);
                out.setRGB(i * cw + x, ch * UNLIT_ROW + y, lum > 330 ? 0xff9c3a42 : lum > 200 ? 0xff7c1824 : lum > 120 ? 0xff5e0e1a : 0xff420812);
            }
            int wheelPx = wheels(f, cw, ch, top, bottom, left, right, out, i);
            meta.append(String.format(Locale.US, "        {%d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d, %d},%n",
                l == null ? cw / 2 - 10 : l[0], l == null ? top + carH / 3 : l[1], l == null ? cw / 2 + 10 : l[3], l == null ? top + carH / 3 : l[4],
                e == null ? cw / 2 - 12 : e[0], e == null ? bottom - 6 : e[1], e == null ? cw / 2 + 12 : e[3], e == null ? bottom - 6 : e[4],
                top, bottom + 1, stop == null ? -1 : stop[0], stop == null ? -1 : stop[1]));
            System.out.printf(Locale.US, "frame %d: wheel px %d%n", i, wheelPx);
            System.out.printf(Locale.US, "frame %d: car %dx%d lamps=%s pipes=%s%n", i, right - left + 1, carH,
                l == null ? "none" : Arrays.toString(l), e == null ? "none" : Arrays.toString(e));
        }
        ImageIO.write(out, "png", new File(args[1]));
        String source = "package com.veloster.neondrive;\n\n"
            + "/** Generated by apps/neondrive/art/SpriteForge.java from " + new File(args[0]).getName() + ". Do not edit. */\n"
            + "final class CarSprite {\n"
            + "    static final int FRAMES = 7, ROWS = " + ROWS + ", WHEEL_ROW = 4, WHEEL_PHASES = " + WHEEL_PHASES + ", TAIL_ROW = " + TAIL_ROW + ", UNLIT_ROW = " + UNLIT_ROW + ", CELL_W = " + cw + ", CELL_H = " + ch + ";\n"
            + "    /** Frame order: turned 35/20/10 deg showing the right side, straight, then 10/20/35 deg showing the left side. */\n"
            + "    static final int[] ANGLE = {-35, -20, -10, 0, 10, 20, 35};\n"
            + "    /** Per frame, sprite pixels: left lamp x,y, right lamp x,y, left pipe x,y, right pipe x,y, car top, car bottom, stop lamp x,y (-1 if none). */\n"
            + "    static final int[][] POINTS = {\n" + meta + "    };\n"
            + "    private CarSprite() {}\n}\n";
        java.nio.file.Files.write(new File(args[2]).toPath(), source.getBytes("UTF-8"));
        System.out.println("cell " + cw + "x" + ch + ", palette " + palette.length + ", key " + Integer.toHexString(key & 0xffffff));
    }

    // ------------------------------------------------------------ pixel-art stylisation
    // Hand-picked hue-shifted ramps (light -> dark): highlights lean warm/cyan, shadows lean purple.
    static final int[] BODY = {0xffe2f6ff, 0xffa9d6f7, 0xff78aee6, 0xff5480cc, 0xff3a56a6, 0xff2a3577};
    static final int[] DARK = {0xff55537a, 0xff383659, 0xff23213c, 0xff141226};
    static final int[] RED = {0xffff6a62, 0xffd82c3e, 0xff961a36, 0xff541028};
    static final int[] GREY = {0xfff6f2ff, 0xffc6c2de, 0xff8c8aac, 0xff5c5a7c};
    static final int[][] RAMPS = {BODY, DARK, RED, GREY};

    static float[] hsv(int p) {
        float r = ((p >> 16) & 255) / 255f, g = ((p >> 8) & 255) / 255f, b = (p & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h = 0;
        if (d > 0) h = max == r ? ((g - b) / d + 6) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new float[] {h * 60, max == 0 ? 0 : d / max, max};
    }

    /** 0 body blue, 1 dark trim/glass/tyres, 2 red, 3 grey/chrome/white. */
    static int material(int p) {
        float[] c = hsv(p);
        if (c[2] < 0.30f) return 1;
        if ((c[0] >= 335 || c[0] <= 18) && c[1] > 0.42f) return 2;
        if (c[0] >= 185 && c[0] <= 255 && c[1] > 0.14f && c[2] > 0.42f) return 0;
        if (c[1] < 0.18f && c[2] > 0.55f) return 3;
        return c[2] < 0.5f ? 1 : (c[0] >= 185 && c[0] <= 270 ? 0 : 3);
    }

    static float luma(int p) { return (((p >> 16) & 255) * 0.3f + ((p >> 8) & 255) * 0.59f + (p & 255) * 0.11f) / 255f; }

    /**
     * Re-paints every frame with the material ramps (same luminance bands for all frames so angles match),
     * then flattens noise with a majority filter, removes orphan pixels and applies a selective outline.
     * Returns the palette actually used.
     */
    static int[] stylise(int[][] frames, int cw, int ch) {
        // Per-material luminance range over all frames (5th..95th percentile).
        List<List<Float>> lum = new ArrayList<List<Float>>();
        for (int m = 0; m < 4; m++) lum.add(new ArrayList<Float>());
        for (int[] f : frames) for (int p : f) if (p != 0) lum.get(material(p)).add(luma(p));
        float[] lo = new float[4], hi = new float[4];
        for (int m = 0; m < 4; m++) {
            List<Float> l = lum.get(m);
            if (l.isEmpty()) { lo[m] = 0; hi[m] = 1; continue; }
            java.util.Collections.sort(l);
            lo[m] = l.get((int) (l.size() * 0.05f)); hi[m] = l.get((int) (l.size() * 0.95f));
            if (hi[m] - lo[m] < 0.05f) hi[m] = lo[m] + 0.05f;
        }
        java.util.Set<Integer> used = new java.util.LinkedHashSet<Integer>();
        for (int[] f : frames) {
            int[] idx = new int[f.length]; // material*16 + level, -1 transparent
            for (int k = 0; k < f.length; k++) {
                if (f[k] == 0) { idx[k] = -1; continue; }
                int m = material(f[k]);
                int[] ramp = RAMPS[m];
                float t = (hi[m] - luma(f[k])) / (hi[m] - lo[m]); // 0 bright .. 1 dark
                int level = Math.max(0, Math.min(ramp.length - 1, Math.round(t * (ramp.length - 1))));
                idx[k] = m * 16 + level;
            }
            for (int pass = 0; pass < 3; pass++) idx = majority(idx, cw, ch);
            idx = orphans(idx, cw, ch);
            // Selective outline: silhouette pixels take the darkest shade of their own ramp,
            // except on the lit top edge, which steps only one shade darker.
            int[] out = idx.clone();
            for (int k = 0; k < idx.length; k++) {
                if (idx[k] < 0) continue;
                int x = k % cw, y = k / cw;
                boolean top = y == 0 || idx[k - cw] < 0;
                boolean edge = top || y == ch - 1 || idx[k + cw] < 0 || x == 0 || idx[k - 1] < 0 || x == cw - 1 || idx[k + 1] < 0;
                if (!edge) continue;
                int m = idx[k] / 16, last = RAMPS[m].length - 1;
                out[k] = m * 16 + (top && m != 1 ? Math.min(last, idx[k] % 16 + 1) : last);
            }
            for (int k = 0; k < f.length; k++) {
                f[k] = out[k] < 0 ? 0 : RAMPS[out[k] / 16][out[k] % 16];
                if (f[k] != 0) used.add(f[k]);
            }
        }
        int[] pal = new int[used.size()]; int i = 0;
        for (int c : used) pal[i++] = c;
        return pal;
    }

    /** 3x3 majority: a pixel takes the most common neighbour value when at least 5 of 8 neighbours agree. */
    static int[] majority(int[] idx, int w, int h) {
        int[] out = idx.clone();
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int k = y * w + x;
            if (idx[k] < 0) continue;
            int best = idx[k], bestN = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int v = idx[k + dy * w + dx], n = 0;
                if (v < 0) continue;
                for (int ey = -1; ey <= 1; ey++) for (int ex = -1; ex <= 1; ex++) if ((ex != 0 || ey != 0) && idx[k + ey * w + ex] == v) n++;
                if (n > bestN) { bestN = n; best = v; }
            }
            if (bestN >= 5) out[k] = best;
        }
        return out;
    }

    /** Pixels with no same-coloured 4-neighbour take the most common 4-neighbour colour. */
    static int[] orphans(int[] idx, int w, int h) {
        int[] out = idx.clone();
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int k = y * w + x, v = idx[k];
            if (v < 0) continue;
            int[] nb = {idx[k - 1], idx[k + 1], idx[k - w], idx[k + w]};
            boolean alone = true;
            for (int n : nb) if (n == v) alone = false;
            if (!alone) continue;
            int best = v, bestN = 0;
            for (int a : nb) { if (a < 0) continue; int n = 0; for (int b : nb) if (b == a) n++; if (n > bestN) { bestN = n; best = a; } }
            if (bestN >= 2) out[k] = best;
        }
        return out;
    }

    /**
     * Hand-annotated wheels for art/veloster-n-sheet-grok-pixel-snapped.png (sprite pixels):
     * {cx, cy, rx, ry, kind}. kind=1: right-side wheel seen side-on, kind=2: left-side wheel seen side-on,
     * kind=0: tyre seen from behind. Rolling forward, a right-side wheel turns clockwise as seen from the
     * right, a left-side wheel counter-clockwise as seen from the left, and the rear face of a tyre seen
     * from behind moves upward. Automatic detection merged
     * the tyres with the black bumper and diffuser, so the positions are read off a grid view.
     */
    static final int[][][] WHEELS = {
        {{56, 62, 6, 11, 1}},
        {{57, 61, 6, 12, 1}},
        {{17, 69, 3, 4, 0}, {68, 69, 3, 4, 0}},
        {{15, 70, 3, 4, 0}, {73, 70, 3, 4, 0}},
        {{17, 70, 3, 4, 0}, {72, 70, 3, 4, 0}},
        {{18, 70, 3, 4, 0}, {74, 70, 3, 4, 0}},
        {{30, 60, 6, 13, 2}},
    };

    /** Writes the wheel animation phases into rows WHEEL_ROW.. and returns the number of wheel pixels. */
    static int wheels(int[] f, int cw, int ch, int top, int bottom, int left, int right, BufferedImage out, int frame) {
        int count = 0;
        for (int[] w : WHEELS[frame]) {
            float cx = w[0], cy = w[1], rx = w[2] + 0.5f, ry = w[3] + 0.5f;
            for (int y = (int) (cy - ry); y <= cy + ry; y++) for (int x = (int) (cx - rx); x <= cx + rx; x++) {
                if (x < 0 || y < 0 || x >= cw || y >= ch) continue;
                float nx = (x - cx) / rx, ny = (y - cy) / ry, rr = nx * nx + ny * ny;
                if (rr > 1f) continue;
                int k = y * cw + x, p = f[k];
                if (p == 0) continue;
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int lum = (r + g + b) / 3, sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (sat > 60 || lum > 200) continue;   // keep red calipers, body paint and white outlines
                count++;
                // Screen angle grows clockwise (y points down). Moving the pattern to larger angles each
                // phase turns it clockwise; -1/3 of a spoke period per step keeps the direction unambiguous.
                double a = Math.atan2(ny, nx) / (2 * Math.PI);
                double turn = w[4] == 1 ? -1 : 1;           // right side: clockwise, left side: counter-clockwise
                for (int ph = 0; ph < WHEEL_PHASES; ph++) {
                    int c = p;
                    double step = turn * ph * (2.0 / WHEEL_PHASES);
                    if (w[4] != 0 && rr < 0.5f) {           // rim: 5 spokes
                        int sector = (int) Math.floor(a * 10 + step);
                        c = (sector & 1) == 0 ? shade(p, 0.5f, 0) : shade(p, 1.35f, 14);
                    } else if (w[4] != 0) {                // tyre ring: fine tread blocks
                        int sector = (int) Math.floor(a * 24 + step);
                        if ((sector & 1) == 0) c = shade(p, 1.6f, 16);
                    } else if ((y + ph) % WHEEL_PHASES == 0) {
                        c = shade(p, 1.8f, 22);             // highlighted row moves up one pixel per phase
                    }
                    out.setRGB(frame * cw + x, (WHEEL_ROW + ph) * ch + y, c);
                }
            }
        }
        return count;
    }
    static final int WHEEL_ROW = 4;

    static int shade(int p, float mul, int add) {
        int r = Math.min(255, (int) (((p >> 16) & 255) * mul) + add), g = Math.min(255, (int) (((p >> 8) & 255) * mul) + add), b = Math.min(255, (int) ((p & 255) * mul) + add);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    static int[] distinct(int[][] frames) {
        java.util.Set<Integer> used = new java.util.LinkedHashSet<Integer>();
        for (int[] f : frames) for (int c : f) if (c != 0) used.add(c);
        int[] pal = new int[used.size()]; int i = 0;
        for (int c : used) pal[i++] = c;
        return pal;
    }

    static int borderAverage(BufferedImage im) {
        long r = 0, g = 0, b = 0; int n = 0;
        for (int x = 0; x < im.getWidth(); x += 4) for (int y : new int[] {2, im.getHeight() - 3}) {
            int p = im.getRGB(x, y); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
        }
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    static boolean keyLike(int p, int key) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int kr = (key >> 16) & 255, kg = (key >> 8) & 255, kb = key & 255;
        int d = Math.abs(r - kr) + Math.abs(g - kg) + Math.abs(b - kb);
        // Pink fringe: strong red and blue, weak green, i.e. magenta mixed into the edge.
        boolean fringe = r > 150 && b > 90 && g < 100 && b > g * 1.6f && r > g * 2f;
        return d < 120 || fringe;
    }

    static boolean[] foreground(BufferedImage im, int key) {
        int w = im.getWidth(), h = im.getHeight();
        boolean[] bg = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<Integer>();
        for (int x = 0; x < w; x++) { q.add(x); q.add((h - 1) * w + x); }
        for (int y = 0; y < h; y++) { q.add(y * w); q.add(y * w + w - 1); }
        while (!q.isEmpty()) {
            int k = q.poll();
            if (bg[k] || !keyLike(im.getRGB(k % w, k / w), key)) continue;
            bg[k] = true;
            int x = k % w, y = k / w;
            if (x > 0) q.add(k - 1); if (x < w - 1) q.add(k + 1); if (y > 0) q.add(k - w); if (y < h - 1) q.add(k + w);
        }
        boolean[] fg = new boolean[w * h];
        for (int k = 0; k < fg.length; k++) fg[k] = !bg[k];
        return fg;
    }

    /** Connected blobs: {cx, cy, halfWidth, count, minX, maxX}. */
    static List<int[]> blobs(boolean[] m, int w, int h, int minSize) {
        boolean[] seen = new boolean[m.length];
        List<int[]> out = new ArrayList<int[]>();
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || seen[s]) continue;
            ArrayDeque<Integer> q = new ArrayDeque<Integer>(); q.add(s); seen[s] = true;
            long sx = 0, sy = 0; int n = 0, minX = w, maxX = 0;
            while (!q.isEmpty()) {
                int k = q.poll(), x = k % w, y = k / w; sx += x; sy += y; n++; minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nk = ny * w + nx;
                    if (m[nk] && !seen[nk]) { seen[nk] = true; q.add(nk); }
                }
            }
            if (n >= minSize) out.add(new int[] {(int) (sx / n), (int) (sy / n), (maxX - minX + 1) / 2, n, minX, maxX});
        }
        return out;
    }

    /** Within columns [x0,x1) keeps only the largest 8-connected foreground shape. */
    static void keepLargest(boolean[] fg, int w, int h, int x0, int x1) {
        int[] label = new int[w * h];
        int next = 0, best = -1, bestSize = 0;
        List<Integer> sizes = new ArrayList<Integer>();
        for (int y = 0; y < h; y++) for (int x = x0; x < x1; x++) {
            int s = y * w + x;
            if (!fg[s] || label[s] != 0) continue;
            next++; int n = 0;
            ArrayDeque<Integer> q = new ArrayDeque<Integer>(); q.add(s); label[s] = next;
            while (!q.isEmpty()) {
                int k = q.poll(); n++; int kx = k % w, ky = k / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = kx + dx, ny = ky + dy;
                    if (nx < x0 || nx >= x1 || ny < 0 || ny >= h) continue;
                    int nk = ny * w + nx;
                    if (fg[nk] && label[nk] == 0) { label[nk] = next; q.add(nk); }
                }
            }
            if (n > bestSize) { bestSize = n; best = next; }
        }
        for (int y = 0; y < h; y++) for (int x = x0; x < x1; x++) { int k = y * w + x; if (fg[k] && label[k] != best) fg[k] = false; }
    }

    /** Tail lamps are wider than tall; keeps at most the two largest such blobs (drops side-skirt stripes and specks). */
    static void keepLampBlobs(boolean[] m, int w, int h) {
        boolean[] seen = new boolean[m.length];
        List<List<Integer>> keep = new ArrayList<List<Integer>>();
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || seen[s]) continue;
            List<Integer> comp = new ArrayList<Integer>();
            ArrayDeque<Integer> q = new ArrayDeque<Integer>(); q.add(s); seen[s] = true;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            while (!q.isEmpty()) {
                int k = q.poll(); comp.add(k); int x = k % w, y = k / w;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nk = ny * w + nx;
                    if (m[nk] && !seen[nk]) { seen[nk] = true; q.add(nk); }
                }
            }
            for (int k : comp) m[k] = false;
            int longest = Math.max(maxX - minX + 1, maxY - minY + 1);
            // Lamps are chunky; side-skirt stripes are 1-2 px diagonal lines.
            if (comp.size() >= 4 && comp.size() >= longest * 1.6f && (maxX - minX + 1) >= (maxY - minY + 1) * 0.9f) keep.add(comp);
        }
        keep.sort((a, b) -> b.size() - a.size());
        for (List<Integer> comp : keep) for (int k : comp) m[k] = true;
    }

    /** Clears connected mask components smaller than minSize pixels. */
    static void dropSpecks(boolean[] m, int w, int h, int minSize) {
        boolean[] seen = new boolean[m.length];
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || seen[s]) continue;
            List<Integer> comp = new ArrayList<Integer>();
            ArrayDeque<Integer> q = new ArrayDeque<Integer>(); q.add(s); seen[s] = true;
            while (!q.isEmpty()) {
                int k = q.poll(); comp.add(k); int x = k % w, y = k / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nk = ny * w + nx;
                    if (m[nk] && !seen[nk]) { seen[nk] = true; q.add(nk); }
                }
            }
            if (comp.size() < minSize) for (int k : comp) m[k] = false;
        }
    }

    /** Centroids of mask pixels left/right of split: {lx, ly, 0, rx, ry, 0}, or null if a side is empty. */
    static int[] sideCentres(boolean[] m, int w, int split) {
        long[] sx = new long[2], sy = new long[2]; int[] n = new int[2];
        for (int k = 0; k < m.length; k++) if (m[k]) { int s = k % w < split ? 0 : 1; sx[s] += k % w; sy[s] += k / w; n[s]++; }
        if (n[0] == 0 || n[1] == 0) return null;
        return new int[] {(int) (sx[0] / n[0]), (int) (sy[0] / n[0]), 0, (int) (sx[1] / n[1]), (int) (sy[1] / n[1]), 0};
    }

    /** Largest blob plus the largest one at least minGap px away, ordered left/right. */
    static int[] pickApart(List<int[]> list, int minGap) {
        if (list.isEmpty()) return null;
        list.sort((a, b) -> b[3] - a[3]);
        int[] a = list.get(0), b = null;
        for (int[] c : list) if (Math.abs(c[0] - a[0]) >= minGap) { b = c; break; }
        if (b == null) return null;
        if (a[0] > b[0]) { int[] t = a; a = b; b = t; }
        return new int[] {a[0], a[1], a[2], b[0], b[1], b[2]};
    }

    /** Two largest blobs ordered left/right: {lx, ly, lhalf, rx, ry, rhalf}. */
    static int[] pickPair(List<int[]> list) {
        if (list.size() < 2) return null;
        list.sort((a, b) -> b[3] - a[3]);
        int[] a = list.get(0), b = list.get(1);
        if (a[0] > b[0]) { int[] t = a; a = b; b = t; }
        return new int[] {a[0], a[1], a[2], b[0], b[1], b[2]};
    }

    static int[] medianCut(int[][] frames, int n) {
        List<int[]> all = new ArrayList<int[]>();
        for (int[] f : frames) for (int p : f) if (p != 0) all.add(new int[] {(p >> 16) & 255, (p >> 8) & 255, p & 255});
        List<List<int[]>> boxes = new ArrayList<List<int[]>>();
        boxes.add(all);
        while (boxes.size() < n) {
            int best = -1, bestRange = -1, bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) for (int c = 0; c < 3; c++) {
                int lo = 255, hi = 0;
                for (int[] p : boxes.get(i)) { lo = Math.min(lo, p[c]); hi = Math.max(hi, p[c]); }
                if (boxes.get(i).size() > 1 && hi - lo > bestRange) { bestRange = hi - lo; best = i; bestCh = c; }
            }
            if (best < 0) break;
            final int c = bestCh;
            List<int[]> b = boxes.remove(best);
            b.sort((x, y) -> x[c] - y[c]);
            boxes.add(new ArrayList<int[]>(b.subList(0, b.size() / 2)));
            boxes.add(new ArrayList<int[]>(b.subList(b.size() / 2, b.size())));
        }
        int[] pal = new int[boxes.size()];
        for (int i = 0; i < pal.length; i++) {
            long r = 0, g = 0, bl = 0;
            for (int[] p : boxes.get(i)) { r += p[0]; g += p[1]; bl += p[2]; }
            int s = Math.max(1, boxes.get(i).size());
            pal[i] = 0xff000000 | (int) (r / s) << 16 | (int) (g / s) << 8 | (int) (bl / s);
        }
        return pal;
    }

    static int nearest(int[] pal, int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255, best = pal[0], bd = Integer.MAX_VALUE;
        for (int q : pal) {
            int dr = r - ((q >> 16) & 255), dg = g - ((q >> 8) & 255), db = b - (q & 255);
            int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }
}
