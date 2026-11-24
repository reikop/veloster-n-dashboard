/* SPDX-License-Identifier: GPL-3.0-only. Event protocol derived from DiPlay; see NOTICE.md. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.tunnel.IapDataTunnel;
import com.veloster.carplay.input.TouchHid;
import com.veloster.carplay.input.ConsumerHid;
import com.veloster.carplay.mediakeys.ConsumerKeySession;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One verified session, one encrypted TCP event connection. No reconnect with reused nonces.
 * close/cancel never call into the parent session, allowing parent/tunnel cancellation under its lock. */
public final class EncryptedEvents implements IapDataTunnel.EventChannel,Closeable {
    private final PairVerify.VerifiedSession session;
    private final ModeControl modes;
    private final PairVerify.EventRecords records;
    private final IapDataTunnel.Binding binding;
    private final ServerSocket listener;
    private final Object state=new Object(),writer=new Object();
    private final ReentrantLock sender=new ReentrantLock(true);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CountDownLatch stopped=new CountDownLatch(3);
    private final ScreenRecovery[] recovery=new ScreenRecovery[2];
    private TouchChannel touch;
    private ConsumerChannel consumer;
    private SiriChannel siri;
    /** One tunnel generation. Cancellation stops new writes; a written command
     * retains the shared sender until its exact ACK arrives, preserving CSeq and
     * cipher counters for media/input traffic on the same event connection. */
    public final class IapChannel implements IapDataTunnel.EventChannel {
        private volatile boolean cancelled;
        private IapChannel(){}
        public void sendIapMessage(IapDataTunnel.Binding supplied,byte[] data,long timeout)throws IOException{
            if(supplied!=binding)throw new IOException("Foreign iAP event binding");
            if(data==null||data.length<1||data.length>65535||timeout<1||timeout>30000)throw new IOException("Event send bounds");
            if(cancelled)throw new IOException("iAP event generation retired");
            sendCommand(data,null,null,null,null,this,timeout);
        }
        public void cancelIapMessages(IapDataTunnel.Binding supplied){if(supplied==binding){cancelled=true;synchronized(state){state.notifyAll();}}}
    }
    public IapChannel iapChannel(PairVerify.VerifiedSession supplied)throws IOException{
        if(supplied!=session)throw new IOException("Foreign iAP session");check();return new IapChannel();
    }
    /** Nonblocking producer-generation check; called again after the shared sender is acquired. */
    public interface SendGuard {boolean current();}
    /** One declared main-screen HID sender. Calls block for the peer ACK; use an input worker. */
    public final class TouchChannel implements Closeable {
        private final ReceiverMetadata metadata;private final TouchHid declaration;private volatile boolean cancelled;
        private TouchChannel(ReceiverMetadata metadata,TouchHid declaration){this.metadata=metadata;this.declaration=declaration;}
        public TouchHid declaration(){return declaration;}
        public void send(byte[] report,long timeoutMillis)throws IOException{
            send(report,timeoutMillis,new SendGuard(){public boolean current(){return true;}});
        }
        public void send(byte[] report,long timeoutMillis,SendGuard guard)throws IOException{
            if(timeoutMillis<1||timeoutMillis>30000)throw new IOException("Touch send bounds");
            if(guard==null)throw new NullPointerException();
            if(report==null)throw new IOException("Missing touch report");byte[] copy=report.clone();
            try{declaration.validate(copy);checkTouch(this);sendCommand(null,null,null,new TouchCommand(this,copy,guard),timeoutMillis);}finally{Arrays.fill(copy,(byte)0);}
        }
        public void close(){cancelled=true;synchronized(state){state.notifyAll();}}
    }
    private static final class TouchCommand {
        final TouchChannel channel;final byte[] report;final SendGuard guard;
        TouchCommand(TouchChannel channel,byte[] report,SendGuard guard){this.channel=channel;this.report=report;this.guard=guard;}
    }
    public TouchChannel touchChannel(PairVerify.VerifiedSession supplied,ReceiverMetadata metadata)throws IOException{
        if(supplied!=session||metadata==null)throw new IOException("Foreign touch session");check();
        TouchHid declaration=metadata.inputFor(session);
        synchronized(state){checkOpen();if(touch!=null)throw new IOException("Touch channel already claimed");
            touch=new TouchChannel(metadata,declaration);return touch;}
    }
    private void checkTouch(TouchChannel supplied)throws IOException{
        check();if(supplied.cancelled||supplied.metadata.inputFor(session)!=supplied.declaration||!session.mediaAllowed(binding))
            throw new IOException("Touch input not permitted");
    }
    /** One declared Consumer HID sender on this verified event connection. */
    public final class ConsumerChannel implements ConsumerKeySession.ConsumerChannel {
        private final ReceiverMetadata metadata;private final ConsumerHid declaration;private volatile boolean cancelled;
        private ConsumerChannel(ReceiverMetadata metadata,ConsumerHid declaration){this.metadata=metadata;this.declaration=declaration;}
        public ConsumerHid declaration(){return declaration;}
        public boolean send(byte[] report,long timeoutMillis,ConsumerKeySession.Current current)throws IOException{
            if(timeoutMillis<1||timeoutMillis>30000)throw new IOException("Consumer send bounds");
            if(current==null)throw new NullPointerException();
            ConsumerHid.validate(report);byte[] copy=report.clone();
            try{checkConsumer(this);ConsumerCommand command=new ConsumerCommand(this,copy,current);
                sendCommand(null,null,null,null,null,null,command,timeoutMillis);return command.sent;
            }finally{Arrays.fill(copy,(byte)0);}
        }
        public void close(){cancelled=true;synchronized(state){state.notifyAll();}}
    }
    private static final class ConsumerCommand {
        final ConsumerChannel channel;final byte[] report;final ConsumerKeySession.Current current;boolean sent;
        ConsumerCommand(ConsumerChannel channel,byte[] report,ConsumerKeySession.Current current){this.channel=channel;this.report=report;this.current=current;}
    }
    public ConsumerChannel consumerChannel(PairVerify.VerifiedSession supplied,ReceiverMetadata metadata)throws IOException{
        if(supplied!=session||metadata==null)throw new IOException("Foreign Consumer session");check();
        ConsumerHid declaration=metadata.consumerFor(session);
        synchronized(state){checkOpen();if(consumer!=null)throw new IOException("Consumer channel already claimed");
            consumer=new ConsumerChannel(metadata,declaration);return consumer;}
    }
    private void checkConsumer(ConsumerChannel supplied)throws IOException{
        check();if(supplied!=consumer||supplied.cancelled||supplied.metadata.consumerFor(session)!=supplied.declaration||!session.mediaAllowed(binding))
            throw new IOException("Consumer input not permitted");
    }
    /** One exact-session Siri sender. I/O and ACK wait belong to a tracked input worker. */
    public final class SiriChannel implements Closeable {
        private volatile boolean cancelled;
        private SiriChannel(){}
        /** Cached connection readiness only; the worker must also check current media admission. */
        public boolean ready(){synchronized(state){return !cancelled&&!closed.get()&&active&&connected;}}
        public void checkCurrent()throws IOException{check();if(cancelled)throw new IOException("Siri channel retired");}
        /** False means the producer was retired before writing; true means the peer ACK arrived. */
        public boolean send(int action,long timeoutMillis,SendGuard guard)throws IOException{
            if(action!=2&&action!=3||timeoutMillis<1||timeoutMillis>30000)throw new IOException("Siri send bounds");
            if(guard==null)throw new NullPointerException();checkCurrent();
            SiriCommand command=new SiriCommand(this,action,guard);
            sendCommand(null,null,null,null,command,timeoutMillis);return command.sent;
        }
        public void close(){cancelled=true;synchronized(state){state.notifyAll();}}
    }
    private static final class SiriCommand {
        final SiriChannel channel;final int action;final SendGuard guard;boolean sent;
        SiriCommand(SiriChannel channel,int action,SendGuard guard){this.channel=channel;this.action=action;this.guard=guard;}
    }
    public SiriChannel siriChannel(PairVerify.VerifiedSession supplied)throws IOException{
        if(supplied!=session)throw new IOException("Foreign Siri session");check();
        synchronized(state){checkOpen();if(siri!=null)throw new IOException("Siri channel already claimed");siri=new SiriChannel();return siri;}
    }
    private void checkSiri(SiriChannel supplied)throws IOException{
        check();if(supplied!=siri||supplied.cancelled||!session.mediaAllowed(binding))throw new IOException("Siri input not permitted");
    }
    /** Session/stream-owned, coalescing recovery capability. No socket I/O on codec/UI callbacks. */
    public final class ScreenRecovery implements Closeable {
        private final String uuid;private boolean cancelled,pending;private long lastSent;
        private ScreenRecovery(String uuid){this.uuid=uuid;}
        public boolean request(){
            try{check();}catch(IOException e){return false;}
            synchronized(state){if(cancelled||closed.get())return false;pending=true;state.notifyAll();return true;}
        }
        public void close(){synchronized(state){
            cancelled=true;pending=false;
            // Remove only this capability. A delayed old close must not erase its successor.
            for(int i=0;i<recovery.length;i++)if(recovery[i]==this)recovery[i]=null;
            state.notifyAll();
        }}
    }
    public ScreenRecovery screenRecovery(PairVerify.VerifiedSession supplied,int type,String uuid)throws IOException{
        if(supplied!=session||(type!=110&&type!=111)||type==110&&uuid!=null)throw new IOException("Foreign screen recovery binding");
        if(type==111)ControlMessage.validateDisplayUuid(uuid);check();
        synchronized(state){checkOpen();int i=type-110;if(recovery[i]!=null)throw new IOException("Screen recovery already claimed");
            recovery[i]=new ScreenRecovery(uuid);return recovery[i];}
    }
    private volatile Socket socket;
    private volatile long connectDeadline=System.nanoTime()+30000000000L,readDeadline,writeDeadline,sendDeadline;
    private volatile IOException failure;
    private boolean active,connected;
    private int sequence;
    private Pending pending;
    private static final class Pending {
        final int seq;final boolean mode;final long deadline;boolean done;long completedAt;ModeControl.Result result;
        Pending(int seq,boolean mode,long deadline){this.seq=seq;this.mode=mode;this.deadline=deadline;}
    }

