/* SPDX-License-Identifier: GPL-3.0-only
 * Java 7 link implementation, based on the protocol and state-machine behavior
 * in DiPlay-5.1 (Jheenz), commit 5776c38. See TRANSPORT-NOTICE.md.
 * Modified: bounded window/ACK validation, session negotiation checks,
 * per-packet deadlines, deterministic clock, and independent stream adapter.
 */
package com.veloster.carplay;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

/** Single-owner iAP2 reliable link. All time values are monotonic milliseconds.
 * This transports bytes; it does not authenticate, advertise CarPlay, or start video.
 */
public final class Iap2Link {
    public enum State { IDLE, DETECTING, NEGOTIATING, OPEN, CLOSED }
    /** Tunnel mode is only for an already authenticated AirPlay type130 byte stream. */
    public enum Mode { BLUETOOTH_BOOTSTRAP, WIFI_TUNNEL }
    public static final int CONTROL_SESSION = 10;
    public static final String SERVICE_UUID = "00000000-deca-fade-deca-deafdecacafe";
    private static final byte[] MARKER = {(byte)255,85,2,0,(byte)238,16};
    private static final int SYN=128, ACK=64, EAK=32, RST=16;
    private static final int MAX_FRAME=65535, LOCAL_LENGTH=4096, LOCAL_WINDOW=4;
    private static final int MAX_QUEUE=64, MAX_EVENTS=256, MAX_OUTPUT=1048576;
    private static final long NEVER=Long.MAX_VALUE;

    public static final class Event {
        public final int session;
        public final byte[] payload;
        private Event(int id, byte[] data) {session=id;payload=data;}
    }
    private static final class Pending {
        int sequence, retries;
        final int session;
        final byte[] bytes;
        long deadline;
        Pending(int id,byte[] value) {session=id;bytes=value;}
    }
    private State state=State.IDLE;
    private final Mode mode;
    private String failure;
    private final byte[] receive=new byte[MAX_FRAME];
    private int used;
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private final ArrayDeque<Pending> queued=new ArrayDeque<Pending>();
    private final ArrayDeque<Pending> outstanding=new ArrayDeque<Pending>();
    private final ArrayList<Pending> reordered=new ArrayList<Pending>();
    private final ArrayDeque<Event> events=new ArrayDeque<Event>();
    private int sent=99, received, lastAck=99, lastReceiveAck;
    private int peerWindow=LOCAL_WINDOW, peerLength=LOCAL_LENGTH;
    private int retryMillis=4000, ackMillis=100, retryLimit=4, ackLimit=3;
    private boolean gotSync;
    private byte[] peerSync;
    private int peerSyncSequence;
    private long now, markerDue=NEVER, syncDue=NEVER, ackDue=NEVER;

    public Iap2Link() { this(Mode.BLUETOOTH_BOOTSTRAP); }
    public Iap2Link(Mode mode) {
        if(mode==null)throw new NullPointerException("Link mode");
        this.mode=mode;
    }

