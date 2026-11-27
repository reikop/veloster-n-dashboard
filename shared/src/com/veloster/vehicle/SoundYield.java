package com.veloster.vehicle;

/**
 * When the engine sound steps aside (or plays on quietly) for the head unit's own voices: navigation prompts, TTS,
 * calls, ringing, voice recognition, CarPlay. Ported from jihoon-seo/de22ev-ves (0561cae, MIT, Copyright (c) 2026
 * Jihoon Seo; see NOTICE.md): AudioPolicy (this class), SharedChannel (Channel), the VesCore glue and the listener's
 * code table, renumbered for our firmware (V141: listener codes one lower than the DE22EV's, our own stream types).
 * Pure Java: EngineSound feeds it the MICOM listener's events and acts on tick().
 *
 * <ul>
 * <li>The very first tick() returns ACT_CLOSE once (cleanup of a path left open by a previous process).</li>
 * <li>ACT_OPEN only when wantSound and not yielding; never two OPENs without a CLOSE in between.</li>
 * <li>Yield: gain() fades to 0 within FADE_MS, then the next tick returns ACT_CLOSE. Sources are tracked separately.
 *     After the last end the gain stays 0 for HOLDOFF_MS, then ramps back over RAMP_MS. A yield without an end
 *     expires EXPIRY_MS after its start.</li>
 * <li>Duck (duck mode and a source that only speaks, see duckable()): the gain drops to DUCK_GAIN, the path stays
 *     open; a non-duck source (a call ...) turns it into a full yield.</li>
 * <li>Someone else's AudioEnd of our own type while open: silent, then ACT_REGRAB (a fresh startAudio).</li>
 * <li>ACT_CLOSE after SILENCE_CLOSE_MS of silence (no rpm, or gain ~0).</li>
 * </ul>
 * Thread-safe: events arrive on binder threads, tick() on the audio thread.
 */
final class SoundYield {
    static final int ACT_NONE=0,ACT_OPEN=1,ACT_CLOSE=2,ACT_REGRAB=3;
    static final int OURS=0,FOREIGN_START=1,FOREIGN_END=2,RESTORE=3;
    static final long SHARED_EXPIRY_MS=5000,FADE_MS=120,HOLDOFF_MS=500,RAMP_MS=200,EXPIRY_MS=60000,SILENCE_CLOSE_MS=2000;
    static final long REGRAB_WAIT_MS=100,REGRAB_RAMP_MS=50,REGRAB_GUARD_MS=1500,RETRY_MS=1000,RETRY_MAX_MS=30000;
    static final double SILENT_GAIN=1e-4;
    /** 안내·통화 양보 modes (SettingsLayout.SOUND_YIELDS): off = the path opened once as before; duck = prompts at
     *  DUCK_GAIN; stop = silent for prompts too. Calls, ringing and voice recognition always silence it. */
    static final int OFF=0,DUCK=1,STOP=2;
    static final double DUCK_GAIN=0.3;

    /** our firmware's MICOM stream types (MicomAudioManagerConstant$StreamType, V141) */
    static final int NAVI=0x3,VUI=0x6,TTS=0x7,ANNOUNCE=0x8,RING=0x9,GUIDE=0xa,AANAVI=0x12,BTSIRI=0x14,VOICEREC=0x15,
        MIRRORLINK_NAVI=0x19,PREVIEWAUDIOTTS=0x1d,UVOBLUERING=0x23,CARLIFE_NAVI=0x24,TTS_EFFECT_SOUND=0x3d,
        NAVI_SOUNDEFFECT=0x40,SYSTEM_ALARM=0x41;
    /** IMicomAudioStateChangeListener: AudioStart(int type) / AudioEnd(int type) */
    static final int CB_AUDIO_START=0x20,CB_AUDIO_END=0x21;

    /** The listener's start / end calls as yield sources (V141 transaction codes), null for anything else. */
    static String source(int code){
        switch(code){
            case 0xf:case 0x10:return "navi";
            case 0x18:case 0x19:return "tts";
            case 0x1a:case 0x1b:return "vui";
            case 0x13:case 0x15:return "btvoice";
            case 0x26:case 0x27:return "ring";
            case 0x42:case 0x43:return "vr";
            case 0x23:case 0x24:return "announce";
            case 0x1e:case 0x1f:return "reartalk";
            case 0x30:case 0x31:return "uvo";
            case 0x38:case 0x39:return "dipo_alt";
            case 0x3a:case 0x3b:return "dipo_call";
            case 0x3c:case 0x3d:return "dipo_ring";
            case 0x3e:case 0x3f:return "dipo_siri";
            case 0x40:case 0x41:return "btsiri";
            default:return null;
        }
    }
    static boolean isStart(int code){return code==0xf||code==0x18||code==0x1a||code==0x13||code==0x26||code==0x42||code==0x23||code==0x1e||code==0x30||code==0x38||code==0x3a||code==0x3c||code==0x3e||code==0x40;}

