/* SPDX-License-Identifier: GPL-3.0-only. Exact-listener DNS-SD publication lifecycle. */
package com.veloster.carplay.discovery;
import com.veloster.carplay.control.ReceiverMetadata;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.jmdns.*;
import javax.jmdns.impl.ServiceInfoImpl;

/** Owns one mDNS engine on the listener's observed local address. A new AP/owner
 * generation requires a new listener/metadata/publisher. No default-interface
 * discovery, radio enablement or pairing approval is performed here. */
public final class ReceiverDiscovery implements Closeable {
    /** Optional platform multicast filter lease; acquire must roll back on failure. */
    public interface MulticastLease {void acquire()throws IOException;void release();}
    private final ReceiverMetadata metadata;private final MulticastLease lease;
    private final Object signal=new Object();private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean closing,published;private volatile IOException failure;
    private volatile String registeredName,hostName;private volatile JmDNS dns;
    public ReceiverDiscovery(ReceiverMetadata metadata,MulticastLease lease)throws IOException{
        if(metadata==null)throw new NullPointerException();metadata.advertisement();this.metadata=metadata;this.lease=lease;
        Thread worker=new Thread(new Runnable(){public void run(){work();}},"vn-receiver-discovery");worker.setDaemon(true);worker.start();
    }
    private static boolean same(ReceiverMetadata.Advertisement a,ReceiverMetadata.Advertisement b){
        return a.address.equals(b.address)&&a.port==b.port&&a.serviceName.equals(b.serviceName)&&a.serviceType.equals(b.serviceType)&&Arrays.equals(a.txtBytes(),b.txtBytes());
    }
    private void failed(IOException error){synchronized(signal){if(!closing&&failure==null)failure=error;closing=true;published=false;signal.notifyAll();}}
    private void work(){
        JmDNS opened=null;boolean leased=false;
        try{
            if(lease!=null){lease.acquire();leased=true;}
            if(closing)return;
            final ReceiverMetadata.Advertisement initial=metadata.advertisement();
            opened=JmDNS.create(initial.address,"vn-"+initial.txt.get("deviceid").replace(":","").toLowerCase(Locale.US));dns=opened;
            opened.setDelegate(new JmDNS.Delegate(){public void cannotRecoverFromIOError(JmDNS source,Collection<ServiceInfo> infos){
                if(source==dns)failed(new IOException("Receiver multicast engine failed"));
            }});
            if(closing)return;
            if(!same(initial,metadata.advertisement())||!initial.address.equals(opened.getInetAddress()))throw new IOException("Receiver changed while opening discovery");
            ServiceInfoImpl service=(ServiceInfoImpl)ServiceInfo.create(initial.serviceType,initial.serviceName,initial.port,0,0,true,initial.txtBytes());
            opened.registerService(service);
            while(!closing){
                if(!same(initial,metadata.advertisement())||!initial.address.equals(opened.getInetAddress()))throw new IOException("Receiver discovery owner or endpoint changed");
                synchronized(signal){
                    if(closing)break;
                    published=service.isAnnounced();registeredName=published?service.getName():null;hostName=published?service.getServer():null;
                    signal.notifyAll();signal.wait(100);
                }
            }
        }catch(IOException e){failed(e);}
         catch(InterruptedException e){failed(new IOException("Receiver discovery interrupted",e));}
         catch(RuntimeException e){failed(new IOException("Receiver discovery failed",e));}
         catch(LinkageError e){failed(new IOException("Receiver discovery API unavailable",e));}
        finally{
            synchronized(signal){closing=true;published=false;signal.notifyAll();}
            Thread.interrupted(); // Let the engine complete goodbye/cancellation waits.
            try{if(opened!=null)opened.close();}
            catch(IOException e){if(failure==null)failure=e;}
            catch(RuntimeException e){if(failure==null)failure=new IOException("Receiver discovery cleanup failed",e);}
            catch(LinkageError e){if(failure==null)failure=new IOException("Receiver discovery cleanup API unavailable",e);}
            finally{
                try{if(leased)lease.release();}
                catch(RuntimeException e){if(failure==null)failure=new IOException("Receiver multicast lease release failed",e);}
                catch(LinkageError e){if(failure==null)failure=new IOException("Receiver multicast lease release API unavailable",e);}
                finally{dns=null;stopped.countDown();synchronized(signal){signal.notifyAll();}}
            }
        }
    }
    /** Announced means the engine completed probing/announcement; it is not a phone observation. */
    public boolean awaitPublished(long millis)throws IOException,InterruptedException{
        if(millis<0)throw new IllegalArgumentException("Wait duration");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized(signal){while(!published&&!closing){long remaining=deadline-System.nanoTime();if(remaining<=0)return false;TimeUnit.NANOSECONDS.timedWait(signal,remaining);}
            if(failure!=null)throw failure;return published&&!closing;}
    }
    public String registeredName(){return published?registeredName:null;}
    public String hostName(){return published?hostName:null;}
    public IOException failure(){return failure;}
    public void close(){synchronized(signal){closing=true;published=false;signal.notifyAll();}}
    /** Wait for this worker's engine close and multicast-lease release. */
    public boolean awaitStopped(long millis)throws InterruptedException{return stopped.await(millis,TimeUnit.MILLISECONDS);}
}
