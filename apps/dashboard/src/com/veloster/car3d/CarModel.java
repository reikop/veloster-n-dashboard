package com.veloster.car3d;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 내 차 screen's car mesh (assets/car3d/veloster_n.bin, written by scripts/car3d_model.py from the CC BY-NC-SA
 * "2022 Hyundai Veloster N" model by Ddiaz Design). Metres, +Z forward, +Y up, +X the car's left.
 * No Android: host-tested. Vertex and index data come back in direct buffers ready for glBufferData.
 */
public final class CarModel {
    public static final int VERSION = 2, STRIDE = 28;
    public static final int BODY = 0, LAMP = 1, GLASS = 2;
    public static final int FIXED = 0, WHEEL = 1, CALLIPER = 2;
    public static final int FL = 0, FR = 1, RL = 2, RR = 3;

    /** One draw call: up to 65535 vertices (16-bit indices, GLES 2.0 core). */
    public static final class Chunk {
        public int program, motion, corner, vertices, indices;
        /** Wheel / calliper pivot (the wheel centre); vertices are relative to it. */
        public float px, py, pz;
        public ByteBuffer vertexData, indexData;
    }

    /** Where a lamp group glows (for the halo sprites). */
    public static final class Spot {
        public int group;
        public float x, y, z, nx, ny, nz, radius;
    }

    public final Chunk[] chunks;
    public final Spot[] spots;
    public final int triangles;

    private CarModel(Chunk[] chunks, Spot[] spots) {
        this.chunks = chunks; this.spots = spots;
        int t = 0; for (Chunk c : chunks) t += c.indices / 3;
        triangles = t;
    }

    public static CarModel read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 22);
        byte[] b = new byte[1 << 16];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return parse(out.toByteArray());
    }

    public static CarModel parse(byte[] data) throws IOException {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) throw new IOException("big-endian host");
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (in.get() != 'V' || in.get() != 'N' || in.get() != '3' || in.get() != 'D') throw new IOException("not a car mesh");
            if (in.getInt() != VERSION) throw new IOException("mesh version");
            int count = in.getInt();
            if (count <= 0 || count > 256) throw new IOException("chunk count " + count);
            Chunk[] chunks = new Chunk[count];
            for (int i = 0; i < count; i++) {
                Chunk c = new Chunk();
                c.program = in.get() & 255; c.motion = in.get() & 255; c.corner = in.get() & 255; in.get();
                c.px = in.getFloat(); c.py = in.getFloat(); c.pz = in.getFloat();
                c.vertices = in.getInt(); c.indices = in.getInt();
                if (c.program > GLASS || c.motion > CALLIPER || c.corner > RR || c.vertices <= 0 || c.vertices > 65535
                    || c.indices <= 0 || c.indices % 3 != 0) throw new IOException("chunk " + i);
                c.vertexData = slice(data, in, c.vertices * STRIDE);
                c.indexData = slice(data, in, c.indices * 2);
                skip(in, (-c.indices * 2) & 3);
                ByteBuffer idx = c.indexData.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 0; k < c.indices; k++) if ((idx.getShort(k * 2) & 0xffff) >= c.vertices) throw new IOException("index range");
                chunks[i] = c;
            }
            int n = in.getInt();
            if (n < 0 || n > 64) throw new IOException("spot count");
            Spot[] spots = new Spot[n];
            for (int i = 0; i < n; i++) {
                Spot s = new Spot();
                s.group = in.get() & 255; in.get(); in.get(); in.get();
                s.x = in.getFloat(); s.y = in.getFloat(); s.z = in.getFloat();
                s.nx = in.getFloat(); s.ny = in.getFloat(); s.nz = in.getFloat(); s.radius = in.getFloat();
                spots[i] = s;
            }
            if (in.hasRemaining()) throw new IOException("trailing bytes");
            return new CarModel(chunks, spots);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("truncated car mesh");
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("truncated car mesh");
        }
    }

    private static ByteBuffer slice(byte[] data, ByteBuffer in, int length) {
        if (length > in.remaining()) throw new java.nio.BufferUnderflowException();
        ByteBuffer out = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        out.put(data, in.position(), length);
        ((java.nio.Buffer) out).position(0);
        skip(in, length);
        return out;
    }

    /** Through Buffer: JDK 9+ ByteBuffer.position(int) returns ByteBuffer, which API 17 does not have. */
    private static void skip(ByteBuffer in, int n) {
        ((java.nio.Buffer) in).position(in.position() + n);
    }
}
