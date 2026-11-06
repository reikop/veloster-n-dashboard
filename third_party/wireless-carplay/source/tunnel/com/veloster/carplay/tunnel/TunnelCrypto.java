/* SPDX-License-Identifier: GPL-3.0-only. See ../../../../../NOTICE.md. */
package com.veloster.carplay.tunnel;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/** API17 JNI adapter. No fallback to unauthenticated/plaintext transport. */
public final class TunnelCrypto {
    static { System.loadLibrary("vn-carplay-crypto"); }
    private TunnelCrypto() {}
    private static native byte[] hkdf(byte[] secret, byte[] salt, byte[] info) throws IOException;
    static native byte[] open(byte[] key, byte[] nonce, byte[] sealed, byte[] aad) throws IOException;

    static native byte[] openScreen(byte[] key, byte[] nonce, byte[] sealed, byte[] aad) throws IOException;
    static native byte[] openAudio(byte[] key, byte[] nonce, byte[] sealed, byte[] aad) throws IOException;
    static native byte[] sealAudio(byte[] key, byte[] nonce, byte[] plain, byte[] aad) throws IOException;

    /** secret must be the 32-byte shared secret from this session's successful PairVerify.
     * This function derives a key; it does not establish or verify a pairing identity. */
    public static byte[] receiveKey(byte[] secret, String unsignedSeed) throws IOException {
        return streamKey(secret,unsignedSeed,"DataStream-Output-Encryption-Key");
    }
    public static byte[] transmitKey(byte[] secret,String unsignedSeed)throws IOException {
        return streamKey(secret,unsignedSeed,"DataStream-Input-Encryption-Key");
    }
    private static byte[] streamKey(byte[] secret,String unsignedSeed,String label)throws IOException {
        if (secret == null || secret.length != 32) throw new IllegalArgumentException("PairVerify secret length");
        if (unsignedSeed == null || !unsignedSeed.matches("0|[1-9][0-9]{0,19}") ||
                new BigInteger(unsignedSeed).bitLength() > 64)
            throw new IllegalArgumentException("Seed must be canonical unsigned uint64 decimal");
        return hkdf(secret, ("DataStream-Salt" + unsignedSeed).getBytes("US-ASCII"),
                label.getBytes("US-ASCII"));
    }

    static byte[] decrypt(byte[] key, long counter, byte[] header, byte[] sealed) throws IOException {
        return decryptRecord(key,counter,header,sealed,false);
    }
    static byte[] decryptScreen(byte[] key,long counter,byte[] header,byte[] sealed)throws IOException {
        return decryptRecord(key,counter,header,sealed,true);
    }
    private static byte[] decryptRecord(byte[] key,long counter,byte[] header,byte[] sealed,boolean screen)throws IOException {
        if (counter < 0) throw new IOException("DataStream counter exhausted");
        byte[] nonce = new byte[12];
        long n = counter;
        for (int i = 4; i < 12; i++) { nonce[i] = (byte)n; n >>>= 8; }
        try { return screen ? openScreen(key, nonce, sealed, header) : open(key, nonce, sealed, header); }
        finally { Arrays.fill(nonce, (byte)0); }
    }
}
