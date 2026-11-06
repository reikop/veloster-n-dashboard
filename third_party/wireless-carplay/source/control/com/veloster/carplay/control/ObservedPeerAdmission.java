/* SPDX-License-Identifier: GPL-3.0-only. Cached association with per-link revocation. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.PairVerify;
import java.io.IOException;
import java.net.InetAddress;

/** The platform collector must establish the selected Bluetooth phone's Wi-Fi
 * association. This class neither infers that mapping from DHCP/ARP nor polls a
 * shell. A missing/stale observation rejects admission while publication remains
 * available. A verified TCP session tolerates a bounded transient link gap; a
 * link reassociation alone does not discard its authenticated keys/counters. */
public final class ObservedPeerAdmission implements ReceiverAdmission {
    public interface Source {
        void checkGeneration(ReceiverScope scope)throws IOException;
        Observation latest()throws IOException;
    }
    public static final class Observation {
        public final ReceiverScope scope;public final InetAddress address;public final String association;
        public final long observedNanos;
        /** association must change after loss/rejoin, including when IP is reused. */
        public Observation(ReceiverScope scope,InetAddress address,String association,long observedNanos){
            if(scope==null||address==null||address.isAnyLocalAddress()||address.isMulticastAddress()||association==null||!association.matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("Association observation");
            this.scope=scope;this.address=address;this.association=association;this.observedNanos=observedNanos;
        }
    }
    interface Clock {long now();}
    private final ReceiverScope scope;private final Source source;private final Clock clock;private final long maxAge,linkGrace;
    public ObservedPeerAdmission(ReceiverScope scope,Source source,long maxAgeMillis){
        this(scope,source,maxAgeMillis,new Clock(){public long now(){return System.nanoTime();}});
    }
    ObservedPeerAdmission(ReceiverScope scope,Source source,long maxAgeMillis,Clock clock){
        this(scope,source,maxAgeMillis,10000,clock);
    }
    public ObservedPeerAdmission(ReceiverScope scope,Source source,long maxAgeMillis,long linkGraceMillis){
        this(scope,source,maxAgeMillis,linkGraceMillis,new Clock(){public long now(){return System.nanoTime();}});
    }
    ObservedPeerAdmission(ReceiverScope scope,Source source,long maxAgeMillis,long linkGraceMillis,Clock clock){
        if(scope==null||source==null||clock==null)throw new NullPointerException();
        if(maxAgeMillis<100||maxAgeMillis>5000)throw new IllegalArgumentException("Observation age bound");
        if(linkGraceMillis<maxAgeMillis||linkGraceMillis<1000||linkGraceMillis>30000)throw new IllegalArgumentException("Link grace bound");
        this.scope=scope;this.source=source;this.clock=clock;maxAge=maxAgeMillis*1000000L;linkGrace=linkGraceMillis*1000000L;
    }
    public ReceiverScope scope(){return scope;}
    public void checkCurrent()throws IOException{source.checkGeneration(scope);}
    private Observation observation()throws IOException{
        checkCurrent();Observation value=source.latest();checkCurrent();
        if(value==null||value.scope!=scope)return null;
        long age=clock.now()-value.observedNanos;if(age<0||age>maxAge)return null;return value;
    }
    public Peer admit(InetAddress remote)throws IOException{
        final Observation observed=observation();if(observed==null||!observed.address.equals(remote))return null;
        final PairVerify.Context context=scope.peer(remote);
        PairVerify.OwnerLease lease=new PairVerify.VerifiedOwnerLease(){
            private boolean verified,retired;private long lastSeen=observed.observedNanos;
            public synchronized void checkCurrent(PairVerify.Context value)throws IOException{
                try{
                    if(retired||value!=context)throw new IOException("Retired/foreign admission capability");
                    ObservedPeerAdmission.this.checkCurrent();Observation current;
                    try{current=source.latest();}catch(IOException unavailable){current=null;}
                    ObservedPeerAdmission.this.checkCurrent();
                    if(current!=null&&current.scope!=scope)throw new IOException("Selected phone scope replaced");
                    long now=clock.now(),age=current==null?-1:now-current.observedNanos;
                    if(current!=null&&age>=0&&age<=maxAge){
                        if(!observed.address.equals(current.address))throw new IOException("Selected phone address changed");
                        if(!verified&&!observed.association.equals(current.association))throw new IOException("Unverified association replaced");
                        lastSeen=current.observedNanos;return;
                    }
                    long gap=now-lastSeen;
                    if(!verified||gap<0||gap>linkGrace)throw new IOException("Selected phone observation unavailable");
                    // Existing TCP crypto/session stays unchanged; this grants no new admission.
                }catch(IOException failure){retired=true;throw failure;}
            }
            public synchronized void verificationComplete(PairVerify.Context value)throws IOException{checkCurrent(value);verified=true;}
        };
        lease.checkCurrent(context);return new Peer(context,lease);
    }
}
