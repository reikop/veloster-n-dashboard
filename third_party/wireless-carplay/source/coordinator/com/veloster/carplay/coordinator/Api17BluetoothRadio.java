/* SPDX-License-Identifier: GPL-3.0-only. Public API17 Bluetooth adapter plus durable private journal. */
package com.veloster.carplay.coordinator;
import android.bluetooth.BluetoothAdapter;
import android.os.Looper;
import java.io.*;

public final class Api17BluetoothRadio {
    private Api17BluetoothRadio(){}
    private static void worker()throws IOException{if(Looper.myLooper()!=null&&Looper.myLooper()==Looper.getMainLooper())throw new IOException("Bluetooth radio commands require a worker");}
    static BluetoothRadioHandoff.Radio radio(){return new BluetoothRadioHandoff.Radio(){
        private BluetoothAdapter adapter()throws IOException{BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();if(a==null)throw new IOException("Bluetooth adapter unavailable");return a;}
        public int state()throws IOException{try{return adapter().getState();}catch(RuntimeException e){throw new IOException("Bluetooth state unavailable",e);}catch(LinkageError e){throw new IOException("Bluetooth state API unavailable",e);}}
        public boolean disable()throws IOException{worker();try{return adapter().disable();}catch(RuntimeException e){throw new IOException("Bluetooth OFF unavailable",e);}catch(LinkageError e){throw new IOException("Bluetooth OFF API unavailable",e);}}
        public boolean enable()throws IOException{worker();try{return adapter().enable();}catch(RuntimeException e){throw new IOException("Bluetooth restore unavailable",e);}catch(LinkageError e){throw new IOException("Bluetooth restore API unavailable",e);}}
    };}
    private static BluetoothRadioHandoff.Journal openJournal(File parent)throws IOException{
        File directory=new File(parent,"bluetooth-radio").getAbsoluteFile();if(!directory.equals(directory.getCanonicalFile()))throw new IOException("Private Bluetooth journal path required");
        if(!directory.isDirectory()&&!directory.mkdir())throw new IOException("Bluetooth journal directory unavailable");new Api17DirectorySync().sync(parent);
        return new BluetoothRadioJournal(directory,new Api17DirectorySync());
    }

    public static BluetoothRadioHandoff open(File parent,String ap,String phone,long generation,BluetoothRadioHandoff.Current current,long timeout)throws IOException{
        worker();BluetoothRadioHandoff.Journal journal=openJournal(parent);boolean handed=false;
        try{BluetoothRadioHandoff result=new BluetoothRadioHandoff(ap,phone,generation,current,radio(),journal,ProcessOwner.current(),timeout);handed=true;return result;}
        finally{if(!handed)journal.close();}
    }
    public static void recover(File parent)throws IOException{
        worker();String[] names=parent.list();if(names==null)throw new IOException("Bluetooth journal parent unavailable");boolean found=false;
        for(String name:names)if(name.equals("bluetooth-radio")){found=true;break;}if(!found)return;
        BluetoothRadioHandoff.Journal journal=openJournal(parent);try{BluetoothRadioHandoff.recover(journal,radio(),ProcessOwner.linux());}finally{journal.close();}
    }
}
