package com.veloster.vehicle;

/** Merges partial OEM widget updates. Times are received milliseconds, never extrapolated. */
public final class MediaState {
    public static final String[] ACTIONS={"com.hkmc.audio.usb.widgetupdate","com.hkmc.audio.btaudio.widgetupdate",
        "com.hkmc.audio.ipod.widgetupdate","com.hkmc.dipoaudio.widgetupdate","com.hkmc.gpmaudio.widgetupdate"};
    private static final String[] SOURCES={"USB","Bluetooth","iPod","CarPlay","Android Auto"};
    private static final int[] MODES={0x31,0x36,0x34,0x73,0x74};
    public static int modeForSource(String source){for(int i=0;i<SOURCES.length;i++)if(SOURCES[i].equals(source))return MODES[i];return -1;}
    private static final long SETTLE_MS=600,NOTICE_MS=5000;
    private final Track[] tracks=new Track[ACTIONS.length];
    private int active=-1;
    private boolean modeKnown;
    private String announced="";
    private long pendingAt=-1,noticeUntil;
    private long artSerial;
    private static final class Track {
        String title="",artist="";
        int playState=-1;
        long position=-1,duration=-1,timeAt=-1;
        String artworkUri="";byte[] artworkBytes;long artworkRevision;
    }
    public static final class Snapshot {
        public final String source,title,artist;
        public final long positionMs,durationMs,timeAgeMs;
        public final boolean notice;
        public final long noticeAgeMs,artworkRevision;
        public final String artworkUri;
        public final byte[] artworkBytes;
        public final int playState;
        private Snapshot(String s,String t,String a,long p,long d,long age,boolean n,long noticeAge,Track track){
            source=s;title=t;artist=a;positionMs=p;durationMs=d;timeAgeMs=age;notice=n;
            noticeAgeMs=noticeAge;artworkRevision=track==null?0:track.artworkRevision;
            artworkUri=track==null?"":track.artworkUri;artworkBytes=track==null?null:track.artworkBytes;
            playState=track==null?-1:track.playState;
        }
        public boolean hasProgress(){return positionMs>=0 && durationMs>0;}
        public float progress(){return hasProgress()?Math.min(1f,positionMs/(float)durationMs):0;}
    }
    /** State-only updates neither invent progress nor trigger a track-change popup. */
    public synchronized void playback(int source,int state){
        if(source<0||source>=tracks.length||state< -1||state>1)return;
        if(tracks[source]==null)tracks[source]=new Track();
        tracks[source].playState=state;
    }
    public synchronized void dismissNotice(){
        if(active>=0&&tracks[active]!=null)announced=active+"\n"+tracks[active].title+"\n"+tracks[active].artist;
        pendingAt=-1;noticeUntil=0;
    }
    public static int source(String action){for(int i=0;i<ACTIONS.length;i++)if(ACTIONS[i].equals(action))return i;return -1;}
    public synchronized void mode(int mode){
        int next=mode==0x31?0:mode==0x36?1:mode==0x34?2:mode==0x73?3:mode==0x74?4:-1;
        if(!modeKnown || active!=next){
            active=next;pendingAt=-1;noticeUntil=0;announced="";
            if(next>=0){tracks[next]=new Track();tracks[next].artworkRevision=++artSerial;}
        }
        modeKnown=true;
    }
    private static String clean(String value){
        if(value==null)return "";
        StringBuilder b=new StringBuilder();
        for(int i=0;i<value.length() && b.length()<256;i++){
            char c=value.charAt(i);b.append(Character.isISOControl(c)?' ':c);
        }
        return b.toString().trim();
    }
    public synchronized void update(int source,boolean hasTitle,String title,boolean hasArtist,String artist,
            boolean hasPosition,long position,boolean hasDuration,long duration,boolean invisible,long now){
        if(source<0 || source>=tracks.length)return;
        Track t=tracks[source];if(t==null)tracks[source]=t=new Track();
        if(invisible){
            tracks[source]=new Track();
            if(active==source){if(!modeKnown)active=-1;pendingAt=-1;noticeUntil=0;announced="";}
            return;
        }
        String before=t.title+"\n"+t.artist;
        if(hasTitle){
            String next=clean(title);
            if(!t.title.equals(next)){
                // A new track must not inherit the previous track's elapsed time or artist.
                t.position=-1;t.duration=-1;t.timeAt=-1;
                t.artworkUri="";t.artworkBytes=null;t.artworkRevision=++artSerial;
                if(!hasArtist)t.artist="";
            }
            t.title=next;
        }
        if(hasArtist)t.artist=clean(artist);
        if(hasPosition){t.position=position>=0?position:-1;t.timeAt=now;}
        if(hasDuration)t.duration=duration>0?duration:-1;
        boolean switched=!modeKnown && hasTitle && t.title.length()>0 && active!=source;
        if(switched)active=source;
        if(active==source){
            if(t.title.length()==0){pendingAt=-1;noticeUntil=0;announced="";}
            else if(switched || !before.equals(t.title+"\n"+t.artist))pendingAt=now;
        }
    }
    public synchronized void artwork(int source,boolean hasUri,String uri,byte[] bytes,boolean clear){
        if(source<0||source>=tracks.length)return;
        Track t=tracks[source];if(t==null)tracks[source]=t=new Track();
        if(clear){t.artworkUri="";t.artworkBytes=null;t.artworkRevision=++artSerial;return;}
        if(bytes!=null && bytes.length>0 && bytes.length<=2*1024*1024){
            if(!java.util.Arrays.equals(t.artworkBytes,bytes)){
                t.artworkBytes=bytes.clone();t.artworkUri="";t.artworkRevision=++artSerial;
            }
        }else if(hasUri){
            String next=uri==null||uri.length()>2048?"":uri.trim();
            if(source==2 && "image".equals(next))next="/data/data/com.daudio.av.app.ipodmusic/files/image";
            if(source==3 && "artwork_update".equals(next))next="/data/data/com.daudio.app.dipo/files/image";
            if(source==4 && "/data/data/com.google.android.projection.sink/files/gpm_artwork".equals(next))next="/data/data/com.google.android.projection.sink/files/image";
            // OEM reuses the same image pathname for subsequent covers.
            t.artworkUri=next;t.artworkBytes=null;t.artworkRevision=++artSerial;
        }
    }
    public synchronized Snapshot snapshot(long now){
        if(active<0)return new Snapshot("","","",-1,-1,-1,false,0,null);
        Track t=tracks[active];
        if(pendingAt>=0 && now-pendingAt>=SETTLE_MS){
            String key=active+"\n"+t.title+"\n"+t.artist;
            if(!announced.equals(key)){announced=key;noticeUntil=pendingAt+SETTLE_MS+NOTICE_MS;}
            pendingAt=-1;
        }
        return new Snapshot(SOURCES[active],t.title,t.artist,t.position,t.duration,
            t.timeAt<0?-1:Math.max(0,now-t.timeAt),t.title.length()>0 && now<noticeUntil,
            Math.max(0,now-(noticeUntil-NOTICE_MS)),t);
    }
    public static String time(long ms){
        if(ms<0)return "—";
        long seconds=ms/1000;return seconds/60+":"+(seconds%60<10?"0":"")+seconds%60;
    }
}
