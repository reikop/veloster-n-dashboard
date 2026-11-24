/* SPDX-License-Identifier: GPL-3.0-only. See media/NOTICE.md. */
package com.veloster.carplay.media;

import java.io.Closeable;
import java.io.IOException;

/** Main/alternate screen lifecycle shared by the view and authenticated SETUP workers.
 * Output replacement keeps the stream alive. Explicit close retires the verified parent.
 * Listener callbacks run on control/codec workers and must return promptly. */
public final class ScreenOutputs implements VideoScreenFactory.Screens,Closeable {
    public interface Listener {
        void keyframeNeeded(int type,VideoSink sink);
        void stopped(int type,VideoSink sink,IOException reason);
    }
    private final boolean alternate;
    private final Listener listener;
    private final VideoSink[] sinks=new VideoSink[2];
    private final VideoSink.Output[] outputs=new VideoSink.Output[2];
    private boolean closed;
    public ScreenOutputs(boolean alternate,Listener listener){
        if(listener==null)throw new NullPointerException();this.alternate=alternate;this.listener=listener;
    }
    private int index(int type){if(type==110)return 0;if(type==111&&alternate)return 1;throw new IllegalArgumentException("Unsupported screen slot");}
    public synchronized boolean supports(int type){return !closed&&(type==110||type==111&&alternate);}
    public synchronized void setOutput(int type,VideoSink.Output output){
        int i=index(type);if(closed)return;outputs[i]=output;if(sinks[i]!=null)sinks[i].setOutput(output);
    }
    public synchronized VideoSink current(int type){return sinks[index(type)];}
    public synchronized void opened(int type,VideoSink sink)throws IOException{
        if(!supports(type))throw new IOException("Screen outputs unavailable");int i=index(type);
        if(sinks[i]!=null)throw new IOException("Screen slot still owned");
        sinks[i]=sink;sink.setOutput(outputs[i]);
    }
    public synchronized void keyframeNeeded(int type,VideoSink sink){
        // The callback and ownership check are serialized with removal/replacement.
        if(!closed&&sinks[index(type)]==sink)listener.keyframeNeeded(type,sink);
    }
    public void closed(int type,VideoSink sink,IOException reason){
        synchronized(this){int i=index(type);if(sinks[i]==sink)sinks[i]=null;}
        listener.stopped(type,sink,reason);
    }
    public synchronized void close(){
        if(closed)return;closed=true;
        for(int i=0;i<sinks.length;i++){outputs[i]=null;if(sinks[i]!=null)sinks[i].close();}
    }
}
