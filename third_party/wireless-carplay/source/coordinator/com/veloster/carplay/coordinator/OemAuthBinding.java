/* SPDX-License-Identifier: GPL-3.0-only. Isolated wireless integration module. */
package com.veloster.carplay.coordinator;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import com.veloster.carplay.Iap2WirelessController;
import com.veloster.carplay.auth.OemAuthClient;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit normal Android binding; no service-manager shortcut/direct CP access.
 * Open/close belong to the coordinator worker. The service's synchronous Binder
 * crypto calls remain uncancellable; closing this lease prevents subsequent calls.
 */
public final class OemAuthBinding implements Closeable {
    public static final class PendingCleanupException extends IOException {
        private static final long serialVersionUID=1L;
        public final OemAuthBinding binding;
        PendingCleanupException(OemAuthBinding binding,IOException cause){super("OEM binding cleanup still pending",cause);this.binding=binding;}
    }
    private final Context context;
    private final CountDownLatch ready=new CountDownLatch(1);
    private final Object lock=new Object();
    private boolean registered,closed,disconnected;
    private IBinder remote;
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder service) {
            synchronized(lock) {
                if(closed || disconnected)return;
                if(!new ComponentName(OemAuthClient.SERVICE_PACKAGE,OemAuthClient.SERVICE_CLASS).equals(name))disconnected=true;
                else if(remote==null)remote=service;
                ready.countDown();
            }
        }
        public void onServiceDisconnected(ComponentName name) {
            synchronized(lock){disconnected=true;remote=null;ready.countDown();}
        }
    };
    private OemAuthBinding(Context context){this.context=context;}
    public static OemAuthBinding open(Context supplied,long timeoutMillis)throws IOException,InterruptedException {
        if(supplied==null || timeoutMillis<1 || timeoutMillis>30000)throw new IllegalArgumentException();
        if(Looper.myLooper()!=null && Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException("OEM binding must wait on a worker");
        Context application=supplied.getApplicationContext();
        OemAuthBinding binding=new OemAuthBinding(application==null?supplied:application);
        boolean success=false;
        try {
            // Android keeps the ServiceConnection dispatcher even for refusal or
            // SecurityException: always attempt the matching unbind after a call.
            binding.registered=true;
            if(!binding.context.bindService(OemAuthClient.serviceIntent(),binding.connection,Context.BIND_AUTO_CREATE))
                throw new IOException("OEM authentication service unavailable");
            if(!binding.ready.await(timeoutMillis,TimeUnit.MILLISECONDS))throw new IOException("OEM authentication binding timed out");
            // Verify the returned Binder interface on the worker, not its main-thread callback.
            binding.authentication();success=true;return binding;
        } catch(RuntimeException unavailable){throw new IOException("OEM authentication binding unavailable",unavailable);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        finally{if(!success)try{binding.close();}catch(IOException pending){throw new PendingCleanupException(binding,pending);}}
    }
    /** Memory-only readiness check; no crypto/descriptor Binder transaction. */
    public void checkCurrent()throws IOException {synchronized(lock){if(closed||disconnected||remote==null)throw new IOException("OEM authentication binding disconnected");}}
    public Iap2WirelessController.Authentication authentication()throws IOException {
        final IBinder binder;
        synchronized(lock){if(closed || disconnected || remote==null)throw new IOException("OEM authentication binding disconnected");binder=remote;}
        final Iap2WirelessController.Authentication adapter=BootstrapAdapters.authentication(binder);
        return new Iap2WirelessController.Authentication() {
            private void check()throws IOException {
                synchronized(lock){if(closed || disconnected || remote!=binder)throw new IOException("OEM authentication binding disconnected");}
            }
            public byte[] certificate()throws IOException{check();return adapter.certificate();}
            public byte[] sign(byte[] challenge)throws IOException{check();return adapter.sign(challenge);}
        };
    }
    public synchronized void close()throws IOException {
        boolean unbind;
        synchronized(lock){closed=true;remote=null;ready.countDown();unbind=registered;}
        if(unbind){
            try{context.unbindService(connection);}
            catch(IllegalArgumentException alreadyGone){/* Android reports no registered connection. */}
            catch(RuntimeException pending){throw new IOException("OEM authentication unbind failed",pending);}
            catch(LinkageError pending){throw new IOException("OEM authentication unbind API failed",pending);}
            synchronized(lock){registered=false;}
        }
    }
}
