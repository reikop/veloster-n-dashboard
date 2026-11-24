/* SPDX-License-Identifier: GPL-3.0-only. Isolated wireless integration module. */
package com.veloster.carplay.coordinator;

import com.veloster.carplay.RfcommConnector;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One pending receiver owner, protected for the lifetime of the coordinator.
 * Caller supplies an app-private internal directory, never shared/external storage.
 * File fsync -> rename -> parent fsync completes BEFORE any receiver BEGIN.
 */
public final class SessionJournal implements Closeable {
    public interface DirectorySync { void sync(File directory)throws IOException; }
    interface CloseOperations {
        void release(FileLock lock)throws IOException;
        void close(RandomAccessFile file)throws IOException;
    }
    private static final Set<String> HELD=new HashSet<String>();
    private final File directory,pending,staging,lockPath;
    private final DirectorySync durability;
    private final CloseOperations closeOperations;
    private RandomAccessFile lockFile;
    private FileDescriptor lockDescriptor;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closing,closed;
    public static final class Record {
        public final String owner,apSession,phone,enrollmentId;public final ProcessOwner process;
        public Record(String owner,String apSession,String phone) {
            this(owner,apSession,phone,null);
        }
        public Record(String owner,String apSession,String phone,ProcessOwner process) {
            this(owner,apSession,phone,null,process);
        }
        /** Separate AP preparation record, never a receiver/phone authorization. */
        public static Record enrollment(String owner,String apSession,String enrollmentId,ProcessOwner process){return new Record(owner,apSession,null,enrollmentId,process);}
        private Record(String owner,String apSession,String phone,String enrollmentId,ProcessOwner process){
            if(owner==null || !owner.matches("[0-9a-f]{32}") || apSession==null
                || !apSession.matches("[0-9a-f]{32}") || owner.equals(apSession))throw new IllegalArgumentException("Invalid session generation");
            this.owner=owner;this.apSession=apSession;this.phone=enrollmentId==null?RfcommConnector.normalizeAddress(phone):null;this.enrollmentId=enrollmentId;this.process=process;
            if(enrollmentId!=null&&(phone!=null||!enrollmentId.matches("[0-9a-f]{32}")||enrollmentId.equals(owner)||enrollmentId.equals(apSession)||process==null))throw new IllegalArgumentException("Invalid enrollment record");
        }
        private byte[] bytes()throws IOException {
            String body=(enrollmentId!=null?"VNCP-ENROLLMENT-1\n":process==null?"VNCP-JOURNAL-1\n":"VNCP-JOURNAL-2\n")+owner+"\n"+apSession+"\n"+(enrollmentId==null?phone:enrollmentId)+"\n"+(process==null?"":process.encode()+"\n");
            return (body+digest(body.getBytes("US-ASCII"))+"\n").getBytes("US-ASCII");
        }
        private static Record parse(byte[] bytes)throws IOException {
            String[] lines=new String(bytes,"US-ASCII").split("\n",-1);
            boolean legacy=lines.length==6&&lines[0].equals("VNCP-JOURNAL-1"),witnessed=lines.length==7&&lines[0].equals("VNCP-JOURNAL-2");
            boolean enrollment=lines.length==7&&lines[0].equals("VNCP-ENROLLMENT-1");
            if((!legacy&&!witnessed&&!enrollment)||!lines[lines.length-1].equals(""))throw new IOException("Recovery journal is invalid");
            try {
                Record record=enrollment?Record.enrollment(lines[1],lines[2],lines[3],ProcessOwner.parse(lines[4])):new Record(lines[1],lines[2],lines[3],witnessed?ProcessOwner.parse(lines[4]):null);
                if(!Arrays.equals(record.bytes(),bytes))throw new IOException("Recovery journal checksum mismatch");
                return record;
            } catch(IllegalArgumentException invalid){throw new IOException("Recovery journal is invalid");}
        }
        public boolean same(Record other){return other!=null && owner.equals(other.owner) && apSession.equals(other.apSession) && sameText(phone,other.phone)&&sameText(enrollmentId,other.enrollmentId)&&(process==null?other.process==null:process.same(other.process));}
        private static boolean sameText(String a,String b){return a==null?b==null:a.equals(b);}
        public String toString(){return "Pending wireless receiver cleanup";}
    }
    public SessionJournal(File privateDirectory,DirectorySync durability)throws IOException {
        this(privateDirectory,durability,new CloseOperations(){
            public void release(FileLock lock)throws IOException{lock.release();}
            public void close(RandomAccessFile file)throws IOException{file.close();}
        });
    }
    SessionJournal(File privateDirectory,DirectorySync durability,CloseOperations closeOperations)throws IOException {
        if(privateDirectory==null || durability==null)throw new NullPointerException();
        if(closeOperations==null)throw new NullPointerException();this.closeOperations=closeOperations;
        directory=privateDirectory.getCanonicalFile();this.durability=durability;
        if(!directory.isDirectory())throw new IOException("Private journal directory unavailable");
        pending=new File(directory,"receiver.pending");staging=new File(directory,"receiver.pending.new");lockPath=new File(directory,"receiver.lock");
        // Opening and then closing a second descriptor can release this process's
        // POSIX locks. Reject same-process duplication BEFORE opening that inode.
        synchronized(HELD){if(!HELD.add(lockPath.getPath()))throw new IOException("Wireless coordinator already active");}
        boolean success=false;
        try {
            regularOrAbsent(lockPath);lockFile=new RandomAccessFile(lockPath,"rw");
            lockDescriptor=lockFile.getFD();lockChannel=lockFile.getChannel();
            try{lock=lockChannel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("Wireless coordinator already active");}
            if(lock==null)throw new IOException("Wireless coordinator already active");
            restrict(directory,true);
            restrict(lockPath,false);lockFile.getFD().sync();durability.sync(directory);
            regularOrAbsent(staging);regularOrAbsent(pending);
            // A staging file cannot precede a sent BEGIN: only the committed name
            // is used after parent fsync. Never substitute staging for pending.
            if(staging.exists()){if(!staging.delete())throw new IOException("Uncommitted journal cleanup failed");durability.sync(directory);}
            pending();success=true;
        } finally{if(!success)close();}
    }
    private void check()throws IOException{if(closing || closed || lock==null || !lock.isValid())throw new IOException("Journal lock unavailable");}
    private static void regularOrAbsent(File file)throws IOException {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile()) || (file.exists() && !file.isFile()))
            throw new IOException("Unexpected journal path");
    }
    private static void restrict(File file,boolean directory)throws IOException {
        if(!file.setReadable(false,false) || !file.setWritable(false,false) || !file.setExecutable(false,false)
            || !file.setReadable(true,true) || !file.setWritable(true,true) || (directory && !file.setExecutable(true,true)))
            throw new IOException("Private journal permissions unavailable");
    }
    public synchronized Record pending()throws IOException {
        check();regularOrAbsent(pending);if(!pending.exists())return null;
        FileInputStream input=new FileInputStream(pending);
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[128];int n;
            while((n=input.read(buffer))!=-1){if(bytes.size()+n>384)throw new IOException("Recovery journal exceeds limit");bytes.write(buffer,0,n);}
            return Record.parse(bytes.toByteArray());
        }finally{input.close();}
    }
    public synchronized void prepare(Record record)throws IOException {
        check();if(record==null)throw new NullPointerException();
        if(pending()!=null)throw new IOException("Previous receiver cleanup required");
        regularOrAbsent(staging);
        // A previous write/rename failure in this still-running process may have
        // left staging. No BEGIN can have used it, just as after process restart.
        if(staging.exists()){if(!staging.delete())throw new IOException("Uncommitted journal cleanup failed");durability.sync(directory);}
        FileOutputStream output=new FileOutputStream(staging);
        try{restrict(staging,false);output.write(record.bytes());output.getFD().sync();}finally{output.close();}
        if(!staging.renameTo(pending))throw new IOException("Recovery journal commit failed");
        durability.sync(directory);
    }
    public synchronized void clearAfterStop(Record record,ReceiverControl.Reply receipt)throws IOException {
        check();if(receipt==null || !receipt.released())throw new IOException("Receiver cleanup not acknowledged");
        clearOwnedResource(record);
    }
    /** Package resource adapters supply their own positive release proof. Never a synthetic receiver STOP. */
    synchronized void clearOwnedResource(Record record)throws IOException {
        check();if(!record.same(pending()))throw new IOException("Recovery journal owner changed");
        if(!pending.delete())throw new IOException("Recovery journal removal failed");
        durability.sync(directory);
    }
    synchronized void syncOwnedResourceDirectory()throws IOException{check();durability.sync(directory);}
    public synchronized void close()throws IOException {
        if(closed)return;closing=true;IOException failure=null;
        try{if(lock!=null&&lock.isValid())closeOperations.release(lock);}
        catch(IOException error){failure=error;}catch(RuntimeException error){failure=new IOException("Journal lock release failed",error);}
        // API17 RAF.getChannel() after close may create a new channel whose
        // isOpen is true despite the closed descriptor. Observe the original
        // channel and descriptor used for locking, never a newly created one.
        try{if(lockFile!=null&&(lockDescriptor==null||lockDescriptor.valid()||(lockChannel!=null&&lockChannel.isOpen())))closeOperations.close(lockFile);}
        catch(IOException error){failure=error;}catch(RuntimeException error){failure=new IOException("Journal file close failed",error);}
        if((lock==null||!lock.isValid())&&(lockDescriptor==null||!lockDescriptor.valid())&&(lockChannel==null||!lockChannel.isOpen())){
            closed=true;synchronized(HELD){HELD.remove(lockPath.getPath());}return;
        }
        throw failure==null?new IOException("Journal cleanup still owns resources"):failure;
    }
    private static String digest(byte[] bytes)throws IOException {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder value=new StringBuilder(64);
            for(byte b:hash){value.append("0123456789abcdef".charAt((b>>>4)&15));value.append("0123456789abcdef".charAt(b&15));}
            return value.toString();
        }catch(NoSuchAlgorithmException missing){throw new IOException("Journal checksum unavailable");}
    }
}
