/* SPDX-License-Identifier: GPL-3.0-only. Private user levels; no system-volume writes. */
package com.veloster.carplay.media;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Load on a worker, share across receiver sessions. Updates are memory-only on
 * the caller; one on-demand writer coalesces durable commits outside this lock.
 * A failed commit remains visible/pending and retries on the next change/retry.
 * Closing a UI must not close the application's shared store. */
public final class VolumeStore implements Closeable {
    public interface Storage {String read()throws IOException;boolean write(String encoded)throws IOException;}
    private static VolumeStore application;
    private final Storage storage;private int[] levels;private long revision,saved;
    private boolean closed,running;private IOException failure;private Object owner;
    public static synchronized VolumeStore open(Context supplied)throws IOException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Volume store requires worker initialization");
        if(application!=null){synchronized(application){if(!application.closed)return application;if(application.running||application.saved!=application.revision)throw new IOException("Volume save still pending");}}
        Context app=supplied.getApplicationContext();if(app==null)app=supplied;
        final SharedPreferences preferences=app.getSharedPreferences("carplay_output_volume",Context.MODE_PRIVATE);
        application=new VolumeStore(new Storage(){
            public String read()throws IOException{try{return preferences.getString("levels_v1",null);}catch(RuntimeException error){throw new IOException("Volume preferences unreadable",error);}}
            public boolean write(String value)throws IOException{try{return preferences.edit().putString("levels_v1",value).commit();}catch(RuntimeException error){throw new IOException("Volume preferences not saved",error);}}
        });return application;
    }
    public VolumeStore(Storage storage)throws IOException{
        if(storage==null)throw new NullPointerException();this.storage=storage;String value=storage.read();levels=decode(value);
    }
    public static VolumeStore memory(){try{return new VolumeStore(new Storage(){public String read(){return null;}public boolean write(String value){return true;}});}catch(IOException impossible){throw new AssertionError(impossible);}}
    public static final class Snapshot {
        private final int[] levels;public final long revision;public final boolean pending,saving;public final IOException failure;
        Snapshot(int[] levels,long revision,boolean pending,boolean saving,IOException failure){this.levels=levels.clone();this.revision=revision;this.pending=pending;this.saving=saving;this.failure=failure;}
        public int level(VolumePolicy.Context context){if(context==null)throw new NullPointerException();return levels[context.ordinal()];}
        int[] copyLevels(){return levels.clone();}
    }
    public synchronized Snapshot snapshot(){return new Snapshot(levels,revision,saved!=revision,running,failure);}
    synchronized boolean claim(Object value){if(closed||value==null)return false;owner=value;return true;}
    synchronized void release(Object value){if(owner==value)owner=null;}
    synchronized boolean owns(Object value){return !closed&&value!=null&&owner==value;}
    synchronized boolean set(Object supplied,VolumePolicy.Context context,int percent){return owns(supplied)&&set(context,percent);}
    /** Explicit profile-settings edit. Playback UI must use AudioPresentation's fenced ticket. */
    public synchronized boolean set(VolumePolicy.Context context,int percent){
        if(context==null)throw new NullPointerException();if(percent<0||percent>100)throw new IllegalArgumentException("Volume percent");
        if(closed)return false;if(levels[context.ordinal()]==percent){if(saved!=revision)startWriter();return true;}
        if(revision==Long.MAX_VALUE){failure=new IOException("Volume revision exhausted");return false;}
        int[] next=levels.clone();next[context.ordinal()]=percent;levels=next;revision++;startWriter();return true;
    }
    public synchronized void retry(){if(saved!=revision)startWriter();}
    private void startWriter(){
        if(running||saved==revision)return;running=true;
        try{Thread worker=new Thread(new Runnable(){public void run(){writeLoop();}},"vn-carplay-volume-save");worker.setDaemon(true);worker.start();}
        catch(RuntimeException error){running=false;failure=new IOException("Volume writer unavailable",error);notifyAll();}
        catch(LinkageError error){running=false;failure=new IOException("Volume writer API unavailable",error);notifyAll();}
    }
    private void writeLoop(){
        try{for(;;){String encoded;long writing;
            synchronized(this){if(saved==revision){running=false;notifyAll();return;}writing=revision;encoded=encode(levels);}
            boolean committed;try{committed=storage.write(encoded);}catch(RuntimeException error){throw new IOException("Volume commit failed",error);}catch(LinkageError error){throw new IOException("Volume storage API unavailable",error);}
            if(!committed)throw new IOException("Volume preferences not saved");
            synchronized(this){saved=writing;failure=null;}
        }}catch(IOException error){synchronized(this){failure=error;running=false;notifyAll();}}
    }
    public synchronized void close(){closed=true;owner=null;if(saved!=revision)startWriter();}
    /** Completion of writer ownership, not a claim that a failed commit succeeded. */
    public synchronized boolean awaitStopped(long millis)throws InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Volume wait");long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(running){long left=until-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);}return true;
    }
    private static String encode(int[] values){StringBuilder out=new StringBuilder("1");for(int value:values)out.append('|').append(value);return out.toString();}
    private static int[] decode(String text)throws IOException{
        int[] out=new int[VolumePolicy.Context.values().length];Arrays.fill(out,100);if(text==null)return out;
        String[] parts=text.split("\\|",-1);if(parts.length!=out.length+1||!parts[0].equals("1"))throw new IOException("Volume preferences invalid");
        for(int i=0;i<out.length;i++){String value=parts[i+1];if(!value.matches("0|[1-9][0-9]?|100"))throw new IOException("Volume preference bounds");out[i]=Integer.parseInt(value);}return out;
    }
}
