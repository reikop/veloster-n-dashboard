/* SPDX-License-Identifier: GPL-3.0-only. Pairing HTTP/RTSP owner for one accepted TCP socket. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.*;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One reader/writer; a watchdog closes the socket to cancel read/write on owner/deadline failure.
 * UI/owner and shutdown callbacks must return promptly. Request handlers may wait
 * for bounded independent work (audio FLUSH: 5s), never for this connection's worker.
 * No callback receives a verified session until M4 has been flushed in plaintext. */
public final class ControlConnection implements Runnable,Closeable {
    /** First failure is retained across socket close and session retirement. Times use System.nanoTime(). */
    public static final class Diagnostic {
        public final String termination,category,exceptionClass,message,phase;
        public final long failureNano,authDeadlineNano,requestDeadlineNano,writeDeadlineNano;
        public final long lastAuthNano,lastRequestByteNano,lastRequestCompleteNano,lastWriteStartNano,lastWriteFlushNano;
        private Diagnostic(String termination,String category,String exceptionClass,String message,String phase,long failureNano,
            long authDeadlineNano,long requestDeadlineNano,long writeDeadlineNano,long lastAuthNano,
            long lastRequestByteNano,long lastRequestCompleteNano,long lastWriteStartNano,long lastWriteFlushNano){
            this.termination=termination;this.category=category;this.exceptionClass=exceptionClass;this.message=message;this.phase=phase;
            this.failureNano=failureNano;this.authDeadlineNano=authDeadlineNano;this.requestDeadlineNano=requestDeadlineNano;this.writeDeadlineNano=writeDeadlineNano;
            this.lastAuthNano=lastAuthNano;this.lastRequestByteNano=lastRequestByteNano;this.lastRequestCompleteNano=lastRequestCompleteNano;
            this.lastWriteStartNano=lastWriteStartNano;this.lastWriteFlushNano=lastWriteFlushNano;
        }
    }
    public interface ApprovalUi {
        void request(Approval approval);
        void dismiss(Approval approval);
    }
    public interface Handler {
        /** Read-only receiver metadata. No session/control capability is supplied before verification. */
        ControlMessage.Response info(ControlMessage.Request request)throws IOException;
        ControlMessage.Response authenticated(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException;
        /** Called once, only after the complete authenticated response was written/flushed. */
        void responseSent(ControlMessage.Request request,PairVerify.VerifiedSession session)throws IOException;
    }
    public interface SessionHandler extends Handler {
        /** Release only resources associated with this exact session; callback must return promptly. */
        void closed(PairVerify.VerifiedSession session);
    }
    /** Local UI capability, never serialized or made available through network routes. */
    public final class Approval {
        public final PairSetup.PendingEnrollment candidate;
        public final PairVerify.Context context;
        private int decision;
        private Approval(PairSetup.PendingEnrollment candidate){this.candidate=candidate;context=ControlConnection.this.context;}
        public synchronized boolean approve(){if(decision!=0||closed.get())return false;decision=1;return true;}
        public synchronized boolean reject(){if(decision!=0||closed.get())return false;decision=2;return true;}
        private synchronized int decision(){return decision;}
        private synchronized void cancel(){decision=3;}
    }
    private static final String PAIRING="application/pairing+tlv8";
    private static final long IO_NANOS=30000000000L,APPROVAL_NANOS=120000000000L;
    private final Socket socket;
    private final PairingStore store;
    private final PairVerify.Context context;
    private final PairVerify.OwnerLease owner;
    private final ApprovalUi ui;
    private final Handler handler;
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicReference<Diagnostic> firstFailure=new AtomicReference<Diagnostic>();
    private final CountDownLatch finished=new CountDownLatch(1);
    private volatile long authDeadline=System.nanoTime()+IO_NANOS,requestDeadline,writeDeadline;
    private volatile long lastAuthNano,lastRequestByteNano,lastRequestCompleteNano,lastWriteStartNano,lastWriteFlushNano;
    private volatile String phase="not_started";
    private volatile PairVerify.VerifiedSession verified;
    private volatile Approval pending;
    private volatile String termination="not_started";
    private PairSetup setup;
    private PairVerify verify;
    private Transport input;
    private OutputStream output;
    public ControlConnection(Socket socket,PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,ApprovalUi ui,Handler handler)throws IOException{
        if(socket==null||store==null||context==null||owner==null||ui==null||handler==null)throw new NullPointerException();
        this.socket=socket;this.store=store;this.context=context;this.owner=owner;this.ui=ui;this.handler=handler;
        if(!socket.isConnected()||socket.isClosed()||!context.localAddress.equals(socket.getLocalAddress())||!context.remoteAddress.equals(socket.getInetAddress()))
            throw new IOException("Control socket/context mismatch");
        NetworkInterface ni=NetworkInterface.getByInetAddress(socket.getLocalAddress());
        if(ni==null||!context.interfaceName.equals(ni.getName()))throw new IOException("Control interface mismatch");
        owner.checkCurrent(context);store.identityIdentifier();socket.setSoTimeout(250);socket.setTcpNoDelay(true);
    }
    private final PairVerify.OwnerLease lease=new PairVerify.PhoneIdentityLease(){
        public void checkCurrent(PairVerify.Context supplied)throws IOException{
            if(supplied!=context)throw new IOException("Foreign control context");checkOwner();
        }
        public void verificationComplete(PairVerify.Context supplied)throws IOException{
            checkCurrent(supplied);
            if(owner instanceof PairVerify.VerifiedOwnerLease)((PairVerify.VerifiedOwnerLease)owner).verificationComplete(supplied);
        }
        public boolean mediaAllowed(PairVerify.Context supplied)throws IOException{
            checkCurrent(supplied);return !(owner instanceof PairVerify.PhoneIdentityLease)||((PairVerify.PhoneIdentityLease)owner).mediaAllowed(supplied);
        }
        public void transportIdentifiers(PairVerify.Context supplied,String bt,String usb)throws IOException{
            checkCurrent(supplied);if(owner instanceof PairVerify.PhoneIdentityLease)((PairVerify.PhoneIdentityLease)owner).transportIdentifiers(supplied,bt,usb);
        }
    };
    private void checkOwner()throws IOException{
        if(closed.get()||socket.isClosed()){
            IOException error=new IOException("Control connection closed");recordFailure("connection_closed",error);throw error;
        }
        try{owner.checkCurrent(context);}catch(IOException error){recordFailure("owner_refused",error);throw error;}
    }
    private void check()throws IOException{
        checkOwner();long now=System.nanoTime(),auth=authDeadline,request=requestDeadline,write=writeDeadline;
        String deadline=auth!=0&&now-auth>=0?"auth_deadline":request!=0&&now-request>=0?"request_deadline":write!=0&&now-write>=0?"write_deadline":null;
        if(deadline!=null){IOException error=new IOException("Control "+deadline+" expired");recordFailure(deadline,error);throw error;}
        PairVerify.VerifiedSession current=verified;if(current!=null)try{current.assertCurrent();}
        catch(IOException error){recordFailure("verified_inactive",error);throw error;}
    }
    private void progress(){long now=System.nanoTime();lastRequestByteNano=now;if(requestDeadline==0)requestDeadline=now+IO_NANOS;}
    private static String diagnosticMessage(String category,Throwable error){
        // Exception messages from a handler or socket may contain peer data. Keep only local literals.
        if("auth_deadline".equals(category)||"request_deadline".equals(category)||"write_deadline".equals(category))
            return "Control "+category+" expired";
        if("connection_closed".equals(category))return "Control connection closed";
        if("owner_refused".equals(category))return "Owner lease check refused";
        if("verified_inactive".equals(category)){
            String message=error.getMessage();
            if("Verified session inactive".equals(message)||"Controller pairing revoked".equals(message)||
                "Verified owner check failed".equals(message)||"Verified owner API unavailable".equals(message))return message;
            return "Verified session check refused";
        }
        return "Control "+category;
    }
    private void recordFailure(String category,Throwable error){
        if(!"running".equals(termination)||firstFailure.get()!=null)return;
        firstFailure.compareAndSet(null,new Diagnostic(termination,category,error.getClass().getName(),diagnosticMessage(category,error),phase,
            System.nanoTime(),authDeadline,requestDeadline,writeDeadline,lastAuthNano,lastRequestByteNano,
            lastRequestCompleteNano,lastWriteStartNano,lastWriteFlushNano));
    }
    public String terminationReason(){return termination;}
    public Diagnostic terminationDiagnostic(){
        Diagnostic first=firstFailure.get();if(first!=null)return new Diagnostic(termination,first.category,first.exceptionClass,first.message,first.phase,
            first.failureNano,first.authDeadlineNano,first.requestDeadlineNano,first.writeDeadlineNano,first.lastAuthNano,
            first.lastRequestByteNano,first.lastRequestCompleteNano,first.lastWriteStartNano,first.lastWriteFlushNano);
        return new Diagnostic(termination,"","","",phase,0,authDeadline,requestDeadline,writeDeadline,lastAuthNano,
            lastRequestByteNano,lastRequestCompleteNano,lastWriteStartNano,lastWriteFlushNano);
    }
    public boolean awaitStopped(long millis)throws InterruptedException{return finished.await(millis,TimeUnit.MILLISECONDS);}
    public void run(){
        if(!started.compareAndSet(false,true))throw new IllegalStateException("Control connection already run");
        Thread watcher=null;termination="running";phase="starting";
        try{
            check();input=new Transport(new BufferedInputStream(socket.getInputStream(),8192));output=socket.getOutputStream();
            watcher=new Thread(new Runnable(){public void run(){
                try{while(!closed.get()){check();Thread.sleep(100);}}
                catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                catch(Exception error){recordFailure("watchdog_exception",error);if("running".equals(termination))termination="owner_or_deadline";close();}
                catch(LinkageError error){recordFailure("owner_unavailable",error);if("running".equals(termination))termination="owner_unavailable";close();}
            }},"vn-control-watchdog");watcher.setDaemon(true);watcher.start();
            while(!closed.get()){
                phase="reading";
                ControlMessage.Request request=ControlMessage.read(input);
                if(request==null){termination="peer_closed";if(verified!=null)verified.retireControl();break;}
                lastRequestCompleteNano=System.nanoTime();phase="dispatching";
                try{check();if(!dispatch(request))break;}
                finally{Arrays.fill(request.body,(byte)0);}
                requestDeadline=0;
                phase="idle";
                if("close".equalsIgnoreCase(request.headers.get("connection"))||verified!=null&&verified.controlRetired()){
                    termination="requested_close";if(verified!=null)verified.retireControl();break;
                }
            }
        }catch(IOException error){recordFailure("protocol_or_io",error);if(termination.equals("running"))termination="protocol_or_io";}
         catch(RuntimeException error){recordFailure("callback_failure",error);if(termination.equals("running"))termination="callback_failure";}
         catch(LinkageError error){recordFailure("api_unavailable",error);if(termination.equals("running"))termination="api_unavailable";}
        finally{
            close();if(watcher!=null)watcher.interrupt();
            if(setup!=null)setup.close();if(verify!=null)verify.close();
            if(handler instanceof SessionHandler&&verified!=null){
                try{((SessionHandler)handler).closed(verified);}catch(RuntimeException ignored){}catch(LinkageError ignored){}
            }
            if(input!=null)input.erase();dismiss();finished.countDown();
        }
    }
    private boolean dispatch(ControlMessage.Request request)throws IOException{
        boolean pairing=request.path.equals("/pair-setup")||request.path.equals("/pair-verify");
        if(pairing){
            if(verified!=null||!request.method.equals("POST")){send(request,ControlMessage.Response.empty(400));termination="pairing_state";return false;}
            String type=request.headers.get("content-type");
            if(type!=null&&!type.equalsIgnoreCase(PAIRING)){send(request,ControlMessage.Response.empty(415));termination="pairing_type";return false;}
            int phase=0;boolean responseAttempted=false;
            try{
                Map<Integer,byte[]> tlv=PairingTlv.decode(request.body);byte[] state=tlv.get(6);
                if(state!=null&&state.length==1)phase=state[0]&255;
                if(request.path.equals("/pair-setup")){
                    if(verify!=null)throw new IOException("PairSetup after PairVerify");
                    if(setup==null)setup=new PairSetup(store,context,lease);
                    PairSetup.Reply reply=setup.handle(request.body);
                    if(reply==null){
                        requestDeadline=0;authDeadline=System.nanoTime()+APPROVAL_NANOS;
                        Approval approval=new Approval(setup.pendingEnrollment());pending=approval;
                        ui.request(approval);
                        try{awaitApproval(approval);reply=setup.approve(approval.candidate);}
                        finally{dismiss();}
                    }
                    responseAttempted=true;send(request,ControlMessage.Response.body(PAIRING,reply.body()));setup.responseSent(reply);
                    if(setup.complete())authDeadline=System.nanoTime()+IO_NANOS;
                }else{
                    if(setup!=null&&!setup.complete())throw new IOException("PairSetup not complete");
                    if(verify==null){verify=new PairVerify(store,context,lease);authDeadline=System.nanoTime()+IO_NANOS;}
                    PairVerify.Reply reply=verify.handle(request.body);
                    responseAttempted=true;send(request,ControlMessage.Response.body(PAIRING,reply.body()));verify.responseSent(reply);
                    if(phase==3){verified=verify.session();lastAuthNano=System.nanoTime();authDeadline=0;}
                }
                return true;
            }catch(IOException e){
                if(!closed.get()&&!responseAttempted){
                    if(phase==1||phase==3||phase==5)send(request,ControlMessage.Response.body(PAIRING,new byte[]{6,1,(byte)(phase+1),7,1,2}));
                    else send(request,ControlMessage.Response.empty(400));
                }
                termination="pairing_rejected";return false;
            }
        }
        if(request.path.equals("/info")&&(request.method.equals("GET")||request.method.equals("POST"))){
            send(request,handler.info(request));return true;
        }
        PairVerify.VerifiedSession session=verified;
        if(session==null){send(request,ControlMessage.Response.empty(401));termination="authentication_required";return false;}
        session.assertCurrent();ControlMessage.Response reply=handler.authenticated(request,session);check();send(request,reply);
        check();handler.responseSent(request,session);return true;
    }
    private void awaitApproval(Approval approval)throws IOException{
        for(;;){
            check();setup.pendingEnrollment();int decision=approval.decision();
            if(decision==2||decision==3)throw new IOException("Enrollment denied");
            // Polling also observes EOF/unexpected pipelining while the UI is visible.
            // Even an already-approved decision probes the socket before durable commit.
            try{int b=input.raw.read();close();if(b<0)throw new EOFException("Enrollment peer disconnected");throw new IOException("Input during enrollment approval");}
            catch(SocketTimeoutException expected){if(decision==1)return;}
        }
    }
    private void dismiss(){
        Approval approval=pending;pending=null;if(approval==null)return;approval.cancel();
        try{ui.dismiss(approval);}catch(RuntimeException ignored){}catch(LinkageError ignored){}
    }
    private void send(ControlMessage.Request request,ControlMessage.Response response)throws IOException{
        if(response==null)throw new IOException("Missing control response");check();byte[] wire=ControlMessage.encode(request,response);
        phase="writing";lastWriteStartNano=System.nanoTime();writeDeadline=lastWriteStartNano+IO_NANOS;
        try{
            PairVerify.VerifiedSession session=verified;
            if(session==null)output.write(wire);
            else for(int at=0;at<wire.length;at+=PairVerify.VerifiedSession.MAX_OUTGOING_CONTROL_PAYLOAD){
                check();byte[] part=Arrays.copyOfRange(wire,at,Math.min(wire.length,at+PairVerify.VerifiedSession.MAX_OUTGOING_CONTROL_PAYLOAD));
                byte[] record=null;try{record=session.sealControlRecord(part);output.write(record);}finally{Arrays.fill(part,(byte)0);if(record!=null)Arrays.fill(record,(byte)0);}
            }
            output.flush();lastWriteFlushNano=System.nanoTime();check();
        }finally{Arrays.fill(wire,(byte)0);writeDeadline=0;phase="dispatching";}
    }
    public void close(){if(!closed.compareAndSet(false,true))return;Approval approval=pending;if(approval!=null)approval.cancel();try{socket.close();}catch(IOException ignored){}}
    private final class Transport extends InputStream {
        private final InputStream raw;
        private byte[] plain=new byte[0],one=new byte[1];private int offset;
        Transport(InputStream raw){this.raw=raw;}
        private int receive(byte[] b,int off,int len)throws IOException{
            for(;;){check();try{int n=raw.read(b,off,len);if(n>0)progress();return n;}catch(SocketTimeoutException ignored){}}
        }
        private boolean frame()throws IOException{
            byte[] head=new byte[2];int n=receive(head,0,1);if(n<0)return false;
            if(receive(head,1,1)!=1)throw new EOFException("Short control record header");
            int size=(head[0]&255)|((head[1]&255)<<8);byte[] wire=new byte[size+18];wire[0]=head[0];wire[1]=head[1];int at=2;
            try{while(at<wire.length){int got=receive(wire,at,wire.length-at);if(got<=0)throw new EOFException("Short control record");at+=got;}
                erase();plain=verified.openControlRecord(wire);offset=0;return true;
            }finally{Arrays.fill(wire,(byte)0);}
        }
        public int read()throws IOException{int n=read(one,0,1);return n<0?-1:one[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();if(len==0)return 0;
            check();if(verified==null)return receive(b,off,len);
            while(offset==plain.length)if(!frame())return -1;
            progress();int n=Math.min(len,plain.length-offset);System.arraycopy(plain,offset,b,off,n);Arrays.fill(plain,offset,offset+n,(byte)0);offset+=n;return n;
        }
        void erase(){Arrays.fill(plain,(byte)0);plain=new byte[0];offset=0;}
    }
}
