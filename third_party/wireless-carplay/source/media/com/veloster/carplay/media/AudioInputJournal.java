/* SPDX-License-Identifier: GPL-3.0-only. Bounded retained input for selective audio reset. */
package com.veloster.carplay.media;
import java.io.*;
import java.util.*;

/** Owned by the audio worker (or its monitor), never by the UDP receiver.
 * Successfully submitted input remains available until device consumption or an
 * explicit discard retires it. AAC retains two retired AUs as decoder history. This is not a
 * decoder reset, retransmission buffer or RTSP FLUSH handler.
 */
public final class AudioInputJournal implements Closeable {
    public static final int MAX_PACKETS=128,MAX_BYTES=512*1024;
    private static final class Entry {
        final long sample,end;long playFrom;final int frames;final byte[] bytes;
        Entry(long sample,int frames,byte[] payload){this.sample=sample;playFrom=sample;this.frames=frames;end=sample+frames;bytes=payload.clone();}
        void erase(){Arrays.fill(bytes,(byte)0);}
    }
    public static final class State {
        public final int packets,bytes,historyPackets,unplayedPackets;
        public final Long consumedThrough,lastPlayedTimestamp,discardedBefore;
        private State(int packets,int bytes,int history,Long consumed,Long last,Long discarded){
            this.packets=packets;this.bytes=bytes;historyPackets=history;unplayedPackets=packets-history;consumedThrough=consumed;lastPlayedTimestamp=last;discardedBefore=discarded;
        }
    }
    public static final class Packet {
        public final long sample;public final int frames,discardFrames;public final byte[] bytes;
        private Packet(Entry entry,long cutoff){
            sample=entry.sample;frames=entry.frames;bytes=entry.bytes.clone();
            discardFrames=cutoff<=sample?0:cutoff>=entry.end?frames:(int)(cutoff-sample);
        }
    }
    /** Caller owns these copies, including warm-up packets; close erases every payload.
     * Decode complete AUs and discard discardFrames of their decoded samples. Never
     * slice compressed bytes or replay samples consumed before the checkpoint. */
    public static final class Replay implements Closeable {
        public final long cutoff;public final List<Packet> packets;public final Long lastPlayedTimestamp;
        private Replay(long cutoff,List<Packet> packets,Long last){this.cutoff=cutoff;this.packets=Collections.unmodifiableList(packets);lastPlayedTimestamp=last;}
        public void close(){for(Packet p:packets)Arrays.fill(p.bytes,(byte)0);}
    }
    private final int historyLimit;private final LinkedList<Entry> entries=new LinkedList<Entry>();
    private int bytes;private boolean closed,submitted;private long lastEnd;private Long consumed,lastPlayed,discarded;
    public AudioInputJournal(int historyPackets){if(historyPackets<0||historyPackets>2)throw new IllegalArgumentException("Audio history bound");historyLimit=historyPackets;}
    public synchronized boolean canRetain(int size){return !closed&&size>0&&size<=65471&&entries.size()<MAX_PACKETS&&bytes<=MAX_BYTES-size;}
    public synchronized void retain(long sample,int frames,byte[] payload)throws IOException{
        if(closed||frames<=0||frames>65536||sample>Long.MAX_VALUE-frames||submitted&&sample<lastEnd||payload==null||!canRetain(payload.length))
            throw new IOException("Audio retained input bounds/order");
        Entry entry=new Entry(sample,frames,payload);if(discarded!=null)entry.playFrom=Math.max(sample,discarded);
        entries.add(entry);bytes+=payload.length;lastEnd=entry.end;submitted=true;prune();
    }
    /** Exclusive sample boundary from actual device-head mapping, not queued/decoded counts. */
    public synchronized void consumedThrough(long boundary)throws IOException{
        if(closed||consumed!=null&&boundary<consumed)throw new IOException("Audio consumption moved backwards");
        consumed=boundary;
        for(Entry e:entries){
            if(e.playFrom<e.end&&e.playFrom<boundary)lastPlayed=e.sample;
            if(e.end>boundary)break;
        }
        prune();
    }
    /** Irreversible discard is not playback. Keep bounded AAC decoder history,
     * without advancing the consumed boundary or inventing a last-played packet. */
    public synchronized void discardBefore(long boundary)throws IOException{
        if(closed||discarded!=null&&boundary<discarded)throw new IOException("Audio discard moved backwards");
        discarded=boundary;for(Entry e:entries)e.playFrom=Math.max(e.playFrom,boundary);prune();
    }
    private Long retiredThrough(){return consumed==null?discarded:discarded==null?consumed:Long.valueOf(Math.max(consumed,discarded));}
    private void prune(){
        Long boundary=retiredThrough();int history=0;
        if(boundary!=null)for(Entry e:entries){if(e.end<=boundary)history++;else break;}
        while(history>historyLimit){Entry e=entries.removeFirst();bytes-=e.bytes.length;e.erase();history--;}
    }
    public synchronized State state(){
        Long boundary=retiredThrough();int history=0;if(boundary!=null)for(Entry e:entries){if(e.end<=boundary)history++;else break;}
        return new State(entries.size(),bytes,history,consumed,lastPlayed,discarded);
    }
    /** Preserve everything intersecting/following the cutoff, plus bounded decoder history.
     * This does not mutate the live journal. The worker must first take a real paused
     * checkpoint and combine these packets with its not-yet-submitted queue/item. */
    public synchronized Replay replayFrom(long requestedCutoff)throws IOException{
        if(closed)throw new IOException("Audio journal closed");
        Long boundary=retiredThrough();long cutoff=boundary==null?requestedCutoff:Math.max(requestedCutoff,boundary);
        int first=0;for(Entry e:entries){if(e.end<=cutoff)first++;else break;}
        first=Math.max(0,first-historyLimit);List<Packet> packets=new ArrayList<Packet>();int index=0;
        for(Entry e:entries)if(index++>=first)packets.add(new Packet(e,cutoff));
        return new Replay(cutoff,packets,lastPlayed);
    }
    public synchronized void close(){if(closed)return;closed=true;for(Entry e:entries)e.erase();entries.clear();bytes=0;}
}
