/* SPDX-License-Identifier: GPL-3.0-only. See audio-oem/NOTICE.md. */
package com.veloster.carplay.audiooem;
/** Pinned private Binder contract. */
public final class OemAlternateProtocol {
    public static final String SERVICE="com.veloster.carplay.oem_audio_alt";
    public static final String ABI="com.veloster.carplay.OemAudioAlt.v1";
    public static final String CALLBACK=ABI+".callback";
    public static final long LEASE_MILLIS=3000;
    private OemAlternateProtocol(){}
}
