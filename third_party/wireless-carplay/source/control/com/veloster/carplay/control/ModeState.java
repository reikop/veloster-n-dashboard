/* SPDX-License-Identifier: GPL-3.0-only. CarPlay mode wire fields; see NOTICE.md. */
package com.veloster.carplay.control;
import java.io.IOException;
import java.util.*;

/** Observed ownership, never inferred from an accepted change request. Missing fields retain
 * their previous value; UNKNOWN means this connection has not received that state yet. */
public final class ModeState {
    public static final int UNKNOWN=-1,NONE=0,PHONE=1,ACCESSORY=2;
    public final int screen,audio,phoneCall,navigation,speechOwner,speechMode;
    public final long revision;
    /** Last change for each capture-relevant owner. Survives an A→B→A sequence. */
    public final long audioRevision,phoneRevision,speechRevision;
    private ModeState(int screen,int audio,int phone,int nav,int speech,int mode,long revision,long audioRevision,long phoneRevision,long speechRevision){
        this.screen=screen;this.audio=audio;phoneCall=phone;navigation=nav;speechOwner=speech;speechMode=mode;this.revision=revision;
        this.audioRevision=audioRevision;this.phoneRevision=phoneRevision;this.speechRevision=speechRevision;
    }
    public static ModeState unknown(){return new ModeState(UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN,0,0,0,0,0);}
    static int number(Object value,int min,int max)throws IOException{
        if(!(value instanceof Long)||((Long)value)<min||((Long)value)>max)throw new IOException("Invalid mode integer");
        return ((Long)value).intValue();
    }
    static List<?> list(Map<String,Object> dict,String key,int max)throws IOException{
        if(!dict.containsKey(key))return Collections.emptyList();Object value=dict.get(key);
        if(!(value instanceof List)||((List<?>)value).size()>max)throw new IOException("Invalid mode list");return (List<?>)value;
    }
    /** Validate the entire delta before publishing any field. */
    ModeState merge(Map<String,Object> delta)throws IOException{
        if(!delta.containsKey("resources")&&!delta.containsKey("appStates"))throw new IOException("Missing mode changes");
        int s=screen,a=audio,p=phoneCall,n=navigation,e=speechOwner,m=speechMode;
        boolean[] resources=new boolean[3],apps=new boolean[4];
        for(Object item:list(delta,"resources",2)){
            Map<String,Object> entry=BinaryPlist.dictionary(item);int id=number(entry.get("resourceID"),1,2);
            if(resources[id])throw new IOException("Duplicate mode resource");resources[id]=true;
            int entity=number(entry.get("entity"),0,2);if(id==1)s=entity;else a=entity;
        }
        for(Object item:list(delta,"appStates",3)){
            Map<String,Object> entry=BinaryPlist.dictionary(item);int id=number(entry.get("appStateID"),1,3);
            if(apps[id])throw new IOException("Duplicate mode app state");apps[id]=true;
            int entity=number(entry.get("entity"),0,2);
            if(id==1){e=entity;m=number(entry.get("speechMode"),-1,2);}else if(id==2)p=entity;else n=entity;
        }
        Object reason=delta.get("reasonStr");if(reason!=null&&(!(reason instanceof String)||((String)reason).length()>1024))throw new IOException("Invalid mode reason");
        if(s==screen&&a==audio&&p==phoneCall&&n==navigation&&e==speechOwner&&m==speechMode)return this;
        if(revision==Long.MAX_VALUE)throw new IOException("Mode revision exhausted");long next=revision+1;
        return new ModeState(s,a,p,n,e,m,next,a!=audio?next:audioRevision,p!=phoneCall?next:phoneRevision,e!=speechOwner||m!=speechMode?next:speechRevision);
    }
}
