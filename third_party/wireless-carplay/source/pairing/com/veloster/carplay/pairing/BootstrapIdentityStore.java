/* SPDX-License-Identifier: GPL-3.0-only. Exact-generation authenticated RFCOMM transport evidence. */
package com.veloster.carplay.pairing;
import com.veloster.carplay.RfcommConnector;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.control.ProvisionalPeerAdmission;
import java.io.*;
import java.net.InetAddress;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.util.*;

/** One app-private store, one process owner, one receiver attempt. This is not a
 * bond-key store or a controller pairing store. A different owner/AP/generation/
 * selected phone never inherits its evidence. Disk loading alone grants nothing.
 * Writes use file fsync, atomic same-directory rename, and directory fsync. */
public final class BootstrapIdentityStore implements Closeable {
    public interface Current {void checkCurrent(ReceiverScope scope)throws IOException;}
    interface DirectorySync {void sync(String path)throws IOException;}
    private static final int MAGIC=0x564e4231,MAX_BYTES=2048;
    private static final Set<String> HELD=new HashSet<String>();
    private final File directory,file,staging,lockPath;
    private final DirectorySync directorySync;
    private RandomAccessFile lockFile;private FileLock lock;
    private volatile boolean closed,poisoned;private Record persisted;private volatile Binding active;
    public static final class Record {
        private final ReceiverScope scope;public final String observedBluetooth,usbIdentifier;
        private Record(ReceiverScope scope,String bt,String usb)throws IOException{
            if(scope==null)throw new NullPointerException();this.scope=scope;
            try{observedBluetooth=bt==null?null:RfcommConnector.normalizeAddress(bt);}catch(IllegalArgumentException e){throw new IOException("Invalid observed Bluetooth",e);}
            if(observedBluetooth!=null&&!scope.selectedBluetooth.equals(observedBluetooth))throw new IOException("Different bootstrap phone");
            if(usb!=null)validText(usb);if(bt==null&&usb==null)throw new IOException("Empty bootstrap notification");usbIdentifier=usb;
        }
        public String usbIdentifier(ReceiverScope supplied)throws IOException{same(scope,supplied);return usbIdentifier;}
        public String toString(){return "BootstrapIdentityRecord[private]";}
    }
    public final class Binding implements Closeable,ProvisionalPeerAdmission.BootstrapIdentity {
        private final ReceiverScope scope;private final Current current;private volatile boolean retired;private volatile Record value;
        private Binding(ReceiverScope scope,Current current,Record value){this.scope=scope;this.current=current;this.value=value;}
        private void check()throws IOException{
            BootstrapIdentityStore.this.check();if(retired||active!=this)throw new IOException("Bootstrap binding retired");
            try{current.checkCurrent(scope);}catch(IOException ended){retired=true;value=null;throw ended;}
        }
        /** Memory-only read. Caller must provide a memory-only Current implementation. */
        public String usbIdentifier(ReceiverScope supplied)throws IOException{
            check();same(scope,supplied);Record result=value;check();return result==null?null:result.usbIdentifier;
        }
        public Record snapshot(ReceiverScope supplied)throws IOException{
            check();same(scope,supplied);Record result=value;check();return result;
        }
        /** Internal trusted boundary: only an actual notification from the selected
         * secure RFCOMM link after its accessory authentication may call this. The
         * optional BT field stays null; selected scope is not a received value. */
        public void received(String observedBluetooth,String usbIdentifier)throws IOException{
            synchronized(BootstrapIdentityStore.this){
                try{
                check();Record incoming=new Record(scope,observedBluetooth,usbIdentifier);
                if(value!=null&&value.usbIdentifier!=null&&incoming.usbIdentifier!=null&&!value.usbIdentifier.equals(incoming.usbIdentifier))
                    throw new IOException("Bootstrap USB identity changed within attempt");
                String bt=incoming.observedBluetooth!=null?incoming.observedBluetooth:value==null?null:value.observedBluetooth;
                String usb=incoming.usbIdentifier!=null?incoming.usbIdentifier:value==null?null:value.usbIdentifier;
                Record next=new Record(scope,bt,usb);current.checkCurrent(scope);save(next);check();value=next;
                }catch(IOException rejected){try{close();}catch(IOException ignored){}throw rejected;}
            }
        }
        public void close()throws IOException{
            synchronized(BootstrapIdentityStore.this){
                retired=true;value=null;
                if(active==this){active=null;save(null);}
            }
        }
    }
    public BootstrapIdentityStore(File appPrivateDirectory)throws IOException{
        this(appPrivateDirectory,new DirectorySync(){public void sync(String path)throws IOException{PairingCrypto.syncDirectory(path);}});
    }
    BootstrapIdentityStore(File appPrivateDirectory,DirectorySync directorySync)throws IOException{
        if(appPrivateDirectory==null)throw new NullPointerException();directory=appPrivateDirectory.getAbsoluteFile();
        if(directorySync==null)throw new NullPointerException();this.directorySync=directorySync;
        if(!directory.equals(directory.getCanonicalFile())||!directory.isDirectory())throw new IOException("Private bootstrap directory unavailable");
        file=new File(directory,"bootstrap.db");staging=new File(directory,"bootstrap.new");lockPath=new File(directory,"bootstrap.lock");
        synchronized(HELD){if(!HELD.add(lockPath.getPath()))throw new IOException("Bootstrap store already owned");}
        boolean ok=false;
        try{
            restrict(directory,true);regular(lockPath);lockFile=new RandomAccessFile(lockPath,"rw");restrict(lockPath,false);
            try{lock=lockFile.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("Bootstrap store locked",e);}
            if(lock==null)throw new IOException("Bootstrap store locked");regular(file);regular(staging);
            if(staging.exists()&&!staging.delete())throw new IOException("Bootstrap staging cleanup failed");directorySync.sync(directory.getPath());
            if(file.exists())persisted=load();else save(null);ok=true;
        }finally{if(!ok)close();}
    }
    private void check()throws IOException{if(closed||poisoned||lock==null||!lock.isValid())throw new IOException("Bootstrap store unavailable");}
    /** Starts a new attempt and invalidates previous selection/evidence durably. */
    public synchronized Binding begin(ReceiverScope scope,Current current)throws IOException{
        check();if(scope==null||current==null)throw new NullPointerException();current.checkCurrent(scope);
        if(active!=null){active.retired=true;active.value=null;active=null;}save(null);
        active=new Binding(scope,current,null);return active;
    }
    /** Explicit restore only after the caller revalidates that exact existing
     * owner/AP/bond generation. Never use this to adopt a new receiver attempt. */
    public synchronized Binding restore(ReceiverScope scope,Current current)throws IOException{
        check();if(scope==null||current==null)throw new NullPointerException();if(active!=null)throw new IOException("Bootstrap attempt already active");
        current.checkCurrent(scope);if(persisted!=null)same(persisted.scope,scope);
        active=new Binding(scope,current,persisted);return active;
    }
    public static void same(ReceiverScope expected,ReceiverScope actual)throws IOException{
        if(actual==null||expected==null||expected.generation!=actual.generation||!expected.owner.equals(actual.owner)||!expected.apSession.equals(actual.apSession)
            ||!expected.selectedBluetooth.equals(actual.selectedBluetooth)||!expected.interfaceName.equals(actual.interfaceName)||!expected.localAddress.equals(actual.localAddress))
            throw new IOException("Bootstrap evidence belongs to another receiver scope");
    }
    private static void regular(File path)throws IOException{if(!path.getAbsoluteFile().equals(path.getCanonicalFile())||path.exists()&&!path.isFile())throw new IOException("Unexpected bootstrap path");}
    private static void restrict(File path,boolean dir)throws IOException{
        if(!path.setReadable(false,false)||!path.setWritable(false,false)||!path.setExecutable(false,false)||!path.setReadable(true,true)||!path.setWritable(true,true)||dir&&!path.setExecutable(true,true))
            throw new IOException("Private bootstrap permissions unavailable");
    }
    private static void validText(String value)throws IOException{
        if(value.length()<1||value.length()>256||value.getBytes("UTF-8").length>256)throw new IOException("Bootstrap string length");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127||value.charAt(i)>=0xd800&&value.charAt(i)<=0xdfff)throw new IOException("Bootstrap string encoding");
    }
    private static void text(DataOutputStream out,String value)throws IOException{
        if(value==null){out.writeShort(0);return;}validText(value);byte[] bytes=value.getBytes("UTF-8");out.writeShort(bytes.length);out.write(bytes);
    }
    private static String text(DataInputStream in)throws IOException{
        int count=in.readUnsignedShort();if(count==0)return null;if(count>256)throw new IOException("Bootstrap string length");
        byte[] bytes=new byte[count];in.readFully(bytes);String value=new String(bytes,"UTF-8");validText(value);
        if(!Arrays.equals(bytes,value.getBytes("UTF-8")))throw new IOException("Bootstrap UTF-8 encoding");return value;
    }
    private static byte[] digest(byte[] value)throws IOException{try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception e){throw new IOException("Bootstrap checksum unavailable",e);}}
    private void save(Record value)throws IOException{
        check();byte[] body=null,wire=null;
        try{
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);out.writeInt(MAGIC);out.writeBoolean(value!=null);
            if(value!=null){ReceiverScope s=value.scope;text(out,s.owner);text(out,s.apSession);out.writeLong(s.generation);text(out,s.selectedBluetooth);text(out,s.interfaceName);
                byte[] address=s.localAddress.getAddress();out.writeByte(address.length);out.write(address);text(out,value.observedBluetooth);text(out,value.usbIdentifier);}
            out.flush();body=buffer.toByteArray();byte[] checksum=digest(body);wire=new byte[body.length+checksum.length];System.arraycopy(body,0,wire,0,body.length);System.arraycopy(checksum,0,wire,body.length,checksum.length);
            if(wire.length>MAX_BYTES)throw new IOException("Bootstrap store size");regular(staging);regular(file);
            FileOutputStream stream=new FileOutputStream(staging);try{restrict(staging,false);stream.write(wire);stream.getFD().sync();}finally{stream.close();}
            if(!staging.renameTo(file))throw new IOException("Bootstrap store rename failed");directorySync.sync(directory.getPath());persisted=value;
        }catch(IOException e){poisoned=true;throw e;}catch(RuntimeException e){poisoned=true;throw new IOException("Bootstrap commit failed",e);}
        catch(LinkageError e){poisoned=true;throw new IOException("Bootstrap durability unavailable",e);}
        finally{if(body!=null)Arrays.fill(body,(byte)0);if(wire!=null)Arrays.fill(wire,(byte)0);}
    }
    private Record load()throws IOException{
        restrict(file,false);long length=file.length();if(length<37||length>MAX_BYTES)throw new IOException("Bootstrap store size");byte[] wire=new byte[(int)length];
        DataInputStream input=new DataInputStream(new FileInputStream(file));try{input.readFully(wire);if(input.read()!=-1)throw new IOException("Bootstrap store changed");}finally{input.close();}
        byte[] body=Arrays.copyOf(wire,wire.length-32);
        try{
            if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(wire,wire.length-32,wire.length)))throw new IOException("Bootstrap checksum mismatch");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(body));if(in.readInt()!=MAGIC)throw new IOException("Bootstrap store format");
            int present=in.readUnsignedByte();if(present>1)throw new IOException("Bootstrap entry flag");Record result=null;
            if(present==1){String owner=text(in),ap=text(in);long generation=in.readLong();String selected=text(in),iface=text(in);int count=in.readUnsignedByte();
                if(count!=4&&count!=16)throw new IOException("Bootstrap address length");byte[] address=new byte[count];in.readFully(address);
                ReceiverScope scope=new ReceiverScope(owner,ap,generation,selected,iface,InetAddress.getByAddress(address));result=new Record(scope,text(in),text(in));}
            if(in.available()!=0)throw new IOException("Trailing bootstrap data");return result;
        }catch(IllegalArgumentException e){throw new IOException("Malformed bootstrap record",e);}
        finally{Arrays.fill(wire,(byte)0);Arrays.fill(body,(byte)0);}
    }
    /** Closing the store retains disk evidence for explicit exact-scope recovery,
     * but retires every live Binding. Use Binding.close for durable revocation. */
    public synchronized void close()throws IOException{
        if(closed)return;closed=true;if(active!=null){active.retired=true;active.value=null;active=null;}persisted=null;
        try{if(lock!=null)lock.release();}finally{try{if(lockFile!=null)lockFile.close();}finally{synchronized(HELD){HELD.remove(lockPath.getPath());}}}
    }
}