    /** stream types that speak to the driver: the engine sound steps aside (or ducks) while they play */
    static boolean yieldsFor(int t){
        switch(t){
            case NAVI:case TTS:case RING:case GUIDE:case AANAVI:case VOICEREC:case VUI:case UVOBLUERING:case BTSIRI:
            case CARLIFE_NAVI:case MIRRORLINK_NAVI:case ANNOUNCE:case NAVI_SOUNDEFFECT:case TTS_EFFECT_SOUND:
            case SYSTEM_ALARM:case PREVIEWAUDIOTTS:return true;
            default:return false;
        }
    }

    /** sources that only speak (prompts, alerts, TTS) and can have the engine sound under them */
    static boolean duckable(String s){
        if(s==null)return false;
        if(s.equals("navi")||s.equals("tts")||s.equals("shared")||s.equals("dipo_alt"))return true;
        if(!s.startsWith("a"))return false;
        try{int t=Integer.parseInt(s.substring(1));
            return t==NAVI||t==TTS||t==GUIDE||t==AANAVI||t==MIRRORLINK_NAVI||t==PREVIEWAUDIOTTS||t==CARLIFE_NAVI||t==TTS_EFFECT_SOUND||t==NAVI_SOUNDEFFECT||t==SYSTEM_ALARM;}
        catch(NumberFormatException e){return false;}
    }

    /** 속도 따라 키우기 (SettingsLayout.SOUND_BOOSTS): 0 / 3 / 6 / 9 dB at level 0..3, rising linearly from 40 to 110 km/h. */
    static double speedBoost(int level,double kph){
        double maxDb=Math.max(0,Math.min(3,level))*3;
        if(maxDb==0||!(kph>40))return 1;   // also NaN
        return Math.pow(10,maxDb*Math.min(1,(kph-40)/70.0)/20);
    }

    private final int mode;
    SoundYield(int mode){this.mode=mode;}

    // ---- the glue (VesCore)

    /** a listener start / end of another source */
    void source(String src,boolean start,long now){
        if(start){onYieldStart(src,now,mode==DUCK&&duckable(src));return;}
        onYieldEnd(src,now);
        if(!"shared".equals(src))synchronized(channel){channel.yieldEnded(now);}
    }
    /** AudioStart / AudioEnd of our own stream type: ours, someone else's (the navigation's chime) or MICOM restoring ours */
    int ownType(boolean start,long now){
        int who;boolean open=isOpen();
        synchronized(channel){who=start?channel.onStart(now):channel.onEnd(now,open);}
        if(who==FOREIGN_START)onYieldStart("shared",now,mode==DUCK);
        else if(who==FOREIGN_END){onYieldEnd("shared",now);channelEnded(now);}
        else if(who==RESTORE)channelRestored(now);
        return who;
    }
    /** before startAudio / endAudio: its AudioStart / AudioEnd may arrive before the call returns */
    void ownStartCalled(long now){synchronized(channel){channel.ownStartCalled(now);}}
    void ownEndCalled(long now){synchronized(channel){channel.ownEndCalled(now);}}
    /** true when endAudio now would cut someone else's sound, or the channel is already closed */
    boolean skipEnd(long now){synchronized(channel){return channel.skipEnd(now);}}

    // ---- the policy (AudioPolicy)

    private static final int MAX_SOURCES=16;
    private final String[] srcName=new String[MAX_SOURCES];
    private final long[] srcStart=new long[MAX_SOURCES];
    private final boolean[] srcActive=new boolean[MAX_SOURCES],srcDuck=new boolean[MAX_SOURCES];
    private final Channel channel=new Channel();
    private boolean lastEpisodeDuck,lostPending,firstDone,open,episode,hadEnd,grace;
    private long lostAt=Long.MIN_VALUE/2,episodeStart,lastEnd,silentSince=Long.MIN_VALUE,endedAt,
        regrabAt=Long.MIN_VALUE/2,rampAt=Long.MIN_VALUE/2,retryAt=Long.MIN_VALUE;
    private double endGain,episodeFrom=1;
    private int refusals;

