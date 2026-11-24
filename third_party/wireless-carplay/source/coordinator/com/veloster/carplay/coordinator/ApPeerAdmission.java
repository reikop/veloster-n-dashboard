/* SPDX-License-Identifier: GPL-3.0-only. Root watcher cache -> provisional receiver admission. */
package com.veloster.carplay.coordinator;
import android.os.SystemClock;
import com.veloster.carplay.control.ProvisionalPeerAdmission;
import com.veloster.carplay.control.ReceiverAdmission;
import com.veloster.carplay.control.ReceiverScope;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

/** Owns one monitor after successful construction. No shell, DNS or blocking
 * observation in admission callbacks. AP membership never identifies the phone.
 * close retires admission immediately; awaitStopped confirms monitor child drain. */
public final class ApPeerAdmission implements ReceiverAdmission,Closeable {
    public interface Generation {
        /** Check the exact coordinator owner/AP/selected bond; memory-only. */
        void checkCurrent(ReceiverScope scope)throws IOException;
    }
    interface Clock {long millis();}
    private final ReceiverScope scope;private final ApStationMonitor monitor;
    private final Generation generation;private final int interfaceIndex;private final Clock clock;
    private final ProvisionalPeerAdmission admission;
    private volatile boolean closed;
    /** bootstrapUsb, if present, must originate from this selected phone's authenticated bootstrap. */
    public ApPeerAdmission(ReceiverScope scope,ApStationMonitor monitor,int interfaceIndex,Generation generation,String bootstrapUsb)throws IOException{
        this(scope,monitor,interfaceIndex,generation,bootstrapUsb,new Clock(){public long millis(){return SystemClock.elapsedRealtime();}});
    }
    public static ApPeerAdmission withBootstrap(ReceiverScope scope,ApStationMonitor monitor,int interfaceIndex,Generation generation,ProvisionalPeerAdmission.BootstrapIdentity bootstrap)throws IOException{
        if(bootstrap==null)throw new NullPointerException();
        return new ApPeerAdmission(scope,monitor,interfaceIndex,generation,null,new Clock(){public long millis(){return SystemClock.elapsedRealtime();}},bootstrap);
    }
    ApPeerAdmission(ReceiverScope scope,ApStationMonitor monitor,int interfaceIndex,Generation generation,String bootstrapUsb,Clock clock)throws IOException{
        this(scope,monitor,interfaceIndex,generation,bootstrapUsb,clock,null);
    }
    ApPeerAdmission(ReceiverScope scope,ApStationMonitor monitor,int interfaceIndex,Generation generation,String bootstrapUsb,Clock clock,ProvisionalPeerAdmission.BootstrapIdentity bootstrap)throws IOException{
        if(scope==null||monitor==null||generation==null||clock==null)throw new NullPointerException();
        if(interfaceIndex<1)throw new IllegalArgumentException("AP interface index");
        this.scope=scope;this.monitor=monitor;this.interfaceIndex=interfaceIndex;this.generation=generation;this.clock=clock;
        // Validate borrowed inputs before ownership transfers at successful return.
        monitor.checkCurrent(scope.apSession,interfaceIndex);generation.checkCurrent(scope);
        ProvisionalPeerAdmission.Source source=new ProvisionalPeerAdmission.Source(){
            public void checkGeneration(ReceiverScope supplied)throws IOException{
                if(supplied!=ApPeerAdmission.this.scope)throw new IOException("Foreign receiver generation");
                ApPeerAdmission.this.checkCurrent();
            }
            public ProvisionalPeerAdmission.Observation latest()throws IOException{return observation();}
        };
        ProvisionalPeerAdmission.Clock admissionClock=new ProvisionalPeerAdmission.Clock(){public long now(){return ApPeerAdmission.this.clock.millis()*1000000L;}};
        admission=bootstrap==null?new ProvisionalPeerAdmission(scope,source,bootstrapUsb,2500,admissionClock):ProvisionalPeerAdmission.withBootstrap(scope,source,bootstrap,2500,admissionClock);
    }
    public ReceiverScope scope(){return scope;}
    public void checkCurrent()throws IOException{
        if(closed)throw new IOException("AP admission retired");
        try{
            monitor.checkCurrent(scope.apSession,interfaceIndex);generation.checkCurrent(scope);
            monitor.checkCurrent(scope.apSession,interfaceIndex);
            if(closed)throw new IOException("AP admission retired");
        }catch(IOException failure){closed=true;monitor.close();throw failure;}
    }
    private ProvisionalPeerAdmission.Observation observation()throws IOException{
        checkCurrent();ApStationSnapshot value=monitor.latest();long now=clock.millis();checkCurrent();
        if(value==null)return null;
        if(!scope.apSession.equals(value.sessionToken)||interfaceIndex!=value.interfaceIndex)throw new IOException("AP sample scope changed");
        long age=now-value.sampledBoottimeMillis;
        if(age<0||age>2500||value.stationMac==null||value.ipv4==null||value.associationToken==null)return null;
        // Parser limits IPv4 to the manager's fixed pool. getByAddress never performs DNS.
        String[] octets=value.ipv4.split("\\.");byte[] address=new byte[4];
        for(int i=0;i<4;i++)address[i]=(byte)Integer.parseInt(octets[i]);
        return new ProvisionalPeerAdmission.Observation(scope,InetAddress.getByAddress(address),value.associationToken,value.sampledBoottimeMillis*1000000L);
    }
    public Peer admit(InetAddress remote)throws IOException{return admission.admit(remote);}
    public void close(){closed=true;monitor.close();}
    public boolean awaitStopped(long millis)throws InterruptedException{return monitor.awaitStopped(millis);}
}
