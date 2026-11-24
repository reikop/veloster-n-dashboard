import android.graphics.Bitmap;
import android.graphics.Canvas;
import com.veloster.neondrive.DriveModel;
import com.veloster.neondrive.DriveSignals;
import com.veloster.neondrive.NeonRenderer;
import java.io.File;

/**
 * Host preview: drives the real DriveSignals/DriveModel/NeonRenderer with
 * synthetic hscand records and writes PNG stills plus an animated GIF.
 * Java2D rasterisation approximates the device; geometry and logic are real.
 */
public final class NeonDrivePreview {
    static final class Car {
        float kmh, pedal, bar, steerRight; boolean brake, left, right, hazard, lowBeam = true;
        /** Gear for the OEM N-mode packet: 0 neutral, 1..6, -1 none sent. */
        int gear = -1;
        boolean parkBrake;
        boolean blinkPhase(long t) { return (t % 700) < 380; }
        void feed(DriveSignals s, long t) {
            int raw = Math.round(kmh * 2);
            s.record(DriveSignals.CLU11, new byte[] {0, (byte) raw, (byte) ((raw >> 8) & 1), 0}, t);
            if (gear >= 0) { byte[] nadd = new byte[40]; nadd[4] = (byte) gear; s.gearInfo(nadd, t); }
            byte[] ems = new byte[8];
            ems[4] = (byte) (brake ? 2 : 1); ems[6] = (byte) Math.round(pedal / 0.3906f);
            s.record(DriveSignals.EMS12, ems, t);
            int p = Math.round(bar * 10);
            byte[] esp = new byte[8];
            esp[3] = (byte) ((p & 0x3f) << 2); esp[4] = (byte) ((p >> 6) & 0x3f);
            s.record(DriveSignals.ESP12, esp, t);
            byte[] cgw = {0x03, 0x44, (byte) 0x81, 0x00, (byte) 0xc0, 0, 0, 0};
            boolean on = blinkPhase(t);
            if ((left || hazard) && on) cgw[2] |= 1 << 3;
            if ((right || hazard) && on) cgw[7] |= (byte) (1 << 6);
            if (parkBrake) cgw[7] |= 0x10;
            if (lowBeam) cgw[3] |= (byte) 0x80;
            if (hazard) cgw[4] |= 1 << 1;
            s.record(DriveSignals.CGW1, cgw, t);
            int sas = Math.round(-steerRight * 10); // raw SAS: positive = left
            s.record(DriveSignals.SAS11, new byte[] {(byte) sas, (byte) (sas >> 8), 0, 0, 0}, t);
        }
    }

    static void render(NeonRenderer r, DriveModel.State st, File out, int w, int h) throws Exception {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        r.draw(new Canvas(b), w, h, st);
        javax.imageio.ImageIO.write(b.image(), "png", out);
    }

    /** Settles the model for two seconds of simulated driving at ~11 Hz records, ending on a lit blinker phase. */
    static DriveModel.State settle(DriveModel m, DriveSignals s, Car car, long start) {
        DriveModel.State st = null;
        long lastFeed = start - 1000;
        for (long t = start; ; t += 33) {
            if (t - lastFeed >= 90) { car.feed(s, t); lastFeed = t; }
            st = m.update(s, t, 0.033f);
            if (t - start >= 2000 && (t % 700) >= 120 && (t % 700) < 330 && t - lastFeed < 40) return st;
        }
    }

    static Bitmap SPRITES;

