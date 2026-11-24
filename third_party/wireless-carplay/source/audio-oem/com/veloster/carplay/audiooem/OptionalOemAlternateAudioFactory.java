/* SPDX-License-Identifier: GPL-3.0-only. See audio-oem/NOTICE.md. */
package com.veloster.carplay.audiooem;

import android.content.Context;
import com.veloster.carplay.control.AudioDescription;
import com.veloster.carplay.control.ModeControl;
import com.veloster.carplay.media.Api17AudioOutput;
import com.veloster.carplay.media.AudioSink;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.IOException;

/** Worker-time optional route selection. Ordinary output stays stream 3. A
 * missing OEM service uses stream 3; a present but untrusted service fails. */
public final class OptionalOemAlternateAudioFactory implements AudioSink.SessionFactory {
    private final AndroidOemAlternateGateway gateway;
    private final OemAlternateAudioFactory alternate;
    private final Api17AudioOutput.Factory ordinary=new Api17AudioOutput.Factory();
    public OptionalOemAlternateAudioFactory(Context context,String exactCompositeJarSha256)throws IOException{
        gateway=new AndroidOemAlternateGateway(context,exactCompositeJarSha256);
        alternate=new OemAlternateAudioFactory(gateway);
    }
    public AudioSink.Output open(AudioWireFormat format)throws IOException{return ordinary.open(format);}
    public AudioSink.Output open(PairVerify.VerifiedSession owner,ModeControl modes,
                                 AudioDescription description,AudioWireFormat format)throws IOException{
        if(owner==null||modes==null||description==null||format==null)throw new NullPointerException();
        owner.assertCurrent();
        if(description.type!=101||!gateway.servicePresent())return ordinary.open(format);
        return alternate.open(owner,modes,description,format);
    }
}
