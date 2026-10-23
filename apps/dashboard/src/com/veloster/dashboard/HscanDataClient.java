package com.veloster.dashboard;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import com.veloster.vehicle.VehicleDash;
import java.io.InputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;

/** Additional read-only subscriber to captured hscand fanout; no OEM startup or handshake. */
final class HscanDataClient {
    interface Gate { boolean accepts(); }
    private final Gate gate;
    private Session session;
    HscanDataClient(Gate gate) { this.gate = gate; }

    synchronized void start() {
        if (session != null || !"jsn_kr".equals(android.os.Build.PRODUCT)
            || !"eng.vmadmin.20250902.081552".equals(android.os.Build.VERSION.INCREMENTAL)) return;
        session = new Session();
        session.decoder.start();
        session.reader.start();
    }

    synchronized void stop() {
        if (session == null) return;
        Session old = session; session = null;
        old.close();
    }

    private static final class Chunk {
        final byte[] bytes;
        final long time, sequence;
        Chunk(byte[] source, int count, long time, long sequence) {
            bytes = new byte[count]; System.arraycopy(source, 0, bytes, 0, count);
            this.time = time; this.sequence = sequence;
        }
    }

    private final class Session {
        private final Object delivery = new Object(), socketLock = new Object();
        private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
        private volatile boolean active = true;
        private LocalSocket socket;
        private long sequence;
        final Thread reader, decoder;

        Session() {
            reader = new Thread(new Runnable() { public void run() { receive(); } }, "n-hscan-read");
            decoder = new Thread(new Runnable() { public void run() { decode(); } }, "n-hscan-decode");
            reader.setDaemon(true); decoder.setDaemon(true);
        }

        void close() {
            // Serialize with delivery, never with a native read or queue consumer.
            synchronized (delivery) { active = false; }
            synchronized (queue) { queue.clear(); queue.notifyAll(); }
            synchronized (socketLock) { closeSocket(socket); socket = null; }
            reader.interrupt(); decoder.interrupt();
        }

        private void receive() {
            byte[] buffer = new byte[4096];
            try {
                while (active) {
                    LocalSocket current = new LocalSocket();
                    synchronized (socketLock) {
                        if (!active) { closeSocket(current); break; }
                        socket = current;
                    }
                    sequence++; // A gap invalidates partial packets across reconnections.
                    synchronized (queue) { queue.clear(); }
                    try {
                        current.connect(new LocalSocketAddress("hscan_mux", LocalSocketAddress.Namespace.ABSTRACT));
                        current.setSoTimeout(500); // API17 needs connect before setting options.
                        InputStream input = current.getInputStream();
                        while (active) {
                            int n;
                            try { n = input.read(buffer); }
                            catch (SocketTimeoutException idle) { continue; }
                            if (n < 0) break;
                            if (n == 0) continue;
                            Chunk chunk = new Chunk(buffer, n, SystemClock.elapsedRealtime(), ++sequence);
                            synchronized (queue) {
                                if (!active) break;
                                // Never wait for model/rendering work. Sequence gaps reset partial frames.
                                if (queue.size() == 32) queue.removeFirst();
                                queue.addLast(chunk); queue.notifyAll();
                            }
                        }
                    } catch (IOException unavailable) {
                        // No diagnostic/handshake fallback; readings expire normally.
                    } finally {
                        synchronized (socketLock) {
                            closeSocket(current);
                            if (socket == current) socket = null;
                        }
                    }
                    if (active) try { Thread.sleep(2000); }
                    catch (InterruptedException stopped) { break; }
                }
            } finally { close(); }
        }

        private void decode() {
            HscanFrameDecoder parser = new HscanFrameDecoder(new HscanFrameDecoder.Sink() {
                public void record(int function, byte[] data, long receivedAt) {
                    synchronized (delivery) {
                        if (active && gate.accepts()) VehicleDash.hscanJsn(0xc7, function, data, receivedAt);
                    }
                }
            });
            long previous = -1;
            try {
                while (active) {
                    Chunk chunk;
                    synchronized (queue) {
                        while (active && queue.isEmpty()) queue.wait();
                        if (!active) break;
                        chunk = queue.removeFirst();
                    }
                    if (chunk.sequence != previous + 1) parser.reset();
                    previous = chunk.sequence;
                    parser.accept(chunk.bytes, 0, chunk.bytes.length, chunk.time);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally { close(); }
        }
    }

    private static void closeSocket(LocalSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
