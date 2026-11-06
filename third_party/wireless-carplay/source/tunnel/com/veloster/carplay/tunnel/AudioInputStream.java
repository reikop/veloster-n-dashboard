/* SPDX-License-Identifier: GPL-3.0-only. See NOTICE.md. */
package com.veloster.carplay.tunnel;
import com.veloster.carplay.media.SystemEld16kEncoder;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** One selected verified session's negotiated input socket/key. Capture may
 * pause without resetting RTP/crypto counters. SETUP flush, RECORD, verified
 * downlink, phone admission and the category gate precede physical capture. */
public final class AudioInputStream implements Closeable {
    public interface Source extends Closeable {
        void start()throws IOException;
        int read(byte[] target,int offset,int count)throws IOException;
        /** Cancel a blocked read; release belongs to the capture worker. */
        void stop();
    }
    public interface SourceFactory {Source open()throws IOException;}
    /** Keeps ownership of a source before potentially blocking device allocation. */
    public interface PreparedSource extends Source {void prepare()throws IOException;}
    /** Bounded, nonblocking ownership snapshot; false pauses capture, not the session. */
    public static final class CaptureState {
        public final boolean allowed;public final long revision;
        public CaptureState(boolean allowed,long revision){if(revision<0)throw new IllegalArgumentException("Capture gate revision");this.allowed=allowed;this.revision=revision;}
    }
    public interface CaptureGate {CaptureState state()throws IOException;}
    public interface Listener {void closed(IOException failure);}
    private static final CaptureState UNCHANGED=new CaptureState(true,0);
    private static final CaptureGate ALWAYS=new CaptureGate(){public CaptureState state(){return UNCHANGED;}};
    private final Object lock=new Object();private final IapDataTunnel.Binding binding;private final IapDataTunnel.SessionLease lease;
    private interface WireEncoder {
        void beginPhase()throws IOException;
        byte[] encode(byte[] pcm,int samples)throws IOException;
        void endPhase();
        void close();
    }
    private static final class PcmWireEncoder implements WireEncoder {
        private final AudioPacketEncoder pcm;
        PcmWireEncoder(byte[] key,AudioWireFormat format){pcm=new AudioPacketEncoder(key,format,100);}
        public void beginPhase(){}
        public byte[] encode(byte[] frame,int samples)throws IOException{return pcm.encode(frame,samples);}
        public void endPhase(){}
        public void close(){pcm.close();}
    }
    private static final class EldWireEncoder implements WireEncoder {
        private final Eld16kPacketEncoder packets;private SystemEld16kEncoder codec;
        EldWireEncoder(byte[] key){packets=new Eld16kPacketEncoder(key);}
        public void beginPhase()throws IOException{
            if(codec!=null)throw new IOException("ELD capture phase already active");
            try{codec=new SystemEld16kEncoder();}
            catch(LinkageError unavailable){throw new IOException("ELD system backend unavailable",unavailable);}
        }
        public byte[] encode(byte[] frame,int samples)throws IOException{
            if(codec==null||samples!=480)throw new IOException("ELD capture phase/clock");
            byte[] au=codec.encode(frame);
            if(au==null)return null;
            try{return packets.encode(au);}finally{Arrays.fill(au,(byte)0);}
        }
        public void endPhase(){if(codec!=null){codec.close();codec=null;}}
        public void close(){try{endPhase();}finally{packets.close();}}
    }
    private final WireEncoder encoder;private final SourceFactory factory;private final Listener listener;
    private final CaptureGate gate;private final MicrophoneCaptureLease captures;
    private final DatagramSocket socket;private final Thread worker,canceller;private final CountDownLatch stopped=new CountDownLatch(1),cancellationStopped=new CountDownLatch(1);
    private final int remotePort,samples,bytes;private final long duration;
    private volatile boolean closed;private boolean setup,recorded,observed,captureFinished;private long cleanupRequests;
    private volatile IOException failure;private Phase phase;
    private volatile long packets,frames;private final long created=System.nanoTime();
    private static final class Phase {Source source;boolean cancelled,stopStarted,stopDone;final long revision;Phase(long revision){this.revision=revision;}}
    private static final class Paused extends IOException {}
    public AudioInputStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,byte[] secret,String id,int remotePort,int samples,AudioWireFormat format,SourceFactory factory,Listener listener)throws IOException{
        this(binding,lease,secret,id,remotePort,samples,format,factory,listener,MicrophoneCaptureLease.process(),ALWAYS);
    }
    public AudioInputStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,byte[] secret,String id,int remotePort,int samples,AudioWireFormat format,SourceFactory factory,Listener listener,MicrophoneCaptureLease captures)throws IOException{
        this(binding,lease,secret,id,remotePort,samples,format,factory,listener,captures,ALWAYS);
    }
    public AudioInputStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,byte[] secret,String id,int remotePort,int samples,AudioWireFormat format,SourceFactory factory,Listener listener,MicrophoneCaptureLease captures,CaptureGate gate)throws IOException {
        if(binding==null||lease==null||factory==null||listener==null||captures==null||gate==null)throw new NullPointerException();
        boolean eld=format!=null&&format.bits==0x04000000L&&format.aac&&format.aacProfile==39&&
            format.sampleRate==16000&&format.channels==1&&format.aacFrameSamples==480&&samples==480;
        boolean pcm=format!=null&&!format.aac&&format.channels==1&&
            (format.sampleRate==16000||format.sampleRate==8000)&&
            samples>=format.sampleRate/200&&samples<=format.sampleRate*60/1000;
        if(remotePort<1||remotePort>65535||(!pcm&&!eld))throw new IOException("Unsupported microphone profile");
        this.binding=binding;this.lease=lease;this.factory=factory;this.listener=listener;this.remotePort=remotePort;this.samples=samples;this.captures=captures;this.gate=gate;bytes=samples*2;duration=samples*1000000000L/format.sampleRate;check();
        byte[] key=TunnelCrypto.transmitKey(secret,id);
        try{encoder=eld?new EldWireEncoder(key):new PcmWireEncoder(key,format);}
        finally{Arrays.fill(key,(byte)0);}
        DatagramSocket owned=null;try{owned=new DatagramSocket(null);owned.setReuseAddress(false);owned.bind(new InetSocketAddress(binding.localAddress,0));check();}
        catch(IOException e){encoder.close();if(owned!=null)owned.close();throw e;}catch(RuntimeException e){encoder.close();if(owned!=null)owned.close();throw e;}
        socket=owned;boolean cancelStarted=false;
        try{worker=new Thread(new Runnable(){public void run(){capture();}},"vn-carplay-microphone");worker.setDaemon(true);
            canceller=new Thread(new Runnable(){public void run(){cancelCapture();}},"vn-carplay-microphone-stop");canceller.setDaemon(true);
            canceller.start();cancelStarted=true;worker.start();}
        catch(RuntimeException e){synchronized(lock){closed=true;captureFinished=true;lock.notifyAll();}socket.close();encoder.close();if(!cancelStarted)cancellationStopped.countDown();stopped.countDown();throw new IOException("Microphone worker unavailable",e);}
    }
    public int localPort(){return socket.getLocalPort();}
    public long sentPackets(){return packets;}
    public long sentFrames(){return frames;}
    /** Physical resource state only, not proof of acoustics or peer recognition. */
    public boolean capturing(){synchronized(lock){return phase!=null&&phase.source!=null&&!phase.cancelled;}}
    private void checkLease()throws IOException{if(closed)throw new IOException("Microphone closed");lease.checkCurrent(binding);}
    private void checkMedia()throws IOException{checkLease();if(lease instanceof IapDataTunnel.MediaLease&&!((IapDataTunnel.MediaLease)lease).mediaAllowed(binding))throw new IOException("Microphone phone identity withdrawn");}
    private void check()throws IOException{checkLease();NetworkInterface value=NetworkInterface.getByInetAddress(binding.localAddress);if(value==null||!binding.interfaceName.equals(value.getName()))throw new IOException("Microphone interface changed");}
    private void fail(IOException error){boolean cancel;synchronized(lock){if(failure==null)failure=error;cancel=!closed;}if(cancel)close();}
    public void setupResponseSent()throws IOException{checkLease();synchronized(lock){setup=true;lock.notifyAll();}}
    public void record()throws IOException{checkLease();synchronized(lock){recorded=true;lock.notifyAll();}}
    /** Called only after AEAD-verified downlink was handed to its output. */
    public void outputObserved()throws IOException{checkLease();synchronized(lock){observed=true;lock.notifyAll();}}
    private boolean cancelled(Phase value){synchronized(lock){return value.cancelled||closed;}}
    private void checkCapture(Phase value)throws IOException{
        checkMedia();CaptureState state=gate.state();if(state==null)throw new IOException("Missing capture gate state");
        synchronized(lock){if(!state.allowed||state.revision!=value.revision){value.cancelled=true;lock.notifyAll();}if(value.cancelled||closed)throw new Paused();}
    }
    /** One persistent tracked cancellation worker per logical input. stop() may
     * block in an OEM driver; the physical lease and shutdown remain pending. */
    private void cancelCapture(){
        boolean interrupted=false;
        try{for(;;){
            Phase value;boolean ending;synchronized(lock){if(captureFinished)return;value=phase;ending=closed;}
            if(!ending){try{lease.checkCurrent(binding);CaptureState state=gate.state();if(state==null)throw new IOException("Missing capture gate state");
                if(value!=null){if(lease instanceof IapDataTunnel.MediaLease&&!((IapDataTunnel.MediaLease)lease).mediaAllowed(binding))throw new IOException("Microphone phone identity withdrawn");
                    if(!state.allowed||state.revision!=value.revision)synchronized(lock){value.cancelled=true;lock.notifyAll();}}
            }catch(IOException e){fail(e);}catch(RuntimeException e){fail(new IOException("Microphone gate failed",e));}catch(LinkageError e){fail(new IOException("Microphone gate API failed",e));}}
            Source stop=null;
            synchronized(lock){value=phase;if(value!=null&&(closed||value.cancelled)&&value.source!=null&&!value.stopStarted){value.cancelled=true;value.stopStarted=true;stop=value.source;}}
            if(stop!=null){try{stop.stop();}catch(RuntimeException e){fail(new IOException("Microphone stop failed",e));}catch(LinkageError e){fail(new IOException("Microphone stop API failed",e));}
                finally{synchronized(lock){value.stopDone=true;lock.notifyAll();}}}
            synchronized(lock){if(!captureFinished)try{lock.wait(25);}catch(InterruptedException e){interrupted=true;fail(new IOException("Microphone cancellation worker interrupted",e));}}
        }}finally{cancellationStopped.countDown();if(interrupted)Thread.currentThread().interrupt();}
    }
    private void capture(){
        byte[] frame=new byte[bytes];boolean interrupted=false;
        try{
            long identityStarted=0,lastInterface=System.nanoTime();boolean identityEstablished=false;
            while(!closed){
                checkLease();long now=System.nanoTime();if(now-lastInterface>=250000000L){check();lastInterface=now;}
                boolean flushed,requested;synchronized(lock){flushed=setup;requested=recorded&&observed;}
                boolean admitted=false;
                if(!flushed){if(now-created>=30000000000L)throw new IOException("Microphone SETUP flush timed out");}
                else{if(identityStarted==0)identityStarted=now;admitted=!(lease instanceof IapDataTunnel.MediaLease)||((IapDataTunnel.MediaLease)lease).mediaAllowed(binding);
                    if(admitted)identityEstablished=true;else if(identityEstablished||now-identityStarted>=30000000000L)throw new IOException("Microphone phone identity unavailable");}
                CaptureState state=gate.state();if(state==null)throw new IOException("Missing capture gate state");
                if(!flushed||!requested||!admitted||!state.allowed){synchronized(lock){if(!closed)lock.wait(25);}continue;}
                MicrophoneCaptureLease.Reservation reservation=captures.acquire(binding,lease,this);
                if(reservation==null){synchronized(lock){if(!closed)lock.wait(25);}continue;}
                Phase value=new Phase(state.revision);synchronized(lock){phase=value;if(closed)value.cancelled=true;lock.notifyAll();}
                try{
                    checkCapture(value);encoder.beginPhase();checkCapture(value);
                    checkCapture(value);Source opened=factory.open();if(opened==null)throw new IOException("Microphone source unavailable");
                    synchronized(lock){value.source=opened;lock.notifyAll();}check();checkCapture(value);
                    if(opened instanceof PreparedSource)((PreparedSource)opened).prepare();check();checkCapture(value);opened.start();check();checkCapture(value);
                    int filled=0;long emptySince=System.nanoTime(),deadline=emptySince;
                    for(;;){
                        checkCapture(value);int count=opened.read(frame,filled,frame.length-filled);
                        // A cancellation-induced read error is a pause, not a failed call.
                        checkCapture(value);if(count<0||count>frame.length-filled)throw new IOException("Microphone capture failed");
                        if(count==0){if(System.nanoTime()-emptySince>2000000000L)throw new IOException("Microphone capture stalled");Thread.sleep(1);continue;}
                        emptySince=System.nanoTime();filled+=count;if(filled<frame.length)continue;
                        now=System.nanoTime();if(now-lastInterface>=250000000L){check();lastInterface=now;}
                        deadline=Math.max(deadline+duration,now);long wait=deadline-System.nanoTime();if(wait>0)Thread.sleep(wait/1000000L,(int)(wait%1000000L));
                        checkCapture(value);byte[] wire=encoder.encode(frame,samples);
                        if(wire==null){Arrays.fill(frame,(byte)0);filled=0;continue;}
                        try{checkCapture(value);socket.send(new DatagramPacket(wire,wire.length,binding.remoteAddress,remotePort));packets++;frames+=samples;}
                        finally{Arrays.fill(wire,(byte)0);Arrays.fill(frame,(byte)0);}filled=0;
                    }
                }catch(Paused paused){/* Keep the endpoint/key/counters; only capture stops. */}
                 catch(IOException e){if(!cancelled(value))throw e;}
                finally{
                    synchronized(lock){value.cancelled=true;lock.notifyAll();}
                    try{encoder.endPhase();}catch(RuntimeException e){fail(new IOException("ELD encoder retirement failed",e));}
                    catch(LinkageError e){fail(new IOException("ELD encoder retirement API failed",e));}
                    if(value.source!=null){
                        for(;;){synchronized(lock){if(value.stopDone)break;try{lock.wait(25);}catch(InterruptedException e){interrupted=true;}}}
                        for(;;){long request;synchronized(lock){request=cleanupRequests;}
                            try{value.source.close();break;}
                            catch(IOException e){fail(e);}catch(RuntimeException e){fail(new IOException("Microphone release failed",e));}catch(LinkageError e){fail(new IOException("Microphone release API failed",e));}
                            synchronized(lock){if(cleanupRequests==request)try{lock.wait(250);}catch(InterruptedException e){interrupted=true;}}
                        }
                    }
                    // No successor can allocate until actual source release succeeds.
                    synchronized(lock){if(phase==value)phase=null;lock.notifyAll();}
                    reservation.released(binding,lease,this);Arrays.fill(frame,(byte)0);
                }
            }
        }catch(IOException e){if(!closed)fail(e);}catch(InterruptedException e){if(!closed)fail(new IOException("Microphone interrupted",e));interrupted=true;}
         catch(RuntimeException e){fail(new IOException("Microphone capture unavailable",e));}catch(LinkageError e){fail(new IOException("Microphone API unavailable",e));}
        finally{
            close();encoder.close();Arrays.fill(frame,(byte)0);synchronized(lock){captureFinished=true;lock.notifyAll();}
            try{listener.closed(failure);}finally{stopped.countDown();}if(interrupted)Thread.currentThread().interrupt();
        }
    }
    public void close(){synchronized(lock){cleanupRequests++;if(!closed){closed=true;if(phase!=null)phase.cancelled=true;}lock.notifyAll();}socket.close();worker.interrupt();}
    public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0||millis>86400000L)throw new IllegalArgumentException();long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);return Thread.currentThread()!=worker&&stopped.await(millis,TimeUnit.MILLISECONDS)&&cancellationStopped.await(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS);}
}
