package com.veloster.carplayhub;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.IOException;
import android.view.View;
import android.widget.*;
import com.veloster.i18n.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit bonded-phone preference UI. Saving is not a connection-success claim. */
public final class WirelessCarPlayActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private WirelessCarPlaySettings settings;
    private LinearLayout devices;private TextView selected,status,help;private CheckBox automatic;private Button clear,refresh;
    private volatile boolean foreground,destroyed;private boolean busy,rendering,registered,reloadPending;private long epoch;private AlertDialog confirmation;
    private WirelessCarPlaySettings.Snapshot snapshot;private final List<Button> choices=new ArrayList<Button>();
    private static final int DISCOVERABLE_REQUEST=4103;
    private WirelessCarPlayEnrollment enrollment;private BluetoothPanel pairingPanel;private LinearLayout pairingArea;
    private boolean discoverabilityPending,discoverabilityApproved;private volatile long enrollmentRadioEpoch;
    private long discoverabilityDeadline,enrollmentGeneration;private WirelessCarPlaySettings.Device afterEnrollment;
    private interface Job{void run()throws Exception;}
    private final BroadcastReceiver radio=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)||BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(action)){
            enrollmentRadioEpoch++;if(enrollment!=null)endEnrollment(null);
        }
        if(foreground)load();
    }};
    public void onCreate(Bundle saved){super.onCreate(saved);LanguageSettings.init(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,8,16,8);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        TextView title=new TextView(this);title.setText(UiText.t("무선 CarPlay"));title.setTextSize(24);root.addView(title);
        help=new TextView(this);help.setText(UiText.t("CarPlay에 사용할 휴대폰을 직접 선택하세요. 아래에는 차량과 페어링된 기기만 표시됩니다."));root.addView(help);
        selected=new TextView(this);selected.setTextSize(18);root.addView(selected);
        automatic=new CheckBox(this);automatic.setText(UiText.t("선택한 휴대폰에 자동 연결"));root.addView(automatic);
        TextView notice=new TextView(this);notice.setText(UiText.t("자동 연결을 켜면 화면 없이도 연결하며 Android 부팅 뒤 다시 시작합니다. 자동 시도는 한 번에 최대 3회이며 휴대폰의 새 Bluetooth 접속을 감지하면 다시 시도합니다. 연결 중지는 재부팅하거나 직접 다시 연결할 때까지 유지됩니다. 정리 실패는 수동 복구가 필요합니다. 새 휴대폰의 등록 승인은 수신 화면에서 진행하세요."));root.addView(notice);
        automatic.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton button,final boolean checked){if(rendering)return;if(busy||snapshot==null){render();return;}final long generation=snapshot.generation;
            submit(new Job(){public void run()throws Exception{settings.setAutoConnect(generation,checked);}},"자동 연결 설정을 저장했습니다.");}});
        LinearLayout row=new LinearLayout(this);root.addView(row);refresh=button(row,"목록 새로고침",new Runnable(){public void run(){load();}});clear=button(row,"휴대폰 선택 해제",new Runnable(){public void run(){if(snapshot==null||busy)return;final long generation=snapshot.generation;
            submit(new Job(){public void run()throws Exception{settings.clear(generation);}},"휴대폰 선택을 해제했습니다.");}});button(row,"닫기",new Runnable(){public void run(){finish();}});
        button(root,"수신 화면 열기",new Runnable(){public void run(){startActivity(new Intent(WirelessCarPlayActivity.this,CarPlayReceiverActivity.class));}});
        LinearLayout add=new LinearLayout(this);root.addView(add);
        button(add,"새 휴대폰 추가 (120초)",new Runnable(){public void run(){requestDiscoverability();}});
        button(add,"추가 종료",new Runnable(){public void run(){endEnrollment(null);}});
        TextView discoveryNotice=new TextView(this);discoveryNotice.setText(UiText.t("처음 페어링할 때만 시스템 승인을 받아 차량을 최대 120초간 검색 가능하게 합니다. CarPlay 광고는 실제 수신 준비 후 시작하며 이 화면을 떠나면 철회합니다. 시스템 Bluetooth 검색 가능 시간은 승인한 시간이 끝날 때까지 남을 수 있습니다. 연결 중이라면 먼저 연결을 중지하고 자동 연결을 끄세요."));root.addView(discoveryNotice);
        pairingArea=new LinearLayout(this);pairingArea.setOrientation(1);root.addView(pairingArea);
        status=new TextView(this);status.setTextSize(17);root.addView(status);devices=new LinearLayout(this);devices.setOrientation(1);root.addView(devices);render();
    }
    public void onResume(){super.onResume();LanguageSettings.init(this);foreground=true;epoch++;IntentFilter filter=new IntentFilter();filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);registerReceiver(radio,filter);registered=true;load();if(discoverabilityApproved){discoverabilityApproved=false;beginEnrollment();}}
    public void onPause(){foreground=false;endEnrollment(null);epoch++;busy=false;reloadPending=false;if(registered){unregisterReceiver(radio);registered=false;}if(confirmation!=null){confirmation.dismiss();confirmation=null;}super.onPause();}
    public void onDestroy(){destroyed=true;endEnrollment(null);epoch++;worker.shutdown();super.onDestroy();}
    private void requestDiscoverability(){
        if(!foreground||busy||snapshot==null||!snapshot.usable)return;
        if(discoverabilityPending){status.setText(UiText.t("시스템 Bluetooth 승인을 기다리고 있습니다."));return;}
        if(enrollment!=null&&!enrollment.drained()){status.setText(UiText.t("이전 등록 준비 또는 정리가 끝날 때까지 기다리세요."));return;}
        if(snapshot.autoConnect){status.setText(UiText.t("새 휴대폰을 추가하기 전에 자동 연결을 끄고 기존 연결을 중지하세요."));return;}
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null||!adapter.isEnabled()){status.setText(UiText.t("Bluetooth를 먼저 켜주세요."));return;}
        Intent request=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120);
        if(request.resolveActivity(getPackageManager())==null){status.setText(UiText.t("이 시스템은 앱에서 Bluetooth 검색 가능 승인을 요청할 수 없습니다. 차량 Bluetooth 설정을 확인하세요."));return;}
        discoverabilityPending=true;discoverabilityDeadline=SystemClock.elapsedRealtime()+120000;
        try{startActivityForResult(request,DISCOVERABLE_REQUEST);}catch(RuntimeException unavailable){discoverabilityPending=false;status.setText(UiText.t("Bluetooth 검색 가능 승인 화면을 열지 못했습니다."));}
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=DISCOVERABLE_REQUEST||!discoverabilityPending)return;discoverabilityPending=false;
        if(result>0&&result<=120&&SystemClock.elapsedRealtime()<discoverabilityDeadline){
            discoverabilityDeadline=Math.min(discoverabilityDeadline,SystemClock.elapsedRealtime()+result*1000L);discoverabilityApproved=true;
            if(foreground){discoverabilityApproved=false;beginEnrollment();}
        }else status.setText(UiText.t("검색 가능 승인이 없거나 시간이 만료되어 CarPlay 등록 광고를 시작하지 않았습니다."));
    }
    private void beginEnrollment(){
        if(!foreground||destroyed||settings==null||!(getApplication() instanceof HubApplication))return;
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        if(adapter==null||!adapter.isEnabled()||adapter.getScanMode()!=BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE){status.setText(UiText.t("시스템 Bluetooth 검색 가능 상태를 확인하지 못했습니다."));return;}
        if(enrollment!=null&&!enrollment.drained())return;
        final WirelessCarPlaySettings source=settings;final WirelessCarPlaySettings.Snapshot selected=source.snapshot();
        if(!selected.usable||selected.autoConnect||SystemClock.elapsedRealtime()>=discoverabilityDeadline)return;
        final long radioEpoch=enrollmentRadioEpoch,deadline=discoverabilityDeadline;
        if(enrollmentGeneration==Long.MAX_VALUE)return;
        final WirelessCarPlayEnrollment[] holder=new WirelessCarPlayEnrollment[1];
        WirelessCarPlayEnrollment created=new WirelessCarPlayEnrollment((HubApplication)getApplication(),new WirelessCarPlayEnrollment.Consent(){public void checkCurrent()throws IOException{
            if(!foreground||destroyed||enrollmentRadioEpoch!=radioEpoch||SystemClock.elapsedRealtime()>=deadline||!source.current(selected.storeId,selected.generation,selected.phone))throw new IOException("Foreground enrollment consent withdrawn");
        }},new WirelessCarPlayEnrollment.Listener(){public void status(final String text,final boolean drained){runOnUiThread(new Runnable(){public void run(){
            if(enrollment!=holder[0])return;
            if(drained){enrollment=null;if(pairingPanel!=null){pairingPanel.stop();pairingPanel=null;}pairingArea.removeAllViews();}
            if(!foreground||destroyed)return;status.setText(UiText.t(text));
            if(drained)load();
        }});}},++enrollmentGeneration);
        holder[0]=created;enrollment=created;afterEnrollment=null;
        pairingPanel=new BluetoothPanel(this,new BluetoothPanel.PairingListener(){public void bonded(BluetoothDevice device){
            endEnrollment(new WirelessCarPlaySettings.Device(device.getAddress(),device.getName()));
        }});pairingPanel.show(pairingArea);created.start();
    }
    private void endEnrollment(WirelessCarPlaySettings.Device completed){
        afterEnrollment=completed;
        if(pairingPanel!=null){pairingPanel.stop();pairingPanel=null;}if(pairingArea!=null)pairingArea.removeAllViews();
        if(enrollment!=null){status.setText(UiText.t("등록 광고와 AP를 종료하고 있습니다. 정리 후 휴대폰을 선택하세요."));enrollment.close();}
    }
    private Button button(LinearLayout row,String text,final Runnable action){Button b=new Button(this);b.setText(UiText.t(text));b.setTextSize(17);row.addView(b,new LinearLayout.LayoutParams(row.getOrientation()==0?0:-1,56,row.getOrientation()==0?1:0));b.setOnClickListener(new View.OnClickListener(){public void onClick(View view){action.run();}});return b;}
    private void render(){rendering=true;try{selected.setText(UiText.t("선택한 휴대폰: ")+(snapshot==null||snapshot.phone==null?UiText.t("없음"):snapshot.phone));automatic.setChecked(snapshot!=null&&snapshot.usable&&snapshot.autoConnect);automatic.setEnabled(!busy&&snapshot!=null&&snapshot.usable&&snapshot.phone!=null);clear.setEnabled(!busy&&snapshot!=null&&(snapshot.phone!=null||!snapshot.usable));refresh.setEnabled(!busy);for(Button b:choices)b.setEnabled(!busy);}finally{rendering=false;}}
    private void load(){if(busy){reloadPending=true;return;}submit(new Job(){public void run()throws Exception{settings.refreshBond();}},null);}
    private void submit(final Job job,final String saved){if(!foreground||destroyed)return;final long ticket=++epoch;busy=true;render();status.setText(UiText.t("확인 중…"));
        worker.execute(new Runnable(){public void run(){List<WirelessCarPlaySettings.Device> found=Collections.emptyList();boolean enabled=false;String error=null;
            try{if(settings==null)settings=WirelessCarPlaySettings.open(getApplicationContext());job.run();enabled=settings.bluetoothEnabled();found=settings.bondedDevices();}
            catch(Exception unavailable){error="설정을 완료하지 못했습니다. Bluetooth 상태를 확인하고 다시 시도하세요.";}
            final WirelessCarPlaySettings.Snapshot result=settings==null?null:settings.snapshot();final List<WirelessCarPlaySettings.Device> rows=found;final boolean on=enabled;final String failed=error;
            runOnUiThread(new Runnable(){public void run(){if(destroyed||!foreground||ticket!=epoch)return;busy=false;snapshot=result;devices.removeAllViews();choices.clear();
                status.setText(UiText.t(failed!=null?failed:!on?"Bluetooth가 꺼져 있습니다. 차량 설정에서 켜주세요.":saved!=null?saved:rows.isEmpty()?"페어링된 휴대폰이 없습니다. Bluetooth 연결 설정에서 먼저 페어링하세요.":"사용할 휴대폰을 선택하세요."));
                for(final WirelessCarPlaySettings.Device device:rows){Button b=button(devices,(device.name.length()==0?UiText.t("이름 없는 기기"):device.name)+"\n"+device.address,new Runnable(){public void run(){choose(device);}});choices.add(b);}render();
                if(saved!=null&&failed==null&&getApplication() instanceof HubApplication)((HubApplication)getApplication()).wakeAutomatic(false);
                if(afterEnrollment!=null&&enrollment==null&&failed==null){WirelessCarPlaySettings.Device completed=afterEnrollment;afterEnrollment=null;choose(completed);}
                if(reloadPending){reloadPending=false;load();}
            }});
        }});
    }
    private void choose(final WirelessCarPlaySettings.Device device){
        if(enrollment!=null&&!enrollment.drained()){endEnrollment(device);return;}
        if(busy||snapshot==null)return;final long generation=snapshot.generation;confirmation=new AlertDialog.Builder(this).setTitle(UiText.t("CarPlay 휴대폰 선택"))
        .setMessage((device.name.length()==0?UiText.t("이름 없는 기기"):device.name)+"\n"+device.address+"\n\n"+UiText.t("이 휴대폰을 선택할까요? 휴대폰을 바꾸면 자동 연결은 꺼집니다."))
        .setPositiveButton(UiText.t("선택"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){submit(new Job(){public void run()throws Exception{settings.select(generation,device.address);}},"휴대폰 선택을 저장했습니다. 자동 연결은 꺼져 있습니다.");}}).setNegativeButton(UiText.t("취소"),null).show();}
}
