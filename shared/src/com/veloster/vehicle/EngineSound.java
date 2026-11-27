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
    /** 안내·통화 양보 (SoundYield.OFF / DUCK / STOP) and 속도 따라 키우기 level (0..3). The mode is read when the thread
     *  starts (OFF keeps the old path: startAudio once, endAudio at the end), so changing it restarts the thread. */
    static volatile int yieldMode,boostLevel;
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
    private volatile double engineMix=1;
    /** 엔진음 in percent: the engine itself against the turbo whistle and blow-off (0 = only those). */
    void engineMix(int percent){engineMix=Math.max(0,Math.min(100,percent))/100.0;}
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
        int r=route,micom=ROUTE_MICOM[r],mode=yieldMode;
        Object router=null;AudioTrack track=null;
        SoundYield yield=mode==SoundYield.OFF?null:new SoundYield(mode);MicomListener ears=null;boolean open=false;double lastGain=0;
        try{
            int min=AudioTrack.getMinBufferSize(EngineSynth.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            final int blk=fast?BLOCK/2:BLOCK;
            int bytes=Math.max(min,blk*2*(fast?2:4));long aheadMs=(bytes/2+blk)*1000L/EngineSynth.RATE;   // write blocks on a full buffer: this far ahead
            track=new AudioTrack(ROUTE_STREAM[r],EngineSynth.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,
                bytes,AudioTrack.MODE_STREAM);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED){android.util.Log.w("VehicleDash","Engine sound: AudioTrack not initialised, stream "+ROUTE_STREAM[r]);return;}
            if(yield!=null){ears=MicomListener.register(yield,micom);if(ears==null)yield=null;}   // no listener: as with 양보 off
            if(yield==null){if(micom>=0)router=startMicom(context,micom);}
            else{router=micom>=0?newRouter(context):null;if(router==null)open=true;}   // no path to manage: only the gain follows the voices
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
                boolean live=rpm.valid&&rpm.ageMs<=1000&&!reverse;
                Telemetry.Sample speed=data.sample(7,now);
                float vol=volume*(float)SoundYield.speedBoost(boostLevel,speed.valid?speed.value:Double.NaN);
                long t0=android.os.Debug.threadCpuTimeNanos();
                {synth.set(live?rpm.value:Double.NaN,pedal.valid?pedal.value/100.0:0,boost.valid?boost.value:0);
                    if(voice!=playing){playing=voice;synth.use(playing);}
                    synth.popRate=popRate;synth.turboVol=turboVol;synth.bovVol=bovVol;synth.lead=lead;synth.engineVol=engineMix;
                    synth.render(f,0,blk,vol);}
                cost+=android.os.Debug.threadCpuTimeNanos()-t0;
                if(yield!=null){   // the path follows the head unit's voices (SoundYield): open, closed, taken back
                    long up=SystemClock.uptimeMillis();
                    if(router!=null){int act=yield.tick(up,live&&volume>0||beepMix!=null,lastGain);
                        if(act==SoundYield.ACT_OPEN||act==SoundYield.ACT_REGRAB){yield.ownStartCalled(up);boolean ok=micomStart(router,micom);
                            if(act==SoundYield.ACT_OPEN)yield.openResult(ok,up);else yield.regrabResult(ok,up);open=ok;
                            android.util.Log.i("VehicleDash","Engine sound: "+(act==SoundYield.ACT_OPEN?"open":"regrab")+" "+ok);}
                        else if(act==SoundYield.ACT_CLOSE){
                            if(open&&!yield.skipEnd(up)){yield.ownEndCalled(up);endMicom(router,micom);}
                            if(open)android.util.Log.i("VehicleDash","Engine sound: closed, voices "+yield.activeSources(up));
                            open=false;}}
                    double g=open?yield.gain(up):0,g0=lastGain;lastGain=g;   // closed: silent, so nothing leaks into a voice's channel
                    if(g<1||g0<1)for(int i=0;i<blk;i++)f[i]*=(float)(g0+(g-g0)*(i+1)/blk);   // ramp across the block: no zipper
                }
                if((made+=blk)>=30*EngineSynth.RATE){android.util.Log.i("VehicleDash","Engine sound: "+cost/1000000*EngineSynth.RATE/made+" ms of CPU per s of sound, rpm "+(rpm.valid?(int)rpm.value:-1));cost=0;made=0;}
                if(synth.flames!=flames){flames=synth.flames;if(volume>0)EngineSynth.flameAt=now+aheadMs;}
                short[] bm=beepMix;   // with 양보 on, a beep waits for the path (opened for it on the next block) and is dropped while a voice speaks
                if(bm!=null&&yield!=null&&!open){if(yield.yielding(SystemClock.uptimeMillis()))beepMix=null;bm=null;}
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
            if(ears!=null)ears.unregister();
            if(router!=null&&(yield==null||open&&!yield.skipEnd(SystemClock.uptimeMillis())))endMicom(router,micom);
        }
    }

    /** MicomAudioManager lives in the head unit's automotive library; anywhere else this logs and plays unrouted. */
    static Object startMicom(Context context,int type){
        Object m=newRouter(context);
        if(m!=null)micomStart(m,type);
        return m;
    }
    static Object newRouter(Context context){
        try{return new com.hkmc.media.MicomAudioManager(context);}
        catch(LinkageError|RuntimeException e){android.util.Log.w("VehicleDash","Engine sound: no micom audio router",e);return null;}
    }
    static boolean micomStart(Object router,int type){
        try{boolean ok=((com.hkmc.media.MicomAudioManager)router).startAudio(type);
            android.util.Log.i("VehicleDash","Engine sound: micom startAudio(0x"+Integer.toHexString(type)+") = "+ok);return ok;}
        catch(LinkageError|RuntimeException e){android.util.Log.w("VehicleDash","Engine sound: startAudio failed",e);return false;}
    }

    /**
     * IMicomAudioStateChangeListener by hand (raw binder to com.hkmc.micom.audiosetup, registerListener 3 /
     * unregisterListener 4, V141): the head unit's voices start and end, and AudioStart / AudioEnd per stream type,
     * fed to SoundYield. Every event is logged ("Engine sound: micom ...") so a drive shows what this firmware
     * really sends. Calls arrive oneway on binder threads.
     */
    static final class MicomListener extends android.os.Binder {
        private static final String SVC="com.hkmc.micom.audiosetup",DESC="com.hkmc.media.IMicomAudioService",LDESC="com.hkmc.media.IMicomAudioStateChangeListener";
        private final SoundYield yield;private final int ownType;private android.os.IBinder svc;
        private MicomListener(SoundYield y,int own){yield=y;ownType=own;attachInterface(null,LDESC);}
        /** null when the service is missing (not this head unit) or refuses: the sound then plays as with 양보 off */
        static MicomListener register(SoundYield y,int ownType){
            MicomListener l=new MicomListener(y,ownType);
            try{l.svc=(android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,SVC);
                if(l.svc==null||!l.call(3))return null;
                android.util.Log.i("VehicleDash","Engine sound: micom listener registered, own type 0x"+Integer.toHexString(ownType));return l;}
            catch(Exception|LinkageError e){android.util.Log.w("VehicleDash","Engine sound: no micom listener",e);return null;}
        }
        void unregister(){call(4);}
        private boolean call(int code){
            android.os.Parcel d=android.os.Parcel.obtain(),r=android.os.Parcel.obtain();
            try{d.writeInterfaceToken(DESC);d.writeStrongBinder(this);svc.transact(code,d,r,0);r.readException();return true;}
            catch(Exception e){android.util.Log.w("VehicleDash","Engine sound: micom listener call "+code,e);return false;}
            finally{d.recycle();r.recycle();}
        }
        @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags){
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(LDESC);return true;}
            try{data.enforceInterface(LDESC);}catch(RuntimeException e){return false;}
            long now=SystemClock.uptimeMillis();String what;
            try{
                String src=SoundYield.source(code);
                if(src!=null){boolean start=SoundYield.isStart(code);yield.source(src,start,now);what=(start?"start ":"end ")+src;}
                else if(code==SoundYield.CB_AUDIO_START||code==SoundYield.CB_AUDIO_END){
                    int t=data.readInt();boolean start=code==SoundYield.CB_AUDIO_START;String ev=(start?"AudioStart 0x":"AudioEnd 0x")+Integer.toHexString(t);
                    if(t==ownType){int who=yield.ownType(start,now);what=ev+" own "+(who==SoundYield.OURS?"ours":who==SoundYield.RESTORE?"restore":"foreign");}
                    else if(SoundYield.yieldsFor(t)){yield.source("a"+t,start,now);what=ev;}
                    else what=ev+" ignored";}
                else what="cb 0x"+Integer.toHexString(code);
            }catch(RuntimeException e){what="cb 0x"+Integer.toHexString(code)+" "+e;}
            android.util.Log.i("VehicleDash","Engine sound: micom "+what+" gain "+Math.round(yield.gain(now)*100)+"%");
            return true;
        }
    }
    static void endMicom(Object router,int type){
        try{boolean ok=((com.hkmc.media.MicomAudioManager)router).endAudio(type);android.util.Log.i("VehicleDash","Engine sound: micom endAudio(0x"+Integer.toHexString(type)+") = "+ok);}
        catch(LinkageError|RuntimeException e){android.util.Log.w("VehicleDash","Engine sound: endAudio failed",e);}
    }
}
