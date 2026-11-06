package com.veloster.carplayhub;
import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.veloster.i18n.*;
import com.veloster.carplay.discovery.AndroidReceiverUi;
import com.veloster.carplay.media.VolumePolicy;
import com.veloster.carplay.media.LastUserModes;

/** Actual receiver Surface owner. Opening this screen alone does not establish a phone session. */
public final class CarPlayReceiverActivity extends Activity {
    SurfaceView surface;ScrollView host;private TextView status;private HubApplication application;private boolean resumed;private android.app.AlertDialog bandDialog;
    private Button siriButton,volumeButton,mediaButton;private boolean modeEntryTaken,userLeft;private android.app.AlertDialog volumeDialog;private TextView volumeLabel;private SeekBar volumeSlider;private VolumePolicy.Snapshot volumeSnapshot;
    private final android.os.Handler siriUi=new android.os.Handler();
    private final Runnable siriRefresh=new Runnable(){public void run(){refreshSiri();refreshVolume();refreshMedia();if(foreground())siriUi.postDelayed(this,500);}};
    public void onCreate(Bundle saved){super.onCreate(saved);requestWindowFeature(Window.FEATURE_NO_TITLE);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);LanguageSettings.init(this);application=(HubApplication)getApplication();
        FrameLayout root=new FrameLayout(this);surface=new SurfaceView(this);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));host=new ScrollView(this);host.setFillViewport(true);host.setBackgroundColor(0xff101924);LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(20,12,124,20);host.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(host,new FrameLayout.LayoutParams(-1,-1));
        TextView title=new TextView(this);title.setText(UiText.t("무선 CarPlay"));title.setTextSize(24);title.setTextColor(0xffffffff);content.addView(title);status=new TextView(this);status.setTextSize(18);status.setTextColor(0xffffffff);content.addView(status);
        add(content,"선택한 휴대폰 연결",new Runnable(){public void run(){application.startSelected(false);}});add(content,"연결 중지",new Runnable(){public void run(){application.stopConnection();}});add(content,"정리 다시 시도",new Runnable(){public void run(){application.recoverConnection();}});
        mediaButton=new Button(this);mediaButton.setTextSize(14);mediaButton.setPadding(4,0,4,0);FrameLayout.LayoutParams mediaPosition=new FrameLayout.LayoutParams(100,64,Gravity.TOP|Gravity.RIGHT);mediaPosition.topMargin=168;root.addView(mediaButton,mediaPosition);mediaButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){LastUserModes.Snapshot state=application.userModes();if(foreground()&&state!=null)application.chooseMedia(CarPlayReceiverActivity.this,!state.playMedia());}});
        TextView mediaHelp=new TextView(this);mediaHelp.setText(UiText.t("화면과 CarPlay 음악 선택을 따로 기억합니다. 통화·Siri·안내 음성은 음악 선택과 별도로 동작합니다."));mediaHelp.setTextColor(0xffffffff);content.addView(mediaHelp);
        TextView restartHelp=new TextView(this);restartHelp.setText(UiText.t("앱이 다시 시작되면 화면과 음악 사용을 다시 선택하세요."));restartHelp.setTextColor(0xffffffff);content.addView(restartHelp);
        add(content,"Wi-Fi 대역 선택",new Runnable(){public void run(){bandDialog=new android.app.AlertDialog.Builder(CarPlayReceiverActivity.this).setTitle(UiText.t("Wi-Fi 대역 선택")).setItems(new String[]{UiText.t("2.4GHz (채널 6 · 연결 후 Bluetooth 전체 꺼짐)"),UiText.t("5GHz (채널 36)")},new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){if(foreground())application.setApChannel(which==0?6:36);}}).setNegativeButton(UiText.t("취소"),null).show();}});
        TextView bandHelp=new TextView(this);bandHelp.setText(UiText.t("2.4GHz는 CarPlay 전환 후 Bluetooth 전체를 꺼 다른 Bluetooth 연결도 끊습니다. 종료 시 이 세션이 소유한 OFF만 복원합니다. 대역을 바꾸면 현재 연결을 종료합니다."));bandHelp.setTextColor(0xffffffff);content.addView(bandHelp);
        TextView closeHelp=new TextView(this);closeHelp.setText(UiText.t("화면을 닫아도 연결과 소리는 유지됩니다. 종료하려면 연결 중지를 누르세요."));closeHelp.setTextColor(0xffffffff);content.addView(closeHelp);
        Button exit=new Button(this);exit.setText(UiText.t("화면 닫기"));exit.setTextSize(14);exit.setSingleLine(true);exit.setPadding(4,0,4,0);FrameLayout.LayoutParams end=new FrameLayout.LayoutParams(100,52,Gravity.TOP|Gravity.RIGHT);root.addView(exit,end);exit.setOnClickListener(new View.OnClickListener(){public void onClick(View v){application.chooseNativeScreen(CarPlayReceiverActivity.this);finish();}});
        siriButton=new Button(this);siriButton.setText(UiText.t("Siri"));siriButton.setVisibility(View.GONE);siriButton.setEnabled(false);FrameLayout.LayoutParams voice=new FrameLayout.LayoutParams(100,52,Gravity.TOP|Gravity.RIGHT);voice.topMargin=56;root.addView(siriButton,voice);
        siriButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){requestSiri();}});
        volumeButton=new Button(this);volumeButton.setText(UiText.t("음량"));volumeButton.setEnabled(false);FrameLayout.LayoutParams volume=new FrameLayout.LayoutParams(100,52,Gravity.TOP|Gravity.RIGHT);volume.topMargin=112;root.addView(volumeButton,volume);
        volumeButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showVolume();}});setContentView(root);
    }
    private void add(LinearLayout content,String title,final Runnable action){Button button=new Button(this);button.setText(UiText.t(title));content.addView(button,new LinearLayout.LayoutParams(-1,56));button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(foreground())action.run();}});}
    public void onResume(){super.onResume();resumed=true;application.attach(this);AndroidReceiverUi ui=application.uiFor(this);if(ui!=null)ui.onResume();refreshStatus();siriUi.removeCallbacks(siriRefresh);siriUi.post(siriRefresh);}
    public void onPause(){resumed=false;application.paused(this);siriUi.removeCallbacks(siriRefresh);refreshSiri();if(bandDialog!=null){bandDialog.dismiss();bandDialog=null;}if(volumeDialog!=null){volumeDialog.dismiss();volumeDialog=null;}AndroidReceiverUi ui=application.uiFor(this);if(ui!=null)ui.onPause();super.onPause();}
    public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(application!=null){AndroidReceiverUi ui=application.uiFor(this);if(ui!=null)ui.onWindowFocusChanged(focused);refreshSiri();}}
    public void onDestroy(){siriUi.removeCallbacks(siriRefresh);application.detach(this);super.onDestroy();}
    boolean foreground(){return resumed&&!isFinishing();}
    boolean explicitModeEntry(){if(userLeft){userLeft=false;return true;}if(modeEntryTaken)return false;modeEntryTaken=true;return !getIntent().getBooleanExtra("restore_user_screen",false);}
    public void onBackPressed(){application.chooseNativeScreen(this);super.onBackPressed();}
    public void onUserLeaveHint(){userLeft=true;application.chooseNativeScreen(this);super.onUserLeaveHint();}
    private void refreshMedia(){if(mediaButton==null)return;LastUserModes.Snapshot state=application.userModes();mediaButton.setEnabled(state!=null&&foreground());mediaButton.setText(UiText.t(state==null||!state.playMedia()?"CarPlay 음악 사용":"CarPlay 음악 끄기"));
        if(state!=null&&state.failure!=null)mediaButton.setText(UiText.t("선택 저장 실패 · 다시 선택하세요"));}
    void refreshStatus(){if(status!=null)status.setText(application.connectionStatus());refreshSiri();}
    private void refreshSiri(){if(siriButton==null||application==null)return;AndroidReceiverUi ui=application.uiFor(this);boolean available=foreground()&&hasWindowFocus()&&ui!=null&&ui.siriAvailable();siriButton.setEnabled(available);siriButton.setVisibility(available?View.VISIBLE:View.GONE);}
    private void requestSiri(){if(!foreground()||!hasWindowFocus())return;AndroidReceiverUi ui=application.uiFor(this);if(ui==null||!ui.siriPulse())Toast.makeText(this,UiText.t("지금은 Siri를 사용할 수 없습니다."),Toast.LENGTH_SHORT).show();refreshSiri();}
    private static String volumeName(VolumePolicy.Context context){if(context==null)return UiText.t("재생 중인 소리가 없습니다.");switch(context){
        case MEDIA:return UiText.t("음악");case NAVIGATION:return UiText.t("안내·알림");case SIRI:return UiText.t("Siri");case RINGTONE:return UiText.t("벨·알림");case CALL:return UiText.t("통화");default:return "";}}
    private void refreshVolume(){if(application==null||volumeButton==null)return;volumeSnapshot=application.volumeFor(this);boolean available=foreground()&&volumeSnapshot!=null&&volumeSnapshot.available();volumeButton.setEnabled(available);
        if(volumeDialog==null||volumeLabel==null)return;volumeSlider.setEnabled(available);
        String text=volumeName(available?volumeSnapshot.activeContext:null);if(available){text+="  "+volumeSnapshot.percent+"%";volumeSlider.setProgress(volumeSnapshot.percent);}
        if(volumeSnapshot!=null&&volumeSnapshot.saveError!=null)text+="\n"+UiText.t("음량 저장 실패. 현재 연결에는 적용됩니다.");
        else if(volumeSnapshot!=null&&volumeSnapshot.saving)text+="\n"+UiText.t("저장 중…");volumeLabel.setText(text);
    }
    private void showVolume(){if(!foreground())return;LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,12,24,12);volumeLabel=new TextView(this);volumeLabel.setTextSize(20);box.addView(volumeLabel);volumeSlider=new SeekBar(this);volumeSlider.setMax(100);box.addView(volumeSlider,new LinearLayout.LayoutParams(-1,64));
        TextView help=new TextView(this);help.setText(UiText.t("현재 재생 중인 CarPlay 소리의 음량을 종류별로 저장합니다. 차량의 전체 음량은 바꾸지 않습니다."));box.addView(help);
        volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}public void onProgressChanged(SeekBar bar,int value,boolean user){if(user&&volumeSnapshot!=null){application.setVolume(CarPlayReceiverActivity.this,volumeSnapshot,value);refreshVolume();}}});
        volumeDialog=new android.app.AlertDialog.Builder(this).setTitle(UiText.t("CarPlay 앱 음량")).setView(box).setPositiveButton(UiText.t("닫기"),null).create();volumeDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener(){public void onDismiss(android.content.DialogInterface dialog){volumeDialog=null;volumeSnapshot=null;}});volumeDialog.show();refreshVolume();
    }
}
