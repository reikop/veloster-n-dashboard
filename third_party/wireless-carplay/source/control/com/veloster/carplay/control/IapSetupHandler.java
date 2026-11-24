/* SPDX-License-Identifier: GPL-3.0-only. SETUP profile based on DiPlay; see NOTICE.md. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.input.TouchHid;
import com.veloster.carplay.tunnel.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Authenticated base SETUP -> event/timing -> type130 -> real iAP2 link.
 * Delegate owns actual receiver /info and remaining commands; media/low-power features are not fabricated.
 * Used by one ControlListener; callbacks are serial and must return promptly. */
public final class IapSetupHandler implements ControlConnection.SessionHandler {
    public interface RuntimeSink {
        /** Called after SETUP flush. Transfer the live transport to the runtime controller without blocking here. */
        void opened(PairVerify.VerifiedSession session,TunnelIap2Session runtime,TimingClock timing)throws IOException;
        /** Nonblocking notification; the tunnel has been cancelled. Do not join the control worker here. */
        void closed(PairVerify.VerifiedSession session);
    }
    /** Exact-generation retirement, separate from whole verified-session close. */
    public interface RetirableRuntimeSink extends RuntimeSink {
        boolean retire(PairVerify.VerifiedSession session,TunnelIap2Session runtime)throws IOException;
        boolean retired(PairVerify.VerifiedSession session,TunnelIap2Session runtime)throws InterruptedException;
    }
    public interface ScreenSinkFactory {
        /** Actual media capabilities, not a default success. Must return promptly. */
        boolean supports(int type);
        /** Enqueueing sink with bounded work; closed() releases this exact sink's resources.
         * Called on the control worker. Do not configure a blocking codec here. */
        ScreenDataStream.Sink open(PairVerify.VerifiedSession session,int type,Map<String,Object> stream,TimingClock timing,EncryptedEvents events)throws IOException;
    }
    public interface AudioSinkFactory {
        /** Must reflect an actual output path. Unsupported voice/microphone profiles return false. */
        boolean supports(AudioDescription description);
        AudioDataStream.Sink open(PairVerify.VerifiedSession session,AudioDescription description,ModeControl modes)throws IOException;
    }
    public interface DeclaredAudioSinkFactory extends AudioSinkFactory {
        /** The same immutable declaration supplied to ReceiverMetadata, or null for legacy fixtures. */
        AudioCapabilities capabilities();
    }
    public interface MicrophoneFactory {
        AudioCapabilities capabilities();
        boolean supports(AudioDescription description);
        AudioInputStream open(PairVerify.VerifiedSession session,AudioDescription description)throws IOException;
    }
    public interface ModeAwareMicrophoneFactory extends MicrophoneFactory {
        AudioInputStream open(PairVerify.VerifiedSession session,AudioDescription description,ModeControl modes)throws IOException;
    }
    public interface ClockedAudioSinkFactory extends AudioSinkFactory {
        AudioDataStream.Sink open(PairVerify.VerifiedSession session,AudioDescription description,ModeControl modes,TimingClock timing)throws IOException;
    }
    public interface InputSinkFactory {
        /** Exact immutable declaration also supplied to ReceiverMetadata. */
        TouchHid declaration();
        /** Reserve a bounded input worker for this session. Do not wait for main,
         * event connection or an ACK here. A failed construction owns its cleanup. */
        ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,
            EncryptedEvents events,ReceiverMetadata metadata)throws IOException;
    }
    /** Session controls independent of the declared touch HID and media formats. */
    public interface SessionControlFactory {
        ReceiverWorkers.Worker open(PairVerify.VerifiedSession session,ModeControl modes,
            EncryptedEvents events,ReceiverMetadata metadata)throws IOException;
    }
    public interface FeedbackAudioSinkFactory extends AudioSinkFactory {
        /** Nonblocking snapshot for this exact stream; never reads a codec on the control worker. */
        Map<String,Object> feedback(PairVerify.VerifiedSession session,AudioDescription description,TimingClock timing)throws IOException;
    }
    private final ReceiverWorkers workers=new ReceiverWorkers(64);
    private final ScreenSinkFactory screenFactory;
    private final AudioSinkFactory audioFactory;
    private final MicrophoneFactory microphoneFactory;
    private final Map<String,AudioInputStream> microphones=new LinkedHashMap<String,AudioInputStream>();
    private final InputSinkFactory inputFactory;
    private ReceiverWorkers.Worker input;
    private final SessionControlFactory sessionControlFactory;private ReceiverWorkers.Worker sessionControl;
    private final ReceiverMetadata metadata;
    private final ModeControl.Listener modeListener;
    private ModeControl modes;
    private final Map<Integer,ScreenDataStream> screens=new LinkedHashMap<Integer,ScreenDataStream>();
    private final Set<Integer> retiringScreens=new HashSet<Integer>();
    private final List<ScreenDataStream> pendingScreens=new ArrayList<ScreenDataStream>();
    private final Map<String,AudioDataStream> audio=new LinkedHashMap<String,AudioDataStream>();
    private final Map<String,AudioDescription> audioDescriptions=new LinkedHashMap<String,AudioDescription>();
    private final List<AudioDataStream> pendingAudio=new ArrayList<AudioDataStream>();
    private boolean pendingTunnel;
    private boolean retiringTunnel;
    private TunnelIap2Session runtime;
    private String tunnelConnectionId;
    private final Set<String> tunnelConnectionIds=new HashSet<String>();
    private final ControlConnection.Handler delegate;
    private final RuntimeSink sink;
    private PairVerify.VerifiedSession owner;
    private EncryptedEvents events;private TimingClock timing;private IapDataTunnel tunnel;
    private ControlMessage.Request awaiting,pendingRecord,pendingFlushRequest;private AudioDataStream.Flush pendingFlush;
    private ControlMessage.Request pendingTeardown;private PairVerify.VerifiedSession teardownOwner;
    private boolean baseSent,recorded;
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink){
        this(delegate,sink,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory){
        this(delegate,sink,screenFactory,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata){
        this(delegate,sink,screenFactory,metadata,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata,ModeControl.Listener modeListener){
        this(delegate,sink,screenFactory,metadata,modeListener,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata,ModeControl.Listener modeListener,AudioSinkFactory audioFactory){
        this(delegate,sink,screenFactory,metadata,modeListener,audioFactory,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata,ModeControl.Listener modeListener,AudioSinkFactory audioFactory,InputSinkFactory inputFactory){
        this(delegate,sink,screenFactory,metadata,modeListener,audioFactory,inputFactory,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata,ModeControl.Listener modeListener,AudioSinkFactory audioFactory,InputSinkFactory inputFactory,MicrophoneFactory microphoneFactory){
        this(delegate,sink,screenFactory,metadata,modeListener,audioFactory,inputFactory,microphoneFactory,null);
    }
    public IapSetupHandler(ControlConnection.Handler delegate,RuntimeSink sink,ScreenSinkFactory screenFactory,ReceiverMetadata metadata,ModeControl.Listener modeListener,AudioSinkFactory audioFactory,InputSinkFactory inputFactory,MicrophoneFactory microphoneFactory,SessionControlFactory sessionControlFactory){
        if(delegate==null||sink==null)throw new NullPointerException();
        if(inputFactory!=null&&(metadata==null||metadata.touchInput()==null||modeListener==null||inputFactory.declaration()!=metadata.touchInput())
            ||metadata!=null&&metadata.touchInput()!=null&&inputFactory==null)
            throw new IllegalArgumentException("Receiver input declaration/factory mismatch");
        if(metadata!=null&&(screenFactory==null||!screenFactory.supports(110)||screenFactory.supports(111)!=metadata.supports(111)))
            throw new IllegalArgumentException("Receiver metadata/media capability mismatch");
        if(metadata!=null&&metadata.hasInitialModes()&&modeListener==null)throw new IllegalArgumentException("Initial modes need a live handler");
        if(metadata!=null&&microphoneFactory!=null&&metadata.audioCapabilities()==null)throw new IllegalArgumentException("Microphone needs matching audio declaration");
        if(metadata!=null&&(metadata.audioCapabilities()!=null||audioFactory!=null)){
            AudioCapabilities declared=metadata.audioCapabilities();
            if(declared==null||modeListener==null||!(audioFactory instanceof DeclaredAudioSinkFactory)||
                ((DeclaredAudioSinkFactory)audioFactory).capabilities()!=declared)
                throw new IllegalArgumentException("Receiver audio declaration/factory mismatch");
            try{declared.validateFactory(audioFactory);}catch(IOException e){throw new IllegalArgumentException("Receiver audio backend mismatch",e);}
            if(declared.hasInput()!=(microphoneFactory!=null))throw new IllegalArgumentException("Receiver microphone declaration/factory mismatch");
            try{declared.validateMicrophone(microphoneFactory);}catch(IOException e){throw new IllegalArgumentException("Receiver microphone backend mismatch",e);}
        }
        this.delegate=delegate;this.sink=sink;this.screenFactory=screenFactory;this.metadata=metadata;this.modeListener=modeListener;this.audioFactory=audioFactory;this.inputFactory=inputFactory;this.microphoneFactory=microphoneFactory;
        this.sessionControlFactory=sessionControlFactory;
    }
    /** Call after preventing new control accepts; safe during an in-flight allocation. */
    public void stopWorkers(){workers.close();}
    public boolean awaitStopped(long millis)throws InterruptedException{return workers.awaitStopped(millis);}
    public IOException cleanupFailure(){return workers.failure();}
    private ReceiverWorkers.Worker openInput(PairVerify.VerifiedSession session)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{
            ReceiverWorkers.Worker value=inputFactory.open(session,modes,events,metadata);
            if(value==null)throw new IOException("Receiver input unavailable");
            slot.attach(value);attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private EncryptedEvents openEvents(PairVerify.VerifiedSession session,ModeControl modes)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final EncryptedEvents value=new EncryptedEvents(session,modes);
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});
            attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private ReceiverWorkers.Worker openSessionControl(PairVerify.VerifiedSession session)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{ReceiverWorkers.Worker value=sessionControlFactory.open(session,modes,events,metadata);
            if(value==null)throw new IOException("Receiver session controls unavailable");slot.attach(value);attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private TimingClock openTiming(PairVerify.VerifiedSession session,int port)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final TimingClock value=new TimingClock(session,port);
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});
            attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private AudioDataStream openAudio(PairVerify.VerifiedSession session,String id,AudioDataStream.Sink output,int controlPort)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final AudioDataStream value=session.openAudio(id,output,controlPort);
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});
            attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private AudioInputStream openMicrophone(PairVerify.VerifiedSession session,AudioDescription description)throws IOException {
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final AudioInputStream value=microphoneFactory instanceof ModeAwareMicrophoneFactory?
                ((ModeAwareMicrophoneFactory)microphoneFactory).open(session,description,modes):microphoneFactory.open(session,description);if(value==null)throw new IOException("Microphone endpoint unavailable");
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private static final class DuplexSink implements AudioDataStream.SynchronizedSink {
        final AudioDataStream.Sink output;volatile AudioInputStream input;
        DuplexSink(AudioDataStream.Sink output){this.output=output;}
        public boolean synchronize(AudioSyncPacket packet)throws IOException{return output instanceof AudioDataStream.SynchronizedSink&&((AudioDataStream.SynchronizedSink)output).synchronize(packet);}
        public void packet(AudioPacketDecoder.Packet packet)throws IOException{output.packet(packet);AudioInputStream value=input;if(value!=null)value.outputObserved();}
        public void record()throws IOException{if(!(output instanceof AudioDataStream.RecordableSink))throw new IOException("Microphone needs RECORD output");((AudioDataStream.RecordableSink)output).record();AudioInputStream value=input;if(value!=null)value.record();}
        public void retire(){AudioInputStream value=input;if(value!=null)value.close();if(output instanceof AudioDataStream.RecordableSink)((AudioDataStream.RecordableSink)output).retire();}
        public boolean canFlush(){return output instanceof AudioDataStream.FlushableSink&&((AudioDataStream.FlushableSink)output).canFlush();}
        public AudioDataStream.Flush beginFlush(long timestamp)throws IOException{if(!(output instanceof AudioDataStream.FlushableSink))throw new IOException("Audio FLUSH unavailable");return ((AudioDataStream.FlushableSink)output).beginFlush(timestamp);}
        public void closed(IOException failure){AudioInputStream value=input;if(value!=null)value.close();output.closed(failure);}
    }
    private IapDataTunnel openTunnel(PairVerify.VerifiedSession session,String seed,IapDataTunnel.EventChannel events)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final IapDataTunnel value=session.openTunnel(seed,events);
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});
            attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    private ScreenDataStream openScreen(PairVerify.VerifiedSession session,int type,String id,ScreenDataStream.Sink media)throws IOException{
        ReceiverWorkers.Slot slot=workers.reserve();boolean attached=false;
        try{final ScreenDataStream value=session.openScreen(type,id,media);
            slot.attach(new ReceiverWorkers.Worker(){public void close(){value.close();}public boolean awaitStopped(long millis)throws InterruptedException{return value.awaitStopped(millis);}});
            attached=true;return value;
        }finally{if(!attached)slot.failed();}
    }
    public ControlMessage.Response info(ControlMessage.Request request)throws IOException{return metadata==null?delegate.info(request):metadata.info();}
    public ControlMessage.Response authenticated(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        session.assertCurrent();
        if(pendingTeardown!=null)return ControlMessage.Response.empty(455);
        if(!request.method.equals("SETUP")){
            if(request.path.equals("/feedback"))return feedback(request,session);
            if(request.method.equals("RECORD"))return record(request,session);
            if(request.method.equals("FLUSH"))return flush(request,session);
            if(request.method.equals("FLUSHBUFFERED"))return ControlMessage.Response.empty(501);
            if(request.method.equals("TEARDOWN"))return teardown(request,session);
            if(modes!=null){ControlMessage.Response reply=modes.handle(request,session);if(reply!=null)return reply;}
            return delegate.authenticated(request,session);
        }
        if(owner!=null&&owner!=session)throw new IOException("SETUP session ownership");
        if(awaiting!=null||pendingFlushRequest!=null)throw new IOException("Previous stream response not flushed");
        String type=request.headers.get("content-type");
        if(type==null||!type.equalsIgnoreCase("application/x-apple-binary-plist"))return ControlMessage.Response.empty(415);
        Map<String,Object> dict;
        try{dict=BinaryPlist.dictionary(BinaryPlist.decode(request.body));}
        catch(IOException e){return ControlMessage.Response.empty(400);}
        if(dict.containsKey("streams"))return stream(request,session,dict.get("streams"));
        if(owner!=null)return ControlMessage.Response.empty(400);
        List<String> enabled=Collections.emptyList();
        if(metadata!=null)try{enabled=metadata.setupFeatures(dict.get("features"));}catch(IOException e){return ControlMessage.Response.empty(400);}
        // Low-power keepalive and feature negotiations need their actual implementations before advertising support.
        Object lowPower=dict.get("keepAliveLowPower");
        if(Boolean.TRUE.equals(lowPower)||Long.valueOf(1).equals(lowPower))return ControlMessage.Response.empty(501);
        int peerPort=0;Object rawPort=dict.get("timingPort");
        if(rawPort!=null){if(!(rawPort instanceof Long)||((Long)rawPort)<0||((Long)rawPort)>65535)return ControlMessage.Response.empty(400);peerPort=((Long)rawPort).intValue();}
        Object protocol=dict.get("timingProtocol");if(protocol!=null&&!"NTP".equals(protocol))return ControlMessage.Response.empty(501);
        owner=session;
        try{
            modes=modeListener==null?null:new ModeControl(session,modeListener);
            events=openEvents(session,modes);timing=openTiming(session,peerPort);
            if(inputFactory!=null)input=openInput(session);
            if(sessionControlFactory!=null)sessionControl=openSessionControl(session);
            Map<String,Object> reply=new LinkedHashMap<String,Object>();reply.put("eventPort",events.port());reply.put("timingPort",timing.port());
            reply.put("enabledFeatures",enabled);
            ControlMessage.Response response=plist(reply);awaiting=request;return response;
        }catch(IOException e){closed(session);session.close();throw e;}
         catch(RuntimeException e){closed(session);session.close();throw e;}
    }
    private ControlMessage.Response stream(ControlMessage.Request request,PairVerify.VerifiedSession session,Object raw)throws IOException{
        if(owner!=session||!baseSent)return ControlMessage.Response.empty(400);
        if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>11)return ControlMessage.Response.empty(400);
        List<Map<String,Object>> requested=new ArrayList<Map<String,Object>>();Set<String> types=new HashSet<String>();Set<String> salts=new HashSet<String>();
        int audioCount=0,inputCount=microphones.size();
        try{
            // Validate the complete request before allocating any stream or codec sink.
            for(Object entry:(List<?>)raw){
                Map<String,Object> stream=BinaryPlist.dictionary(entry);Object value=stream.get("type");
                if(!(value instanceof Long))return ControlMessage.Response.empty(400);
                long number=((Long)value).longValue();if(number!=130&&number!=110&&number!=111&&number!=100&&number!=101&&number!=102)return ControlMessage.Response.empty(501);
                int type=(int)number;boolean isAudio=type>=100&&type<=102;
                BigInteger salt;
                if(isAudio){
                    if(audioFactory==null)return ControlMessage.Response.empty(501);
                    AudioDescription description=AudioDescription.parse(stream);
                    if(description.inputPort!=0&&(microphoneFactory==null||!microphoneFactory.supports(description)||metadata!=null&&!metadata.audioCapabilities().supportsInput(description)))return ControlMessage.Response.empty(501);
                    if(description.inputPort!=0&&++inputCount>2)return ControlMessage.Response.empty(455);
                    if(description.inputPort!=0&&!session.microphoneAvailable(description.audioType))return ControlMessage.Response.empty(455);
                    if(metadata!=null&&(metadata.audioCapabilities()==null||!metadata.audioCapabilities().supports(description)))return ControlMessage.Response.empty(501);
                    if(!audioFactory.supports(description))return ControlMessage.Response.empty(501);
                    if(!types.add(description.key)||audio.containsKey(description.key)||audio.size()+(++audioCount)>8)return ControlMessage.Response.empty(400);
                    salt=new BigInteger(description.connectionId);
                }else if(type==130){
                    if(!types.add(Integer.toString(type)))return ControlMessage.Response.empty(400);
                    if(tunnel!=null){
                        if(!retiringTunnel)return ControlMessage.Response.empty(400);
                        try{if(!tunnel.awaitStopped(0)||!((RetirableRuntimeSink)sink).retired(session,runtime))return ControlMessage.Response.empty(455);}
                        catch(InterruptedException e){Thread.currentThread().interrupt();return ControlMessage.Response.empty(455);}
                        tunnel=null;runtime=null;retiringTunnel=false;tunnelConnectionId=null;
                    }
                    Object uuid=stream.get("clientTypeUUID");
                    if(!(uuid instanceof String)||!IapDataTunnel.CLIENT_TYPE_UUID.equalsIgnoreCase((String)uuid))return ControlMessage.Response.empty(400);
                    salt=BinaryPlist.unsigned64(stream.get("seed"));
                    if(stream.containsKey("streamConnectionID")&&tunnelConnectionIds.contains(BinaryPlist.unsigned64(stream.get("streamConnectionID")).toString()))return ControlMessage.Response.empty(400);
                }else{
                    if(!types.add(Integer.toString(type)))return ControlMessage.Response.empty(400);
                    if(screenFactory==null||!screenFactory.supports(type)||metadata!=null&&!metadata.supports(type))return ControlMessage.Response.empty(501);
                    ScreenDataStream previous=screens.get(type);
                    if(previous!=null){
                        if(!retiringScreens.contains(type))return ControlMessage.Response.empty(400);
                        try{if(!previous.awaitStopped(0))return ControlMessage.Response.empty(455);}
                        catch(InterruptedException e){Thread.currentThread().interrupt();return ControlMessage.Response.empty(455);}
                        screens.remove(type);retiringScreens.remove(type);
                    }
                    salt=BinaryPlist.unsigned64(stream.get("streamConnectionID"));
                }
                if(!salts.add(salt.toString()))return ControlMessage.Response.empty(400);
                requested.add(stream);
            }
        }catch(IOException e){return ControlMessage.Response.empty(400);}
        try{
            List<Object> replies=new ArrayList<Object>();
            for(Map<String,Object> stream:requested){
                int type=((Long)stream.get("type")).intValue();Map<String,Object> reply=new LinkedHashMap<String,Object>();reply.put("type",type);
                if(type>=100&&type<=102){
                    AudioDescription description=AudioDescription.parse(stream);
                    AudioDataStream.Sink output=audioFactory instanceof ClockedAudioSinkFactory?
                        ((ClockedAudioSinkFactory)audioFactory).open(session,description,modes,timing):audioFactory.open(session,description,modes);
                    if(output==null)throw new IOException("Audio output unavailable");
                    AudioDataStream endpoint=null;
                    DuplexSink duplex=description.inputPort==0?null:new DuplexSink(output);
                    try{
                        if(metadata!=null&&!(output instanceof AudioDataStream.RecordableSink))throw new IOException("Declared audio output needs RECORD control");
                        endpoint=openAudio(session,description.connectionId,duplex==null?output:duplex,description.controlPort);
                        if(duplex!=null){AudioInputStream microphone=openMicrophone(session,description);duplex.input=microphone;microphones.put(description.key,microphone);}
                    }
                    finally{if(endpoint==null)output.closed(new IOException("Audio allocation failed"));}
                    audio.put(description.key,endpoint);audioDescriptions.put(description.key,description);pendingAudio.add(endpoint);
                    reply.put("dataPort",endpoint.dataPort());reply.put("controlPort",endpoint.controlPort());
                    reply.put("streamConnectionID",new BigInteger(description.connectionId));
                }else if(type==130){
                    tunnel=openTunnel(session,BinaryPlist.unsigned64(stream.get("seed")).toString(),events.iapChannel(session));pendingTunnel=true;
                    tunnelConnectionId=stream.containsKey("streamConnectionID")?BinaryPlist.unsigned64(stream.get("streamConnectionID")).toString():null;
                    if(tunnelConnectionId!=null)tunnelConnectionIds.add(tunnelConnectionId);
                    reply.put("streamID",1);reply.put("dataPort",tunnel.dataPort());
                    if(stream.containsKey("streamConnectionID"))reply.put("streamConnectionID",BinaryPlist.unsigned64(stream.get("streamConnectionID")));
                }else{
                    ScreenDataStream.Sink media=screenFactory.open(session,type,Collections.unmodifiableMap(stream),timing,events);
                    if(media==null)throw new IOException("Screen media sink unavailable");
                    ScreenDataStream screen=null;
                    try{screen=openScreen(session,type,BinaryPlist.unsigned64(stream.get("streamConnectionID")).toString(),media);}
                    finally{if(screen==null)media.closed(new IOException("Screen allocation failed"));}
                    screens.put(type,screen);pendingScreens.add(screen);reply.put("dataPort",screen.dataPort());
                }
                replies.add(reply);
            }
            ControlMessage.Response response=plist(Collections.singletonMap("streams",replies));awaiting=request;return response;
        }catch(IOException e){closed(session);session.close();throw e;}
         catch(RuntimeException e){closed(session);session.close();throw e;}
    }
    private static ControlMessage.Response plist(Object value)throws IOException{return ControlMessage.Response.body("application/x-apple-binary-plist",BinaryPlist.encode(value));}
    private ControlMessage.Response feedback(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(!request.method.equals("POST"))return ControlMessage.Response.empty(405);
        if(request.body.length>0){
            if(!"application/x-apple-binary-plist".equalsIgnoreCase(request.headers.get("content-type")))return ControlMessage.Response.empty(415);
            try{BinaryPlist.dictionary(BinaryPlist.decode(request.body));}catch(IOException e){return ControlMessage.Response.empty(400);}
        }
        if(owner==null)return ControlMessage.Response.empty(200);
        if(owner!=session||!baseSent||awaiting!=null||pendingRecord!=null||pendingFlushRequest!=null)return ControlMessage.Response.empty(455);
        if(audio.isEmpty())return ControlMessage.Response.empty(200);
        if(!(audioFactory instanceof FeedbackAudioSinkFactory))return ControlMessage.Response.empty(501);
        List<Object> streams=new ArrayList<Object>();
        for(AudioDescription description:audioDescriptions.values()){
            Map<String,Object> value=((FeedbackAudioSinkFactory)audioFactory).feedback(session,description,timing);
            if(value!=null)streams.add(value);
        }
        session.assertCurrent();Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("streams",streams);return plist(result);
    }
    private ControlMessage.Response record(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(owner!=session||!baseSent||awaiting!=null||pendingRecord!=null||pendingFlushRequest!=null)return ControlMessage.Response.empty(455);
        if(metadata==null||metadata.audioCapabilities()==null||audioFactory==null)return ControlMessage.Response.empty(501);
        if(request.body.length!=0)return ControlMessage.Response.empty(400);
        String rtp=request.headers.get("rtp-info");
        if(rtp!=null&&!validRecordRtpInfo(rtp))return ControlMessage.Response.empty(400);
        // Optional initial seq/time are framing metadata, not a seek or a local
        // clock anchor. Playback uses authenticated packet timestamps as before.
        pendingRecord=request;return ControlMessage.Response.empty(200);
    }
    private static boolean validRecordRtpInfo(String value){
        if(value.length()>80)return false;String[] parts=value.split(";",-1);if(parts.length!=2)return false;
        Set<String> keys=new HashSet<String>();
        for(String part:parts){String[] pair=part.trim().split("=",-1);if(pair.length!=2||!keys.add(pair[0])||!pair[1].matches("[0-9]{1,10}"))return false;
            long n=Long.parseLong(pair[1]);
            if(pair[0].equals("seq")){if(n>65535)return false;}
            else if(pair[0].equals("rtptime")){if(n>0xffffffffL)return false;}
            else return false;
        }return keys.contains("seq")&&keys.contains("rtptime");
    }
    private ControlMessage.Response flush(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(owner!=session||!baseSent||!recorded||awaiting!=null||pendingRecord!=null||pendingFlushRequest!=null)return ControlMessage.Response.empty(455);
        if(metadata==null||metadata.audioCapabilities()==null||audioFactory==null)return ControlMessage.Response.empty(501);
        String rtp=request.headers.get("rtp-info");
        if(request.body.length!=0||rtp==null||!validRecordRtpInfo(rtp))return ControlMessage.Response.empty(400);
        // Type100/102 are main audio; 101 is alternate. Never infer one category
        // or connection ID when several main outputs exist in our extended profile.
        AudioDataStream target=null;
        for(Map.Entry<String,AudioDataStream> entry:audio.entrySet())
            if(entry.getKey().startsWith("100:")||entry.getKey().startsWith("102:")){
                if(target!=null)return ControlMessage.Response.empty(455);target=entry.getValue();
            }
        if(target==null||!target.canFlush())return ControlMessage.Response.empty(455);
        long timestamp=0;for(String part:rtp.split(";")){String[] pair=part.trim().split("=");if(pair[0].equals("rtptime"))timestamp=Long.parseLong(pair[1]);}
        // seq is range-checked framing metadata: the authenticated payload covers
        // timestamp/SSRC, not the RTP sequence. Do not reset nonce/replay state.
        try{
            pendingFlush=target.beginFlush(timestamp);pendingFlushRequest=request;
            Long last=pendingFlush.await(5000);session.assertCurrent();
            // Zero is the wire sentinel before any packet was played, not an
            // observed consumed sample or a fabricated seek position.
            return new ControlMessage.Response(200,Collections.singletonMap("RTP-Info","rtptime="+(last==null?0:last)),new byte[0]);
        }catch(InterruptedException e){Thread.currentThread().interrupt();closed(session);session.close();throw new IOException("Audio FLUSH interrupted",e);}
         catch(IOException e){closed(session);session.close();throw e;}
         catch(RuntimeException e){closed(session);session.close();throw e;}
    }
    public void responseSent(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(session.controlRetired())return; // An old response cannot activate a successor's resources.
        if(request==pendingTeardown){
            if(teardownOwner!=session||owner!=null&&owner!=session)throw new IOException("TEARDOWN response ownership");
            session.assertCurrent();pendingTeardown=null;teardownOwner=null;
            // ControlConnection calls this only after the complete response flush.
            // Its normal-close path then runs exact-session cleanup in finally.
            session.retireControl();return;
        }
        if(request==pendingFlushRequest){
            if(owner!=session||pendingFlush==null)throw new IOException("FLUSH response ownership");session.assertCurrent();
            AudioDataStream.Flush operation=pendingFlush;pendingFlush=null;pendingFlushRequest=null;
            try{operation.responseSent();}catch(IOException e){closed(session);session.close();throw e;}
            return;
        }
        if(request==pendingRecord){
            if(owner!=session||!baseSent)throw new IOException("RECORD response ownership");
            pendingRecord=null;session.assertCurrent();
            try{for(AudioDataStream stream:audio.values())stream.record();recorded=true;}
            catch(IOException e){closed(session);session.close();throw e;}
            catch(RuntimeException e){closed(session);session.close();throw e;}
            return;
        }
        if(request!=awaiting){delegate.responseSent(request,session);return;}
        if(owner!=session)throw new IOException("SETUP flush ownership");awaiting=null;session.assertCurrent();
        if(!baseSent){events.setupResponseSent();timing.setupResponseSent();baseSent=true;}
        else{
            try{
                if(pendingTunnel){tunnel.setupResponseSent();runtime=new TunnelIap2Session(tunnel);sink.opened(session,runtime,timing);pendingTunnel=false;}
                for(ScreenDataStream screen:pendingScreens)screen.setupResponseSent();pendingScreens.clear();
                for(AudioDataStream stream:pendingAudio){stream.setupResponseSent();if(recorded)stream.record();}pendingAudio.clear();
                for(AudioInputStream microphone:microphones.values())microphone.setupResponseSent();
            }catch(IOException e){closed(session);session.close();throw e;}
             catch(RuntimeException e){closed(session);session.close();throw e;}
        }
    }
    public void closed(PairVerify.VerifiedSession session){
        if(teardownOwner==session){pendingTeardown=null;teardownOwner=null;}
        if(owner==session){
            if(input!=null){try{input.close();}catch(RuntimeException ignored){/* Worker group retries and records cleanup failure. */}input=null;}
            if(sessionControl!=null){try{sessionControl.close();}catch(RuntimeException ignored){/* Retained by worker group until drained. */}sessionControl=null;}
            if(tunnel!=null)tunnel.close();if(events!=null)events.close();if(timing!=null)timing.close();
            if(modes!=null)modes.close();modes=null;
            for(ScreenDataStream screen:screens.values())screen.close();screens.clear();retiringScreens.clear();pendingScreens.clear();pendingTunnel=false;
            for(AudioDataStream stream:audio.values())stream.close();audio.clear();audioDescriptions.clear();pendingAudio.clear();
            for(AudioInputStream microphone:microphones.values())microphone.close();microphones.clear();
            owner=null;awaiting=null;pendingRecord=null;pendingFlushRequest=null;pendingFlush=null;recorded=false;tunnel=null;runtime=null;retiringTunnel=false;tunnelConnectionId=null;tunnelConnectionIds.clear();events=null;timing=null;baseSent=false;sink.closed(session);
        }
        if(delegate instanceof ControlConnection.SessionHandler)((ControlConnection.SessionHandler)delegate).closed(session);
    }
    /** Empty body/dictionary/stream list selects the whole session, as in the pinned
     * DiPlay teardownStreamTypes reader. This is terminal for this verification:
     * event/tunnel keys and counters must not reset on the same control session. */
    private ControlMessage.Response teardown(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(owner!=null&&owner!=session)return ControlMessage.Response.empty(455);
        if(request.body.length!=0){
            if(!"application/x-apple-binary-plist".equalsIgnoreCase(request.headers.get("content-type")))return ControlMessage.Response.empty(415);
            Map<String,Object> body;try{body=BinaryPlist.dictionary(BinaryPlist.decode(request.body));}catch(IOException e){return ControlMessage.Response.empty(400);}
            if(body.containsKey("streams")){
                Object streams=body.get("streams");if(!(streams instanceof List))return ControlMessage.Response.empty(400);
                if(!((List<?>)streams).isEmpty())return teardownStreams(request,session);
            }
        }
        pendingTeardown=request;teardownOwner=session;
        return new ControlMessage.Response(200,Collections.singletonMap("Connection","close"),new byte[0]);
    }
    /** Validate all selectors before mutating any channel. */
    private ControlMessage.Response teardownStreams(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException{
        if(owner!=session||!baseSent||awaiting!=null||pendingFlushRequest!=null)return ControlMessage.Response.empty(400);
        if(!"application/x-apple-binary-plist".equalsIgnoreCase(request.headers.get("content-type")))return ControlMessage.Response.empty(415);
        Set<String> keys=new HashSet<String>();Set<Integer> screenTypes=new HashSet<Integer>();boolean iap=false;
        try{
            Object raw=BinaryPlist.dictionary(BinaryPlist.decode(request.body)).get("streams");
            if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>10)return ControlMessage.Response.empty(400);
            for(Object entry:(List<?>)raw){
                Map<String,Object> d=BinaryPlist.dictionary(entry);Object n=d.get("type");if(!(n instanceof Long))return ControlMessage.Response.empty(400);
                long type=(Long)n;
                if(type==130){
                    Object id=d.get("streamID");
                    if(id!=null&&!(id instanceof Long))return ControlMessage.Response.empty(400);
                    // Pinned DiPlay ignores other type130 streamIDs (e.g. video-in-car).
                    if(id!=null&&!Long.valueOf(1).equals(id))continue;
                    if(!(sink instanceof RetirableRuntimeSink))return ControlMessage.Response.empty(501);
                    if(iap||tunnel==null||runtime==null||retiringTunnel)return ControlMessage.Response.empty(400);
                    if(d.containsKey("streamConnectionID")&&(tunnelConnectionId==null||!tunnelConnectionId.equals(BinaryPlist.unsigned64(d.get("streamConnectionID")).toString())))return ControlMessage.Response.empty(400);
                    iap=true;continue;
                }
                if(type==110||type==111){
                    ScreenDataStream screen=screens.get((int)type);
                    if(screen==null||!screenTypes.add((int)type)||retiringScreens.contains((int)type))return ControlMessage.Response.empty(400);
                    if(d.containsKey("streamConnectionID")&&!screen.connectionId().equals(BinaryPlist.unsigned64(d.get("streamConnectionID")).toString()))return ControlMessage.Response.empty(400);
                    continue;
                }
                if(type<100||type>102)return ControlMessage.Response.empty(501);
                Object category=d.get("audioType");if(category!=null&&!(category instanceof String))return ControlMessage.Response.empty(400);
                String prefix=type+":";boolean matched=false;
                for(String key:audio.keySet())if(key.startsWith(prefix)&&(category==null||key.equals(prefix+category))){keys.add(key);matched=true;}
                if(!matched)return ControlMessage.Response.empty(400);
            }
        }catch(IOException e){return ControlMessage.Response.empty(400);}
        if(iap){
            if(!((RetirableRuntimeSink)sink).retire(session,runtime))throw new IOException("Runtime stopped before protocol retirement");
            retiringTunnel=true;
        }
        for(Integer type:screenTypes){
            ScreenDataStream screen=screens.get(type);
            if(!screen.retire())throw new IOException("Screen stopped before protocol retirement");
            retiringScreens.add(type);
        }
        for(String key:keys){AudioDataStream stream=audio.remove(key);audioDescriptions.remove(key);AudioInputStream microphone=microphones.remove(key);if(microphone!=null)microphone.close();try{stream.retireOutput();}finally{stream.close();}}return ControlMessage.Response.empty(200);
    }
}
