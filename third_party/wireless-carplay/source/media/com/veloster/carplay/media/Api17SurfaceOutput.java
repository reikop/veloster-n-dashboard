/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** API16/17 synchronous codec API. Only called by VideoSink's decoder worker. */
public final class Api17SurfaceOutput implements VideoSink.ScheduledOutput {
    /** Release observation is not a hardware display timestamp. */
    public interface Observer {
        void outputReady(long ptsUs,long monotonicUs)throws IOException;
        void released(long ptsUs,long monotonicUs,boolean render)throws IOException;
    }
    private final Surface surface;private final Observer observer;
    public Api17SurfaceOutput(Surface surface){this(surface,null);}
    public Api17SurfaceOutput(Surface surface,Observer observer){if(surface==null)throw new NullPointerException();this.surface=surface;this.observer=observer;}
    /** Declared profile/level support, not proof of hardware throughput or correct vendor decoding. */
    public static String[] decoderNames(AvcConfig config){
        int profile;
        switch(config.profile){case 66:profile=1;break;case 77:profile=2;break;case 88:profile=4;break;case 100:profile=8;break;
            case 110:profile=16;break;case 122:profile=32;break;case 244:profile=64;break;default:return new String[0];}
        int[] levels={10,9,11,12,13,20,21,22,30,31,32,40,41,42,50,51};int level=0;
        if(config.level==11&&(config.constraints&16)!=0&&config.profile<=88)level=2;
        else for(int i=0;i<levels.length;i++)if(config.level==levels[i])level=1<<i;
        ArrayList<String> result=new ArrayList<String>();if(level==0)return new String[0];
        for(int i=0;i<MediaCodecList.getCodecCount();i++){
            MediaCodecInfo info=MediaCodecList.getCodecInfoAt(i);if(info.isEncoder())continue;
            for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc")){
                try{
                    for(MediaCodecInfo.CodecProfileLevel p:info.getCapabilitiesForType(type).profileLevels)
                        if(p.profile==profile&&p.level>=level){result.add(info.getName());break;}
                }catch(IllegalArgumentException ignored){}
            }
        }
        return result.toArray(new String[result.size()]);
    }
    public VideoSink.Decoder open(AvcConfig config)throws IOException{
        return openDecoder(config,null,false);
    }
    public VideoSink.Decoder open(AvcConfig config,VideoSink.RenderGate gate)throws IOException{
        if(gate==null)throw new NullPointerException();return openDecoder(config,gate,true);
    }
    private VideoSink.Decoder openDecoder(AvcConfig config,VideoSink.RenderGate gate,boolean timed)throws IOException{
        if(!surface.isValid())throw new IOException("Video Surface is invalid");
        RuntimeException last=null;
        for(String name:decoderNames(config)){
          MediaCodec codec=null;boolean started=false;
          try{
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",config.width,config.height);
            format.setByteBuffer("csd-0",ByteBuffer.wrap(config.sps()));
            format.setByteBuffer("csd-1",ByteBuffer.wrap(config.pps()));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,AvcConfig.MAX_FRAME);
            codec=MediaCodec.createByCodecName(name);codec.configure(format,surface,null,0);codec.start();started=true;
            return new Decoder(codec,codec.getInputBuffers(),surface,gate,timed,observer);
          }catch(RuntimeException e){release(codec,started);last=e;}
        }
        throw new IOException("No usable AVC decoder for profile "+config.profile+" level "+config.level,last);
    }
    private static void release(MediaCodec codec,boolean started){
        if(codec==null)return;
        try{if(started)codec.stop();}catch(RuntimeException ignored){}
        finally{try{codec.release();}catch(RuntimeException ignored){}}
    }
    private static final class Decoder implements VideoSink.ScheduledDecoder {
        private MediaCodec codec;private final ByteBuffer[] input;private final Surface surface;
        private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        private final VideoPresentation presentation;private final Observer observer;private long pendingPts;
        Decoder(MediaCodec c,ByteBuffer[] buffers,Surface s,final VideoSink.RenderGate gate,boolean timed,final Observer observer){
            codec=c;input=buffers;surface=s;this.observer=observer;
            presentation=new VideoPresentation(timed,new VideoSink.RenderGate(){public boolean current()throws IOException{
                return surface.isValid()&&(gate==null||gate.current());
            }},new VideoPresentation.Release(){public void release(int index,boolean render)throws IOException{
                codec.releaseOutputBuffer(index,render);if(observer!=null)observer.released(pendingPts,System.nanoTime()/1000,render);
            }});
        }
        public boolean presentationPending(){return presentation.waiting(System.nanoTime()/1000);}
        public boolean submit(byte[] data,long pts)throws IOException{
            int index=codec.dequeueInputBuffer(0);if(index<0)return false;
            ByteBuffer buffer=input[index];buffer.clear();
            if(data.length>buffer.remaining())throw new IOException("AVC input exceeds codec buffer");
            buffer.put(data);codec.queueInputBuffer(index,0,data.length,pts,0);return true;
        }
        public int drain()throws IOException{
            int count=0;
            for(int i=0;i<32;i++){
                if(presentation.pending()){
                    count+=presentation.drain(System.nanoTime()/1000);if(presentation.pending())break;
                }
                int index=codec.dequeueOutputBuffer(info,0);
                if(index>=0){
                    pendingPts=info.presentationTimeUs;presentation.offer(index,pendingPts,(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0);
                    if(observer!=null&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0)observer.outputReady(pendingPts,System.nanoTime()/1000);
                }else if(index==MediaCodec.INFO_TRY_AGAIN_LATER)break;
                else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat format=codec.getOutputFormat();
                    int w=format.getInteger(MediaFormat.KEY_WIDTH),h=format.getInteger(MediaFormat.KEY_HEIGHT);
                    if(w<16||h<16||w>1920||h>1088)throw new IOException("AVC output dimensions");
                }else if(index!=MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)throw new IOException("AVC output status");
            }
            return count;
        }
        public void close(){MediaCodec current=codec;
            try{presentation.cancel();}catch(IOException ignored){}catch(RuntimeException ignored){}
            finally{codec=null;release(current,true);}
        }
    }
}
