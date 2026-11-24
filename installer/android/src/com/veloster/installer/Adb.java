// Adapted from sung9155/i30-pd-dashboard (i30pd_port/installer/android, MIT) for the Veloster N public repo.
package com.veloster.installer;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * The host half of adb, just enough to install an APK: connect + RSA auth, shell:, and sync: push and pull.
 * Speaks the old protocol the car's Android 4.2 adbd expects (version 0x01000000, 4 KB payloads,
 * byte-sum checksums). One stream at a time. No Android imports, so the desktop test drives it over TCP.
 */
final class Adb implements Closeable {
    /** A byte pipe to adbd. read() returns 0 when nothing arrived within timeoutMs. */
    interface Transport extends Closeable {
        void write(byte[] b, int off, int len) throws IOException;
        int read(byte[] b, int off, int len, int timeoutMs) throws IOException;
    }

    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Transport t;
    private int maxData = MAX_DATA, lastId;
    String banner = "";

    Adb(Transport t) { this.t = t; }

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private void send(int cmd, int a0, int a1, byte[] d, int off, int len) throws IOException {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += d[off + i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        t.write(h.array(), 0, 24);
        if (len > 0) t.write(d, off, len);
    }

    private void send(int cmd, int a0, int a1, byte[] d) throws IOException { send(cmd, a0, a1, d, 0, d.length); }

    private void readFully(byte[] b, int len, long deadline) throws IOException {
        for (int off = 0; off < len; ) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new IOException("adb 응답 없음 (시간 초과)");
            off += t.read(b, off, len - off, (int) Math.min(left, 1000));
        }
    }

    private Msg recv(long deadline) throws IOException {
        byte[] h = new byte[24];
        readFully(h, 24, deadline);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(0); m.a0 = b.getInt(4); m.a1 = b.getInt(8);
        int len = b.getInt(12);
        if (b.getInt(20) != ~m.cmd || len < 0 || len > 1 << 20) throw new IOException("adb 헤더가 깨졌습니다");
        m.data = new byte[len];
        readFully(m.data, len, deadline);
        return m;
    }

