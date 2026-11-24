// SPDX-License-Identifier: GPL-3.0-only
package com.veloster.carplay.sirioem;

/** One physical PTT destination per DOWN/UP pair. No Android or OEM dependencies. */
public final class PttRouteCore {
    public interface Clock {long uptimeMillis();}
    /** null from down means delivery outcome is uncertain; the press is consumed. */
    public interface Sink {Boolean down(long sequence);void up(long sequence);void retired();}
    public static final int PTT=0x105,DOWN=0,UP=1,LONG=0x80,SKIP_UP=0x20;
    public static final long LEASE_MILLIS=1500;
    private final Clock clock;
    private Claim claim,held,pending;private boolean pendingUp,completionPending,suppressedPair;private int quarantineDepth;
    private long sequence;
    public static final class Claim {
        public final String token;public final int uid;public final Sink sink;
        private long until;private boolean live=true,enabled,awaitingCompletion;
        Claim(String token,int uid,Sink sink,long until){this.token=token;this.uid=uid;this.sink=sink;this.until=until;}
    }
    public PttRouteCore(Clock clock){if(clock==null)throw new NullPointerException();this.clock=clock;}
    private static void notifyRetired(Claim value){if(value!=null)try{value.sink.retired();}catch(RuntimeException ignored){}}
    private void quarantineLocked(Claim value){if(value!=null&&!value.awaitingCompletion){value.awaitingCompletion=true;quarantineDepth++;}}
    private Claim retireLocked(Claim value){if(value!=null&&value.live){if(held==value||pending==value)quarantineLocked(value);value.live=false;return value;}return null;}
    /** Keep future physical presses paired and suppressed until client completion is acknowledged. */
    public synchronized void quarantine(Claim value){quarantineLocked(value);}
    public synchronized void completionAcknowledged(Claim value){if(value!=null&&value.awaitingCompletion){value.awaitingCompletion=false;quarantineDepth--;}}
    private boolean current(Claim value,long now){return value!=null&&value.live&&now>=0&&now<value.until;}
    private Claim expireLocked(long now){if(claim!=null&&!current(claim,now)){Claim old=retireLocked(claim);claim=null;return old;}return null;}
    public Claim acquire(String token,int uid,Sink sink){
        if(token==null||token.length()<16||token.length()>128||uid<1||sink==null)throw new IllegalArgumentException("PTT claim");
        Claim retired,next;long now=clock.uptimeMillis();long until=now+LEASE_MILLIS;
        if(now<0||until<now)throw new IllegalStateException("PTT clock");
        synchronized(this){retired=retireLocked(claim);claim=next=new Claim(token,uid,sink,until);}
        notifyRetired(retired);return next;
    }
    public boolean renew(Claim value,int uid,boolean downEnabled){
        Claim retired;boolean good=false;long now=clock.uptimeMillis();
        synchronized(this){retired=expireLocked(now);if(value!=null&&claim==value&&value.uid==uid&&current(value,now)&&now<=Long.MAX_VALUE-LEASE_MILLIS){value.until=now+LEASE_MILLIS;value.enabled=downEnabled;good=true;}}
        notifyRetired(retired);return good;
    }
    public void release(Claim value,int uid){Claim retired=null;
        synchronized(this){if(value!=null&&value.uid==uid){if(claim==value)claim=null;retired=retireLocked(value);}}
        notifyRetired(retired);
    }
    public boolean alive(Claim value){Claim retired;boolean good;long now=clock.uptimeMillis();
        synchronized(this){retired=expireLocked(now);good=value!=null&&claim==value&&current(value,now);}
        notifyRetired(retired);return good;
    }
    /** Returns true when OEM delivery must be suppressed. No sink callback runs under the core lock.
     * A prior accepted DOWN owns its UP even if the lease later expires or is replaced. */
    public boolean route(int action,int flags,int keyCode,int repeat,boolean safe){
        if(keyCode!=PTT)return false;
        Claim retired,target=null;long number=0;boolean consume=false,sendUp=false,sendDown=false;
        long now=clock.uptimeMillis();
        synchronized(this){
            retired=expireLocked(now);
            if(completionPending){consume=true;if(action==DOWN&&repeat==0)suppressedPair=true;else if(action==UP&&(flags&SKIP_UP)==0&&suppressedPair)suppressedPair=false;}
            else if(suppressedPair){consume=true;if(action==UP&&(flags&SKIP_UP)==0)suppressedPair=false;}
            else if(held!=null){consume=true;if(action==UP&&(flags&SKIP_UP)==0){target=held;held=null;if(current(target,now)&&claim==target){completionPending=true;sendUp=true;number=++sequence;}}}
            else if(pending!=null){consume=true;
                if(action==UP&&(flags&SKIP_UP)==0){if(suppressedPair)suppressedPair=false;else pendingUp=true;}
                else if(action==DOWN&&repeat==0&&pendingUp)suppressedPair=true;
            }
            else if(quarantineDepth>0){consume=true;if(action==DOWN&&repeat==0)suppressedPair=true;}
            else if(action==DOWN&&safe&&repeat==0&&(flags&LONG)==0&&current(claim,now)&&claim.enabled){target=claim;pending=target;number=++sequence;sendDown=true;}
        }
        notifyRetired(retired);
        if(sendUp){try{target.sink.up(number);}catch(RuntimeException lost){release(target,target.uid);}finally{synchronized(this){completionPending=false;}}return true;}
        if(consume)return true;
        if(!sendDown)return false;
        Boolean accepted;try{accepted=target.sink.down(number);}catch(RuntimeException uncertain){accepted=null;}
        Claim becameRetired,uncertainRetired=null;boolean stillCurrent,releasePending;
        now=clock.uptimeMillis();
        synchronized(this){
            releasePending=pending==target&&pendingUp;
            if(pending==target){pending=null;pendingUp=false;}
            becameRetired=expireLocked(now);stillCurrent=current(target,now)&&claim==target;
            if(Boolean.TRUE.equals(accepted)&&stillCurrent){if(held==null)held=target;consume=true;}
            else if(accepted==null||Boolean.TRUE.equals(accepted)){if(held==null)held=target;consume=true;
                if(accepted==null){uncertainRetired=retireLocked(target);if(claim==target)claim=null;}}
            else{if(held==target)held=null;consume=releasePending;}
        }
        notifyRetired(becameRetired);
        notifyRetired(uncertainRetired);
        if(consume&&!stillCurrent&&becameRetired!=target&&uncertainRetired!=target)notifyRetired(target);
        if(consume&&releasePending){Claim destination;long upNumber=0;
            synchronized(this){destination=held;if(destination==target){held=null;if(stillCurrent&&Boolean.TRUE.equals(accepted)){completionPending=true;upNumber=++sequence;}}}
            if(upNumber!=0)try{target.sink.up(upNumber);}catch(RuntimeException lost){release(target,target.uid);}finally{synchronized(this){completionPending=false;}}
        }
        return consume;
    }
    public synchronized boolean held(){return held!=null||pending!=null||suppressedPair||completionPending||quarantineDepth>0;}
}
