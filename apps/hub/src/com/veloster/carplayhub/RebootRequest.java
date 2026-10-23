package com.veloster.carplayhub;

/** One explicit confirmation authorizes at most one request. Creating/opening/cancelling never reboots. */
public final class RebootRequest {
    public interface Backend {boolean permitted();void reboot()throws Exception;}
    private final Backend backend;
    private long sequence;
    private boolean armed,dispatched;
    public RebootRequest(Backend backend){this.backend=backend;}
    public synchronized long arm(){if(dispatched)return -1;armed=true;return ++sequence;}
    public synchronized void cancel(){armed=false;sequence++;}
    public boolean request(long ticket)throws Exception {
        synchronized(this){
            if(!armed||dispatched||ticket!=sequence)return false;
            armed=false;sequence++;
            if(!backend.permitted())throw new SecurityException("REBOOT permission unavailable");
            dispatched=true;
        }
        try{backend.reboot();return true;}
        catch(Exception e){synchronized(this){dispatched=false;}throw e;}
        catch(LinkageError e){synchronized(this){dispatched=false;}throw e;}
    }
}
