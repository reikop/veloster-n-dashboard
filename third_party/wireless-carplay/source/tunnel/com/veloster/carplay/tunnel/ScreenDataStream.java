/* SPDX-License-Identifier: GPL-3.0-only. ScreenStream wire profile; see NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One verified screen stream, one TCP connection, one key/counter. No wildcard listener.
 * close() only cancels sockets; the owning worker erases the decoder and releases the sink.
 * Sinks must be nonblocking (enqueue to a bounded codec worker); never wait on this worker. */
public final class ScreenDataStream implements Closeable {
    public interface Sink extends ScreenDecoder.Sink {
        /** Exactly once on the stream worker after receive/decoder shutdown. Null means local cancellation. */
        void closed(IOException failure);
    }
    /** Explicit protocol retirement is distinct from application close or failure.
     * A false return means a prior stop already won and cannot be relabeled normal. */
    public interface RetirableSink extends Sink {
        boolean retire();
        boolean awaitStopped(long millis)throws InterruptedException;
    }
    public interface HeaderSink extends Sink,ScreenDecoder.HeaderSink {}
    public interface RetirableHeaderSink extends HeaderSink,RetirableSink {}
    private static final long IO_NANOS=30000000000L;
    private final Object lock=new Object();
    private final IapDataTunnel.Binding binding;
    private final IapDataTunnel.SessionLease lease;
    private final Sink sink;
    private final String connectionId;
    private final ScreenDecoder decoder;
    private final ServerSocket server;
    private final Thread worker;
    private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean closed,active,retiring;
    private Socket socket;
    private final long created=System.nanoTime();
    private volatile long activated;

