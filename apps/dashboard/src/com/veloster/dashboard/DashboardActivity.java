package com.veloster.dashboard;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.veloster.vehicle.VehicleDash;
import com.veloster.i18n.LanguageSettings;

/** Own package, own task and own data subscriptions. Never starts the OEM NMode app. */
public final class DashboardActivity extends Activity {
    private VehicleDataClient client;
    /** On screen: the floating window (FloatService) stays down while true. */
    static volatile boolean resumed;
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LanguageSettings.init(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new FrameLayout(this));
        VehicleDash.attachStandalone(this);
        // One car data client for the app: the floating window keeps it running while the dashboard is away.
        client=VehicleDataClient.shared(this);
    }
    protected void onResume(){super.onResume();resumed=true;FloatService.stop(this);LanguageSettings.init(this);VehicleDash.resumeStandalone(this);client.start();}
    protected void onPause(){resumed=false;VehicleDash.pauseStandalone(this);FloatService.startIfOn(this);if(!FloatService.wanted)client.stop();super.onPause();}
    /** The back key closes the settings first; otherwise the activity goes back as before. */
    @Override public void onBackPressed(){if(!VehicleDash.back(this))super.onBackPressed();}
    protected void onDestroy(){if(client!=null&&!FloatService.wanted&&!resumed)client.stop();super.onDestroy();}
}
