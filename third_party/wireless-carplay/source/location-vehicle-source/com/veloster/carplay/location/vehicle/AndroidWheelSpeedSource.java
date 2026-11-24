/* SPDX-License-Identifier: GPL-3.0-only. Additional passive hscan_mux subscriber. */
package com.veloster.carplay.location.vehicle;

import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** No OutputStream, OEM service startup, ECU request, CAN transmission or GPS speed.
 * Create on a worker; close revokes queued samples and awaitStopped proves drain.
 * Disk hscand hash + root peer credentials do not authenticate the executing
 * peer binary: ordinary API17 UIDs cannot inspect a root peer's /proc/PID/exe.
 * This source alone cannot advertise PASCD: actual slip detection remains unknown. */
public final class AndroidWheelSpeedSource implements Closeable {
    public interface Listener {void sample(WheelSpeedSource.Snapshot sample);}
    interface Connection extends Closeable {InputStream input()throws IOException;String bootId();String producerIdentity();}
    interface Platform {Connection connect()throws IOException;long now();}
    private static final String HSCAND_SHA256="c4290363e12d759d7ae983d0744285c7fc84e8d67e16b015f64d5b9071c9f335";
    private final Platform platform;private final Listener listener;private final ArrayDeque<Chunk> queue=new ArrayDeque<Chunk>();
    private final CountDownLatch readerStopped=new CountDownLatch(1),decoderStopped=new CountDownLatch(1);private volatile boolean closed;
    private volatile Connection connection;private volatile WheelSpeedSource current;private volatile IOException failure;
    private volatile String bootId,producerIdentity;private long generation;private boolean started,readerStarted,decoderStarted;
    private Thread reader,decoder;
    private static final class Chunk {
        final WheelSpeedSource owner;final byte[] data;final long received,sequence;
        Chunk(WheelSpeedSource owner,byte[] source,int size,long received,long sequence){this.owner=owner;data=new byte[size];System.arraycopy(source,0,data,0,size);this.received=received;this.sequence=sequence;}
    }
    public static AndroidWheelSpeedSource create(Listener listener)throws IOException{
        if(Looper.getMainLooper()!=null&&Looper.myLooper()==Looper.getMainLooper())throw new IOException("Wheel source identity checks require worker");
        RealPlatform.verify();return new AndroidWheelSpeedSource(new RealPlatform(),listener);
    }
    /** Explicit test boundary: synthetic Platform does not establish OEM provenance. */
    AndroidWheelSpeedSource(Platform platform,Listener listener){if(platform==null||listener==null)throw new NullPointerException();this.platform=platform;this.listener=listener;}
    public synchronized void start()throws IOException{
        if(started||closed)throw new IOException("Wheel source already started/closed");started=true;
        reader=new Thread(new Runnable(){public void run(){receive();}},"vn-wheel-read");decoder=new Thread(new Runnable(){public void run(){decode();}},"vn-wheel-decode");reader.setDaemon(true);decoder.setDaemon(true);
        try{reader.start();readerStarted=true;decoder.start();decoderStarted=true;}
        catch(RuntimeException error){failure=new IOException("Wheel workers unavailable",error);close();throw failure;}
        catch(LinkageError error){failure=new IOException("Wheel worker API unavailable",error);close();throw failure;}
    }
    public WheelSpeedSource.Snapshot snapshot(long nowElapsedMillis){WheelSpeedSource owner=current;return closed||owner==null?null:owner.snapshot(bootId,nowElapsedMillis);}
    public boolean isCurrent(WheelSpeedSource.Snapshot sample,long nowElapsedMillis){WheelSpeedSource owner=current;return !closed&&owner!=null&&owner.isCurrent(sample,bootId,nowElapsedMillis);}
    /** Retained-sample validity for future batching. Only the live owner accepts; a
     * reconnect creates a new owner identity, so prior snapshots fail even with equal
     * boot/generation values. Queued latest-only callbacks must still use isCurrent. */
    public boolean isRetainedValid(WheelSpeedSource.Snapshot sample,long nowElapsedMillis){WheelSpeedSource owner=current;return !closed&&owner!=null&&owner.isRetainedValid(sample,bootId,nowElapsedMillis);}
    public String producerIdentity(){return producerIdentity;}
    public IOException failure(){return failure;}
    private void revoke(){WheelSpeedSource owner=current;if(owner!=null)owner.close();current=null;}
    private void receive(){byte[] buffer=new byte[4096];try{
        while(!closed){Connection opened=null;try{
            opened=platform.connect();connection=opened;if(closed)break;
            String boot=opened.bootId();if(boot==null||!boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Wheel boot identity unavailable");
            if(generation==Long.MAX_VALUE)throw new IOException("Wheel source generation exhausted");
            final WheelSpeedSource[] owner=new WheelSpeedSource[1];bootId=boot;producerIdentity=opened.producerIdentity();
            owner[0]=new WheelSpeedSource(boot,++generation,new WheelSpeedSource.Listener(){public void sample(WheelSpeedSource.Snapshot sample){
                if(!closed&&current==owner[0]&&owner[0].isCurrent(sample,bootId,platform.now()))listener.sample(sample);
            }});current=owner[0];if(closed){owner[0].close();break;}failure=null;long sequence=0;InputStream input=opened.input();
            while(!closed){int count;try{count=input.read(buffer);}catch(SocketTimeoutException idle){continue;}
                if(count<0)break;if(count==0)continue;if(sequence==Long.MAX_VALUE)throw new IOException("Wheel chunk sequence exhausted");
                Chunk chunk=new Chunk(owner[0],buffer,count,platform.now(),++sequence);
                synchronized(queue){if(closed)break;if(queue.size()==32)queue.removeFirst();queue.addLast(chunk);queue.notifyAll();}
            }
        }catch(IOException unavailable){if(!closed)failure=unavailable;}
        finally{revoke();synchronized(queue){queue.clear();}if(opened!=null)try{opened.close();}catch(IOException ignored){}if(connection==opened)connection=null;}
        if(!closed)try{Thread.sleep(2000);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}
        }
    }catch(RuntimeException error){failure=new IOException("Wheel receiver failed",error);}
     catch(LinkageError error){failure=new IOException("Wheel receive API failed",error);}
     finally{close();readerStopped.countDown();}}
    private void decode(){long previous=-1;WheelSpeedSource previousOwner=null;try{
        while(!closed){Chunk chunk;synchronized(queue){while(!closed&&queue.isEmpty())queue.wait();if(closed)break;chunk=queue.removeFirst();}
            if(chunk.owner!=current)continue;
            if(chunk.owner!=previousOwner||chunk.sequence!=previous+1)chunk.owner.discontinuity();previousOwner=chunk.owner;previous=chunk.sequence;
            long now=platform.now();if(now<chunk.received||now-chunk.received>WheelSpeedSource.MAX_AGE_MILLIS){chunk.owner.discontinuity();continue;}
            chunk.owner.accept(chunk.data,0,chunk.data.length,chunk.received);
        }
    }catch(InterruptedException stop){Thread.currentThread().interrupt();}
     catch(RuntimeException error){failure=new IOException("Wheel decoder/listener failed",error);}
     catch(LinkageError error){failure=new IOException("Wheel decoder API failed",error);}
     finally{close();decoderStopped.countDown();}}
    public void close(){Connection opened;Thread r,d;synchronized(this){if(!closed){closed=true;revoke();}opened=connection;connection=null;r=reader;d=decoder;
            if(!started){readerStopped.countDown();decoderStopped.countDown();}else{if(!readerStarted)readerStopped.countDown();if(!decoderStarted)decoderStopped.countDown();}}
        synchronized(queue){queue.clear();queue.notifyAll();}if(opened!=null)try{opened.close();}catch(IOException error){failure=new IOException("Wheel socket close unresolved",error);}
        if(r!=null)r.interrupt();if(d!=null)d.interrupt();
    }
    public boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException();if(!closed)return false;long began=System.nanoTime();if(!readerStopped.await(millis,TimeUnit.MILLISECONDS))return false;long left=Math.max(0,millis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));return decoderStopped.await(left,TimeUnit.MILLISECONDS);}
    private static final class RealPlatform implements Platform {
        static void verify()throws IOException{
            if(!"jsn_kr".equals(Build.PRODUCT)||!"eng.vmadmin.20250902.081552".equals(Build.VERSION.INCREMENTAL))throw new IOException("Wheel source unsupported OEM build");
            try{MessageDigest digest=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream("/system/bin/hscand");try{byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}finally{in.close();}
                StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.US,"%02x",value&255));if(!HSCAND_SHA256.equals(hex.toString()))throw new IOException("Wheel producer hash mismatch");
            }catch(IOException error){throw error;}catch(Exception error){throw new IOException("Wheel producer identity unavailable",error);}
        }
        public long now(){return SystemClock.elapsedRealtime();}
        public Connection connect()throws IOException{
            verify();final String boot=read("/proc/sys/kernel/random/boot_id",128).trim().toLowerCase(Locale.US);final LocalSocket socket=new LocalSocket();
            try{socket.connect(new LocalSocketAddress("hscan_mux",LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(500);
                Credentials peer=socket.getPeerCredentials();if(peer==null||peer.getUid()!=0||peer.getPid()<1)throw new IOException("Wheel peer credentials mismatch");
                // Peer PID is informational and scoped by this live socket/boot/generation.
                // Do not require root-only readlink as a condition for an ordinary app reader.
                final String identity=boot+":peer-uid0-pid"+peer.getPid();return new Connection(){public InputStream input()throws IOException{return socket.getInputStream();}public String bootId(){return boot;}public String producerIdentity(){return identity;}public void close()throws IOException{socket.close();}};
            }catch(IOException error){try{socket.close();}catch(IOException ignored){}throw error;}catch(RuntimeException error){try{socket.close();}catch(IOException ignored){}throw new IOException("Wheel socket identity unavailable",error);}
        }
        private static String read(String path,int limit)throws IOException{FileInputStream in=new FileInputStream(path);try{byte[] b=new byte[limit+1];int used=0,n;while(used<b.length&&(n=in.read(b,used,b.length-used))!=-1)used+=n;if(used>limit)throw new IOException("Wheel identity file too long");return new String(b,0,used,"US-ASCII");}finally{in.close();}}
    }
}
