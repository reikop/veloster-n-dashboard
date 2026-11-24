/* SPDX-License-Identifier: GPL-3.0-only. Observed remote ownership, never platform-wide audio routing. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.AudioInputStream;
import java.io.IOException;

/** A purpose's immutable capture permission. Only relevant owner transitions
 * advance its epoch, including transitions that return to the prior value.
 * No request ACK, local focus gain, UI state, mode write, or global volume is
 * interpreted as a phone call. Existing Siri-only capture behavior is retained. */
public final class VoiceCapturePolicy implements AudioInputStream.CaptureGate {
    private final ModeControl modes;private final boolean telephony,dualPurpose;
    private AudioInputStream.CaptureState cached;
    public VoiceCapturePolicy(PairVerify.VerifiedSession session,ModeControl modes,String purpose,boolean dualPurpose)throws IOException{
        if(modes==null||!modes.belongsTo(session)||!("telephony".equals(purpose)||"speechRecognition".equals(purpose)))throw new IOException("Foreign voice mode capability");
        this.modes=modes;telephony="telephony".equals(purpose);this.dualPurpose=dualPurpose;
    }
    public AudioInputStream.CaptureState state(){
        ModeState observed=modes.snapshot();boolean closed=modes.isClosed(),allowed;long revision;
        if(telephony){allowed=!closed&&observed.audio==ModeState.PHONE&&observed.phoneCall==ModeState.PHONE;revision=Math.max(observed.audioRevision,observed.phoneRevision);}
        else if(dualPurpose){allowed=!closed&&observed.audio==ModeState.PHONE&&observed.phoneCall==ModeState.NONE&&observed.speechOwner==ModeState.PHONE&&observed.speechMode==2;
            revision=Math.max(Math.max(observed.audioRevision,observed.phoneRevision),observed.speechRevision);}
        else{allowed=!closed;revision=0;}
        synchronized(this){if(cached==null||cached.allowed!=allowed||cached.revision!=revision)cached=new AudioInputStream.CaptureState(allowed,revision);return cached;}
    }
}
