/* SPDX-License-Identifier: GPL-3.0-only. See tunnel/NOTICE.md. */
package com.veloster.carplay.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Receive-only type130 TCP port plus send-over-event byte-stream adapter.
 * Factory caller must supply a successful PairVerify lease and its encrypted event channel.
 * The adapter never creates identities, maps a signed identity to a Bluetooth MAC,
 * or treats a matching IP as proof of selected-phone identity. */
public final class IapDataTunnel implements Closeable {
    public static final String CLIENT_TYPE_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2";
    private static final int MAX_PENDING = 1048576;
    /** Immutable association to one verified control session and owned AP attempt. */
    public static final class Binding {
        public final String owner, controllerId, interfaceName;
        public final long generation;
        public final InetAddress localAddress, remoteAddress;
        public Binding(String owner, long generation, String controllerId, String interfaceName,
                       InetAddress localAddress, InetAddress remoteAddress) {
            if (owner == null || !owner.matches("[0-9a-f]{32}") || generation <= 0 ||
                controllerId == null || controllerId.length() == 0 || controllerId.length() > 256 ||
                interfaceName == null || interfaceName.length() == 0 || interfaceName.length() > 64 ||
                localAddress == null || remoteAddress == null || localAddress.isAnyLocalAddress() ||
                remoteAddress.isAnyLocalAddress() || localAddress.isMulticastAddress() || remoteAddress.isMulticastAddress())
                throw new IllegalArgumentException("Incomplete DataStream binding");
            this.owner = owner; this.generation = generation; this.controllerId = controllerId;
            this.interfaceName = interfaceName; this.localAddress = localAddress; this.remoteAddress = remoteAddress;
        }
    }
    public interface SessionLease {
        /** Must reject revoked/replaced PairVerify, owner, AP, interface, or selected-phone association.
         * Must be thread safe and nonblocking. No permissive production implementation is supplied. */
        void checkCurrent(Binding binding) throws IOException;
    }
    public interface MediaLease extends SessionLease {
        /** false keeps prepared media endpoints idle while type130 identifies the phone. */
        boolean mediaAllowed(Binding binding)throws IOException;
    }
    public interface EventChannel {
        /** Send encrypted iAPSendMessage(params.data) on exactly binding's session.
         * Must recheck ownership, serialize requests, bound I/O by timeoutMillis, and return
         * only when the actual event request succeeds. No plaintext or alternate-channel fallback. */
        void sendIapMessage(Binding binding, byte[] data, long timeoutMillis) throws IOException;
        /** Nonblocking, idempotent cancellation of this tunnel generation's requests.
         * Queued writes must stop promptly. A written command may retain its bounded
         * ACK wait to preserve a shared event connection; drain must be observed
         * before replacement. Must not close another/newer generation or session. */
        void cancelIapMessages(Binding binding);
    }
    private final Object lock = new Object();
    private final ReentrantLock sendLock = new ReentrantLock();
    private final Binding binding;
    private final SessionLease lease;
    private final EventChannel events;
    private final ServerSocket server;
    private final TunnelDecoder decoder;
    private final Thread worker;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();
    private Socket socket;
    private int pendingBytes, firstOffset;
    private boolean active, connected, closed, iap2Claimed, protocolRetired;
    private IOException failure;

