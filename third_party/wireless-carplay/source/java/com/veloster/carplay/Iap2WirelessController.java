/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md.
 * Wireless CSM contracts adapted from DiPlay-5.1 5776c389. OEM auth and receiver
 * remain injected real services. No authentication or receiver identity fallback.
 */
package com.veloster.carplay;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bluetooth bootstrap only. A HANDOFF result does not assert decoded video/audio.
 * run() owns the bootstrap session and closes it on every terminal path.
 */
public final class Iap2WirelessController {
    private static final Charset UTF8=Charset.forName("UTF-8");
    private static final long STEP_TIMEOUT=30000, SEND_TIMEOUT=5000;
    public enum State { NEW, IDENTIFYING, AUTHENTICATING, WAITING_PERMISSION,
        WIFI_SENT, WAITING_RECEIVER, HANDOFF, DISCONNECTED, DECLINED, CANCELLED, FAILED }
    public enum Permission { PENDING, ALLOW, DENY }
    /** Explicit receiver capability choice; never inferred from missing metadata. */
    public enum StartMode { CSM_START_SESSION, BONJOUR_CONTROL }
    public interface Authentication {
        byte[] certificate() throws IOException;
        byte[] sign(byte[] challenge) throws IOException;
    }
    public interface Consent {
        Permission decision(String selectedAddress,String apSession) throws IOException;
    }
    public interface Environment {
        /** Fresh credentials for the currently ready, owned AP. Caller erases snapshot. */
        ApSnapshot accessPoint() throws IOException;
        boolean current(String apSession) throws IOException;
        /** Actual metadata from the same active receiver; null when not known. */
        ReceiverIdentity receiver(String apSession) throws IOException;
        /** Observations must belong to this AP session and the selected phone. */
        HandoffObservation handoff(String apSession,String selectedAddress) throws IOException;
        /** Called only after verified Wi-Fi/iAP2 handoff. Must verify selected-device
         * profiles disconnected; must not globally disable the Bluetooth adapter. */
        boolean disconnectBluetoothProfiles(String apSession,String selectedAddress) throws IOException;
        /** Legacy callback: selectedAddress is link context, not a received BT field.
         * Evidence consumers must implement TransportObservationEnvironment instead. */
        void transportIdentifiers(String apSession,String selectedAddress,String usbIdentifier) throws IOException;
    }
    /** Optional actual-wire observation. selectedAddress is the bonded link context;
     * observedBluetooth is null when the notification omitted that field. Called
     * only after accessory authentication succeeds on this selected RFCOMM link. */
    public interface TransportObservationEnvironment extends Environment {
        void receivedTransportIdentifiers(String apSession,String selectedAddress,String observedBluetooth,String usbIdentifier)throws IOException;
    }
    public interface Observer {void state(State value);}
    public static final class Identity {
        public final String name,model,manufacturer,serial,firmware,hardware,language,bluetoothAddress;
        public Identity(String name,String model,String manufacturer,String serial,String firmware,
                        String hardware,String language,String bluetoothAddress) {
            this.name=text(name,128);this.model=text(model,128);this.manufacturer=text(manufacturer,128);
            this.serial=text(serial,128);this.firmware=text(firmware,128);this.hardware=text(hardware,128);
            this.language=text(language,16);this.bluetoothAddress=RfcommConnector.normalizeAddress(bluetoothAddress);
        }
    }
    public static final class ApSnapshot implements Closeable {
        public final String session,ssid,ipv4;
        public final int channel;
        private final char[] passphrase;
        private final byte[] bssid;
        private boolean closed;
        public ApSnapshot(String session,String ssid,char[] passphrase,int channel,String ipv4,byte[] bssid) {
            if(session==null || !session.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid AP session");
            this.session=session;this.ssid=text(ssid,32);this.ipv4=ipv4(ipv4);
            if(!(channel>=1 && channel<=11) && channel!=36 && channel!=40 && channel!=44 && channel!=48)
                throw new IllegalArgumentException("Unsupported AP channel");
            if(passphrase==null || passphrase.length<8 || passphrase.length>63)throw new IllegalArgumentException("Invalid AP passphrase");
            for(char c:passphrase)if(c<32 || c>126)throw new IllegalArgumentException("Invalid AP passphrase");
            if(bssid!=null && bssid.length!=6)throw new IllegalArgumentException("Invalid AP BSSID");
            this.passphrase=passphrase.clone();this.channel=channel;this.bssid=bssid==null?null:bssid.clone();
        }
        private byte[] secret() {
            if(closed)throw new IllegalStateException("AP credentials already erased");
            byte[] bytes=new byte[passphrase.length+1];for(int i=0;i<passphrase.length;i++)bytes[i]=(byte)passphrase[i];return bytes;
        }
        public void close(){Arrays.fill(passphrase,'\0');closed=true;}
    }
    public static final class ReceiverIdentity {
        public final String apSession,deviceIdentifier,publicKey,sourceVersion;
        public final int port;
        public ReceiverIdentity(String apSession,int port,String deviceIdentifier,String publicKey,String sourceVersion) {
            if(apSession==null || !apSession.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid receiver AP session");
            if(port<1 || port>65535)throw new IllegalArgumentException("Invalid observed receiver port");
            this.apSession=apSession;this.port=port;this.deviceIdentifier=text(deviceIdentifier,128);
            this.publicKey=text(publicKey,512);this.sourceVersion=text(sourceVersion,64);
        }
    }
    public static final class HandoffObservation {
        public final String apSession,selectedAddress;
        public final boolean wifiAssociated,wifiIap2Established,nativeSessionStarted;
        public final boolean disableBluetoothRequested,bluetoothProfilesDisconnected;
        public HandoffObservation(String token,String address,boolean wifi,boolean iap2,boolean nativeStarted,
                                  boolean disableBluetooth,boolean profilesDisconnected) {
            apSession=token;selectedAddress=RfcommConnector.normalizeAddress(address);
            wifiAssociated=wifi;wifiIap2Established=iap2;nativeSessionStarted=nativeStarted;
            disableBluetoothRequested=disableBluetooth;bluetoothProfilesDisconnected=profilesDisconnected;
        }
    }
    public static final class Result {
        public final State state;
        public final int wifiConfigurations,startSessions;
        public final StartMode startMode;
        public final boolean wirelessCarPlayVerified=false;
        private Result(State state,int wifi,int starts,StartMode mode){this.state=state;wifiConfigurations=wifi;startSessions=starts;startMode=mode;}
    }
    private final Iap2MessageSession session;
    private final Authentication auth;
    private final Environment environment;
    private final Consent consent;
    private final Observer observer;
    private final Identity identity;
    private final String peer;
    private final StartMode startMode;
    private final AtomicBoolean cancelled=new AtomicBoolean(),started=new AtomicBoolean();
    private volatile State state=State.NEW;
    private String token,ssid;
    private int channel,wifiSent,startSent;
    private Boolean phoneEnabled;
    private boolean pendingWifi,pendingStart,requestedWifi;

