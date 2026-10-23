package com.veloster.dashboard;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.veloster.vehicle.VehicleDash;

/** Own package, own task and own data subscriptions. Never starts the OEM NMode app. */
public final class DashboardActivity extends Activity {
    private VehicleDataClient client;
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new FrameLayout(this));
        VehicleDash.attachStandalone(this);
        client=new VehicleDataClient(this);
    }
    protected void onResume(){super.onResume();VehicleDash.resumeStandalone(this);client.start();}
    protected void onPause(){client.stop();VehicleDash.pauseStandalone(this);super.onPause();}
    protected void onDestroy(){if(client!=null)client.stop();super.onDestroy();}
}
