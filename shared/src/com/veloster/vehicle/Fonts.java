package com.veloster.vehicle;

import android.content.Context;
import android.graphics.Typeface;

/** Chakra Petch (SIL OFL 1.1, third_party/chakra-petch) for the floating window's skins, loaded once from res/raw.
 *  API 17 has no font resources: copied into the cache, then Typeface.createFromFile; null on any failure. */
public final class Fonts {
    private Fonts(){}
    public static volatile Typeface MEDIUM,BOLD;
    public static void load(Context c){
        if(MEDIUM!=null)return;
        MEDIUM=raw(c,"chakra_petch_medium");BOLD=raw(c,"chakra_petch_bold");
    }
    private static Typeface raw(Context c,String name){
        try{int id=c.getResources().getIdentifier(name,"raw",c.getPackageName());if(id==0)return null;
            java.io.File f=new java.io.File(c.getCacheDir(),name+".ttf");
            if(!f.isFile()||f.length()==0){java.io.InputStream in=c.getResources().openRawResource(id);java.io.FileOutputStream out=new java.io.FileOutputStream(f);
                try{byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)out.write(b,0,n);}finally{in.close();out.close();}}
            return Typeface.createFromFile(f);
        }catch(Throwable e){android.util.Log.w("VehicleDash","font "+name+": "+e);return null;}
    }
}
