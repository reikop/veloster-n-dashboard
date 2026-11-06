/* SPDX-License-Identifier: GPL-3.0-only. Separate unadvertised codec backend. */
package com.veloster.carplay.media;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** API17 system FDK AAC-ELD/16k/mono/480 encoder. Owns one native handle on
 * one capture worker. Construction is an actual runtime ABI/profile probe;
 * absent or incompatible system libraries cause an IOException. */
public final class SystemEld16kEncoder implements Closeable {
    static {System.loadLibrary("vn-carplay-eld16");}
    private static native long nativeOpen(byte[] asc,int[] ascLength)throws IOException;
    private static native byte[] nativeEncode(long handle,byte[] pcm)throws IOException;
    private static native void nativeClose(long handle);
    private long handle;private byte[] asc;
    public SystemEld16kEncoder()throws IOException{
        byte[] output=new byte[64];int[] count=new int[1];
        try{handle=nativeOpen(output,count);
            if(handle==0||count[0]<4||count[0]>64){
                if(handle!=0){nativeClose(handle);handle=0;}
                throw new IOException("ELD codec config unavailable");
            }
            asc=Arrays.copyOf(output,count[0]);
        }finally{Arrays.fill(output,(byte)0);}
    }
    public synchronized byte[] audioSpecificConfig()throws IOException{
        if(handle==0)throw new IOException("ELD encoder retired");return asc.clone();
    }
    /** One exact 30 ms PCM16LE chunk. null means encoder is still priming;
     * no RTP timestamp or nonce is advanced until a raw AU is emitted. */
    public synchronized byte[] encode(byte[] pcm16le)throws IOException{
        if(handle==0)throw new IOException("ELD encoder retired");
        if(pcm16le==null||pcm16le.length!=960)throw new IOException("ELD input chunk bounds");
        return nativeEncode(handle,pcm16le);
    }
    public synchronized void close(){
        if(handle==0)return;long owned=handle;handle=0;
        nativeClose(owned);Arrays.fill(asc,(byte)0);asc=null;
    }
}
