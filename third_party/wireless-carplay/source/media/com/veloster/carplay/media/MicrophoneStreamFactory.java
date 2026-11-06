/* SPDX-License-Identifier: GPL-3.0-only. Declared API17 Siri microphone input. */
package com.veloster.carplay.media;
import android.content.Context;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.AudioInputStream;
import com.veloster.carplay.tunnel.MicrophoneCaptureLease;
import java.io.IOException;

/** Product opt-in. AudioCapabilities only publishes input formats when the
 * same actual factory validates permission, platform buffers and exact profile. */
public final class MicrophoneStreamFactory implements IapSetupHandler.ModeAwareMicrophoneFactory {
    private final Context context;private final AudioCapabilities capabilities;
    private final MicrophoneCapabilityProbe.Result initialized;
    private final Eld16kInputCapability eld;
    /** Compatibility constructor. Unprobed factories cannot advertise input. */
    public MicrophoneStreamFactory(Context supplied,AudioCapabilities capabilities){this(supplied,capabilities,null);}
    public MicrophoneStreamFactory(Context supplied,AudioCapabilities capabilities,MicrophoneCapabilityProbe.Result initialized){this(supplied,capabilities,initialized,null);}
    public MicrophoneStreamFactory(Context supplied,AudioCapabilities capabilities,MicrophoneCapabilityProbe.Result initialized,Eld16kInputCapability eld){
        if(supplied==null||capabilities==null)throw new NullPointerException();
        Context app=supplied.getApplicationContext();context=app==null?supplied:app;
        this.capabilities=capabilities;this.initialized=initialized;this.eld=eld;
        if(initialized!=null&&!initialized.matches(context))throw new IllegalArgumentException("Foreign microphone probe context");
        if(eld!=null&&(initialized==null||!eld.matches(context,initialized)))
            throw new IllegalArgumentException("Foreign ELD codec/microphone proof");
    }
    public AudioCapabilities capabilities(){return capabilities;}
    public boolean supports(AudioDescription d){
        if(initialized==null||d==null||d.inputPort<1||d.type!=100||
            !(d.audioType.equals("speechRecognition")||d.audioType.equals("telephony"))||
            !capabilities.supportsInput(d))return false;
        try{
            if(d.format.bits==Eld16kInputCapability.FORMAT)
                return eld!=null&&d.inputSamples==480&&eld.physicalRate(purpose(d))!=0;
            return (d.format.bits==4||d.format.bits==16)&&
                (initialized.formats(purpose(d))&d.format.bits)==d.format.bits;
        }catch(IOException e){return false;}
    }
    public AudioInputStream open(PairVerify.VerifiedSession session,final AudioDescription d)throws IOException {
        if(capabilities.hasInput("telephony"))throw new IOException("Voice input requires exact mode capability");
        session.assertCurrent();if(!supports(d)||!d.audioType.equals("speechRecognition"))throw new IOException("Unsupported microphone input");
        return session.openAudioInput(d.connectionId,d.inputPort,d.inputSamples,d.format,source(d));
    }
    public AudioInputStream open(PairVerify.VerifiedSession session,final AudioDescription d,ModeControl modes)throws IOException {
        session.assertCurrent();if(!supports(d))throw new IOException("Unsupported microphone input");
        VoiceCapturePolicy policy=new VoiceCapturePolicy(session,modes,d.audioType,capabilities.hasInput("telephony"));
        return session.openAudioInput(d.audioType,d.connectionId,d.inputPort,d.inputSamples,d.format,source(d),MicrophoneCaptureLease.process(),policy);
    }
    private static Api17MicrophoneInput.Purpose purpose(AudioDescription d){return d.audioType.equals("telephony")?Api17MicrophoneInput.Purpose.TELEPHONY:Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION;}
    private AudioInputStream.SourceFactory source(final AudioDescription d){return new AudioInputStream.SourceFactory(){public AudioInputStream.Source open()throws IOException{
        initialized.checkCurrent();
        if(d.format.bits==Eld16kInputCapability.FORMAT){
            if(eld==null)throw new IOException("ELD input proof unavailable");
            int physical=eld.physicalRate(purpose(d));
            if(physical==0)throw new IOException("ELD physical microphone unavailable");
            return new Eld16kMicrophoneSource(context,physical,purpose(d));
        }
        return new Api17MicrophoneInput(context,d.format.sampleRate,d.inputSamples,purpose(d));
    }};}
}
