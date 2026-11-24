/* SPDX-License-Identifier: GPL-3.0-only. PairVerify responder based on DiPlay 5776c38. */
package com.veloster.carplay.pairing;
import com.veloster.carplay.RfcommConnector;
import com.veloster.carplay.tunnel.IapDataTunnel;
import com.veloster.carplay.tunnel.ScreenDataStream;
import com.veloster.carplay.tunnel.AudioDataStream;
import com.veloster.carplay.tunnel.AudioPacketDecoder;
import com.veloster.carplay.tunnel.AudioInputStream;
import com.veloster.carplay.tunnel.MicrophoneCaptureLease;
import com.veloster.carplay.tunnel.AudioWireFormat;
import com.veloster.carplay.tunnel.AudioSyncPacket;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.math.BigInteger;

/** One control connection, one handshake, no trust-on-first-use or downgrade.
 * The HTTP owner must call responseSent only after each cleartext response is flushed. */
public final class PairVerify implements Closeable {
    public static final class Context {
        public final String owner,apSession,selectedBluetooth,interfaceName;
        public final long generation;
        public final InetAddress localAddress,remoteAddress;
        public Context(String owner,String apSession,long generation,String selectedBluetooth,
                       String interfaceName,InetAddress localAddress,InetAddress remoteAddress){
            if(owner==null||!owner.matches("[0-9a-f]{32}")||apSession==null||!apSession.matches("[0-9a-f]{32}")
                ||owner.equals(apSession)||generation<1)throw new IllegalArgumentException("PairVerify owner");
            // Reuse address/interface structural validation; this does not claim a verified identity.
            new IapDataTunnel.Binding(owner,generation,"validation",interfaceName,localAddress,remoteAddress);
            this.owner=owner;this.apSession=apSession;this.generation=generation;
            this.selectedBluetooth=RfcommConnector.normalizeAddress(selectedBluetooth);this.interfaceName=interfaceName;
            this.localAddress=localAddress;this.remoteAddress=remoteAddress;
        }
    }
    /** Actual coordinator/AP/consent and control-socket lifetime must back this nonblocking check. */
    public interface OwnerLease { void checkCurrent(Context context)throws IOException; }
    /** Optional transport-continuity policy, enabled only after authenticated M3
     * and confirmed M4 flush. Never call this for mere association or enrollment. */
    public interface VerifiedOwnerLease extends OwnerLease { void verificationComplete(Context context)throws IOException; }
    public interface PhoneIdentityLease extends VerifiedOwnerLease {
        boolean mediaAllowed(Context context)throws IOException;
        void transportIdentifiers(Context context,String observedBluetooth,String observedUsb)throws IOException;
    }
    public static final class Reply {
        private final byte[] body;
        private Reply(byte[] body){this.body=body;}
        public byte[] body(){return body.clone();}
    }
    private enum State { NEW, M2_UNSENT, WAIT_M3, M4_UNSENT, VERIFIED, CLOSED }
    private final PairingStore store;
    private final Context context;
    private final OwnerLease owner;
    private final long deadline=System.nanoTime()+30000000000L;
    private State state=State.NEW;
    private Reply expected;
    private byte[] ownPublic,peerPublic,shared,encryptionKey;
    private VerifiedSession verified;
    public PairVerify(PairingStore store,Context context,OwnerLease owner)throws IOException{
        if(store==null||context==null||owner==null)throw new NullPointerException();
        this.store=store;this.context=context;this.owner=owner;owner.checkCurrent(context);store.identityIdentifier();
    }
    private void check()throws IOException{
        if(state==State.CLOSED||state==State.VERIFIED||System.nanoTime()-deadline>=0)throw new IOException("PairVerify not accepting messages");
        owner.checkCurrent(context);
    }
    public synchronized Reply handle(byte[] request)throws IOException{
        Map<Integer,byte[]> fields=null;
        try{
            check();fields=PairingTlv.decode(request);
            int message=PairingTlv.required(fields,6,1,1)[0]&255;
            if(message==1&&state==State.NEW)return m2(fields);
            if(message==3&&state==State.WAIT_M3)return m4(fields);
            throw new IOException("Unexpected PairVerify state");
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw new IOException("PairVerify failed",e);}
         catch(LinkageError e){close();throw new IOException("PairVerify crypto unavailable",e);}
        finally{PairingTlv.wipe(fields);}
    }
    private Reply m2(Map<Integer,byte[]> fields)throws IOException{
        peerPublic=PairingTlv.required(fields,3,32,32).clone();
        byte[] pair=PairingCrypto.generate(false),privateKey=Arrays.copyOfRange(pair,0,32);
        try{ownPublic=Arrays.copyOfRange(pair,32,64);shared=PairingCrypto.shared(privateKey,peerPublic);}
        finally{PairingTlv.wipe(pair);PairingTlv.wipe(privateKey);}
        encryptionKey=PairingCrypto.derive(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info");
        byte[] id=PairingTlv.utf8(store.identityIdentifier());
        byte[] data=PairingTlv.join(ownPublic,id,peerPublic),signature=null,plain=null;
        try{
            signature=store.sign(data);plain=PairingTlv.encode(new int[]{1,10},new byte[][]{id,signature});
            byte[] sealed=PairingCrypto.aead(true,encryptionKey,PairingCrypto.label("PV-Msg02"),plain,new byte[0]);
            try{expected=new Reply(PairingTlv.encode(new int[]{6,3,5},new byte[][]{new byte[]{2},ownPublic,sealed}));}
            finally{PairingTlv.wipe(sealed);}
        }finally{PairingTlv.wipe(data);PairingTlv.wipe(signature);PairingTlv.wipe(plain);}
        state=State.M2_UNSENT;return expected;
    }
    private Reply m4(Map<Integer,byte[]> fields)throws IOException{
        byte[] sealed=PairingTlv.required(fields,5,16,1024),plain=null,read=null,write=null;
        Map<Integer,byte[]> sub=null;
        try{
            plain=PairingCrypto.aead(false,encryptionKey,PairingCrypto.label("PV-Msg03"),sealed,new byte[0]);
            sub=PairingTlv.decode(plain);byte[] idBytes=PairingTlv.required(sub,1,1,256);
            String id=PairingTlv.identifier(idBytes);byte[] signature=PairingTlv.required(sub,10,64,64);
            PairingStore.Record record=store.lookup(id);
            if(record==null||!context.selectedBluetooth.equals(record.bluetoothAddress))throw new IOException("Controller not approved for selected phone");
            byte[] publicKey=record.key(),data=PairingTlv.join(peerPublic,idBytes,ownPublic);
            try{if(!PairingCrypto.verify(publicKey,data,signature))throw new IOException("Controller signature rejected");}
            finally{PairingTlv.wipe(publicKey);PairingTlv.wipe(data);}
            owner.checkCurrent(context);
            if(!store.unchanged(record))throw new IOException("Pairing changed during verification");
            read=PairingCrypto.derive(shared,"Control-Salt","Control-Write-Encryption-Key");
            write=PairingCrypto.derive(shared,"Control-Salt","Control-Read-Encryption-Key");
            verified=new VerifiedSession(store,record,context,owner,shared,read,write);
            expected=new Reply(PairingTlv.encode(new int[]{6},new byte[][]{new byte[]{4}}));
            state=State.M4_UNSENT;clearEphemeral();return expected;
        }finally{PairingTlv.wipe(plain);PairingTlv.wipe(sub);PairingTlv.wipe(read);PairingTlv.wipe(write);}
    }
    public synchronized void responseSent(Reply reply)throws IOException{
        try{
            check();if(reply==null||reply!=expected)throw new IOException("PairVerify response ownership");
            if(state==State.M2_UNSENT)state=State.WAIT_M3;
            else if(state==State.M4_UNSENT){verified.activate();state=State.VERIFIED;}
            else throw new IOException("PairVerify response state");
            expected=null;
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw new IOException("PairVerify response failed",e);}
         catch(LinkageError e){close();throw new IOException("PairVerify response API unavailable",e);}
    }
    public synchronized VerifiedSession session()throws IOException{
        if(state!=State.VERIFIED)throw new IOException("PairVerify final response not sent");
        verified.assertCurrent();return verified;
    }
    private void clearEphemeral(){
        PairingTlv.wipe(ownPublic);PairingTlv.wipe(peerPublic);PairingTlv.wipe(shared);PairingTlv.wipe(encryptionKey);
        ownPublic=peerPublic=shared=encryptionKey=null;
    }
    public synchronized void close(){state=State.CLOSED;expected=null;clearEphemeral();if(verified!=null)verified.close();}

    /** Minted only after an authenticated M3 and confirmed M4 flush. Counters never reset.
     * One control connection; retired type130 generations need fresh receive seeds. */
    public static final class VerifiedSession implements IapDataTunnel.MediaLease,Closeable {
        // ControlCipher reference emits 16KiB chunks and accepts the full uint16 wire length.
        public static final int MAX_OUTGOING_CONTROL_PAYLOAD=16384;
        public static final int MAX_INCOMING_CONTROL_PAYLOAD=65535;
        private final PairingStore store;
        private final PairingStore.Record record;
        private final Context context;
        private final OwnerLease owner;
        private final IapDataTunnel.Binding binding;
        private final byte[] secret,readKey,writeKey;
        private long readCounter,writeCounter;
        private boolean active,closed,controlRetired;
        private IapDataTunnel tunnel;
        private final Map<Integer,ScreenDataStream> screens=new HashMap<Integer,ScreenDataStream>();
        private final Map<String,AudioDataStream> audio=new HashMap<String,AudioDataStream>();
        private final Map<String,AudioInputStream> microphones=new HashMap<String,AudioInputStream>();
        private final Map<String,String> microphonePurposes=new HashMap<String,String>();
        private final Set<String> inputSeeds=new HashSet<String>();
        // All these receivers derive the same output-key label: no salt may occur twice.
        private final Set<String> receiveSeeds=new HashSet<String>();
        private byte[] eventRead,eventWrite;
        private long eventReadCounter,eventWriteCounter;
        private boolean eventClaimed;
        private VerifiedSession(PairingStore store,PairingStore.Record record,Context context,OwnerLease owner,
                                byte[] secret,byte[] read,byte[] write){
            this.store=store;this.record=record;this.context=context;this.owner=owner;
            this.secret=secret.clone();readKey=read.clone();writeKey=write.clone();
            binding=new IapDataTunnel.Binding(context.owner,context.generation,record.identifier,context.interfaceName,context.localAddress,context.remoteAddress);
        }
        private synchronized void activate()throws IOException{
            if(closed)throw new IOException("Verified session closed");active=true;assertCurrent();
            if(owner instanceof VerifiedOwnerLease)((VerifiedOwnerLease)owner).verificationComplete(context);
        }
        public synchronized void assertCurrent()throws IOException{
            try{
                if(!active||closed)throw new IOException("Verified session inactive");
                owner.checkCurrent(context);if(!store.unchanged(record))throw new IOException("Controller pairing revoked");
            }catch(IOException e){close();throw e;}
             catch(RuntimeException e){close();throw new IOException("Verified owner check failed",e);}
             catch(LinkageError e){close();throw new IOException("Verified owner API unavailable",e);}
        }
        public void checkCurrent(IapDataTunnel.Binding supplied)throws IOException{
            if(supplied!=binding)throw new IOException("Foreign DataStream binding");assertCurrent();
        }
        public synchronized boolean mediaAllowed(IapDataTunnel.Binding supplied)throws IOException{
            checkCurrent(supplied);return !(owner instanceof PhoneIdentityLease)||((PhoneIdentityLease)owner).mediaAllowed(context);
        }
        /** Called only with received fields after this session's runtime iAP2 authentication. */
        public synchronized void transportIdentifiers(String observedBluetooth,String observedUsb)throws IOException{
            assertCurrent();
            try{if(owner instanceof PhoneIdentityLease)((PhoneIdentityLease)owner).transportIdentifiers(context,observedBluetooth,observedUsb);}
            catch(IOException denied){close();throw denied;}
        }
        public synchronized String controllerIdentifier()throws IOException{assertCurrent();return record.identifier;}
        public synchronized Context context()throws IOException{assertCurrent();return context;}
        public synchronized IapDataTunnel.Binding binding()throws IOException{assertCurrent();return binding;}
        /** One event connection/counter set per verification; secret bytes never leave this owner. */
        public synchronized EventRecords claimEventRecords()throws IOException{
            assertCurrent();if(eventClaimed)throw new IOException("Event records already claimed");eventClaimed=true;
            try{
                eventRead=PairingCrypto.derive(secret,"Events-Salt","Events-Read-Encryption-Key");
                eventWrite=PairingCrypto.derive(secret,"Events-Salt","Events-Write-Encryption-Key");return new EventRecords(this);
            }catch(IOException e){close();throw e;}catch(RuntimeException e){close();throw e;}catch(LinkageError e){close();throw e;}
        }
        private synchronized byte[] eventRecord(boolean seal,byte[] input)throws IOException{
            byte[] plain=null,sealed=null;
            try{
                assertCurrent();if(!eventClaimed||eventRead==null||input==null)throw new IOException("Event records unavailable");
                if(seal){
                    if(input.length>MAX_OUTGOING_CONTROL_PAYLOAD)throw new IOException("Event record limit");
                    byte[] aad={(byte)input.length,(byte)(input.length>>>8)};
                    sealed=PairingCrypto.aead(true,eventWrite,PairingCrypto.counter(eventWriteCounter),input,aad);
                    eventWriteCounter=eventWriteCounter==Long.MAX_VALUE?-1:eventWriteCounter+1;assertCurrent();return PairingTlv.join(aad,sealed);
                }
                if(input.length<18||input.length!=18+(input[0]&255)+((input[1]&255)<<8))throw new IOException("Event record length");
                plain=PairingCrypto.aead(false,eventRead,PairingCrypto.counter(eventReadCounter),Arrays.copyOfRange(input,2,input.length),Arrays.copyOf(input,2));
                eventReadCounter=eventReadCounter==Long.MAX_VALUE?-1:eventReadCounter+1;assertCurrent();return plain;
            }catch(IOException e){PairingTlv.wipe(plain);close();throw e;}
             catch(RuntimeException e){PairingTlv.wipe(plain);close();throw new IOException("Event cipher failed",e);}
             catch(LinkageError e){PairingTlv.wipe(plain);close();throw new IOException("Event cipher unavailable",e);}
            finally{PairingTlv.wipe(sealed);}
        }
        public synchronized IapDataTunnel openTunnel(String seed,IapDataTunnel.EventChannel events)throws IOException{
            assertCurrent();
            if(tunnel!=null){
                try{if(!tunnel.protocolRetired()||!tunnel.awaitStopped(0))throw new IOException("Previous verified tunnel not retired and drained");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Tunnel drain interrupted",e);}
            }
            try{claimReceiveSeed(seed);tunnel=new IapDataTunnel(binding,this,events,secret,seed);return tunnel;}
            catch(IOException e){close();throw e;}
            catch(RuntimeException e){close();throw new IOException("Verified tunnel open failed",e);}
            catch(LinkageError e){close();throw new IOException("Verified tunnel API unavailable",e);}
        }
        private void claimReceiveSeed(String id)throws IOException{
            if(id==null||!id.matches("0|[1-9][0-9]{0,19}")||new BigInteger(id).bitLength()>64)
                throw new IOException("Invalid DataStream identifier");
            if(receiveSeeds.size()>=256)throw new IOException("DataStream key budget exhausted");
            if(!receiveSeeds.add(id))throw new IOException("DataStream receive key already used");
        }
        /** Audio data endpoint only. SETUP/RTCP and actual codec/output belong to the caller.
         * A normal per-stream stop preserves control/video; a failed sink or revoked lease
         * retires the parent. Keys are never reused after a stream has stopped. */
        public synchronized AudioDataStream openAudio(final String connectionId,final AudioDataStream.Sink sink)throws IOException{
            return openAudio(connectionId,sink,0);
        }
        public synchronized AudioDataStream openAudio(final String connectionId,final AudioDataStream.Sink sink,int controlPort)throws IOException{
            assertCurrent();if(sink==null||audio.size()>=8)throw new IOException("Audio claim state");
            try{
                claimReceiveSeed(connectionId);
                AudioDataStream stream=new AudioDataStream(binding,this,secret,connectionId,new AudioDataStream.SynchronizedSink(){
                    public boolean synchronize(AudioSyncPacket packet)throws IOException{assertCurrent();return sink instanceof AudioDataStream.SynchronizedSink&&((AudioDataStream.SynchronizedSink)sink).synchronize(packet);}
                    public boolean canFlush(){return sink instanceof AudioDataStream.FlushableSink&&((AudioDataStream.FlushableSink)sink).canFlush();}
                    public AudioDataStream.Flush beginFlush(long timestamp)throws IOException{
                        assertCurrent();if(!(sink instanceof AudioDataStream.FlushableSink))throw new IOException("Audio output has no FLUSH control");
                        final AudioDataStream.Flush operation=((AudioDataStream.FlushableSink)sink).beginFlush(timestamp);
                        return new AudioDataStream.Flush(){
                            public Long await(long millis)throws IOException,InterruptedException{assertCurrent();Long value=operation.await(millis);assertCurrent();return value;}
                            public void responseSent()throws IOException{assertCurrent();operation.responseSent();}
                        };
                    }
                    public void record()throws IOException{
                        assertCurrent();
                        if(!(sink instanceof AudioDataStream.RecordableSink))throw new IOException("Audio output has no RECORD control");
                        ((AudioDataStream.RecordableSink)sink).record();
                    }
                    public void retire(){if(sink instanceof AudioDataStream.RecordableSink)((AudioDataStream.RecordableSink)sink).retire();}
                    public void packet(AudioPacketDecoder.Packet packet)throws IOException{assertCurrent();sink.packet(packet);}
                    public void closed(IOException failure){
                        boolean failed=failure!=null;
                        try{sink.closed(failure);}
                        catch(RuntimeException e){failed=true;}
                        catch(LinkageError e){failed=true;}
                        finally{synchronized(VerifiedSession.this){audio.remove(connectionId);if(failed)VerifiedSession.this.close();}}
                    }
                },controlPort);audio.put(connectionId,stream);return stream;
            }catch(IOException e){close();throw e;}
             catch(RuntimeException e){close();throw new IOException("Audio open failed",e);}
             catch(LinkageError e){close();throw new IOException("Audio API unavailable",e);}
        }
        /** One input key/counter set paired with an existing output salt. The
         * input HKDF direction differs; an input salt can still never reopen. */
        public synchronized boolean microphoneAvailable(String purpose)throws IOException{
            assertCurrent();return ("speechRecognition".equals(purpose)||"telephony".equals(purpose))&&microphones.size()<2&&!microphonePurposes.containsKey(purpose);
        }
        public synchronized AudioInputStream openAudioInput(final String connectionId,int port,int samples,AudioWireFormat format,AudioInputStream.SourceFactory factory)throws IOException {
            return openAudioInput("speechRecognition",connectionId,port,samples,format,factory,MicrophoneCaptureLease.process(),null);
        }
        /** At most one logical reservation per voice purpose. Physical capture
         * is separately serialized and remains held through pending cleanup. */
        public synchronized AudioInputStream openAudioInput(final String purpose,final String connectionId,int port,int samples,AudioWireFormat format,AudioInputStream.SourceFactory factory,MicrophoneCaptureLease captures,AudioInputStream.CaptureGate gate)throws IOException {
            assertCurrent();if(!"speechRecognition".equals(purpose)&&!"telephony".equals(purpose)||!audio.containsKey(connectionId)||microphones.size()>=2||microphonePurposes.containsKey(purpose)||!inputSeeds.add(connectionId))throw new IOException("Microphone claim state");
            try{
                AudioInputStream.Listener listener=new AudioInputStream.Listener(){public void closed(IOException failure){
                    synchronized(VerifiedSession.this){microphones.remove(connectionId);if(connectionId.equals(microphonePurposes.get(purpose)))microphonePurposes.remove(purpose);if(failure!=null)VerifiedSession.this.close();}
                }};
                AudioInputStream stream=gate==null?new AudioInputStream(binding,this,secret,connectionId,port,samples,format,factory,listener,captures):new AudioInputStream(binding,this,secret,connectionId,port,samples,format,factory,listener,captures,gate);
                microphones.put(connectionId,stream);microphonePurposes.put(purpose,connectionId);return stream;
            }catch(IOException e){close();throw e;}catch(RuntimeException e){close();throw new IOException("Microphone open failed",e);}catch(LinkageError e){close();throw new IOException("Microphone API unavailable",e);}
        }
        /** No raw shared secret escapes. Explicit per-stream TEARDOWN preserves the parent;
         * abnormal EOF, codec failure and generic close still retire the verification. */
        public synchronized ScreenDataStream openScreen(final int type,String connectionId,final ScreenDataStream.Sink sink)throws IOException{
            assertCurrent();if((type!=110&&type!=111)||sink==null)throw new IOException("Screen claim state");
            ScreenDataStream previous=screens.get(type);
            if(previous!=null){
                try{if(!previous.awaitStopped(0))throw new IOException("Screen claim still draining");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Screen drain interrupted",e);}
                screens.remove(type);
            }
            try{
                claimReceiveSeed(connectionId);
                final ScreenDataStream[] exact=new ScreenDataStream[1];
                ScreenDataStream stream=new ScreenDataStream(binding,this,secret,connectionId,new ScreenDataStream.RetirableHeaderSink(){
                    private volatile boolean retired;
                    public boolean retire(){
                        if(sink instanceof ScreenDataStream.RetirableSink&&!((ScreenDataStream.RetirableSink)sink).retire())return false;
                        retired=true;return true;
                    }
                    public boolean awaitStopped(long millis)throws InterruptedException{
                        return !(sink instanceof ScreenDataStream.RetirableSink)||((ScreenDataStream.RetirableSink)sink).awaitStopped(millis);
                    }
                    public void configuration(byte[] data)throws IOException{assertCurrent();sink.configuration(data);assertCurrent();}
                    public void configuration(byte[] header,byte[] data)throws IOException{
                        assertCurrent();if(sink instanceof com.veloster.carplay.tunnel.ScreenDecoder.HeaderSink)
                            ((com.veloster.carplay.tunnel.ScreenDecoder.HeaderSink)sink).configuration(header,data);
                        else sink.configuration(data);assertCurrent();
                    }
                    public void frame(byte[] header,byte[] data)throws IOException{assertCurrent();sink.frame(header,data);assertCurrent();}
                    public void closed(IOException failure){
                        boolean failed=failure!=null||!retired;
                        try{sink.closed(failure);}catch(RuntimeException e){failed=true;}catch(LinkageError e){failed=true;}
                        finally{synchronized(VerifiedSession.this){
                            // Keep a retired entry until both receive and codec workers drain.
                            // It cannot be replaced by a late close of a different stream.
                            if(failed&&(exact[0]==null||screens.get(type)==exact[0]))VerifiedSession.this.close();
                        }}
                    }
                });exact[0]=stream;screens.put(type,stream);return stream;
            }catch(IOException e){close();throw e;}
             catch(RuntimeException e){close();throw new IOException("Screen open failed",e);}
             catch(LinkageError e){close();throw new IOException("Screen API unavailable",e);}
        }
        /** Complete control record only; the HTTP framing layer must preserve record boundaries. */
        public synchronized byte[] openControlRecord(byte[] wire)throws IOException{
            byte[] out=null;
            try{
                assertCurrent();
                if(wire==null||wire.length<18)throw new IOException("Short control record");
                int n=(wire[0]&255)|((wire[1]&255)<<8);
                if(n>MAX_INCOMING_CONTROL_PAYLOAD||wire.length!=n+18)throw new IOException("Control record length");
                out=PairingCrypto.aead(false,readKey,PairingCrypto.counter(readCounter),Arrays.copyOfRange(wire,2,wire.length),Arrays.copyOf(wire,2));
                readCounter=readCounter==Long.MAX_VALUE?-1:readCounter+1;assertCurrent();return out;
            }catch(IOException e){PairingTlv.wipe(out);close();throw e;}
             catch(RuntimeException e){PairingTlv.wipe(out);close();throw new IOException("Control decrypt failed",e);}
             catch(LinkageError e){PairingTlv.wipe(out);close();throw new IOException("Control crypto unavailable",e);}
        }
        public synchronized byte[] sealControlRecord(byte[] plain)throws IOException{
            try{
                assertCurrent();if(plain==null||plain.length>MAX_OUTGOING_CONTROL_PAYLOAD)throw new IOException("Control record length");
                byte[] aad={(byte)plain.length,(byte)(plain.length>>>8)};
                byte[] sealed=PairingCrypto.aead(true,writeKey,PairingCrypto.counter(writeCounter),plain,aad);
                writeCounter=writeCounter==Long.MAX_VALUE?-1:writeCounter+1;
                try{assertCurrent();return PairingTlv.join(aad,sealed);}finally{PairingTlv.wipe(sealed);}
            }catch(IOException e){close();throw e;}
             catch(RuntimeException e){close();throw new IOException("Control encrypt failed",e);}
             catch(LinkageError e){close();throw new IOException("Control crypto unavailable",e);}
        }
        /** Normal authenticated control EOF/requested close, before its owner
         * fence is withdrawn. Never reclassify a session already lost to a
         * crypto, event, or ownership failure as normal termination. */
        public synchronized void retireControl(){if(active&&!closed)controlRetired=true;close();}
        public synchronized boolean controlRetired(){return controlRetired;}
        public synchronized void close(){
            if(closed)return;closed=true;active=false;PairingTlv.wipe(secret);PairingTlv.wipe(readKey);PairingTlv.wipe(writeKey);
            PairingTlv.wipe(eventRead);PairingTlv.wipe(eventWrite);eventRead=eventWrite=null;
            if(tunnel!=null)tunnel.close();
            for(ScreenDataStream screen:screens.values())screen.close();
            for(AudioDataStream stream:audio.values())stream.close();
            for(AudioInputStream stream:microphones.values())stream.close();
        }
    }
    /** Uses the parent monitor only, avoiding lock-order inversions during parent/tunnel close. */
    public static final class EventRecords implements Closeable {
        private final VerifiedSession parent;
        private EventRecords(VerifiedSession parent){this.parent=parent;}
        public byte[] open(byte[] wire)throws IOException{return parent.eventRecord(false,wire);}
        public byte[] seal(byte[] plain)throws IOException{return parent.eventRecord(true,plain);}
        public void close(){parent.close();}
    }
}
