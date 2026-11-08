package com.veloster.carplayhub;
import com.veloster.carplay.coordinator.ReceiverEngineProvider;
/** Optional implementation is packaged by the reviewed combined build, never downloaded at runtime. */
public final class ReceiverEngines {
    private ReceiverEngines(){}
    public static ReceiverEngineProvider packaged(){
        try{return (ReceiverEngineProvider)Class.forName("com.veloster.diplayprobe.JsnHubEngine").newInstance();}
        catch(ClassNotFoundException absent){return null;}
        catch(Exception failure){throw new IllegalStateException("Packaged receiver engine cannot be loaded",failure);}
    }
}
