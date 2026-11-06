/* SPDX-License-Identifier: GPL-3.0-only. See NOTICE.md. */
package com.veloster.carplay.tunnel;
import java.util.*;

/** Bounded loss hints, never a replay guard or a media clock. Only call received
 * after AEAD verification. Sequence is not authenticated in this wire profile,
 * so it may only suggest short requests consistent with authenticated nonces.
 * Monotonic times are supplied by the caller; no timer or transport is owned. */
public final class AudioResend {
    private static final long LIFE=40000000L,RETRY=10000000L;
    private static final int LIMIT=32;
    private static final class Missing {final long created,counter;long sent,firstSent;int tries;Missing(long now,long counter){created=now;this.counter=counter;}}
    private final LinkedHashMap<Integer,Missing> missing=new LinkedHashMap<Integer,Missing>();
    // Last32 issued entries survive active-request expiry. A control worker may
    // be descheduled between receive()'s timestamp and receipt lookup.
    private final LinkedHashMap<Integer,Missing> history=new LinkedHashMap<Integer,Missing>();
    private boolean seen,requested;private int sequence;private long nonce,lastRequest,epoch;
    /** A receipt proves only that this hint arrived while a request was live.
     * It never authorizes media without the independent AEAD/replay checks. */
    public static final class Receipt {
        private final AudioResend owner;private final int sequence;private final long counter,epoch;
        private Receipt(AudioResend owner,int sequence,long counter,long epoch){this.owner=owner;this.sequence=sequence;this.counter=counter;this.epoch=epoch;}
    }
    public synchronized void received(int seq,long counter,long now){
        expire(now);Missing expected=missing.get(seq);if(expected!=null&&expected.counter==counter)missing.remove(seq);
        if(!seen){seen=true;sequence=seq;nonce=counter;return;}
        if(!AudioPacketDecoder.unsignedGreater(counter,nonce))return;
        long distance=counter-nonce;int delta=(seq-sequence)&65535;
        // Large jumps, forged sequence changes and counter resets never amplify traffic.
        if(distance>1&&distance<=LIMIT+1&&delta==distance){
            for(int n=1;n<delta&&missing.size()<LIMIT;n++){
                int value=(sequence+n)&65535;if(!missing.containsKey(value))missing.put(value,new Missing(now,nonce+n));
            }
        }
        nonce=counter;sequence=seq;
    }
    public synchronized boolean requested(int seq,long now){expire(now);Missing m=missing.get(seq);return m!=null&&m.tries>0;}
    /** Validate the authenticated inner nonce as well as its unauthenticated hint. */
    public synchronized boolean requested(int seq,long counter,long now){expire(now);Missing m=missing.get(seq);return m!=null&&m.tries>0&&m.counter==counter;}
    public synchronized Receipt receipt(int seq,long arrived){
        Missing m=history.get(seq);
        return m==null||m.tries==0||arrived-m.created<0||arrived-m.created>=LIFE||arrived-m.firstSent<0?null:new Receipt(this,seq,m.counter,epoch);
    }
    public synchronized boolean matches(Receipt receipt,int seq,long counter){return receipt!=null&&receipt.owner==this&&receipt.epoch==epoch&&receipt.sequence==seq&&receipt.counter==counter;}
    public synchronized byte[] next(long now){
        expire(now);if(requested&&now-lastRequest<RETRY)return null;
        for(Map.Entry<Integer,Missing> entry:missing.entrySet()){
            Missing m=entry.getValue();if(m.tries>=3||m.tries>0&&now-m.sent<RETRY)continue;
            int first=entry.getKey(),count=1;
            while(count<LIMIT){Missing next=missing.get((first+count)&65535);
                if(next==null||next.tries>=3||next.tries>0&&now-next.sent<RETRY)break;count++;}
            for(int n=0;n<count;n++){
                int seq=(first+n)&65535;Missing value=missing.get(seq);
                if(value.tries==0){
                    value.firstSent=now;history.remove(seq);history.put(seq,value);
                    while(history.size()>LIMIT){Iterator<Integer> old=history.keySet().iterator();old.next();old.remove();}
                }
                value.sent=now;value.tries++;
            }
            requested=true;lastRequest=now;
            return new byte[]{(byte)0x80,(byte)0xd5,0,1,(byte)(first>>8),(byte)first,(byte)(count>>8),(byte)count};
        }
        return null;
    }
    private void expire(long now){Iterator<Missing> it=missing.values().iterator();while(it.hasNext())if(now-it.next().created>=LIFE)it.remove();}
    public synchronized void clear(){missing.clear();history.clear();seen=false;requested=false;epoch++;}
}
