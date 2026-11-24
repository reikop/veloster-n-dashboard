/* SPDX-License-Identifier: GPL-3.0-only. App-owned CarPlay output gain, not OEM volume. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.AudioDescription;
import com.veloster.carplay.control.ModeState;

/** Contexts follow Apple WWDC2016/723 volume guidance. Category mapping is local:
 * alternate is announcement/navigation; main alert shares the bell/alert level.
 * phoneCall ownership alone does not distinguish ringing from an active call. */
public final class VolumePolicy {
    private VolumePolicy(){}
    public enum Context {MEDIA,NAVIGATION,SIRI,RINGTONE,CALL}
    public static Context context(AudioDescription description,ModeState state){
        if(description==null||state==null)throw new NullPointerException();
        if(description.type==101)return Context.NAVIGATION;
        String name=description.audioType;
        if(name.equals("speechRecognition"))return Context.SIRI;
        if(name.equals("telephony"))return Context.CALL;
        if(name.equals("alert"))return Context.RINGTONE;
        if(name.equals("default")){
            if(state.phoneCall==ModeState.PHONE)return Context.CALL;
            if(state.speechOwner==ModeState.PHONE&&state.speechMode>0)return Context.SIRI;
        }
        return Context.MEDIA;
    }
    static int priority(Context value){switch(value){case NAVIGATION:return 5;case RINGTONE:return 4;case CALL:return 3;case SIRI:return 2;default:return 1;}}
    /** Immutable UI ticket. Its private identity and revision fence active-context changes. */
    public static final class Snapshot {
        private final Object owner;private final int[] levels;
        public final Context activeContext;public final int percent;
        public final long contextRevision,storeRevision;public final boolean saving,pending;public final String saveError;
        Snapshot(Object owner,Context context,long revision,VolumeStore.Snapshot stored){
            this.owner=owner;activeContext=context;contextRevision=revision;storeRevision=stored.revision;
            levels=stored.copyLevels();percent=context==null?-1:levels[context.ordinal()];saving=stored.saving;pending=stored.pending;saveError=stored.failure==null?"":String.valueOf(stored.failure.getMessage());
        }
        boolean current(Object supplied,Context context,long revision){return owner!=null&&owner==supplied&&activeContext==context&&context!=null&&contextRevision==revision;}
        public int level(Context context){if(context==null)throw new NullPointerException();return levels[context.ordinal()];}
        public boolean available(){return owner!=null&&activeContext!=null;}
    }
}
