/* SPDX-License-Identifier: GPL-3.0-only. Typed outbound CarPlay mode requests. */
package com.veloster.carplay.control;
import java.util.*;

/** Accessory intent. Ownership is decided by the controller and reported separately. */
public final class ModeChanges {
    public static final int SCREEN=1,AUDIO=2,TAKE=1,UNTAKE=2,BORROW=3,UNBORROW=4;
    public static final int NICE=100,USER=500,ANYTIME=100,USER_ONLY=500,NEVER=1000;
    public static final class Resource {
        private final int id,type,priority,take,other;
        private Resource(int id,int type,int priority,int take,int other){
            if(id!=SCREEN&&id!=AUDIO)throw new IllegalArgumentException("Resource ID");
            if(type==TAKE||type==BORROW){
                if(priority!=NICE&&priority!=USER)throw new IllegalArgumentException("Transfer priority");
                constraint(other);if(type==TAKE)constraint(take);
            }
            this.id=id;this.type=type;this.priority=priority;this.take=take;this.other=other;
        }
        private static void constraint(int value){if(value!=ANYTIME&&value!=USER_ONLY&&value!=NEVER)throw new IllegalArgumentException("Transfer constraint");}
        public static Resource take(int id,int priority,int takeConstraint,int borrowConstraint){return new Resource(id,TAKE,priority,takeConstraint,borrowConstraint);}
        public static Resource borrow(int id,int priority,int unborrowConstraint){return new Resource(id,BORROW,priority,0,unborrowConstraint);}
        public static Resource untake(int id){return new Resource(id,UNTAKE,0,0,0);}
        public static Resource unborrow(int id){return new Resource(id,UNBORROW,0,0,0);}
        private Map<String,Object> wire(){
            Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("resourceID",id);out.put("transferType",type);
            if(type==TAKE||type==BORROW)out.put("transferPriority",priority);
            if(type==TAKE){out.put("takeConstraint",take);out.put("borrowConstraint",other);}
            if(type==BORROW)out.put("unborrowConstraint",other);return out;
        }
    }
    private final Resource screen,audio;
    private final Integer speech;
    private final Boolean phone,navigation;
    public ModeChanges(Resource screen,Resource audio,Integer speech,Boolean phone,Boolean navigation){
        if(screen!=null&&screen.id!=SCREEN||audio!=null&&audio.id!=AUDIO)throw new IllegalArgumentException("Resource slot");
        if(speech!=null&&speech!=-1&&speech!=1&&speech!=2)throw new IllegalArgumentException("Speech mode");
        if(screen==null&&audio==null&&speech==null&&phone==null&&navigation==null)throw new IllegalArgumentException("Empty mode request");
        this.screen=screen;this.audio=audio;this.speech=speech;this.phone=phone;this.navigation=navigation;
    }
    /** Fresh nested containers: callers cannot mutate a later command or /info declaration. */
    public Map<String,Object> wire(){
        Map<String,Object> out=new LinkedHashMap<String,Object>();List<Object> resources=new ArrayList<Object>(),apps=new ArrayList<Object>();
        if(screen!=null)resources.add(screen.wire());if(audio!=null)resources.add(audio.wire());
        if(speech!=null)apps.add(app(1,"speechMode",speech));if(phone!=null)apps.add(app(2,"state",phone));if(navigation!=null)apps.add(app(3,"state",navigation));
        if(!resources.isEmpty())out.put("resources",resources);if(!apps.isEmpty())out.put("appStates",apps);return out;
    }
    private static Map<String,Object> app(int id,String key,Object value){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("appStateID",id);out.put(key,value);return out;}
}
