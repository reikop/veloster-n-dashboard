import com.veloster.car3d.CarModel;
import com.veloster.car3d.CarState;
import com.veloster.neondrive.DriveSignals;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 내 차 (3D car): the shipped mesh parses and is laid out as the renderer expects; lamps / steering / roll logic. */
public class Car3DTest {
    static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(System.getProperty("vn.root", "."), "apps/dashboard/assets/car3d/veloster_n.bin"));
        CarModel m = CarModel.parse(data);
        check(m.triangles > 50000 && m.triangles < 125000, "triangle budget for the head unit: " + m.triangles);
        int[] wheels = new int[4], callipers = new int[4]; boolean lamp = false, glass = false;
        float minY = 9, maxY = -9, minZ = 9, maxZ = -9, minX = 9, maxX = -9;
        int[] groups = new int[CarState.GROUPS];
        for (CarModel.Chunk c : m.chunks) {
            if (c.motion == CarModel.WHEEL) wheels[c.corner]++;
            if (c.motion == CarModel.CALLIPER) callipers[c.corner]++;
            lamp |= c.program == CarModel.LAMP; glass |= c.program == CarModel.GLASS;
            ByteBuffer v = c.vertexData.duplicate().order(ByteOrder.nativeOrder());
            for (int i = 0; i < c.vertices; i++) {
                float x = v.getFloat(i * CarModel.STRIDE) + c.px, y = v.getFloat(i * CarModel.STRIDE + 4) + c.py, z = v.getFloat(i * CarModel.STRIDE + 8) + c.pz;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                int g = v.getShort(i * CarModel.STRIDE + 18) & 0xffff;
                check(g < CarState.GROUPS, "lamp group in range");
                if (c.program == CarModel.LAMP || g == 8) groups[g]++;
            }
            if (c.motion != CarModel.FIXED) {
                check(c.py > 0.3f && c.py < 0.35f, "wheel pivot at the axle height (tyre radius)");
                check((c.corner == CarModel.FL || c.corner == CarModel.RL) == (c.px > 0), "left corners on +X");
                check((c.corner <= CarModel.FR) == (c.pz > 0), "front corners on +Z");
            }
        }
        for (int k = 0; k < 4; k++) check(wheels[k] == 1 && callipers[k] == 1, "every corner has a wheel and a calliper: " + k);
        check(lamp && glass, "lamp and glass parts present");
        check(Math.abs(minY) < 0.01f, "the car stands on y=0");
        check(maxZ - minZ > 4.1f && maxZ - minZ < 4.4f, "length of a Veloster N (4,265 mm class): " + (maxZ - minZ));
        check(maxX - minX > 1.75f && maxX - minX < 2.15f, "width with mirrors: " + (maxX - minX));
        check(maxY > 1.3f && maxY < 1.45f, "height (1,399 mm): " + maxY);
        for (int g : new int[] {CarState.HEAD, CarState.TAIL, CarState.STOP, CarState.TURN_L, CarState.TURN_R, CarState.REVERSE, 8})
            check(groups[g] > 0, "lamp group has triangles: " + g);
        boolean[] spot = new boolean[CarState.GROUPS];
        for (CarModel.Spot s : m.spots) {
            spot[s.group] = true;
            if (s.group == CarState.TURN_L) check(s.x > 0, "left turn lamps on the car's left (+X)");
            if (s.group == CarState.TURN_R) check(s.x < 0, "right turn lamps on the car's right");
            if (s.group == CarState.HEAD) check(s.z > 1.5f && s.nz > 0, "headlamps at the front, facing forward");
            if (s.group == CarState.TAIL || s.group == CarState.STOP) check(s.z < -1.5f, "tail / stop lamps at the back");
        }
        check(spot[CarState.HEAD] && spot[CarState.TAIL] && spot[CarState.STOP] && spot[CarState.TURN_L] && spot[CarState.TURN_R], "halo spots for the lamps");
        // corrupt files are refused
        byte[] bad = data.clone(); bad[0] = 'X';
        try { CarModel.parse(bad); check(false, "bad magic refused"); } catch (java.io.IOException expected) { }
        try { CarModel.parse(java.util.Arrays.copyOf(data, data.length - 7)); check(false, "truncated file refused"); } catch (java.io.IOException expected) { }

        // ---- CarState ----
        CarState s = new CarState();
        s.update(null, 0, 1000);
        check(!s.live && s.glow[CarState.HEAD] == 0 && s.wheelDeg == 0, "no data: everything off, wheels straight");
        s.update(null, CarState.TEST_HEAD, 1100);
        check(s.head && s.tail && s.glow[CarState.HEAD] > 0.9f && s.glow[CarState.TAIL] > 0.3f && s.glow[CarState.TAIL] < 0.6f, "headlamps bring the tail lamps at tail brightness");
        s.update(null, CarState.TEST_HEAD | CarState.TEST_STOP, 1400);
        check(s.glow[CarState.TAIL] > 0.95f && s.glow[CarState.STOP] > 0.95f, "brake: tail lamps to full, high-mount stop lamp on");
        s.update(null, CarState.TEST_LEFT, 1500);
        boolean sawOn = false, sawOff = false;
        for (long t = 1500; t < 3000; t += 20) { s.update(null, CarState.TEST_LEFT, t); if (s.left) sawOn = true; else sawOff = true; check(!s.right, "only the left side blinks"); }
        check(sawOn && sawOff && s.leftActive, "test turn signal self-blinks");

        // live signals
        DriveSignals sig = new DriveSignals();
        long t0 = 10_000;
        byte[] cgw = new byte[8];
        cgw[4] = (byte) (2 << 6);   // light switch at headlamps
        cgw[2] = (byte) (1 << 3);   // left lamp lit
        sig.record(DriveSignals.CGW1, cgw, t0);
        sig.record(DriveSignals.SAS11, new byte[] {(byte) 0x2c, (byte) 0x01, 0, 0, 0}, t0);   // +30.0 deg raw = left
        sig.record(DriveSignals.EMS12, new byte[] {0, 0, 0, 0, 2, 0, 0, 0}, t0);              // brake pressed
        sig.record(DriveSignals.CLU11, new byte[] {0, (byte) 72, 0, 0}, t0);                  // 36 km/h
        CarState live = new CarState();
        live.update(sig.snapshot(t0), 0, t0);
        check(live.live && live.head && live.tail && live.stop, "live: headlamps, tail and stop lamps");
        check(live.left && !live.right, "live: the real left lamp phase");
        check(live.steerDeg < 0 && live.wheelDeg > 0, "steering left turns the front wheels left (+Y rotation)");
        check(Math.abs(live.wheelDeg - 30f / CarState.STEER_RATIO) < 0.01f, "road wheel angle = steering / ratio");
        cgw[2] = 0; sig.record(DriveSignals.CGW1, cgw, t0 + 400);
        live.update(sig.snapshot(t0 + 400), 0, t0 + 400);
        check(!live.left && live.leftActive, "lamp off phase while still blinking");
        // slow: the drawn wheel follows the real speed, no blur
        sig.record(DriveSignals.CLU11, new byte[] {0, (byte) 10, 0, 0}, t0 + 400);   // 5 km/h
        live.update(sig.snapshot(t0 + 400), 0, t0 + 400);
        float roll = live.rollDeg;
        live.update(sig.snapshot(t0 + 500), 0, t0 + 500);
        float expected = (float) Math.toDegrees(5 / 3.6 / CarState.TYRE_RADIUS_M * 0.1);
        float d = ((live.rollDeg - roll) % 360 + 360) % 360;
        check(Math.abs(d - expected) < 0.5f && live.blur == 0, "wheels roll with speed, no blur at walking pace: " + d + " vs " + expected);
        sig.record(DriveSignals.CLU11, new byte[] {0, (byte) 72, 0, 0}, t0 + 500);   // back to 36 km/h
        live.update(sig.snapshot(t0 + 500), 0, t0 + 500);
        check(live.blur > 0.99f, "36 km/h: rims fully blurred");
        // suspension follows the G sensor: braking dives the nose, accelerating squats, cornering left lifts the left side
        CarState sus = new CarState(); long ts = 50_000;
        for (int i = 0; i < 40; i++) sus.update(null, -0.9f, 0f, 0, ts += 33);
        check(sus.pitchDeg > 2f, "hard braking (-0.9 g): nose down " + sus.pitchDeg);
        for (int i = 0; i < 40; i++) sus.update(null, 0.4f, 0f, 0, ts += 33);
        check(sus.pitchDeg < -1f, "accelerating (+0.4 g): nose up " + sus.pitchDeg);
        for (int i = 0; i < 60; i++) sus.update(null, 0f, 0.6f, 0, ts += 33);
        check(sus.rollBodyDeg > 1f && Math.abs(sus.pitchDeg) < 0.5f, "cornering left: body leans right (left side up) " + sus.rollBodyDeg);
        for (int i = 0; i < 60; i++) sus.update(null, Float.NaN, Float.NaN, 0, ts += 33);
        check(Math.abs(sus.pitchDeg) < 0.2f && Math.abs(sus.rollBodyDeg) < 0.2f, "no G sensor: level");
        CarState parked = new CarState(); long tp = 90_000;
        for (int i = 0; i < 30; i++) parked.update(null, CarState.TEST_STOP | CarState.TEST_STEER, tp += 33);
        check(Math.abs(parked.pitchDeg) < 0.01f && Math.abs(parked.rollBodyDeg) < 0.01f, "test buttons never tilt the body, only the G sensor does");
        for (int i = 0; i < 60; i++) parked.update(null, 0, tp += 33);
        check(Math.abs(parked.pitchDeg) < 0.2f, "released: settles back level");
        float r0 = live.rollDeg; live.update(sig.snapshot(t0 + 600), 0, t0 + 600);
        float dr = ((live.rollDeg - r0) % 360 + 360) % 360;
        check(Math.abs(dr - CarState.ROLL_CAP_DPS * 0.1f) < 1f, "the drawn wheel turns at the capped rate when fast");
        live.update(null, 0, t0 + 700);
        check(!live.live && live.glow[CarState.HEAD] < 1f, "losing the signal fades the lamps");
        // backdrops: every table has a row per scene, stored values are clamped
        java.lang.reflect.Field[] fs = com.veloster.car3d.CarScene.class.getDeclaredFields();
        for (java.lang.reflect.Field f : fs) if (f.getType().isArray()) { f.setAccessible(true); check(java.lang.reflect.Array.getLength(f.get(null)) == com.veloster.car3d.CarScene.COUNT, "scene table " + f.getName()); }
        for (String f : com.veloster.car3d.CarScene.FILES) {   // panoramas: power-of-two (GLES 2.0 REPEAT wrap), 4:1
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(Paths.get(System.getProperty("vn.root", "."), "apps/dashboard/assets", f).toFile());
            check(im != null && im.getWidth() == 2048 && im.getHeight() == 512, "backdrop panorama " + f);
        }
        check(com.veloster.car3d.CarScene.valid(9) == 0 && com.veloster.car3d.CarScene.valid(3) == 3, "stored scene clamped");
        System.out.println("PASS: 3D car mesh (" + m.triangles + " triangles, " + m.chunks.length + " chunks), lamp groups, wheels, lamp and steering logic");
    }
}