    synchronized void onYieldStart(String source,long now,boolean duck){
        String s=norm(source);
        expire(now);
        if(open&&!"shared".equals(s)&&now-regrabAt<REGRAB_GUARD_MS){lostPending=true;lostAt=now;}   // a voice right after our regrab: step aside fully
        double from=gainAt(now);
        boolean wasDuck=episode&&allDuck();
        int i=find(s);
        if(i<0){i=freeSlot();srcName[i]=s;}
        srcStart[i]=now;srcActive[i]=true;srcDuck[i]=duck;
        if(!episode){episode=true;episodeStart=now;episodeFrom=from;}
        else if(wasDuck&&!duck){episodeStart=now;episodeFrom=from;}   // a call during a ducked prompt: down to silence
    }

    synchronized void onYieldEnd(String source,long now){
        String s=norm(source);
        expire(now);
        int i=find(s);
        if(i<0||!srcActive[i])return;
        boolean duck=allDuck();double g=gainAt(now);
        srcActive[i]=false;
        if(!anyActive())endEpisode(now,duck,g);
        else if(!duck&&allDuck()){episodeStart=now;episodeFrom=g;}   // the call ended, the prompt goes on: back up to the ducked level
    }

    /** the channel was closed under us: close now, open again when allowed */
    synchronized void channelLost(long now){lostPending=true;lostAt=now;}

    synchronized void channelEnded(long now){
        if(!open||now-regrabAt<REGRAB_GUARD_MS){channelLost(now);return;}
        if(grace)return;
        grace=true;endedAt=now;
    }

    synchronized void channelRestored(long now){if(!grace)return;grace=false;rampAt=now;}

    /** the answer to ACT_OPEN's startAudio. Refused: not open, no CLOSE, the next OPEN waits 1, 2, 4 .. 30 s */
    synchronized void openResult(boolean ok,long now){
        if(ok){refusals=0;return;}
        open=false;refused(now);
    }
    /** the answer to ACT_REGRAB's startAudio */
    synchronized void regrabResult(boolean ok,long now){
        if(ok){refusals=0;return;}
        open=false;regrabAt=Long.MIN_VALUE/2;refused(now);
    }
    private void refused(long now){refusals++;retryAt=now+Math.min(RETRY_MAX_MS,RETRY_MS<<Math.min(refusals-1,5));}

    synchronized boolean isOpen(){return open;}
    synchronized boolean ducking(long now){expire(now);return episode&&allDuck();}
    synchronized boolean yielding(long now){expire(now);return yieldingNow(now);}
    synchronized String activeSources(long now){
        expire(now);StringBuilder b=new StringBuilder();
        for(int i=0;i<MAX_SOURCES;i++)if(srcActive[i]){if(b.length()>0)b.append(',');b.append(srcName[i]);}
        return b.toString();
    }

    /** multiplier for the output: 1 normally, DUCK_GAIN under a prompt, 0 while another source owns the channel */
    synchronized double gain(long now){
        expire(now);
        if(grace)return 0;
        double f=(now-rampAt)/(double)REGRAB_RAMP_MS;
        return gainAt(now)*(f>=1?1:f<=0?0:f);
    }

    synchronized int tick(long now,boolean wantSound,double currentGain){
        if(!firstDone){firstDone=true;open=false;return ACT_CLOSE;}
        expire(now);
        if(grace){
            if(episode){grace=false;lostAt=endedAt;return close();}   // a voice after the end: step aside
            if(now-endedAt<REGRAB_WAIT_MS)return ACT_NONE;
            grace=false;
            if(!wantSound){lostAt=endedAt;return close();}
            regrabAt=rampAt=now;
            return ACT_REGRAB;
        }
        if(lostPending){lostPending=false;if(open)return close();}
        boolean silent=!wantSound||!(currentGain>SILENT_GAIN);
        if(!silent)silentSince=Long.MIN_VALUE;else if(silentSince==Long.MIN_VALUE)silentSince=now;
        if(yieldingNow(now)){if(open&&gainAt(now)<=0)return close();return ACT_NONE;}
        if(open){if(silentSince!=Long.MIN_VALUE&&now-silentSince>=SILENCE_CLOSE_MS)return close();return ACT_NONE;}
        if(now-lostAt<HOLDOFF_MS)return ACT_NONE;
        if(refusals>0&&now<retryAt)return ACT_NONE;
        if(wantSound){open=true;silentSince=currentGain>SILENT_GAIN?Long.MIN_VALUE:now;return ACT_OPEN;}
        return ACT_NONE;
    }

