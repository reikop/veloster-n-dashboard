package com.veloster.vehicle;

import android.app.Activity;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Queries the OEM restriction service without sending commands or changing OEM cached state. */
public final class RegulationState {
    private RegulationState(){}
    /** Unknown is distinct from OFF: the framework wrapper returns zero even when its service is missing. */
    public static int read(Activity activity){
        try{
            Class<?> type=Class.forName("com.hkmc.regulation.RegulationActivityManager");
            type.getMethod("getInstance",Context.class).invoke(null,activity);
            Field field=type.getDeclaredField("sService");field.setAccessible(true);
            Object service=field.get(null);
            if(service==null)return -1;
            Method status=service.getClass().getMethod("getStatus");status.setAccessible(true);
            Object value=status.invoke(service);
            if(!(value instanceof Integer))return -1;
            int result=((Integer)value).intValue();return result==0||result==1?result:-1;
        }catch(Exception e){return -1;}catch(LinkageError e){return -1;}
    }
}
