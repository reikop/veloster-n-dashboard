package com.veloster.carplayhub;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.widget.*;
import android.view.View;
import com.veloster.i18n.*;
import com.veloster.carplayhub.update.*;
import java.io.*;
import java.util.*;

/** Explicit foreground actions only. No auto-download, root commands or firmware updates. */
public final class UpdateActivity extends Activity {
 private static final String[] ROOTS={"/storage/usb0","/storage/usb1","/storage/sdcard0","/storage/sdcard1","/storage/extSdCard","/mnt/usb0","/mnt/usb1","/mnt/sdcard","/sdcard"};
 private AndroidUpdate updater;private LinearLayout content;private TextView status;private final List<Button> buttons=new ArrayList<Button>();
 private volatile Task task;private boolean foreground;private AlertDialog dialog;private AndroidUpdate.Prepared prepared;
 private static final class Task implements UpdateFiles.Cancellation {volatile boolean cancelled;AndroidUpdate.Prepared ready;Intent intent;public boolean cancelled(){return cancelled;}}
 private interface Work {String run(Task task)throws Exception;}
 public void onCreate(Bundle state){super.onCreate(state);LanguageSettings.init(this);updater=new AndroidUpdate(this);updater.cleanup();draw();}
 private void draw(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,8,16,8);setContentView(root);
  TextView title=new TextView(this);title.setText(UiText.t("앱 업데이트"));title.setTextSize(23);root.addView(title);
  ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);root.addView(scroll);
  TextView help=new TextView(this);help.setText(UiText.t("정차 중 사용하세요. 확인한 새 버전만 다운로드하며 설치는 Android 설치 화면에서 진행합니다. USB/SD에서도 업데이트 파일을 읽을 수 있습니다."));content.addView(help);
  status=new TextView(this);status.setTextSize(17);status.setPadding(0,12,0,12);content.addView(status);
  action("N 계기판 업데이트 확인",new Runnable(){public void run(){online(UpdateManifest.DASHBOARD);}});
  action("내 앱 업데이트 확인",new Runnable(){public void run(){online(UpdateManifest.HUB);}});
  action("저장된 업데이트 찾기",new Runnable(){public void run(){chooseSaved();}});
  action("연결 진단 저장",new Runnable(){public void run(){diagnose();}});
  action("USB/SD 업데이트 찾기",new Runnable(){public void run(){chooseLocal();}});
  Button cancel=new Button(this);cancel.setText(UiText.t("취소"));cancel.setOnClickListener(new View.OnClickListener(){public void onClick(View v){cancel();status.setText(UiText.t("업데이트 작업을 취소했습니다."));}});content.addView(cancel);
  Button close=new Button(this);close.setText(UiText.t("돌아가기"));close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){finish();}});content.addView(close);
 }
 private void action(String label,final Runnable run){Button b=new Button(this);b.setText(UiText.t(label));b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(task==null)run.run();}});buttons.add(b);content.addView(b);}
 private void enabled(boolean value){for(Button b:buttons)b.setEnabled(value);}
 private void cancel(){Task old=task;if(old!=null)old.cancelled=true;task=null;enabled(true);if(dialog!=null){dialog.dismiss();dialog=null;}if(prepared!=null){updater.discard(prepared.file);prepared=null;}}
 protected void onPause(){foreground=false;cancel();super.onPause();}
 protected void onResume(){super.onResume();foreground=true;work(new Work(){public String run(Task t)throws Exception{String result=updater.reconcile();updater.pruneSaved(t);return result.equals("pending")?UiText.t("이전 설치의 완료를 확인하지 못했습니다. 취소했거나 설치가 아직 끝나지 않았을 수 있습니다."):result.length()>0?UiText.t("설치된 파일 확인 완료: ")+result:UiText.t("업데이트 확인 또는 USB/SD 가져오기를 선택하세요.");}});}
 private void work(final Work work){
  if(task!=null||!foreground)return;final Task current=new Task();task=current;enabled(false);status.setText(UiText.t("확인 중…"));
  new Thread(new Runnable(){public void run(){String message;try{message=work.run(current);}catch(FileNotFoundException e){message=UiText.t("업데이트 파일이 없습니다. 온라인 배포가 아직 없거나 USB/SD 경로를 확인해야 합니다.");}catch(Exception e){com.veloster.diagnostics.ErrorLog.record("app_update",e);message=UiText.t("업데이트를 완료하지 못했습니다. 연결·기기 호환성·파일 서명·버전을 확인하세요.");}
   final String shown=message;runOnUiThread(new Runnable(){public void run(){
    if(task!=current||current.cancelled||!foreground){if(current.ready!=null)updater.discard(current.ready.file);return;}
    task=null;enabled(true);status.setText(shown);
    if(current.ready!=null)showOffer(current.ready);
    if(current.intent!=null)try{startActivity(current.intent);}catch(RuntimeException e){status.setText(UiText.t("설치 화면을 열지 못했습니다. 기존 ADB 설치기를 사용하세요."));com.veloster.diagnostics.ErrorLog.record("update_installer",e);}
   }});
  }},"vn-update").start();
 }
 private void offer(AndroidUpdate.Prepared value,Task current){current.ready=value;}
 private void showOffer(final AndroidUpdate.Prepared value){
   prepared=value;enabled(false);
   dialog=new AlertDialog.Builder(UpdateActivity.this).setTitle(UiText.t("검증된 업데이트"))
    .setMessage(value.manifest.packageName+"\nv"+value.manifest.version+"\n"+UiText.t("서명과 파일을 확인했습니다. 정차 상태에서 설치 화면을 여세요. 취소해도 저장 파일은 유지됩니다."))
    .setPositiveButton(UiText.t("설치 화면 열기"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){prepared=null;launch(value);}})
    .setNegativeButton(UiText.t("취소"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){updater.discard(value.file);prepared=null;enabled(true);}})
    .setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){updater.discard(value.file);prepared=null;enabled(true);}}).create();dialog.show();
 }
 private void launch(final AndroidUpdate.Prepared value){
  // Prepare handoff off the UI thread, then require the same foreground session.
  if(task!=null||!foreground){updater.discard(value.file);return;}
  work(new Work(){public String run(final Task t)throws Exception{
   try{UpdateFiles.alive(t);Intent intent=updater.installer(value,t);UpdateFiles.alive(t);t.intent=intent;return UiText.t("설치 요청 후 돌아오면 설치된 파일을 다시 확인합니다.");}finally{updater.discard(value.file);}
  }});
 }
 private void chooseSaved(){
  dialog=new AlertDialog.Builder(this).setTitle(UiText.t("저장된 업데이트"))
   .setItems(new String[]{UiText.t("N 계기판"),UiText.t("내 앱")},new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
    final String name=which==0?UpdateManifest.DASHBOARD:UpdateManifest.HUB;
    work(new Work(){public String run(Task t)throws Exception{AndroidUpdate.Prepared held=updater.saved(name,t);if(held==null)return UiText.t("저장된 새 업데이트가 없습니다.");offer(held,t);return UiText.t("저장된 업데이트를 다시 검증했습니다. 인터넷 없이 설치할 수 있습니다.");}});
   }}).setNegativeButton(UiText.t("취소"),null).create();dialog.show();
 }
 private void online(final String packageName){
  work(new Work(){public String run(final Task t)throws Exception{
   AndroidUpdate.Prepared held=updater.saved(packageName,t);
   try{
    UpdateNetwork.requireLocalConnection(UpdateActivity.this);final UpdateTransport net=AndroidTls.transport(UpdateActivity.this,new UpdateTransport.Policy(){public void check()throws IOException{UpdateNetwork.requireLocalConnection(UpdateActivity.this);}});String feed=UpdateTransport.feed(packageName);
    byte[] data=net.bytes(feed+"manifest.txt",4096,t),signature=net.bytes(feed+"manifest.sig",256,t);
    final UpdateManifest m=updater.metadata(data,signature,packageName);
    if(held!=null&&(held.manifest.version>m.version||held.manifest.sha256.equals(m.sha256))){offer(held,t);held=null;return UiText.t("저장된 업데이트를 다시 검증했습니다. 인터넷 없이 설치할 수 있습니다.");}
    final File stage=updater.newStage();boolean success=false;
    try{net.fetch(m.url,t,new UpdateTransport.Reader<Void>(){public Void read(InputStream in)throws Exception{UpdateFiles.copy(in,stage,m,t);return null;}});AndroidUpdate.Prepared value=updater.finish(stage,m,t);updater.remember(value,data,signature,t);offer(value,t);success=true;return UiText.t("새 버전 검증이 끝났습니다. 나중에 설치할 수 있도록 저장했습니다.");}finally{if(!success)updater.discard(stage);}
   }catch(Exception failure){
    UpdateFiles.alive(t);if(held==null)throw failure;offer(held,t);held=null;return UiText.t("온라인 확인에 실패했습니다. 검증된 저장 파일로 설치할 수 있습니다.");
   }finally{if(held!=null)updater.discard(held.file);}
  }});
 }
 private void diagnose(){
  work(new Work(){public String run(Task t)throws Exception{
   NetworkSnapshot snapshot=NetworkSnapshot.capture();UpdateFiles.alive(t);
   File base=getExternalFilesDir(null);if(base==null)base=getFilesDir();
   File folder=new File(base,"network-diagnostic");snapshot.save(folder);
   return UiText.t("연결 진단을 저장했습니다. 연결 설정은 바꾸지 않았습니다.\n")+folder.getPath()+"\n"+snapshot.summary;
  }});
 }
 private void chooseLocal(){
  final List<File> bundles=new ArrayList<File>();final List<String> labels=new ArrayList<String>();Set<String> seen=new HashSet<String>();
  for(String root:ROOTS)for(String name:new String[]{"dashboard","hub"})try{File bundle=new File(root,"vn-update/"+name).getCanonicalFile();if(new File(bundle,"manifest.txt").isFile()&&seen.add(bundle.getPath())){bundles.add(bundle);labels.add(bundle.getPath());}}catch(IOException ignored){}
  if(bundles.isEmpty()){status.setText(UiText.t("USB/SD의 vn-update/dashboard 또는 vn-update/hub 폴더에 업데이트 파일을 넣으세요."));return;}
  dialog=new AlertDialog.Builder(this).setTitle(UiText.t("USB/SD 업데이트")) .setItems(labels.toArray(new String[labels.size()]),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){local(bundles.get(which));}}).setNegativeButton(UiText.t("취소"),null).create();dialog.show();
 }
 private void local(final File bundle){
  work(new Work(){public String run(Task t)throws Exception{
   String selected=bundle.getName().equals("dashboard")?UpdateManifest.DASHBOARD:UpdateManifest.HUB;
   byte[] data=UpdateFiles.read(new File(bundle,"manifest.txt"),4096),signature=UpdateFiles.read(new File(bundle,"manifest.sig"),256);
   UpdateManifest m=updater.metadata(data,signature,selected);
   File stage=updater.newStage();boolean success=false;try{InputStream in=new FileInputStream(new File(bundle,"package.apk"));try{UpdateFiles.copy(in,stage,m,t);}finally{in.close();}AndroidUpdate.Prepared value=updater.finish(stage,m,t);updater.remember(value,data,signature,t);offer(value,t);success=true;return UiText.t("새 버전 검증이 끝났습니다. 나중에 설치할 수 있도록 저장했습니다.");}finally{if(!success)updater.discard(stage);}
  }});
 }
}
