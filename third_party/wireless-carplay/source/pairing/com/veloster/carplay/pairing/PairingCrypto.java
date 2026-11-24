/* SPDX-License-Identifier: GPL-3.0-only. See pairing/NOTICE.md. */
package com.veloster.carplay.pairing;
import java.io.IOException;

/** OpenSSL API17 primitives. No Java RNG fallback and no plaintext downgrade. */
public final class PairingCrypto {
    static { System.loadLibrary("vn-carplay-crypto"); }
    private PairingCrypto() {}
    static native byte[] generate(boolean ed25519) throws IOException; // private32 || public32
    static native byte[] publicKey(boolean ed25519, byte[] privateKey) throws IOException;
    static native byte[] shared(byte[] privateKey, byte[] peer) throws IOException;
    static native byte[] sign(byte[] privateKey, byte[] data) throws IOException;
    static native boolean verify(byte[] publicKey, byte[] data, byte[] signature) throws IOException;
    static native byte[] hkdf(byte[] secret, byte[] salt, byte[] info) throws IOException;
    static native byte[] aead(boolean seal, byte[] key, byte[] nonce, byte[] data, byte[] aad) throws IOException;
    static native void syncDirectory(String path) throws IOException;
    static byte[] derive(byte[] secret,String salt,String info)throws IOException {
        return hkdf(secret,salt.getBytes("US-ASCII"),info.getBytes("US-ASCII"));
    }
    static byte[] label(String label)throws IOException {
        byte[] ascii=label.getBytes("US-ASCII");
        if(ascii.length!=8)throw new IllegalArgumentException("Nonce label length");
        byte[] nonce=new byte[12];System.arraycopy(ascii,0,nonce,4,8);return nonce;
    }
    static byte[] counter(long counter)throws IOException {
        if(counter<0)throw new IOException("Control nonce exhausted");
        byte[] nonce=new byte[12];for(int i=4;i<12;i++){nonce[i]=(byte)counter;counter>>>=8;}return nonce;
    }
}