    private boolean yieldingNow(long now){return episode||hadEnd&&!lastEpisodeDuck&&now-lastEnd<HOLDOFF_MS;}
    private int close(){open=false;grace=false;return ACT_CLOSE;}
    private boolean allDuck(){
        boolean any=false;
        for(int i=0;i<MAX_SOURCES;i++){if(!srcActive[i])continue;any=true;if(!srcDuck[i])return false;}
        return any;
    }
    private double gainAt(long now){
        if(episode){
            boolean duck=allDuck();double floor=duck?DUCK_GAIN:0,g;
            if(duck&&episodeFrom<floor)g=Math.min(floor,episodeFrom+(now-episodeStart)/(double)RAMP_MS*(floor-episodeFrom));   // up from a call's silence
            else g=Math.max(floor,episodeFrom-(now-episodeStart)/(double)FADE_MS);
            return g<=0?0:g>1?1:g;
        }
        if(!hadEnd)return 1;
        if(lastEpisodeDuck){double f=(now-lastEnd)/(double)RAMP_MS;return f>=1?1:f<=0?endGain:endGain+(1-endGain)*f;}
        long t=now-lastEnd-HOLDOFF_MS;
        return t<0?0:t>=RAMP_MS?1:t/(double)RAMP_MS;
    }
    private void endEpisode(long t,boolean duck,double g){episode=false;hadEnd=true;lastEnd=t;lastEpisodeDuck=duck;endGain=g;}
    private void expire(long now){
        if(!episode)return;
        long latest=Long.MIN_VALUE;boolean any=false,duck=allDuck();double g0=gainAt(now);
        for(int i=0;i<MAX_SOURCES;i++){
            if(!srcActive[i])continue;
            long exp=srcStart[i]+("shared".equals(srcName[i])?SHARED_EXPIRY_MS:EXPIRY_MS);
            if(now>=exp){srcActive[i]=false;if(exp>latest)latest=exp;}else any=true;
        }
        if(!any)endEpisode(latest==Long.MIN_VALUE?now:latest,duck,g0);
    }
    private boolean anyActive(){for(int i=0;i<MAX_SOURCES;i++)if(srcActive[i])return true;return false;}
    private int find(String s){for(int i=0;i<MAX_SOURCES;i++)if(s.equals(srcName[i]))return i;return -1;}
    private int freeSlot(){
        for(int i=0;i<MAX_SOURCES;i++)if(srcName[i]==null)return i;
        for(int i=0;i<MAX_SOURCES;i++)if(!srcActive[i])return i;
        int o=0;for(int i=1;i<MAX_SOURCES;i++)if(srcStart[i]<srcStart[o])o=i;   // all active: reuse the oldest
        return o;
    }
    private static String norm(String s){
        if(s==null)return "foreign";
        String t=s.trim().toLowerCase(java.util.Locale.US);
        return t.length()==0?"foreign":t;
    }

    /**
     * Tells our own MICOM AudioStart / AudioEnd from another user's of the same stream type (SharedChannel): MICOM
     * reports them for every startAudio / endAudio, and endAudio(type) ends whatever sound holds the channel. An
     * event within ACK_MS of our own call is ours. Not thread-safe: SoundYield synchronizes on it.
     */
    static final class Channel {
        static final long ACK_MS=1000,RESTORE_MS=1000,FOREIGN_MAX_MS=30000;
        private long startCallAt,endCallAt,foreignSince,lastYieldEnd=Long.MIN_VALUE/2,lostAt=Long.MIN_VALUE/2;
        private boolean startPending,endPending,foreignHeld,lost;
        void yieldEnded(long now){lastYieldEnd=now;}
        void ownStartCalled(long now){startCallAt=now;startPending=true;lost=false;}
        void ownEndCalled(long now){endCallAt=now;endPending=true;}
        int onStart(long now){
            if(startPending&&now-startCallAt<=ACK_MS&&now>=startCallAt){startPending=false;return OURS;}
            startPending=false;
            if(now-lastYieldEnd<=RESTORE_MS&&now>=lastYieldEnd||now-lostAt<=RESTORE_MS&&now>=lostAt){lost=false;foreignHeld=false;return RESTORE;}
            foreignHeld=true;foreignSince=now;
            return FOREIGN_START;
        }
        int onEnd(long now,boolean pathOpen){
            if(endPending&&now-endCallAt<=ACK_MS&&now>=endCallAt){endPending=false;return OURS;}
            endPending=false;foreignHeld=false;
            if(pathOpen){lost=true;lostAt=now;}
            return FOREIGN_END;
        }
        boolean skipEnd(long now){
            if(foreignHeld&&now-foreignSince>=FOREIGN_MAX_MS)foreignHeld=false;
            return foreignHeld||lost;
        }
    }
}
