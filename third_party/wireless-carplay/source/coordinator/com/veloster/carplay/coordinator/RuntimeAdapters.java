/* SPDX-License-Identifier: GPL-3.0-only. Actual OEM binding/AP credentials for the runtime worker. */
package com.veloster.carplay.coordinator;
import android.content.Context;
import com.veloster.carplay.*;
import com.veloster.carplay.location.Api17GpsLocationSource;
import com.veloster.carplay.location.LocationSource;
import com.veloster.carplay.control.RuntimeControlHost;
import com.veloster.carplay.pairing.PairVerify;
import java.io.IOException;
import java.util.Arrays;

/** Factory for RuntimeControlHost. Receiver/phone observations and UI consumers remain explicit inputs. */
public final class RuntimeAdapters implements RuntimeControlHost.Factory {
    public interface Observations {
        /** Nonblocking current coordinator/AP/selected-phone observation; never a shell query per poll. */
        void checkCurrent(PairVerify.Context context)throws IOException;
        Iap2WirelessController.ReceiverIdentity receiver(PairVerify.Context context)throws IOException;
        /** Wire Bluetooth may be null; context.selectedBluetooth is only the intended selection. */
        void transportIdentifiers(PairVerify.Context context,String observedBluetooth,String usbIdentifier)throws IOException;
        Iap2RuntimeController.Listener listener(PairVerify.Context context)throws IOException;
    }
    private final Context android;
    private final ApManagerClient manager;
    private final Iap2WirelessController.Identity identity;
    private final Observations observations;
    public RuntimeAdapters(Context android,ApManagerClient manager,Iap2WirelessController.Identity identity,Observations observations){
        if(android==null||manager==null||identity==null||observations==null)throw new NullPointerException();
        this.android=android;this.manager=manager;this.identity=identity;this.observations=observations;
    }
    public RuntimeControlHost.Resources open(final PairVerify.VerifiedSession session)throws IOException,InterruptedException{
        final PairVerify.Context context=session.context();session.assertCurrent();observations.checkCurrent(context);
        if(!manager.current(context.apSession))throw new IOException("Runtime AP is no longer current");
        // Separate bind lease: closing the Bluetooth bootstrap cannot unbind runtime authentication.
        final OemAuthBinding binding;
        try{binding=OemAuthBinding.open(android,10000);}
        catch(OemAuthBinding.PendingCleanupException pending){throw new SessionCoordinator.FactoryCleanupException("Runtime authentication cleanup pending",pending.binding,pending);}
        boolean success=false;RuntimeControlHost.Resources acquired=null;
        try{
            Iap2RuntimeController.Environment environment=new Iap2RuntimeController.Environment(){
                public void checkCurrent()throws IOException{
                    session.assertCurrent();observations.checkCurrent(context);
                }
                public Iap2WirelessController.ApSnapshot accessPoint()throws IOException{
                    checkCurrent();ApCredentials credentials=manager.credentials();byte[] name=null,secret=null;char[] password=null;
                    try{
                        if(!context.apSession.equals(credentials.sessionToken))throw new IOException("Runtime credentials belong to another AP");
                        name=credentials.ssidBytes();secret=credentials.passphraseBytes();password=new char[secret.length];
                        for(int i=0;i<secret.length;i++)password[i]=(char)(secret[i]&255);
                        checkCurrent();return new Iap2WirelessController.ApSnapshot(credentials.sessionToken,new String(name,"UTF-8"),password,credentials.channel,"172.30.88.1",null);
                    }finally{credentials.close();if(name!=null)Arrays.fill(name,(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);if(password!=null)Arrays.fill(password,'\0');}
                }
                public Iap2WirelessController.ReceiverIdentity receiver()throws IOException{checkCurrent();return observations.receiver(context);}
                public void transportIdentifiers(String selected,String usb)throws IOException{checkCurrent();observations.transportIdentifiers(context,selected,usb);}
            };
            Iap2RuntimeController.Listener listener=observations.listener(context);
            final LocationSource location=Api17GpsLocationSource.create(android);
            RuntimeControlHost.Resources resources=new RuntimeControlHost.Resources(binding.authentication(),identity,environment,listener,location){
                public void close()throws IOException{try{location.close();}finally{binding.close();}}
            };acquired=resources;
            session.assertCurrent();observations.checkCurrent(context);success=true;return resources;
        }finally{if(!success){java.io.Closeable pendingResources=acquired==null?binding:acquired;try{pendingResources.close();}catch(IOException pending){throw new SessionCoordinator.FactoryCleanupException("Runtime resource cleanup pending",pendingResources,pending);}}}
    }
}
