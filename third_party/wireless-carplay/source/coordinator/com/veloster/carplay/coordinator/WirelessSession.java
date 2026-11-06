/* SPDX-License-Identifier: GPL-3.0-only. Isolated wireless integration module. */
package com.veloster.carplay.coordinator;

import com.veloster.carplay.Iap2MessageSession;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.RfcommConnector;
import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** One selected phone and one AP generation. Run on a worker, never the UI thread.
 * Owns only its RFCOMM/receiver; does not stop a shared AP or globally disable BT.
 * HANDOFF keeps the receiver alive until stop, revocation or observed link loss.
 * The caller owns the OEM Binder binding and must keep it alive through run().
 */
public final class WirelessSession implements Closeable {
    public enum State { NEW, CONNECTING, BOOTSTRAP, HANDOFF, STOPPED, FAILED, CLEANUP_REQUIRED }
    public enum End { USER_STOP, DECLINED, DISCONNECTED, FAILED }
    public interface Transport extends Closeable {
        Link open(String selectedAddress) throws IOException,InterruptedException;
    }
    public static final class Link {
        public final Iap2MessageSession messages;
        public final String localAddress;
        public Link(Iap2MessageSession messages,String localAddress) {
            if(messages==null)throw new NullPointerException();
            this.messages=messages;this.localAddress=RfcommConnector.normalizeAddress(localAddress);
        }
    }
    public static final class RfcommTransport implements Transport {
        private final RfcommConnector connector;
        public RfcommTransport(RfcommConnector connector){if(connector==null)throw new NullPointerException();this.connector=connector;}
        public Link open(String address)throws IOException,InterruptedException {
            RfcommConnector.Connected connected=connector.connect(address,30000);
            return new Link(connected.stream,connected.localAddress);
        }
        public void close()throws IOException{connector.close();}
    }
    public interface Observer { void state(State state); }
    /** Optional lifecycle for an environment holding exact receiver-attempt evidence. */
    public interface AttemptEnvironment {
        void attemptStarted(String receiverOwner,String apSession,String selectedAddress)throws IOException;
        void attemptStopped(String receiverOwner)throws IOException;
    }
    public static final class Result {
        public final State state;
        public final End end;
        public final boolean cleanupRequired;
        public final boolean wirelessCarPlayVerified=false;
        private Result(State state,End end,boolean pending){this.state=state;this.end=end;cleanupRequired=pending;}
    }
    private final Transport transport;
    private final ReceiverControl receiver;
    private final Iap2WirelessController.Authentication authentication;
    private final Iap2WirelessController.Environment platform;
    private final Iap2WirelessController.Consent consent;
    private final Iap2WirelessController.Identity identity;
    private final Iap2WirelessController.StartMode mode;
    private final Observer observer;
    private final String phone;
    private final String receiverOwner;
    private final AtomicBoolean used=new AtomicBoolean(),stopping=new AtomicBoolean();
    private volatile boolean finished,cleanupRequired;
    private volatile State state=State.NEW;
    private volatile Iap2WirelessController bootstrap;
    private volatile Iap2MessageSession messages;
    private String token;
    // Set BEFORE begin: a lost reply may still mean the OEM side created a receiver.
    private boolean receiverAttempted,receiverPrepared,platformAttempted;

