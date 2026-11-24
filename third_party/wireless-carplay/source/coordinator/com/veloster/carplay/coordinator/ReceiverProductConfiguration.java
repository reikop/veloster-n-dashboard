/* SPDX-License-Identifier: GPL-3.0-only. Product inputs, never fixture identity defaults. */
package com.veloster.carplay.coordinator;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.advertisement.EirProtocol;
import java.net.InetAddress;
import com.veloster.carplay.control.ReceiverMetadata;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.pairing.PairingStore;
import java.io.IOException;
import java.util.Locale;

/** Worker-only configuration capture. Does not start Bluetooth/AP or generate OEM serials.
 * Returned objects share identity fields; admission still owns live generation checks. */
public final class ReceiverProductConfiguration {
    public interface Generation { void checkCurrent(ReceiverScope scope)throws IOException; }
    public interface Platform { Observation observe(ReceiverScope scope,int interfaceIndex)throws IOException; }
    public interface EnrollmentPlatform {EnrollmentObservation observeEnrollment(EirProtocol.EnrollmentScope scope,String interfaceName,InetAddress address,int index)throws IOException;}
    public interface EnrollmentGeneration {void checkCurrent(EirProtocol.EnrollmentScope scope)throws IOException;}
    public static final class EnrollmentObservation {
        public final EirProtocol.EnrollmentScope scope;public final Observation product;
        public EnrollmentObservation(EirProtocol.EnrollmentScope scope,Observation product){this.scope=scope;this.product=product;}
    }
    /** Phone-independent validated product values; cannot be passed as a receiver grant. */
    public static final class EnrollmentProduct {
        public final EirProtocol.EnrollmentScope scope;public final ReceiverMetadata.Configuration metadata;
        public final Iap2WirelessController.Identity identity;public final int interfaceIndex;
        private EnrollmentProduct(EirProtocol.EnrollmentScope scope,ReceiverProductConfiguration product){this.scope=scope;metadata=product.metadata;identity=product.identity;interfaceIndex=product.interfaceIndex;}
    }
    /** Fresh AP availability may have no associated station; that is never selected-phone evidence. */
    public static void requireEnrollmentAp(EirProtocol.EnrollmentScope scope,ApStationSnapshot observed,int index,long now)throws IOException{
        if(scope==null||observed==null||index<1||!scope.ap.equals(observed.sessionToken)||index!=observed.interfaceIndex||now<observed.sampledBoottimeMillis||now-observed.sampledBoottimeMillis>2500)
            throw invalid("enrollmentApObservation");
    }
    public static EnrollmentProduct captureEnrollment(Spec spec,PairingStore store,EirProtocol.EnrollmentScope scope,String name,InetAddress address,int index,EnrollmentPlatform platform,EnrollmentGeneration generation)throws IOException{
        if(spec==null||store==null||scope==null||platform==null||generation==null)throw new NullPointerException();if(index<1)throw invalid("interfaceIndex");
        generation.checkCurrent(scope);String persisted=store.identityIdentifier();
        if(!persisted.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("persistedAccessoryIdentifier");
        EnrollmentObservation observed=platform.observeEnrollment(scope,name,address,index);
        if(observed==null||!scope.equals(observed.scope)||observed.product==null||observed.product.scope!=null)throw invalid("enrollmentObservationScope");
        ReceiverProductConfiguration product=new ReceiverProductConfiguration(spec,null,index,observed.product,"vn-"+persisted,true);
        generation.checkCurrent(scope);if(!persisted.equals(store.identityIdentifier()))throw invalid("persistedAccessoryIdentifierChanged");return new EnrollmentProduct(scope,product);
    }
    public static final class ProtocolProfile {
        public final String name,sourceVersion,reference;
        public ProtocolProfile(String name,String sourceVersion,String reference)throws IOException{
            this.name=text(name,"protocolProfile",64);this.sourceVersion=text(sourceVersion,"sourceVersion",32);
            if(!sourceVersion.matches("[0-9]+(?:\\.[0-9]+){1,3}"))throw invalid("sourceVersion");
            this.reference=text(reference,"protocolReference",512);
        }
        /** Metadata compatibility selection, NOT a claim to implement every DiPlay/Apple feature. */
        public static ProtocolProfile experimentalDiPlay()throws IOException{
            return new ProtocolProfile("vn-diplay-5776c38-experimental","950.7.1",
                "https://github.com/Jheenz/DiPlay-5.1/blob/5776c38910824267a561160457270748ac76f6db/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L3364");
        }
    }
    public static final class PhysicalDisplay {
        public final int widthMm,heightMm;public final String provenance;public final boolean approximate;
        public PhysicalDisplay(int widthMm,int heightMm,String provenance,boolean approximate)throws IOException{
            if(widthMm<1||heightMm<1||widthMm>2000||heightMm>2000)throw invalid("displayPhysicalSize");
            this.widthMm=widthMm;this.heightMm=heightMm;this.provenance=text(provenance,"displayPhysicalProvenance",256);this.approximate=approximate;
        }
        public static PhysicalDisplay platformReported(int width,int height,float xdpi,float ydpi)throws IOException{
            if(width<1||height<1||Float.isNaN(xdpi)||Float.isInfinite(xdpi)||xdpi<=0||Float.isNaN(ydpi)||Float.isInfinite(ydpi)||ydpi<=0)
                throw invalid("displayDpi");
            double w=width*25.4/xdpi,h=height*25.4/ydpi;
            if(w<1||h<1||w>2000||h>2000)throw invalid("displayPhysicalSize");
            return new PhysicalDisplay((int)Math.round(w),(int)Math.round(h),"Android Display.getRealMetrics xdpi/ydpi; platform-reported, uncalibrated",true);
        }
    }
    public static final class Spec {
        public final String name,model,manufacturer,hardwareRevision,language;
        public final ProtocolProfile protocol;public final PhysicalDisplay physicalOverride;
        public final int expectedWidth,expectedHeight,maxFps;public final boolean rightHandDrive;
        public Spec(String name,String model,String manufacturer,String hardwareRevision,String language,ProtocolProfile protocol,
                    int expectedWidth,int expectedHeight,int maxFps,boolean rightHandDrive,PhysicalDisplay physicalOverride)throws IOException{
            this.name=text(name,"name",63);this.model=text(model,"model",128);this.manufacturer=text(manufacturer,"manufacturer",128);
            this.hardwareRevision=text(hardwareRevision,"hardwareRevision",128);this.language=text(language,"language",16);
            if(protocol==null)throw invalid("protocolProfile");if(expectedWidth<1||expectedHeight<1||maxFps<1||maxFps>60)throw invalid("displayProfile");
            this.protocol=protocol;this.expectedWidth=expectedWidth;this.expectedHeight=expectedHeight;this.maxFps=maxFps;
            this.rightHandDrive=rightHandDrive;this.physicalOverride=physicalOverride;
        }
    }
    public static final class Observation {
        public final ReceiverScope scope;public final int interfaceIndex,width,height;
        public final String bluetoothAddress,apHardwareAddress,appVersion;public final float xdpi,ydpi;
        public Observation(ReceiverScope scope,int index,String bluetooth,String apMac,String appVersion,int width,int height,float xdpi,float ydpi){
            this.scope=scope;this.interfaceIndex=index;this.bluetoothAddress=bluetooth;this.apHardwareAddress=apMac;this.appVersion=appVersion;
            this.width=width;this.height=height;this.xdpi=xdpi;this.ydpi=ydpi;
        }
    }
    public final ReceiverMetadata.Configuration metadata;
    public final Iap2WirelessController.Identity identity;
    public final ReceiverScope scope;public final int interfaceIndex;
    public final ProtocolProfile protocol;public final PhysicalDisplay physicalDisplay;
    /** Actual AP interface hardware MAC; this alone is NOT hostapd BSSID evidence. */
    public final String apHardwareAddress;
    private ReceiverProductConfiguration(Spec spec,ReceiverScope scope,int index,Observation value,String serial)throws IOException{
        this(spec,scope,index,value,serial,false);
    }
    private ReceiverProductConfiguration(Spec spec,ReceiverScope scope,int index,Observation value,String serial,boolean enrollment)throws IOException{
        if(value==null||(enrollment?(scope!=null||value.scope!=null):!same(scope,value.scope))||index!=value.interfaceIndex)throw invalid("observationScope");
        if(value.width!=spec.expectedWidth||value.height!=spec.expectedHeight)throw invalid("displayPixels");
        String bt=mac(value.bluetoothAddress,"localBluetoothAddress");apHardwareAddress=mac(value.apHardwareAddress,"apHardwareAddress");
        physicalDisplay=spec.physicalOverride==null?PhysicalDisplay.platformReported(value.width,value.height,value.xdpi,value.ydpi):spec.physicalOverride;
        metadata=new ReceiverMetadata.Configuration(spec.name,apHardwareAddress,bt,spec.protocol.sourceVersion,spec.manufacturer,spec.model,
            spec.rightHandDrive,new ReceiverMetadata.Display(value.width,value.height,physicalDisplay.widthMm,physicalDisplay.heightMm,spec.maxFps),null);
        try{identity=new Iap2WirelessController.Identity(spec.name,spec.model,spec.manufacturer,serial,text(value.appVersion,"appVersion",128),spec.hardwareRevision,spec.language,bt);}
        catch(IllegalArgumentException e){throw new IOException("Invalid iAP product identity",e);}
        this.scope=scope;interfaceIndex=index;protocol=spec.protocol;
    }
    public static ReceiverProductConfiguration capture(Spec spec,PairingStore store,ReceiverScope scope,int index,Platform platform,Generation generation)throws IOException{
        if(spec==null||store==null||scope==null||platform==null||generation==null)throw new NullPointerException();if(index<1)throw invalid("interfaceIndex");
        generation.checkCurrent(scope);String persisted=store.identityIdentifier();
        if(!persisted.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("persistedAccessoryIdentifier");
        ReceiverProductConfiguration result=new ReceiverProductConfiguration(spec,scope,index,platform.observe(scope,index),"vn-"+persisted);
        generation.checkCurrent(scope);if(!persisted.equals(store.identityIdentifier()))throw invalid("persistedAccessoryIdentifierChanged");return result;
    }
    public void requireScope(ReceiverScope expected,int index)throws IOException{if(!same(scope,expected)||index!=interfaceIndex)throw invalid("configurationScope");}
    private static boolean same(ReceiverScope a,ReceiverScope b){return a!=null&&b!=null&&a.owner.equals(b.owner)&&a.apSession.equals(b.apSession)&&a.generation==b.generation
        &&a.selectedBluetooth.equals(b.selectedBluetooth)&&a.interfaceName.equals(b.interfaceName)&&a.localAddress.equals(b.localAddress);}
    static String mac(String value,String field)throws IOException{
        if(value==null||!value.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")||value.equals("00:00:00:00:00:00")||value.equals("02:00:00:00:00:00")
            ||(Integer.parseInt(value.substring(0,2),16)&1)!=0)throw invalid(field);return value.toUpperCase(Locale.US);
    }
    static String text(String value,String field,int max)throws IOException{
        if(value==null||value.length()==0||value.getBytes("UTF-8").length>max)throw invalid(field);
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isISOControl(c))throw invalid(field);
            if(Character.isHighSurrogate(c)){if(++i==value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid(field);}
            else if(Character.isLowSurrogate(c))throw invalid(field);}
        return value;
    }
    static IOException invalid(String field){return new IOException("Missing or invalid product configuration field: "+field);}
}
