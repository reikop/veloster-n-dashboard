/* SPDX-License-Identifier: GPL-3.0-only. DiPlay 5776c38 runtime identification/auth/subscriptions.
 * See TRANSPORT-NOTICE.md. No OEM authentication or receiver metadata fallback. */
package com.veloster.carplay;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.veloster.carplay.location.LocationSource;
import com.veloster.carplay.location.NmeaLocationSource;
import com.veloster.carplay.location.VehicleSpeedNmea;

/** Long-lived Wi-Fi tunnel CSM owner, independent of Bluetooth bootstrap/profile teardown.
 * run owns session; READY means authentication/subscription requests completed, not media readiness.
 * Authentication may block in OEM Binder: cancellation prevents later traffic but cannot abort that call. */
public final class Iap2RuntimeController {
    public enum State { NEW, LINK, IDENTIFYING, AUTHENTICATING, SUBSCRIBING, READY, DISCONNECTED, CANCELLED, FAILED }
    public interface Environment {
        /** Backed by the exact verified session, AP and selected phone; nonblocking. */
        void checkCurrent()throws IOException;
        Iap2WirelessController.ApSnapshot accessPoint()throws IOException;
        Iap2WirelessController.ReceiverIdentity receiver()throws IOException;
        /** Actual wire fields; null Bluetooth means omitted, never the configured selection. */
        void transportIdentifiers(String observedBluetooth,String usbIdentifier)throws IOException;
    }
    public interface Listener {
        /** Nonblocking application observation, not an instruction to mark CarPlay media active. */
        void state(State state)throws IOException;
        /** Only the six advertised update message IDs, after authentication. Frame is immutable. */
        void update(Iap2Csm.Frame frame)throws IOException;
        void availability(boolean available)throws IOException;
    }
    /** Actual same-session phone/media grant, never the configured selection. */
    public interface LocationEnvironment extends Environment {boolean locationAllowed()throws IOException;}
    private static final long STEP=30000,SEND=5000;
    private static final int[] START={0x5000,0x5200,0xae00,0x4157,0x4154};
    private static final int[] UPDATES={0x5001,0x5201,0x5202,0xae01,0x4158,0x4155};
    private final Iap2MessageSession session;
    private final Iap2WirelessController.Authentication authentication;
    private final Iap2WirelessController.Identity identity;
    private final Environment environment;private final Listener listener;
    private final String selectedBluetooth;
    private final LocationSource location;private boolean locationAdvertised,locationOwnerSeen;
    private int locationRequested,locationRunning,locationAdvertisedMask;private long locationPolled;
    private final AtomicBoolean started=new AtomicBoolean(),stop=new AtomicBoolean();
    private volatile State state=State.NEW;
    private String apToken,ssid;private int channel,wifiSent,startSent;private boolean transportSeen;
    public Iap2RuntimeController(Iap2MessageSession session,Iap2WirelessController.Authentication authentication,
            Iap2WirelessController.Identity identity,String selectedBluetooth,Environment environment,Listener listener){
        this(session,authentication,identity,selectedBluetooth,environment,listener,null);
    }
    public Iap2RuntimeController(Iap2MessageSession session,Iap2WirelessController.Authentication authentication,
            Iap2WirelessController.Identity identity,String selectedBluetooth,Environment environment,Listener listener,LocationSource location){
        if(session==null||authentication==null||identity==null||environment==null||listener==null)throw new NullPointerException();
        this.session=session;this.authentication=authentication;this.identity=identity;
        this.selectedBluetooth=RfcommConnector.normalizeAddress(selectedBluetooth);this.environment=environment;this.listener=listener;
        this.location=location;
    }
    public State state(){return state;}
    /** Nonblocking for control lifecycle callbacks; parent owns socket cancellation. */
    public void requestStop(){stop.set(true);}
    public void cancel()throws IOException{requestStop();session.close();}
    private static long millis(){return System.nanoTime()/1000000L;}
    private static final class Cancelled extends IOException{private static final long serialVersionUID=1;}
    private void check()throws IOException,InterruptedException{
        if(stop.get())throw new Cancelled();if(Thread.currentThread().isInterrupted())throw new InterruptedException();environment.checkCurrent();
    }
    private void state(State next)throws IOException{state=next;listener.state(next);}
    private void send(Iap2Csm.Frame frame)throws IOException,InterruptedException{check();session.send(frame,SEND);check();}
    private Iap2Csm.Frame next(long deadline)throws IOException,InterruptedException{
        for(;;){check();long left=deadline-millis();if(left<=0)throw new IOException("Runtime negotiation timed out");
            Iap2Csm.Frame frame=session.receive(Math.min(left,250));check();if(frame!=null)return frame;
            if(session.isClosed())throw new IOException("Runtime negotiation disconnected");}
    }
    public State run()throws IOException,InterruptedException{
        if(!started.compareAndSet(false,true))throw new IllegalStateException("Runtime controller already used");
        try{
            check();Iap2WirelessController.ApSnapshot ap=environment.accessPoint();
            if(ap==null)throw new IOException("Runtime AP unavailable");
            try{apToken=ap.session;ssid=ap.ssid;channel=ap.channel;}finally{ap.close();}check();
            state(State.LINK);if(!session.awaitReady(STEP))throw new IOException("Runtime link not ready");check();
            identify();authenticate();state(State.SUBSCRIBING);
            for(Iap2Csm.Frame subscription:subscriptions())send(subscription);check();state(State.READY);
            for(;;){
                check();locationTick();Iap2Csm.Frame frame=session.receive(250);check();
                if(frame==null){if(session.isClosed()){state(State.DISCONNECTED);return state;}continue;}
                List<Iap2Csm.Field> fields=frame.fields();
                if(contains(UPDATES,frame.message)){listener.update(frame);check();continue;}
                switch(frame.message){
                    case 0x5702:sendWifi();break;
                    case 0x4300:
                        // Availability details are not proof that this receiver is ready. Use the actual endpoint.
                        byte[] wireless=optional(fields,1);
                        if(wireless!=null&&!bool(Iap2Csm.parseFields(wireless),0)){listener.availability(false);break;}
                        sendStart();break;
                    case 0x4e0d:listener.availability(bool(fields,0));break;
                    case 0x4e0e:
                        byte[] bt=optional(fields,0),usb=optional(fields,1);
                        String observed=bt==null?null:RfcommConnector.normalizeAddress(protocolString(bt,17));
                        if(observed!=null&&!selectedBluetooth.equals(observed))
                            throw new IOException("Runtime phone differs from selected bond");
                        String receivedUsb=usb==null?null:protocolString(usb,256);
                        environment.transportIdentifiers(observed,receivedUsb);check();
                        if(observed!=null||receivedUsb!=null)locationOwnerSeen=true;
                        // Refreshed credentials are only sent in response to a validated transport notification.
                        if(!transportSeen){transportSeen=true;sendWifi();}break;
                    case 0xfffa:
                        if(!locationAdvertised)throw new IOException("Unadvertised location request");
                        locationRequested=locationMask(fields)&locationAdvertisedMask;break;
                    case 0xfffc:
                        if(!locationAdvertised||!fields.isEmpty())throw new IOException("Invalid location stop");
                        locationRequested=0;location.stop();locationRunning=0;break;
                    case 0xaa04:throw new IOException("Phone revoked runtime authentication");
                    case 0xaa00:case 0xaa02:case 0xaa05:case 0x1d00:case 0x1d02:case 0x1d03:
                        throw new IOException("Runtime negotiation restarted unexpectedly");
                    default:throw new IOException("Unadvertised runtime message");
                }
            }
        }catch(Cancelled e){state(State.CANCELLED);return state;}
         catch(InterruptedException e){state(State.CANCELLED);Thread.currentThread().interrupt();throw e;}
         catch(IOException e){state=stop.get()?State.CANCELLED:State.FAILED;throw e;}
         catch(RuntimeException e){state=State.FAILED;throw new IOException("Runtime controller failed",e);}
         catch(LinkageError e){state=State.FAILED;throw new IOException("Runtime API unavailable",e);}
         finally{try{if(location!=null)location.stop();}catch(IOException failure){state=State.FAILED;throw failure;}finally{session.close();}}
    }
    private void identify()throws IOException,InterruptedException{
        state(State.IDENTIFYING);long deadline=millis()+STEP;int sent=0;
        for(;;){Iap2Csm.Frame frame=next(deadline);frame.fields();
            if(frame.message==0x1d00){if(++sent>4)throw new IOException("Runtime identification request limit");
                if(sent==1){locationAdvertisedMask=location!=null&&location.available()?(location instanceof LocationSource.Typed?((LocationSource.Typed)location).supportedMask():3):0;
                    if(locationAdvertisedMask<0||(locationAdvertisedMask&~7)!=0)throw new IOException("Unsupported location capability");locationAdvertised=locationAdvertisedMask!=0;}
                send(identification(identity,ssid,locationAdvertisedMask));}
            else if(frame.message==0x1d02){if(sent==0)throw new IOException("Identification accepted before response");return;}
            else if(frame.message==0x1d03)throw new IOException("Phone rejected runtime identification");
            else throw new IOException("Unexpected runtime identification message");}
    }
    private void authenticate()throws IOException,InterruptedException{
        state(State.AUTHENTICATING);long deadline=millis()+STEP;byte[] certificate=null;boolean responded=false;
        try{
            check();certificate=authentication.certificate();check();
            if(certificate==null||certificate.length<1||certificate.length>65525)throw new IOException("Runtime OEM certificate unavailable");
            for(int requests=0;requests<16;requests++){
                Iap2Csm.Frame frame=next(deadline);List<Iap2Csm.Field> fields=frame.fields();
                if(frame.message==0xaa00)send(new Iap2Csm.Frame(0xaa01,Iap2Csm.fields(new Iap2Csm.Field(0,certificate))));
                else if(frame.message==0xaa02){
                    byte[] challenge=required(fields,0),signature=null;
                    try{
                        if(challenge.length<1||challenge.length>128)throw new IOException("Invalid runtime challenge");
                        check();signature=authentication.sign(challenge);check();
                        if(signature==null||signature.length<1||signature.length>4096)throw new IOException("Runtime OEM signature unavailable");
                        if(millis()>=deadline)throw new IOException("Runtime authentication timed out");
                        send(new Iap2Csm.Frame(0xaa03,Iap2Csm.fields(new Iap2Csm.Field(0,signature))));responded=true;
                    }finally{Arrays.fill(challenge,(byte)0);if(signature!=null)Arrays.fill(signature,(byte)0);}
                }else if(frame.message==0xaa05){if(!responded)throw new IOException("Runtime authentication accepted before response");return;}
                else if(frame.message==0xaa04)throw new IOException("Phone rejected runtime authentication");
                else throw new IOException("Unexpected runtime authentication message");
            }throw new IOException("Runtime authentication request limit");
        }finally{if(certificate!=null)Arrays.fill(certificate,(byte)0);}
    }
    private Iap2WirelessController.ApSnapshot ap()throws IOException{
        Iap2WirelessController.ApSnapshot value=environment.accessPoint();if(value==null)throw new IOException("Runtime AP unavailable");
        if(!apToken.equals(value.session)||!ssid.equals(value.ssid)||channel!=value.channel){value.close();throw new IOException("Runtime AP changed");}return value;
    }
    private void sendWifi()throws IOException,InterruptedException{
        if(wifiSent>=7)throw new IOException("Runtime Wi-Fi request limit");Iap2WirelessController.ApSnapshot value=ap();
        try{send(Iap2WirelessController.wifiConfiguration(value));wifiSent++;}finally{value.close();}
    }
    private void sendStart()throws IOException,InterruptedException{
        if(startSent>=3)throw new IOException("Runtime start request limit");
        Iap2WirelessController.ReceiverIdentity receiver=environment.receiver();
        if(receiver==null||!apToken.equals(receiver.apSession))throw new IOException("Current runtime receiver metadata unavailable");
        Iap2WirelessController.ApSnapshot value=ap();try{send(Iap2WirelessController.startSession(value,receiver));startSent++;}finally{value.close();}
    }
    public static Iap2Csm.Frame identification(Iap2WirelessController.Identity identity,String ssid)throws IOException{
        return identification(identity,ssid,false);
    }
    public static Iap2Csm.Frame identification(Iap2WirelessController.Identity identity,String ssid,boolean location)throws IOException{
        return identification(identity,ssid,location?3:0);
    }
    public static Iap2Csm.Frame identification(Iap2WirelessController.Identity identity,String ssid,int locationMask)throws IOException{
        if(locationMask<0||(locationMask&~7)!=0)throw new IOException("Unsupported location capability");boolean location=locationMask!=0;
        List<Iap2Csm.Field> fields=Iap2WirelessController.identification(identity,ssid).fields();List<Iap2Csm.Field> result=new ArrayList<Iap2Csm.Field>();
        for(Iap2Csm.Field field:fields)if(field.id!=6&&field.id!=7)result.add(field);
        result.add(new Iap2Csm.Field(6,u16s(location?new int[]{0xaa01,0xaa03,0x5000,0x5200,0xae00,0x4157,0x4154,0x5703,0x4301,0xfffb}:new int[]{0xaa01,0xaa03,0x5000,0x5200,0xae00,0x4157,0x4154,0x5703,0x4301})));
        result.add(new Iap2Csm.Field(7,u16s(location?new int[]{0xaa00,0xaa02,0xaa04,0xaa05,0x5001,0x5201,0x5202,0xae01,0x4158,0x4155,0x5702,0x4300,0x4e0d,0x4e0e,0xfffa,0xfffc}:new int[]{0xaa00,0xaa02,0xaa04,0xaa05,0x5001,0x5201,0x5202,0xae01,0x4158,0x4155,0x5702,0x4300,0x4e0d,0x4e0e})));
        if(location){List<Iap2Csm.Field> component=new ArrayList<Iap2Csm.Field>();component.add(Iap2Csm.u16(0,0));component.add(Iap2Csm.string(1,identity.name));
            if((locationMask&LocationSource.GGA)!=0)component.add(new Iap2Csm.Field(17,new byte[0]));
            if((locationMask&LocationSource.RMC)!=0)component.add(new Iap2Csm.Field(18,new byte[0]));
            if((locationMask&LocationSource.PASCD)!=0)component.add(new Iap2Csm.Field(20,new byte[0]));
            result.add(new Iap2Csm.Field(22,Iap2Csm.fields(component.toArray(new Iap2Csm.Field[component.size()]))));}
        result.add(new Iap2Csm.Field(30,Iap2Csm.fields(Iap2Csm.u16(0,42),Iap2Csm.string(1,"RouteGuidance"),Iap2Csm.u16(2,64),Iap2Csm.u16(4,64),Iap2Csm.u16(6,8))));
        return new Iap2Csm.Frame(0x1d01,Iap2Csm.fields(result.toArray(new Iap2Csm.Field[result.size()])));
    }
    private static int locationMask(List<Iap2Csm.Field> fields)throws IOException{
        int mask=0;Set<Integer> seen=new HashSet<Integer>();
        for(Iap2Csm.Field field:fields){if(!seen.add(field.id))throw new IOException("Duplicate location selector");
            if(field.id==1||field.id==2||field.id==4){if(field.bytes().length!=0)throw new IOException("Location selector must be empty");mask|=field.id;}}
        return mask;
    }
    private boolean locationAllowed()throws IOException{
        return locationOwnerSeen&&environment instanceof LocationEnvironment&&((LocationEnvironment)environment).locationAllowed();
    }
    private void locationTick()throws IOException,InterruptedException{
        if(!locationAdvertised)return;check();
        if(locationRequested==0||!location.available()||!locationAllowed()){
            if(locationRunning!=0){location.stop();locationRunning=0;}return;
        }
        if(locationRunning!=locationRequested){locationRunning=0;location.start(locationRequested);check();locationRunning=locationRequested;locationPolled=0;}
        long now=millis();if(locationPolled!=0&&now-locationPolled<1000)return;locationPolled=now;
        List<String> sentences=location.poll();check();if(!locationAllowed()){location.stop();locationRunning=0;return;}
        if(sentences==null||sentences.size()>3)throw new IOException("Location source bounds");int seen=0;
        for(String sentence:sentences){int type=NmeaLocationSource.type(sentence);if(type==0)type=VehicleSpeedNmea.type(sentence);
            if(type==0||!sentence.endsWith("\r\n")||(type&locationRunning)==0||(seen&type)!=0)throw new IOException("Undeclared location sentence");seen|=type;
            check();if(!locationAllowed()){location.stop();locationRunning=0;return;}send(new Iap2Csm.Frame(0xfffb,Iap2Csm.fields(Iap2Csm.string(0,sentence))));
        }
    }
    private static byte[] voids(int...ids){Iap2Csm.Field[] fields=new Iap2Csm.Field[ids.length];for(int i=0;i<ids.length;i++)fields[i]=new Iap2Csm.Field(ids[i],new byte[0]);return Iap2Csm.fields(fields);}
    public static List<Iap2Csm.Frame> subscriptions(){
        return Collections.unmodifiableList(Arrays.asList(
            new Iap2Csm.Frame(START[0],Iap2Csm.fields(new Iap2Csm.Field(0,voids(1,4,6,12,26)),new Iap2Csm.Field(1,voids(0,1,7)))),
            new Iap2Csm.Frame(START[1],Iap2Csm.fields(Iap2Csm.u16(0,42),new Iap2Csm.Field(1,new byte[0]),new Iap2Csm.Field(2,new byte[0]))),
            new Iap2Csm.Frame(START[2],voids(4,5,6)),new Iap2Csm.Frame(START[3],voids(0,4,5)),new Iap2Csm.Frame(START[4],voids(0,1,2,3,4,11))));
    }
    private static byte[] u16s(int[] ids){byte[] b=new byte[ids.length*2];for(int i=0;i<ids.length;i++){b[i*2]=(byte)(ids[i]>>>8);b[i*2+1]=(byte)ids[i];}return b;}
    private static boolean contains(int[] values,int n){for(int value:values)if(value==n)return true;return false;}
    private static byte[] optional(List<Iap2Csm.Field> fields,int id)throws IOException{
        byte[] found=null;for(Iap2Csm.Field field:fields)if(field.id==id){if(found!=null)throw new IOException("Duplicate runtime field");found=field.bytes();}return found;
    }
    private static byte[] required(List<Iap2Csm.Field> fields,int id)throws IOException{byte[] b=optional(fields,id);if(b==null)throw new IOException("Missing runtime field");return b;}
    private static boolean bool(List<Iap2Csm.Field> fields,int id)throws IOException{byte[] b=required(fields,id);if(b.length!=1||(b[0]!=0&&b[0]!=1))throw new IOException("Invalid runtime boolean");return b[0]==1;}
    private static String protocolString(byte[] b,int max)throws IOException{
        if(b.length<2||b.length>max+1||b[b.length-1]!=0)throw new IOException("Invalid runtime transport string");
        // Transport identifiers are ASCII protocol identifiers, never a display name.
        for(int i=0;i<b.length-1;i++)if((b[i]&255)<32||(b[i]&255)>126)throw new IOException("Invalid runtime transport character");return new String(b,0,b.length-1,"US-ASCII");
    }
}