    public EncryptedEvents(PairVerify.VerifiedSession session)throws IOException{
        this(session,null);
    }
    public EncryptedEvents(PairVerify.VerifiedSession session,ModeControl modes)throws IOException{
        if(session==null)throw new NullPointerException();this.session=session;this.modes=modes;binding=session.binding();
        PairVerify.Context context=session.context();NetworkInterface iface=NetworkInterface.getByInetAddress(binding.localAddress);
        if(iface==null||!iface.getName().equals(context.interfaceName))throw new IOException("Event interface changed");
        records=session.claimEventRecords();ServerSocket opened=null;
        try{opened=new ServerSocket();opened.bind(new InetSocketAddress(binding.localAddress,0),1);opened.setSoTimeout(250);session.assertCurrent();}
        catch(IOException e){if(opened!=null)opened.close();records.close();throw e;}
        catch(RuntimeException e){if(opened!=null)opened.close();records.close();throw e;}
        listener=opened;
        try{if(modes!=null)modes.bind(session,this);}catch(IOException e){close();records.close();throw e;}
        Thread reader=new Thread(new Runnable(){public void run(){receive();}},"vn-carplay-events");reader.setDaemon(true);
        Thread watcher=new Thread(new Runnable(){public void run(){watch();}},"vn-carplay-events-watch");watcher.setDaemon(true);
        Thread recoverer=new Thread(new Runnable(){public void run(){recover();}},"vn-carplay-keyframes");recoverer.setDaemon(true);
        int started=0;
        try{reader.start();started++;watcher.start();started++;recoverer.start();started++;}
        catch(RuntimeException e){close();for(int i=started;i<3;i++)stopped.countDown();records.close();throw e;}
    }
    public int port(){return listener.getLocalPort();}
    /** Call only after the complete base SETUP response (containing eventPort) is flushed. */
    public void setupResponseSent()throws IOException{
        check();if(modes!=null)modes.started();
        synchronized(state){checkOpen();if(active)throw new IOException("Event SETUP already sent");active=true;state.notifyAll();}
    }
    private void checkOpen()throws IOException{if(closed.get())throw failure==null?new IOException("Event channel closed"):failure;}
    private void check()throws IOException{checkOpen();session.assertCurrent();}
    private static boolean expired(long deadline,long now){return deadline!=0&&now-deadline>=0;}
    private void watch(){
        try{
            while(!closed.get()){
                check();long now=System.nanoTime(),c=connectDeadline,r=readDeadline,w=writeDeadline;
                if(expired(c,now)||expired(r,now)||expired(w,now))throw new IOException("Event I/O deadline");
                // ACK completion retires this deadline under the same lock. A
                // delayed sender must not keep an already completed request live.
                synchronized(state){if(expired(sendDeadline,System.nanoTime()))throw new IOException("Event I/O deadline");}
                Thread.sleep(100);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail(new IOException("Event watchdog interrupted",e));}
         catch(IOException e){fail(e);}catch(RuntimeException e){fail(new IOException("Event watchdog failed",e));}
         finally{stopped.countDown();}
    }
    private void receive(){
        PlainInput input=null;
        try{
            synchronized(state){while(!active&&!closed.get())state.wait(250);checkOpen();}
            for(;;){
                check();Socket candidate;
                try{candidate=listener.accept();}catch(SocketTimeoutException timeout){continue;}
                if(!candidate.getInetAddress().equals(binding.remoteAddress)||!candidate.getLocalAddress().equals(binding.localAddress)){
                    candidate.close();continue;
                }
                synchronized(state){if(closed.get()){candidate.close();checkOpen();}socket=candidate;}
                candidate.setSoTimeout(250);candidate.setTcpNoDelay(true);check();
                input=new PlainInput(new BufferedInputStream(candidate.getInputStream()));listener.close();
                synchronized(state){checkOpen();connected=true;connectDeadline=0;state.notifyAll();}break;
            }
            for(;;){
                check();ControlMessage.EventMessage message=ControlMessage.readEvent(input);
                if(message==null)throw new EOFException("Event peer closed");
                try{
                    // Parsing consumes only bounded decrypted bytes. Recheck the
                    // exact owner before any request/ACK can change shared state.
                    check();
                    if(message.request!=null){
                        ControlMessage.Response reply=modes==null?null:modes.handle(message.request,session);
                        write(ControlMessage.encode(message.request,reply==null?ControlMessage.Response.empty(501):reply));
                    }else{
                        int seq=Integer.parseInt(message.headers.get("cseq"));Pending response;
                        synchronized(state){
                            checkOpen();if(pending==null||pending.done||pending.seq!=seq||!pending.mode&&message.status!=200)
                                throw new IOException("Unexpected/rejected event acknowledgement");
                            if(expired(pending.deadline,System.nanoTime()))throw new IOException("Event acknowledgement deadline");
                            response=pending;
                        }
                        // Do not invoke the mode listener under the event state lock. The callback
                        // runs before the next read, preserving response/notification wire order.
                        ModeControl.Result result=response.mode?modes.reply(message.status,message.body):null;
                        synchronized(state){
                            checkOpen();if(pending!=response)throw new IOException("Retired event acknowledgement");
                            long completed=System.nanoTime();if(expired(response.deadline,completed))throw new IOException("Event acknowledgement deadline");
                            response.result=result;response.completedAt=completed;response.done=true;sendDeadline=0;state.notifyAll();
                        }
                    }
                }finally{Arrays.fill(message.body,(byte)0);}
                readDeadline=0;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail(new IOException("Event reader interrupted",e));}
         catch(IOException e){fail(e);}catch(RuntimeException e){fail(new IOException("Event reader failed",e));}
         catch(LinkageError e){fail(new IOException("Event API unavailable",e));}
         finally{close();if(input!=null)input.erase();records.close();stopped.countDown();}
    }
    private void write(byte[] message)throws IOException{
        try{
            synchronized(writer){
                check();Socket current=socket;if(current==null)throw new IOException("Event not connected");
                writeDeadline=System.nanoTime()+30000000000L;
                try{
                    OutputStream out=current.getOutputStream();
                    for(int at=0;at<message.length;at+=16384){
                        check();byte[] plain=Arrays.copyOfRange(message,at,Math.min(message.length,at+16384)),wire=null;
                        try{wire=records.seal(plain);out.write(wire);}finally{Arrays.fill(plain,(byte)0);if(wire!=null)Arrays.fill(wire,(byte)0);}
                    }
                    out.flush();check();
                }finally{writeDeadline=0;}
            }
        }finally{Arrays.fill(message,(byte)0);}
    }
    public void sendIapMessage(IapDataTunnel.Binding supplied,byte[] data,long timeoutMillis)throws IOException{
        if(supplied!=binding)throw new IOException("Foreign event binding");
        if(data==null||data.length<1||data.length>65535||timeoutMillis<1||timeoutMillis>30000)throw new IOException("Event send bounds");
        sendCommand(data,null,null,null,timeoutMillis);
    }
    ModeControl.Result sendModeChange(ModeControl supplied,ModeChanges changes,long timeoutMillis)throws IOException{
        if(supplied==null||supplied!=modes)throw new IOException("Foreign mode event binding");
        return sendCommand(null,null,changes,null,timeoutMillis);
    }
    private boolean cancelled(ScreenRecovery recovery){synchronized(state){return recovery!=null&&recovery.cancelled;}}
    private boolean cancelled(ScreenRecovery recovery,TouchCommand touch){return cancelled(recovery)||touch!=null&&(touch.channel.cancelled||!touch.guard.current());}
    private ModeControl.Result sendCommand(byte[] data,ScreenRecovery recovery,ModeChanges changes,TouchCommand touch,long timeoutMillis)throws IOException{
        return sendCommand(data,recovery,changes,touch,null,timeoutMillis);
    }
    private boolean cancelled(ScreenRecovery recovery,TouchCommand touch,SiriCommand siri){
        return cancelled(recovery,touch)||siri!=null&&(siri.channel.cancelled||!siri.guard.current());
    }
    private boolean cancelled(ScreenRecovery recovery,TouchCommand touch,SiriCommand siri,ConsumerCommand consumer){
        return cancelled(recovery,touch,siri)||consumer!=null&&(consumer.channel.cancelled||!consumer.current.current());
    }
    private ModeControl.Result sendCommand(byte[] data,ScreenRecovery recovery,ModeChanges changes,TouchCommand touch,SiriCommand siri,long timeoutMillis)throws IOException{
        return sendCommand(data,recovery,changes,touch,siri,null,timeoutMillis);
    }
    private ModeControl.Result sendCommand(byte[] data,ScreenRecovery recovery,ModeChanges changes,TouchCommand touch,SiriCommand siri,IapChannel iap,long timeoutMillis)throws IOException{
        return sendCommand(data,recovery,changes,touch,siri,iap,null,timeoutMillis);
    }
    private ModeControl.Result sendCommand(byte[] data,ScreenRecovery recovery,ModeChanges changes,TouchCommand touch,SiriCommand siri,IapChannel iap,ConsumerCommand consumer,long timeoutMillis)throws IOException{
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);boolean locked=false,restoreInterrupt=false,written=false;
        try{
            while(!locked){
                if(cancelled(recovery,touch,siri,consumer)||iap!=null&&iap.cancelled)return null;
                check();long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Event sender busy");
                locked=sender.tryLock(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
            }
            check();Pending request;
            synchronized(state){
                sendDeadline=deadline;
                while(!connected&&!closed.get()&&!cancelled(recovery,touch,siri,consumer)&&!(iap!=null&&iap.cancelled))waitUntil(deadline);checkOpen();
                if(cancelled(recovery,touch,siri,consumer)||iap!=null&&iap.cancelled)return null;
                if(sequence==Integer.MAX_VALUE)throw new IOException("Event CSeq exhausted");
                request=new Pending(++sequence,changes!=null,deadline);pending=request;
            }
            if(touch!=null){if(cancelled(null,touch))return null;checkTouch(touch.channel);}
            if(siri!=null){if(cancelled(null,null,siri))return null;checkSiri(siri.channel);}
            if(consumer!=null){if(cancelled(null,null,null,consumer))return null;checkConsumer(consumer.channel);}
            if(iap!=null&&iap.cancelled)return null;
            write(consumer!=null?ControlMessage.consumerCommand(request.seq,consumer.report):siri!=null?ControlMessage.siriCommand(request.seq,siri.action):touch!=null?ControlMessage.touchCommand(request.seq,touch.report):changes!=null?ControlMessage.modeCommand(request.seq,changes):
                recovery!=null?ControlMessage.keyframeCommand(request.seq,recovery.uuid):ControlMessage.iapCommand(request.seq,data));
            written=true;
            if(siri!=null)siri.sent=true;
            if(consumer!=null)consumer.sent=true;
            synchronized(state){while(!request.done&&!closed.get()){
                try{waitUntil(deadline);}
                catch(InterruptedException e){
                    // iAP stream shutdown interrupts its writer. A command already
                    // on the wire still owns its ACK; drain it before releasing
                    // the sender to touch/Siri/mode traffic.
                    if(iap==null||!iap.cancelled)throw e;restoreInterrupt=true;
                }
            }checkOpen();}
            // The reader validated the exact ACK before its deadline. Caller
            // scheduling after completion does not turn success into a timeout.
            check();return request.result;
        }catch(InterruptedException e){
            if(iap!=null&&iap.cancelled&&!written){restoreInterrupt=true;return null;}
            Thread.currentThread().interrupt();IOException failure=new IOException("Event send interrupted",e);fail(failure);throw failure;
        }
         catch(IOException e){fail(e);throw e;}
         catch(RuntimeException e){IOException failure=new IOException("Event send failed",e);fail(failure);throw failure;}
         finally{if(locked){synchronized(state){pending=null;sendDeadline=0;}sender.unlock();}if(restoreInterrupt)Thread.currentThread().interrupt();}
    }
    private void recover(){
        int next=0;
        try{
            while(!closed.get()){
                ScreenRecovery selected=null;
                synchronized(state){
                    checkOpen();long now=System.nanoTime();
                    for(int n=0;n<2;n++){
                        int i=(next+n)%2;ScreenRecovery candidate=recovery[i];
                        if(candidate!=null&&!candidate.cancelled&&candidate.pending&&(candidate.lastSent==0||now-candidate.lastSent>=1000000000L)){
                            selected=candidate;candidate.pending=false;next=(i+1)%2;break;
                        }
                    }
                    if(selected==null){state.wait(100);continue;}
                }
                sendCommand(null,selected,null,null,5000);
                // Measure from completed I/O, not queue selection: waiting behind iAP or a
                // slow ACK must not consume the interval and release a recovery burst.
                synchronized(state){selected.lastSent=System.nanoTime();}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail(new IOException("Recovery worker interrupted",e));}
         catch(IOException e){if(!closed.get())fail(e);}
         catch(RuntimeException e){fail(new IOException("Recovery command failed",e));}
         finally{stopped.countDown();}
    }
    private void waitUntil(long deadline)throws IOException,InterruptedException{
        long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Event send deadline");
        state.wait(Math.max(1,Math.min(250,TimeUnit.NANOSECONDS.toMillis(left))));
    }
    public void cancelIapMessages(IapDataTunnel.Binding supplied){if(supplied==binding)close();}
    private void fail(IOException why){if(failure==null)failure=why;close();}
    public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{listener.close();}catch(IOException ignored){}
        synchronized(state){
            if(touch!=null)touch.close();
            if(siri!=null)siri.close();
            if(consumer!=null)consumer.close();
            for(ScreenRecovery value:recovery)if(value!=null){value.cancelled=true;value.pending=false;}
            Socket current=socket;if(current!=null)try{current.close();}catch(IOException ignored){}state.notifyAll();
        }
        if(modes!=null)modes.close();
    }
    public boolean awaitStopped(long millis)throws InterruptedException{return stopped.await(millis,TimeUnit.MILLISECONDS);}
    private final class PlainInput extends InputStream {
        final InputStream raw;byte[] plain=new byte[0],one=new byte[1];int offset;
        PlainInput(InputStream raw){this.raw=raw;}
        private void progress(){if(readDeadline==0)readDeadline=System.nanoTime()+30000000000L;}
        private int rawRead(byte[] b,int at,int size)throws IOException{
            for(;;){check();try{int n=raw.read(b,at,size);if(n>0)progress();return n;}catch(SocketTimeoutException timeout){}}
        }
        private boolean frame()throws IOException{
            byte[] head=new byte[2];if(rawRead(head,0,1)<0)return false;
            if(rawRead(head,1,1)!=1)throw new EOFException("Short event record header");
            int size=(head[0]&255)|((head[1]&255)<<8);byte[] wire=new byte[size+18];wire[0]=head[0];wire[1]=head[1];
            try{int at=2;while(at<wire.length){int n=rawRead(wire,at,wire.length-at);if(n<=0)throw new EOFException("Short event record");at+=n;}
                erase();plain=records.open(wire);return true;
            }finally{Arrays.fill(wire,(byte)0);}
        }
        public int read()throws IOException{int n=read(one,0,1);return n<0?-1:one[0]&255;}
        public int read(byte[] b,int at,int size)throws IOException{
            if(b==null)throw new NullPointerException();if(at<0||size<0||at>b.length-size)throw new IndexOutOfBoundsException();if(size==0)return 0;
            // Raw reads and record authentication check the full session lease;
            // buffered plaintext parsing only needs the local close fence. The
            // receive loop checks the full owner again before dispatch.
            checkOpen();while(offset==plain.length)if(!frame())return -1;progress();int n=Math.min(size,plain.length-offset);
            System.arraycopy(plain,offset,b,at,n);Arrays.fill(plain,offset,offset+n,(byte)0);offset+=n;return n;
        }
        void erase(){Arrays.fill(plain,(byte)0);plain=new byte[0];offset=0;}
    }
}
