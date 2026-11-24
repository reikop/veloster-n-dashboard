/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;

import com.veloster.carplay.tunnel.ScreenDataStream;
import com.veloster.carplay.control.ClockReference;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One stream, one decoder worker. Socket callbacks only copy bounded data; close never joins. */
public final class VideoSink implements ScreenDataStream.RetirableHeaderSink,Closeable {
    public interface Output {
        Decoder open(AvcConfig config)throws IOException;
    }
    public interface RenderGate {boolean current()throws IOException;}
    public interface ScheduledOutput extends Output {Decoder open(AvcConfig config,RenderGate gate)throws IOException;}
    public interface ScheduledDecoder extends Decoder {boolean presentationPending();}
    public interface Decoder {
        /** false means no input buffer; worker will drain and retry with a bounded deadline. */
        boolean submit(byte[] annexB,long ptsUs)throws IOException;
        /** Nonblocking drain, returns number submitted to the display, NOT proof of visible pixels. */
        int drain()throws IOException;
        void close();
    }
    public interface Listener {
        /** Worker callback; must return promptly. Wire keyframe request is the owner's responsibility. */
        void keyframeNeeded();
        /** Called once after codec release, including explicit close. Null means orderly close. */
        void stopped(IOException failure);
    }
    private static final int MAX_QUEUED=24,MAX_BYTES=16*1024*1024;
    private final ArrayDeque<Packet> queue=new ArrayDeque<Packet>();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private final Listener listener;private final Thread worker;private final ClockReference.Source clock;
    private AvcConfig config;private Output output;private int bytes;
    private long generation,accepted,submitted,dropped,displaySubmissions,restarts;
    private boolean closed,respectTimestamps,protocolRetired;private IOException failure;
    public VideoSink(Listener listener){
        this(listener,null);
    }
    public VideoSink(Listener listener,ClockReference.Source clock){
        if(listener==null)throw new NullPointerException();this.listener=listener;this.clock=clock;
        worker=new Thread(new Runnable(){public void run(){work();}},"carplay-avc");worker.setDaemon(true);worker.start();
    }
    /** Output belongs to its Surface owner; this sink never releases an externally supplied Surface. */
    public synchronized void setOutput(Output value){
        if(closed)return;output=value;reset();
    }
    public void configuration(byte[] data)throws IOException{
        configure(data,false);
    }
    public void configuration(byte[] header,byte[] data)throws IOException{
        if(header==null||header.length!=128||header[4]!=1)throw new IOException("Video configuration header");
        configure(data,(header[6]&2)!=0);
    }
    private void configure(byte[] data,boolean timed)throws IOException{
        if(timed&&clock==null)throw new IOException("Video network clock unavailable");
        AvcConfig parsed=AvcConfig.parse(data);
        synchronized(this){check();config=parsed;respectTimestamps=timed;reset();}
    }
    public synchronized void frame(byte[] header,byte[] data)throws IOException{
        check();if(config==null)throw new IOException("Video before configuration");
        if(header==null||header.length!=128||data==null||data.length<1||data.length>AvcConfig.MAX_FRAME)throw new IOException("Video packet bounds");
        if(queue.size()>=MAX_QUEUED||bytes>MAX_BYTES-data.length)reset();
        // Unflagged video uses arrival PTS. Flagged video maps the authenticated
        // header's NTP timestamp on the worker when a fresh session clock exists.
        Packet p=new Packet(header.clone(),data.clone(),System.nanoTime()/1000,generation);
        queue.add(p);bytes+=p.data.length;accepted++;notifyAll();
    }
    private void reset(){generation++;clear();notifyAll();}
    private void clear(){while(!queue.isEmpty()){queue.remove().erase();dropped++;}bytes=0;}
    private void check()throws IOException{if(closed)throw new IOException("Video sink closed");}
    public void closed(IOException reason){stop(reason);}
    public void close(){stop(null);}
    /** A prior app/codec stop cannot be relabeled as an accepted remote TEARDOWN. */
    public synchronized boolean retire(){if(closed)return false;protocolRetired=true;stop(null);return true;}
    public synchronized boolean protocolRetired(){return protocolRetired;}
    private synchronized void stop(IOException reason){
        if(closed)return;closed=true;failure=reason;clear();notifyAll();worker.interrupt();
    }
    public boolean awaitStopped(long millis)throws InterruptedException{return stopped.await(millis,TimeUnit.MILLISECONDS);}
    public synchronized long[] stats(){return new long[]{accepted,submitted,dropped,displaySubmissions,restarts};}
    private void work(){
        Decoder decoder=null;long epoch=-1,lastRequest=0,decoderClock=0;boolean reference=false;Packet packet=null;
        try{
            while(true){
                AvcConfig active;Output target;long current;boolean timed;
                synchronized(this){if(closed)break;current=generation;active=config;target=output;timed=respectTimestamps;}
                if(epoch!=current){
                    if(decoder!=null){decoder.close();decoder=null;}epoch=current;reference=false;lastRequest=0;
                    synchronized(this){restarts++;}
                }
                if(active!=null&&target!=null&&!reference){
                    long now=System.nanoTime();if(lastRequest==0||now-lastRequest>=1000000000L){listener.keyframeNeeded();lastRequest=now;}
                }
                ClockReference time=timed&&active!=null&&target!=null?clock.reference():null;
                if(timed&&decoder!=null&&(time==null||time.generation!=decoderClock)){
                    synchronized(this){if(generation==epoch)reset();}continue;
                }
                if(timed&&active!=null&&target!=null&&time==null){
                    synchronized(this){if(!closed&&generation==epoch)wait(10);}continue;
                }
                synchronized(this){
                    if(closed)break;
                    if(generation!=epoch)continue;
                    packet=queue.poll();if(packet!=null)bytes-=packet.data.length;
                }
                if(packet==null){
                    if(decoder!=null)drain(decoder,epoch);
                    synchronized(this){if(!closed&&queue.isEmpty()&&generation==epoch)wait(10);}
                    continue;
                }
                try{
                    if(active==null||target==null){drop();continue;}
                    AvcConfig.Frame frame=active.frame(packet.data);
                    try{
                        if(!reference&&!frame.idr){drop();continue;}
                        long pts=packet.ptsUs;
                        if(timed){
                            long ntp=0;for(int i=7;i>=0;i--)ntp=(ntp<<8)|(packet.header[8+i]&255L);
                            long nanos=time.toMonotonicNanos(ntp,System.nanoTime());pts=nanos/1000;if(nanos%1000>0)pts++;
                        }
                        if(decoder==null){
                            if(timed){
                                if(!(target instanceof ScheduledOutput))throw new IOException("Video output cannot schedule timestamps");
                                final long ownerEpoch=epoch,clockEpoch=time.generation;decoderClock=clockEpoch;
                                decoder=((ScheduledOutput)target).open(active,new RenderGate(){public boolean current()throws IOException{
                                    if(!VideoSink.this.current(ownerEpoch))return false;
                                    ClockReference latest=clock.reference();return latest!=null&&latest.generation==clockEpoch;
                                }});
                            }else decoder=target.open(active);
                        }
                        // Allow slow software/vendor startup while continuously draining output.
                        // This is an operation deadline, not a session or idle timeout.
                        long deadline=System.nanoTime()+2000000000L;boolean queued=false;
                        while(true){
                            synchronized(this){if(closed||generation!=epoch)break;}
                            drain(decoder,epoch);
                            if(decoder.submit(frame.annexB,pts)){queued=true;break;}
                            if(decoder instanceof ScheduledDecoder&&((ScheduledDecoder)decoder).presentationPending())deadline=System.nanoTime()+2000000000L;
                            if(System.nanoTime()>=deadline)throw new IOException("AVC decoder input stalled");
                            Thread.sleep(2);
                        }
                        if(queued){reference=true;synchronized(this){submitted++;}drain(decoder,epoch);}else drop();
                    }finally{Arrays.fill(frame.annexB,(byte)0);}
                }catch(IOException e){if(current(epoch))throw e;drop();}
                 catch(RuntimeException e){if(current(epoch))throw e;drop();}
                 finally{packet.erase();packet=null;}
            }
        }catch(InterruptedException e){synchronized(this){if(!closed)failure=new IOException("Video worker interrupted",e);}}
         catch(IOException e){synchronized(this){failure=e;}}
         catch(RuntimeException e){synchronized(this){failure=new IOException("Video decoder failure",e);}}
         catch(LinkageError e){synchronized(this){failure=new IOException("Video decoder unavailable",e);}}
         finally{
            if(packet!=null)packet.erase();
            try{if(decoder!=null)decoder.close();}
            finally{
                IOException reason;synchronized(this){closed=true;clear();config=null;output=null;reason=failure;}
                try{listener.stopped(reason);}finally{stopped.countDown();}
            }
        }
    }
    private void drop(){synchronized(this){dropped++;}}
    private synchronized boolean current(long epoch){return !closed&&generation==epoch;}
    private void drain(Decoder decoder,long epoch)throws IOException{
        if(!current(epoch))return;
        try{int n=decoder.drain();synchronized(this){displaySubmissions+=n;}}
        catch(IOException e){if(current(epoch))throw e;}
        catch(RuntimeException e){if(current(epoch))throw e;}
    }
    private static final class Packet {
        final byte[] header,data;final long ptsUs,generation;
        Packet(byte[] h,byte[] d,long p,long g){header=h;data=d;ptsUs=p;generation=g;}
        void erase(){Arrays.fill(header,(byte)0);Arrays.fill(data,(byte)0);}
    }
}
