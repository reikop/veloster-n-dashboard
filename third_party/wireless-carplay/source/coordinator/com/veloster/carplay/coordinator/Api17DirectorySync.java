package com.veloster.carplay.coordinator;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;

/** API17 libcore Os (pre-android.system.Os). Missing/denied fsync stops BEGIN. */
public final class Api17DirectorySync implements SessionJournal.DirectorySync {
    public void sync(File directory)throws IOException {
        Object os=null;Method close=null;FileDescriptor descriptor=null;
        try {
            Class<?> api=Class.forName("libcore.io.Os"),constants=Class.forName("libcore.io.OsConstants");
            os=Class.forName("libcore.io.Libcore").getField("os").get(null);
            int flags=constants.getField("O_RDONLY").getInt(null)|constants.getField("O_NOFOLLOW").getInt(null);
            close=api.getMethod("close",FileDescriptor.class);
            descriptor=(FileDescriptor)api.getMethod("open",String.class,Integer.TYPE,Integer.TYPE).invoke(os,directory.getPath(),flags,0);
            Object stat=api.getMethod("fstat",FileDescriptor.class).invoke(os,descriptor);
            int mode=stat.getClass().getField("st_mode").getInt(stat);
            if(!Boolean.TRUE.equals(constants.getMethod("S_ISDIR",Integer.TYPE).invoke(null,mode)))throw new IOException();
            api.getMethod("fsync",FileDescriptor.class).invoke(os,descriptor);
        } catch(Exception unavailable){throw new IOException("Journal directory synchronization unavailable");}
        finally{if(descriptor!=null && close!=null)try{close.invoke(os,descriptor);}catch(Exception ignored){}}
    }
}
