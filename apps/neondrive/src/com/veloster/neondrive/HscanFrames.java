package com.veloster.neondrive;

/**
 * Exact captured 250902 hscand transport framing (264-byte frames, XOR checksum,
 * ALL_C 0xC7 batches). Same layout as the dashboard decoder; forwards only the
 * functions accepted by {@link DriveSignals}.
 */
public final class HscanFrames {
    public interface Sink { void record(int function, byte[] data, long receivedAt); }
    private static final int[] HEADER = {255, 11, 142, 1, 139, 199, 1, 0};
    private final byte[] frame = new byte[264];
    private final Sink sink;
    private int used;
    public HscanFrames(Sink sink) { this.sink = sink; }
    public void reset() { used = 0; }

    public void accept(byte[] data, int offset, int length, long receivedAt) {
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        for (int i = offset; i < offset + length; i++) {
            frame[used++] = data[i];
            while (used > 0 && !prefix()) discardFirst();
            if (used != frame.length) continue;
            int checksum = 0;
            for (byte b : frame) checksum ^= b & 255;
            if (checksum == 0) {
                batch(receivedAt);
                used = 0;
            } else {
                discardFirst();
                while (used > 0 && !prefix()) discardFirst();
            }
        }
    }

    private boolean prefix() {
        for (int i = 0; i < Math.min(used, HEADER.length); i++)
            if ((frame[i] & 255) != HEADER[i]) return false;
        return true;
    }
    private void discardFirst() { used--; System.arraycopy(frame, 1, frame, 0, used); }

    private void batch(long receivedAt) {
        if ((frame[8] & 255) != 0xc7) return;
        int count = (frame[9] & 255) | ((frame[10] & 255) << 8);
        if (count > 84) return;
        int offset = 11;
        // Validate the complete batch before publishing any values.
        for (int i = 0; i < count; i++) {
            if (offset + 2 > 263) return;
            int size = frame[offset + 1] & 255;
            if (size < 1 || size > 8 || offset + 2 + size > 263) return;
            offset += 2 + size;
        }
        for (int i = offset; i < 263; i++) if (frame[i] != 0) return;
        offset = 11;
        for (int i = 0; i < count; i++) {
            int function = frame[offset++] & 255, size = frame[offset++] & 255;
            if (DriveSignals.accepts(function, size)) {
                byte[] value = new byte[size];
                System.arraycopy(frame, offset, value, 0, size);
                sink.record(function, value, receivedAt);
            }
            offset += size;
        }
    }
}
