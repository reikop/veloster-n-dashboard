package com.veloster.carplay.receiver;

/** Serialized on the OEM main looper, including complete wired callback bodies.
 * No monitor is held across native/Binder calls; foreign callbacks may enqueue
 * and return while the main thread is inside an OEM call. Native callbacks are
 * still the only session evidence. Same-thread callbacks may be reentrant.
 */
public final class ReceiverController {
    public interface Backend {
        boolean apAvailable() throws Exception;
        boolean blocked() throws Exception;
        boolean receiverExists() throws Exception;
        boolean authBound() throws Exception;
        void create() throws Exception;
        void updateNetwork() throws Exception;
        void delete() throws Exception;
    }
    public static final int OK=0, UNAVAILABLE=1, BUSY=2, WRONG_OWNER=3, FAILED=4;
    private final Backend backend;
    private String owner;
    private boolean wired, prepared, needsCleanup;
    private int observed;
    public ReceiverController(Backend backend) { this.backend=backend; }
    private boolean owns(String token) { return !wired && token.equals(owner); }
    public int begin(String token) {
        if (!valid(token)) return WRONG_OWNER;
        if (owner!=null && !owner.equals(token)) return BUSY;
        try {
            if (wired) return BUSY;
            boolean exists=backend.receiverExists();
            if (owner==null && exists) return BUSY;
            if (!backend.apAvailable() || backend.blocked() || !backend.authBound()) return UNAVAILABLE;
            if (wired) return BUSY;
            if (owner==null) { owner=token; observed=0; prepared=false; needsCleanup=false; }
            if (needsCleanup) return FAILED;
            if (prepared && exists) return OK;
            if (!exists) {
                prepared=false; needsCleanup=true;
                backend.create();
                // Same-thread native/Binder reentrancy can relinquish ownership.
                if (!owns(token)) return BUSY;
                if (!backend.receiverExists()) return FAILED;
                needsCleanup=false;
            }
            if (!owns(token)) return BUSY;
            backend.updateNetwork();
            if (!owns(token)) return BUSY;
            prepared=true;
            return OK;
        } catch (Exception failure) {
            // Partial creation remains owned for cleanup, never a successful retry.
            if (owns(token)) prepared=false;
            return wired?BUSY:FAILED;
        }
    }
    public int network(String token) {
        if (owner==null || !owner.equals(token)) return WRONG_OWNER;
        if (needsCleanup) return FAILED;
        try {
            if (wired) { owner=null; prepared=false; return BUSY; }
            if (!backend.apAvailable() || backend.blocked() || !backend.authBound() || !backend.receiverExists()) return UNAVAILABLE;
            backend.updateNetwork();
            if (!owns(token)) return BUSY;
            prepared=true; return OK;
        } catch (Exception failure) { if (owns(token)) prepared=false; return wired?BUSY:FAILED; }
    }
    public int stop(String token) {
        if (owner==null) return OK;
        if (!owner.equals(token)) return WRONG_OWNER;
        if (wired) { owner=null; prepared=false; return BUSY; }
        try {
            needsCleanup=true;
            backend.delete();
            // A reentrant wired callback owns subsequent receiver work.
            if (!owns(token)) return BUSY;
            owner=null; prepared=false; needsCleanup=false; observed=0; return OK;
        } catch (Exception failure) { if (owns(token)) prepared=false; return wired?BUSY:FAILED; }
    }
    public void shutdown() { if (owner!=null) stop(owner); }
    /** Status is scoped to the caller's generation, not another owner's flags. */
    public int status(String token) {
        if (!valid(token) || (owner!=null && !owner.equals(token))) return WRONG_OWNER;
        return OK;
    }
    public void wired(boolean connected) {
        wired=connected;
        // Ownership never transfers from a wired session back to this controller.
        if (connected) { owner=null; prepared=false; needsCleanup=false; observed=0; }
    }
    public void observe(int event) {
        if (owner==null) return;
        if (event==1) observed=1;       // OEM native session initialized
        else if (event==2) observed|=2; // OEM native session started, not video proof
        else if (event==3) { observed=0; prepared=false; }
    }
    public int flags() { return (owner!=null?1:0)|(wired?2:0)|(observed<<2); }
    static boolean valid(String token) { return token!=null && token.matches("[0-9a-f]{32}"); }
}
