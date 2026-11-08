/* SPDX-License-Identifier: GPL-3.0-only. One owner/AP/selected peer per listener. */
package com.veloster.carplay.control;
import com.veloster.carplay.pairing.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Single live control connection, local-address/peer filtering and bounded admission.
 * Context must come from actual coordinator/AP/phone observations; an IP is not identity proof.
 * A new owner/AP generation requires a new listener. Does not publish mDNS or open a radio. */
public final class ControlListener implements Closeable {
    public interface Connection {
        /** Called after this exact object is retained by the listener. */
        void start()throws IOException;
        void close();boolean awaitStopped(long millis)throws InterruptedException;
    }
    public interface ConnectionFactory {
        /** Construct an inert owner; allocate only in start so cancellation can retain it. */
        Connection create(Socket socket,ReceiverAdmission.Peer peer)throws IOException;
    }
    private final ServerSocket server;
    private final ReceiverScope scope;
    private final ReceiverAdmission admission;
    private final PairingStore store;
    private final ControlConnection.ApprovalUi ui;
    private final ControlConnection.Handler handler;
    private final ConnectionFactory connections;
    private final CountDownLatch stopped=new CountDownLatch(1);
    private volatile boolean closed;
    private volatile Connection active;
    private long window=System.nanoTime(),lastAttempt;private int attempts;
    public ControlListener(PairingStore store,PairVerify.Context context,PairVerify.OwnerLease owner,
        ControlConnection.ApprovalUi ui,ControlConnection.Handler handler,int port)throws IOException{
        this(store,new ReceiverAdmission.Fixed(context,owner),ui,handler,port);
    }
    public ControlListener(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi ui,ControlConnection.Handler handler,int port)throws IOException{
        this(store,admission,ui,handler,port,null);
    }
    public ControlListener(PairingStore store,ReceiverAdmission admission,
        ControlConnection.ApprovalUi ui,ControlConnection.Handler handler,int port,ConnectionFactory connections)throws IOException{
        if(store==null||admission==null||ui==null||handler==null)throw new NullPointerException();
        scope=admission.scope();if(scope==null)throw new NullPointerException();
        if(port<0||port>65535)throw new IOException("Invalid control listener port");
        this.store=store;this.admission=admission;this.ui=ui;this.handler=handler;
        this.connections=connections;
        checkAdmission();store.identityIdentifier();server=new ServerSocket();
        boolean ok=false;
        try{server.setReuseAddress(false);server.bind(new InetSocketAddress(scope.localAddress,port),1);server.setSoTimeout(250);ok=true;}
        finally{if(!ok)server.close();}
        Thread thread=new Thread(new Runnable(){public void run(){acceptLoop();}},"vn-control-listener");thread.setDaemon(true);
        try{thread.start();}catch(RuntimeException e){close();stopped.countDown();throw new IOException("Listener worker unavailable",e);}
    }
    private void checkAdmission()throws IOException{if(admission.scope()!=scope)throw new IOException("Receiver scope replaced");admission.checkCurrent();}
    public int port(){return server.getLocalPort();}
    ReceiverScope metadataScope(PairingStore expected)throws IOException{
        if(expected!=store||closed||server.isClosed()||!server.isBound())throw new IOException("Receiver endpoint unavailable");
        checkAdmission();store.identityIdentifier();
        NetworkInterface iface=NetworkInterface.getByInetAddress(scope.localAddress);
        if(iface==null||!scope.interfaceName.equals(iface.getName()))throw new IOException("Receiver interface changed");
        if(closed||server.isClosed())throw new IOException("Receiver endpoint closed");return scope;
    }
    private void acceptLoop(){
        try{
            while(!closed){
                checkAdmission();Socket socket;
                try{socket=server.accept();}catch(SocketTimeoutException e){continue;}
                boolean owned=false;
                try{
                    if(closed)continue;
                    Connection previous=active;
                    if(previous!=null&&!previous.awaitStopped(0))continue;
                    final ReceiverAdmission.Peer peer=admission.admit(socket.getInetAddress());
                    if(peer==null)continue;
                    if(!scope.contains(peer.context)||!socket.getInetAddress().equals(peer.context.remoteAddress))continue;
                    final PairVerify.Context context=peer.context;
                    PairVerify.OwnerLease owner=new PairVerify.PhoneIdentityLease(){public void checkCurrent(PairVerify.Context value)throws IOException{
                        if(closed||value!=context)throw new IOException("Control generation closed");
                        checkAdmission();peer.lease.checkCurrent(value);
                    }
                    public void verificationComplete(PairVerify.Context value)throws IOException{
                        checkCurrent(value);if(peer.lease instanceof PairVerify.VerifiedOwnerLease)((PairVerify.VerifiedOwnerLease)peer.lease).verificationComplete(value);
                    }
                    public boolean mediaAllowed(PairVerify.Context value)throws IOException{
                        checkCurrent(value);return !(peer.lease instanceof PairVerify.PhoneIdentityLease)||((PairVerify.PhoneIdentityLease)peer.lease).mediaAllowed(value);
                    }
                    public void transportIdentifiers(PairVerify.Context value,String bt,String usb)throws IOException{
                        checkCurrent(value);if(peer.lease instanceof PairVerify.PhoneIdentityLease)((PairVerify.PhoneIdentityLease)peer.lease).transportIdentifiers(value,bt,usb);
                    }};
                    long now=System.nanoTime();if(now-window>=60000000000L){window=now;attempts=0;}
                    if(attempts>=5||lastAttempt!=0&&now-lastAttempt<1000000000L)continue;
                    attempts++;lastAttempt=now;
                    final ControlConnection legacy=connections==null?new ControlConnection(socket,store,context,owner,ui,handler):null;
                    Connection connection=connections==null?new Connection(){
                        public void start(){Thread worker=new Thread(legacy,"vn-control-connection");worker.setDaemon(true);worker.start();}
                        public void close(){legacy.close();}
                        public boolean awaitStopped(long millis)throws InterruptedException{return legacy.awaitStopped(millis);}
                    }:connections.create(socket,new ReceiverAdmission.Peer(context,owner));
                    if(connection==null)throw new IOException("Connection factory returned no owner");
                    synchronized(this){active=connection;if(closed){connection.close();continue;}}
                    try{connection.start();owned=true;}catch(IOException e){connection.close();throw e;}
                    catch(RuntimeException e){connection.close();throw e;}
                }catch(IOException denied){/* A stale/rejected peer cannot withdraw a healthy advertisement. */}
                finally{if(!owned)socket.close();}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
         catch(IOException ignored){}catch(RuntimeException ignored){}catch(LinkageError ignored){}
        finally{close();stopped.countDown();}
    }
    public synchronized void close(){if(closed)return;closed=true;try{server.close();}catch(IOException ignored){}
        Connection connection=active;if(connection!=null)connection.close();}
    /** Both accept loop and the last accepted connection must terminate. Store is caller-owned. */
    public boolean awaitStopped(long millis)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        if(!stopped.await(millis,TimeUnit.MILLISECONDS))return false;
        Connection connection=active;return connection==null||connection.awaitStopped(Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
    }
}
