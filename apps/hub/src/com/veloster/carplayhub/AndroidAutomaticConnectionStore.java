package com.veloster.carplayhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.*;

/** App-private persistence; never substitutes a process ID for an Android boot. */
final class AndroidAutomaticConnectionStore {
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Automatic state I/O requires worker");}
    static AutomaticConnectionState open(Context context)throws IOException{
        worker();final SharedPreferences preferences=context.getSharedPreferences("wireless_carplay_automatic",Context.MODE_PRIVATE);
        String boot;InputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id");
        try{byte[] data=new byte[80];int count=in.read(data);if(count<1||count==data.length)throw new IOException("Android boot identity size");boot=new String(data,0,count,"US-ASCII").trim();}finally{in.close();}
        return new AutomaticConnectionState(new AutomaticConnectionState.Store(){
            public String read()throws IOException{worker();try{return preferences.getString("intent_v1",null);}catch(RuntimeException e){throw new IOException("Automatic state read failed",e);}}
            public boolean write(String record)throws IOException{worker();try{return preferences.edit().putString("intent_v1",record).commit();}catch(RuntimeException e){throw new IOException("Automatic state write failed",e);}}
        },boot);
    }
}
