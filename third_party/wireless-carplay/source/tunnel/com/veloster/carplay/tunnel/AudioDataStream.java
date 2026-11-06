/* SPDX-License-Identifier: GPL-3.0-only. See NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Receive-only audio data UDP endpoint bound to one verified session's local interface.
 * Optional negotiated peer control port supports bounded audio resend requests.
 * Retransmissions are AEAD-verified on the data worker. Optional PT84 clock anchors
 * require the negotiated peer endpoint plus current authenticated media; the
 * plain UDP sync packet itself has no cryptographic authenticator.
 * Sink takes each packet's ownership only on normal return and must enqueue promptly.
 * close() cancels without taking the decoder lock or calling the sink under an owner lock.
 */
public final class AudioDataStream implements Closeable {
    public interface Sink {
        void packet(AudioPacketDecoder.Packet packet)throws IOException;
        void closed(IOException failure);
    }
    public interface RecordableSink extends Sink {
        /** Nonblocking session-start permission; local presentation permission is separate. */
        void record()throws IOException;
        /** Nonblocking logical retirement before partial TEARDOWN is acknowledged. */
        void retire();
    }
    public interface Flush {
        /** Last played packet's uint32 RTP timestamp, null if none has played.
         * A timeout does not cancel the underlying request. */
        Long await(long millis)throws IOException,InterruptedException;
        /** Release only this operation's playback gate after its response was sent. */
        void responseSent()throws IOException;
    }
    public interface FlushableSink extends RecordableSink {
        boolean canFlush();
        Flush beginFlush(long timestamp)throws IOException;
    }
    public interface SynchronizedSink extends FlushableSink {
        /** Nonblocking peer-bound sync observation, not a cryptographic assertion. */
        boolean synchronize(AudioSyncPacket packet)throws IOException;
    }
    private final Object lock=new Object();
    private final IapDataTunnel.Binding binding;
    private final IapDataTunnel.SessionLease lease;
    private final Sink sink;
    private final AudioPacketDecoder decoder;
    private final DatagramSocket socket,control;
    private final Thread worker,controlWorker;
    private final int remoteControlPort;
    private final AudioResend resend=new AudioResend();
    private static final class Repair {final byte[] wire;final AudioResend.Receipt receipt;Repair(byte[] wire,AudioResend.Receipt receipt){this.wire=wire;this.receipt=receipt;}}
    private final ArrayDeque<Repair> repairs=new ArrayDeque<Repair>();
    private int repairBytes;
    private volatile long resendRequests,repaired;
    private final CountDownLatch stopped=new CountDownLatch(1);
    private final CountDownLatch controlStopped=new CountDownLatch(1);
    private volatile boolean closed,active;
    private volatile long accepted,rejected,foreign,ignoredControl;
    private volatile IOException controlFailure;
    private volatile boolean authenticatedAudio;
    private final long created=System.nanoTime();
    public AudioDataStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,
                           byte[] verifiedSecret,String connectionId,Sink sink)throws IOException{
        this(binding,lease,verifiedSecret,connectionId,sink,0);
    }
    public AudioDataStream(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease lease,
                           byte[] verifiedSecret,String connectionId,Sink sink,int remoteControlPort)throws IOException{
        if(remoteControlPort<0||remoteControlPort>65535)throw new IOException("Audio control port bounds");
        this.remoteControlPort=remoteControlPort;
        if(binding==null||lease==null||sink==null)throw new NullPointerException();
        this.binding=binding;this.lease=lease;this.sink=sink;check();
        byte[] key=TunnelCrypto.receiveKey(verifiedSecret,connectionId);
        try{decoder=new AudioPacketDecoder(key);}finally{Arrays.fill(key,(byte)0);}
        DatagramSocket bound=null,controlBound=null;
        try{
            bound=new DatagramSocket(null);bound.setReuseAddress(false);
            bound.bind(new InetSocketAddress(binding.localAddress,0));bound.setSoTimeout(remoteControlPort==0?250:5);
            bound.setReceiveBufferSize(512*1024);check();
            controlBound=new DatagramSocket(null);controlBound.setReuseAddress(false);
            controlBound.bind(new InetSocketAddress(binding.localAddress,0));controlBound.setSoTimeout(250);check();
        }catch(IOException e){decoder.close();if(bound!=null)bound.close();if(controlBound!=null)controlBound.close();throw e;}
         catch(RuntimeException e){decoder.close();if(bound!=null)bound.close();if(controlBound!=null)controlBound.close();throw e;}
        socket=bound;control=controlBound;worker=new Thread(new Runnable(){public void run(){receive();}},"vn-carplay-audio-rx");worker.setDaemon(true);
        controlWorker=new Thread(new Runnable(){public void run(){receiveControl();}},"vn-carplay-audio-control");controlWorker.setDaemon(true);
        try{controlWorker.start();worker.start();}catch(RuntimeException e){close();decoder.close();throw e;}
    }
    public int dataPort(){return socket.getLocalPort();}
    public int controlPort(){return control.getLocalPort();}
    public long resendRequests(){return resendRequests;}
    public long repairedPackets(){return repaired;}
    public long ignoredControlDatagrams(){return ignoredControl;}
    public long acceptedPackets(){return accepted;}
    public long rejectedPackets(){return rejected;}
    public long foreignPackets(){return foreign;}
    public void record()throws IOException{
        check();if(!active||!(sink instanceof RecordableSink))throw new IOException("Audio RECORD unavailable");
        ((RecordableSink)sink).record();
    }
    public boolean canFlush()throws IOException{check();return active&&sink instanceof FlushableSink&&((FlushableSink)sink).canFlush();}
    public Flush beginFlush(long timestamp)throws IOException{
        check();if(!active||!(sink instanceof FlushableSink))throw new IOException("Audio FLUSH unavailable");
        final Flush operation=((FlushableSink)sink).beginFlush(timestamp);
        return new Flush(){
            public Long await(long millis)throws IOException,InterruptedException{
                check();Long result=operation.await(millis);check();
                if(result!=null&&(result<0||result>0xffffffffL))throw new IOException("Audio FLUSH result bounds");return result;
            }
            public void responseSent()throws IOException{check();operation.responseSent();}
        };
    }
    /** Control worker only, outside the verified-session monitor. close() deliberately
     * never calls output callbacks while a parent shutdown may hold that monitor. */
    public void retireOutput(){if(sink instanceof RecordableSink)((RecordableSink)sink).retire();}
    public void setupResponseSent()throws IOException{
        check();synchronized(lock){if(closed||active)throw new IOException("Audio activation state");active=true;lock.notifyAll();}
    }
    private void check()throws IOException{
        checkLease();
        NetworkInterface local=NetworkInterface.getByInetAddress(binding.localAddress);
        if(local==null||!binding.interfaceName.equals(local.getName()))throw new IOException("Audio interface changed");
    }
    private void checkLease()throws IOException{if(closed)throw new IOException("Audio closed");lease.checkCurrent(binding);}
    private boolean peer(InetAddress address){
        InetAddress expected=binding.remoteAddress;
        return expected.equals(address)&&(!(expected instanceof Inet6Address)||!expected.isLinkLocalAddress()||
            address instanceof Inet6Address&&((Inet6Address)expected).getScopeId()==((Inet6Address)address).getScopeId());
    }
    private void receive(){
        byte[] buffer=new byte[65536];IOException failure=null;
        try{
            while(!active){
                check();if(System.nanoTime()-created>=30000000000L)throw new IOException("Audio SETUP flush timeout");
                synchronized(lock){if(!active&&!closed)lock.wait(250);}
            }
            long identityWait=System.nanoTime();
            while(lease instanceof IapDataTunnel.MediaLease&&!((IapDataTunnel.MediaLease)lease).mediaAllowed(binding)){
                check();if(System.nanoTime()-identityWait>=30000000000L)throw new IOException("Audio phone identity timeout");
                synchronized(lock){if(!closed)lock.wait(50);}
            }
            long lastInterfaceCheck=System.nanoTime();DatagramPacket datagram=new DatagramPacket(buffer,buffer.length);
            while(true){
                checkLease();long now=System.nanoTime();
                if(now-lastInterfaceCheck>=250000000L){check();lastInterfaceCheck=now;}
                // Drain a bounded batch before blocking on unrelated data. One
                // 5ms socket wait per repair wastes the same 40ms playback budget.
                for(int n=0;n<16;n++){
                    Repair repair;
                    synchronized(repairs){repair=repairs.poll();if(repair!=null)repairBytes-=repair.wire.length;}
                    if(repair==null)break;
                    try{accept(repair.wire,repair.wire.length,repair.receipt);}finally{Arrays.fill(repair.wire,(byte)0);}
                }
                requestRepair();
                datagram.setLength(buffer.length);
                try{socket.receive(datagram);}catch(SocketTimeoutException e){continue;}
                checkLease();if(!peer(datagram.getAddress())){foreign++;continue;}
                accept(buffer,datagram.getLength(),null);
            }
        }catch(IOException e){if(!closed)failure=e;}
         catch(InterruptedException e){Thread.currentThread().interrupt();if(!closed)failure=new IOException("Audio interrupted",e);}
         catch(RuntimeException e){failure=new IOException("Audio receive/sink failed",e);}
         catch(LinkageError e){failure=new IOException("Audio API unavailable",e);}
        finally{
            close();decoder.close();resend.clear();Arrays.fill(buffer,(byte)0);
            if(failure==null)failure=controlFailure;
            try{sink.closed(failure);}finally{stopped.countDown();}
        }
    }
    private void accept(byte[] bytes,int length,AudioResend.Receipt receipt)throws IOException{
        boolean repair=receipt!=null;
        AudioPacketDecoder.Packet packet;
        if(repair){
            if(length<37)return;
            long counter=0;for(int i=0;i<8;i++)counter|=(long)(bytes[length-8+i]&255)<<(8*i);
            // A cheap negative filter, never authentication: avoid consuming a
            // decoder replay-window slot for a nonce unrelated to the request.
            if(!resend.matches(receipt,((bytes[2]&255)<<8)|(bytes[3]&255),counter)){rejected++;return;}
        }
        try{packet=decoder.decode(bytes,length);}catch(IOException e){rejected++;return;}
        boolean handedOff=false;
        try{check();
            if(repair&&!resend.matches(receipt,packet.sequence,packet.nonce)){rejected++;return;}
            resend.received(packet.sequence,packet.nonce,System.nanoTime());sink.packet(packet);handedOff=true;authenticatedAudio=true;accepted++;if(repair)repaired++;}
        finally{if(!handedOff)packet.close();}
    }
    private void requestRepair()throws IOException{
        if(remoteControlPort==0)return;checkLease();byte[] request=resend.next(System.nanoTime());
        if(request!=null){try{control.send(new DatagramPacket(request,request.length,binding.remoteAddress,remoteControlPort));resendRequests++;}
            catch(IOException unavailable){checkLease();/* An optional resend failure cannot retire valid media. */}}
    }
    private void receiveControl(){
        byte[] buffer=new byte[65536];
        try{
            while(!active){check();synchronized(lock){if(!active&&!closed)lock.wait(250);}}
            while(true){
                checkLease();DatagramPacket p=new DatagramPacket(buffer,buffer.length);
                try{control.receive(p);}catch(SocketTimeoutException e){check();continue;}
                long arrived=System.nanoTime();
                checkLease();
                if(!peer(p.getAddress())){foreign++;continue;}
                int n=p.getLength();
                if(remoteControlPort!=0&&p.getPort()==remoteControlPort&&n==20&&(buffer[1]&255)==0xd4){
                    if(!authenticatedAudio||!(sink instanceof SynchronizedSink)||
                        lease instanceof IapDataTunnel.MediaLease&&!((IapDataTunnel.MediaLease)lease).mediaAllowed(binding)){ignoredControl++;continue;}
                    AudioSyncPacket sync;try{sync=AudioSyncPacket.parse(buffer,n);}catch(IOException invalid){ignoredControl++;continue;}
                    checkLease();if(!((SynchronizedSink)sink).synchronize(sync))ignoredControl++;continue;
                }
                if(remoteControlPort==0||p.getPort()!=remoteControlPort||n<41||n>AudioPacketDecoder.MAX_PACKET+4||buffer[0]!=(byte)0x80||(buffer[1]&127)!=86){ignoredControl++;continue;}
                int seq=((buffer[6]&255)<<8)|(buffer[7]&255);
                AudioResend.Receipt receipt=resend.receipt(seq,arrived);if(receipt==null){ignoredControl++;continue;}
                synchronized(repairs){
                    if(closed||repairs.size()>=16||repairBytes+n-4>262144){ignoredControl++;continue;}
                    byte[] inner=Arrays.copyOfRange(buffer,4,n);repairs.add(new Repair(inner,receipt));repairBytes+=inner.length;
                }
            }
        }catch(IOException e){if(!closed)controlFailure=e;}
         catch(InterruptedException e){Thread.currentThread().interrupt();if(!closed)controlFailure=new IOException("Audio control interrupted",e);}
         catch(RuntimeException e){controlFailure=new IOException("Audio control failed",e);}
         catch(LinkageError e){controlFailure=new IOException("Audio control API unavailable",e);}
        finally{close();Arrays.fill(buffer,(byte)0);controlStopped.countDown();}
    }
    public void close(){synchronized(lock){if(closed)return;closed=true;lock.notifyAll();}socket.close();control.close();worker.interrupt();controlWorker.interrupt();
        synchronized(repairs){for(Repair repair:repairs)Arrays.fill(repair.wire,(byte)0);repairs.clear();repairBytes=0;}}
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0||millis>86400000)throw new IllegalArgumentException("Audio wait limit");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        return Thread.currentThread()!=worker&&Thread.currentThread()!=controlWorker&&stopped.await(millis,TimeUnit.MILLISECONDS)&&
            controlStopped.await(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
}
