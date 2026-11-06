package com.veloster.carplayhub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.veloster.i18n.LanguageSettings;
import com.veloster.i18n.UiText;
import com.veloster.carplayhub.update.AndroidUsbWifi;
import com.veloster.carplayhub.wifi.*;
import java.io.File;
import java.util.*;

/** Explicit controls for the separately installed USB Wi-Fi support kit. */
public final class UsbWifiActivity extends Activity {
 private TextView status,remoteStatus,internetStatus;private EditText ssid,password,terminalPassword;private final List<Button> actions=new ArrayList<Button>();private boolean busy;
 public void onCreate(Bundle saved){
  super.onCreate(saved);LanguageSettings.init(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setFocusableInTouchMode(true);root.requestFocus();root.setPadding(16,8,16,8);
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  TextView title=new TextView(this);title.setText(UiText.t("USB Wi-Fi"));title.setTextSize(24);root.addView(title);
  TextView help=new TextView(this);help.setText(UiText.t("처음에는 USB Wi-Fi 지원 도구 설치가 필요합니다. 휴대폰 핫스팟이나 공유기의 WPA2 네트워크에 연결하세요."));root.addView(help);
  ssid=new EditText(this);ssid.setSingleLine(true);ssid.setHint(UiText.t("네트워크 이름"));ssid.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);ssid.setSaveEnabled(false);ssid.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_NEXT);root.addView(ssid);
  password=new EditText(this);password.setSingleLine(true);password.setHint(UiText.t("Wi-Fi 비밀번호"));password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);password.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_DONE);root.addView(password);
  LinearLayout buttons=new LinearLayout(this);root.addView(buttons);
  add(buttons,"저장 후 연결",new Runnable(){public void run(){connectNew();}});
  add(buttons,"저장된 네트워크 연결",new Runnable(){public void run(){perform(1,null,null);}});
  add(buttons,"연결 해제",new Runnable(){public void run(){perform(2,null,null);}});
  status=new TextView(this);status.setTextSize(18);root.addView(status);
  LinearLayout more=new LinearLayout(this);root.addView(more);
  add(more,"상태 새로고침",new Runnable(){public void run(){refresh();}});
  add(more,"HTTPS 검사",new Runnable(){public void run(){startActivity(new Intent(UsbWifiActivity.this,InternetCheckActivity.class));}});
  add(more,"앱 업데이트",new Runnable(){public void run(){startActivity(new Intent(UsbWifiActivity.this,UpdateActivity.class));}});
  add(more,"닫기",new Runnable(){public void run(){finish();}});
  TextView internetHelp=new TextView(this);internetHelp.setText(UiText.t("다른 앱 인터넷 · Wi-Fi 연결 후 켜세요. 연결이 끊기면 이전 설정으로 복원합니다. 일부 앱은 연결을 인식하지 못하거나 구형 HTTPS 때문에 작동하지 않을 수 있습니다."));root.addView(internetHelp);
  LinearLayout internetButtons=new LinearLayout(this);root.addView(internetButtons);
  add(internetButtons,"다른 앱 인터넷 켜기",new Runnable(){public void run(){internet(0);}});
  add(internetButtons,"인터넷 설정 복원",new Runnable(){public void run(){internet(1);}});
  add(internetButtons,"인터넷 상태",new Runnable(){public void run(){internet(2);}});
  internetStatus=new TextView(this);internetStatus.setTextSize(16);root.addView(internetStatus);
  TextView remoteHelp=new TextView(this);remoteHelp.setText(UiText.t("Wi-Fi SSH 터미널 · 루트 권한\n접속 비밀번호를 저장한 뒤 켜세요. 직접 끄거나 Wi-Fi 연결이 끊길 때 종료됩니다."));root.addView(remoteHelp);
  terminalPassword=new EditText(this);terminalPassword.setSingleLine(true);terminalPassword.setHint(UiText.t("터미널 비밀번호 (8자 이상)"));terminalPassword.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);terminalPassword.setSaveEnabled(false);terminalPassword.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_ACTION_DONE);root.addView(terminalPassword);
  LinearLayout remoteButtons=new LinearLayout(this);root.addView(remoteButtons);
  add(remoteButtons,"비밀번호 저장",new Runnable(){public void run(){remote(3);}});
  add(remoteButtons,"터미널 켜기",new Runnable(){public void run(){remote(0);}});
  add(remoteButtons,"터미널 끄기",new Runnable(){public void run(){remote(1);}});
  add(remoteButtons,"접속 정보",new Runnable(){public void run(){remote(2);}});
  remoteStatus=new TextView(this);remoteStatus.setTextSize(16);remoteStatus.setTextIsSelectable(true);root.addView(remoteStatus);refresh();
 }
 private void add(LinearLayout root,String label,final Runnable task){Button b=new Button(this);b.setText(UiText.t(label));root.addView(b,new LinearLayout.LayoutParams(0,56,1));actions.add(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View view){if(!busy)task.run();}});}
 private void connectNew(){String name=ssid.getText().toString();char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);password.setText("");perform(0,name,secret);}
 private void perform(final int operation,final String name,final char[] secret){
  busy=true;for(Button button:actions)button.setEnabled(false);ssid.setEnabled(false);password.setEnabled(false);status.setText(UiText.t("처리 중…"));
  new Thread(new Runnable(){public void run(){
   String result;
   try{UsbWifiClient client=new UsbWifiClient();if(operation==0){byte[] encoded=UsbWifiCredentials.encode(name,secret);try{client.stop();client.configure(encoded);}finally{Arrays.fill(encoded,(byte)0);}}if(operation==2){client.stop();result="연결을 해제했습니다.";}else{client.start();result="연결을 시작했습니다. 잠시 후 상태를 새로고침하세요.";}}
   catch(java.security.GeneralSecurityException invalid){result="네트워크 이름과 비밀번호를 확인하세요. 비밀번호는 영문·숫자·기호 8~63자입니다.";}
   catch(Exception failure){result="연결 작업을 완료하지 못했습니다. 지원 도구 설치와 동글 연결을 확인하세요.";}
   finally{if(secret!=null)Arrays.fill(secret,'\0');}
   final String message=result;runOnUiThread(new Runnable(){public void run(){if(isFinishing())return;busy=false;for(Button button:actions)button.setEnabled(true);ssid.setEnabled(true);password.setEnabled(true);status.setText(UiText.t(message));}});
  }},"usb-wifi-control").start();
 }
 private void internet(final int action){
  busy=true;for(Button b:actions)b.setEnabled(false);internetStatus.setText(UiText.t("처리 중…"));
  new Thread(new Runnable(){public void run(){String message;
   try{
    UsbWifiClient client=new UsbWifiClient();if(action==0)client.internet("start");else if(action==1)client.internet("stop");
    String state=client.internet("status");
    if("on".equals(state))message=UiText.t("다른 앱용 인터넷 설정 적용됨 · 실제 인터넷 접속은 앱에서 확인하세요.");
    else if("off".equals(state))message=UiText.t("다른 앱용 인터넷 설정 꺼짐");
    else if("restore-needed".equals(state))message=UiText.t("이전 설정 복원이 필요합니다. 인터넷 설정 복원을 누르세요.");
    else throw new java.io.IOException("Internet status");
   }catch(Exception failure){message=UiText.t("인터넷 설정 작업을 완료하지 못했습니다. Wi-Fi 연결과 지원 도구를 확인하고 인터넷 설정 복원을 시도하세요.");}
   final String text=message;runOnUiThread(new Runnable(){public void run(){if(isFinishing())return;busy=false;for(Button b:actions)b.setEnabled(true);internetStatus.setText(text);}});
  }},"usb-wifi-internet").start();
 }
 private void remote(final int action){
  final char[] secret;if(action==3){secret=new char[terminalPassword.length()];terminalPassword.getText().getChars(0,terminalPassword.length(),secret,0);terminalPassword.setText("");}else secret=null;
  busy=true;for(Button b:actions)b.setEnabled(false);remoteStatus.setText(UiText.t("처리 중…"));
  new Thread(new Runnable(){public void run(){String message;
   try{
    UsbWifiClient client=new UsbWifiClient();if(action==0)client.remote("start");else if(action==1)client.remote("stop");else if(action==3)client.remotePassword(secret);
    String state=client.remote("status");
    if("off".equals(state))message=UiText.t(action==3?"비밀번호를 저장했습니다. 터미널 켜기를 누르세요.":"터미널 접속 꺼짐");
    else{
     String[] parts=state.split(" ");if(parts.length!=3||!"on".equals(parts[0])||!com.veloster.carplayhub.update.ExternalWifiLease.ipv4(parts[1])||!"2222".equals(parts[2]))throw new java.io.IOException("Terminal status");
     message=UiText.t("접속 허용 중: ")+parts[1]+":2222\nssh -p 2222 root@"+parts[1];
     String identity=client.remote("identity");for(String line:identity.split("\n"))if(line.matches("Fingerprint: SHA256:[A-Za-z0-9+/=]+"))message+="\n"+line;
    }
   }catch(Exception e){message=UiText.t("터미널 작업을 완료하지 못했습니다. 비밀번호(8자 이상), Wi-Fi 연결, 원격 접속 도구 설치를 확인하세요.");}
   finally{if(secret!=null)Arrays.fill(secret,'\0');}
   final String text=message;runOnUiThread(new Runnable(){public void run(){if(isFinishing())return;busy=false;for(Button b:actions)b.setEnabled(true);remoteStatus.setText(text);}});
  }},"usb-wifi-terminal").start();
 }
 private void refresh(){
  if(!new File(UsbWifiClient.MANAGER).isFile()){status.setText(UiText.t("USB Wi-Fi 지원 도구가 설치되지 않았습니다."));return;}
  try{status.setText(UiText.t(AndroidUsbWifi.currentOrNull()!=null?"Wi-Fi 연결됨 · 앱 업데이트 사용 가능":"Wi-Fi 연결 대기 또는 연결 해제됨"));}
  catch(Exception unavailable){status.setText(UiText.t("Wi-Fi 연결을 확인할 수 없습니다. 다시 연결해 주세요."));}
 }
}
