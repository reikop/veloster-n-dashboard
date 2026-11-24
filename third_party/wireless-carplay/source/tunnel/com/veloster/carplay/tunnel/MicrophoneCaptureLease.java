/* SPDX-License-Identifier: GPL-3.0-only. Physical microphone ownership, independent of negotiated input sockets. */
package com.veloster.carplay.tunnel;
import java.io.IOException;

/** A reservation owns the physical capture allocation until its source really
 * stops and releases. No timeout, revocation, or successor can steal that slot.
 * Callers perform device I/O outside this monitor. Logical input endpoints may
 * wait without allocating any capture device. */
public final class MicrophoneCaptureLease {
    /** Receiver-generation ownership before a peer address/verification exists. */
    public interface ScopeLease {void checkCurrent()throws IOException;}
    private static final MicrophoneCaptureLease PROCESS=new MicrophoneCaptureLease();
    public static MicrophoneCaptureLease process(){return PROCESS;}
    private Reservation active;
    public final class Reservation {
        private final IapDataTunnel.Binding binding;private final IapDataTunnel.SessionLease session;private final Object endpoint;
        private final ScopeLease scope;
        private Reservation(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease session,Object endpoint){this.binding=binding;this.session=session;this.endpoint=endpoint;scope=null;}
        private Reservation(ScopeLease scope,Object endpoint){this.scope=scope;this.endpoint=endpoint;binding=null;session=null;}
        /** Only the exact owner may surrender a source that it has drained. */
        public void released(IapDataTunnel.Binding supplied,IapDataTunnel.SessionLease lease,Object token)throws IOException{
            synchronized(MicrophoneCaptureLease.this){
                if(active!=this||scope!=null||supplied!=binding||lease!=session||token!=endpoint)throw new IOException("Foreign or already released microphone reservation");
                active=null;MicrophoneCaptureLease.this.notifyAll();
            }
        }
        public void released(ScopeLease supplied,Object token)throws IOException{
            synchronized(MicrophoneCaptureLease.this){if(active!=this||scope==null||supplied!=scope||token!=endpoint)throw new IOException("Foreign microphone probe reservation");active=null;MicrophoneCaptureLease.this.notifyAll();}
        }
    }
    public Reservation acquire(IapDataTunnel.Binding binding,IapDataTunnel.SessionLease session,Object endpoint)throws IOException{
        if(binding==null||session==null||endpoint==null)throw new NullPointerException();
        // Never invoke a session/owner callback while holding the device arbiter.
        session.checkCurrent(binding);
        synchronized(this){if(active!=null)return null;active=new Reservation(binding,session,endpoint);return active;}
    }
    public synchronized boolean occupied(){return active!=null;}
    public Reservation acquire(ScopeLease scope,Object endpoint)throws IOException{
        if(scope==null||endpoint==null)throw new NullPointerException();scope.checkCurrent();
        synchronized(this){if(active!=null)return null;active=new Reservation(scope,endpoint);return active;}
    }
}
