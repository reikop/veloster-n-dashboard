/* SPDX-License-Identifier: GPL-3.0-only. Independently written feedback projection. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.*;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/** Receiver output-clock feedback. Nominal rate is the initial/unreliable fallback.
 * A measured rate never changes or extrapolates the observed sample/time tuple. */
public final class AudioFeedback {
    private AudioFeedback(){}
    private static BigInteger unsigned(long value){
        BigInteger result=BigInteger.valueOf(value&Long.MAX_VALUE);return value<0?result.setBit(63):result;
    }
    public static Map<String,Object> project(AudioDescription description,AudioHead head,ClockReference reference,long now)throws IOException{
        return project(description,head,null,reference,now);
    }
    public static Map<String,Object> project(AudioDescription description,AudioHead head,AudioRateEstimator.Estimate rate,ClockReference reference,long now)throws IOException{
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("type",description.type);result.put("sr",(double)description.format.sampleRate);
        if(rate!=null&&rate.nominalRate==description.format.sampleRate&&rate.matches(head,now))result.put("sr",rate.sampleRate);
        if(head==null||!head.fresh(now)||reference==null||!reference.isFresh(now))return result;
        // A new correction must not relabel a head observed before that measurement.
        long measured=now-reference.measurementAgeNanos(now);
        if(head.monotonicNanos-measured<0)return result;
        result.put("streamConnectionID",new BigInteger(description.connectionId));
        result.put("timestamp",unsigned(reference.toNtpTime(head.monotonicNanos,now)));
        result.put("timestampRawNs",unsigned(head.monotonicNanos));
        result.put("sampleTime",head.frames&0xffffffffL);
        return result;
    }
}