    public WirelessSession(Transport transport,ReceiverControl receiver,
                           Iap2WirelessController.Authentication authentication,
                           Iap2WirelessController.Environment platform,
                           Iap2WirelessController.Consent consent,
                           Iap2WirelessController.Identity identity,String selectedAddress,
                           Iap2WirelessController.StartMode mode,Observer observer) {
        this(transport,receiver,authentication,platform,consent,identity,selectedAddress,mode,observer,newOwner());
    }
    /** The app service should persist this unique attempt token BEFORE run(), and
     * retain it until stop is acknowledged, so process restart can retry cleanup.
     * Never reuse an AP token or a previous attempt's token to adopt a receiver.
     */
    public WirelessSession(Transport transport,ReceiverControl receiver,
                           Iap2WirelessController.Authentication authentication,
                           Iap2WirelessController.Environment platform,
                           Iap2WirelessController.Consent consent,
                           Iap2WirelessController.Identity identity,String selectedAddress,
                           Iap2WirelessController.StartMode mode,Observer observer,String receiverOwner) {
        if(transport==null || receiver==null || authentication==null || platform==null || consent==null || identity==null || mode==null)
            throw new NullPointerException();
        this.transport=transport;this.receiver=receiver;this.authentication=authentication;
        this.platform=platform;this.consent=consent;this.identity=identity;this.mode=mode;this.observer=observer;
        phone=RfcommConnector.normalizeAddress(selectedAddress);
        if(receiverOwner==null || !receiverOwner.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid receiver owner");
        this.receiverOwner=receiverOwner;
    }
    public static String newOwner() {
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);char[] chars=new char[32];
        final String hex="0123456789abcdef";
        for(int i=0;i<bytes.length;i++){chars[2*i]=hex.charAt((bytes[i]>>>4)&15);chars[2*i+1]=hex.charAt(bytes[i]&15);}
        return new String(chars);
    }
    public String receiverOwner(){return receiverOwner;}
    public String selectedAddress(){return phone;}
    public State state(){return state;}
    public boolean cleanupRequired(){return cleanupRequired;}
    private void state(State next) {
        state=next;
        if(observer!=null)try{observer.state(next);}catch(RuntimeException ignored){/* UI observers cannot skip cleanup. */}
    }
    private void check()throws IOException {
        if(stopping.get())throw new IOException("Wireless session stopping");
        if(token==null || !platform.current(token))throw new IOException("Wireless AP generation unavailable");
    }
    private void scope(String ap,String selected)throws IOException {
        if(!token.equals(ap) || !phone.equals(selected))throw new IOException("Wireless session scope mismatch");
        check();
    }
    private ReceiverControl.Reply ownedStatus()throws IOException {
        ReceiverControl.Reply status=receiver.request(ReceiverControl.STATUS,receiverOwner);
        if(!status.owned())throw new IOException("OEM receiver ownership lost");
        return status;
    }
    private void prepareReceiver()throws IOException {
        check();
        if(receiverPrepared)return;
        receiverAttempted=true;
        ReceiverControl.Reply reply=receiver.request(ReceiverControl.BEGIN,receiverOwner);
        if(!reply.owned())throw new IOException("OEM receiver unavailable");
        check();receiverPrepared=true;
    }
    private final Iap2WirelessController.Consent scopedConsent=new Iap2WirelessController.Consent() {
        public Iap2WirelessController.Permission decision(String peer,String ap)throws IOException {
            scope(ap,peer);
            Iap2WirelessController.Permission permission=consent.decision(peer,ap);
            // The bootstrap calls this only AFTER the phone accepts real OEM authentication.
            if(permission==Iap2WirelessController.Permission.ALLOW)prepareReceiver();
            return permission;
        }
    };
    private final Iap2WirelessController.Environment scopedEnvironment=new Iap2WirelessController.TransportObservationEnvironment() {
        public Iap2WirelessController.ApSnapshot accessPoint()throws IOException {
            check();Iap2WirelessController.ApSnapshot ap=platform.accessPoint();
            if(!token.equals(ap.session)){ap.close();throw new IOException("Wireless AP generation changed");}
            return ap;
        }
        public boolean current(String ap)throws IOException {return !stopping.get() && token.equals(ap) && platform.current(ap);}
        public Iap2WirelessController.ReceiverIdentity receiver(String ap)throws IOException {
            scope(ap,phone);ownedStatus();return platform.receiver(ap);
        }
        public Iap2WirelessController.HandoffObservation handoff(String ap,String peer)throws IOException {
            scope(ap,peer);ReceiverControl.Reply actual=ownedStatus();
            Iap2WirelessController.HandoffObservation observed=platform.handoff(ap,peer);
            if(observed==null)return null;
            // Keep peer/AP fields so the controller can reject mismatched evidence.
            return new Iap2WirelessController.HandoffObservation(observed.apSession,observed.selectedAddress,
                observed.wifiAssociated,observed.wifiIap2Established,
                observed.nativeSessionStarted && actual.nativeStarted(),
                observed.disableBluetoothRequested,observed.bluetoothProfilesDisconnected);
        }
        public boolean disconnectBluetoothProfiles(String ap,String peer)throws IOException {
            scope(ap,peer);ownedStatus();return platform.disconnectBluetoothProfiles(ap,peer);
        }
        public void transportIdentifiers(String ap,String peer,String usb)throws IOException {
            scope(ap,peer);platform.transportIdentifiers(ap,peer,usb);
        }
        public void receivedTransportIdentifiers(String ap,String peer,String observed,String usb)throws IOException {
            scope(ap,peer);
            if(platform instanceof Iap2WirelessController.TransportObservationEnvironment)
                ((Iap2WirelessController.TransportObservationEnvironment)platform).receivedTransportIdentifiers(ap,peer,observed,usb);
            else platform.transportIdentifiers(ap,peer,usb);
            scope(ap,peer);
        }
    };
    public Result run() {
        if(!used.compareAndSet(false,true))throw new IllegalStateException("Wireless session already used");
        End end=End.FAILED;
        try {
            if(stopping.get())throw new IOException("Wireless session stopping");
            Iap2WirelessController.ApSnapshot ap=platform.accessPoint();
            try{token=ap.session;}finally{ap.close();}
            if(token.equals(receiverOwner))throw new IOException("Receiver attempt must be distinct from AP generation");
            if(platform instanceof AttemptEnvironment){platformAttempted=true;((AttemptEnvironment)platform).attemptStarted(receiverOwner,token,phone);}
            check();state(State.CONNECTING);
            Link link=transport.open(phone);messages=link.messages;
            check();
            if(!identity.bluetoothAddress.equals(link.localAddress))throw new IOException("Accessory Bluetooth identity changed");
            bootstrap=new Iap2WirelessController(link.messages,authentication,scopedEnvironment,scopedConsent,
                identity,phone,null,mode);
            check();state(State.BOOTSTRAP);
            Iap2WirelessController.Result result=bootstrap.run();
            if(result.state==Iap2WirelessController.State.HANDOFF) {
                check();state(State.HANDOFF);
                while(!stopping.get()) {
                    check();
                    Iap2WirelessController.Permission permission=consent.decision(phone,token);
                    if(permission!=Iap2WirelessController.Permission.ALLOW){end=End.DECLINED;break;}
                    Iap2WirelessController.HandoffObservation live=scopedEnvironment.handoff(token,phone);
                    if(live==null || !token.equals(live.apSession) || !phone.equals(live.selectedAddress)
                        || !live.wifiAssociated || !live.wifiIap2Established || !live.nativeSessionStarted) {
                        end=End.DISCONNECTED;break;
                    }
                    Thread.sleep(250);
                }
            } else if(result.state==Iap2WirelessController.State.DECLINED)end=End.DECLINED;
            else if(result.state==Iap2WirelessController.State.DISCONNECTED)end=End.DISCONNECTED;
        } catch(InterruptedException interrupted) {stopping.set(true);Thread.currentThread().interrupt();}
        catch(IOException unavailable) { /* Sanitized state only; no remote payload logging. */ }
        catch(RuntimeException unavailable) { }
        finally {
            // Temporarily clear worker interruption so a bounded stop receipt can be read.
            boolean interrupted=Thread.interrupted();
            try{cleanup();}finally{if(interrupted)Thread.currentThread().interrupt();}
        }
        if(stopping.get())end=End.USER_STOP;
        return finish(end);
    }
    private Result finish(End end) {
        state(cleanupRequired?State.CLEANUP_REQUIRED:end==End.FAILED?State.FAILED:State.STOPPED);
        finished=true;return new Result(state,end,cleanupRequired);
    }
    private void cleanup() {
        cleanupRequired=false;
        Iap2MessageSession session=messages;
        if(session!=null)try{session.close();}catch(Exception ignored){}
        try{transport.close();}catch(Exception ignored){}
        if(receiverAttempted) {
            cleanupRequired=true;
            try{if(receiver.request(ReceiverControl.STOP,receiverOwner).released()){cleanupRequired=false;receiverAttempted=false;}}
            catch(Exception unavailable){ }
        }
        if(platformAttempted){
            try{((AttemptEnvironment)platform).attemptStopped(receiverOwner);platformAttempted=false;}
            catch(Exception unavailable){cleanupRequired=true;}
        }
    }
    /** Nonblocking with respect to the worker's OEM Binder call; socket close cancels RFCOMM.
     * run() alone performs receiver cleanup AFTER any in-flight begin has returned.
     */
    public void close()throws IOException {
        stopping.set(true);IOException failure=null;
        Iap2WirelessController controller=bootstrap;
        if(controller!=null)try{controller.cancel();}catch(IOException e){failure=e;}
        try{transport.close();}catch(IOException e){failure=e;}
        if(failure!=null)throw new IOException("Wireless transport close unavailable");
    }
    /** A failed/unknown stop remains visible; no automatic success or receiver adoption. */
    public synchronized boolean retryCleanup() {
        if(!finished)throw new IllegalStateException("Session worker still running");
        if(cleanupRequired){cleanup();if(!cleanupRequired)state(State.STOPPED);}
        return !cleanupRequired;
    }
}
