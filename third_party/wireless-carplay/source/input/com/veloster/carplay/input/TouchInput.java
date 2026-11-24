/* SPDX-License-Identifier: GPL-3.0-only. Owned two-contact tracker and nonblocking UI queue. */
package com.veloster.carplay.input;
import com.veloster.carplay.control.EncryptedEvents;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** UI producer methods never perform network IO. One worker serializes reports/ACKs.
 * configure/disable discard old queued gestures and send a release before new input.
 * close requests a final release; awaitStopped confirms completion, not close(). */
public final class TouchInput implements Closeable {
    public interface Sink extends Closeable {void send(byte[] report)throws IOException;}
    public interface GuardedSink extends Sink {void send(byte[] report,EncryptedEvents.SendGuard guard)throws IOException;}
    private static final int DOWN=0,UP=1,MOVE=2,CANCEL=3,POINTER_DOWN=5,POINTER_UP=6;
    private final TouchHid hid;private final Sink sink;private final Object lock=new Object();
    private final ArrayDeque<Report> queue=new ArrayDeque<Report>();private final CountDownLatch stopped=new CountDownLatch(1);
    private final int[] pointers={-1,-1},x=new int[2],y=new int[2];
    private float left,top,right,bottom;private int rotation;private boolean configured,enabled,closing;
    private volatile IOException failure;
    private long generation;
    private static final class Report {final byte[] bytes;final boolean move;final long generation;Report(byte[] b,boolean m,long g){bytes=b;move=m;generation=g;}}
    public TouchInput(final EncryptedEvents.TouchChannel channel){
        this(channel.declaration(),new GuardedSink(){
            public void send(byte[] report)throws IOException{channel.send(report,2000);}
            public void send(byte[] report,EncryptedEvents.SendGuard guard)throws IOException{channel.send(report,2000,guard);}
            public void close(){channel.close();}});
    }
    public TouchInput(TouchHid hid,Sink sink){
        if(hid==null||sink==null)throw new NullPointerException();this.hid=hid;this.sink=sink;
        Thread worker=new Thread(new Runnable(){public void run(){drain();}},"vn-carplay-touch");worker.setDaemon(true);worker.start();
    }
    public TouchHid declaration(){return hid;}
    private static boolean finite(float x){return !Float.isNaN(x)&&!Float.isInfinite(x);}
    public void configure(float left,float top,float right,float bottom,int clockwiseRotation){
        if(!finite(left)||!finite(top)||!finite(right)||!finite(bottom)||right<=left||bottom<=top||
            clockwiseRotation!=0&&clockwiseRotation!=90&&clockwiseRotation!=180&&clockwiseRotation!=270)throw new IllegalArgumentException("Touch viewport");
        synchronized(lock){if(closing)return;release(true);this.left=left;this.top=top;this.right=right;this.bottom=bottom;rotation=clockwiseRotation;configured=true;}
    }
    public void setEnabled(boolean value){synchronized(lock){if(closing)return;if(!value||!enabled)release(true);enabled=value;}}
    public boolean enabled(){synchronized(lock){return enabled&&configured&&!closing;}}
    public IOException failure(){return failure;}
    /** Snapshot pointer IDs/coordinates; IDs stay in their assigned slot until UP/CANCEL. */
    public boolean event(int action,int actionPointer,int[] ids,float[] xs,float[] ys){
        synchronized(lock){
            if(closing||!enabled||!configured)return false;
            if(action==CANCEL){release(true);return true;}
            if(action!=DOWN&&action!=UP&&action!=MOVE&&action!=POINTER_DOWN&&action!=POINTER_UP)return false;
            if(ids==null||xs==null||ys==null||ids.length<1||ids.length>32||xs.length!=ids.length||ys.length!=ids.length){release(true);throw new IllegalArgumentException("Touch snapshot size");}
            for(int i=0;i<ids.length;i++){
                if(ids[i]<0||ids[i]>31||!finite(xs[i])||!finite(ys[i])){release(true);throw new IllegalArgumentException("Touch pointer");}
                for(int j=0;j<i;j++)if(ids[j]==ids[i]){release(true);throw new IllegalArgumentException("Duplicate pointer");}
            }
            int changed=index(ids,actionPointer);if(action!=MOVE&&changed<0){release(true);throw new IllegalArgumentException("Missing action pointer");}
            if(action==DOWN)release(true);
            boolean had=pointers[0]>=0||pointers[1]>=0;
            for(int slot=0;slot<2;slot++)if(pointers[slot]>=0){
                int i=index(ids,pointers[slot]);if(i<0){release(true);return true;}position(slot,xs[i],ys[i]);
            }
            if(action==DOWN||action==POINTER_DOWN){
                if(index(pointers,actionPointer)<0&&inside(xs[changed],ys[changed]))for(int slot=0;slot<2;slot++)if(pointers[slot]<0){pointers[slot]=actionPointer;position(slot,xs[changed],ys[changed]);break;}
            }else if(action==UP||action==POINTER_UP){int slot=index(pointers,actionPointer);if(slot>=0)pointers[slot]=-1;}
            if(had||pointers[0]>=0||pointers[1]>=0)enqueue(report(),action==MOVE);
            return true;
        }
    }
    private static int index(int[] ids,int id){for(int i=0;i<ids.length;i++)if(ids[i]==id)return i;return -1;}
    private boolean inside(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;}
    private void position(int slot,float px,float py){
        double u=Math.max(0,Math.min(1,((double)px-left)/(right-left))),v=Math.max(0,Math.min(1,((double)py-top)/(bottom-top))),a=u,b=v;
        if(rotation==90){a=v;b=1-u;}else if(rotation==180){a=1-u;b=1-v;}else if(rotation==270){a=1-v;b=u;}
        x[slot]=(int)Math.round(a*(hid.width-1));y[slot]=(int)Math.round(b*(hid.height-1));
    }
    private byte[] report(){
        byte[] b=new byte[12];for(int slot=0;slot<2;slot++){int at=slot*6;b[at]=(byte)slot;b[at+1]=(byte)(pointers[slot]<0?0:1);
            b[at+2]=(byte)x[slot];b[at+3]=(byte)(x[slot]>>>8);b[at+4]=(byte)y[slot];b[at+5]=(byte)(y[slot]>>>8);}return b;
    }
    private void eraseQueue(){while(!queue.isEmpty())Arrays.fill(queue.removeFirst().bytes,(byte)0);}
    private void release(boolean clear){
        generation++;
        boolean held=pointers[0]>=0||pointers[1]>=0;
        // An already-dequeued all-up remains valid across generation changes (see drain).
        // Queued old work is replaced by an all-up, ordered before any new gesture.
        boolean pending=!queue.isEmpty();if(clear)eraseQueue();pointers[0]=pointers[1]=-1;
        if(held||pending)enqueue(report(),false);
    }
    private void enqueue(byte[] bytes,boolean move){
        Report last=queue.peekLast();if(move&&last!=null&&last.move&&bytes[1]==last.bytes[1]&&bytes[7]==last.bytes[7]){Arrays.fill(queue.removeLast().bytes,(byte)0);}
        if(queue.size()>=64){eraseQueue();pointers[0]=pointers[1]=-1;Arrays.fill(bytes,(byte)0);bytes=report();move=false;
            enabled=false;closing=true;failure=new IOException("Touch transition queue overflow");}
        queue.addLast(new Report(bytes,move,generation));lock.notifyAll();
    }
    public void close(){synchronized(lock){if(closing)return;release(true);closing=true;enabled=false;lock.notifyAll();}}
    public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException("Wait bound");return stopped.await(millis,TimeUnit.MILLISECONDS);}
    private void drain(){
        try{for(;;){final Report report;synchronized(lock){while(queue.isEmpty()&&!closing)lock.wait();if(queue.isEmpty())return;report=queue.removeFirst();}
            try{hid.validate(report.bytes);
                if(sink instanceof GuardedSink)((GuardedSink)sink).send(report.bytes,new EncryptedEvents.SendGuard(){public boolean current(){
                    synchronized(lock){return report.bytes[1]==0&&report.bytes[7]==0||generation==report.generation;}
                }});
                else sink.send(report.bytes);
            }finally{Arrays.fill(report.bytes,(byte)0);}
        }}catch(InterruptedException e){Thread.currentThread().interrupt();failure=new IOException("Touch worker interrupted",e);}
        catch(IOException e){failure=e;}catch(RuntimeException e){failure=new IOException("Touch worker failed",e);}
        finally{synchronized(lock){closing=true;enabled=false;eraseQueue();pointers[0]=pointers[1]=-1;}
            try{sink.close();}catch(IOException e){if(failure==null)failure=e;}finally{stopped.countDown();}}
    }
}
