/* SPDX-License-Identifier: GPL-3.0-only. Separate unadvertised capture adapter. */
package com.veloster.carplay.media;
import android.content.Context;
import com.veloster.carplay.tunnel.AudioInputStream;
import java.io.IOException;
import java.util.Arrays;

/** One physical mono 8/16 kHz AudioRecord and exact 30 ms 16 kHz/480 output.
 * The capture worker owns prepare/start/read/close. The existing cancellation
 * worker may call stop() while read blocks; physical release remains with the
 * capture worker and its MicrophoneCaptureLease reservation.
 */
public final class Eld16kMicrophoneSource implements AudioInputStream.PreparedSource {
    private final AudioInputStream.PreparedSource physical;
    private final Eld16kPcmSource converter;
    private final byte[] sourceFrame;private int filled;
    private volatile boolean stopped;private boolean closed;
    public Eld16kMicrophoneSource(Context context,int sourceRate,Api17MicrophoneInput.Purpose purpose)throws IOException{
        this(new Api17MicrophoneInput(context,sourceRate,sourceRate*30/1000,purpose),sourceRate);
    }
    /** Fixture constructor: caller supplies a source with the selected physical rate. */
    Eld16kMicrophoneSource(AudioInputStream.PreparedSource source,int sourceRate){
        if(source==null)throw new NullPointerException();
        physical=source;converter=new Eld16kPcmSource(sourceRate);
        sourceFrame=new byte[converter.sourceFrames()*2];
    }
    public void prepare()throws IOException{if(closed||stopped)throw new IOException("ELD capture retired");physical.prepare();}
    public void start()throws IOException{if(closed||stopped)throw new IOException("ELD capture retired");physical.start();}
    public int read(byte[] target,int offset,int count)throws IOException{
        if(closed||stopped)throw new IOException("ELD capture retired");
        if(target==null||offset<0||count<Eld16kPcmSource.OUTPUT_BYTES||
            offset>target.length-Eld16kPcmSource.OUTPUT_BYTES)throw new IOException("ELD output buffer bounds");
        while(filled<sourceFrame.length){
            int n=physical.read(sourceFrame,filled,sourceFrame.length-filled);
            if(stopped)throw new IOException("ELD capture cancelled");
            if(n<0||n>sourceFrame.length-filled)throw new IOException("ELD physical read failed");
            if(n==0)return 0;
            filled+=n;
        }
        byte[] converted=null;
        try{converted=converter.convert(sourceFrame);
            System.arraycopy(converted,0,target,offset,converted.length);return converted.length;
        }finally{Arrays.fill(sourceFrame,(byte)0);filled=0;
            if(converted!=null)Arrays.fill(converted,(byte)0);}
    }
    public void stop(){stopped=true;physical.stop();}
    public void close()throws IOException{
        if(closed)return;closed=true;stopped=true;
        try{physical.close();}finally{converter.close();Arrays.fill(sourceFrame,(byte)0);filled=0;}
    }
}
