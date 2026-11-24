/* SPDX-License-Identifier: GPL-3.0-only. Physical AudioTrack frames to media sample positions. */
package com.veloster.carplay.media;
import java.io.IOException;
import java.util.ArrayDeque;

/** Tracks only frames actually written, then observed consumed by the output device.
 * Samples are relative to the worker's media origin, not a network/NTP clock.
 * Gap concealment can carry media time; finite tail padding deliberately cannot.
 * Consumed spans are discarded, while adjacent continuous spans are coalesced.
 */
public final class AudioPlaybackTimeline {
    public static final int MAX_SPANS=8192;
    public static final class Position {
        public final long writtenFrames,playedFrames,nextSample;
        public final boolean sampleKnown,headSampleKnown;
        private Position(long written,long played,boolean known,long sample,boolean atHead){writtenFrames=written;playedFrames=played;sampleKnown=known;nextSample=sample;headSampleKnown=atHead;}
    }
    private static final class Span {
        final long start,sample;final boolean mapped;long end;
        Span(long start,long end,boolean mapped,long sample){this.start=start;this.end=end;this.mapped=mapped;this.sample=sample;}
    }
    private final ArrayDeque<Span> spans=new ArrayDeque<Span>();
    private long written,played,nextSample;private boolean known,atHead;
    public synchronized void append(long frames,long firstSample)throws IOException{append(frames,true,firstSample);}
    public synchronized void appendPadding(long frames)throws IOException{append(frames,false,0);}
    private void append(long frames,boolean mapped,long sample)throws IOException{
        if(frames<=0||frames>Long.MAX_VALUE-written||mapped&&(sample<0||frames>Long.MAX_VALUE-sample))throw new IOException("Audio timeline bounds");
        Span last=spans.peekLast();
        boolean joins=last!=null&&last.mapped==mapped&&(!mapped||last.sample+(last.end-last.start)==sample);
        if(!joins&&spans.size()>=MAX_SPANS)throw new IOException("Audio timeline span limit");
        if(joins)last.end+=frames;else spans.addLast(new Span(written,written+frames,mapped,sample));
        written+=frames;
    }
    /** Absolute unwrapped physical playback head, never a packet sequence counter. */
    public synchronized void advance(long consumedFrames)throws IOException{
        if(consumedFrames<played||consumedFrames>written)throw new IOException("Audio timeline head bounds");
        while(played<consumedFrames){
            Span span=spans.peekFirst();if(span==null||played<span.start||played>=span.end)throw new IOException("Audio timeline coverage");
            played=Math.min(consumedFrames,span.end);
            atHead=span.mapped;if(span.mapped){nextSample=span.sample+(played-span.start);known=true;}
            if(played==span.end)spans.removeFirst();
        }
    }
    /** nextSample is exclusive: the sample immediately after the last consumed mapped frame.
     * Before any such frame, sampleKnown is false even if buffers are already full. */
    public synchronized Position position(){return new Position(written,played,known,nextSample,atHead);}
    public synchronized int retainedSpans(){return spans.size();}
}
