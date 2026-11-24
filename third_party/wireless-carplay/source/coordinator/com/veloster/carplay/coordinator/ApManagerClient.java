package com.veloster.carplay.coordinator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed root command, private captured pipe. Never logs subprocess output. */
public final class ApManagerClient {
    private static final String MANAGER="/data/local/tmp/vn-usb-wifi/ap/bin/vn-ap-manager";
    private final String[] command,stationCommand;private String[] lifecyclePrefix;
    public ApManagerClient(){command=new String[]{"/system/bin/amossu","-c",MANAGER+" credentials"};stationCommand=new String[]{"/system/bin/amossu","-c",MANAGER+" station"};}
    // Package-private alternate process for an isolated runtime fixture, not user input.
    ApManagerClient(String[] fixture){command=fixture.clone();stationCommand=fixture.clone();}
    ApManagerClient(String[] fixture,boolean appendVerb){
        lifecyclePrefix=fixture.clone();command=operation("credentials");stationCommand=operation("station");
        if(!appendVerb)throw new IllegalArgumentException("Fixture verb dispatch required");
    }
    private String[] operation(String verb){
        if(lifecyclePrefix==null)return new String[]{"/system/bin/amossu","-c",MANAGER+" "+verb};
        String[] result=Arrays.copyOf(lifecyclePrefix,lifecyclePrefix.length+1);result[result.length-1]=verb;return result;
    }
    public ApOwnedSession owned(ApConfiguration configuration,String sessionToken)throws IOException{
        return new ApOwnedSession(this,configuration,sessionToken);
    }
    void prepareOwned(byte[] request)throws IOException{
        byte[] response=readPrivate(operation("prepare-owned"),10000,request);
        try{if(response.length!=0)throw new IOException("Unexpected AP prepare response");}finally{Arrays.fill(response,(byte)0);}
    }
    void ownedCommand(String verb,String token,String expected,long timeout)throws IOException{
        if(!verb.equals("start-owned")&&!verb.equals("stop-owned"))throw new IllegalArgumentException("AP operation");
        byte[] request=("format=1\nsession_token="+token+"\nend\n").getBytes("US-ASCII");
        byte[] response=null;try{
            response=readPrivate(operation(verb),timeout,request);
            byte[] required=("format=1\nsession_token="+token+"\nstate="+expected+"\nend\n").getBytes("US-ASCII");
            if(!Arrays.equals(response,required))throw new IOException("AP ownership response rejected");
        }finally{Arrays.fill(request,(byte)0);if(response!=null)Arrays.fill(response,(byte)0);}
    }
    public ApCredentials credentials() throws IOException {
        byte[] frame=readPrivate(command,5000);
        try{return ApCredentials.parse(frame);}finally{Arrays.fill(frame,(byte)0);}
    }
    public boolean current(String token)throws IOException {
        ApCredentials c=credentials();try{return c.sessionToken.equals(token);}finally{c.close();}
    }
    /** Blocking root IPC: collect on a worker, never from a lease check or UI thread.
     * Neither this observation nor its DHCP address authenticates a selected phone. */
    public ApStationSnapshot station()throws IOException {
        byte[] frame=readPrivate(stationCommand,5000);
        try{return ApStationSnapshot.parse(frame);}finally{Arrays.fill(frame,(byte)0);}
    }
    public ApStationMonitor watch(String sessionToken,int interfaceIndex){
        return new ApStationMonitor(operation("watch-station"),sessionToken,interfaceIndex,
            new ApStationMonitor.Clock(){public long now(){return android.os.SystemClock.elapsedRealtime();}});
    }
    static byte[] readPrivate(String[] command,long timeoutMillis)throws IOException {
        return readPrivate(command,timeoutMillis,new byte[0]);
    }
    static byte[] readPrivate(String[] command,long timeoutMillis,byte[] request)throws IOException {
        if(request==null || request.length>384)throw new IOException("Private request too large");
        final Process process=new ProcessBuilder(command).start();
        final Capture stdout=new Capture(process.getInputStream(),384),stderr=new Capture(process.getErrorStream(),512);
        Thread a=new Thread(stdout,"vn-ap-private-out"),b=new Thread(stderr,"vn-ap-private-err");a.setDaemon(true);b.setDaemon(true);
        final CountDownLatch exited=new CountDownLatch(1);final int[] code={-1};
        Thread waiter=new Thread(new Runnable(){public void run(){try{code[0]=process.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exited.countDown();}}},"vn-ap-private-wait");waiter.setDaemon(true);
        byte[] result=null;
        try {
            a.start();b.start();waiter.start();
            // Bounded below pipe capacity; request contents never enter argv.
            process.getOutputStream().write(request);process.getOutputStream().close();
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            if(!exited.await(timeoutMillis,TimeUnit.MILLISECONDS))throw new IOException("AP credential request timed out");
            long left=Math.max(0,end-System.nanoTime());a.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
            left=Math.max(0,end-System.nanoTime());b.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));
            if(a.isAlive()||b.isAlive()||stdout.bad||stderr.bad)throw new IOException("AP credential response unavailable");
            if(code[0]==4)throw new IOException("AP password must be configured again");
            if(code[0]!=0)throw new IOException("AP credentials unavailable");
            result=stdout.copy();return result;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("AP credential request interrupted");}
        finally {
            process.destroy();
            try{process.getOutputStream().close();}catch(IOException ignored){}
            try{process.getInputStream().close();}catch(IOException ignored){}
            try{process.getErrorStream().close();}catch(IOException ignored){}
            waiter.interrupt();stdout.clear();stderr.clear();
        }
    }
    private static final class Capture implements Runnable {
        final InputStream input;final int limit;private final byte[] bytes;private int size;
        volatile boolean bad;
        Capture(InputStream input,int limit){this.input=input;this.limit=limit;bytes=new byte[limit];}
        public void run(){byte[] buffer=new byte[128];try {
            int n;while((n=input.read(buffer))!=-1) {
                synchronized(this){if(size+n>limit)bad=true;else if(!bad){System.arraycopy(buffer,0,bytes,size,n);size+=n;}}
            }
        }catch(IOException e){bad=true;}finally{Arrays.fill(buffer,(byte)0);}}
        synchronized byte[] copy(){return Arrays.copyOf(bytes,size);}
        synchronized void clear(){Arrays.fill(bytes,(byte)0);size=0;bad=true;}
    }
}
