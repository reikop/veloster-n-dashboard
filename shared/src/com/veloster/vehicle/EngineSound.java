package com.veloster.vehicle;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * Plays EngineSynth from the shared Telemetry. On this head unit an app's PCM reaches the amplifier only after the
 * micom opens a path for it (MicomAudioService.startAudio; AudioService never asks), which is why the first version,
 * a plain media-stream AudioTrack, was silent in the car. ROUTES (firmware analysis; tried in the car 2026-11-03): the
 * navi mix channel (Android STREAM_NAVI 12, plays over radio and music) as a system alarm, the only one that sounds
 * (front speakers, no bass), or as a navi sound effect; the main channel as a music source; and the plain media
 * stream without the micom. The last three were silent. Silent in reverse and without fresh rpm.
 */
final class EngineSound implements Runnable {
    /** Android stream and micom stream type per route (SettingsLayout.SOUND_ROUTES names them). */
    private static final int STREAM_NAVI=12;   // android.media.AudioSystem.STREAM_NAVI on this firmware
    private static final int[] ROUTE_STREAM={STREAM_NAVI,STREAM_NAVI,AudioManager.STREAM_MUSIC,AudioManager.STREAM_MUSIC};
    private static final int[] ROUTE_MICOM={0x41,0x40,0x31,-1};   // SYSTEM_ALARM, NAVI_SOUNDEFFECT, MY_MUSIC, none
    private static final int BLOCK=512;   // about 46 ms at 11.025 kHz on top of the 40..80 ms hscan rate
    /** 소리 반응 속도 "빠름": blocks of 256 (23 ms) and a buffer of 2 blocks instead of 512 and 4, so the sound is written about
     *  70 ms instead of 139 ms ahead. The risk is a gap when the UI thread stalls (the draw takes 40..130 ms here), which is why
     *  it is a switch; read when the thread starts, so changing it restarts the thread. */
    static volatile boolean fast;
    private final Context context;
    private final Telemetry data;
    private volatile Thread thread;
    private volatile float volume=.7f;
    private int route=-1;
    /** A one-shot beep (ShiftBeep.pcmMix, already at EngineSynth.RATE, mono) mixed into the stream so a shift beep is
     *  heard over the engine sound: the car's single alarm path carries only one AudioTrack, so a second one is masked
     *  by this stream. beepPos is set before beepMix so the audio thread never reads a stale position. */
    private volatile short[] beepMix;
    private volatile int beepPos;
    private volatile float beepGain=1;
    EngineSound(Context context,Telemetry data){this.context=context;this.data=data;}
    void volume(int percent){volume=Math.max(0,Math.min(200,percent))/100f;}   // up to 200 %: EngineSynth drives the extra into the soft clip
    /** Queue a beep to ride on the next blocks of the engine stream at gain g (0..2, above 1 clips harder). A new beep replaces one still playing. */
    void mixBeep(short[] pcm,float g){beepGain=Math.max(0,Math.min(2,g));beepPos=0;beepMix=pcm;}
    /** Engine and exhaust (EngineSynth tables), pop rate / turbo whistle / blow-off in percent of the voice's own, and
     *  the latency compensation in ms. Picked up by the audio thread on its next block. */
    private volatile EngineSynth.Voice voice=EngineSynth.voice(0,0);
    private volatile double popRate=1,turboVol=1,bovVol=1,lead=.08;
    void voice(int engine,int exhaust){voice=EngineSynth.voice(engine,exhaust);}
    /** The sound's own tuning in percent (SettingsLayout.SOUND_TONE..SOUND_LOW): brightness, flow noise, pipe reflections,
     *  intake, firing scatter (100 = as designed) and the off-load loudness (of full load). */
    private volatile double tone=1,rough=1,body=1,intake=1,uneven=1,low=EngineSynth.LOW;
    void tune(int tonePercent,int roughPercent,int bodyPercent,int intakePercent,int unevenPercent,int lowPercent){
        tone=tonePercent/100.0;rough=roughPercent/100.0;body=bodyPercent/100.0;intake=intakePercent/100.0;uneven=unevenPercent/100.0;low=lowPercent/100.0;}
    void trims(int popPercent,int turboPercent,int bovPercent,int leadMs){popRate=popPercent/100.0;turboVol=turboPercent/100.0;bovVol=bovPercent/100.0;lead=leadMs/1000.0;}

