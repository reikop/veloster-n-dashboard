/* SPDX-License-Identifier: GPL-3.0-only. Verified-session CarPlay mode control. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import java.io.*;
import java.util.*;

/** One verified connection's state and command capability. Listener callbacks are serialized,
 * bounded enqueue-only operations: never wait for network/UI or call request() from a callback.
 * A UI queue must retain this capability and discard work after isClosed(), including on reconnect. */
public final class ModeControl implements Closeable {
    public interface Listener {
        void opened(ModeControl control)throws IOException;
        void changed(ModeControl control,ModeState state)throws IOException;
        /** Return true only when the local host UI request was actually accepted for processing. */
        boolean hostUiRequested(ModeControl control)throws IOException;
        void closed(ModeControl control);
    }
    public static final class Result {
        public final int httpStatus;public final long protocolStatus;public final ModeState state;
        private Result(int status,long protocolStatus,ModeState state){httpStatus=status;this.protocolStatus=protocolStatus;this.state=state;}
        public boolean accepted(){return httpStatus==200&&protocolStatus==0;}
    }
    private final PairVerify.VerifiedSession session;
    private final Listener listener;
    private final Object lock=new Object();
    private EncryptedEvents events;private ModeState state=ModeState.unknown();private boolean ready,closed;
    private Thread callbackThread;
    public ModeControl(PairVerify.VerifiedSession session,Listener listener){
        if(session==null||listener==null)throw new NullPointerException();this.session=session;this.listener=listener;
    }
    void bind(PairVerify.VerifiedSession supplied,EncryptedEvents events)throws IOException{
        if(supplied!=session)throw new IOException("Foreign mode session");session.assertCurrent();
        synchronized(lock){if(closed||this.events!=null)throw new IOException("Mode control already bound/closed");this.events=events;}
    }
    void started()throws IOException{
        session.assertCurrent();synchronized(lock){if(closed||ready)throw new IOException("Mode control not startable");ready=true;
            callbackThread=Thread.currentThread();try{listener.opened(this);}finally{callbackThread=null;}}
    }
    public boolean isClosed(){synchronized(lock){return closed;}}
    /** SETUP response flush/start fence, independent of media admission. */
    public boolean isReady(){synchronized(lock){return ready&&!closed;}}
    public ModeState snapshot(){synchronized(lock){return state;}}
    public boolean belongsTo(PairVerify.VerifiedSession supplied){return supplied!=null&&supplied==session;}
    private void usable()throws IOException{if(!ready||closed)throw new IOException("Mode control not active");}
    public Result request(ModeChanges changes,long timeoutMillis)throws IOException{
        if(changes==null||timeoutMillis<1||timeoutMillis>30000)throw new IOException("Mode request bounds");
        // Check callback reentry before taking the parent monitor: parent shutdown can hold
        // that monitor while waiting to retire this capability's callback.
        synchronized(lock){usable();if(callbackThread==Thread.currentThread())throw new IOException("Mode send from callback");}
        session.assertCurrent();EncryptedEvents transport;
        synchronized(lock){usable();transport=events;}
        return transport.sendModeChange(this,changes,timeoutMillis);
    }
    /** null means the caller must try its next handler. Never interpret changeModes as a notification. */
    public ControlMessage.Response handle(ControlMessage.Request request,PairVerify.VerifiedSession supplied)throws IOException{
        if(supplied!=session)throw new IOException("Foreign mode request");session.assertCurrent();
        boolean direct=request.path.equals("/modesChanged"),command=request.path.equals("/command");
        if(!direct&&!command)return null;
        if(!request.method.equals("POST"))return ControlMessage.Response.empty(405);
        if(!"application/x-apple-binary-plist".equalsIgnoreCase(request.headers.get("content-type")))return ControlMessage.Response.empty(415);
        Map<String,Object> body,params;String type;
        try{
            body=BinaryPlist.dictionary(BinaryPlist.decode(request.body));
            if(direct){type="modesChanged";params=body;}
            else{
                Object raw=body.get("type");if(!(raw instanceof String))return ControlMessage.Response.empty(400);type=(String)raw;
                if(!type.equals("modesChanged")&&!type.equals("requestUI"))return null;
                params=body.containsKey("params")?BinaryPlist.dictionary(body.get("params")):Collections.<String,Object>emptyMap();
            }
        }catch(IOException e){return ControlMessage.Response.empty(400);}
        synchronized(lock){
            usable();
            if(type.equals("modesChanged")){
                ModeState next;try{next=state.merge(params);}catch(IOException e){return ControlMessage.Response.empty(400);}
                publish(next);return ControlMessage.Response.empty(200);
            }
            Object url=params.get("url");
            if(params.containsKey("url")&&!(url instanceof String))return ControlMessage.Response.empty(400);
            // VideoInCar and arbitrary URL dispatch require separate implementations.
            if(url!=null&&!url.equals(""))return ControlMessage.Response.empty(501);
            callbackThread=Thread.currentThread();try{return ControlMessage.Response.empty(listener.hostUiRequested(this)?200:501);}
            finally{callbackThread=null;}
        }
    }
    private void publish(ModeState next)throws IOException{
        if(next==state)return;state=next;callbackThread=Thread.currentThread();
        try{listener.changed(this,next);}finally{callbackThread=null;}
    }
    /** Runs on the event reader before it consumes the next notification, so an ACK's old
     * state cannot overwrite a newer notification when the sending thread wakes late. */
    Result reply(int status,byte[] body)throws IOException{
        long protocolStatus=0;Map<String,Object> params=null;
        if(status==200&&body.length!=0){
            Map<String,Object> dict=BinaryPlist.dictionary(BinaryPlist.decode(body));
            if(dict.containsKey("status")){
                Object value=dict.get("status");if(!(value instanceof Long))throw new IOException("Invalid mode reply status");protocolStatus=(Long)value;
            }
            if(dict.containsKey("params"))params=BinaryPlist.dictionary(dict.get("params"));
        }
        synchronized(lock){usable();if(status==200&&protocolStatus==0&&params!=null)publish(state.merge(params));return new Result(status,protocolStatus,state);}
    }
    public void close(){
        synchronized(lock){if(closed)return;closed=true;ready=false;
            callbackThread=Thread.currentThread();try{listener.closed(this);}catch(RuntimeException ignored){}catch(LinkageError ignored){}finally{callbackThread=null;}}
    }
}
