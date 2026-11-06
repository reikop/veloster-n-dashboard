/* SPDX-License-Identifier: GPL-3.0-only. One decoder-owned pending output buffer. */
package com.veloster.carplay.media;
import java.io.IOException;

/** Nonblocking API17 presentation scheduling. A released buffer is owned by the
 * backend, never by this queue again. Surface composition/panel latency is separate. */
public final class VideoPresentation {
    public interface Release {void release(int index,boolean render)throws IOException;}
    private final VideoSink.RenderGate gate;private final Release backend;private final boolean timed;
    private int index=-1;private long pts;private boolean renderable;
    public VideoPresentation(boolean timed,VideoSink.RenderGate gate,Release backend){
        if(gate==null||backend==null)throw new NullPointerException();this.timed=timed;this.gate=gate;this.backend=backend;
    }
    public boolean pending(){return index>=0;}
    public boolean waiting(long nowUs){return pending()&&timed&&renderable&&pts>nowUs;}
    public void offer(int buffer,long ptsUs,boolean display)throws IOException{
        if(buffer<0||pending())throw new IOException("Video output ownership");index=buffer;pts=ptsUs;renderable=display;
    }
    /** Returns 1 only for a buffer actually released for rendering. */
    public int drain(long nowUs)throws IOException{
        if(!pending())return 0;
        boolean display=renderable&&gate.current();
        if(display&&timed&&pts>nowUs){
            if(pts-nowUs>10000000L)throw new IOException("Video presentation horizon");return 0;
        }
        int owned=index;index=-1;backend.release(owned,display);return display?1:0;
    }
    public void cancel()throws IOException{if(pending()){int owned=index;index=-1;backend.release(owned,false);}}
}
