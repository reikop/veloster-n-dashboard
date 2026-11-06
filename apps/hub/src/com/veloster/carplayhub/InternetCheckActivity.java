package com.veloster.carplayhub;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.veloster.i18n.*;
import com.veloster.carplayhub.update.*;
import com.veloster.carplayhub.network.HttpsMessages;
import com.veloster.network.ModernHttps;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.*;

/** Explicit HEAD probes; no cookies/login, response body, clock changes or TLS bypass. */
public final class InternetCheckActivity extends Activity {
 private TextView result,caStatus;private boolean caBusy;private final List<Button> caButtons=new ArrayList<Button>();private EditText address;private Button check;
 private volatile Task active;private boolean foreground;
 private static final class Task implements ModernHttps.Cancellation {volatile boolean cancelled;volatile HttpsURLConnection legacy;volatile boolean legacyDone;public boolean cancelled(){return cancelled;}}
 public void onCreate(Bundle saved){
  super.onCreate(saved);LanguageSettings.init(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,8,16,8);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  TextView title=new TextView(this);title.setText(UiText.t("HTTPS 연결 검사"));title.setTextSize(24);root.addView(title);
  TextView help=new TextView(this);help.setText(UiText.t("입력한 서버에 최신 HTTPS 모듈과 Android 기본 통신으로 각각 접속합니다. 연결 성공은 앱 설치·호환성 검증을 뜻하지 않습니다."));root.addView(help);
  address=new EditText(this);address.setSingleLine(true);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setText("https://github.com/");root.addView(address);
  LinearLayout buttons=new LinearLayout(this);root.addView(buttons);
  check=new Button(this);check.setText(UiText.t("검사 시작"));buttons.addView(check,new LinearLayout.LayoutParams(0,56,1));check.setOnClickListener(new View.OnClickListener(){public void onClick(View v){start();}});
  Button cancel=new Button(this);cancel.setText(UiText.t("취소"));buttons.addView(cancel,new LinearLayout.LayoutParams(0,56,1));cancel.setOnClickListener(new View.OnClickListener(){public void onClick(View v){cancel();}});
  Button close=new Button(this);close.setText(UiText.t("닫기"));buttons.addView(close,new LinearLayout.LayoutParams(0,56,1));close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){finish();}});
  TextView caHelp=new TextView(this);caHelp.setText(UiText.t("다른 앱의 인증서 호환성을 선택적으로 보강합니다. 순정 파일을 보존하며 직접 복원하거나 재부팅하면 해제됩니다. 적용 후 대상 앱을 다시 여세요. 마켓용 최신 HTTPS는 이 설정 없이 작동합니다."));root.addView(caHelp);
  LinearLayout caRow=new LinearLayout(this);root.addView(caRow);
  String[] labels={"인증서 호환성 켜기","인증서 원복","인증서 상태"};
  for(int i=0;i<labels.length;i++){final int action=i;Button b=new Button(this);b.setText(UiText.t(labels[i]));caRow.addView(b,new LinearLayout.LayoutParams(0,56,1));caButtons.add(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View view){certificates(action);}});}
  caStatus=new TextView(this);root.addView(caStatus);
  result=new TextView(this);result.setTextSize(17);result.setTextIsSelectable(true);root.addView(result);
 }
 public void onResume(){super.onResume();foreground=true;}
 public void onPause(){foreground=false;cancel();super.onPause();}
 private void cancel(){Task t=active;if(t!=null){t.cancelled=true;final HttpsURLConnection c=t.legacy;if(c!=null)new Thread(new Runnable(){public void run(){c.disconnect();}},"vn-check-close").start();}if(check!=null)check.setEnabled(active==null&&!caBusy);if(address!=null)address.setEnabled(active==null);if(result!=null)result.setText(UiText.t("HTTPS 검사를 취소했습니다."));}
 private void certificates(final int action){
  if(caBusy||active!=null||!foreground)return;caBusy=true;check.setEnabled(false);for(Button b:caButtons)b.setEnabled(false);caStatus.setText(UiText.t("처리 중…"));
  new Thread(new Runnable(){public void run(){String message;
   try{com.veloster.carplayhub.wifi.UsbWifiClient client=new com.veloster.carplayhub.wifi.UsbWifiClient();if(action==0)client.certificates("start");else if(action==1)client.certificates("stop");String state=client.certificates("status");
    if("on".equals(state))message=UiText.t("인증서 보강 적용됨 · 대상 앱을 다시 열어 확인하세요.");
    else if("off".equals(state))message=UiText.t("순정 인증서 설정 사용 중");
    else if("restore-needed".equals(state))message=UiText.t("인증서 복원이 필요합니다. 인증서 원복을 누르세요.");else throw new IOException();
   }catch(Exception error){message=UiText.t("인증서 작업을 완료하지 못했습니다. 지원 도구를 확인하고 인증서 원복을 시도하세요.");}
   final String text=message;runOnUiThread(new Runnable(){public void run(){caBusy=false;if(isFinishing())return;check.setEnabled(active==null);for(Button b:caButtons)b.setEnabled(true);caStatus.setText(text);}});
  }},"vn-ca-control").start();
 }
 private void start(){
  if(active!=null||caBusy||!foreground)return;
  final String input=address.getText().toString().trim();final URL url;
  try{url=new URL(input);if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443))throw new IOException();}
  catch(Exception bad){result.setText(UiText.t("로그인 정보나 검색어가 없는 HTTPS 주소를 입력하세요."));return;}
  final Task t=new Task();active=t;check.setEnabled(false);address.setEnabled(false);result.setText(UiText.t("확인 중…"));
  new Thread(new Runnable(){public void run(){StringBuilder text=new StringBuilder();SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'",Locale.US);date.setTimeZone(TimeZone.getTimeZone("UTC"));text.append(UiText.t("기기 시각: ")).append(date.format(new Date())).append('\n').append(UiText.t("시각이 틀리면 차량 날짜·시간을 먼저 맞춰 주세요.")).append('\n');
   try{
    final UpdateTransport.Policy policy=new UpdateTransport.Policy(){public void check()throws IOException{UpdateNetwork.requireLocalConnection(InternetCheckActivity.this);}};
    ModernHttps modern=AndroidTls.modern(InternetCheckActivity.this,Collections.singleton(url.getHost()),policy);modern.checkUrl(url);
    try{int code=modern.probe(input,t);text.append(UiText.t("최신 HTTPS: 성공 · HTTP ")).append(code);}
    catch(ModernHttps.Failure failure){text.append(UiText.t("최신 HTTPS: ")).append(HttpsMessages.failure(failure));}
    text.append('\n');if(t.cancelled())throw new ModernHttps.Failure(ModernHttps.Reason.CANCELLED,0);
    Thread legacyWatch=null;
    try{
     policy.check();final HttpsURLConnection c=(HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);t.legacy=c;
     legacyWatch=new Thread(new Runnable(){public void run(){try{while(!t.legacyDone){if(t.cancelled())throw new IOException();policy.check();Thread.sleep(100);}}catch(Exception stop){if(!t.legacyDone)c.disconnect();}}},"vn-legacy-check-watch");legacyWatch.setDaemon(true);legacyWatch.start();
     c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod("HEAD");c.setConnectTimeout(10000);c.setReadTimeout(10000);
     SSLContext tls=SSLContext.getInstance("TLSv1.2");tls.init(null,null,null);c.setSSLSocketFactory(new UpdateTransport.Tls12Factory(tls.getSocketFactory()));
     if(t.cancelled())throw new ModernHttps.Failure(ModernHttps.Reason.CANCELLED,0);int code=c.getResponseCode();policy.check();text.append(UiText.t("Android 기본 HTTPS: 성공 · HTTP ")).append(code);
    }catch(Exception failed){text.append(UiText.t("Android 기본 HTTPS: 실패 · 최신 모듈의 결과와 비교하세요."));}
    finally{t.legacyDone=true;if(legacyWatch!=null)legacyWatch.interrupt();if(t.legacy!=null){t.legacy.disconnect();t.legacy=null;}}
    ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);NetworkInfo info=cm==null?null:cm.getActiveNetworkInfo();text.append('\n').append(UiText.t(info!=null&&info.isConnected()?"Android 연결 상태: 연결됨":"Android 연결 상태: 연결 안 됨 · 일부 앱은 접속을 거부할 수 있습니다."));
   }catch(Exception failed){text.append(UiText.t("Wi-Fi 연결과 HTTPS 모듈을 확인하세요."));}
   final String shown=text.toString();runOnUiThread(new Runnable(){public void run(){if(active!=t)return;active=null;check.setEnabled(!caBusy);address.setEnabled(true);if(!t.cancelled&&foreground)result.setText(shown);}});
  }},"vn-https-check").start();
 }
}
