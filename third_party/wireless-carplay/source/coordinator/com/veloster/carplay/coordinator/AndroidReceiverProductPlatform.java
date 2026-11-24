/* SPDX-License-Identifier: GPL-3.0-only. API17 actual package/adapter/interface/display observations. */
package com.veloster.carplay.coordinator;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import com.veloster.carplay.control.ReceiverScope;
import java.io.*;
import java.net.*;
import java.util.*;

/** Call on the service worker. BLUETOOTH permission is required. No guessed MAC,
 * Settings.Secure android_id, OEM serial, default density or app-version fallback. */
public final class AndroidReceiverProductPlatform implements ReceiverProductConfiguration.Platform,ReceiverProductConfiguration.EnrollmentPlatform {
    private final Context context;
    public AndroidReceiverProductPlatform(Context context){if(context==null)throw new NullPointerException();Context application=context.getApplicationContext();this.context=application==null?context:application;}
    public ReceiverProductConfiguration.Observation observe(ReceiverScope scope,int interfaceIndex)throws IOException{
        if(scope==null)throw ReceiverProductConfiguration.invalid("receiverScope");return observe(scope,scope.interfaceName,scope.localAddress,interfaceIndex);
    }
    public ReceiverProductConfiguration.EnrollmentObservation observeEnrollment(com.veloster.carplay.advertisement.EirProtocol.EnrollmentScope scope,String name,InetAddress address,int index)throws IOException{
        if(scope==null)throw ReceiverProductConfiguration.invalid("enrollmentScope");return new ReceiverProductConfiguration.EnrollmentObservation(scope,observe(null,name,address,index));
    }
    private ReceiverProductConfiguration.Observation observe(ReceiverScope scope,String name,InetAddress address,int interfaceIndex)throws IOException{
        try{
            if(name==null||!name.matches("[A-Za-z0-9_.-]{1,15}")||address==null||interfaceIndex<1)throw ReceiverProductConfiguration.invalid("apInterface");
            BluetoothAdapter bt=BluetoothAdapter.getDefaultAdapter();if(bt==null||!bt.isEnabled())throw ReceiverProductConfiguration.invalid("localBluetoothEnabled");
            String bluetooth=ReceiverProductConfiguration.mac(bt.getAddress(),"localBluetoothAddress");
            String mac=interfaceMac(name,address,interfaceIndex);
            PackageInfo app=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            String version=ReceiverProductConfiguration.text(app.versionName,"appVersionName",100)+" ("+app.versionCode+")";
            WindowManager windows=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);if(windows==null)throw ReceiverProductConfiguration.invalid("display");
            Display display=windows.getDefaultDisplay();if(display==null)throw ReceiverProductConfiguration.invalid("display");DisplayMetrics metrics=new DisplayMetrics();
            // Introduced at API17; reflection keeps the project's API16 compile jar usable.
            Display.class.getMethod("getRealMetrics",DisplayMetrics.class).invoke(display,metrics);
            if(!mac.equals(interfaceMac(name,address,interfaceIndex))||!bt.isEnabled()||!bluetooth.equals(ReceiverProductConfiguration.mac(bt.getAddress(),"localBluetoothAddress")))
                throw ReceiverProductConfiguration.invalid("platformChangedDuringCapture");
            return new ReceiverProductConfiguration.Observation(scope,interfaceIndex,bluetooth,mac,version,metrics.widthPixels,metrics.heightPixels,metrics.xdpi,metrics.ydpi);
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Android receiver product observation unavailable",e);}
        catch(LinkageError e){throw new IOException("Android receiver product API unavailable",e);}
    }
    private static String interfaceMac(String name,InetAddress address,int expected)throws IOException{
        if(readIndex(name)!=expected)throw ReceiverProductConfiguration.invalid("apInterfaceIndex");
        NetworkInterface nic=NetworkInterface.getByName(name);if(nic==null||!nic.isUp()||nic.isLoopback())throw ReceiverProductConfiguration.invalid("apInterface");
        boolean found=false;for(Enumeration<InetAddress> addresses=nic.getInetAddresses();addresses.hasMoreElements();)if(address.equals(addresses.nextElement()))found=true;
        if(!found)throw ReceiverProductConfiguration.invalid("apLocalAddress");byte[] bytes=nic.getHardwareAddress();if(bytes==null||bytes.length!=6)throw ReceiverProductConfiguration.invalid("apHardwareAddress");
        StringBuilder value=new StringBuilder();for(int i=0;i<bytes.length;i++){if(i!=0)value.append(':');value.append(String.format(Locale.US,"%02X",bytes[i]&255));}
        if(readIndex(name)!=expected)throw ReceiverProductConfiguration.invalid("apInterfaceIndexChanged");return ReceiverProductConfiguration.mac(value.toString(),"apHardwareAddress");
    }
    private static int readIndex(String name)throws IOException{
        // NetworkInterface.getIndex() is unavailable on API17. Read the actual
        // kernel ifindex, not an ordinal from Java enumeration or an IP guess.
        FileInputStream input=new FileInputStream("/sys/class/net/"+name+"/ifindex");ByteArrayOutputStream out=new ByteArrayOutputStream();
        try{int b;while((b=input.read())!=-1){if(out.size()>=16)throw ReceiverProductConfiguration.invalid("apInterfaceIndex");out.write(b);}}
        finally{input.close();}String value=new String(out.toByteArray(),"US-ASCII").trim();
        if(!value.matches("[1-9][0-9]{0,9}"))throw ReceiverProductConfiguration.invalid("apInterfaceIndex");
        try{return Integer.parseInt(value);}catch(NumberFormatException e){throw ReceiverProductConfiguration.invalid("apInterfaceIndex");}
    }
}