    public ScreenDataStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,
                            byte[] verifiedSecret,String connectionId,final Sink sink)throws IOException{
        if(binding==null||lease==null||sink==null)throw new NullPointerException();
        this.binding=binding;this.lease=lease;this.sink=sink;this.connectionId=connectionId;check();
        byte[] key=TunnelCrypto.receiveKey(verifiedSecret,connectionId);
        try{decoder=new ScreenDecoder(key,new ScreenDecoder.HeaderSink(){
            public void configuration(byte[] data)throws IOException{check();sink.configuration(data);check();}
            public void configuration(byte[] header,byte[] data)throws IOException{
                check();if(sink instanceof ScreenDecoder.HeaderSink)((ScreenDecoder.HeaderSink)sink).configuration(header,data);else sink.configuration(data);check();
            }
            public void frame(byte[] header,byte[] data)throws IOException{check();sink.frame(header,data);check();}
        });}finally{Arrays.fill(key,(byte)0);}
        ServerSocket bound=null;
        try{
            bound=new ServerSocket();bound.setReuseAddress(false);bound.bind(new InetSocketAddress(binding.localAddress,0),1);
            bound.setSoTimeout(250);check();
        }catch(IOException e){decoder.close();if(bound!=null)bound.close();throw e;}
         catch(RuntimeException e){decoder.close();if(bound!=null)bound.close();throw e;}
        server=bound;worker=new Thread(new Runnable(){public void run(){receive();}},"vn-carplay-screen");worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException e){server.close();decoder.close();throw e;}
    }
    public int dataPort(){return server.getLocalPort();}
    public String connectionId(){return connectionId;}
    /** Only the authenticated exact-owner TEARDOWN path uses this; close remains a parent stop. */
    public boolean retire(){
        synchronized(lock){
            if(closed||retiring)return false;
            // Cancel future callbacks before retiring the codec. A callback already
            // in flight may now report its sink was closed; that is cancellation,
            // not a newly observed transport failure. close/finally takes this lock
            // so the exact sink's retirement decision is published first.
            retiring=true;
            if(sink instanceof RetirableSink&&!((RetirableSink)sink).retire()){close();return false;}
            close();return true;
        }
    }
    public void setupResponseSent()throws IOException{
        check();synchronized(lock){if(closed||active)throw new IOException("Screen activation state");activated=System.nanoTime();active=true;lock.notifyAll();}
    }
    private void check()throws IOException{
        if(closed||retiring)throw new IOException("Screen closed");lease.checkCurrent(binding);
        NetworkInterface local=NetworkInterface.getByInetAddress(binding.localAddress);
        if(local==null||!binding.interfaceName.equals(local.getName()))throw new IOException("Screen interface changed");
    }
    private static boolean same(InetAddress a,InetAddress b){
        return a.equals(b)&&(!(a instanceof Inet6Address)||!a.isLinkLocalAddress()||
            b instanceof Inet6Address&&((Inet6Address)a).getScopeId()==((Inet6Address)b).getScopeId());
    }
    private void receive(){
        byte[] buffer=new byte[16384];IOException failure=null;
        try{
            while(!active){
                check();if(System.nanoTime()-created>=IO_NANOS)throw new IOException("Screen SETUP flush timeout");
                synchronized(lock){if(!active&&!closed)lock.wait(250);}
            }
            while(lease instanceof IapDataTunnel.MediaLease&&!((IapDataTunnel.MediaLease)lease).mediaAllowed(binding)){
                check();if(System.nanoTime()-activated>=IO_NANOS)throw new IOException("Screen phone identity timeout");
                synchronized(lock){if(!closed)lock.wait(50);}
            }
            while(true){
                check();if(System.nanoTime()-activated>=IO_NANOS)throw new IOException("Screen accept timeout");
                Socket candidate;try{candidate=server.accept();}catch(SocketTimeoutException e){continue;}
                boolean accepted=false;
                try{
                    if(!same(candidate.getInetAddress(),binding.remoteAddress)||!same(candidate.getLocalAddress(),binding.localAddress))continue;
                    candidate.setSoTimeout(250);candidate.setTcpNoDelay(true);check();
                    synchronized(lock){if(closed)throw new IOException("Screen closed during accept");socket=candidate;accepted=true;}
                }finally{if(!accepted)candidate.close();}
                if(accepted)break;
            }
            server.close(); // A second TCP connection cannot reset the frame nonce.
            InputStream input=socket.getInputStream();long partialSince=0;
            while(true){
                check();if(partialSince!=0&&System.nanoTime()-partialSince>=IO_NANOS)throw new IOException("Screen partial packet timeout");
                int n;try{n=input.read(buffer);}catch(SocketTimeoutException e){continue;}
                if(n<0){decoder.endOfInput();throw new EOFException("Screen peer disconnected");}
                if(n==0)continue;check();long before=decoder.completedPackets();long now=System.nanoTime();decoder.feed(buffer,0,n);
                if(!decoder.hasPartialInput())partialSince=0;
                else if(partialSince==0||decoder.completedPackets()!=before)partialSince=now;
            }
        }catch(IOException e){if(!closed&&!retiring)failure=e;}
         catch(InterruptedException e){Thread.currentThread().interrupt();if(!closed)failure=new IOException("Screen interrupted",e);}
         catch(RuntimeException e){failure=new IOException("Screen callback/receive failed",e);}
         catch(LinkageError e){failure=new IOException("Screen API unavailable",e);}
        finally{
            close();decoder.close();Arrays.fill(buffer,(byte)0);
            try{sink.closed(failure);}finally{stopped.countDown();}
        }
    }
    /** Never takes the decoder lock or calls a sink while holding a session/stream lock. */
    public void close(){
        Socket owned;synchronized(lock){if(closed)return;closed=true;owned=socket;lock.notifyAll();}
        try{server.close();}catch(IOException ignored){}if(owned!=null)try{owned.close();}catch(IOException ignored){}
        worker.interrupt();
    }
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0||millis>86400000)throw new IllegalArgumentException("Screen wait limit");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        if(Thread.currentThread()==worker||!stopped.await(millis,TimeUnit.MILLISECONDS))return false;
        return !(sink instanceof RetirableSink)||((RetirableSink)sink).awaitStopped(Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
    }
}
