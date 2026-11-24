/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.*;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.AudioWireFormat;
import java.io.*;
import java.util.*;

/** Binds real output readiness and per-track gain to one active mode capability.
 * Attach once before accepting connections. Presentation callbacks must only enqueue work;
 * they must not wait for UI/network or call back into a verified session. No method joins a
 * worker or takes a session/ModeControl lock while holding this adapter's monitor.
 */
public final class AudioPresentation implements AudioStreamFactory.SessionOutputs,ModePresentation.AudioGate,ModeControl.Listener,Closeable {
    private static final int MAX_STREAMS=8;
    private final AudioSink.Factory factory;
    private final VolumeStore volume;
    private final IdentityHashMap<AudioSink,Entry> streams=new IdentityHashMap<AudioSink,Entry>();
    private AudioReadiness presentation;private ModeControl active;private PairVerify.VerifiedSession session;
    private boolean closed,publishedReady;private int readyCount;private float gain;
    private ModeState observed=ModeState.unknown();
    private boolean mediaEnabled=true;private VolumePolicy.Context volumeContext;private long volumeRevision;
    private static final class Entry {
        final PairVerify.VerifiedSession session;final ModeControl modes;final AudioDescription description;
        boolean ready;
        Entry(PairVerify.VerifiedSession s,ModeControl m,AudioDescription d){session=s;modes=m;description=d;}
        boolean matches(PairVerify.VerifiedSession s,ModeControl m,AudioDescription d){return session==s&&modes==m&&description==d;}
    }
    public AudioPresentation(AudioSink.Factory factory){this(factory,VolumeStore.memory());}
    public AudioPresentation(AudioSink.Factory factory,VolumeStore volume){if(factory==null||volume==null)throw new NullPointerException();this.factory=factory;this.volume=volume;}
    public synchronized void attach(AudioReadiness presentation){
        if(presentation==null)throw new NullPointerException();
        if(closed||this.presentation!=null)throw new IllegalStateException("Audio presentation already attached/disposed");
        this.presentation=presentation;
    }
    public boolean supports(AudioDescription d){
        return d!=null&&(d.audioType.equals("default")||d.audioType.equals("compatibility")||d.audioType.equals("media")||d.audioType.equals("alert")||(d.audioType.equals("speechRecognition")||d.audioType.equals("telephony"))&&d.type==100&&(d.format.bits==0x10L||d.format.bits==0x4L||d.format.bits==0x04000000L));
    }
    public AudioSink.Output open(AudioWireFormat format)throws IOException{return factory.open(format);}
    public AudioSink.Output open(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription description,AudioWireFormat format)throws IOException{
        synchronized(this){if(!bound(owner,modes,description))throw new IOException("Audio output stream retired");}
        AudioSink.Output output=factory instanceof AudioSink.SessionFactory?
            ((AudioSink.SessionFactory)factory).open(owner,modes,description,format):factory.open(format);
        synchronized(this){if(bound(owner,modes,description))return output;}
        output.close();throw new IOException("Audio output owner changed during open");
    }
    private boolean bound(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription description){
        if(closed||active!=modes||session!=owner)return false;
        for(Map.Entry<AudioSink,Entry> item:streams.entrySet())
            if(!item.getKey().isClosing()&&item.getValue().matches(owner,modes,description))return true;
        return false;
    }
    public synchronized void opened(ModeControl control)throws IOException{
        if(control==null)throw new NullPointerException();
        if(closed||presentation==null)throw new IOException("Audio presentation unavailable");
        if(active==control)throw new IOException("Mode capability already opened");
        retire();if(!volume.claim(control))throw new IOException("Volume preferences disposed");active=control;
        try{presentation.opened(control);}catch(IOException e){volume.release(control);active=null;throw e;}
    }
    public synchronized void changed(ModeControl control,ModeState state)throws IOException{
        if(!closed&&active==control&&control!=null){observed=state;refreshGains();presentation.changed(control,state);if(presentation instanceof AudioReadiness.Roles)publishRoles(control);else publishReady(control,anyPermittedReady());}
    }
    public synchronized boolean hostUiRequested(ModeControl control)throws IOException{
        return !closed&&active==control&&control!=null&&presentation.hostUiRequested(control);
    }
    public synchronized void closed(ModeControl control){
        if(control==null||active!=control)return;
        retire();active=null;presentation.closed(control);
    }
    public synchronized void opened(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription d,AudioSink sink)throws IOException{
        if(closed||active==null||active!=modes||owner==null||sink==null||!supports(d))throw new IOException("Inactive audio owner");
        if(session!=null&&session!=owner)throw new IOException("Foreign audio session");
        // A TEARDOWN reply commits logical cancellation before codec release finishes.
        // Reap only cancelled identities; a late ready/stopped callback for them is
        // then harmless and cannot remove or mute the replacement entry.
        boolean lostReady=false;
        for(Iterator<Map.Entry<AudioSink,Entry>> i=streams.entrySet().iterator();i.hasNext();){
            Map.Entry<AudioSink,Entry> old=i.next();if(old.getKey().isClosing()){
                if(old.getValue().ready){readyCount--;lostReady=true;}i.remove();
            }
        }
        if(lostReady){if(readyCount==0)gain=0;if(presentation instanceof AudioReadiness.Roles)publishRoles(modes);else publishReady(modes,anyPermittedReady());}
        if(streams.size()>=MAX_STREAMS||streams.containsKey(sink))throw new IOException("Audio output limit/duplicate");
        for(Entry e:streams.values())if(e.description.key.equals(d.key)||e.description.connectionId.equals(d.connectionId))
            throw new IOException("Audio output identity in use");
        sink.setGain(0);session=owner;streams.put(sink,new Entry(owner,modes,d));
    }
    public synchronized void ready(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription d,AudioSink sink){
        Entry e=streams.get(sink);
        if(closed||active!=modes||e==null||!e.matches(owner,modes,d)||e.ready||sink.isClosing())return;
        e.ready=true;readyCount++;applyGain(sink,e);
        if(presentation instanceof AudioReadiness.Roles)publishRoles(modes);else publishReady(modes,anyPermittedReady());
        selectVolumeContext();
    }
    public synchronized void stopped(PairVerify.VerifiedSession owner,ModeControl modes,AudioDescription d,AudioSink sink,IOException failure){
        Entry e=streams.get(sink);if(e==null||!e.matches(owner,modes,d))return;
        streams.remove(sink);sink.setGain(0);
        if(e.ready){if(--readyCount==0)gain=0;if(presentation instanceof AudioReadiness.Roles)publishRoles(modes);else publishReady(modes,anyPermittedReady());}
        selectVolumeContext();
        // Keep the session identity even while empty: a mode capability cannot change owner.
    }
    private void publishReady(ModeControl modes,boolean value){
        if(publishedReady==value)return;
        try{presentation.audioReady(modes,value);publishedReady=value;}catch(IOException e){throw new IllegalStateException("Audio presentation queue failed",e);}
    }
    private void publishRoles(ModeControl modes){
        boolean main=false,alternate=false;for(Entry e:streams.values())if(e.ready&&mediaPermitted(e)){if(e.description.type==101)alternate=true;else main=true;}
        try{((AudioReadiness.Roles)presentation).audioReady(modes,main,alternate);}catch(IOException e){throw new IllegalStateException("Audio role readiness queue failed",e);}
    }
    public synchronized void gain(ModeControl modes,float value){
        if(Float.isNaN(value)||value<0||value>1)throw new IllegalArgumentException("Audio gain");
        if(closed||active==null||active!=modes)return;
        // Readiness is necessary even if a delayed UI grant arrives after the last stop.
        gain=readyCount==0?0:value;
        refreshGains();
    }
    private boolean mediaPermitted(Entry entry){return mediaEnabled||VolumePolicy.context(entry.description,observed)!=VolumePolicy.Context.MEDIA;}
    /** Existing ownership/focus envelope, before user attenuation. */
    private float permittedGain(Entry entry){
        if(!entry.ready)return 0;
        if(entry.description.type==101)return gain;
        if(observed.audio!=ModeState.PHONE)return 0;
        boolean call=observed.phoneCall==ModeState.PHONE;
        if(entry.description.audioType.equals("telephony"))return call&&observed.audio==ModeState.PHONE?gain:0;
        if(call&&(entry.description.audioType.equals("media")||entry.description.audioType.equals("compatibility")||entry.description.audioType.equals("speechRecognition")))return 0;
        return gain;
    }
    private void applyGain(AudioSink sink,Entry entry){
        float permitted=permittedGain(entry);VolumePolicy.Context context=VolumePolicy.context(entry.description,observed);
        boolean mediaOff=!mediaPermitted(entry);
        float level=mediaOff?0:permitted*volume.snapshot().level(context)/100f;
        // User mute attenuates an authorized stream; it must not hold its RTP
        // timeline or fill a bounded input queue forever. An intentionally
        // disabled MEDIA source likewise consumes silently without focus.
        boolean silentMedia=mediaOff&&entry.ready&&observed.audio==ModeState.PHONE;
        sink.setPlayback(level,permitted>0||silentMedia);
    }
    private void refreshGains(){for(Map.Entry<AudioSink,Entry> e:streams.entrySet())applyGain(e.getKey(),e.getValue());selectVolumeContext();}
    /** Main-source user choice. Calls/voice/bell/alternate retain their own gates. */
    public synchronized void setMediaEnabled(boolean enabled){
        if(closed||mediaEnabled==enabled)return;mediaEnabled=enabled;refreshGains();
        if(active!=null){if(presentation instanceof AudioReadiness.Roles)publishRoles(active);else publishReady(active,anyPermittedReady());}
    }
    private boolean anyPermittedReady(){for(Entry entry:streams.values())if(entry.ready&&mediaPermitted(entry))return true;return false;}
    /** Activity is a local UI choice, not an Apple-defined timeout or a new wire field. */
    private void selectVolumeContext(){
        long now=System.nanoTime();VolumePolicy.Context selected=null;
        for(Map.Entry<AudioSink,Entry> pair:streams.entrySet()){
            Entry entry=pair.getValue();if(!entry.ready||pair.getKey().isClosing()||!pair.getKey().recorded()||!mediaPermitted(entry)||permittedGain(entry)<=0)continue;
            long[] stats=pair.getKey().stats();
            long packetTime=pair.getKey().lastPacketNanos();AudioHead head=pair.getKey().headObservation();
            long packetAge=now-packetTime;boolean recentPacket=packetTime!=0&&packetAge>=0&&packetAge<300000000L;
            long headAge=head==null?Long.MAX_VALUE:now-head.monotonicNanos;boolean recentHead=head!=null&&headAge>=0&&headAge<300000000L;
            VolumePolicy.Context candidate=VolumePolicy.context(entry.description,observed);
            boolean interaction=candidate==VolumePolicy.Context.CALL&&observed.phoneCall==ModeState.PHONE||candidate==VolumePolicy.Context.SIRI&&observed.speechOwner==ModeState.PHONE&&observed.speechMode>0;
            boolean current=(packetTime!=0||head!=null)&&stats[3]>stats[4]||recentPacket||recentHead||interaction;
            if(current&&(selected==null||VolumePolicy.priority(candidate)>VolumePolicy.priority(selected)))selected=candidate;
        }
        if(selected!=volumeContext){volumeContext=selected;if(volumeRevision!=Long.MAX_VALUE)volumeRevision++;}
    }
    public synchronized VolumePolicy.Snapshot volumeSnapshot(){selectVolumeContext();return new VolumePolicy.Snapshot(closed||!volume.owns(active)?null:active,volumeContext,volumeRevision,volume.snapshot());}
    /** Main-thread safe. The ticket rejects stale owner and context A-to-B-to-A changes. */
    public synchronized boolean setVolume(VolumePolicy.Snapshot expected,int percent){
        if(percent<0||percent>100)throw new IllegalArgumentException("Volume percent");selectVolumeContext();
        if(closed||volumeRevision==Long.MAX_VALUE||expected==null||!expected.current(active,volumeContext,volumeRevision))return false;
        if(!volume.set(active,volumeContext,percent))return false;refreshGains();return true;
    }
    private void retire(){
        volume.release(active);
        for(AudioSink sink:streams.keySet()){sink.setGain(0);sink.close();}
        streams.clear();readyCount=0;publishedReady=false;gain=0;session=null;observed=ModeState.unknown();volumeContext=null;if(volumeRevision!=Long.MAX_VALUE)volumeRevision++;
    }
    /** Cancel output workers; caller still owns ModePresentation.close() on Android main. */
    public synchronized void close(){
        if(closed)return;closed=true;ModeControl old=active;retire();active=null;
        if(old!=null)presentation.closed(old);
    }
}
