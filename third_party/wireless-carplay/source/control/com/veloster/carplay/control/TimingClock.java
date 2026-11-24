/* SPDX-License-Identifier: GPL-3.0-only. RTCP timing profile adapted from DiPlay NtpClock.kt. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-owned RTCP PT210/211 clock, separate from the Android system clock.
 * Unsynchronized until two matching replies yield a valid low-delay sample. NTP64 wraps modulo 2^64. */
public final class TimingClock implements Closeable,ClockReference.Source {
    private static final double TWO32=4294967296.0;
    private final PairVerify.VerifiedSession session;
    private final PairVerify.Context context;
    private final DatagramSocket socket;
    private final int peerPort;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean active;
    private final long anchorMono=System.nanoTime(),anchorNtp=fromMillis(System.currentTimeMillis());
    private long correction,pending,lastUpdate,generation;private boolean hasPending,synced;
    private final double[] delays=new double[8];private int delayIndex,picks;
    private double bestDelay=Double.POSITIVE_INFINITY,bestOffset;
    public TimingClock(PairVerify.VerifiedSession session,int peerPort)throws IOException{
        if(session==null||peerPort<0||peerPort>65535)throw new IllegalArgumentException("Timing peer port");
        this.session=session;context=session.context();this.peerPort=peerPort;Arrays.fill(delays,Double.POSITIVE_INFINITY);
        DatagramSocket opened=new DatagramSocket(null);
        try{opened.bind(new InetSocketAddress(context.localAddress,0));opened.setSoTimeout(100);session.assertCurrent();}
        catch(IOException e){opened.close();throw e;}catch(RuntimeException e){opened.close();throw e;}
        socket=opened;Thread worker=new Thread(new Runnable(){public void run(){runClock();}},"vn-carplay-timing");worker.setDaemon(true);
        try{worker.start();}catch(RuntimeException e){close();stopped.countDown();throw e;}
    }
    public int port(){return socket.getLocalPort();}
    public void setupResponseSent()throws IOException{session.assertCurrent();if(closed.get()||active)throw new IOException("Timing SETUP state");active=true;}
    private boolean fresh(long now){long age=now-lastUpdate;return active&&!closed.get()&&synced&&age>=0&&age<=ClockReference.MAX_AGE_NANOS;}
    public synchronized boolean isSynchronized(){return fresh(System.nanoTime());}
    /** null means no recent synchronized reference. Never substitute local arrival
     * time for a requested peer-clock deadline. Caller must retain session ownership. */
    public ClockReference reference()throws IOException{
        session.assertCurrent();
        synchronized(this){long now=System.nanoTime();if(!fresh(now))return null;
            return new ClockReference(this,anchorNtp+fromNanos(now-anchorMono)+correction,now,lastUpdate,generation);}
    }
    /** A clock step invalidates deadlines projected before that step. */
    public boolean isCurrent(ClockReference reference)throws IOException{
        session.assertCurrent();synchronized(this){long now=System.nanoTime();
            return reference!=null&&reference.owner==this&&fresh(now)&&reference.generation==generation&&reference.isFresh(now);}
    }
    /** Raw unsigned NTP64 bit pattern. Consumers must preserve all 64 bits. */
    public synchronized long ntpNow(){return anchorNtp+fromNanos(System.nanoTime()-anchorMono)+correction;}
    private static long fromMillis(long millis){long seconds=millis/1000+2208988800L;return (seconds<<32)+((millis%1000)<<32)/1000;}
    private static long fromNanos(long nanos){return ((nanos/1000000000L)<<32)+((nanos%1000000000L)<<32)/1000000000L;}
    private static long get(byte[] b,int at){long n=0;for(int i=0;i<8;i++)n=(n<<8)|(b[at+i]&255);return n;}
    private static void put(byte[] b,int at,long n){for(int i=7;i>=0;i--){b[at+i]=(byte)n;n>>>=8;}}
    private static byte[] packet(int type){byte[] b=new byte[32];b[0]=(byte)128;b[1]=(byte)type;b[3]=7;return b;}
    private void send(byte[] b,int port)throws IOException{session.assertCurrent();if(closed.get())throw new IOException("Timing closed");socket.send(new DatagramPacket(b,b.length,context.remoteAddress,port));}
    private void request()throws IOException{
        byte[] b=packet(210);long t1=ntpNow();synchronized(this){pending=t1;hasPending=true;}put(b,24,t1);send(b,peerPort);
    }
    private synchronized void response(byte[] b,long t4){
        long t1=get(b,8);if(!hasPending||t1!=pending)return;hasPending=false;
        long t2=get(b,16),t3=get(b,24);
        double elapsed=(t4-t1)/TWO32,processing=(t3-t2)/TWO32;
        if(elapsed<0||elapsed>5||processing<0||processing>elapsed)return;
        double rtt=elapsed-processing;
        if(rtt<0||rtt>5)return;
        long now=System.nanoTime();
        if(synced&&!fresh(now)){synced=false;picks=0;bestDelay=Double.POSITIVE_INFINITY;Arrays.fill(delays,Double.POSITIVE_INFINITY);delayIndex=0;}
        double offset=((t2-t1)/TWO32+(t3-t4)/TWO32)*0.5;
        if(rtt<bestDelay){bestDelay=rtt;bestOffset=offset;}if(++picks<2)return;
        boolean use=true;for(double delay:delays)if(bestDelay>delay)use=false;
        delays[delayIndex]=bestDelay;delayIndex=(delayIndex+1)%delays.length;
        if(use){boolean step=!synced||Math.abs(bestOffset)>0.128;
            correction+=Math.round(bestOffset*TWO32*(step?1.0:0.125));synced=true;lastUpdate=now;
            if(step){generation++;Arrays.fill(delays,Double.POSITIVE_INFINITY);delayIndex=0;hasPending=false;}}
        picks=0;bestDelay=Double.POSITIVE_INFINITY;
    }
    private void runClock(){
        try{
            long next=0;byte[] buffer=new byte[33];
            while(!closed.get()){
                session.assertCurrent();if(!active){Thread.sleep(100);continue;}
                if(peerPort!=0&&(next==0||System.nanoTime()-next>=0)){request();next=System.nanoTime()+1000000000L;}
                DatagramPacket datagram=new DatagramPacket(buffer,buffer.length);
                try{socket.receive(datagram);}catch(SocketTimeoutException timeout){continue;}
                long received=ntpNow();
                if(!datagram.getAddress().equals(context.remoteAddress)||peerPort!=0&&datagram.getPort()!=peerPort||datagram.getLength()!=32||
                    buffer[0]!=(byte)128||buffer[2]!=0||buffer[3]!=7)continue;
                session.assertCurrent();int type=buffer[1]&255;
                if(type==210){byte[] reply=packet(211);put(reply,8,get(buffer,24));put(reply,16,received);put(reply,24,ntpNow());send(reply,datagram.getPort());}
                else if(type==211&&peerPort!=0)response(buffer,received);
            }
        }catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}
         catch(RuntimeException ignored){}
         finally{close();session.close();stopped.countDown();}
    }
    public void close(){if(closed.compareAndSet(false,true))socket.close();}
    public boolean awaitStopped(long millis)throws InterruptedException{return stopped.await(millis,TimeUnit.MILLISECONDS);}
}