    public State state() {return state;}
    public String failure() {return failure;}
    public int outstandingCount() {return outstanding.size();}
    public int queuedCount() {return queued.size();}
    public int maximumPayload() {return Math.max(1,peerLength-10);}
    public Event poll() {return events.poll();}
    public byte[] drainOutput() {byte[] value=output.toByteArray();output.reset();return value;}
    public boolean writable() {return state==State.OPEN && queued.size()<MAX_QUEUE;}
    private void clock(long value) {
        if(value<now)throw new IllegalArgumentException("Monotonic clock moved backwards");
        now=value;
    }
    public void start(long millis) {
        clock(millis);
        if(state!=State.IDLE)throw new IllegalStateException("Link already started");
        state=State.DETECTING;emit(MARKER);markerDue=now+1000;
        if(mode==Mode.WIFI_TUNNEL) {
            state=State.NEGOTIATING;markerDue=NEVER;synchronize();syncDue=now+500;
        }
    }
    public void close() {terminate(null);}
    private void terminate(String why) {
        if(state==State.CLOSED)return;
        state=State.CLOSED;failure=why;markerDue=syncDue=ackDue=NEVER;
        output.reset();events.clear();queued.clear();outstanding.clear();reordered.clear();
        Arrays.fill(receive,(byte)0);used=0;
    }
    private void emit(byte[] bytes) {
        if(state==State.CLOSED)return;
        if(bytes.length>MAX_OUTPUT-output.size()){terminate("Undrained link output limit");return;}
        output.write(bytes,0,bytes.length);
    }
    public long nextDeadline() {
        long due=Math.min(markerDue,Math.min(syncDue,ackDue));
        for(Pending p:outstanding)due=Math.min(due,p.deadline);
        return due;
    }
    public void tick(long millis) {
        clock(millis);
        if(state==State.CLOSED)return;
        if(markerDue<=now){emit(MARKER);markerDue=state==State.DETECTING?now+1000:NEVER;}
        if(syncDue<=now){synchronize();syncDue=state==State.NEGOTIATING?now+500:NEVER;}
        if(ackDue<=now){ackDue=NEVER;acknowledge();}
        for(Pending p:new ArrayList<Pending>(outstanding)) {
            if(state==State.CLOSED)break;
            if(p.deadline<=now)retransmit(p);
        }
    }
    public void send(int session, byte[] bytes, long millis) {
        clock(millis);
        if(state!=State.OPEN)throw new IllegalStateException("Link not open");
        if(session!=CONTROL_SESSION)throw new IllegalArgumentException("Session not negotiated");
        if(bytes==null || bytes.length==0 || bytes.length>maximumPayload())
            throw new IllegalArgumentException("Payload exceeds negotiated link frame");
        if(queued.size()>=MAX_QUEUE)throw new IllegalStateException("Link queue full");
        queued.add(new Pending(session,bytes.clone()));flush();
    }
    private void flush() {
        while(state==State.OPEN && !queued.isEmpty() && outstanding.size()<peerWindow) {
            Pending p=queued.remove();sent=(sent+1)&255;p.sequence=sent;
            p.deadline=now+retryMillis;packet(ACK,p.sequence,p.session,p.bytes);
            if(retryLimit>0)outstanding.add(p);else lastAck=sent;
            ackDue=NEVER;lastReceiveAck=received;
        }
    }
    private void retransmit(Pending p) {
        p.retries++;
        if(p.retries>=retryLimit){terminate("Peer did not acknowledge packet");return;}
        p.deadline=now+retryMillis;packet(ACK,p.sequence,p.session,p.bytes);
        ackDue=NEVER;lastReceiveAck=received;
    }
    public void feed(byte[] bytes, int offset, int count, long millis) {
        clock(millis);
        if(offset<0 || count<0 || count>bytes.length-offset)throw new IndexOutOfBoundsException();
        if(state==State.IDLE)throw new IllegalStateException("Link not started");
        int end=offset+count;
        while(offset<end && state!=State.CLOSED) {
            int size=Math.min(end-offset,receive.length-used);
            if(size==0){terminate("Inbound frame capacity exceeded");break;}
            System.arraycopy(bytes,offset,receive,used,size);used+=size;offset+=size;
            while(parse() && state!=State.CLOSED) { /* drain complete frames */ }
        }
    }
    public void feed(byte[] bytes,long millis) {feed(bytes,0,bytes.length,millis);}
    private void discard(int size) {
        System.arraycopy(receive,size,receive,0,used-size);
        Arrays.fill(receive,used-size,used,(byte)0);used-=size;
    }
    private boolean parse() {
        if(state==State.DETECTING) {
            if(used<MARKER.length)return false;
            for(int i=0;i<MARKER.length;i++)if(receive[i]!=MARKER[i]) {
                terminate("iAP2 detection marker missing");return false;
            }
            discard(MARKER.length);state=State.NEGOTIATING;markerDue=NEVER;
            synchronize();syncDue=now+500;return true;
        }
        if(used<2)return false;
        if(u(receive[0])!=255 || u(receive[1])!=90){discard(1);return true;}
        if(used<9)return false;
        int length=u16(receive,2);
        if(length<9 || !valid(receive,0,9)){discard(1);return true;}
        if(length>localLength()){terminate("Peer exceeded advertised frame size");return false;}
        if(used<length)return false;
        int control=u(receive[4]),seq=u(receive[5]),ack=u(receive[6]),session=u(receive[7]);
        byte[] payload=null;
        if(length>9) {
            if(!valid(receive,9,length-9)){discard(length);return true;}
            payload=Arrays.copyOfRange(receive,9,length-1);
        }
        discard(length);process(control,seq,ack,session,payload);return true;
    }
    private void process(int control,int seq,int ack,int session,byte[] payload) {
        if((control&RST)!=0){terminate("Peer reset");return;}
        if((control&~(SYN|ACK|EAK|RST))!=0)return;
        if((control&SYN)!=0) {
            if(!synchronization(payload,seq))return;
        }
        if((control&ACK)!=0)ack(ack);
        if(state!=State.OPEN)return;
        if((control&EAK)!=0 && payload!=null) {
            for(Pending p:new ArrayList<Pending>(outstanding)) {
                for(byte b:payload)if(u(b)==p.sequence){retransmit(p);break;}
                if(state==State.CLOSED)return;
            }
        }
        if((control&~ACK)==0 && payload!=null && payload.length>0) {
            if(session!=CONTROL_SESSION){terminate("Unnegotiated data session");return;}
            data(seq,session,payload);
        }
    }
    private boolean synchronization(byte[] bytes,int sequence) {
        if(state==State.OPEN) {
            if(sequence==peerSyncSequence && Arrays.equals(bytes,peerSync)) {acknowledge();return true;}
            terminate("Peer changed synchronization during active link");return false;
        }
        if(state!=State.NEGOTIATING || bytes==null || bytes.length<13 ||
           (bytes.length-10)%3!=0 || u(bytes[0])!=1) {
            terminate("Invalid peer synchronization");return false;
        }
        int window=u(bytes[1]),length=u16(bytes,2),retry=u16(bytes,4),ackTime=u16(bytes,6);
        int retries=u(bytes[8]),acks=u(bytes[9]);boolean control=false;
        for(int i=10;i<bytes.length;i+=3) {
            if(u(bytes[i])==CONTROL_SESSION) {
                if(control || u(bytes[i+1])!=0 || u(bytes[i+2])<1 || u(bytes[i+2])>2) {
                    terminate("Invalid control session negotiation");return false;
                }
                control=true;
            }
        }
        if(!control || window<1 || length<=10 || (retries>0 && retry<1)) {
            terminate("Unusable peer synchronization");return false;
        }
        if(gotSync && (sequence!=peerSyncSequence || !Arrays.equals(bytes,peerSync))) {
            terminate("Inconsistent peer synchronization");return false;
        }
        peerWindow=Math.min(window,LOCAL_WINDOW);peerLength=length;
        retryMillis=retry;ackMillis=ackTime;retryLimit=retries;ackLimit=acks;
        peerSync=bytes.clone();peerSyncSequence=sequence;gotSync=true;
        received=lastReceiveAck=sequence;acknowledge();return true;
    }
    private void ack(int sequence) {
        if(state==State.NEGOTIATING) {
            // Do not accept an ACK for a packet we never sent.
            if(!gotSync || sequence!=sent)return;
            state=State.OPEN;syncDue=NEVER;lastAck=sequence;flush();return;
        }
        if(state!=State.OPEN || outstanding.isEmpty())return;
        int distance=distance(sequence,lastAck);
        if(distance==0 || distance>outstanding.size())return; // stale or impossible future ACK
        for(int i=0;i<distance;i++)outstanding.remove();lastAck=sequence;flush();
    }
    private void data(int sequence,int session,byte[] bytes) {
        int ahead=distance(sequence,received);
        if(ahead==0 || ahead>LOCAL_WINDOW){acknowledge();return;}
        for(Pending p:reordered)if(p.sequence==sequence){acknowledge();return;}
        Pending p=new Pending(session,bytes);p.sequence=sequence;reordered.add(p);
        boolean delivered=false;
        while(true) {
            Pending next=null;
            for(Pending item:reordered)if(distance(item.sequence,received)==1){next=item;break;}
            if(next==null)break;
            reordered.remove(next);received=next.sequence;
            if(events.size()>=MAX_EVENTS){terminate("Undrained event limit");return;}
            events.add(new Event(next.session,next.bytes));delivered=true;
        }
        if(!reordered.isEmpty()) {
            ByteArrayOutputStream missing=new ByteArrayOutputStream();int farthest=0;
            for(Pending item:reordered)farthest=Math.max(farthest,distance(item.sequence,received));
            for(int step=1;step<farthest;step++) {
                int wanted=(received+step)&255;boolean found=false;
                for(Pending item:reordered)if(item.sequence==wanted)found=true;
                if(!found)missing.write(wanted);
            }
            if(missing.size()>0){packet(EAK,sent,0,missing.toByteArray());ackDue=NEVER;}
        }
        if(!delivered)return;
        if(ackLimit==0)return;
        if(distance(received,lastReceiveAck)>=Math.min(LOCAL_WINDOW,ackLimit))acknowledge();
        else if(ackDue==NEVER)ackDue=now+ackMillis;
    }
    private void synchronize() {
        byte[] sync={1,LOCAL_WINDOW,16,0,15,(byte)160,0,100,4,3,CONTROL_SESSION,0,2};
        if(mode==Mode.WIFI_TUNNEL) {
            sync[2]=(byte)(MAX_FRAME>>>8);sync[3]=(byte)MAX_FRAME;
            Arrays.fill(sync,4,10,(byte)0);
        }
        packet(SYN,sent,0,sync);
    }
    private int localLength() {return mode==Mode.WIFI_TUNNEL?MAX_FRAME:LOCAL_LENGTH;}
    private void acknowledge() {
        if(state==State.CLOSED)return;
        packet(ACK,sent,0,null);lastReceiveAck=received;ackDue=NEVER;
    }
    private void packet(int control,int sequence,int session,byte[] payload) {
        int length=9+(payload==null?0:payload.length+1);
        byte[] frame=new byte[length];frame[0]=(byte)255;frame[1]=90;
        frame[2]=(byte)(length>>>8);frame[3]=(byte)length;frame[4]=(byte)control;
        frame[5]=(byte)sequence;frame[6]=(byte)received;frame[7]=(byte)session;
        frame[8]=checksum(frame,0,8);
        if(payload!=null){System.arraycopy(payload,0,frame,9,payload.length);frame[length-1]=checksum(payload,0,payload.length);}
        emit(frame);
    }
    private static int distance(int current,int previous) {return (current-previous)&255;}
    private static int u(byte b) {return b&255;}
    private static int u16(byte[] b,int at) {return (u(b[at])<<8)|u(b[at+1]);}
    private static boolean valid(byte[] b,int at,int size) {return checksum(b,at,size)==0;}
    private static byte checksum(byte[] b,int at,int size) {
        int sum=0;for(int i=0;i<size;i++)sum+=u(b[at+i]);return (byte)-sum;
    }
}
