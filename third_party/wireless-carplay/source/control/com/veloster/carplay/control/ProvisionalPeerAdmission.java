/* SPDX-License-Identifier: GPL-3.0-only. AP candidate admission with a separate media grant. */
package com.veloster.carplay.control;
import com.veloster.carplay.RfcommConnector;
import com.veloster.carplay.pairing.PairVerify;
import java.io.IOException;
import java.net.InetAddress;

/** Resolves the first-connect dependency: an observed AP candidate may establish
 * encrypted control/type130, but cannot feed media until received transport IDs
 * match the selected bonded Bluetooth peer or its prior bootstrap USB identity.
 * This is transport-ID corroboration, not an independent cryptographic BT proof. */
public final class ProvisionalPeerAdmission implements ReceiverAdmission {
    public interface Source {
        void checkGeneration(ReceiverScope scope)throws IOException;
        Observation latest()throws IOException;
    }
    public static final class Observation {
        public final ReceiverScope scope;public final InetAddress address;public final String association;public final long observedNanos;
        public Observation(ReceiverScope scope,InetAddress address,String association,long observedNanos){
            if(scope==null||address==null||address.isAnyLocalAddress()||address.isMulticastAddress()||association==null||!association.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("AP candidate observation");
            this.scope=scope;this.address=address;this.association=association;this.observedNanos=observedNanos;
        }
    }
    /** Monotonic nanosecond units in the same time domain as Observation.observedNanos.
     * Android AP sources use elapsedRealtime, including suspend, for both values. */
    public interface Clock {long now();}
    /** Memory-only evidence from the authenticated selected-phone bootstrap. Null means not received yet. */
    public interface BootstrapIdentity {String usbIdentifier(ReceiverScope scope)throws IOException;}
    private final ReceiverScope scope;private final Source source;private final String bootstrapUsb;private final Clock clock;private final long maxAge;private final BootstrapIdentity bootstrap;
    public ProvisionalPeerAdmission(ReceiverScope scope,Source source,String bootstrapUsb,long maxAgeMillis){
        this(scope,source,bootstrapUsb,maxAgeMillis,new Clock(){public long now(){return System.nanoTime();}});
    }
    public ProvisionalPeerAdmission(ReceiverScope scope,Source source,String bootstrapUsb,long maxAgeMillis,Clock clock){
        this(scope,source,bootstrapUsb,maxAgeMillis,clock,null);
    }
    public static ProvisionalPeerAdmission withBootstrap(ReceiverScope scope,Source source,BootstrapIdentity bootstrap,long maxAgeMillis,Clock clock){
        if(bootstrap==null)throw new NullPointerException();return new ProvisionalPeerAdmission(scope,source,null,maxAgeMillis,clock,bootstrap);
    }
    private ProvisionalPeerAdmission(ReceiverScope scope,Source source,String bootstrapUsb,long maxAgeMillis,Clock clock,BootstrapIdentity bootstrap){
        if(scope==null||source==null||clock==null)throw new NullPointerException();if(maxAgeMillis<100||maxAgeMillis>5000||bootstrapUsb!=null&&!validUsb(bootstrapUsb))throw new IllegalArgumentException("Candidate policy");
        this.scope=scope;this.source=source;this.bootstrapUsb=bootstrapUsb;this.clock=clock;this.bootstrap=bootstrap;maxAge=maxAgeMillis*1000000L;
    }
    private static boolean validUsb(String value){if(value.length()<1||value.length()>256)return false;for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return false;return true;}
    public ReceiverScope scope(){return scope;}
    public void checkCurrent()throws IOException{source.checkGeneration(scope);}
    public Peer admit(InetAddress remote)throws IOException{
        checkCurrent();final Observation first=source.latest();checkCurrent();
        if(first==null||first.scope!=scope||!first.address.equals(remote)||clock.now()-first.observedNanos<0||clock.now()-first.observedNanos>maxAge)return null;
        final PairVerify.Context context=scope.peer(remote);
        PairVerify.PhoneIdentityLease lease=new PairVerify.PhoneIdentityLease(){
            private boolean verified,identified,retired,bootstrapSeen;private long verifiedAt,lastSeen=first.observedNanos;private String knownUsb=bootstrapUsb,pendingUsb;
            private void refreshBootstrap()throws IOException{
                if(bootstrap==null)return;String value=bootstrap.usbIdentifier(scope);
                if(value==null){if(bootstrapSeen)throw new IOException("Bootstrap identity withdrawn");return;}
                if(!validUsb(value)||knownUsb!=null&&!knownUsb.equals(value))throw new IOException("Bootstrap identity changed");
                knownUsb=value;bootstrapSeen=true;
                if(pendingUsb!=null){if(!pendingUsb.equals(value))throw new IOException("Runtime/bootstrap USB mismatch");if(verified)identified=true;}
            }
            public synchronized void checkCurrent(PairVerify.Context supplied)throws IOException{
                try{
                    if(retired||supplied!=context)throw new IOException("Retired/foreign candidate");
                    ProvisionalPeerAdmission.this.checkCurrent();Observation current;
                    try{current=source.latest();}catch(IOException missing){current=null;}
                    ProvisionalPeerAdmission.this.checkCurrent();long now=clock.now();
                    if(verified&&!identified&&(now-verifiedAt<0||now-verifiedAt>30000000000L))throw new IOException("Phone transport identity timed out");
                    refreshBootstrap();
                    if(current!=null&&current.scope!=scope)throw new IOException("Candidate scope changed");
                    long age=current==null?-1:now-current.observedNanos;
                    if(current!=null&&age>=0&&age<=maxAge){
                        if(!first.address.equals(current.address))throw new IOException("Candidate address changed");
                        if(!identified&&!first.association.equals(current.association))throw new IOException("Unidentified association replaced");
                        lastSeen=current.observedNanos;return;
                    }
                    long gap=now-lastSeen;if(!verified||gap<0||gap>10000000000L)throw new IOException("Candidate observation unavailable");
                }catch(IOException denied){retired=true;throw denied;}
            }
            public synchronized void verificationComplete(PairVerify.Context supplied)throws IOException{checkCurrent(supplied);if(!verified){verifiedAt=clock.now();verified=true;}}
            public synchronized boolean mediaAllowed(PairVerify.Context supplied)throws IOException{checkCurrent(supplied);return identified;}
            public synchronized void transportIdentifiers(PairVerify.Context supplied,String bt,String usb)throws IOException{
                checkCurrent(supplied);
                try{
                    if(!verified||usb!=null&&!validUsb(usb))throw new IOException("Invalid phone identity phase");
                    String observed=bt==null?null:RfcommConnector.normalizeAddress(bt);
                    if(observed!=null&&!scope.selectedBluetooth.equals(observed))throw new IOException("Different Bluetooth phone");
                    if(knownUsb!=null&&usb!=null&&!knownUsb.equals(usb))throw new IOException("Different USB transport identity");
                    if(pendingUsb!=null&&usb!=null&&!pendingUsb.equals(usb))throw new IOException("Runtime USB identity changed");
                    if(observed==null){
                        if(usb==null)throw new IOException("No received phone identity");
                        if(knownUsb==null){
                            if(bootstrap==null)throw new IOException("No matching received phone identity");
                            pendingUsb=usb;return; // Keep media blocked until authenticated bootstrap arrives or deadline expires.
                        }
                    }
                    if(knownUsb==null&&usb!=null)knownUsb=usb;identified=true;
                }catch(IOException denied){retired=true;throw denied;}
                 catch(IllegalArgumentException denied){retired=true;throw new IOException("Malformed Bluetooth phone identity");}
            }
        };
        lease.checkCurrent(context);return new Peer(context,lease);
    }
}