    /** Binds only the given interface address. No data is processed before setupResponseSent(). */
    public IapDataTunnel(Binding binding, SessionLease lease, EventChannel events,
                         byte[] verifiedSharedSecret, String unsignedSeed) throws IOException {
        if (binding == null || lease == null || events == null) throw new NullPointerException();
        this.binding = binding; this.lease = lease; this.events = events;
        checkCurrent();
        byte[] key = TunnelCrypto.receiveKey(verifiedSharedSecret, unsignedSeed);
        try {
            decoder = new TunnelDecoder(key, new TunnelDecoder.Sink() {
                public void onIap(byte[] body) throws IOException { offer(body); }
            });
        } finally { Arrays.fill(key, (byte)0); }
        ServerSocket opened = null;
        try {
            opened = new ServerSocket();
            opened.bind(new InetSocketAddress(binding.localAddress, 0), 1);
            opened.setSoTimeout(250);
            checkCurrent();
        } catch (IOException e) { decoder.close(); if (opened != null) opened.close(); throw e; }
          catch (RuntimeException e) { decoder.close(); if (opened != null) opened.close(); throw e; }
        server = opened;
        worker = new Thread(new Runnable() { public void run() { runTunnel(); } }, "vn-carplay-datastream");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException e) { server.close(); decoder.close(); throw e; }
    }
    /** Advertise this port only as stream type130/streamID1 for CLIENT_TYPE_UUID. */
    public int dataPort() { return server.getLocalPort(); }
    public void setupResponseSent() throws IOException {
        checkCurrent();
        synchronized (lock) { checkOpen(); active = true; lock.notifyAll(); }
    }
    void claimIap2() throws IOException {
        checkCurrent();
        synchronized(lock) {
            checkOpen();
            if(!active)throw new IOException("SETUP response must be sent before opening iAP2");
            if(iap2Claimed)throw new IOException("DataStream already has an iAP2 owner");
            iap2Claimed=true;
        }
    }
    void requireCurrent() throws IOException {
        checkCurrent();
        synchronized(lock) { checkOpen(); }
    }
    private void checkCurrent() throws IOException {
        lease.checkCurrent(binding);
        NetworkInterface local = NetworkInterface.getByInetAddress(binding.localAddress);
        if (local == null || !binding.interfaceName.equals(local.getName()))
            throw new IOException("DataStream interface changed");
    }
    private void checkOpen() throws IOException {
        if (closed) throw new IOException("DataStream closed", failure);
    }
    private void offer(byte[] data) throws IOException {
        checkCurrent();
        synchronized (lock) {
            checkOpen();
            if (data.length > MAX_PENDING-pendingBytes || queue.size() >= 256)
                throw new IOException("DataStream receive backlog exceeded");
            queue.addLast(data.clone()); pendingBytes += data.length; lock.notifyAll();
        }
    }
    private void runTunnel() {
        byte[] readBuffer = new byte[16384];
        try {
            while (true) {
                checkCurrent();
                synchronized (lock) {
                    if (closed) return;
                    if (active) break;
                    lock.wait(250);
                }
            }
            // Wrong peers do not claim the connection or reset the nonce. No dual wildcard listeners.
            while (true) {
                checkCurrent();
                synchronized (lock) { if (closed) return; }
                Socket candidate;
                try { candidate = server.accept(); }
                catch (SocketTimeoutException e) { continue; }
                boolean adopted = false;
                try {
                    if (!sameAddress(candidate.getInetAddress(), binding.remoteAddress) ||
                        !sameAddress(candidate.getLocalAddress(), binding.localAddress)) continue;
                    candidate.setSoTimeout(250);
                    candidate.setTcpNoDelay(true);
                    checkCurrent();
                    synchronized (lock) {
                        checkOpen(); socket = candidate; connected = true; adopted = true; lock.notifyAll();
                    }
                } finally { if (!adopted) candidate.close(); }
                if (adopted) break;
            }
            server.close(); // A key/counter pair is never reused by a replacement TCP connection.
            InputStream in = socket.getInputStream();
            while (true) {
                checkCurrent();
                synchronized (lock) { if (closed) return; }
                int n;
                try { n = in.read(readBuffer); }
                catch (SocketTimeoutException e) { continue; }
                if (n < 0) { decoder.endOfInput(); throw new IOException("DataStream peer disconnected"); }
                checkCurrent();
                if (n > 0) decoder.feed(readBuffer, 0, n);
            }
        } catch (IOException e) { terminate(e); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); terminate(new IOException("DataStream interrupted", e)); }
          catch (RuntimeException e) { terminate(new IOException("DataStream worker failed", e)); }
          catch (LinkageError e) { terminate(new IOException("DataStream API unavailable", e)); }
        finally { Arrays.fill(readBuffer, (byte)0); decoder.close(); terminate(null); }
    }
    /** null = operation deadline; terminal state always throws. No session lifetime limit. */
    public byte[] receive(int maximum, long timeoutMillis) throws IOException, InterruptedException {
        if (maximum <= 0 || maximum > MAX_PENDING) throw new IllegalArgumentException("Receive limit");
        long deadline = deadline(timeoutMillis);
        while (true) {
            checkCurrent();
            synchronized (lock) {
                checkOpen();
                if (!queue.isEmpty()) {
                    byte[] head = queue.peekFirst();
                    int n = Math.min(maximum, head.length-firstOffset);
                    byte[] out = Arrays.copyOfRange(head, firstOffset, firstOffset+n);
                    Arrays.fill(head, firstOffset, firstOffset+n, (byte)0);
                    firstOffset += n; pendingBytes -= n;
                    if (firstOffset == head.length) { queue.removeFirst(); firstOffset = 0; }
                    return out;
                }
                long left = remainingMillis(deadline);
                if (left <= 0) return null;
                lock.wait(Math.min(left, 250));
            }
        }
    }
    /** Outbound bytes go exclusively through this verified session's encrypted event channel. */
    public void send(byte[] data, long timeoutMillis) throws IOException, InterruptedException {
        if (data == null || data.length == 0 || data.length > 65535) throw new IllegalArgumentException("Send length");
        long deadline = deadline(timeoutMillis);
        if (!sendLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("DataStream sender busy");
        try {
            try {
                while (true) {
                    checkCurrent();
                    synchronized (lock) {
                        checkOpen();
                        if (active && connected) break;
                        long left = remainingMillis(deadline);
                        if (left <= 0) throw new IOException("DataStream peer not ready");
                        lock.wait(Math.min(left, 250));
                    }
                }
                long left = remainingMillis(deadline);
                if (left <= 0) throw new IOException("DataStream send deadline");
                byte[] copy = data.clone();
                try { events.sendIapMessage(binding, copy, left); }
                finally { Arrays.fill(copy, (byte)0); }
                checkCurrent();
                synchronized (lock) { checkOpen(); }
            } catch (IOException e) { terminate(e); throw e; }
              catch (InterruptedException e) { terminate(new IOException("DataStream send interrupted", e)); throw e; }
              catch (RuntimeException e) { IOException failure = new IOException("DataStream event failed", e); terminate(failure); throw failure; }
        } finally { sendLock.unlock(); }
    }
    private void terminate(IOException reason) {
        Socket owned;
        synchronized (lock) {
            if (closed) return;
            closed = true; connected = false; failure = reason; owned = socket;
            for (byte[] b : queue) Arrays.fill(b, (byte)0);
            queue.clear(); pendingBytes = firstOffset = 0; lock.notifyAll();
        }
        try { server.close(); } catch (IOException ignored) {}
        if (owned != null) try { owned.close(); } catch (IOException ignored) {}
        try { events.cancelIapMessages(binding); }
        catch(RuntimeException e) { synchronized(lock) { if(failure==null)failure=new IOException("Event cancellation failed",e); } }
        catch(LinkageError e) { synchronized(lock) { if(failure==null)failure=new IOException("Event cancellation API unavailable",e); } }
        // Decoder is erased by its owning worker; never acquire decoder while holding lock.
        worker.interrupt();
    }
    public void close() { terminate(null); }
    /** Only an active, nonfailed transport can be relabeled as a protocol stop. */
    public boolean retire(){
        synchronized(lock){if(closed)return protocolRetired;protocolRetired=true;terminate(null);return true;}
    }
    public boolean protocolRetired(){synchronized(lock){return protocolRetired;}}
    public boolean awaitStopped(long timeoutMillis) throws InterruptedException {
        deadline(timeoutMillis);
        if (Thread.currentThread() == worker) return false;
        if (timeoutMillis > 0) worker.join(timeoutMillis);
        return !worker.isAlive()&&!sendLock.isLocked();
    }
    private static boolean sameAddress(InetAddress a, InetAddress b) {
        if (!a.equals(b)) return false;
        if (a instanceof Inet6Address && b instanceof Inet6Address && a.isLinkLocalAddress())
            return ((Inet6Address)a).getScopeId() == ((Inet6Address)b).getScopeId();
        return true;
    }
    private static long deadline(long millis) {
        if (millis < 0 || millis > 86400000) throw new IllegalArgumentException("Operation timeout");
        return System.nanoTime() + millis*1000000L;
    }
    private static long remainingMillis(long deadline) {
        long nanos = deadline-System.nanoTime();
        return nanos <= 0 ? 0 : (nanos+999999)/1000000;
    }
}
