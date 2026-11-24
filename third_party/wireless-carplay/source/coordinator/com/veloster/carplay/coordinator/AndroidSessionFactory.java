/* SPDX-License-Identifier: GPL-3.0-only. Isolated wireless integration module. */
package com.veloster.carplay.coordinator;

import android.content.Context;
import com.veloster.carplay.Api17RfcommConnector;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.RfcommConnector;
import java.io.IOException;

/** Production binding/transport composition. Caller supplies real platform
 * observations, user consent and accessory identity, after installation checks.
 * No default identity, auth fixture or synthesized Wi-Fi/native session evidence.
 */
public final class AndroidSessionFactory implements SessionCoordinator.Factory {
    private final Context context;
    private final Iap2WirelessController.Environment platform;
    private final Iap2WirelessController.Consent consent;
    private final Iap2WirelessController.Identity identity;
    private final Iap2WirelessController.StartMode mode;
    private final WirelessSession.Observer observer;
    public AndroidSessionFactory(Context context,Iap2WirelessController.Environment platform,
                                 Iap2WirelessController.Consent consent,Iap2WirelessController.Identity identity,
                                 Iap2WirelessController.StartMode mode,WirelessSession.Observer observer) {
        if(context==null || platform==null || consent==null || identity==null || mode==null)throw new NullPointerException();
        Context application=context.getApplicationContext();this.context=application==null?context:application;
        this.platform=platform;this.consent=consent;this.identity=identity;this.mode=mode;this.observer=observer;
    }
    public SessionCoordinator.Handle open(SessionJournal.Record record,ReceiverControl fenced)throws IOException,InterruptedException {
        if(!platform.current(record.apSession))throw new IOException("AP generation changed before authentication binding");
        OemAuthBinding binding=OemAuthBinding.open(context,5000);RfcommConnector connector=null;boolean returned=false;
        try {
            connector=Api17RfcommConnector.create();
            WirelessSession session=new WirelessSession(new WirelessSession.RfcommTransport(connector),fenced,
                binding.authentication(),platform,consent,identity,record.phone,mode,observer,record.owner);
            SessionCoordinator.Handle handle=new SessionCoordinator.Handle(session,binding);returned=true;return handle;
        }finally {
            if(!returned){try{if(connector!=null)connector.close();}finally{binding.close();}}
        }
    }
}
