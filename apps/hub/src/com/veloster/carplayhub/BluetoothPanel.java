package com.veloster.carplayhub;

import com.veloster.i18n.UiText;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Separate Classic Bluetooth device chooser using the installed Android Bluetooth stack. */
public final class BluetoothPanel {
    private final Activity activity;
    private final BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
    private final Map<String,BluetoothDevice> found=new LinkedHashMap<String,BluetoothDevice>();
    private LinearLayout root;
    private String message="";
    private boolean registered;
    private AlertDialog confirmation;
    private final BluetoothSession session=new BluetoothSession(new BluetoothSession.Radio(){
        public boolean enabled(){return adapter!=null&&adapter.isEnabled();}
        public boolean discovering(){return adapter!=null&&adapter.isDiscovering();}
        public boolean scan(){return adapter!=null&&adapter.startDiscovery();}
        public boolean cancel(){return adapter!=null&&adapter.cancelDiscovery();}
        public int bondState(String a){return adapter.getRemoteDevice(a).getBondState();}
        public boolean pair(String a)throws Exception{
            return Boolean.TRUE.equals(BluetoothDevice.class.getMethod("createBond").invoke(adapter.getRemoteDevice(a)));
        }
    });
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        String action=intent.getAction();
        if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action))session.finished();
        Object device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if(device instanceof BluetoothDevice){BluetoothDevice d=(BluetoothDevice)device;found.put(d.getAddress(),d);}
        draw();
    }};
    public BluetoothPanel(Activity activity){this.activity=activity;}
    public void show(LinearLayout parent){
        root=parent;
        if(!registered){
            IntentFilter filter=new IntentFilter();filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            activity.registerReceiver(receiver,filter);registered=true;
        }
        draw();
    }
    public void stop(){
        if(confirmation!=null){confirmation.dismiss();confirmation=null;}
        if(registered){activity.unregisterReceiver(receiver);registered=false;}
        try{session.stop();}catch(Exception e){message=UiText.t("검색 종료 상태를 확인하지 못했습니다.");}
        root=null;
    }
    private void text(String label){TextView v=new TextView(activity);v.setText(label);v.setTextSize(17);v.setPadding(5,6,5,6);root.addView(v);}
    private void button(LinearLayout parent,String title,final Runnable click){
        Button button=new Button(activity);button.setText(title);button.setTextSize(17);
        parent.addView(button,new LinearLayout.LayoutParams(parent.getOrientation()==0?0:-1,52,parent.getOrientation()==0?1:0));
        button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){click.run();}});
    }
    private void draw(){
        if(root==null)return;root.removeAllViews();
        text(UiText.t("블루투스 기기 검색·페어링"));
        text(UiText.t("GPS·오디오 등 실제 연결은 해당 앱에서 진행합니다. 기기를 선택해 확인할 때만 페어링을 요청합니다."));
        if(adapter==null){text(UiText.t("안드로이드 블루투스 어댑터가 없습니다."));return;}
        try{
            text(adapter.isEnabled()?(adapter.isDiscovering()?UiText.t("주변 기기 검색 중"):UiText.t("블루투스 켜짐")):UiText.t("블루투스 꺼짐 · 차량 설정에서 먼저 켜주세요."));
            LinearLayout actions=new LinearLayout(activity);
            button(actions,UiText.t("주변 기기 검색"),new Runnable(){public void run(){
                try{message=session.scan();}catch(Exception e){message=UiText.t("검색 요청 실패: ")+e.getClass().getSimpleName();}draw();
            }});
            button(actions,UiText.t("검색 종료"),new Runnable(){public void run(){
                try{session.stop();message=UiText.t("이 화면에서 시작한 검색을 종료했습니다.");}catch(Exception e){message=UiText.t("검색 종료 실패");}draw();
            }});root.addView(actions);
            if(message.length()>0)text(UiText.t(message));
            Set<BluetoothDevice> paired=adapter.getBondedDevices();text(UiText.t("페어링된 기기"));
            if(paired==null||paired.isEmpty())text(UiText.t("페어링된 기기가 없습니다."));
            else for(BluetoothDevice device:paired)deviceButton(device);
            text(UiText.t("검색된 기기"));int count=0;
            for(BluetoothDevice device:found.values())if(device.getBondState()!=BluetoothDevice.BOND_BONDED){deviceButton(device);count++;}
            if(count==0)text(UiText.t("주변 기기 검색을 누르고 연결할 기기를 페어링 모드로 켜주세요."));
            text(UiText.t("일반 블루투스(Classic)를 사용합니다. BLE 검색과 GPS 데이터 수신 기능은 포함하지 않습니다."));
        }catch(SecurityException e){text(UiText.t("블루투스 접근 권한을 확인할 수 없습니다."));}
    }
    private void deviceButton(final BluetoothDevice device){
        String name=device.getName();if(name==null||name.length()==0)name=UiText.t("이름 없는 기기");
        int state=device.getBondState();String label=name+" · "+(state==BluetoothDevice.BOND_BONDED?UiText.t("페어링됨"):state==BluetoothDevice.BOND_BONDING?UiText.t("페어링 중"):UiText.t("새 기기"))+"\n"+device.getAddress();
        final String title=name;
        button(root,label,new Runnable(){public void run(){
            if(device.getBondState()==BluetoothDevice.BOND_BONDED){message=UiText.t("이미 페어링된 기기입니다. 실제 연결은 해당 앱에서 진행하세요.");draw();return;}
            confirmation=new AlertDialog.Builder(activity).setTitle(UiText.t("기기 페어링"))
                .setMessage(title+"\n"+device.getAddress()+UiText.t("\n\n이 기기와 페어링할까요?"))
                .setPositiveButton(UiText.t("페어링"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int n){
                    try{message=session.pair(device.getAddress());}catch(Exception e){message=UiText.t("페어링 요청 실패: ")+e.getClass().getSimpleName();}draw();
                }}).setNegativeButton(UiText.t("취소"),null).show();
        }});
    }
}
