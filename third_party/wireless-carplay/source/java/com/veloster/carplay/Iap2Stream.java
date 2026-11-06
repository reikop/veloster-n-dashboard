/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md. */
package com.veloster.carplay;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real stream adapter for an already connected RFCOMM socket or authenticated tunnel.
 * The owner must close that socket to interrupt blocking reads/writes. No Android
 * Bluetooth stack assumption is hidden here: caller owns pairing and socket creation.
 * No payload logging. One reader and one protocol/writer thread, both bounded.
 */
public final class Iap2Stream implements Iap2MessageSession {
    private static final byte[] EOF=new byte[0];
    private static final long EPOCH_NANOS=System.nanoTime();
    private final InputStream input;
    private final OutputStream output;
    private final Closeable owner;
    private final Iap2Link link;
    private final Iap2Csm csm=new Iap2Csm();
    private final ArrayBlockingQueue<byte[]> incoming=new ArrayBlockingQueue<byte[]>(16);
    private final ArrayBlockingQueue<Send> sending=new ArrayBlockingQueue<Send>(16);
    private final ArrayBlockingQueue<Iap2Csm.Frame> received=new ArrayBlockingQueue<Iap2Csm.Frame>(64);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Object ready=new Object();
    private volatile boolean open;
    private volatile IOException failure;
    private final Thread reader,worker;
    private static final class Send {
        final byte[] bytes;
        final CountDownLatch sent=new CountDownLatch(1);
        int offset;
        volatile boolean flushed;
        Send(byte[] b){bytes=b;}
    }
    public Iap2Stream(InputStream input,OutputStream output,Closeable owner) {
        this(input,output,owner,Iap2Link.Mode.BLUETOOTH_BOOTSTRAP);
    }
    /** Caller owns authentication and post-SETUP activation. No Bluetooth fallback. */
    public static Iap2Stream openTunnel(InputStream input,OutputStream output,Closeable owner) {
        return new Iap2Stream(input,output,owner,Iap2Link.Mode.WIFI_TUNNEL);
    }
    private Iap2Stream(InputStream input,OutputStream output,Closeable owner,Iap2Link.Mode mode) {
        if(input==null || output==null || owner==null)throw new NullPointerException();
        this.input=input;this.output=output;this.owner=owner;this.link=new Iap2Link(mode);
        reader=new Thread(new Runnable(){public void run(){readLoop();}},"vn-iap2-reader");
        worker=new Thread(new Runnable(){public void run(){protocolLoop();}},"vn-iap2-link");
        reader.setDaemon(true);worker.setDaemon(true);reader.start();worker.start();
    }
    public boolean isClosed(){return closed.get();}
    public IOException failure(){return failure;}
    public boolean awaitReady(long timeoutMillis) throws IOException,InterruptedException {
        deadlineCheck(timeoutMillis);long deadline=millis()+timeoutMillis;
        synchronized(ready) {
            while(!open && !closed.get()) {
                long left=deadline-millis();if(left<=0)return false;ready.wait(left);
            }
        }
        if(failure!=null)throw new IOException("iAP2 link failed",failure);
        return open && !closed.get();
    }
    /** Returns after every fragment of this frame reached the output stream and was flushed.
     * This is not an acknowledgement of application processing or successful authentication.
     */
    public void send(Iap2Csm.Frame frame,long timeoutMillis) throws IOException,InterruptedException {
        deadlineCheck(timeoutMillis);long deadline=millis()+timeoutMillis;
        if(!open || closed.get())throw new IOException("iAP2 stream not open");
        Send request=new Send(frame.encode());
        if(!sending.offer(request,timeoutMillis,TimeUnit.MILLISECONDS))throw new IOException("iAP2 send queue full");
        if(closed.get()){sending.remove(request);Arrays.fill(request.bytes,(byte)0);throw new IOException("iAP2 stream closed",failure);}
        long left=Math.max(0,deadline-millis());
        if(!request.sent.await(left,TimeUnit.MILLISECONDS)) {
            fail(new IOException("iAP2 send timed out"));throw new IOException("iAP2 send timed out");
        }
        if(!request.flushed)throw new IOException("iAP2 stream closed before frame flush",failure);
    }
    public Iap2Csm.Frame receive(long timeoutMillis) throws IOException,InterruptedException {
        deadlineCheck(timeoutMillis);long deadline=millis()+timeoutMillis;
        while(true) {
            Iap2Csm.Frame frame=received.poll();if(frame!=null)return frame;
            if(closed.get())break;
            long left=deadline-millis();
            frame=received.poll(Math.max(0,Math.min(left,100)),TimeUnit.MILLISECONDS);
            if(frame!=null)return frame;if(left<=0)return null;
        }
        if(failure!=null)throw new IOException("iAP2 stream failed",failure);
        return null;
    }
    private static void deadlineCheck(long n) {
        if(n<0 || n>86400000)throw new IllegalArgumentException("Invalid operation timeout");
    }
    private static long millis(){return (System.nanoTime()-EPOCH_NANOS)/1000000L;}
    private void readLoop() {
        byte[] b=new byte[4096];
        try {
            while(!closed.get()) {
                int n=input.read(b);
                if(n<0){incoming.put(EOF);return;}
                if(n==0)continue;
                incoming.put(Arrays.copyOf(b,n));
            }
        } catch(InterruptedException e){if(!closed.get())fail(new IOException("iAP2 reader interrupted"));Thread.currentThread().interrupt();}
        catch(IOException e){if(!closed.get())fail(new IOException("iAP2 read failed",e));}
        catch(RuntimeException e){if(!closed.get())fail(new IOException("iAP2 reader failed",e));}
        catch(LinkageError e){if(!closed.get())fail(new IOException("iAP2 stream API unavailable",e));}
        finally {Arrays.fill(b,(byte)0);}
    }
    private void protocolLoop() {
        Send current=null;
        try {
            link.start(millis());
            while(!closed.get()) {
                long now=millis();link.tick(now);
                byte[] bytes=incoming.poll();
                if(bytes==EOF)break;
                if(bytes!=null){link.feed(bytes,millis());Arrays.fill(bytes,(byte)0);}
                if(link.state()==Iap2Link.State.CLOSED) {
                    if(link.failure()!=null)throw new IOException(link.failure());break;
                }
                if(link.state()==Iap2Link.State.OPEN && !open) {
                    synchronized(ready){open=true;ready.notifyAll();}
                }
                if(current==null)current=sending.poll();
                if(current!=null && link.writable()) {
                    while(current.offset<current.bytes.length && link.queuedCount()<64) {
                        int end=Math.min(current.bytes.length,current.offset+link.maximumPayload());
                        link.send(Iap2Link.CONTROL_SESSION,Arrays.copyOfRange(current.bytes,current.offset,end),millis());
                        current.offset=end;
                    }
                }
                Iap2Link.Event event;
                while((event=link.poll())!=null) {
                    csm.feed(event.payload);Arrays.fill(event.payload,(byte)0);Iap2Csm.Frame frame;
                    while((frame=csm.poll())!=null)if(!received.offer(frame))throw new IOException("Undrained iAP2 receive queue");
                }
                byte[] wire=link.drainOutput();
                try{if(wire.length>0){output.write(wire);output.flush();}}
                finally{Arrays.fill(wire,(byte)0);}
                if(current!=null && current.offset==current.bytes.length && link.queuedCount()==0) {
                    Arrays.fill(current.bytes,(byte)0);current.flushed=true;current.sent.countDown();current=null;
                }
                if(bytes==null && sending.isEmpty())Thread.sleep(5);
            }
        } catch(InterruptedException e){if(!closed.get())fail(new IOException("iAP2 worker interrupted"));Thread.currentThread().interrupt();}
        catch(IOException e){if(!closed.get())fail(e);}
        catch(RuntimeException e){if(!closed.get())fail(new IOException("iAP2 worker failed",e));}
        catch(LinkageError e){if(!closed.get())fail(new IOException("iAP2 stream API unavailable",e));}
        finally {
            if(current!=null){Arrays.fill(current.bytes,(byte)0);current.sent.countDown();}
            shutdown();link.close();csm.clear();
        }
    }
    private void fail(IOException error){if(!closed.get())failure=error;shutdown();}
    private void shutdown() {
        if(!closed.compareAndSet(false,true))return;
        open=false;
        try{owner.close();}catch(IOException e){if(failure==null)failure=new IOException("iAP2 socket close failed",e);}
        catch(RuntimeException e){if(failure==null)failure=new IOException("iAP2 socket close failed",e);}
        catch(LinkageError e){if(failure==null)failure=new IOException("iAP2 socket close API unavailable",e);}
        synchronized(ready){ready.notifyAll();}
        reader.interrupt();worker.interrupt();
        Send item;while((item=sending.poll())!=null){Arrays.fill(item.bytes,(byte)0);item.sent.countDown();}
        byte[] bytes;while((bytes=incoming.poll())!=null)Arrays.fill(bytes,(byte)0);
    }
    public void close() throws IOException {
        shutdown();
        received.clear();
        try {
            if(Thread.currentThread()!=reader)reader.join(2000);
            if(Thread.currentThread()!=worker)worker.join(2000);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted during iAP2 close",e);}
        if((reader.isAlive() && Thread.currentThread()!=reader) || (worker.isAlive() && Thread.currentThread()!=worker))
            throw new IOException("Owner did not unblock iAP2 I/O on close");
    }
    /** Positive proof of both transport workers, also after a bounded close timed out. */
    public boolean awaitStopped(long timeout)throws InterruptedException{
        if(timeout<0||timeout>86400000)throw new IllegalArgumentException("iAP2 drain timeout");
        long end=System.nanoTime()+timeout*1000000L;
        if(timeout>0&&Thread.currentThread()!=reader)reader.join(timeout);
        long left=(end-System.nanoTime())/1000000L;
        if(left>0&&Thread.currentThread()!=worker)worker.join(left);
        return !reader.isAlive()&&!worker.isAlive();
    }
}
