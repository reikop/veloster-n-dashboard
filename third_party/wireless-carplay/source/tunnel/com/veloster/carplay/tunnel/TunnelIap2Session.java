/* SPDX-License-Identifier: GPL-3.0-only. See tunnel/NOTICE.md. */
package com.veloster.carplay.tunnel;

import com.veloster.carplay.Iap2Csm;
import com.veloster.carplay.Iap2MessageSession;
import com.veloster.carplay.Iap2Stream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Owns the type130 tunnel and runs the Wi-Fi iAP2 link/CSM over it.
 * Does not reuse the Bluetooth bootstrap controller or claim MFi/media readiness. */
public final class TunnelIap2Session implements Iap2MessageSession {
    private final IapDataTunnel tunnel;
    private final Iap2Stream stream;

    public TunnelIap2Session(final IapDataTunnel tunnel) throws IOException {
        if(tunnel==null)throw new NullPointerException();
        tunnel.claimIap2();this.tunnel=tunnel;
        Closeable owner=new Closeable(){public void close()throws IOException{
            tunnel.close();
            try { if(!tunnel.protocolRetired()&&!tunnel.awaitStopped(2000))throw new IOException("DataStream worker did not stop"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw interrupted(e);}
        }};
        InputStream input=new InputStream(){
            public int read()throws IOException{
                byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;
            }
            public int read(byte[] target,int offset,int count)throws IOException{
                if(target==null)throw new NullPointerException();
                if(offset<0 || count<0 || offset>target.length-count)throw new IndexOutOfBoundsException();
                if(count==0)return 0;
                try {
                    while(true) {
                        byte[] bytes=tunnel.receive(Math.min(count,65535),250);
                        if(bytes==null)continue;
                        try {System.arraycopy(bytes,0,target,offset,bytes.length);return bytes.length;}
                        finally {Arrays.fill(bytes,(byte)0);}
                    }
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw interrupted(e);}
            }
        };
        OutputStream output=new OutputStream(){
            public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
            public void write(byte[] source,int offset,int count)throws IOException{
                if(source==null)throw new NullPointerException();
                if(offset<0 || count<0 || offset>source.length-count)throw new IndexOutOfBoundsException();
                int end=offset+count;
                while(offset<end) {
                    int n=Math.min(65535,end-offset);byte[] bytes=Arrays.copyOfRange(source,offset,offset+n);
                    try {tunnel.send(bytes,5000);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw interrupted(e);}
                    finally {Arrays.fill(bytes,(byte)0);}
                    offset+=n;
                }
            }
            // Each write waits for its event response; no buffered data remains on flush().
        };
        try { stream=Iap2Stream.openTunnel(input,output,owner); }
        catch(RuntimeException e){owner.close();throw new IOException("Could not open tunnel iAP2",e);}
        catch(LinkageError e){owner.close();throw new IOException("Tunnel iAP2 API unavailable",e);}
    }
    private static InterruptedIOException interrupted(InterruptedException cause) {
        InterruptedIOException e=new InterruptedIOException("Tunnel iAP2 interrupted");e.initCause(cause);return e;
    }
    public boolean awaitReady(long timeout)throws IOException,InterruptedException {
        tunnel.requireCurrent();boolean ready=stream.awaitReady(timeout);tunnel.requireCurrent();return ready;
    }
    public void send(Iap2Csm.Frame frame,long timeout)throws IOException,InterruptedException {
        tunnel.requireCurrent();stream.send(frame,timeout);tunnel.requireCurrent();
    }
    public Iap2Csm.Frame receive(long timeout)throws IOException,InterruptedException {
        tunnel.requireCurrent();Iap2Csm.Frame frame=stream.receive(timeout);tunnel.requireCurrent();return frame;
    }
    public boolean isClosed(){return stream.isClosed();}
    /** Nonblocking protocol cancellation; the runtime worker owns stream.close(). */
    public boolean retire(){return tunnel.retire();}
    public boolean awaitStopped(long timeout)throws InterruptedException{return stream.awaitStopped(timeout)&&tunnel.awaitStopped(0);}
    public void close()throws IOException{stream.close();}
}