    public Iap2WirelessController(Iap2MessageSession session,Authentication auth,Environment environment,
                                  Consent consent,Identity identity,String selectedAddress,Observer observer) {
        this(session,auth,environment,consent,identity,selectedAddress,observer,StartMode.CSM_START_SESSION);
    }
    public Iap2WirelessController(Iap2MessageSession session,Authentication auth,Environment environment,
                                  Consent consent,Identity identity,String selectedAddress,Observer observer,StartMode mode) {
        if(session==null || auth==null || environment==null || consent==null || identity==null || mode==null)throw new NullPointerException();
        this.session=session;this.auth=auth;this.environment=environment;this.consent=consent;
        this.identity=identity;this.peer=RfcommConnector.normalizeAddress(selectedAddress);this.observer=observer;startMode=mode;
    }
    public State state(){return state;}
    private void state(State value){if(state==value)return;state=value;if(observer!=null)observer.state(value);}
    public void cancel() throws IOException {cancelled.set(true);session.close();}
    private static long millis(){return System.nanoTime()/1000000L;}
    private void check() throws IOException,InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(cancelled.get())throw new Cancelled();
        if(!environment.current(token))throw new IOException("AP session changed or stopped");
    }
    private static final class Cancelled extends IOException {private static final long serialVersionUID=1;}
    public Result run() throws IOException,InterruptedException {
        if(!started.compareAndSet(false,true))throw new IllegalStateException("Controller already used");
        try {
            ApSnapshot ap=environment.accessPoint();
            try{token=ap.session;ssid=ap.ssid;channel=ap.channel;}finally{ap.close();}
            check();
            if(!session.awaitReady(STEP_TIMEOUT))throw new IOException("iAP2 link did not become ready");
            identify();authenticate();state(State.WAITING_PERMISSION);
            while(true) {
                check();
                Permission permission=consent.decision(peer,token);
                if(permission==null)throw new IOException("Consent result unavailable");
                if(permission==Permission.DENY){state(State.DECLINED);return result();}
                if(permission==Permission.ALLOW && !Boolean.FALSE.equals(phoneEnabled)) {
                    if(pendingWifi)sendWifi();
                    if(pendingStart)sendStartIfKnown();
                    if((wifiSent>0 || startSent>0) && completedHandoff()){state(State.HANDOFF);return result();}
                }
                Iap2Csm.Frame frame=session.receive(250);
                check();
                if(frame==null) {
                    if(session.isClosed()){state(State.DISCONNECTED);return result();}
                    continue;
                }
                switch(frame.message) {
                    case 0x5702:
                        frame.fields(); // Reject malformed TLVs without requiring undocumented optional fields.
                        requestedWifi=true;pendingWifi=true;break;
                    case 0x4e0d:
                        phoneEnabled=Boolean.valueOf(booleanField(frame.fields(),0));
                        if(!phoneEnabled.booleanValue()){pendingWifi=false;pendingStart=false;}
                        break;
                    case 0x4e0e:
                        transport(frame);
                        if(requestedWifi && wifiSent>0)pendingWifi=true;
                        break;
                    case 0x4300:
                        Boolean wireless=wirelessAvailability(frame);
                        if(wireless!=null){phoneEnabled=wireless;pendingStart=wireless.booleanValue() && startMode==StartMode.CSM_START_SESSION;}
                        break;
                    case 0xaa04:throw new IOException("Phone rejected accessory authentication");
                    case 0xaa05:throw new IOException("Unexpected authentication success notification");
                    case 0x1d00:throw new IOException("Phone restarted identification during bootstrap");
                    default:
                        // We advertise no media/vehicle/EA subscriptions on this temporary BT link.
                        frame.fields();break;
                }
            }
        } catch(Cancelled e){state(State.CANCELLED);return result();}
        catch(InterruptedException e){state(State.CANCELLED);Thread.currentThread().interrupt();throw e;}
        catch(IOException e){state(State.FAILED);throw e;}
        catch(RuntimeException e){state(State.FAILED);throw new IOException("Wireless bootstrap failed",e);}
        finally{try{session.close();}catch(IOException e){state(State.FAILED);throw e;}}
    }
    private Result result(){return new Result(state,wifiSent,startSent,startMode);}
    private Iap2Csm.Frame next(long deadline) throws IOException,InterruptedException {
        while(true) {
            check();long left=deadline-millis();if(left<=0)throw new IOException("iAP2 negotiation timed out");
            Iap2Csm.Frame frame=session.receive(Math.min(250,left));
            check();
            if(frame!=null)return frame;if(session.isClosed())throw new IOException("iAP2 negotiation disconnected");
        }
    }
    private void send(Iap2Csm.Frame frame) throws IOException,InterruptedException {check();session.send(frame,SEND_TIMEOUT);}
    private void identify() throws IOException,InterruptedException {
        state(State.IDENTIFYING);long deadline=millis()+STEP_TIMEOUT;int sent=0;
        while(true) {
            Iap2Csm.Frame frame=next(deadline);
            if(frame.message==0x1d00) {
                frame.fields();if(++sent>4)throw new IOException("Repeated identification limit");send(identification(identity,ssid,startMode));
            } else if(frame.message==0x1d02) {
                frame.fields();if(sent==0)throw new IOException("Identification accepted before information sent");return;
            } else if(frame.message==0x1d03){frame.fields();throw new IOException("Phone rejected accessory identification");}
            else throw new IOException("Unexpected identification message");
        }
    }
    private void authenticate() throws IOException,InterruptedException {
        state(State.AUTHENTICATING);long deadline=millis()+STEP_TIMEOUT;
        byte[] certificate=auth.certificate();
        if(certificate==null || certificate.length<1 || certificate.length>65525)throw new IOException("OEM certificate unavailable");
        boolean responded=false;int requests=0;
        try {
            while(true) {
                Iap2Csm.Frame frame=next(deadline);if(++requests>16)throw new IOException("Authentication request limit");
                if(frame.message==0xaa00){frame.fields();send(new Iap2Csm.Frame(0xaa01,Iap2Csm.fields(new Iap2Csm.Field(0,certificate))));}
                else if(frame.message==0xaa02) {
                    byte[] challenge=required(frame.fields(),0);
                    if(challenge.length<1 || challenge.length>128)throw new IOException("Invalid authentication challenge");
                    byte[] signature=null;
                    try {
                        signature=auth.sign(challenge);
                        if(signature==null || signature.length<1 || signature.length>4096)throw new IOException("OEM signature unavailable");
                        send(new Iap2Csm.Frame(0xaa03,Iap2Csm.fields(new Iap2Csm.Field(0,signature))));responded=true;
                    }finally{Arrays.fill(challenge,(byte)0);if(signature!=null)Arrays.fill(signature,(byte)0);}
                } else if(frame.message==0xaa05) {
                    frame.fields();if(!responded)throw new IOException("Authentication accepted before response sent");return;
                } else if(frame.message==0xaa04)throw new IOException("Phone rejected accessory authentication");
                else throw new IOException("Unexpected authentication message");
            }
        }finally{Arrays.fill(certificate,(byte)0);}
    }
    private ApSnapshot ap() throws IOException {
        ApSnapshot value=environment.accessPoint();
        if(!token.equals(value.session) || !ssid.equals(value.ssid) || channel!=value.channel) {
            value.close();throw new IOException("AP credentials changed during bootstrap");
        }
        return value;
    }
    private void sendWifi() throws IOException,InterruptedException {
        if(wifiSent>=7)throw new IOException("Wi-Fi configuration request limit");
        ApSnapshot value=ap();try{send(wifiConfiguration(value));}finally{value.close();}
        wifiSent++;pendingWifi=false;state(State.WIFI_SENT);
    }
    private void sendStartIfKnown() throws IOException,InterruptedException {
        ReceiverIdentity receiver=environment.receiver(token);
        // Older engines may use Wi-Fi credentials plus Bonjour/control and have no
        // verified 0x4301 public key metadata. Do not fabricate fields or block 0x5703.
        if(receiver==null){state(State.WAITING_RECEIVER);return;}
        if(!token.equals(receiver.apSession))throw new IOException("Receiver belongs to another AP session");
        if(startSent>=3)throw new IOException("CarPlay start request limit");
        ApSnapshot value=ap();try{send(startSession(value,receiver));}finally{value.close();}
        startSent++;pendingStart=false;state(State.WAITING_RECEIVER);
    }
    private void transport(Iap2Csm.Frame frame) throws IOException {
        List<Iap2Csm.Field> fields=frame.fields();byte[] bt=optional(fields,0),usb=optional(fields,1);
        String observed=bt==null?null:RfcommConnector.normalizeAddress(protocolString(bt,17));
        if(observed!=null && !peer.equals(observed))
            throw new IOException("Phone transport identity differs from selected bond");
        String usbIdentifier=usb==null?null:protocolString(usb,256);
        if(environment instanceof TransportObservationEnvironment)
            ((TransportObservationEnvironment)environment).receivedTransportIdentifiers(token,peer,observed,usbIdentifier);
        else environment.transportIdentifiers(token,peer,usbIdentifier); // Legacy selectedAddress remains context, not wire evidence.
    }
    private boolean completedHandoff() throws IOException,InterruptedException {
        HandoffObservation value=environment.handoff(token,peer);
        if(value==null)return false;
        if(!token.equals(value.apSession) || !peer.equals(value.selectedAddress))throw new IOException("Handoff observation belongs to another session");
        if(!value.wifiAssociated || !value.wifiIap2Established || !value.nativeSessionStarted)return false;
        boolean allProfiles=channel<=14 || value.disableBluetoothRequested;
        // Receiver policy for 2.4GHz coexistence: once this exact receiver has
        // verified all three Wi-Fi/runtime gates, release only the selected
        // device. This is not an inferred phone disable request (0x4e0d is
        // availability). Let the adapter verify actual asynchronous ACL loss.
        if(allProfiles && !value.bluetoothProfilesDisconnected && !environment.disconnectBluetoothProfiles(token,peer))
            throw new IOException("Bluetooth profile handoff not confirmed");
        // Avoid pre-closing the bootstrap transport before an unsupported or
        // refused teardown. run() still owns cleanup on every failure path.
        check();session.close();
        return true;
    }
    public static Iap2Csm.Frame identification(Identity identity,String ssid) {
        return identification(identity,ssid,StartMode.CSM_START_SESSION);
    }
    public static Iap2Csm.Frame identification(Identity identity,String ssid,StartMode mode) {
        if(mode==null)throw new NullPointerException();
        int[] sent=mode==StartMode.CSM_START_SESSION?new int[]{0xaa01,0xaa03,0x5703,0x4301}:new int[]{0xaa01,0xaa03,0x5703};
        int[] received=mode==StartMode.CSM_START_SESSION?new int[]{0xaa00,0xaa02,0xaa04,0xaa05,0x5702,0x4300,0x4e0d,0x4e0e}:new int[]{0xaa00,0xaa02,0xaa04,0xaa05,0x5702,0x4e0d,0x4e0e};
        byte[] mac=new byte[6];String[] parts=identity.bluetoothAddress.split(":");for(int i=0;i<6;i++)mac[i]=(byte)Integer.parseInt(parts[i],16);
        byte[] bt=Iap2Csm.fields(Iap2Csm.u16(0,0),Iap2Csm.string(1,"Bluetooth"),new Iap2Csm.Field(2,new byte[0]),new Iap2Csm.Field(3,mac),Iap2Csm.string(4,"Bluetooth"),new Iap2Csm.Field(5,new byte[0]));
        byte[] wireless=Iap2Csm.fields(Iap2Csm.u16(0,1),Iap2Csm.string(1,text(ssid,32)),new Iap2Csm.Field(2,new byte[0]),Iap2Csm.u16(3,1),new Iap2Csm.Field(4,new byte[0]),new Iap2Csm.Field(5,new byte[0]));
        return new Iap2Csm.Frame(0x1d01,Iap2Csm.fields(Iap2Csm.string(0,identity.name),Iap2Csm.string(1,identity.model),Iap2Csm.string(2,identity.manufacturer),Iap2Csm.string(3,identity.serial),Iap2Csm.string(4,identity.firmware),Iap2Csm.string(5,identity.hardware),new Iap2Csm.Field(6,u16s(sent)),new Iap2Csm.Field(7,u16s(received)),Iap2Csm.u8(8,0),Iap2Csm.u16(9,20),Iap2Csm.string(12,identity.language),Iap2Csm.string(13,identity.language),new Iap2Csm.Field(17,bt),new Iap2Csm.Field(24,wireless)));
    }
    public static Iap2Csm.Frame wifiConfiguration(ApSnapshot ap) {
        byte[] secret=ap.secret();
        try {
            Iap2Csm.Field[] fields=new Iap2Csm.Field[ap.bssid==null?4:5];int i=0;
            if(ap.bssid!=null)fields[i++]=new Iap2Csm.Field(0,ap.bssid);
            fields[i++]=Iap2Csm.string(1,ap.ssid);fields[i++]=new Iap2Csm.Field(2,secret);
            fields[i++]=Iap2Csm.u8(3,2);fields[i]=Iap2Csm.u8(4,ap.channel);
            return new Iap2Csm.Frame(0x5703,Iap2Csm.fields(fields));
        }finally{Arrays.fill(secret,(byte)0);}
    }
    public static Iap2Csm.Frame startSession(ApSnapshot ap,ReceiverIdentity receiver) {
        if(!ap.session.equals(receiver.apSession))throw new IllegalArgumentException("Receiver/AP mismatch");
        byte[] secret=ap.secret();
        try {
            byte[] network=Iap2Csm.fields(Iap2Csm.string(0,ap.ssid),new Iap2Csm.Field(1,secret),Iap2Csm.u8(2,ap.channel),Iap2Csm.string(3,ap.ipv4),Iap2Csm.u8(4,2));
            int p=receiver.port;byte[] port={(byte)(p>>>24),(byte)(p>>>16),(byte)(p>>>8),(byte)p};
            return new Iap2Csm.Frame(0x4301,Iap2Csm.fields(new Iap2Csm.Field(1,network),new Iap2Csm.Field(2,port),Iap2Csm.string(3,receiver.deviceIdentifier),Iap2Csm.string(4,receiver.publicKey),Iap2Csm.string(5,receiver.sourceVersion)));
        }finally{Arrays.fill(secret,(byte)0);}
    }
    private static byte[] u16s(int[] values) {byte[] out=new byte[values.length*2];for(int i=0;i<values.length;i++){out[i*2]=(byte)(values[i]>>>8);out[i*2+1]=(byte)values[i];}return out;}
    private static byte[] optional(List<Iap2Csm.Field> fields,int id) throws IOException {
        byte[] value=null;for(Iap2Csm.Field field:fields)if(field.id==id){if(value!=null)throw new IOException("Duplicate control parameter");value=field.bytes();}return value;
    }
    private static byte[] required(List<Iap2Csm.Field> fields,int id) throws IOException {byte[] value=optional(fields,id);if(value==null)throw new IOException("Missing control parameter");return value;}
    private static boolean booleanField(List<Iap2Csm.Field> fields,int id) throws IOException {
        byte[] value=required(fields,id);if(value.length!=1 || (value[0]!=0 && value[0]!=1))throw new IOException("Invalid boolean control parameter");return value[0]==1;
    }
    private static Boolean wirelessAvailability(Iap2Csm.Frame frame) throws IOException {
        byte[] group=optional(frame.fields(),1);if(group==null)return null;
        return Boolean.valueOf(booleanField(Iap2Csm.parseFields(group),0));
    }
    private static String protocolString(byte[] bytes,int maximum) throws IOException {
        if(bytes.length<2 || bytes.length>maximum+1 || bytes[bytes.length-1]!=0)throw new IOException("Invalid protocol string");
        for(int i=0;i<bytes.length-1;i++)if(bytes[i]==0)throw new IOException("Embedded protocol terminator");
        try {
            CharBuffer value=UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,bytes.length-1));
            return value.toString();
        }catch(CharacterCodingException e){throw new IOException("Invalid protocol string encoding");}
    }
    private static String text(String value,int maximum) {
        if(value==null || value.length()==0 || value.getBytes(UTF8).length>maximum)throw new IllegalArgumentException("Invalid identity field");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32 || value.charAt(i)==127)throw new IllegalArgumentException("Invalid identity field");return value;
    }
    private static String ipv4(String value) {
        if(value==null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw new IllegalArgumentException("AP address must be numeric IPv4");
        String[] parts=value.split("\\.");for(String part:parts)if(Integer.parseInt(part)>255)throw new IllegalArgumentException("Invalid AP address");
        int first=Integer.parseInt(parts[0]);if(first==0 || first==127 || first>=224)throw new IllegalArgumentException("Invalid AP address");return value;
    }
}
