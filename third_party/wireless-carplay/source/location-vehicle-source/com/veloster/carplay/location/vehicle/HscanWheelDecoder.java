/* SPDX-License-Identifier: GPL-3.0-only. Captured250902 receive framing only. */
package com.veloster.carplay.location.vehicle;

/** No CAN identifier guessing and no output/handshake path. Timestamps describe
 * first-byte reception by this process, not the MICOM/sensor measurement clock. */
final class HscanWheelDecoder {
    interface Sink {void wheel(byte[] data,long first,long completed);void invalid();}
    private static final int[] HEADER={255,11,142,1,139,199,1,0};
    private final byte[] frame=new byte[264];private final long[] times=new long[264];
    private final Sink sink;private int used;
    HscanWheelDecoder(Sink sink){this.sink=sink;}
    void reset(){used=0;}
    void accept(byte[] data,int offset,int length,long receivedAt){
        for(int i=offset;i<offset+length;i++){
            frame[used]=data[i];times[used++]=receivedAt;
            while(used>0&&!prefix()){sink.invalid();discard();}
            if(used!=264)continue;
            int sum=0;for(byte value:frame)sum^=value&255;
            if(sum==0){batch(times[0],receivedAt);used=0;}
            else{sink.invalid();discard();while(used>0&&!prefix())discard();}
        }
    }
    private boolean prefix(){for(int i=0;i<Math.min(used,HEADER.length);i++)if((frame[i]&255)!=HEADER[i])return false;return true;}
    private void discard(){used--;System.arraycopy(frame,1,frame,0,used);System.arraycopy(times,1,times,0,used);}
    private void batch(long first,long completed){
        if((frame[8]&255)!=0xc7){sink.invalid();return;}
        int count=(frame[9]&255)|((frame[10]&255)<<8),at=11;
        if(count>84){sink.invalid();return;}
        // Validate the entire batch before a single wheel sample is published.
        for(int i=0;i<count;i++){
            if(at+2>263){sink.invalid();return;}
            int size=frame[at+1]&255;
            if(size<1||size>8||at+2+size>263){sink.invalid();return;}
            if((frame[at]&255)==0x6e&&size!=8){sink.invalid();return;}
            at+=2+size;
        }
        for(int i=at;i<263;i++)if(frame[i]!=0){sink.invalid();return;}
        at=11;
        for(int i=0;i<count;i++){
            int function=frame[at++]&255,size=frame[at++]&255;
            if(function==0x6e){
                if(size!=8){sink.invalid();return;}
                byte[] value=new byte[8];System.arraycopy(frame,at,value,0,8);sink.wheel(value,first,completed);
            }
            at+=size;
        }
    }
}
