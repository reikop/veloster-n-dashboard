/* SPDX-License-Identifier: GPL-3.0-only. DiPlay /info and Bonjour wire profile; see NOTICE.md. */
package com.veloster.carplay.control;

import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.RfcommConnector;
import com.veloster.carplay.pairing.PairingStore;
import com.veloster.carplay.pairing.PairVerify;
import com.veloster.carplay.input.TouchHid;
import java.io.*;
import java.net.InetAddress;
import java.util.*;

/** One persistent modern receiver identity and one live listener, shared by info/TXT/iAP.
 * This component does not publish mDNS. Platform MACs, display measurements and protocol version
 * are explicit inputs. Optional output formats must share the SETUP factory's declaration.
 * HID is opt-in; HEVC and low-power features are not enabled. */
public final class ReceiverMetadata {
    // Screen, Car, CarPlayControl and encrypted pairing. No borrowed audio/photo/DRM bit mask.
    public static final long FEATURES=(1L<<7)|(1L<<32)|(1L<<37)|(1L<<38);
    public static final String SERVICE_TYPE="_airplay._tcp.local.";
    public static final class Display {
        public final int width,height,widthMm,heightMm,fps;
        public Display(int width,int height,int widthMm,int heightMm,int fps)throws IOException{
            if(width<1||width>1920||height<1||height>1088||widthMm<1||widthMm>2000||heightMm<1||heightMm>2000||fps<1||fps>60)
                throw new IOException("Invalid receiver display geometry");
            this.width=width;this.height=height;this.widthMm=widthMm;this.heightMm=heightMm;this.fps=fps;
        }
    }
    public static final class Configuration {
        public final String name,deviceId,bluetoothId,sourceVersion,manufacturer,model;
        public final boolean rightHandDrive;public final Display main,alternate;
        public Configuration(String name,String deviceId,String bluetoothId,String sourceVersion,String manufacturer,String model,
                             boolean rightHandDrive,Display main,Display alternate)throws IOException{
            this.name=text(name,63);this.deviceId=mac(deviceId);
            try{this.bluetoothId=RfcommConnector.normalizeAddress(bluetoothId);}
            catch(IllegalArgumentException e){throw new IOException("Invalid receiver Bluetooth address",e);}
            this.sourceVersion=text(sourceVersion,32);if(!sourceVersion.matches("[0-9]+(?:\\.[0-9]+){1,3}"))throw new IOException("Invalid receiver protocol version");
            this.manufacturer=text(manufacturer,128);this.model=text(model,128);
            if(main==null)throw new IOException("Main display required");
            this.rightHandDrive=rightHandDrive;this.main=main;this.alternate=alternate;
        }
    }
    /** A snapshot for a publisher; re-check the owner via advertisement() before publishing/replacing it. */
    public static final class Advertisement {
        public final String serviceName,serviceType;public final InetAddress address;public final int port;
        public final Map<String,String> txt;private final byte[] wire;
        private Advertisement(Configuration config,InetAddress address,int port,Map<String,String> txt)throws IOException{
            if(port<1||port>65535)throw new IOException("Receiver endpoint closed");
            serviceName=config.name;serviceType=SERVICE_TYPE;this.address=address;this.port=port;
            this.txt=Collections.unmodifiableMap(new LinkedHashMap<String,String>(txt));wire=txtWire(txt);
        }
        public byte[] txtBytes(){return wire.clone();}
    }
    private final PairingStore store;private final Configuration config;
    private final ModeChanges initialModes;
    private final AudioCapabilities audio;private final long features;
    private final TouchHid touch;
    private final String pairingId,publicKeyHex,mainUuid,alternateUuid;
    private final byte[] publicKey;private final Map<String,String> txt;
    private volatile ControlListener listener;
    public ReceiverMetadata(PairingStore store,Configuration config)throws IOException{
        this(store,config,null);
    }
    public ReceiverMetadata(PairingStore store,Configuration config,ModeChanges initialModes)throws IOException{
        this(store,config,initialModes,null);
    }
    public ReceiverMetadata(PairingStore store,Configuration config,ModeChanges initialModes,AudioCapabilities audio)throws IOException{
        this(store,config,initialModes,audio,null);
    }
    public ReceiverMetadata(PairingStore store,Configuration config,ModeChanges initialModes,AudioCapabilities audio,TouchHid touch)throws IOException{
        if(store==null||config==null)throw new NullPointerException();this.store=store;this.config=config;this.initialModes=initialModes;
        if(touch!=null&&(touch.width!=config.main.width||touch.height!=config.main.height))throw new IOException("Touch/display geometry mismatch");
        this.touch=touch;
        this.audio=audio;features=FEATURES|(audio==null?0:audio.features());
        pairingId=store.identityIdentifier();publicKey=store.identityPublicKey();
        if(publicKey.length!=32)throw new IOException("Invalid receiver identity key");
        StringBuilder hex=new StringBuilder();for(byte b:publicKey)hex.append(String.format(Locale.US,"%02x",b&255));publicKeyHex=hex.toString();
        mainUuid=displayId(110);alternateUuid=config.alternate==null?null:displayId(111);
        txt=new LinkedHashMap<String,String>();txt.put("deviceid",config.deviceId);
        txt.put("features","0x"+Long.toHexString(features&0xffffffffL)+",0x"+Long.toHexString(features>>>32));
        txt.put("flags","0x4");txt.put("model",config.model);txt.put("srcvers",config.sourceVersion);
        txt.put("protovers","1.1");txt.put("pi",pairingId);txt.put("pk",publicKeyHex);txtWire(txt);
    }
    private String displayId(int type)throws IOException{
        return UUID.nameUUIDFromBytes((pairingId+":screen:"+type).getBytes("UTF-8")).toString();
    }
    /** Bind exactly once, after the listener has successfully opened its actual socket. */
    public synchronized void bind(ControlListener value)throws IOException{
        if(value==null||listener!=null)throw new IOException("Receiver listener already bound or missing");
        value.metadataScope(store);listener=value;
    }
    private ReceiverScope check()throws IOException{
        ControlListener bound=listener;if(bound==null)throw new IOException("Receiver listener not bound");
        ReceiverScope context=bound.metadataScope(store);
        if(!pairingId.equals(store.identityIdentifier())||!Arrays.equals(publicKey,store.identityPublicKey()))throw new IOException("Receiver identity changed");
        return context;
    }
    public boolean supports(int type){return type==110||type==111&&config.alternate!=null;}
    public boolean hasInitialModes(){return initialModes!=null;}
    public AudioCapabilities audioCapabilities(){return audio;}
    public TouchHid touchInput(){return touch;}
    TouchHid inputFor(PairVerify.VerifiedSession session)throws IOException{
        if(touch==null||session==null||!check().contains(session.context()))throw new IOException("Undeclared/foreign touch input");
        session.assertCurrent();return touch;
    }
    public String displayUuid(int type)throws IOException{
        if(!supports(type))throw new IOException("Unconfigured receiver display");return type==110?mainUuid:alternateUuid;
    }
    public Advertisement advertisement()throws IOException{
        ReceiverScope context=check();return new Advertisement(config,context.localAddress,listener.port(),txt);
    }
    public Iap2WirelessController.ReceiverIdentity receiverIdentity()throws IOException{
        ReceiverScope context=check();int port=listener.port();if(port<1)throw new IOException("Receiver endpoint closed");
        return new Iap2WirelessController.ReceiverIdentity(context.apSession,port,config.deviceId,publicKeyHex,config.sourceVersion);
    }
    public List<String> setupFeatures(Object proposed)throws IOException{
        if(proposed!=null){
            if(!(proposed instanceof List)||((List<?>)proposed).size()>64)throw new IOException("Invalid SETUP feature list");
            for(Object item:(List<?>)proposed)if(!(item instanceof String)||text((String)item,64).length()==0)throw new IOException("Invalid SETUP feature token");
        }
        // DiPlay enables these structural features even when an older sender omits its proposals.
        List<String> result=new ArrayList<String>();result.add("iAPChannel");result.add("viewAreas");
        if(config.alternate!=null)result.add("altScreen");return Collections.unmodifiableList(result);
    }
    public ControlMessage.Response info()throws IOException{
        Advertisement publication=advertisement();Map<String,Object> info=new LinkedHashMap<String,Object>();
        info.put("sourceVersion",config.sourceVersion);info.put("protocolVersion","1.1");info.put("features",features);info.put("statusFlags",4L);
        info.put("model",config.model);info.put("manufacturer",config.manufacturer);info.put("name",config.name);
        info.put("deviceID",config.deviceId);info.put("bluetoothIDs",Collections.singletonList(config.bluetoothId));
        info.put("pi",pairingId);info.put("pk",publicKey.clone());info.put("txtAirPlay",publication.txtBytes());
        info.put("rightHandDrive",config.rightHandDrive);info.put("keepAliveLowPower",false);info.put("keepAliveSendStatsAsBody",false);
        List<Object> displays=new ArrayList<Object>();displays.add(display(config.main,110,mainUuid));
        if(config.alternate!=null)displays.add(display(config.alternate,111,alternateUuid));info.put("displays",displays);
        info.put("hidDevices",touch==null?Collections.emptyList():Collections.singletonList(touch.declaration(mainUuid)));info.put("extendedFeatures",Collections.emptyList());
        if(audio!=null)info.put("audioFormats",audio.wire());
        if(initialModes!=null)info.put("modes",initialModes.wire());
        return ControlMessage.Response.body("application/x-apple-binary-plist",BinaryPlist.encode(info));
    }
    private Map<String,Object> display(Display d,int type,String uuid){
        Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("uuid",uuid);out.put("type",type);
        out.put("widthPixels",d.width);out.put("heightPixels",d.height);out.put("widthPhysical",d.widthMm);out.put("heightPhysical",d.heightMm);out.put("maxFPS",d.fps);
        out.put("features",touch!=null&&type==110?8:0);out.put("primaryInputDevice",touch!=null&&type==110?1:0);
        Map<String,Object> area=area(d),safe=area(d);safe.put("drawUIOutsideSafeArea",false);area.put("safeArea",safe);
        out.put("viewAreas",Collections.singletonList(area));out.put("initialViewArea",0);return out;
    }
    private static Map<String,Object> area(Display d){
        Map<String,Object> value=new LinkedHashMap<String,Object>();value.put("widthPixels",d.width);value.put("heightPixels",d.height);value.put("originXPixels",0);value.put("originYPixels",0);return value;
    }
    private static String mac(String value)throws IOException{
        if(value==null||!value.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")||value.equals("00:00:00:00:00:00")||(Integer.parseInt(value.substring(0,2),16)&1)!=0)
            throw new IOException("Invalid receiver hardware address");return value.toUpperCase(Locale.US);
    }
    private static String text(String value,int max)throws IOException{
        if(value==null||value.length()==0)throw new IOException("Missing receiver text");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(Character.isISOControl(c))throw new IOException("Control character in receiver text");
            if(Character.isHighSurrogate(c)){if(++i==value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new IOException("Invalid receiver Unicode");}
            else if(Character.isLowSurrogate(c))throw new IOException("Invalid receiver Unicode");
        }
        if(value.getBytes("UTF-8").length>max)throw new IOException("Receiver text too long");return value;
    }
    private static byte[] txtWire(Map<String,String> entries)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(Map.Entry<String,String> entry:entries.entrySet()){
            byte[] bytes=(entry.getKey()+"="+entry.getValue()).getBytes("UTF-8");
            if(bytes.length>255)throw new IOException("DNS TXT entry too long");out.write(bytes.length);out.write(bytes);
        }
        if(out.size()>1300)throw new IOException("Receiver TXT record too large");return out.toByteArray();
    }
}
