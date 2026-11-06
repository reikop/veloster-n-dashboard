/* SPDX-License-Identifier: GPL-3.0-only. Linux process identity, never a PID alone. */
package com.veloster.carplay.coordinator;
import java.io.*;

/** Evidence for resources whose workers/sockets live exclusively in this process.
 * A missing or unreadable proc observation is UNKNOWN, never proof of death.
 * Root AP resources additionally require their exact-token release receipt. */
public final class ProcessOwner {
    public interface Probe { boolean dead(ProcessOwner owner)throws IOException; }
    public final String boot;public final long pid,start,uid;
    public ProcessOwner(String boot,long pid,long start,long uid){
        if(boot==null||!boot.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||pid<1||start<1||uid<0)throw new IllegalArgumentException("Invalid process identity");
        this.boot=boot;this.pid=pid;this.start=start;this.uid=uid;
    }
    String encode(){return boot+","+pid+","+start+","+uid;}
    static ProcessOwner parse(String value)throws IOException{
        try{String[] p=value.split(",",-1);if(p.length!=4)throw new IllegalArgumentException();ProcessOwner result=new ProcessOwner(p[0],Long.parseLong(p[1]),Long.parseLong(p[2]),Long.parseLong(p[3]));
            if(!result.encode().equals(value))throw new IllegalArgumentException();return result;
        }catch(IllegalArgumentException invalid){throw new IOException("Process identity rejected",invalid);}
    }
    public boolean same(ProcessOwner other){return other!=null&&encode().equals(other.encode());}
    public static ProcessOwner current()throws IOException{return current(new File("/proc"));}
    static ProcessOwner current(File proc)throws IOException{
        long[] stat=stat(read(new File(proc,"self/stat"),4096));
        return new ProcessOwner(boot(proc),stat[0],stat[1],uid(read(new File(proc,"self/status"),16384)));
    }
    public static Probe linux(){return linux(new File("/proc"));}
    static Probe linux(final File proc){return new Probe(){public boolean dead(ProcessOwner owner)throws IOException{
        if(uid(read(new File(proc,"self/status"),16384))!=owner.uid)throw new IOException("Journal process UID is foreign");
        if(!boot(proc).equals(owner.boot))return true;
        // A complete directory listing distinguishes an absent PID from denied
        // access to its stat file. Do not infer death from File.exists().
        String[] entries=proc.list();if(entries==null)throw new IOException("Process listing unavailable");
        boolean found=false;for(String entry:entries)if(entry.equals(Long.toString(owner.pid))){found=true;break;}
        if(!found)return true;
        File directory=new File(proc,Long.toString(owner.pid));long[] identity=stat(read(new File(directory,"stat"),4096));
        if(identity[0]!=owner.pid)throw new IOException("Process observation changed");
        if(identity[1]!=owner.start)return true;
        if(uid(read(new File(directory,"status"),16384))!=owner.uid)throw new IOException("Process UID changed without a new generation");
        return false;
    }};}
    private static String boot(File proc)throws IOException{
        String value=read(new File(proc,"sys/kernel/random/boot_id"),64).trim();
        try{return new ProcessOwner(value,1,1,0).boot;}catch(IllegalArgumentException invalid){throw new IOException("Boot identity unavailable",invalid);}
    }
    static long[] stat(String text)throws IOException{
        try{int open=text.indexOf('('),close=text.lastIndexOf(')');if(open<2||close<=open)throw new IllegalArgumentException();
            long pid=Long.parseLong(text.substring(0,open).trim());String[] fields=text.substring(close+1).trim().split("\\s+");
            // fields[0] is Linux stat field 3; starttime is field 22.
            long start=Long.parseLong(fields[19]);if(pid<1||start<1)throw new IllegalArgumentException();return new long[]{pid,start};
        }catch(RuntimeException invalid){throw new IOException("Process stat rejected",invalid);}
    }
    private static long uid(String status)throws IOException{
        for(String line:status.split("\n"))if(line.startsWith("Uid:")){
            try{String[] fields=line.substring(4).trim().split("\\s+");if(fields.length!=4)throw new IllegalArgumentException();long uid=Long.parseLong(fields[1]);if(uid<0)throw new IllegalArgumentException();return uid;}
            catch(RuntimeException invalid){throw new IOException("Process UID rejected",invalid);}
        }throw new IOException("Process UID unavailable");
    }
    private static String read(File path,int limit)throws IOException{
        FileInputStream input=new FileInputStream(path);try{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] b=new byte[512];int n;
            while((n=input.read(b))!=-1){if(output.size()+n>limit)throw new IOException("Process observation exceeds limit");output.write(b,0,n);}return new String(output.toByteArray(),"US-ASCII");
        }finally{input.close();}
    }
    public String toString(){return "ProcessOwner[private]";}
}
