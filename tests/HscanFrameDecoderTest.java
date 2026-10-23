package com.veloster.dashboard;

/** Synthetic framing tests only; no private vehicle capture is required. */
public final class HscanFrameDecoderTest {
    static int checks;
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    static void checksum(byte[] b) {
        b[263] = 0;
        for (int i = 0; i < 263; i++) b[263] ^= b[i];
    }
    static byte[] frame(int function, byte[] value) {
        byte[] b = new byte[264];
        int[] header = {255, 11, 142, 1, 139, 199, 1, 0, 199, 1, 0};
        for (int i = 0; i < header.length; i++) b[i] = (byte) header[i];
        b[11] = (byte) function; b[12] = (byte) value.length;
        System.arraycopy(value, 0, b, 13, value.length); checksum(b); return b;
    }
    public static void main(String[] args) {
        final int[] count = {0}; final long[] timestamp = {0};
        HscanFrameDecoder decoder = new HscanFrameDecoder(new HscanFrameDecoder.Sink() {
            public void record(int function, byte[] data, long at) {
                count[0]++; timestamp[0] = at;
            }
        });
        byte[] good = frame(7, new byte[] {0, 0, 70, 64, 31, 0, 0, 0});
        for (int chunk : new int[] {1, 7, 263, 264}) {
            decoder.reset(); int before = count[0];
            for (int i = 0; i < good.length; i += chunk)
                decoder.accept(good, i, Math.min(chunk, good.length - i), 1000);
            check(count[0] == before + 1, "fragment size " + chunk);
        }
        int before = count[0];
        byte[] bad = good.clone(); bad[30] ^= 1;
        decoder.accept(bad, 0, 264, 1);
        check(count[0] == before, "bad checksum rejected");
        bad = good.clone(); bad[9] = 2; bad[21] = 0x23; bad[22] = 9; checksum(bad);
        decoder.accept(bad, 0, 264, 1);
        check(count[0] == before, "malformed second item rejects entire batch");
        bad = good.clone(); bad[200] = 1; checksum(bad);
        decoder.accept(bad, 0, 264, 1);
        check(count[0] == before, "nonzero padding rejected");
        bad = good.clone(); bad[9] = 85; checksum(bad);
        decoder.accept(bad, 0, 264, 1);
        check(count[0] == before, "oversized count rejected");
        bad = good.clone(); bad[8] = (byte) 200; checksum(bad);
        decoder.accept(bad, 0, 264, 1);
        check(count[0] == before, "wrong payload type rejected");
        decoder.accept(new byte[] {1, 2, (byte) 255, 11}, 0, 4, 1);
        decoder.accept(good, 0, 100, 1);
        check(count[0] == before, "partial frame withheld");
        decoder.accept(good, 100, 164, 123);
        check(count[0] == ++before && timestamp[0] == 123, "resync and completion timestamp");
        decoder.accept(good, 0, 100, 1); decoder.reset();
        decoder.accept(good, 100, 164, 1);
        check(count[0] == before, "reset cannot join old fragments");
        decoder.accept(good, 0, 264, 2);
        check(count[0] == ++before, "fresh frame after reset");
        decoder.accept(frame(0x68, new byte[8]), 0, 264, 2);
        decoder.accept(frame(7, new byte[5]), 0, 264, 2);
        check(count[0] == before, "unknown function and wrong known size ignored");
        for (int function : new int[] {0x23, 0x53, 0x5a, 0x6e}) {
            byte[] value = frame(function, new byte[function == 0x53 ? 5 : 8]);
            decoder.accept(value, 0, 264, 2);
            check(count[0] == ++before, "allowlisted function " + function);
        }
        System.out.println("PASS " + checks + " synthetic HSCAN decoder checks");
    }
}
