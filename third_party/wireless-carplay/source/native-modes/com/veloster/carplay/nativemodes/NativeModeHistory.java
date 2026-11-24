/* SPDX-License-Identifier: GPL-3.0-only. Continuous OEM producer history. */
package com.veloster.carplay.nativemodes;

/** History belongs to one boot and one OEM service lifetime. A disk choice is
 * eligible only against its exact producer checkpoint; lost lifetimes and later
 * ambiguous manual changes never become observed choices. */
public final class NativeModeHistory {
 public static final int UNKNOWN=0,NATIVE=1,CARPLAY=2;
 public static final class Snapshot {
  public final String boot,epoch;public final long sequence,nativeMediaIntent,unknownMedia,nativeScreenIntent,unknownScreen,mediaAt,unknownMediaAt,screenAt,unknownScreenAt,startedAt;public final boolean healthy;
  public Snapshot(String boot,String epoch,long startedAt,long sequence,long nativeMediaIntent,long unknownMedia,long nativeScreenIntent,long unknownScreen,long mediaAt,long unknownMediaAt,long screenAt,long unknownScreenAt,boolean healthy){
   if(!uuid(boot)||!uuid(epoch)||sequence<0||nativeMediaIntent<0||unknownMedia<0||nativeScreenIntent<0||unknownScreen<0||nativeMediaIntent>sequence||unknownMedia>sequence||nativeScreenIntent>sequence||unknownScreen>sequence)throw new IllegalArgumentException("OEM history snapshot");
   if(startedAt<0||mediaAt<0||unknownMediaAt<0||screenAt<0||unknownScreenAt<0)throw new IllegalArgumentException("OEM history timestamp");this.startedAt=startedAt;
   this.boot=boot;this.epoch=epoch;this.sequence=sequence;this.nativeMediaIntent=nativeMediaIntent;this.unknownMedia=unknownMedia;this.nativeScreenIntent=nativeScreenIntent;this.unknownScreen=unknownScreen;this.mediaAt=mediaAt;this.unknownMediaAt=unknownMediaAt;this.screenAt=screenAt;this.unknownScreenAt=unknownScreenAt;this.healthy=healthy;
  }
  /** actionAt is the actual explicit app action's shared OEM uptime, not the
   * later disk commit/read time. Same-millisecond conflicting actions are unknown. */
  public Checkpoint checkpoint(long actionAt){if(!healthy||actionAt<startedAt)throw new IllegalStateException("OEM history does not cover action");return new Checkpoint(boot,epoch,sequence,actionAt);}
  public int restore(Checkpoint saved,int choice,boolean media){
   if(choice!=NATIVE&&choice!=CARPLAY)throw new IllegalArgumentException("Explicit saved choice");if(!healthy)return UNKNOWN;
   long nativeIntent=media?nativeMediaIntent:nativeScreenIntent,unknown=media?unknownMedia:unknownScreen,nativeAt=media?mediaAt:screenAt,unknownAt=media?unknownMediaAt:unknownScreenAt;
   boolean identity=saved!=null&&boot.equals(saved.boot)&&epoch.equals(saved.epoch);if(identity&&(sequence<saved.sequence||saved.actionAt<startedAt))return UNKNOWN;
   boolean same=identity&&sequence>=saved.sequence;
   if(!same)return saved!=null&&nativeIntent>unknown&&(!boot.equals(saved.boot)||nativeAt>saved.actionAt)?NATIVE:UNKNOWN;
   if(unknown!=0&&unknownAt>=saved.actionAt&&unknown>=nativeIntent)return UNKNOWN;
   if(nativeIntent!=0&&nativeAt==saved.actionAt)return UNKNOWN;
   return nativeIntent!=0&&nativeAt>saved.actionAt?NATIVE:choice;
  }
 }
 public static final class Checkpoint {
  public final String boot,epoch;public final long sequence,actionAt;
  public Checkpoint(String boot,String epoch,long sequence,long actionAt){if(!uuid(boot)||!uuid(epoch)||sequence<0||actionAt<0)throw new IllegalArgumentException("OEM checkpoint");this.boot=boot;this.epoch=epoch;this.sequence=sequence;this.actionAt=actionAt;}
  public String encode(){return "1|"+boot+"|"+epoch+"|"+sequence+"|"+actionAt;}
  public static Checkpoint decode(String encoded){if(encoded==null)throw new IllegalArgumentException();String[] v=encoded.split("\\|",-1);if(v.length!=5||!v[0].equals("1")||!v[3].matches("0|[1-9][0-9]{0,18}")||!v[4].matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("OEM checkpoint encoding");return new Checkpoint(v[1],v[2],Long.parseLong(v[3]),Long.parseLong(v[4]));}
 }
 private final String boot,epoch;private final long startedAt;private long sequence,nativeMedia,unknownMedia,nativeScreen,unknownScreen,mediaAt,unknownMediaAt,screenAt,unknownScreenAt;private boolean healthy=true;
 private long request,at;private int mode,running,reason;private boolean accepted,automatic;private String acceptedPackage;
 public NativeModeHistory(String boot,String epoch){this(boot,epoch,0);}
 public NativeModeHistory(String boot,String epoch,long startedAt){if(!uuid(boot)||!uuid(epoch)||startedAt<0)throw new IllegalArgumentException();this.boot=boot;this.epoch=epoch;this.startedAt=startedAt;}
 private long next(){if(sequence==Long.MAX_VALUE){healthy=false;return sequence;}return ++sequence;}
 public synchronized void fail(){healthy=false;}
 public synchronized Snapshot snapshot(){return new Snapshot(boot,epoch,startedAt,sequence,nativeMedia,unknownMedia,nativeScreen,unknownScreen,mediaAt,unknownMediaAt,screenAt,unknownScreenAt,healthy);}
 public synchronized void request(int mode,int running,int reason,long now){if(now<startedAt){healthy=false;return;}request=next();this.mode=mode;this.running=running;this.reason=reason;at=now;accepted=false;acceptedPackage=null;}
 /** Capture while the OEM creates the exact delayed change Runnable. */
 public synchronized long token(int mode){return healthy&&this.mode==mode?request:0;}
 /** Result + flags are copied from that same Runnable after its original
  * Internal method returns. deviceId==0 never proves an audio route. */
 public synchronized void accepted(long token,int mode,int deviceId,String pkg,boolean auto,boolean audioChange,boolean success,long now){
  long event=next();if(now<0)healthy=false;if(!healthy||!success||deviceId==0)return;
  boolean qualified=token!=0&&token==request&&this.mode==mode&&now>=at&&now-at<=3000&&userReason(reason)&&NativeModeObserver.mediaMode(mode)&&matches(mode,pkg);
  if(token==request){accepted=qualified;automatic=auto;acceptedPackage=pkg;}
  if(!auto&&audioChange){if(qualified){nativeMedia=request;mediaAt=at;}else{unknownMedia=event;unknownMediaAt=now;}}
 }
 /** Every actual OEM activity resume is observed. Only an accepted qualified
  * foreground request establishes native screen; other resumes make process-gap
  * restoration unknown. Own app and temporary phone UI do not assert a choice. */
 public synchronized void visible(String pkg,long now){
  long event=next();if(now<0)healthy=false;if(!healthy)return;
  if("com.veloster.carplayhub".equals(pkg)||"com.android.phone".equals(pkg))return;
  boolean qualified=accepted&&!automatic&&running==1&&userReason(reason)&&now>=at&&now-at<=3000&&pkg!=null&&pkg.equals(acceptedPackage)&&matches(mode,pkg);
  if(qualified){nativeScreen=request;screenAt=at;}else{unknownScreen=event;unknownScreenAt=now;}
 }
 private static boolean userReason(int r){return r==3||r==6||r==8||r==9;}
 private static boolean matches(int mode,String pkg){if(pkg==null)return false;switch(mode){case 2:case 3:case 4:return pkg.equals("com.hkmc.av.app.radiokr")||pkg.equals("com.hkmc.av.app.radioge");case 0x31:return pkg.equals("com.daudio.av.app.usbmusic");case 0x32:return pkg.equals("com.daudio.av.app.usbvideo");case 0x34:return pkg.equals("com.daudio.av.app.ipodmusic");case 0x35:return pkg.equals("com.hkmc.av.app.AvAux.media");case 0x36:return pkg.equals("com.daudio.av.app.btaudio.ui");case 0x37:return pkg.equals("com.daudio.av.app.mymusic");case 0x81:return pkg.equals("com.hkmc.av.app.naturesound");default:return false;}}
 private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
}