    public static void main(String[] args) throws Exception {
        SPRITES = Bitmap.fromImage(javax.imageio.ImageIO.read(new File(args[1])));
        File dir = new File(args[0]);
        dir.mkdirs();
        String[] names = {"cruise", "full-throttle", "braking", "left-signal", "right-signal", "hazard-stopped", "demo", "no-signal", "steer-left", "steer-right-throttle"};
        long base = 1_000_000L;
        for (int i = 0; i < names.length; i++) {
            NeonRenderer renderer = new NeonRenderer();
            renderer.setCarSprites(SPRITES);
            DriveSignals signals = new DriveSignals();
            DriveModel model = new DriveModel();
            Car car = new Car();
            DriveModel.State st;
            long t0 = base + i * 100_000L;
            switch (names[i]) {
                case "cruise": car.kmh = 82; car.gear = 4; car.pedal = 18; st = settle(model, signals, car, t0); break;
                case "full-throttle": car.kmh = 128; car.gear = 6; car.pedal = 96; st = settle(model, signals, car, t0); break;
                case "braking": car.kmh = 54; car.gear = 3; car.brake = true; car.bar = 28; st = settle(model, signals, car, t0); break;
                case "left-signal": car.kmh = 46; car.gear = 3; car.pedal = 10; car.left = true; st = settle(model, signals, car, t0); break;
                case "right-signal": car.kmh = 46; car.gear = 3; car.pedal = 10; car.right = true; st = settle(model, signals, car, t0); break;
                case "hazard-stopped": car.kmh = 0; car.gear = 0; car.brake = true; car.bar = 12; car.hazard = true; st = settle(model, signals, car, t0); break;
                case "steer-left": car.kmh = 58; car.gear = 3; car.pedal = 14; car.steerRight = -130; car.left = true; st = settle(model, signals, car, t0); break;
                case "steer-right-throttle": car.kmh = 96; car.gear = 5; car.pedal = 90; car.steerRight = 120; st = settle(model, signals, car, t0); break;
                case "demo": {
                    model.setFeedAvailable(false);
                    st = null;
                    for (long t = t0; t <= t0 + 5200; t += 33) st = model.update(null, t, 0.033f);
                    break;
                }
                default: {
                    st = null;
                    for (long t = t0; t <= t0 + 3000; t += 33) st = model.update(signals, t, 0.033f);
                }
            }
            render(renderer, st, new File(dir, (i + 1) + "-" + names[i] + ".png"), 800, 480);
        }
        // Standstill: waiting at a light (stop card + showroom) and parked (shoulder, idle scenery), night and day.
        String[] idle = {"light-night", "park-night", "light-day", "park-day"};
        for (int i = 0; i < idle.length; i++) {
            NeonRenderer ir = new NeonRenderer(); ir.setCarSprites(SPRITES);
            ir.setTheme(i < 2 ? com.veloster.neondrive.Theme.NIGHT : com.veloster.neondrive.Theme.DAY);
            DriveSignals is = new DriveSignals(); DriveModel im = new DriveModel(); Car ic = new Car();
            long t0 = base + 9_000_000L + i * 100_000L;
            ic.kmh = 64; ic.pedal = 20; ic.gear = 3;
            DriveModel.State ist = null;
            long t = t0, last = t0 - 1000;
            for (; t < t0 + 20000; t += 33) { if (t - last >= 90) { ic.feed(is, t); last = t; } ist = im.update(is, t, 0.033f); }
            ic.kmh = 0; ic.pedal = 0; ic.gear = 0; ic.brake = i % 2 == 0; ic.parkBrake = i % 2 == 1;
            long until = t + (i % 2 == 0 ? 6200 : 9000) + (i >= 2 ? 1400 : 0);
            for (; t < until; t += 33) { if (t - last >= 90) { ic.feed(is, t); last = t; } ist = im.update(is, t, 0.033f); }
            render(ir, ist, new File(dir, "idle-" + idle[i] + ".png"), 800, 480);
        }
        // Now playing: generated gradient "album art", long Korean title, with and without art.
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        {
            android.graphics.Canvas ac = new android.graphics.Canvas(art); android.graphics.Paint ap = new android.graphics.Paint();
            ap.setShader(new android.graphics.LinearGradient(0, 0, 300, 300, new int[] {0xffff4fd8, 0xff35f2ff}, null, android.graphics.Shader.TileMode.CLAMP));
            ac.drawRect(0, 0, 300, 300, ap); ap.setShader(null);
            ap.setColor(0xfffff27a); ac.drawCircle(150, 140, 70, ap);
            ap.setColor(0xff1a0836); ac.drawRect(0, 200, 300, 300, ap);
        }
        for (int i = 0; i < 2; i++) {
            NeonRenderer mr = new NeonRenderer(); mr.setCarSprites(SPRITES); mr.setTheme(i == 0 ? com.veloster.neondrive.Theme.NIGHT : com.veloster.neondrive.Theme.DAY);
            mr.setMedia(i == 0 ? "이토록 아름다운 밤에 우리가 함께라면" : "Plastic Love", i == 0 ? "에스지 워너비" : "Mariya Takeuchi", 0.42f, i == 0 ? art : null);
            DriveSignals ms = new DriveSignals(); DriveModel mm = new DriveModel(); Car mc = new Car();
            mc.kmh = 76; mc.pedal = 25; mc.gear = 4;
            render(mr, settle(mm, ms, mc, base + 8_000_000L + i * 10_000L), new File(dir, "media-" + (i == 0 ? "night-art" : "day-noart") + ".png"), 800, 480);
        }
        // 8-speed DCT gear display: drive 7, neutral, no data.
        int[] dctGears = {7, 0, -1};
        for (int i = 0; i < dctGears.length; i++) {
            NeonRenderer dr = new NeonRenderer(); dr.setCarSprites(SPRITES); dr.setGearMode(NeonRenderer.GEARS_DCT);
            DriveSignals ds = new DriveSignals(); DriveModel dm = new DriveModel(); Car dc = new Car();
            dc.kmh = dctGears[i] == 0 ? 0 : 118; dc.pedal = 30; dc.gear = dctGears[i];
            render(dr, settle(dm, ds, dc, base + 7_000_000L + i * 10_000L), new File(dir, "dct-" + (dctGears[i] < 0 ? "none" : dctGears[i] == 0 ? "N" : "D" + dctGears[i]) + ".png"), 800, 480);
        }
        // Tail lamps: on (dim, no high-mount stop lamp), off, and braking with them on, at night and by day.
        String[] lampCases = {"tail-on", "tail-off", "tail-brake"};
        for (int th : new int[] {com.veloster.neondrive.Theme.NIGHT, com.veloster.neondrive.Theme.DAY}) {
            for (int i = 0; i < lampCases.length; i++) {
                NeonRenderer lr = new NeonRenderer(); lr.setCarSprites(SPRITES); lr.setTheme(th);
                DriveSignals ls = new DriveSignals(); DriveModel lmodel = new DriveModel(); Car lc = new Car();
                lc.kmh = 40; lc.pedal = 8; lc.gear = 2; lc.lowBeam = i != 1; lc.brake = i == 2; lc.bar = lc.brake ? 20 : 0;
                DriveModel.State lst = settle(lmodel, ls, lc, base + 5_000_000L + th * 100_000L + i * 10_000L);
                render(lr, lst, new File(dir, "lamps-" + (th == 0 ? "night" : "day") + "-" + lampCases[i] + ".png"), 800, 480);
            }
        }
        // Settings panel and every factory colour on the cruise scene.
        for (int i = 0; i < com.veloster.neondrive.CarPaint.COUNT; i++) {
            Bitmap painted = SPRITES.copy(Bitmap.Config.ARGB_8888, true);
            int w = painted.getWidth(), rowH = painted.getHeight() / 9;
            int[] px = new int[w * rowH];
            painted.getPixels(px, 0, w, 0, 0, w, rowH);
            com.veloster.neondrive.CarPaint.repaint(px, i);
            painted.setPixels(px, 0, w, 0, 0, w, rowH);
            NeonRenderer renderer = new NeonRenderer();
            renderer.setCarSprites(painted);
            DriveSignals signals = new DriveSignals();
            DriveModel model = new DriveModel();
            Car car = new Car(); car.kmh = 74; car.pedal = 14;
            DriveModel.State st = settle(model, signals, car, 9_000_000L + i * 100_000L);
            renderer.setUi(0f, i == 1, i, false, 0);
            render(renderer, st, new File(dir, "paint-" + i + "-" + com.veloster.neondrive.CarPaint.CODE[i] + (i == 1 ? "-settings" : "") + ".png"), 800, 480);
            if (i == 0) { renderer.setUi(1f, false, 0, false, 0); render(renderer, st, new File(dir, "gear-button.png"), 800, 480); }
            if (i == 0) {   // every time of day, plus the settings panel at sunset
                String[] timeNames = {"night", "morning", "day", "sunset"};
                for (int th = 0; th < com.veloster.neondrive.Theme.COUNT; th++) {
                    NeonRenderer tr = new NeonRenderer(); tr.setCarSprites(painted); tr.setTheme(th);
                    tr.setClock(true, "22:51", "", "11월 23일 (월)");
                    tr.setUi(0f, false, i, false, 0);
                    render(tr, st, new File(dir, "time-" + th + "-" + timeNames[th] + ".png"), 800, 480);
                }
                NeonRenderer sr = new NeonRenderer(); sr.setCarSprites(painted); sr.setTheme(com.veloster.neondrive.Theme.SUNSET);
                sr.setUi(0f, true, i, false, 0);
                sr.setClock(true, "17:52", "", "11월 23일 (월)");
                sr.setSunInfo("07:09", "17:08", 3.0, 2, 35.18, 129.08);
                render(sr, st, new File(dir, "time-settings.png"), 800, 480);
            }
        }
        // Animated GIF of the demo loop, scaled down to keep the file small.
        NeonRenderer renderer = new NeonRenderer();
        renderer.setCarSprites(SPRITES);
        DriveModel model = new DriveModel();
        model.setFeedAvailable(false);
        int gw = 480, gh = 288;
        GifOut gif = new GifOut(new File(dir, "neon-drive-demo.gif"), 100);
        long t0 = 5_000_000L;
        for (long t = t0; t < t0 + DriveModel.DEMO_LOOP_MS; t += 33) {
            DriveModel.State st = model.update(null, t, 0.033f);
            if ((t - t0) % 99 != 0) continue;
            Bitmap b = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            renderer.draw(new Canvas(b), gw, gh, st);
            gif.add(b.image());
        }
        gif.close();
        System.out.println("Wrote preview to " + dir.getAbsolutePath());
    }

