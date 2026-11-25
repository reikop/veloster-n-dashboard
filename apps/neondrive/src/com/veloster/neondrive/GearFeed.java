package com.veloster.neondrive;

import android.content.Context;
import android.os.SystemClock;
import com.hkmc.micom.NModeListener;
import com.hkmc.micom.NModeManager;
import com.hkmc.pgs.PgsManager;
import com.hkmc.pgs.PgsStatusListener;

/**
 * Gear for the HUD: the same passive OEM subscriptions the N dashboard uses (N-mode additional info for
 * the forward gear, rear view status for reverse). Listeners only; no setters, commands or app IPC.
 * Only on the Veloster N head unit (jsn_kr, any build); any missing service is logged and the gear stays unknown.
 */
final class GearFeed {
    private final Context context;
    private final DriveSignals signals;
    private NModeManager nmode;
    private volatile NModeListener nlistener;
    private PgsManager rear;
    private volatile PgsStatusListener rlistener;

    GearFeed(Context context, DriveSignals signals) { this.context = context.getApplicationContext(); this.signals = signals; }

    synchronized void start() {
        if (!HscanFeed.supported()) return;
        if (nmode == null) try {
            nmode = new NModeManager(context);
            nlistener = new NModeListener() {
                public void onNAddInfoReceived(byte[] b) { if (nlistener == this) signals.gearInfo(b, SystemClock.elapsedRealtime()); }
            };
            nmode.registerNModeListener(nlistener);
        } catch (RuntimeException e) { nmode = null; nlistener = null; log("NMode", e); }
        catch (LinkageError e) { nmode = null; nlistener = null; log("NMode", e); }
        if (rear == null) try {
            rear = new PgsManager(context);
            rlistener = new PgsStatusListener() {
                public void onRVMViewStatusChanged(int view) { if (rlistener == this) signals.rearView(view); }
            };
            rear.registerPgsStatusListener(rlistener);
        } catch (RuntimeException e) { rear = null; rlistener = null; log("rear view", e); }
        catch (LinkageError e) { rear = null; rlistener = null; log("rear view", e); }
    }

    synchronized void stop() {
        NModeManager m = nmode; NModeListener l = nlistener; nmode = null; nlistener = null;
        try { if (m != null && l != null) m.unregisterNModeListener(l); }
        catch (RuntimeException e) { log("NMode stop", e); } catch (LinkageError e) { log("NMode stop", e); }
        PgsManager r = rear; PgsStatusListener rl = rlistener; rear = null; rlistener = null;
        try { if (r != null && rl != null) r.unregisterPgsStatusListener(rl); }
        catch (RuntimeException e) { log("rear view stop", e); } catch (LinkageError e) { log("rear view stop", e); }
        signals.rearView(-1);
    }

    private static void log(String source, Throwable e) { android.util.Log.w("NeonDrive", "Gear source unavailable: " + source, e); }
}
