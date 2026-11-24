/* SPDX-License-Identifier: GPL-3.0-only. Adapted from DiPlay PairSetup/Srp6a. */
package com.veloster.carplay.pairing;
import java.io.Closeable;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/** One SRP/M5 exchange per control connection. Fixed public 3939 is not phone authentication.
 * M5 stages a signed candidate. Only a trusted local UI may call approve, after correlating the
 * selected bonded phone with this owner/AP/control connection. No network auto-approval API.
 * The HTTP owner confirms each response flush. Approval commits before M6 is returned; a lost
 * M6 does not roll back explicit approval. The same key can repeat setup with fresh local approval;
 * a different key or phone cannot replace the existing registration. */
public final class PairSetup implements Closeable {
    interface Clock { long nanoTime(); }
    private enum State { NEW, M2_UNSENT, WAIT_M3, M4_UNSENT, WAIT_M5, PENDING, M6_UNSENT, COMPLETE, CLOSED }
    public static final class Reply {
        private final byte[] body;
        private Reply(byte[] body){this.body=body;}
        public byte[] body(){return body.clone();}
    }
    /** Display data is untrusted peer input except selectedBluetooth. Identity is not a phone name. */
    public static final class PendingEnrollment {
        public final String identifier,publicKeySha256,selectedBluetooth;
        private PendingEnrollment(String id,byte[] key,String bluetooth)throws IOException{
            identifier=id;selectedBluetooth=bluetooth;
            try{
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(key);StringBuilder out=new StringBuilder(64);
                for(byte b:digest){out.append("0123456789abcdef".charAt((b&255)>>>4));out.append("0123456789abcdef".charAt(b&15));}
                publicKeySha256=out.toString();
            }catch(Exception e){throw new IOException("Enrollment fingerprint unavailable",e);}
        }
    }
    private final PairingStore store;
    private final PairVerify.Context context;
    private final PairVerify.OwnerLease owner;
    private final Clock clock;
    private long deadline;
    private State state=State.NEW;
    private byte[] srp,key,candidateKey;
    private String candidateId;
    private Reply expected;
    private PendingEnrollment pending;
    private PairingStore.Record existing;
    public PairSetup(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner)throws IOException{
        this(store,context,owner,new Clock(){public long nanoTime(){return System.nanoTime();}});
    }
    PairSetup(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,Clock clock)throws IOException{
        if(store==null||context==null||owner==null||clock==null)throw new NullPointerException();
        this.store=store;this.context=context;this.owner=owner;this.clock=clock;
        deadline=clock.nanoTime()+30000000000L;owner.checkCurrent(context);store.identityIdentifier();
    }
    private void check()throws IOException{
        if(state==State.CLOSED||state==State.COMPLETE||clock.nanoTime()-deadline>=0)throw new IOException("PairSetup not accepting messages");
        owner.checkCurrent(context);store.identityIdentifier();
    }
    /** Returns null only for a valid M5 awaiting local approval. Call pendingEnrollment then. */
    public synchronized Reply handle(byte[] request)throws IOException{
        Map<Integer,byte[]> fields=null;
        try{
            check();fields=PairingTlv.decode(request);int message=PairingTlv.required(fields,6,1,1)[0]&255;
            if(message==1&&state==State.NEW){
                if(fields.containsKey(0)&&(PairingTlv.required(fields,0,1,1)[0]&255)!=0)throw new IOException("Unsupported PairSetup method");
                srp=Srp6a.start();
                expected=new Reply(PairingTlv.encode(new int[]{6,2,3},new byte[][]{new byte[]{2},Arrays.copyOf(srp,16),Arrays.copyOfRange(srp,432,816)}));
                state=State.M2_UNSENT;return expected;
            }
            if(message==3&&state==State.WAIT_M3){
                byte[] peer=PairingTlv.required(fields,3,1,384),padded=new byte[384],result=null;
                System.arraycopy(peer,0,padded,384-peer.length,peer.length);
                try{
                    result=Srp6a.verify(srp,padded,PairingTlv.required(fields,4,64,64));key=Arrays.copyOf(result,64);
                    expected=new Reply(PairingTlv.encode(new int[]{6,4},new byte[][]{new byte[]{4},Arrays.copyOfRange(result,64,128)}));
                }finally{PairingTlv.wipe(srp);srp=null;PairingTlv.wipe(result);PairingTlv.wipe(padded);}
                state=State.M4_UNSENT;return expected;
            }
            if(message==5&&state==State.WAIT_M5){stage(fields);state=State.PENDING;deadline=clock.nanoTime()+120000000000L;return null;}
            throw new IOException("Unexpected PairSetup state");
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw new IOException("PairSetup failed",e);}
         catch(LinkageError e){close();throw new IOException("PairSetup crypto unavailable",e);}
        finally{PairingTlv.wipe(fields);}
    }
    private void stage(Map<Integer,byte[]> fields)throws IOException{
        byte[] encryption=null,plain=null,signKey=null,data=null;Map<Integer,byte[]> sub=null;
        try{
            encryption=PairingCrypto.derive(key,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info");
            plain=PairingCrypto.aead(false,encryption,PairingCrypto.label("PS-Msg05"),PairingTlv.required(fields,5,16,1024),new byte[0]);
            sub=PairingTlv.decode(plain);byte[] id=PairingTlv.required(sub,1,1,256),pub=PairingTlv.required(sub,3,32,32);
            String identifier=PairingTlv.identifier(id);
            signKey=PairingCrypto.derive(key,"Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info");
            data=PairingTlv.join(signKey,id,pub);
            if(!PairingCrypto.verify(pub,data,PairingTlv.required(sub,10,64,64)))throw new IOException("PairSetup signature rejected");
            check();existing=store.lookup(identifier);
            if(existing!=null&&(!Arrays.equals(existing.key(),pub)||!existing.bluetoothAddress.equals(context.selectedBluetooth)))
                throw new IOException("Controller registration conflicts");
            pending=new PendingEnrollment(identifier,pub,context.selectedBluetooth);candidateId=identifier;candidateKey=pub.clone();
        }finally{
            PairingTlv.wipe(encryption);PairingTlv.wipe(plain);PairingTlv.wipe(signKey);PairingTlv.wipe(data);PairingTlv.wipe(sub);
        }
    }
    public synchronized PendingEnrollment pendingEnrollment()throws IOException{
        try{check();if(state!=State.PENDING)throw new IOException("No pending enrollment");return pending;}
        catch(IOException e){close();throw e;}
        catch(RuntimeException e){close();throw new IOException("Enrollment owner check failed",e);}
        catch(LinkageError e){close();throw new IOException("Enrollment owner unavailable",e);}
    }
    /** Trusted local action, bound to the exact staged object; never route this from remote HTTP. */
    public synchronized Reply approve(PendingEnrollment approval)throws IOException{
        byte[] signKey=null,data=null,signature=null,plain=null,encryption=null,sealed=null;
        try{
            check();if(state!=State.PENDING||approval==null||approval!=pending)throw new IOException("Foreign/stale enrollment approval");
            byte[] id=PairingTlv.utf8(store.identityIdentifier()),pub=store.identityPublicKey();
            signKey=PairingCrypto.derive(key,"Pair-Setup-Accessory-Sign-Salt","Pair-Setup-Accessory-Sign-Info");
            data=PairingTlv.join(signKey,id,pub);signature=store.sign(data);
            plain=PairingTlv.encode(new int[]{1,3,10},new byte[][]{id,pub,signature});
            encryption=PairingCrypto.derive(key,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info");
            sealed=PairingCrypto.aead(true,encryption,PairingCrypto.label("PS-Msg06"),plain,new byte[0]);
            Reply reply=new Reply(PairingTlv.encode(new int[]{6,5},new byte[][]{new byte[]{6},sealed}));
            synchronized(store){check();
                if(existing==null?store.lookup(candidateId)!=null:!store.unchanged(existing))throw new IOException("Controller registration changed concurrently");
                store.enrollVerified(candidateId,candidateKey,context.selectedBluetooth);}
            // A durable, explicitly approved registration survives a later socket/flush failure.
            expected=reply;state=State.M6_UNSENT;clearSecrets();pending=null;return expected;
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw new IOException("Enrollment failed",e);}
         catch(LinkageError e){close();throw new IOException("Enrollment crypto unavailable",e);}
        finally{PairingTlv.wipe(signKey);PairingTlv.wipe(data);PairingTlv.wipe(signature);PairingTlv.wipe(plain);PairingTlv.wipe(encryption);PairingTlv.wipe(sealed);}
    }
    public synchronized void responseSent(Reply reply)throws IOException{
        try{
            check();if(reply==null||reply!=expected)throw new IOException("PairSetup response ownership");
            if(state==State.M2_UNSENT)state=State.WAIT_M3;
            else if(state==State.M4_UNSENT)state=State.WAIT_M5;
            else if(state==State.M6_UNSENT)state=State.COMPLETE;
            else throw new IOException("PairSetup response state");
            expected=null;
        }catch(IOException e){close();throw e;}
         catch(RuntimeException e){close();throw new IOException("PairSetup response failed",e);}
         catch(LinkageError e){close();throw new IOException("PairSetup owner unavailable",e);}
    }
    public synchronized boolean complete(){return state==State.COMPLETE;}
    private void clearSecrets(){PairingTlv.wipe(srp);PairingTlv.wipe(key);PairingTlv.wipe(candidateKey);srp=key=candidateKey=null;candidateId=null;existing=null;}
    public synchronized void close(){state=State.CLOSED;expected=null;pending=null;clearSecrets();}
}