    /** Idempotent. route -1 stops. A new route stops the old thread first, so its endAudio never closes the new path. */
    synchronized void play(int r){
        if(r==route)return;
        Thread old=thread;thread=null;
        if(old!=null){old.interrupt();try{old.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        route=r;
        if(r>=0&&r<ROUTE_STREAM.length){thread=new Thread(this,"engine-sound");thread.start();}
    }

    public void run(){
        // Default, not THREAD_PRIORITY_AUDIO: on this head unit the audio band (-16) preempted the cluster's own
        // drawing and the whole UI stuttered. The stream buffer below is large enough to ride out the lower priority.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        int r=route,micom=ROUTE_MICOM[r];
        Object router=null;AudioTrack track=null;
        try{
            int min=AudioTrack.getMinBufferSize(EngineSynth.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            final int blk=fast?BLOCK/2:BLOCK;
            int bytes=Math.max(min,blk*2*(fast?2:4));long aheadMs=(bytes/2+blk)*1000L/EngineSynth.RATE;   // write blocks on a full buffer: this far ahead
            track=new AudioTrack(ROUTE_STREAM[r],EngineSynth.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,
                bytes,AudioTrack.MODE_STREAM);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED){android.util.Log.w("VehicleDash","Engine sound: AudioTrack not initialised, stream "+ROUTE_STREAM[r]);return;}
            if(micom>=0)router=startMicom(context,micom);
            track.play();
            android.util.Log.i("VehicleDash","Engine sound on: route "+r+" stream "+ROUTE_STREAM[r]+" micom 0x"+Integer.toHexString(micom)+" min buffer "+min+" block "+blk+" buffer "+bytes+" B ahead "+aheadMs+" ms");
            EngineSynth.Voice playing=voice;EngineSynth synth=new EngineSynth(EngineSynth.RATE,playing);float[] f=new float[blk];short[] pcm=new short[blk];int flames=0;
            long cost=0;int made=0;   // the synth's own CPU time (this thread's, not the wait behind the drawing), logged every 30 s of sound: the head unit's figure, which a PC cannot give
            while(thread==Thread.currentThread()){
                long now=SystemClock.elapsedRealtime();EngineSynth.voiced=volume>0;
                Telemetry.Sample rpm=data.sample(0,now),pedal=data.sample(9,now),boost=data.sample(Telemetry.BOOST,now),
                    selector=data.sample(Telemetry.GEAR_SELECTOR,now);
                Telemetry.Sample gear=data.sample(8,now);
                boolean reverse=selector.valid&&(int)selector.value==1||gear.valid&&(int)gear.value==14;   // DCT lever R, or 6MT reverse
                synth.set(rpm.valid&&rpm.ageMs<=1000&&!reverse?rpm.value:Double.NaN,pedal.valid?pedal.value/100.0:0,boost.valid?boost.value:0);
                if(voice!=playing){playing=voice;synth.use(playing);}
                synth.tune(tone,rough,body,intake,uneven,low);   // rebuilds the wavelets only when brightness or reflections changed
                synth.popRate=popRate;synth.turboVol=turboVol;synth.bovVol=bovVol;synth.lead=lead;
                long t0=android.os.Debug.threadCpuTimeNanos();synth.render(f,0,blk,volume);cost+=android.os.Debug.threadCpuTimeNanos()-t0;
                if((made+=blk)>=30*EngineSynth.RATE){android.util.Log.i("VehicleDash","Engine sound: "+cost/1000000*EngineSynth.RATE/made+" ms of CPU per s of sound, rpm "+(rpm.valid?(int)rpm.value:-1));cost=0;made=0;}
                if(synth.flames!=flames){flames=synth.flames;if(volume>0)EngineSynth.flameAt=now+aheadMs;}
                short[] bm=beepMix;
                if(bm!=null){int bp=beepPos;float bg=beepGain;
                    for(int i=0;i<blk&&bp<bm.length;i++,bp++){float s=f[i]+(bm[bp]/32000f)*bg;f[i]=s>1?1:s<-1?-1:s;}
                    beepPos=bp;if(bp>=bm.length)beepMix=null;}
                for(int i=0;i<blk;i++)pcm[i]=(short)Math.round(f[i]*32000);
                // MODE_STREAM write blocks on a full buffer and paces the loop; a negative return is an error, not
                // back-pressure, so stop rather than spin the CPU rewriting a dead track.
                int w=track.write(pcm,0,blk);
                if(w<0){android.util.Log.w("VehicleDash","Engine sound: AudioTrack.write "+w+", stopping");break;}
            }
        }catch(RuntimeException e){android.util.Log.e("VehicleDash","Engine sound stopped",e);}
        finally{
            EngineSynth.voiced=false;
            if(track!=null){try{track.stop();}catch(IllegalStateException ignored){/* never started */}track.release();}
            if(router!=null)endMicom(router,micom);
        }
    }

    /** MicomAudioManager lives in the head unit's automotive library; anywhere else this logs and plays unrouted. */
    static Object startMicom(Context context,int type){
        try{
            com.hkmc.media.MicomAudioManager m=new com.hkmc.media.MicomAudioManager(context);
            boolean ok=m.startAudio(type);
            android.util.Log.i("VehicleDash","Engine sound: micom startAudio(0x"+Integer.toHexString(type)+") = "+ok);
            return m;
        }catch(LinkageError|RuntimeException e){android.util.Log.w("VehicleDash","Engine sound: no micom audio router",e);return null;}
    }
    static void endMicom(Object router,int type){
        try{boolean ok=((com.hkmc.media.MicomAudioManager)router).endAudio(type);android.util.Log.i("VehicleDash","Engine sound: micom endAudio(0x"+Integer.toHexString(type)+") = "+ok);}
        catch(LinkageError|RuntimeException e){android.util.Log.w("VehicleDash","Engine sound: endAudio failed",e);}
    }
}
