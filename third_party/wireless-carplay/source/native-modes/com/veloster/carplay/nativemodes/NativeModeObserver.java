/* SPDX-License-Identifier: GPL-3.0-only. OEM observations, never mode setters. */
package com.veloster.carplay.nativemodes;

/** Recognizes a bounded subset of OEM user requests. A request is not acceptance.
 * No disk snapshot, lifecycle event, focus callback or unqualified mode value can
 * manufacture a choice. Coverage excludes touchscreen reason NONE and Home. */
public final class NativeModeObserver {
    public interface Listener { void accepted(Selection selection); }
    public static final class Selection {
        public final long generation, requestedAt; public final int mode,reason;
        public final boolean screen,media;
        Selection(Pending p,boolean s,boolean m){generation=p.generation;requestedAt=p.at;mode=p.mode;reason=p.reason;screen=s;media=m;}
    }
    private static final class Pending {
        final long generation,at;final int mode,reason,running;boolean changed,screenSent;Selection emitted;
        Pending(long g,int m,int r,int why,long at){generation=g;mode=m;running=r;reason=why;this.at=at;}
    }
    private final Listener listener;private final long startedAt;private long generation;private Pending pending;
    public NativeModeObserver(long startedAt,Listener listener){if(startedAt<0||listener==null)throw new IllegalArgumentException();this.startedAt=startedAt;this.listener=listener;}
    public synchronized long generation(){return generation;}
    public synchronized void request(int mode,int running,int reason,long timestamp,long now){
        cancel();
        // OEM uptime and our uptime share one clock. Replay before registration,
        // stale/future timestamps, unknown reasons and non-media sources fail closed.
        if(timestamp<startedAt||timestamp>now||now-timestamp>3000||!userReason(reason)||!mediaMode(mode)||(running!=1&&running!=2))return;
        pending=new Pending(generation,mode,running,reason,timestamp);
    }
    public synchronized void changed(int mode){if(pending!=null){pending.emitted=null;if(pending.mode==mode){if(generation==Long.MAX_VALUE)throw new IllegalStateException("Mode generation exhausted");generation++;pending.changed=true;}else cancel();}}
    /** Fence a worker callback queued for later main-thread dispatch. */
    public synchronized boolean isCurrent(Selection selection,long now){Pending p=pending;return selection!=null&&p!=null&&p.emitted==selection&&p.generation==selection.generation&&p.screenSent&&p.changed&&now>=p.at&&now-p.at<=3000;}
    public synchronized void cancel(){if(generation==Long.MAX_VALUE)throw new IllegalStateException("Mode generation exhausted");generation++;pending=null;}
    /** Called only after a fresh Binder current-mode + actual top-Activity read.
     * sampleGeneration fences callbacks delivered during those reads. */
    public synchronized void sample(long sampleGeneration,long now,int currentMode,String acceptedPackage,String topPackage){
        Pending p=pending;if(p==null||sampleGeneration!=generation)return;
        if(now<p.at||now-p.at>3000){cancel();return;}
        if(!p.changed||currentMode!=p.mode||!matches(p.mode,acceptedPackage))return;
        boolean screen=!p.screenSent&&p.running==1&&acceptedPackage.equals(topPackage);
        if(!screen)return;
        p.screenSent=true;
        // currentRealMode/onModeChanged also update for audioChange=false. They
        // cannot establish a last-user music choice. Keep that field false.
        p.emitted=new Selection(p,true,false);listener.accepted(p.emitted);
    }
    private static boolean userReason(int reason){return reason==3||reason==6||reason==8||reason==9;}
    public static boolean mediaMode(int mode){return packageName(mode)!=null||mode==2||mode==3||mode==4;}
    private static boolean matches(int mode,String name){
        if(name==null)return false;
        if(mode==2||mode==3||mode==4)return name.equals("com.hkmc.av.app.radiokr")||name.equals("com.hkmc.av.app.radioge");
        return name.equals(packageName(mode));
    }
    private static String packageName(int mode){switch(mode){
        case 0x31:return "com.daudio.av.app.usbmusic";
        case 0x32:return "com.daudio.av.app.usbvideo";
        case 0x34:return "com.daudio.av.app.ipodmusic";
        case 0x35:return "com.hkmc.av.app.AvAux.media";
        case 0x36:return "com.daudio.av.app.btaudio.ui";
        case 0x37:return "com.daudio.av.app.mymusic";
        case 0x81:return "com.hkmc.av.app.naturesound";
        default:return null;
    }}
}
