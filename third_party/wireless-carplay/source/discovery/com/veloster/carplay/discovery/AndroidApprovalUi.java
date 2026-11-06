/* SPDX-License-Identifier: GPL-3.0-only. Local, foreground-only pairing consent. */
package com.veloster.carplay.discovery;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import com.veloster.carplay.control.ControlConnection;
import com.veloster.carplay.control.ReceiverScope;
import com.veloster.carplay.pairing.PairVerify;
import java.util.Locale;

/** Construct and drive lifecycle on main. Network callbacks enqueue only. One
 * pending capability; never approve from an Intent, saved state, or a timeout. */
public final class AndroidApprovalUi implements ControlConnection.ApprovalUi {
    /** Apps may supply resource translations; the standalone adapter supports ko/en. */
    public static final class Labels {
        public final String title,device,question,allow,deny;
        public Labels(String title,String device,String question,String allow,String deny){
            String[] values={title,device,question,allow,deny};
            for(String value:values)if(value==null||value.trim().length()==0||value.length()>512)throw new IllegalArgumentException("Approval labels");
            this.title=title;this.device=device;this.question=question;this.allow=allow;this.deny=deny;
        }
        public static Labels localized(){
            return "ko".equals(Locale.getDefault().getLanguage())?
                new Labels("CarPlay 연결 허용","선택한 Bluetooth 주소: ","새 CarPlay 연결 요청을 저장할까요?","허용","거부"):
                new Labels("Allow CarPlay connection","Selected Bluetooth address: ","Save this new CarPlay connection request?","Allow","Deny");
        }
    }
    private final Activity activity;private final PairVerify.Context context;private final ReceiverScope scope;private final Labels labels;
    private final Handler main=new Handler(Looper.getMainLooper());private final Object lock=new Object();
    private boolean foreground,closed;private ControlConnection.Approval pending,presented;private AlertDialog dialog;
    public AndroidApprovalUi(Activity activity,PairVerify.Context context){
        this(activity,context,Labels.localized());
    }
    public AndroidApprovalUi(Activity activity,PairVerify.Context context,Labels labels){
        this(activity,ReceiverScope.from(context),context,labels);
    }
    public AndroidApprovalUi(Activity activity,ReceiverScope scope,Labels labels){this(activity,scope,null,labels);}
    private AndroidApprovalUi(Activity activity,ReceiverScope scope,PairVerify.Context context,Labels labels){
        requireMain();if(activity==null||scope==null||labels==null)throw new NullPointerException();this.activity=activity;this.context=context;this.scope=scope;this.labels=labels;
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");}
    public void request(final ControlConnection.Approval approval){
        if(approval==null)throw new NullPointerException();
        synchronized(lock){
            if(closed||!foreground||pending!=null||context!=null&&approval.context!=context||!scope.contains(approval.context)){approval.reject();return;}
            pending=approval;
            if(!main.post(new Runnable(){public void run(){show(approval);}})){pending=null;approval.reject();}
        }
    }
    public void dismiss(final ControlConnection.Approval approval){
        synchronized(lock){if(pending==approval)pending=null;}
        main.post(new Runnable(){public void run(){synchronized(lock){if(presented==approval)hide();}}});
    }
    private void show(final ControlConnection.Approval approval){
        requireMain();synchronized(lock){
            if(pending!=approval)return;
            if(closed||!foreground||activity.isFinishing()){pending=null;approval.reject();return;}
            hide();presented=approval;
            try{
                dialog=new AlertDialog.Builder(activity).setTitle(labels.title)
                    .setMessage(labels.device+scope.selectedBluetooth+"\n"+labels.question)
                    .setPositiveButton(labels.allow,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){decide(approval,true);}})
                    .setNegativeButton(labels.deny,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){decide(approval,false);}})
                    .create();
                dialog.setOnCancelListener(new DialogInterface.OnCancelListener(){public void onCancel(DialogInterface d){decide(approval,false);}});
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){decide(approval,false);}});
                dialog.show();
            }catch(RuntimeException e){pending=null;approval.reject();hide();}
        }
    }
    private void decide(ControlConnection.Approval approval,boolean accept){
        requireMain();synchronized(lock){
            if(pending==approval){pending=null;if(accept&&foreground&&!closed&&presented==approval&&!activity.isFinishing())approval.approve();else approval.reject();}
            if(presented==approval)hide();
        }
    }
    /** Clears identity before dismiss callbacks so a late callback cannot affect a replacement. */
    private void hide(){AlertDialog old=dialog;dialog=null;presented=null;if(old!=null)old.dismiss();}
    public void foreground(boolean value){
        requireMain();synchronized(lock){foreground=value&&!closed;if(!foreground){ControlConnection.Approval old=pending;pending=null;if(old!=null)old.reject();hide();}}
    }
    public void close(){requireMain();synchronized(lock){closed=true;}foreground(false);}
}
