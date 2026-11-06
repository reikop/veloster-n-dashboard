/* SPDX-License-Identifier: GPL-3.0-only. Private app choices; no OEM settings writes. */
package com.veloster.carplay.media;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
public final class AndroidLastUserModes {
    private AndroidLastUserModes(){}
    public static LastUserModes open(Context context)throws IOException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Worker required");
        final SharedPreferences prefs=context.getSharedPreferences("carplay_last_user_modes",Context.MODE_PRIVATE);
        long observedInstall;try{observedInstall=context.getPackageManager().getPackageInfo(context.getPackageName(),0).lastUpdateTime;}catch(Exception unavailable){observedInstall=-1;}
        final long installation=observedInstall;
        return new LastUserModes(new LastUserModes.Storage(){
            public String read()throws IOException{try{return LastUserModes.selectPersisted(prefs.getString("choices_v2",null),prefs.getString("choices_v1",null),installation>0&&prefs.getLong("choices_v2_install",-1)==installation);}catch(RuntimeException e){throw new IOException("Last user modes unreadable",e);}}
            public boolean write(String value){String[] fields=value.split(";",-1);if(fields.length!=7||!fields[0].equals("2"))return false;return prefs.edit().putString("choices_v2",value).putString("choices_v1","1|"+fields[1]+"|"+fields[2]).putLong("choices_v2_install",installation).commit();}
        },new LastUserModes.Clock(){public long uptimeMillis(){return SystemClock.uptimeMillis();}});
    }
}
