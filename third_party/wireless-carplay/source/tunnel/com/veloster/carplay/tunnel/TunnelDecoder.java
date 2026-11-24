/* SPDX-License-Identifier: GPL-3.0-only. See tunnel/NOTICE.md. */
package com.veloster.carplay.tunnel;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Incremental authenticated records -> complete APTransportPackage bodies.
 * One instance per TCP connection; failures are terminal and erase buffered material.
 * Does not assume that TCP, AEAD records, AP packages, and iAP2 packets align. */
public final class TunnelDecoder implements Closeable {
    public interface Sink { void onIap(byte[] body) throws IOException; }
    private static final int MAX_PACKAGE = 4 * 1024 * 1024;
    private final Sink sink;
    private final byte[] key, header = new byte[2], packageHeader = new byte[32];
    private byte[] sealed, body;
    private int headerUsed, sealedUsed, packageUsed, remaining, bodyUsed;
    private long counter;
    private boolean closed;

    public TunnelDecoder(byte[] key, Sink sink) {
        if (key == null || key.length != 32 || sink == null) throw new IllegalArgumentException();
        this.key = key.clone(); this.sink = sink;
    }
    public synchronized void feed(byte[] input, int offset, int count) throws IOException {
        if (closed) throw new IOException("DataStream decoder closed");
        if (input == null || offset < 0 || count < 0 || offset > input.length-count)
            throw new IndexOutOfBoundsException();
        try {
            int end = offset + count;
            while (offset < end) {
                if (headerUsed < 2) {
                    header[headerUsed++] = input[offset++];
                    if (headerUsed == 2) sealed = new byte[u16(header) + 16];
                    continue;
                }
                int n = Math.min(end-offset, sealed.length-sealedUsed);
                System.arraycopy(input, offset, sealed, sealedUsed, n); offset += n; sealedUsed += n;
                if (sealedUsed == sealed.length) {
                    byte[] plain = TunnelCrypto.decrypt(key, counter, header, sealed);
                    // Deliberately retire before signed-long wrap. No nonce reuse or reconnect.
                    counter = counter == Long.MAX_VALUE ? -1 : counter + 1;
                    try { packages(plain); }
                    finally { Arrays.fill(plain, (byte)0); }
                    Arrays.fill(sealed, (byte)0); sealed = null; sealedUsed = headerUsed = 0;
                }
            }
        } catch (IOException e) { close(); throw e; }
          catch (RuntimeException e) { close(); throw new IOException("DataStream decode failed", e); }
          catch (LinkageError e) { close(); throw new IOException("DataStream crypto unavailable", e); }
    }
    private void packages(byte[] plain) throws IOException {
        int offset = 0;
        while (offset < plain.length) {
            if (packageUsed < 32) {
                int n = Math.min(32-packageUsed, plain.length-offset);
                System.arraycopy(plain, offset, packageHeader, packageUsed, n); offset += n; packageUsed += n;
                if (packageUsed < 32) continue;
                long size = u32(packageHeader, 0);
                if (size < 32 || size > MAX_PACKAGE) throw new IOException("Invalid APTransportPackage length");
                remaining = (int)size-32;
                if (u32(packageHeader, 16) == 0x636f6d6dL) body = new byte[remaining];
                if (remaining == 0) { completePackage(); continue; }
            }
            int n = Math.min(remaining, plain.length-offset);
            if (body != null) { System.arraycopy(plain, offset, body, bodyUsed, n); bodyUsed += n; }
            offset += n; remaining -= n;
            if (remaining == 0) completePackage();
        }
    }
    private void completePackage() throws IOException {
        byte[] completed = body;
        body = null; bodyUsed = packageUsed = remaining = 0;
        Arrays.fill(packageHeader, (byte)0);
        if (completed != null) {
            // Sink must copy bytes it retains. Unknown package types are bounded and skipped.
            try { if (completed.length != 0) sink.onIap(completed); }
            finally { Arrays.fill(completed, (byte)0); }
        }
    }
    public synchronized void endOfInput() throws IOException {
        boolean truncated = headerUsed != 0 || packageUsed != 0;
        close();
        if (truncated) throw new IOException("Truncated DataStream");
    }
    public synchronized void close() {
        closed = true; Arrays.fill(key, (byte)0); Arrays.fill(header, (byte)0);
        Arrays.fill(packageHeader, (byte)0);
        if (sealed != null) Arrays.fill(sealed, (byte)0);
        if (body != null) Arrays.fill(body, (byte)0);
        sealed = body = null; sealedUsed = headerUsed = bodyUsed = packageUsed = remaining = 0;
    }
    private static int u16(byte[] b) { return (b[0]&255) | ((b[1]&255)<<8); }
    private static long u32(byte[] b, int p) {
        return ((long)(b[p]&255)<<24) | ((long)(b[p+1]&255)<<16) | ((b[p+2]&255)<<8) | (b[p+3]&255);
    }
}
