/* SPDX-License-Identifier: GPL-3.0-only. Optional processing handles for one owned AudioRecord session. */
package com.veloster.carplay.media;
import java.io.*;

/** No global effect or session0. Failed configuration never loses its handle;
 * failed release keeps it owned for the capture worker's cleanup retry. */
public final class CaptureProcessing implements Closeable {
    public interface Effect {boolean enabled();boolean control();int enable();void release();}
    public interface Factory {Effect echo(int session);Effect noise(int session);}
    private final Effect[] effects=new Effect[2];private String status="not requested";private boolean prepared;
    public synchronized void prepare(Factory factory,int session){
        if(factory==null||session<1)throw new IllegalArgumentException("Owned capture session required");if(prepared)throw new IllegalStateException("Processing already prepared");prepared=true;
        String[] observed=new String[2];
        for(int i=0;i<2;i++)try{
            Effect effect=i==0?factory.echo(session):factory.noise(session);effects[i]=effect;
            if(effect==null)observed[i]="unavailable";
            else{boolean initial=effect.enabled();if(!effect.control())observed[i]="no-control(enabled="+initial+")";
                else{int result=initial?0:effect.enable();observed[i]="status="+result+",enabled="+effect.enabled()+",control="+effect.control();}}
        }catch(RuntimeException e){observed[i]="failed:"+e.getClass().getSimpleName();}catch(LinkageError e){observed[i]="api-unavailable";}
        status="AEC{"+observed[0]+"};NS{"+observed[1]+"}";
    }
    public synchronized String status(){return status;}
    public synchronized void close()throws IOException{
        IOException failed=null;
        for(int i=0;i<effects.length;i++)if(effects[i]!=null)try{effects[i].release();effects[i]=null;}
            catch(RuntimeException e){if(failed==null)failed=new IOException("Capture effect release failed",e);}
            catch(LinkageError e){if(failed==null)failed=new IOException("Capture effect release API failed",e);}
        if(failed!=null)throw failed;
    }
}
