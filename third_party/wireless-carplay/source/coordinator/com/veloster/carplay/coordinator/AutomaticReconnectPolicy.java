/* SPDX-License-Identifier: GPL-3.0-only. Foreground eligibility and bounded retry. */
package com.veloster.carplay.coordinator;

/** Main-thread policy only. The caller supplies a monotonic clock, the current
 * selected store/generation/phone key, actual foreground+auto eligibility, and
 * controller idle/cleanup state. It never starts, cancels or recovers resources.
 * RUNNING means only task existence; it must not reset the retry budget. */
public final class AutomaticReconnectPolicy {
    private final int limit;private final long firstDelay,maxDelay;
    private String key;private boolean eligible,suppressed,inFlight;private int attempts;private long next=-1;
    public AutomaticReconnectPolicy(int maxAttempts,long firstRetryMillis,long maxRetryMillis){
        if(maxAttempts<1||firstRetryMillis<1||maxRetryMillis<firstRetryMillis)throw new IllegalArgumentException("Retry bounds");
        limit=maxAttempts;firstDelay=firstRetryMillis;maxDelay=maxRetryMillis;
    }
    private static void time(long now){if(now<0)throw new IllegalArgumentException("Monotonic time required");}
    public void update(String selectedGenerationKey,boolean allowed,long now){
        time(now);if(allowed&&(selectedGenerationKey==null||selectedGenerationKey.length()==0))throw new IllegalArgumentException("Selected generation required");
        if(key==null?selectedGenerationKey!=null:!key.equals(selectedGenerationKey)){
            key=selectedGenerationKey;attempts=0;suppressed=false;inFlight=false;next=-1;
        }
        eligible=allowed;if(!allowed)next=-1;
        else if(!suppressed&&!inFlight&&attempts<limit&&next<0)next=attempts==0?now:after(now,delay());
    }
    /** Atomically reserves one start. An uncertain cleanup never consumes an
     * attempt and never schedules automatic recovery or a replacement. */
    public boolean claim(long now,boolean idle,boolean cleanupRequired){
        time(now);if(!eligible||suppressed||inFlight||cleanupRequired||!idle||next<0||now<next||attempts>=limit)return false;
        attempts++;inFlight=true;next=-1;return true;
    }
    /** Call only for the currently claimed attempt after its controller drains.
     * The caller fences stale callbacks using the same selection/request key. */
    public void finished(long now){
        time(now);if(!inFlight)return;inFlight=false;
        next=eligible&&!suppressed&&attempts<limit?after(now,delay()):-1;
    }
    public void cancel(){suppressed=true;next=-1;}
    public void reset(String selectedGenerationKey,boolean allowed,long now){
        key=null;attempts=0;suppressed=false;inFlight=false;next=-1;update(selectedGenerationKey,allowed,now);
    }
    private long delay(){long value=firstDelay;for(int n=1;n<attempts&&value<maxDelay;n++)value=value>maxDelay/2?maxDelay:value*2;return value;}
    private static long after(long now,long delay){return now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;}
    public long nextAttemptAt(){return next;}
    public int attempts(){return attempts;}
}
