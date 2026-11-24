/* SPDX-License-Identifier: GPL-3.0-only. See TRANSPORT-NOTICE.md. */
package com.veloster.carplay;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, cancellable, secure outbound RFCOMM for ONE explicitly selected bond.
 * No discovery, pairing, profile disconnection or global Bluetooth toggling.
 */
public final class RfcommConnector implements Closeable {
    public interface SocketHandle extends Closeable {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
    }
    public interface Backend {
        boolean enabled() throws IOException;
        boolean bonded(String address) throws IOException;
        String localAddress() throws IOException;
        SocketHandle secureSocket(String address,UUID uuid) throws IOException;
    }
    public static final class Connected implements Closeable {
        public final String selectedAddress,localAddress;
        public final Iap2Stream stream;
        private Connected(String peer,String local,Iap2Stream stream) {
            selectedAddress=peer;localAddress=local;this.stream=stream;
        }
        public void close() throws IOException {stream.close();}
    }
    private final Backend backend;
    private final Object lock=new Object();
    private SocketHandle active;
    private boolean attempted,closed;
    public RfcommConnector(Backend backend) {if(backend==null)throw new NullPointerException();this.backend=backend;}
    public static String normalizeAddress(String address) {
        if(address==null || !address.matches("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))throw new IllegalArgumentException("Invalid Bluetooth address");
        String value=address.toUpperCase(Locale.US);
        if(value.equals("00:00:00:00:00:00") || value.equals("FF:FF:FF:FF:FF:FF"))throw new IllegalArgumentException("Unusable Bluetooth address");
        return value;
    }
    public Connected connect(String selectedAddress,long timeoutMillis) throws IOException,InterruptedException {
        final String peer=normalizeAddress(selectedAddress);
        if(timeoutMillis<1 || timeoutMillis>120000)throw new IllegalArgumentException("Invalid connect deadline");
        synchronized(lock){if(closed || attempted)throw new IOException("Connector already used");attempted=true;}
        if(!backend.enabled() || !backend.bonded(peer))throw new IOException("Selected device is not an available Bluetooth bond");
        String local=normalizeAddress(backend.localAddress());
        final SocketHandle socket=backend.secureSocket(peer,UUID.fromString(Iap2Link.SERVICE_UUID));
        if(socket==null)throw new IOException("Bluetooth socket unavailable");
        synchronized(lock){if(closed){socket.close();throw new IOException("Connection cancelled");}active=socket;}
        FutureTask<Void> task=new FutureTask<Void>(new Callable<Void>() {
            public Void call() throws IOException {socket.connect();return null;}
        });
        Thread thread=new Thread(task,"vn-rfcomm-connect");thread.setDaemon(true);thread.start();
        boolean delivered=false;
        try {
            task.get(timeoutMillis,TimeUnit.MILLISECONDS);
            if(!backend.enabled() || !backend.bonded(peer))throw new IOException("Selected bond changed during connect");
            synchronized(lock){if(closed)throw new IOException("Connection cancelled");}
            Iap2Stream stream=new Iap2Stream(socket.input(),socket.output(),new Closeable() {
                public void close() throws IOException {socket.close();}
            });
            Connected result=new Connected(peer,local,stream);delivered=true;return result;
        } catch(TimeoutException e){throw new IOException("RFCOMM connection timed out");}
        catch(ExecutionException e){throw new IOException("Secure RFCOMM connection failed",e.getCause());}
        finally {
            if(!delivered) {
                task.cancel(true);
                try{close();}finally{thread.interrupt();}
                // Closing the socket is the cancellation mechanism, not Thread.interrupt.
                // Avoid an unlimited join if an OEM socket violates that contract.
                if(!Thread.currentThread().isInterrupted())thread.join(2000);
            }
        }
    }
    public void close() throws IOException {
        SocketHandle socket;
        synchronized(lock){closed=true;socket=active;active=null;}
        if(socket!=null)socket.close();
    }
}
