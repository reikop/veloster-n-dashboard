package com.veloster.neondrive;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * Additional read-only subscriber to the captured hscand fanout socket.
 * No handshake, no OEM service calls, nothing is ever written to the socket.
 */
final class HscanFeed {
    private final DriveSignals signals;
    private Session session;

    HscanFeed(DriveSignals signals) { this.signals = signals; }

    /**
     * The Veloster N head unit (any build number). The framing was captured on eng.vmadmin.20250902.081552;
     * HscanFrames accepts only exact frames (header, XOR checksum, sizes, zero padding), so another build with
     * other framing just stays at NO SIGNAL. Elsewhere (not jsn_kr) the view runs the demo.
     */
    static boolean supported() {
        return "jsn_kr".equals(android.os.Build.PRODUCT);
    }

    synchronized void start() {
        if (session != null || !supported()) return;
        session = new Session();
        session.reader.start();
    }

    synchronized void stop() {
        if (session == null) return;
        Session old = session;
        session = null;
        old.close();
        signals.clear();
    }

    private final class Session {
        private volatile boolean active = true;
        private final Object lock = new Object();
        private LocalSocket socket;
        final Thread reader;

        Session() {
            reader = new Thread(new Runnable() { public void run() { receive(); } }, "neon-hscan-read");
            reader.setDaemon(true);
        }

        void close() {
            active = false;
            synchronized (lock) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                socket = null;
            }
            reader.interrupt();
        }

        private void receive() {
            HscanFrames frames = new HscanFrames(new HscanFrames.Sink() {
                public void record(int function, byte[] data, long at) { if (active) signals.record(function, data, at); }
            });
            byte[] buffer = new byte[4096];
            while (active) {
                LocalSocket current = new LocalSocket();
                synchronized (lock) {
                    if (!active) break;
                    socket = current;
                }
                frames.reset();
                try {
                    current.connect(new LocalSocketAddress("hscan_mux", LocalSocketAddress.Namespace.ABSTRACT));
                    current.setSoTimeout(500); // API17 needs connect before setting options.
                    InputStream input = current.getInputStream();
                    while (active) {
                        int n;
                        try { n = input.read(buffer); }
                        catch (SocketTimeoutException idle) { continue; }
                        if (n < 0) break;
                        if (n > 0) frames.accept(buffer, 0, n, SystemClock.elapsedRealtime());
                    }
                } catch (IOException unavailable) {
                    // Readings simply go stale; the view falls back to cruising.
                } finally {
                    synchronized (lock) {
                        try { current.close(); } catch (IOException ignored) { }
                        if (socket == current) socket = null;
                    }
                }
                if (active) try { Thread.sleep(2000); } catch (InterruptedException stopped) { break; }
            }
        }
    }
}
