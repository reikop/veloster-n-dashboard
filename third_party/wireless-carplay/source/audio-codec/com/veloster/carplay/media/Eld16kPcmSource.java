/* SPDX-License-Identifier: GPL-3.0-only. Separate unadvertised uplink backend. */
package com.veloster.carplay.media;
import java.io.Closeable;
import java.io.IOException;

/** Maps an already admitted 8/16 kHz mono AudioRecord chunk to one 16 kHz
 * mono, 480-sample AAC-ELD encoder input. Each call is exactly 30 ms. At 8 kHz
 * causal linear interpolation keeps one input-sample history across chunks;
 * 16 kHz is copied exactly. One instance belongs to one capture generation.
 */
public final class Eld16kPcmSource implements Closeable {
    public static final int OUTPUT_FRAMES=480,OUTPUT_BYTES=960;
    private final int sourceRate,sourceFrames;
    private int previous;private boolean started,closed;
    private long consumed,produced;
    public Eld16kPcmSource(int sourceRate){
        if(sourceRate!=8000&&sourceRate!=16000)throw new IllegalArgumentException("ELD capture rate");
        this.sourceRate=sourceRate;sourceFrames=sourceRate*30/1000;
    }
    public int sourceRate(){return sourceRate;}
    public int sourceFrames(){return sourceFrames;}
    public synchronized long consumedFrames(){return consumed;}
    public synchronized long producedFrames(){return produced;}
    public synchronized byte[] convert(byte[] mono16le)throws IOException{
        if(closed)throw new IOException("ELD capture retired");
        if(mono16le==null||mono16le.length!=sourceFrames*2||
            consumed>Long.MAX_VALUE-sourceFrames||produced>Long.MAX_VALUE-OUTPUT_FRAMES)
            throw new IOException("ELD capture chunk bounds");
        byte[] result=new byte[OUTPUT_BYTES];
        if(sourceRate==16000){System.arraycopy(mono16le,0,result,0,result.length);}
        else{
            for(int i=0;i<sourceFrames;i++){
                int at=i*2,current=(short)((mono16le[at]&255)|((mono16le[at+1]&255)<<8));
                if(!started){previous=current;started=true;}
                // Exact 2:1 causal interpolation; the first output for each
                // input sample is the preceding sample, then their midpoint.
                int midpoint=(previous+current)/2;
                int out=i*4;result[out]=(byte)previous;result[out+1]=(byte)(previous>>>8);
                result[out+2]=(byte)midpoint;result[out+3]=(byte)(midpoint>>>8);
                previous=current;
            }
        }
        consumed+=sourceFrames;produced+=OUTPUT_FRAMES;return result;
    }
    public synchronized void close(){closed=true;previous=0;started=false;}
}
