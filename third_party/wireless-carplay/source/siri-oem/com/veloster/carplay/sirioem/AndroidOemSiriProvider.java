/* SPDX-License-Identifier: GPL-3.0-only. Optional pinned vehicle PTT provider. */
package com.veloster.carplay.sirioem;
import android.content.Context;
import com.veloster.carplay.input.ReceiverHardwareSiriSource;
import java.io.IOException;

/** Construction performs no IPC. The receiver's verified source worker owns
 * opening, renewing and closing the exact Binder lease. */
public final class AndroidOemSiriProvider implements ReceiverHardwareSiriSource.Provider {
    private final Context context;private final String jarSha256;
    public AndroidOemSiriProvider(Context context,String jarSha256){
        if(context==null||jarSha256==null||!jarSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Pinned OEM PTT source required");
        Context app=context.getApplicationContext();this.context=app==null?context:app;this.jarSha256=jarSha256;
    }
    public ReceiverHardwareSiriSource.Lease open(String nonce,final ReceiverHardwareSiriSource.Listener listener)throws IOException{
        final AndroidOemPttClient.Lease lease=AndroidOemPttClient.open(context,jarSha256,nonce,new AndroidOemPttClient.Listener(){
            public boolean down(){return listener.down();}
            public void up(){listener.up();}
            public void retired(){listener.retired();}
        });
        return new ReceiverHardwareSiriSource.Lease(){
            public void renew(boolean downEnabled)throws IOException{if(!lease.renew(downEnabled))throw new IOException("OEM PTT lease retired");}
            public void close(){lease.close();}
        };
    }
}
