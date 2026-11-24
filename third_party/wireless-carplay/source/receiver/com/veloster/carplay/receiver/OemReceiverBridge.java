package com.veloster.carplay.receiver;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs inside the OEM DiPO service; root-only local control, no network listener. */
public final class OemReceiverBridge implements Runnable {
    public static final String SOCKET="vn.carplay.receiver.v1";
    private static final int MAGIC=0x564e4350;
    private static volatile OemReceiverBridge current;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private final Handler main=MAIN;
    private final ReceiverController controller;
    private final Object service;
    private volatile LocalServerSocket server;
    private volatile LocalSocket client;
    private volatile boolean closed;
    private OemReceiverBridge(Object service) {
        this.service=service; this.controller=new ReceiverController(new OemBackend(service));
    }
    public static synchronized void attach(Object service) {
        if (current!=null || service==null) return;
        OemReceiverBridge bridge=new OemReceiverBridge(service); current=bridge;
        Thread thread=new Thread(bridge,"VN-CarPlay-Receiver"); thread.setDaemon(true); thread.start();
    }
    public static synchronized void detach(Object service) {
        if (current==null || current.service!=service) return;
        OemReceiverBridge old=current; current=null; old.closed=true;
        try { if (old.client!=null) old.client.close(); } catch (Exception ignored) { }
        try { if (old.server!=null) old.server.close(); } catch (Exception ignored) { }
        old.controller.shutdown();
    }
    /** Public OEM callback wrappers call this BEFORE executing any original body.
     * The original Binder callback returns after enqueue, so native work on main
     * cannot deadlock waiting for that Binder reply. The entire original body,
     * not just ownership bookkeeping, runs on the same queue as bridge commands.
     */
    public static void dispatchWired(final Object listener, final Object owningService, final boolean connected) {
        if (listener==null || owningService==null) throw new IllegalArgumentException("OEM callback target missing");
        Runnable callback=new Runnable() { public void run() {
            OemReceiverBridge b=current;
            if (b!=null && !b.closed && b.service==owningService) b.controller.wired(connected);
            invokeOriginal(listener,connected);
        } };
        if (Looper.myLooper()==Looper.getMainLooper()) callback.run();
        else if (!MAIN.post(callback)) {
            // A shutting-down looper cannot safely serialize this callback.
            // Never silently discard it or run it concurrently with OEM work.
            throw new IllegalStateException("OEM main looper rejected iAP2 callback");
        }
    }
    private static void invokeOriginal(Object listener, boolean connected) {
        try {
            Method original=listener.getClass().getDeclaredMethod(connected?
                    "vnOriginalOnIap2Connected":"vnOriginalOnIap2Disconnected");
            original.setAccessible(true); original.invoke(listener);
        } catch (InvocationTargetException wrapped) {
            Throwable cause=wrapped.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw new IllegalStateException("OEM iAP2 callback failed",cause);
        } catch (Exception unavailable) {
            throw new IllegalStateException("OEM iAP2 callback wrapper mismatch",unavailable);
        }
    }
    public static void observed(final Object owningService, final int event) {
        final OemReceiverBridge b=current;
        if (b==null || b.closed || b.service!=owningService) return;
        if (Looper.myLooper()==Looper.getMainLooper()) b.controller.observe(event);
        else b.main.post(new Runnable() { public void run() { if (!b.closed && current==b) b.controller.observe(event); } });
    }
    public void run() {
        // Keep the same controller/ownership across a failed bind or listener.
        // Replacing current would orphan a receiver created before that failure.
        while (!closed) try {
            LocalServerSocket opened=new LocalServerSocket(SOCKET); server=opened;
            if (closed) { opened.close(); return; }
            while (!closed) {
                LocalSocket socket=opened.accept(); client=socket;
                try {
                    if (closed || socket.getPeerCredentials().getUid()!=0) continue;
                    socket.setSoTimeout(2000);
                    DataInputStream input=new DataInputStream(socket.getInputStream());
                    if (input.readInt()!=MAGIC || input.readInt()!=1) continue;
                    final int operation=input.readInt();
                    if (operation<1 || operation>4) continue;
                    byte[] bytes=new byte[32]; input.readFully(bytes);
                    final String token=new String(bytes,"US-ASCII");
                    if (!ReceiverController.valid(token)) continue;
                    final int[] result={5,0}; final AtomicInteger state=new AtomicInteger(0);
                    final CountDownLatch done=new CountDownLatch(1);
                    main.post(new Runnable() { public void run() {
                        if (!state.compareAndSet(0,1)) return;
                        if (!closed) {
                            if (operation==1) result[0]=controller.begin(token);
                            else if (operation==2) result[0]=controller.network(token);
                            else if (operation==3) result[0]=controller.stop(token);
                            else result[0]=controller.status(token);
                            result[1]=result[0]==ReceiverController.WRONG_OWNER?0:controller.flags();
                        }
                        state.set(2); done.countDown();
                    } });
                    if (!done.await(4,TimeUnit.SECONDS)) {
                        // Cancel only if still queued. A running OEM call has unknown outcome;
                        // close without a false failure/success acknowledgement; root may query.
                        state.compareAndSet(0,3); continue;
                    }
                    DataOutputStream output=new DataOutputStream(socket.getOutputStream());
                    output.writeInt(MAGIC); output.writeInt(result[0]); output.writeInt(result[1]); output.flush();
                } catch (Exception ignored) { /* Never include request data in logs. */ }
                finally { try { socket.close(); } catch (Exception ignored) { } client=null; }
            }
        } catch (Exception ignored) { }
        finally {
            try { if (server!=null) server.close(); } catch (Exception ignored) { }
            server=null;
            if (!closed) try { Thread.sleep(250); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); closed=true; }
        }
    }
    private static final class OemBackend implements ReceiverController.Backend {
        final Object target;
        OemBackend(Object target) { this.target=target; }
        Object call(String name) throws Exception {
            Method method=target.getClass().getDeclaredMethod(name); method.setAccessible(true);
            return method.invoke(target);
        }
        boolean yes(String name) throws Exception { return Boolean.TRUE.equals(call(name)); }
        public boolean apAvailable() throws Exception {
            NetworkInterface nic=NetworkInterface.getByName("usbwifi0");
            if (nic==null || !nic.isUp()) return false;
            Enumeration<InetAddress> list=nic.getInetAddresses();
            while (list.hasMoreElements()) if ("172.30.88.1".equals(list.nextElement().getHostAddress())) return true;
            return false;
        }
        public boolean blocked() throws Exception {
            if (yes("isDelaymode") || yes("isEmergencyExceptTheft") || yes("isTMUCall") || yes("isUVOService")) return true;
            Class<?> utils=Class.forName("com.daudio.app.dipo.util.DipoUtils",false,target.getClass().getClassLoader());
            return Boolean.TRUE.equals(utils.getMethod("isValetModeOn").invoke(null));
        }
        public boolean receiverExists() throws Exception { return yes("isDipoCreated") || yes("isDipoStarted"); }
        public boolean authBound() throws Exception {
            Class<?> player=Class.forName("com.daudio.app.dipo.player.DipoPlayer",false,target.getClass().getClassLoader());
            Field field=player.getDeclaredField("mIAP2Service"); field.setAccessible(true);
            return field.get(null)!=null;
        }
        public void create() throws Exception { call("createDipo"); }
        public void updateNetwork() throws Exception { call("updateNetworkStatus"); }
        public void delete() throws Exception { call("deleteDipo"); }
    }
}
