package com.veloster.carplay.coordinator;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Persistent private root pipe -> immutable, expiring local cache. No phone
 * identity inference and no shell/process work in latest(). close requests stop;
 * only awaitStopped confirms that the worker and its direct child have exited. */
public final class ApStationMonitor implements Closeable {
    interface Clock {long now();}
    private final String[] command;private final String token;private final int index;private final Clock clock;
    private final Thread worker;private volatile Thread errors,waiter;private volatile Process process;
    private final CountDownLatch exited=new CountDownLatch(1);
    private volatile ApStationSnapshot snapshot;private volatile boolean closed,failed;
    ApStationMonitor(String[] command,String token,int index,Clock clock){
        if(command==null||clock==null||token==null||!token.matches("[0-9a-f]{32}")||index<1)throw new IllegalArgumentException("AP monitor scope");
        this.command=command.clone();this.token=token;this.index=index;this.clock=clock;
        worker=new Thread(new Runnable(){public void run(){collect();}},"vn-ap-station-watch");worker.setDaemon(true);worker.start();
    }
    public ApStationSnapshot latest(){
        ApStationSnapshot value=snapshot;if(closed||failed||value==null)return null;
        long age=clock.now()-value.sampledBoottimeMillis;return age>=0&&age<=2500?value:null;
    }
    public boolean failed(){return failed;}
    /** Memory-only lifecycle/scope check, safe in a lease callback. */
    public void checkCurrent(String expectedToken,int expectedIndex)throws IOException{
        if(!token.equals(expectedToken)||index!=expectedIndex||closed||failed||exited.getCount()==0)
            throw new IOException("AP station watcher retired or foreign");
    }
    public void close(){closed=true;snapshot=null;stopProcess();}
    private void stopProcess(){
        Process value=process;if(value==null)return;
        // No writes occur on this stream, so closing it cannot contend with an IO writer.
        try{value.getOutputStream().close();}catch(IOException ignored){}
        value.destroy();
    }
    private void fail(){if(!closed)failed=true;snapshot=null;stopProcess();}
    private void collect(){
        try{
            if(closed)return;
            final Process child=new ProcessBuilder(command).start();process=child;
            waiter=new Thread(new Runnable(){public void run(){try{child.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exited.countDown();}}},"vn-ap-station-exit");waiter.setDaemon(true);waiter.start();
            errors=new Thread(new Runnable(){public void run(){
                byte[] buffer=new byte[128];int total=0;try{int n;while((n=child.getErrorStream().read(buffer))!=-1){total+=n;if(total>512){fail();break;}}}
                catch(IOException error){if(!closed)fail();}finally{java.util.Arrays.fill(buffer,(byte)0);}
            }},"vn-ap-station-errors");errors.setDaemon(true);errors.start();
            if(closed){stopProcess();return;}
            InputStream input=child.getInputStream();
            while(!closed&&!failed){
                byte[] frame=frame(input);if(frame==null)throw new IOException("AP observation pipe ended");
                try{
                    String text=new String(frame,"US-ASCII");
                    if(text.equals("format=0\nend\n")){snapshot=null;continue;}
                    if(!text.startsWith("format=2\n"))throw new IOException("AP observation requires generation");
                    ApStationSnapshot value=ApStationSnapshot.parse(frame);
                    if(!value.sessionToken.equals(token)||value.interfaceIndex!=index)throw new IOException("AP observation scope changed");
                    long age=clock.now()-value.sampledBoottimeMillis;
                    if(!closed&&!failed)snapshot=age>=0&&age<=2500?value:null;
                }finally{java.util.Arrays.fill(frame,(byte)0);}
            }
        }catch(IOException error){fail();}catch(RuntimeException error){fail();}
        finally{
            snapshot=null;stopProcess();if(process==null)exited.countDown();
            Process value=process;if(value!=null){try{value.getInputStream().close();}catch(IOException ignored){}try{value.getErrorStream().close();}catch(IOException ignored){}}
        }
    }
    private static byte[] frame(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int suffix=0;
        byte[] end=new byte[]{10,101,110,100,10};
        for(int n=0;n<384;n++){
            int value=in.read();if(value<0){if(n==0)return null;throw new IOException("Incomplete AP observation");}
            out.write(value);suffix=value==(end[suffix]&255)?suffix+1:(value==10?1:0);
            if(suffix==end.length)return out.toByteArray();
        }
        throw new IOException("AP observation too large");
    }
    public boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait bound");long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        join(worker,end);Thread a=errors,b=waiter;if(a!=null)join(a,end);if(b!=null)join(b,end);
        return !worker.isAlive()&&(a==null||!a.isAlive())&&(b==null||!b.isAlive())&&exited.getCount()==0;
    }
    private static void join(Thread thread,long end)throws InterruptedException{long left=end-System.nanoTime();if(left>0)thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));}
}
