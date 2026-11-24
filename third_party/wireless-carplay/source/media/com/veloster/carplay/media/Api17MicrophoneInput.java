/* SPDX-License-Identifier: GPL-3.0-only. API17 microphone capture. */
package com.veloster.carplay.media;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AudioEffect;
import com.veloster.carplay.tunnel.AudioInputStream;
import java.io.*;

/** Actual negotiated mono8k/16k PCM capture; no synthetic samples, global mode/SCO changes or
 * fallback to another microphone source. Construction runs on the input worker. */
public final class Api17MicrophoneInput implements AudioInputStream.PreparedSource {
    public enum Purpose { SPEECH_RECOGNITION, TELEPHONY }
    private AudioRecord record;private boolean stopped,closed;
    private final Context context;private final int rate,samples,source;private final Purpose purpose;
    private final CaptureProcessing processing=new CaptureProcessing();
    public String processingStatus(){return processing.status();}
    public static boolean supported(Context context){
        return supportedFormats(context)!=0;
    }
    /** No ownerless initialization proof exists. Use MicrophoneCapabilityProbe.Result. */
    @Deprecated
    public static long supportedFormats(Context context){
        return 0;
    }
    /** Permission/buffer prefilter only, never a positive input declaration. */
    public static long bufferFormats(Context context){
        try{if(context==null||context.checkCallingOrSelfPermission("android.permission.RECORD_AUDIO")!=PackageManager.PERMISSION_GRANTED)return 0;
            long mask=0;if(AudioRecord.getMinBufferSize(8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)>0)mask|=0x4;
            if(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)>0)mask|=0x10;return mask;
        }catch(RuntimeException e){return 0;}catch(LinkageError e){return 0;}
    }
    @Deprecated
    public static boolean supportsFormat(Context context,long bit){return false;}
    public static boolean bufferFormat(Context context,long bit){
        int rate=bit==0x4?8000:bit==0x10?16000:0;
        try{return rate!=0&&context!=null&&context.checkCallingOrSelfPermission("android.permission.RECORD_AUDIO")==PackageManager.PERMISSION_GRANTED&&AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)>0;}
        catch(RuntimeException e){return false;}catch(LinkageError e){return false;}
    }
    public Api17MicrophoneInput(Context context,int samples)throws IOException {
        this(context,16000,samples);
    }
    public Api17MicrophoneInput(Context context,int rate,int samples)throws IOException {
        this(context,rate,samples,Purpose.SPEECH_RECOGNITION);
    }
    public Api17MicrophoneInput(Context context,int rate,int samples,Purpose purpose)throws IOException {
        if(purpose==null)throw new NullPointerException();
        long bit=rate==8000?0x4:rate==16000?0x10:0;
        if(bit==0||samples<rate/200||samples>rate*60/1000||!bufferFormat(context,bit))throw new IOException("Microphone permission/profile unavailable");
        this.context=context;this.rate=rate;this.samples=samples;this.purpose=purpose;
        source=purpose==Purpose.TELEPHONY?MediaRecorder.AudioSource.VOICE_COMMUNICATION:MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }
    public synchronized void prepare()throws IOException {
        if(closed||stopped)throw new IOException("Microphone retired");if(record!=null)return;
        if(!bufferFormat(context,rate==8000?4:16))throw new IOException("Microphone permission/profile withdrawn");
        int minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<1||minimum>524288)throw new IOException("Microphone buffer bounds");
        try{record=new AudioRecord(source,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,samples*8));
            if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getAudioSource()!=source)throw new IOException("Microphone not initialized for requested source");
            if(purpose==Purpose.TELEPHONY)effects(record.getAudioSessionId());}
        catch(IOException e){throw e;}catch(RuntimeException e){throw new IOException("Microphone startup unavailable",e);}
    }
    private static CaptureProcessing.Effect wrap(final AudioEffect effect){if(effect==null)return null;return new CaptureProcessing.Effect(){
        public boolean enabled(){return effect.getEnabled();}public boolean control(){return effect.hasControl();}
        public int enable(){return effect.setEnabled(true);}public void release(){effect.release();}
    };}
    /** Effects belong only to this new AudioRecord session. Optional failure is
     * observable; raw capture is not described as successful echo cancellation. */
    private void effects(int session){
        processing.prepare(new CaptureProcessing.Factory(){
            public CaptureProcessing.Effect echo(int id){return AcousticEchoCanceler.isAvailable()?wrap(AcousticEchoCanceler.create(id)):null;}
            public CaptureProcessing.Effect noise(int id){return NoiseSuppressor.isAvailable()?wrap(NoiseSuppressor.create(id)):null;}
        },session);
    }
    public synchronized void start()throws IOException {prepare();if(closed||stopped||record==null)throw new IOException("Microphone retired");try{record.startRecording();if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("Microphone did not start");}catch(RuntimeException e){throw new IOException("Microphone start failed",e);}}
    public int read(byte[] target,int offset,int count)throws IOException {AudioRecord value; synchronized(this){if(closed||stopped||record==null)throw new IOException("Microphone retired");value=record;}try{return value.read(target,offset,count);}catch(RuntimeException e){throw new IOException("Microphone read failed",e);}}
    public synchronized void stop(){if(stopped)return;stopped=true;if(record!=null)try{record.stop();}catch(IllegalStateException ignored){}}
    public synchronized void close()throws IOException{if(closed)return;stop();processing.close();AudioRecord value=record;if(value!=null)value.release();record=null;closed=true;}
}
