/* SPDX-License-Identifier: GPL-3.0-only. Fixed AirPlay Pair-Setup SRP profile. */
package com.veloster.carplay.pairing;
import java.io.IOException;

/** Native state is private to one PairSetup instance and wiped on every terminal path. */
final class Srp6a {
    static { System.loadLibrary("vn-carplay-crypto"); }
    private Srp6a() {}
    static native byte[] start()throws IOException; // salt16 || private32 || verifier384 || B384
    static native byte[] verify(byte[] state,byte[] peer,byte[] proof)throws IOException; // key64 || proof64
}
