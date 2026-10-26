package com.veloster.carplayhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import com.veloster.vehicle.UsbDebugControl;

/** Separate ordinary-UID app. Optional standard Android shortcut; no firmware writes, CAN or OEM package patches. */
public final class HubActivity extends Activity {
    private static final String TAG = "VNCarPlayHub";
    private LinearLayout content;
    private TextView status;
    private volatile boolean busy;
    private volatile boolean usbBusy;
    private SharedPreferences prefs;
    private WifiManager wifi;
    private BluetoothPanel bluetoothPanel;
    private RebootRequest rebootRequest;
    private AlertDialog rebootDialog;
    private final String trialSsid = "VN-CarPlay";
    private String page = "apps";
    private final Runnable quickEntryStatus=new Runnable(){public void run(){
        runOnUiThread(new Runnable(){public void run(){if(!isFinishing()&&prefs!=null&&"apps".equals(page))render();}});
    }};

    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("radio_trial", MODE_PRIVATE);
        wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bluetoothPanel=new BluetoothPanel(this);
        rebootRequest=new RebootRequest(new RebootRequest.Backend(){
            public boolean permitted(){return checkCallingOrSelfPermission("android.permission.REBOOT")==PackageManager.PERMISSION_GRANTED;}
            public void reboot(){
                android.os.PowerManager manager=(android.os.PowerManager)getSystemService(Context.POWER_SERVICE);
                if(manager==null)throw new IllegalStateException("Power service unavailable");
                manager.reboot(null);
            }
        });
        QuickEntryService.restore(this); // Restore only an already opted-in setting.
        showIntent(getIntent());
    }
    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); showIntent(intent); }
    protected void onResume() { super.onResume(); QuickEntryService.observe(quickEntryStatus); if(prefs!=null && !busy)render(); }
    protected void onPause(){
        QuickEntryService.unobserve(quickEntryStatus);
        if(bluetoothPanel!=null)bluetoothPanel.stop();
        if(rebootRequest!=null)rebootRequest.cancel();
        if(rebootDialog!=null){rebootDialog.dismiss();rebootDialog=null;}
        super.onPause();
    }
    protected void onDestroy(){QuickEntryService.unobserve(quickEntryStatus);if(bluetoothPanel!=null)bluetoothPanel.stop();super.onDestroy();}
    private void showIntent(Intent intent) {
        page = "radio".equals(intent.getStringExtra("page")) ? "radio" : "apps";
        render();
        // Exported intents can only open/read. State-changing actions require a visible button and confirmation.
    }
    private void render() {
        if(bluetoothPanel!=null&&!"bluetooth".equals(page))bluetoothPanel.stop();
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(14, 5, 14, 5);
        LinearLayout bar = new LinearLayout(this);
        add(bar, "내 앱", new Runnable(){public void run(){page="apps";render();}});
        add(bar, "연결 점검", new Runnable(){public void run(){page="radio";render();}});
        add(bar, "닫기", new Runnable(){public void run(){finish();}});
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(1); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        if ("radio".equals(page)) radioPage(); else if("android".equals(page)) androidAppsPage(); else if("bluetooth".equals(page))bluetoothPanel.show(content);else appsPage();
    }
    private void add(LinearLayout parent, String title, final Runnable action) {
        Button button = new Button(this); button.setText(title); button.setTextSize(17);
        parent.addView(button, new LinearLayout.LayoutParams(parent.getOrientation()==0 ? 0 : -1, 52,
                parent.getOrientation()==0 ? 1 : 0));
        button.setOnClickListener(new View.OnClickListener(){public void onClick(View view){action.run();}});
    }
    private void text(String value) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(17); v.setPadding(5, 6, 5, 6); content.addView(v);
    }
    private List<ResolveInfo> launchable() {
        Intent query = new Intent(Intent.ACTION_MAIN); query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = getPackageManager().queryIntentActivities(query, 0);
        Collections.sort(all,new Comparator<ResolveInfo>(){public int compare(ResolveInfo a,ResolveInfo b){
            return a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString());}});
        List<ResolveInfo> unique = new ArrayList<ResolveInfo>(); Set<String> seen = new HashSet<String>();
        for (ResolveInfo r : all) if (!getPackageName().equals(r.activityInfo.packageName) && accessible(r.activityInfo) && seen.add(r.activityInfo.packageName)) unique.add(r);
        return unique;
    }
    private void appsPage() {
        add(content,"실행 바로가기 / Quick entry",new Runnable(){public void run(){quickEntry();}});
        text("바로가기: "+QuickEntryService.status(this));
        if(QuickEntryService.mode(this)==QuickEntryPolicy.OFF)
            text("처음 설치했다면 실행 바로가기를 설정하세요. 순정 홈에는 앱이 자동으로 추가되지 않습니다.");
        LinearLayout first=new LinearLayout(this),second=new LinearLayout(this);
        add(first,"N 계기판",new Runnable(){public void run(){openDashboard();}});
        add(first,"USB 디버깅",new Runnable(){public void run(){usbDebug();}});
        add(second,"엔지니어링 모드",new Runnable(){public void run(){openEngineering();}});
        add(second,"안드로이드 기본앱",new Runnable(){public void run(){page="android";render();}});
        content.addView(first);content.addView(second);
        add(content,"블루투스 연결 설정",new Runnable(){public void run(){openBluetooth();}});
        add(content,"강제 재시작",new Runnable(){public void run(){confirmReboot();}});
        final List<ResolveInfo> all = new ArrayList<ResolveInfo>();
        for(ResolveInfo item:launchable())
            if(!"com.veloster.dashboard".equals(item.activityInfo.packageName)&&!"com.hkmc.app.nmode.ui".equals(item.activityInfo.packageName)&&!"com.hkmc.system.app.engineering".equals(item.activityInfo.packageName))all.add(item);
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<String>());
        Set<String> pinned = prefs.getStringSet("pinned", new HashSet<String>());
        int count = 0;
        for (final ResolveInfo r : all) {
            int flags = r.activityInfo.applicationInfo.flags;
            boolean ordinary = (flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
            if ((!ordinary && !pinned.contains(r.activityInfo.packageName)) || hidden.contains(r.activityInfo.packageName)) continue;
            count++;
            add(content, r.loadLabel(getPackageManager()).toString(),new Runnable(){public void run(){launch(r.activityInfo.packageName);}});
        }
        if (count == 0) text("표시할 설치 앱이 없습니다. 앱 설치 뒤 다시 열거나 목록 편집에서 선택하세요.");
        add(content,"목록 편집",new Runnable(){public void run(){editApps(all);}});
        text("일반 설치 앱은 자동 표시됩니다. 목록에서 숨겨도 앱은 삭제되지 않습니다.");
    }
    private void quickEntry(){
        final int[] modes={QuickEntryPolicy.FLOATING,QuickEntryPolicy.NOTIFICATION,QuickEntryPolicy.OFF};
        final int[] selected={2};int current=QuickEntryService.mode(this);
        for(int i=0;i<modes.length;i++)if(modes[i]==current)selected[0]=i;
        new AlertDialog.Builder(this).setTitle("실행 바로가기 / Quick entry")
            .setSingleChoiceItems(new String[]{"가장자리 버튼 + 알림 / Floating + notification","알림만 / Notification only","사용 안 함 / Off"},selected[0],new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface dialog,int which){selected[0]=which;}
            })
            .setPositiveButton("저장 / Save",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){
                boolean accepted=QuickEntryService.apply(HubActivity.this,modes[selected[0]]);render();
                if(!accepted)new AlertDialog.Builder(HubActivity.this).setMessage("바로가기 시작 실패. 설정 상태와 시스템 권한을 확인하세요.").setPositiveButton("확인",null).show();
            }}).setNegativeButton("취소 / Cancel",null)
            .setNeutralButton("설명 / Help",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){
                new AlertDialog.Builder(HubActivity.this).setMessage("켜면 재부팅 뒤에도 작은 바로가기만 복원합니다. 앱 화면은 자동으로 열지 않습니다. 가장자리 버튼은 끌어서 옮길 수 있습니다.\n\n시스템이 다른 앱 위 표시를 허용하지 않으면 알림만 유지합니다. 순정 화면에서 알림을 열 수 없는 경우에는 별도 메뉴 진입점이 필요합니다. 강제 종료하면 직접 앱을 다시 열 때까지 자동 복원이 중지될 수 있습니다.\n\nWhen enabled, reboot restores only the shortcut. Drag the edge button to move it. If overlays are denied, a notification remains. If the factory UI hides notifications too, use a separate menu entry. Force-stop may block restoration until My Apps is opened again.")
                    .setPositiveButton("설정으로 / Back",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){quickEntry();}}).show();
            }}).show();
    }
    private void editApps(final List<ResolveInfo> all) {
        final boolean[] chosen = new boolean[all.size()]; String[] titles = new String[all.size()];
        Set<String> hidden=prefs.getStringSet("hidden",new HashSet<String>()), pinned=prefs.getStringSet("pinned",new HashSet<String>());
        for(int i=0;i<all.size();i++) {
            ResolveInfo r=all.get(i); boolean ordinary=(r.activityInfo.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0;
            chosen[i]=(ordinary || pinned.contains(r.activityInfo.packageName)) && !hidden.contains(r.activityInfo.packageName);
            titles[i]=r.loadLabel(getPackageManager())+"  ("+r.activityInfo.packageName+")";
        }
        new AlertDialog.Builder(this).setTitle("내 앱에 표시할 앱")
            .setMultiChoiceItems(titles,chosen,new android.content.DialogInterface.OnMultiChoiceClickListener(){public void onClick(android.content.DialogInterface d,int i,boolean b){chosen[i]=b;}})
            .setPositiveButton("저장",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){
                Set<String> hide=new HashSet<String>(),pin=new HashSet<String>();
                for(int i=0;i<all.size();i++) if(chosen[i])pin.add(all.get(i).activityInfo.packageName);else hide.add(all.get(i).activityInfo.packageName);
                prefs.edit().putStringSet("hidden",hide).putStringSet("pinned",pin).commit();render();
            }}).setNegativeButton("취소",null).show();
    }
    private void launch(String name) {
        Intent intent=getPackageManager().getLaunchIntentForPackage(name);
        if(intent==null){text("실행 가능한 액티비티가 없습니다: "+name);return;}
        try{startActivity(intent);}catch(Exception e){text("실행 실패: "+e.getClass().getSimpleName());}
    }
    private boolean accessible(ActivityInfo info){
        return info!=null&&info.enabled&&info.exported&&info.applicationInfo.enabled&&
            (info.permission==null||getPackageManager().checkPermission(info.permission,getPackageName())==PackageManager.PERMISSION_GRANTED);
    }
    private Intent available(Intent request){
        try{
            ActivityInfo info;
            if(request.getComponent()!=null)info=getPackageManager().getActivityInfo(request.getComponent(),0);
            else{ResolveInfo resolved=getPackageManager().resolveActivity(request,PackageManager.MATCH_DEFAULT_ONLY);info=resolved==null?null:resolved.activityInfo;}
            return accessible(info)?request:null;
        }catch(PackageManager.NameNotFoundException e){return null;}
    }
    private void open(Intent request,String name){
        if(available(request)==null){text(name+" 화면이 없거나 현재 앱에서 실행할 수 없습니다.");return;}
        try{startActivity(request);}catch(RuntimeException e){text(name+" 실행 실패: "+e.getClass().getSimpleName());}
    }
    private void openDashboard(){
        Intent request=new Intent(Intent.ACTION_MAIN).setClassName("com.veloster.dashboard","com.veloster.dashboard.DashboardActivity");
        request.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open(request,"N 계기판");
    }
    private void openEngineering(){
        open(new Intent(Intent.ACTION_MAIN).setClassName("com.hkmc.system.app.engineering","com.hkmc.system.app.engineering.EngineeringModeMainActivity"),"엔지니어링 모드");
    }
    private void openBluetooth(){
        page="bluetooth";render();
    }
    private void androidShortcut(String title,Intent... candidates){
        Intent chosen=null;for(Intent candidate:candidates){chosen=available(candidate);if(chosen!=null)break;}
        final Intent request=chosen;final String label=title;
        if(request==null){text(title+" · 이 기기에서 실행할 수 없음");return;}
        add(content,title,new Runnable(){public void run(){open(request,label);}});
    }
    private Intent settingsScreen(String cls){return new Intent(Intent.ACTION_MAIN).setClassName("com.android.settings",cls);}
    private void androidAppsPage(){
        androidShortcut("안드로이드 설정",settingsScreen("com.android.settings.Settings"));
        androidShortcut("앱 관리",settingsScreen("com.android.settings.applications.ManageApplicationsList").setAction("android.settings.MANAGE_APPLICATIONS_SETTINGS"),new Intent("android.settings.MANAGE_APPLICATIONS_SETTINGS").setPackage("com.android.settings"));
        androidShortcut("저장공간",settingsScreen("com.android.settings.deviceinfo.Memory").setAction("android.settings.INTERNAL_STORAGE_SETTINGS"),new Intent("android.settings.INTERNAL_STORAGE_SETTINGS").setPackage("com.android.settings"));
        androidShortcut("개발자 설정",settingsScreen("com.android.settings.DevelopmentSettings").setAction("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS").setPackage("com.android.settings"));
        int count=0;
        for(final ResolveInfo info:launchable()){
            if((info.activityInfo.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0)continue;
            if("com.android.settings".equals(info.activityInfo.packageName)||"com.hkmc.app.nmode.ui".equals(info.activityInfo.packageName)||"com.hkmc.system.app.engineering".equals(info.activityInfo.packageName))continue;
            if("com.android.providers.downloads.ui".equals(info.activityInfo.packageName)||"com.android.providers.downloads".equals(info.activityInfo.packageName))continue;
            count++;add(content,info.loadLabel(getPackageManager()).toString(),new Runnable(){public void run(){
                open(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(info.activityInfo.packageName,info.activityInfo.name)),info.loadLabel(getPackageManager()).toString());
            }});
        }
        if(count==0)text("실행 가능한 기본 앱이 더 없습니다.");
        text("설치된 기본 앱 중 외부 실행이 허용된 앱만 표시합니다.");
    }
    private void usbDebug(){
        new AlertDialog.Builder(this).setTitle("USB 디버깅")
            .setMessage(UsbDebugControl.status()+"\nON: PC 연결용 USB 디버깅 모드\nOFF: 일반 USB 모드로 복귀하며 PC 연결이 끊길 수 있습니다.\n개발자 설정의 일반 USB 토글과는 별개입니다.")
            .setPositiveButton("ON",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){requestUsb(true);}})
            .setNegativeButton("OFF",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){requestUsb(false);}})
            .setNeutralButton("닫기",null).show();
    }
    private void confirmReboot(){
        if(rebootDialog!=null)return;
        final long ticket=rebootRequest.arm();
        if(ticket<0){text("이미 내비게이션 재시작을 요청했습니다.");return;}
        rebootDialog=new AlertDialog.Builder(this).setTitle("내비게이션 재시작")
            .setMessage("내비게이션 시스템을 재시작합니다. 화면·음악·PC 연결이 잠시 끊깁니다. 정차 중에 사용하세요.")
            .setPositiveButton("재시작",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){requestReboot(ticket);}})
            .setNegativeButton("취소",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){rebootRequest.cancel();}}).create();
        rebootDialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener(){public void onCancel(android.content.DialogInterface d){rebootRequest.cancel();}});
        rebootDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener(){public void onDismiss(android.content.DialogInterface d){rebootDialog=null;}});
        rebootDialog.show();
    }
    private void requestReboot(final long ticket){
        new Thread(new Runnable(){public void run(){
            String result;
            try{result=rebootRequest.request(ticket)?"내비게이션 재시작 요청을 전달했습니다.":"재시작 요청이 취소되었습니다.";}
            catch(Exception e){Log.e(TAG,"Reboot request failed",e);result="재시작 요청 실패: "+e.getClass().getSimpleName();}
            catch(LinkageError e){Log.e(TAG,"Reboot API unavailable",e);result="이 기기에서 재시작 기능을 사용할 수 없습니다.";}
            final String answer=result;
            runOnUiThread(new Runnable(){public void run(){if(!isFinishing())new AlertDialog.Builder(HubActivity.this).setMessage(answer).setPositiveButton("확인",null).show();}});
        }},"vn-reboot-request").start();
    }
    private void requestUsb(final boolean on){
        if(usbBusy)return;usbBusy=true;
        new Thread(new Runnable(){public void run(){String result;
            try{result=UsbDebugControl.request(HubActivity.this,on)?"USB 디버깅 "+(on?"ON":"OFF")+" 전환 요청 완료. 실제 PC 연결은 별도로 확인하세요.":"USB 모드 전환 요청이 거부되었습니다.";}
            catch(Exception e){result="USB 모드 전환 실패: "+e.getClass().getSimpleName();}
            final String answer=result;usbBusy=false;
            runOnUiThread(new Runnable(){public void run(){if(!isFinishing())new AlertDialog.Builder(HubActivity.this).setMessage(answer).setPositiveButton("확인",null).show();}});
        }},"vn-usb-mode").start();
    }
    private void radioPage() {
        text("정차 상태에서 확인하세요. 이 화면을 여는 것만으로 무선 설정은 바뀌지 않습니다.");
        status = new TextView(this); status.setTextSize(16); content.addView(status);
        add(content,"상태 새로 확인",new Runnable(){public void run(){inspect();}});
        add(content,"시험 핫스팟 켜기",new Runnable(){public void run(){confirm(true);}});
        add(content,"시험 접속정보 보기",new Runnable(){public void run(){credentials();}});
        add(content,"기존 무선 설정으로 복구",new Runnable(){public void run(){confirm(false);}});
        inspect();
    }
    private void confirm(final boolean start) {
        new AlertDialog.Builder(this).setTitle(start?"시험 핫스팟 켜기":"기존 설정 복구")
            .setMessage(start?"기존 AP 설정과 Wi-Fi 상태를 백업한 뒤 VN-CarPlay 핫스팟을 요청합니다. Wi-Fi 접속이 끊길 수 있습니다. 정차 중에만 진행하세요.":"DiPlay 연결을 먼저 종료하세요. 시험 AP를 끄고 백업한 AP 설정과 Wi-Fi 켜짐 상태를 복원합니다.")
            .setPositiveButton(start?"켜기":"복구",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int n){operate(start);}})
            .setNegativeButton("취소",null).show();
    }
    private byte[] pack(WifiConfiguration c) {
        if(c==null)return null; Parcel p=Parcel.obtain();try{c.writeToParcel(p,0);return p.marshall();}finally{p.recycle();}
    }
    private WifiConfiguration unpack(byte[] data) {
        if(data==null)return null; Parcel p=Parcel.obtain();try{p.unmarshall(data,0,data.length);p.setDataPosition(0);
            android.os.Parcelable.Creator<?> c=(android.os.Parcelable.Creator<?>)WifiConfiguration.class.getField("CREATOR").get(null);
            return (WifiConfiguration)c.createFromParcel(p);
        }catch(Exception e){throw new IllegalStateException("Cannot read saved AP config",e);}finally{p.recycle();}
    }
    private Object invoke(String name,Class<?>[] types,Object... args)throws Exception{
        if(wifi==null)throw new IllegalStateException("WifiManager unavailable");
        return WifiManager.class.getMethod(name,types).invoke(wifi,args);
    }
    private final RadioTrial.Radio radio = new RadioTrial.Radio(){
        public int wifiState(){if(wifi==null)return -1;return wifi.getWifiState();}
        public int apState()throws Exception{return ((Integer)invoke("getWifiApState",new Class<?>[0])).intValue();}
        public byte[] config()throws Exception{return pack((WifiConfiguration)invoke("getWifiApConfiguration",new Class<?>[0]));}
        public boolean wifi(boolean on){return wifi.setWifiEnabled(on);}
        public boolean ap(byte[] c,boolean on)throws Exception{return Boolean.TRUE.equals(invoke("setWifiApEnabled",new Class<?>[]{WifiConfiguration.class,Boolean.TYPE},unpack(c),Boolean.valueOf(on)));}
        public boolean config(byte[] c)throws Exception{return Boolean.TRUE.equals(invoke("setWifiApConfiguration",new Class<?>[]{WifiConfiguration.class},unpack(c)));}
        public boolean noWifiInterfaces()throws Exception{
            Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
            if(all==null)throw new IllegalStateException("Cannot enumerate interfaces");
            while(all.hasMoreElements()){
                String name=all.nextElement().getName();
                if(name.matches("(?i)(?:wlan|ap|softap|swlan|p2p).*"))return false;
            }
            return true;
        }
    };
    private final RadioTrial.Store store = new RadioTrial.Store(){
        public RadioTrial.Snapshot load(){String b=prefs.getString("backup",null);return b==null?null:new RadioTrial.Snapshot(prefs.getBoolean("wifi_before",false),Base64.decode(b,Base64.NO_WRAP));}
        public boolean save(RadioTrial.Snapshot s){return prefs.edit().putString("backup",Base64.encodeToString(s.config(),Base64.NO_WRAP)).putBoolean("wifi_before",s.wifiOn).commit();}
        public boolean clear(){return prefs.edit().remove("backup").remove("wifi_before").commit();}
    };
    private final RadioTrial.Clock clock = new RadioTrial.Clock(){public long now(){return SystemClock.elapsedRealtime();}public void sleep(long n)throws Exception{Thread.sleep(n);}};
    private void operate(final boolean start) {
        if(busy){status.setText("처리 중입니다. 완료될 때까지 기다려 주세요.");return;} busy=true;
        new Thread(new Runnable(){public void run(){
            String result;
            try {
                byte[] config=null;
                if(start && store.load()==null && radio.apState()==11) {
                    WifiConfiguration old=unpack(radio.config());
                    if(old!=null){
                        WifiConfiguration trial=unpack(pack(old));trial.SSID=trialSsid;trial.preSharedKey=password();
                        int key=WifiConfiguration.KeyMgmt.class.getField("WPA2_PSK").getInt(null);
                        trial.allowedKeyManagement.clear();trial.allowedKeyManagement.set(key);config=pack(trial);
                    }
                }
                RadioTrial control=new RadioTrial(radio,store,clock);result=start?control.start(config):control.restore();
            }catch(Exception e){result="처리 실패: "+e.getClass().getSimpleName()+". 백업이 남아 있으면 원상복구하세요.";Log.e(TAG,"operation failed: "+e.getClass().getSimpleName());}
            final String answer=result;busy=false;
            runOnUiThread(new Runnable(){public void run(){if(!isFinishing()){render();new AlertDialog.Builder(HubActivity.this).setMessage(answer).setPositiveButton("확인",null).show();}}});
        }},"vn-radio-trial").start();
    }
    private String password() {
        String p=prefs.getString("trial_password",null);
        if(p==null){p="vn"+UUID.randomUUID().toString().replace("-","").substring(0,12);if(!prefs.edit().putString("trial_password",p).commit())throw new IllegalStateException("Cannot save trial secret");}
        return p;
    }
    private void credentials() {
        String p=prefs.getString("trial_password",null);
        if(p==null || !prefs.contains("backup")){text("활성 시험 백업이 없습니다. 기존 핫스팟 접속정보는 차량 설정에서 확인하세요.");return;}
        new AlertDialog.Builder(this).setTitle("DiPlay와 iPhone에 입력")
            .setMessage("SSID: "+trialSsid+"\n비밀번호: "+p+"\n\nAP 켜짐·IP 할당은 상태와 실제 iPhone에서 따로 확인하세요.")
            .setPositiveButton("닫기",null).show();
    }
    private void inspect() {
        if(busy)return;
        try {
            Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("package",getPackageName());r.put("sdk",android.os.Build.VERSION.SDK_INT);
            r.put("hardware",android.os.Build.HARDWARE);r.put("timestamp_ms",System.currentTimeMillis());
            r.put("wifi_feature",getPackageManager().hasSystemFeature("android.hardware.wifi"));
            r.put("wifi_state",radio.wifiState());r.put("restore_pending",prefs.contains("backup"));
            try{r.put("ap_state",radio.apState());r.put("ap_config_readable",radio.config()!=null);}catch(Exception e){r.put("ap_error",e.getClass().getSimpleName());}
            try{
                BluetoothAdapter b=BluetoothAdapter.getDefaultAdapter();r.put("bluetooth_adapter",b!=null);
                if(b!=null){r.put("bluetooth_enabled",b.isEnabled());r.put("bonded_count",b.getBondedDevices().size());}
                r.put("rfcomm_connected_tested",false);
            }catch(Exception e){r.put("bluetooth_error",e.getClass().getSimpleName());}
            List<Object> nets=new ArrayList<Object>();try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()){
                NetworkInterface n=interfaces.nextElement();Map<String,Object> x=new LinkedHashMap<String,Object>();x.put("name",n.getName());List<String> addresses=new ArrayList<String>();
                for(Enumeration<InetAddress> a=n.getInetAddresses();a.hasMoreElements();)addresses.add(a.nextElement().getHostAddress());
                x.put("addresses",addresses);nets.add(x);
            }
            }catch(Exception e){r.put("interface_error",e.getClass().getSimpleName());}
            r.put("interfaces",nets);List<String> decoders=new ArrayList<String>();
            try{for(int i=0;i<MediaCodecList.getCodecCount();i++){MediaCodecInfo c=MediaCodecList.getCodecInfoAt(i);if(!c.isEncoder())for(String t:c.getSupportedTypes())if("video/avc".equals(t))decoders.add(c.getName());}}
            catch(Exception e){r.put("decoder_error",e.getClass().getSimpleName());}
            r.put("h264_decoders",decoders);r.put("video_decode_tested",false);
            String result=json(r);status.setText(result.replace(",\"",",\n\""));Log.i(TAG,"inspection "+result);
            File folder=getExternalFilesDir(null);if(folder==null)folder=getFilesDir();folder.mkdirs();
            FileOutputStream file=new FileOutputStream(new File(folder,"report.json"));try{file.write(result.getBytes("UTF-8"));}finally{file.close();}
        }catch(Exception e){status.setText("상태 확인 실패: "+e.getClass().getSimpleName());}
    }
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        StringBuilder b=new StringBuilder();
        if(value instanceof Map){
            b.append('{');for(Object item:((Map<?,?>)value).entrySet()){
                Map.Entry<?,?> e=(Map.Entry<?,?>)item;if(b.length()>1)b.append(',');b.append(json(e.getKey())).append(':').append(json(e.getValue()));
            }return b.append('}').toString();
        }
        if(value instanceof Iterable){b.append('[');for(Object item:(Iterable<?>)value){if(b.length()>1)b.append(',');b.append(json(item));}return b.append(']').toString();}
        b.append('"');for(char c:value.toString().toCharArray()){
            if(c=='"' || c=='\\')b.append('\\').append(c);
            else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);
        }return b.append('"').toString();
    }
}
