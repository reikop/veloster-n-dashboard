/* SPDX-License-Identifier: GPL-3.0-only. Optional, unadvertised codec proof. */
package com.veloster.carplay.media;
import android.content.Context;
import java.io.IOException;
import java.util.Arrays;

/** Owner-scoped physical capture plus actual system AAC-ELD encoder probe.
 * Discovery may use this for one attempt; it does not publish any format.
 * The probe closes its codec before returning. This immutable result needs no
 * close, but must be discarded and probed again for each new attempt/owner.
 * physicalRate() checks the original owner each time. Capture separately
 * opens and checks a new codec instance so a later system change fails closed.
 */
public final class Eld16kInputCapability {
    public static final long FORMAT=0x04000000L;
    private final Context context;
    private final MicrophoneCapabilityProbe.Result microphone;
    private final int speechRate,telephonyRate;
    private Eld16kInputCapability(Context context,MicrophoneCapabilityProbe.Result microphone,
                                 int speechRate,int telephonyRate){
        this.context=context;this.microphone=microphone;
        this.speechRate=speechRate;this.telephonyRate=telephonyRate;
    }
    private static int physical(long bits){return (bits&0x10L)!=0?16000:(bits&0x4L)!=0?8000:0;}
    public static Eld16kInputCapability probe(Context supplied,MicrophoneCapabilityProbe.Result result)throws IOException {
        if(supplied==null||result==null)throw new NullPointerException();
        Context app=supplied.getApplicationContext();Context context=app==null?supplied:app;
        if(!result.matches(context))throw new IOException("Foreign microphone capability context");
        int speech=physical(result.formats(Api17MicrophoneInput.Purpose.SPEECH_RECOGNITION));
        int call=physical(result.formats(Api17MicrophoneInput.Purpose.TELEPHONY));
        if(speech==0&&call==0)throw new IOException("No initialized voice capture rate");
        SystemEld16kEncoder encoder=null;byte[] asc=null;
        try{
            try{encoder=new SystemEld16kEncoder();}
            catch(LinkageError e){throw new IOException("ELD system codec unavailable",e);}
            asc=encoder.audioSpecificConfig();
            if(!Arrays.equals(asc,new byte[]{(byte)0xf8,(byte)0xf0,0x30,0}))
                throw new IOException("ELD mono16 codec config mismatch");
            result.checkCurrent();
            return new Eld16kInputCapability(context,result,speech,call);
        }finally{if(asc!=null)Arrays.fill(asc,(byte)0);if(encoder!=null)encoder.close();}
    }
    public boolean matches(Context supplied,MicrophoneCapabilityProbe.Result result){
        if(supplied==null||result==null)return false;
        Context app=supplied.getApplicationContext();return context==(app==null?supplied:app)&&microphone==result;
    }
    public int physicalRate(Api17MicrophoneInput.Purpose purpose)throws IOException {
        if(purpose==null)throw new NullPointerException();
        microphone.checkCurrent();
        return purpose==Api17MicrophoneInput.Purpose.TELEPHONY?telephonyRate:speechRate;
    }
}