    /** onKeySent runs when the car has to show its "allow USB debugging" prompt for this phone's key. */
    void connect(KeyPair key, long timeoutMs, Runnable onKeySent) throws IOException, GeneralSecurityException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        for (;;) {
            Msg m = recv(deadline);
            if (m.cmd == CNXN) {
                maxData = Math.max(256, Math.min(MAX_DATA, m.a1));
                banner = new String(m.data, StandardCharsets.UTF_8);
                return;
            }
            if (m.cmd != AUTH || m.a0 != 1) continue;
            if (!signed) { send(AUTH, 2, 0, sign(key.getPrivate(), m.data)); signed = true; }
            else { send(AUTH, 3, 0, publicKey((RSAPublicKey) key.getPublic())); onKeySent.run(); }
        }
    }

    /** PKCS#1 v1.5 over SHA-1 DigestInfo with the token standing in for the hash: what adbd's RSA_verify(NID_sha1) checks. */
    static byte[] sign(PrivateKey k, byte[] token) throws GeneralSecurityException {
        byte[] em = new byte[256];
        int tail = SHA1_DIGEST_INFO.length + token.length;
        em[1] = 1;
        for (int i = 2; i < 256 - tail - 1; i++) em[i] = (byte) 0xff;
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, 256 - tail, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, 256 - token.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return c.doFinal(em);
    }

    /** Android's RSAPublicKey struct (words, n0inv, n, R^2 mod n, e; little-endian), base64, then a name. */
    static byte[] publicKey(RSAPublicKey pk) {
        BigInteger n = pk.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n.mod(r32).modInverse(r32).negate().mod(r32).intValue());
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(pk.getPublicExponent().intValue());
        return (Base64.getEncoder().encodeToString(b.array()) + " vn-installer@phone\0").getBytes(StandardCharsets.US_ASCII);
    }

    // --- one stream ---------------------------------------------------------
    private int local, remote;
    private boolean closed, writable;
    private final ByteArrayOutputStream inbox = new ByteArrayOutputStream();

    private void open(String service, long deadline) throws IOException {
        local = ++lastId; remote = 0; closed = false; writable = false; inbox.reset();
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        while (remote == 0) {
            pump(deadline);
            if (closed) throw new IOException("내비가 거부했습니다: " + service);
        }
    }

    /** Handles one message for the open stream. */
    private void pump(long deadline) throws IOException {
        Msg m = recv(deadline);
        if (m.a1 != local) return;   // stale message from an earlier stream
        if (m.cmd == OKAY) { remote = m.a0; writable = true; }
        else if (m.cmd == WRTE) { inbox.write(m.data, 0, m.data.length); send(OKAY, local, remote, new byte[0]); }
        else if (m.cmd == CLSE) closed = true;
    }

    private void write(byte[] d, long deadline) throws IOException {
        for (int off = 0; off < d.length; ) {
            while (!writable) { pump(deadline); if (closed) throw new IOException("내비가 연결을 닫았습니다"); }
            int n = Math.min(maxData, d.length - off);
            writable = false;
            send(WRTE, local, remote, d, off, n);
            off += n;
        }
    }

    private void close(long deadline) throws IOException {
        send(CLSE, local, remote, new byte[0]);
        while (!closed) pump(deadline);
    }

    /** Runs a shell command and returns its output (the car's pty uses \r\n). */
    String shell(String cmd, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        open("shell:" + cmd, deadline);
        while (!closed) pump(deadline);
        send(CLSE, local, remote, new byte[0]);
        return new String(inbox.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
    }

    /** sync: SEND path,mode / DATA... / DONE mtime, then OKAY or FAIL. */
    void push(File f, String remotePath, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        open("sync:", deadline);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        syncHeader(req, "SEND", (remotePath + ",33188").getBytes(StandardCharsets.UTF_8));   // 0100644
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) syncHeader(req, "DATA", java.util.Arrays.copyOf(buf, n));
        }
        ByteBuffer done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        done.put("DONE".getBytes(StandardCharsets.US_ASCII)).putInt((int) (System.currentTimeMillis() / 1000));
        req.write(done.array(), 0, 8);
        write(req.toByteArray(), deadline);
        while (inbox.size() < 8 && !closed) pump(deadline);
        byte[] r = inbox.toByteArray();
        if (r.length < 8) throw new IOException("전송 실패: 내비 응답 없음");
        if (!new String(r, 0, 4, StandardCharsets.US_ASCII).equals("OKAY")) {
            int len = ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            while (!closed && inbox.size() < 8 + len) pump(deadline);
            r = inbox.toByteArray();
            throw new IOException("전송 실패: " + new String(r, 8, Math.max(0, Math.min(len, r.length - 8)), StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream quit = new ByteArrayOutputStream();
        syncHeader(quit, "QUIT", new byte[0]);
        write(quit.toByteArray(), deadline);
        close(deadline);
    }

    /** sync: RECV path, then DATA... / DONE (or FAIL). Whole file in memory: only used for the two small APKs. */
    byte[] pull(String remotePath, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        open("sync:", deadline);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        syncHeader(req, "RECV", remotePath.getBytes(StandardCharsets.UTF_8));
        write(req.toByteArray(), deadline);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        for (int pos = 0; ; ) {
            byte[] r = await(pos + 8, deadline);
            String id = new String(r, pos, 4, StandardCharsets.US_ASCII);
            if (id.equals("DONE")) break;
            int len = ByteBuffer.wrap(r, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ((!id.equals("DATA") && !id.equals("FAIL")) || len < 0 || len > 1 << 16) throw new IOException("받기 실패: 응답이 깨졌습니다");
            r = await(pos + 8 + len, deadline);
            if (id.equals("FAIL")) throw new IOException("받기 실패: " + new String(r, pos + 8, len, StandardCharsets.UTF_8));
            file.write(r, pos + 8, len);
            pos += 8 + len;
        }
        ByteArrayOutputStream quit = new ByteArrayOutputStream();
        syncHeader(quit, "QUIT", new byte[0]);
        write(quit.toByteArray(), deadline);
        close(deadline);
        return file.toByteArray();
    }

    private byte[] await(int size, long deadline) throws IOException {
        while (inbox.size() < size) { if (closed) throw new IOException("받기 실패: 내비가 연결을 닫았습니다"); pump(deadline); }
        return inbox.toByteArray();
    }

    private static void syncHeader(ByteArrayOutputStream o, String id, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length);
        o.write(b.array(), 0, 8);
        o.write(body, 0, body.length);
    }

    @Override public void close() throws IOException { t.close(); }
}
