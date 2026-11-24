/* SPDX-License-Identifier: GPL-3.0-only. Private persistent modern receiver identity. */
package com.veloster.carplay.pairing;
import com.veloster.carplay.RfcommConnector;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated app-internal directory only. One process owns the store for its lifetime.
 * File fsync -> rename -> directory fsync precedes a successful enrollment/revocation.
 * Public keys are not learned by PairVerify. PairSetup requires a trusted local approval UI. */
public final class PairingStore implements Closeable {
    private static final Set<String> HELD=new HashSet<String>();
    private static final int MAGIC=0x564e5031,MAX_BYTES=8192;
    private final File directory,file,staging,lockPath;
    private final Map<String,Record> entries=new LinkedHashMap<String,Record>();
    private RandomAccessFile lockFile;
    private FileLock lock;
    private byte[] seed;
    private String identifier;
    private long revision;
    private boolean closed,poisoned;
    private static final class SecretBuffer extends ByteArrayOutputStream {
        void erase(){Arrays.fill(buf,(byte)0);reset();}
    }
    public static final class Record {
        public final String identifier,bluetoothAddress;
        public final long revision;
        private final byte[] publicKey;
        private Record(String id,byte[] key,String bluetooth,long revision)throws IOException{
            PairingTlv.utf8(id);if(key==null||key.length!=32||revision<1)throw new IOException("Invalid pairing record");
            this.identifier=id;this.publicKey=key.clone();this.revision=revision;
            try{bluetoothAddress=RfcommConnector.normalizeAddress(bluetooth);}
            catch(IllegalArgumentException e){throw new IOException("Invalid paired Bluetooth address",e);}
        }
        byte[] key(){return publicKey.clone();}
        boolean same(Record other){return other!=null&&revision==other.revision&&identifier.equals(other.identifier)
                &&bluetoothAddress.equals(other.bluetoothAddress)&&Arrays.equals(publicKey,other.publicKey);}
        public String toString(){return "Stored paired controller";}
    }
    public PairingStore(File appPrivateDirectory)throws IOException{
        if(appPrivateDirectory==null)throw new NullPointerException();
        directory=appPrivateDirectory.getAbsoluteFile();
        if(!directory.equals(directory.getCanonicalFile())||!directory.isDirectory())throw new IOException("Private pairing directory unavailable");
        file=new File(directory,"pairings.db");staging=new File(directory,"pairings.new");lockPath=new File(directory,"pairings.lock");
        synchronized(HELD){if(!HELD.add(lockPath.getPath()))throw new IOException("Pairing store already owned");}
        boolean success=false;
        try{
            restrict(directory,true);regular(lockPath);
            lockFile=new RandomAccessFile(lockPath,"rw");restrict(lockPath,false);
            try{lock=lockFile.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("Pairing store locked",e);}
            if(lock==null)throw new IOException("Pairing store locked");
            regular(staging);regular(file);
            if(staging.exists()&&!staging.delete())throw new IOException("Stale pairing staging removal failed");
            PairingCrypto.syncDirectory(directory.getPath());
            if(file.exists())load();
            else{
                byte[] pair=PairingCrypto.generate(true);
                try{seed=Arrays.copyOfRange(pair,0,32);}finally{PairingTlv.wipe(pair);}
                identifier=UUID.randomUUID().toString();save();
            }
            success=true;
        }finally{if(!success)close();}
    }
    private void check()throws IOException{
        if(closed||poisoned||lock==null||!lock.isValid())throw new IOException("Pairing store unavailable");
    }
    public synchronized String identityIdentifier()throws IOException{check();return identifier;}
    public synchronized byte[] identityPublicKey()throws IOException{check();return PairingCrypto.publicKey(true,seed);}
    synchronized byte[] sign(byte[] data)throws IOException{check();return PairingCrypto.sign(seed,data);}
    public synchronized Record lookup(String id)throws IOException{check();return entries.get(id);}
    public synchronized boolean unchanged(Record record)throws IOException{check();return record!=null&&record.same(entries.get(record.identifier));}
    /** Internal enrollment boundary. Caller MUST have checked PairSetup signature and explicit
     * local approval associating this identity with this selected bonded phone. Not a network API.
     * PairSetup calls this only for its verified candidate after an explicit local approve action.
     * The actual UI and selected-phone/control-connection correlation are still required. */
    synchronized void enrollVerified(String id,byte[] key,String selectedBluetooth)throws IOException{
        check();if(revision==Long.MAX_VALUE)throw new IOException("Pairing generation exhausted");
        Record record=new Record(id,key,selectedBluetooth,revision+1),old=entries.get(id);
        if(old!=null){
            if(!Arrays.equals(old.publicKey,key)||!old.bluetoothAddress.equals(record.bluetoothAddress))
                throw new IOException("Existing pairing cannot be silently replaced");
            return;
        }
        if(entries.size()>=16)throw new IOException("Paired controller limit");
        revision++;entries.put(id,record);save();
    }
    public synchronized void revoke(String id)throws IOException{
        check();if(!entries.containsKey(id))return;
        if(revision==Long.MAX_VALUE)throw new IOException("Pairing generation exhausted");
        revision++;entries.remove(id);save();
    }
    private static void regular(File path)throws IOException{
        if(!path.getAbsoluteFile().equals(path.getCanonicalFile())||(path.exists()&&!path.isFile()))throw new IOException("Unexpected pairing path");
    }
    private static void restrict(File path,boolean directory)throws IOException{
        if(!path.setReadable(false,false)||!path.setWritable(false,false)||!path.setExecutable(false,false)||
           !path.setReadable(true,true)||!path.setWritable(true,true)||(directory&&!path.setExecutable(true,true)))
            throw new IOException("Private pairing permissions unavailable");
    }
    private static byte[] digest(byte[] bytes)throws IOException{
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw new IOException("Pairing digest unavailable",e);}
    }
    private static void string(DataOutputStream out,String text)throws IOException{
        byte[] bytes=PairingTlv.utf8(text);out.writeShort(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream in)throws IOException{
        int size=in.readUnsignedShort();if(size<1||size>256)throw new IOException("Pairing string length");
        byte[] bytes=new byte[size];in.readFully(bytes);return PairingTlv.identifier(bytes);
    }
    private void save()throws IOException{
        check();byte[] body=null,wire=null;SecretBuffer buffer=new SecretBuffer();
        try{
            regular(staging);regular(file);
            DataOutputStream out=new DataOutputStream(buffer);
            out.writeInt(MAGIC);out.writeLong(revision);string(out,identifier);out.write(seed);out.writeByte(entries.size());
            for(Record r:entries.values()){string(out,r.identifier);string(out,r.bluetoothAddress);out.writeLong(r.revision);out.write(r.publicKey);}
            out.flush();body=buffer.toByteArray();wire=PairingTlv.join(body,digest(body));
            if(wire.length>MAX_BYTES)throw new IOException("Pairing store size limit");
            FileOutputStream output=new FileOutputStream(staging);
            try{restrict(staging,false);output.write(wire);output.getFD().sync();}finally{output.close();}
            if(!staging.renameTo(file))throw new IOException("Pairing store rename failed");
            PairingCrypto.syncDirectory(directory.getPath());
        }catch(IOException e){poisoned=true;throw e;}
         catch(RuntimeException e){poisoned=true;throw new IOException("Pairing store commit failed",e);}
         catch(LinkageError e){poisoned=true;throw new IOException("Pairing store durability unavailable",e);}
        finally{buffer.erase();PairingTlv.wipe(body);PairingTlv.wipe(wire);}
    }
    private void load()throws IOException{
        restrict(file,false);long length=file.length();if(length<4+8+2+1+32+1+32||length>MAX_BYTES)throw new IOException("Pairing store length");
        byte[] wire=new byte[(int)length],body=null;FileInputStream in=new FileInputStream(file);
        try{
            DataInputStream input=new DataInputStream(in);input.readFully(wire);if(input.read()!=-1)throw new IOException("Pairing store changed");
            body=Arrays.copyOf(wire,wire.length-32);
            if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(wire,wire.length-32,wire.length)))throw new IOException("Pairing store checksum mismatch");
            DataInputStream data=new DataInputStream(new ByteArrayInputStream(body));
            if(data.readInt()!=MAGIC)throw new IOException("Pairing store format");
            revision=data.readLong();if(revision<0)throw new IOException("Pairing store generation");
            identifier=string(data);seed=new byte[32];data.readFully(seed);
            int count=data.readUnsignedByte();if(count>16)throw new IOException("Pairing store count");
            for(int i=0;i<count;i++){
                String id=string(data),bluetooth=string(data);long rev=data.readLong();byte[] pub=new byte[32];data.readFully(pub);
                Record record=new Record(id,pub,bluetooth,rev);
                if(rev>revision||entries.put(id,record)!=null)throw new IOException("Duplicate/invalid pairing record");
            }
            if(data.available()!=0)throw new IOException("Trailing pairing data");
        }finally{in.close();PairingTlv.wipe(wire);PairingTlv.wipe(body);}
    }
    public synchronized void close()throws IOException{
        if(closed)return;closed=true;PairingTlv.wipe(seed);entries.clear();
        try{if(lock!=null)lock.release();}
        finally{try{if(lockFile!=null)lockFile.close();}finally{synchronized(HELD){HELD.remove(lockPath.getPath());}}}
    }
}