    /** Minimal looping animated GIF writer via ImageIO. */
    static final class GifOut {
        final javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersBySuffix("gif").next();
        final javax.imageio.stream.ImageOutputStream out;
        final int delayCs;
        boolean first = true;
        GifOut(File f, int delayMs) throws Exception {
            f.delete();
            out = javax.imageio.ImageIO.createImageOutputStream(f);
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            delayCs = delayMs / 10;
        }
        void add(java.awt.image.BufferedImage argb) throws Exception {
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(argb.getWidth(), argb.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(argb, 0, 0, null);
            javax.imageio.ImageTypeSpecifier type = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(rgb);
            javax.imageio.metadata.IIOMetadata meta = writer.getDefaultImageMetadata(type, null);
            String fmt = meta.getNativeMetadataFormatName();
            javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
            javax.imageio.metadata.IIOMetadataNode gce = child(root, "GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", String.valueOf(delayCs));
            gce.setAttribute("transparentColorIndex", "0");
            if (first) {
                javax.imageio.metadata.IIOMetadataNode apps = child(root, "ApplicationExtensions");
                javax.imageio.metadata.IIOMetadataNode app = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
                app.setAttribute("applicationID", "NETSCAPE");
                app.setAttribute("authenticationCode", "2.0");
                app.setUserObject(new byte[] {1, 0, 0});
                apps.appendChild(app);
                first = false;
            }
            meta.setFromTree(fmt, root);
            writer.writeToSequence(new javax.imageio.IIOImage(rgb, null, meta), null);
        }
        static javax.imageio.metadata.IIOMetadataNode child(javax.imageio.metadata.IIOMetadataNode root, String name) {
            for (int i = 0; i < root.getLength(); i++)
                if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (javax.imageio.metadata.IIOMetadataNode) root.item(i);
            javax.imageio.metadata.IIOMetadataNode node = new javax.imageio.metadata.IIOMetadataNode(name);
            root.appendChild(node);
            return node;
        }
        void close() throws Exception { writer.endWriteSequence(); out.close(); }
    }
}
