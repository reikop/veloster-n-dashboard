/* SPDX-License-Identifier: GPL-3.0-only. API17 managed-Wi-Fi multicast filter lease. */
package com.veloster.carplay.discovery;
import android.net.wifi.WifiManager;
import java.io.IOException;
/** Use for Android-managed Wi-Fi. Native USB AP interfaces do not acquire an
 * unrelated Wi-Fi lock; membership is instead bound by the mDNS socket. */
public final class AndroidMulticastLease implements ReceiverDiscovery.MulticastLease {
    private final WifiManager.MulticastLock lock;private boolean acquired;
    public AndroidMulticastLease(WifiManager manager){if(manager==null)throw new NullPointerException();lock=manager.createMulticastLock("vn-carplay-discovery");lock.setReferenceCounted(false);}
    public synchronized void acquire()throws IOException{
        if(acquired)throw new IOException("Multicast lease already acquired");
        try{lock.acquire();acquired=true;}
        catch(RuntimeException e){if(lock.isHeld())lock.release();throw new IOException("Cannot acquire multicast filter lease",e);}
    }
    public synchronized void release(){if(acquired){acquired=false;if(lock.isHeld())lock.release();}}
}
