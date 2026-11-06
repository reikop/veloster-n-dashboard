/* SPDX-License-Identifier: GPL-3.0-only. Explicit choices and validated OEM history. */
package com.veloster.carplay.media;
import com.veloster.carplay.control.ModeChanges;
import com.veloster.carplay.nativemodes.NativeModeHistory;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Temporary ownership/focus/lifecycle never changes intent. Persisted choices
 * remain unobserved until an explicit action or exact healthy OEM history
 * establishes them. Screen and music checkpoints are independent. */
public final class LastUserModes {
 public static final int UNKNOWN=0,NATIVE=1,CARPLAY=2;
 public interface Storage {String read()throws IOException;boolean write(String value)throws IOException;}
 public interface Clock {long uptimeMillis();}
 private static final class Field {int choice;long revision,actionAt;boolean observed,explicit,fromHistory;NativeModeHistory.Checkpoint checkpoint;}
 public static final class Snapshot {
  public final int screen,media;public final long revision,screenRevision,mediaRevision,screenActionAt,mediaActionAt;
  public final boolean pending,screenObserved,mediaObserved;public final IOException failure;
  public final NativeModeHistory.Checkpoint screenCheckpoint,mediaCheckpoint;
  Snapshot(LastUserModes modes){Field s=modes.screen,m=modes.media;screen=s.choice;media=m.choice;revision=modes.revision;screenRevision=s.revision;mediaRevision=m.revision;screenActionAt=s.actionAt;mediaActionAt=m.actionAt;screenObserved=s.observed;mediaObserved=m.observed;screenCheckpoint=s.checkpoint;mediaCheckpoint=m.checkpoint;pending=modes.saved!=modes.storageRevision;failure=modes.failure;}
  public boolean showScreen(){return screen==CARPLAY&&screenObserved;}
  public boolean playMedia(){return media==CARPLAY&&mediaObserved;}
  public ModeChanges modes(){return new ModeChanges(effective(ModeChanges.SCREEN,screen,screenObserved),effective(ModeChanges.AUDIO,media,mediaObserved),null,null,null);}
 }
 private final Storage storage;private final Clock clock;private final long openedAt;private final Field screen=new Field(),media=new Field();private long revision,storageRevision,saved;private boolean writing;private IOException failure;private Object owner;
 public LastUserModes(Storage storage)throws IOException{this(storage,new Clock(){public long uptimeMillis(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());}});}
 public LastUserModes(Storage storage,Clock clock)throws IOException{
  if(storage==null||clock==null)throw new NullPointerException();this.storage=storage;this.clock=clock;openedAt=clock.uptimeMillis();if(openedAt<0)throw new IOException("Mode clock unavailable");String text=storage.read();if(text==null)return;
  if(text.matches("1\\|[0-2]\\|[0-2]")){screen.choice=text.charAt(2)-'0';media.choice=text.charAt(4)-'0';return;}
  try{String[] v=text.split(";",-1);if(v.length!=7||!v[0].equals("2")||!v[1].matches("[0-2]")||!v[2].matches("[0-2]")||!number(v[3])||!number(v[4]))throw new IllegalArgumentException();screen.choice=Integer.parseInt(v[1]);media.choice=Integer.parseInt(v[2]);screen.actionAt=Long.parseLong(v[3]);media.actionAt=Long.parseLong(v[4]);screen.checkpoint=decode(v[5],screen);media.checkpoint=decode(v[6],media);}
  catch(IllegalArgumentException e){throw new IOException("Last user modes invalid",e);}
 }
 private static boolean number(String value){return value.matches("0|[1-9][0-9]{0,18}");}
 /** Old versions write only v1. A projection mismatch or changed installation
  * lifetime makes remaining v2 checkpoints historical, even for same-value
  * selections an older writer cannot timestamp. */
 static String selectPersisted(String v2,String legacy,boolean sameInstallation){
  if(v2==null||!sameInstallation)return legacy;String[] v=v2.split(";",-1);
  if(v.length!=7||!v[0].equals("2")||!v[1].matches("[0-2]")||!v[2].matches("[0-2]"))return legacy;
  return ("1|"+v[1]+"|"+v[2]).equals(legacy)?v2:legacy;
 }
 private static NativeModeHistory.Checkpoint decode(String text,Field field){if(text.equals("-"))return null;NativeModeHistory.Checkpoint cp=NativeModeHistory.Checkpoint.decode(text);if(field.choice==UNKNOWN||cp.actionAt!=field.actionAt)throw new IllegalArgumentException("Mode checkpoint/action mismatch");return cp;}
 public synchronized Snapshot snapshot(){return new Snapshot(this);}
 public synchronized void claim(Object next){if(next==null)throw new NullPointerException();owner=next;}
 public synchronized void release(Object previous){if(owner==previous)owner=null;}
 public synchronized boolean owns(Object expected){return expected!=null&&owner==expected;}
 public synchronized boolean choose(Object expected,Integer nextScreen,Integer nextMedia){return owns(expected)&&choose(nextScreen,nextMedia);}
 public synchronized boolean choose(Integer nextScreen,Integer nextMedia){return chooseAt(nextScreen,nextMedia,clock.uptimeMillis());}
 public synchronized boolean chooseAt(Integer nextScreen,Integer nextMedia,long actionAt){
  if(nextScreen!=null)check(nextScreen);if(nextMedia!=null)check(nextMedia);if(actionAt<0)throw new IllegalArgumentException("Explicit choice uptime");if(nextScreen==null&&nextMedia==null)return false;
  if(revision==Long.MAX_VALUE||storageRevision==Long.MAX_VALUE)return false;revision++;
  if(nextScreen!=null)choose(screen,nextScreen,actionAt);if(nextMedia!=null)choose(media,nextMedia,actionAt);storageRevision++;scheduleSave();return true;
 }
 private void choose(Field field,int choice,long at){field.choice=choice;field.actionAt=at;field.revision=revision;field.observed=true;field.explicit=true;field.fromHistory=false;field.checkpoint=null;}
 /** Snapshot completion is fenced by each exact field revision. Returns true
  * only for effective intent/trust changes. Checkpoint-only persistence does not
  * advance changeModes revision. Null/unhealthy sources revoke history-derived
  * trust while a fresh explicit app action remains usable without optional OEM support. */
 public synchronized boolean applyHistory(NativeModeHistory.Snapshot history,long expectedScreenRevision,long expectedMediaRevision){
  if(revision==Long.MAX_VALUE||storageRevision==Long.MAX_VALUE)return false;
  int s=screen.revision==expectedScreenRevision?reconcile(screen,history,false):0;
  int m=media.revision==expectedMediaRevision?reconcile(media,history,true):0;
  boolean effective=(s&1)!=0||(m&1)!=0;
  if(effective){revision++;if((s&1)!=0)screen.revision=revision;if((m&1)!=0)media.revision=revision;}
  if((s&2)!=0||(m&2)!=0)storageRevision++;scheduleSave();return effective;
 }
 private int reconcile(Field field,NativeModeHistory.Snapshot h,boolean music){
  int beforeChoice=field.choice;boolean beforeObserved=field.observed;long beforeAt=field.actionAt;String beforeCp=encoded(field.checkpoint);
  if(h==null||!h.healthy){if(field.fromHistory){field.observed=false;field.fromHistory=false;}return beforeObserved!=field.observed?1:0;}
  long nativeAt=music?h.mediaAt:h.screenAt,nativeIntent=music?h.nativeMediaIntent:h.nativeScreenIntent,unknownAt=music?h.unknownMediaAt:h.unknownScreenAt,unknown=music?h.unknownMedia:h.unknownScreen;
  int next;
  if(field.explicit){
   if(unknown!=0&&unknownAt>=field.actionAt&&unknown>=nativeIntent)next=UNKNOWN;
   else if(nativeIntent!=0&&nativeAt==field.actionAt)next=UNKNOWN;
   else if(nativeIntent!=0&&nativeAt>field.actionAt)next=NATIVE;
   else if(field.actionAt<h.startedAt)next=UNKNOWN;
   else next=field.choice;
  }else if(field.checkpoint==null)next=nativeIntent>unknown&&nativeAt>openedAt?NATIVE:UNKNOWN;
  else next=h.restore(field.checkpoint,field.choice==UNKNOWN?NATIVE:field.choice,music);
  if(next==UNKNOWN){field.observed=false;field.fromHistory=false;}
  else {
   boolean newerNative=next==NATIVE&&nativeIntent!=0&&(field.checkpoint==null||!field.checkpoint.boot.equals(h.boot)||!field.checkpoint.epoch.equals(h.epoch)||nativeAt>field.actionAt);
   if(newerNative){field.actionAt=nativeAt;field.explicit=false;field.fromHistory=true;}
   else if(!field.observed){field.fromHistory=true;field.explicit=false;}
   field.choice=next;field.observed=true;
  }
  // Polling an empty producer never upgrades an unobserved old v1 disk value.
  if(field.choice!=UNKNOWN&&(field.explicit||field.observed)&&field.actionAt>=h.startedAt){
   NativeModeHistory.Checkpoint cp=field.checkpoint;
   if(cp==null||!cp.boot.equals(h.boot)||!cp.epoch.equals(h.epoch)||cp.actionAt!=field.actionAt)field.checkpoint=h.checkpoint(field.actionAt);
  }
  int result=(beforeChoice!=field.choice||beforeObserved!=field.observed||beforeAt!=field.actionAt)?1:0;
  if(beforeChoice!=field.choice||beforeAt!=field.actionAt||!beforeCp.equals(encoded(field.checkpoint)))result|=2;return result;
 }
 private static String encoded(NativeModeHistory.Checkpoint cp){return cp==null?"-":cp.encode();}
 private String encode(){return "2;"+screen.choice+";"+media.choice+";"+screen.actionAt+";"+media.actionAt+";"+encoded(screen.checkpoint)+";"+encoded(media.checkpoint);}
 private void scheduleSave(){if(saved==storageRevision||writing)return;writing=true;try{Thread thread=new Thread(new Runnable(){public void run(){save();}},"vn-carplay-modes-save");thread.setDaemon(true);thread.start();}catch(RuntimeException e){writing=false;failure=new IOException("Last user modes writer unavailable",e);notifyAll();}catch(LinkageError e){writing=false;failure=new IOException("Last user modes writer API unavailable",e);notifyAll();}}
 private void save(){try{for(;;){String text;long version;synchronized(this){if(saved==storageRevision){writing=false;notifyAll();return;}text=encode();version=storageRevision;}if(!storage.write(text))throw new IOException("Last user modes not saved");synchronized(this){saved=version;failure=null;}}}catch(IOException e){failed(e);}catch(RuntimeException e){failed(new IOException("Last user modes storage failed",e));}catch(LinkageError e){failed(new IOException("Last user modes storage unavailable",e));}}
 private synchronized void failed(IOException error){failure=error;writing=false;notifyAll();}
 public synchronized boolean awaitStopped(long millis)throws InterruptedException{if(millis<0)throw new IllegalArgumentException();long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(writing){long left=until-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);}return true;}
 private static void check(int value){if(value!=NATIVE&&value!=CARPLAY)throw new IllegalArgumentException("Explicit mode choice");}
 private static ModeChanges.Resource effective(int id,int choice,boolean observed){return observed?resource(id,choice):ModeChanges.Resource.take(id,ModeChanges.NICE,ModeChanges.USER_ONLY,ModeChanges.ANYTIME);}
 public static ModeChanges changes(int screen,int media){if(screen==UNKNOWN&&media==UNKNOWN)return null;return new ModeChanges(resource(ModeChanges.SCREEN,screen),resource(ModeChanges.AUDIO,media),null,null,null);}
 private static ModeChanges.Resource resource(int id,int choice){if(choice==UNKNOWN)return null;if(choice==CARPLAY)return ModeChanges.Resource.untake(id);if(choice==NATIVE)return ModeChanges.Resource.take(id,ModeChanges.USER,ModeChanges.USER_ONLY,ModeChanges.ANYTIME);throw new IllegalArgumentException("Mode choice");}
}
