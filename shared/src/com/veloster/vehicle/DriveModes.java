package com.veloster.vehicle;

/**
 * Veloster N drive modes as the stock N-mode service reports them (DriveMode.mCurrentDrviceMode, read with
 * NModeListener.onIndividualDriveModeChanged / NModeManager.getIndividualDriveMode; the stock N-mode app's
 * NModeUtils: 0 NORMAL, 1 SPORT, 2 ECO, 4 / 13 N CUSTOM, 6 N, 15 invalid). Telemetry.DRIVE_MODE keeps the i30 PD build's
 * CLU13 coding for NORMAL / SPORT / ECO (0 / 1 / 3) so its screens read it unchanged, plus 6 = N and 4 = N CUSTOM.
 * The mode-screen option follows the i30 PD build's ClusterPicker.onMode (sung9155/i30-pd-dashboard, MIT).
 * No Android: host-tested. Not yet confirmed in the car.
 */
public final class DriveModes {
    private DriveModes(){}
    public static final int NORMAL=0,SPORT=1,ECO=3,N_CUSTOM=4,N=6;
    /** Settings order (slots) and their codes. */
    public static final String[] NAMES={"SPORT","ECO","NORMAL","N","N CUSTOM"};
    public static final int[] CODES={SPORT,ECO,NORMAL,N,N_CUSTOM};
    /** Default screen per slot: -1 = 원래 화면 (the screen from before the first jump). */
    public static final int[] DEFAULT={-1,-1,-1,-1,-1};
    /** The DRIVE_MODE code for a stock service value, or -1 (invalid / unknown). */
    public static int fromOem(int v){
        switch(v){case 0:return NORMAL;case 1:return SPORT;case 2:return ECO;case 6:return N;case 4:case 13:return N_CUSTOM;default:return -1;}
    }
    public static int slot(int code){for(int i=0;i<CODES.length;i++)if(CODES[i]==code)return i;return -1;}
    public static String label(int code){int k=slot(code);return k<0?"—":NAMES[k];}
    /** A saved target: a page id, or -1 (원래 화면) when out of range. */
    public static int validTarget(int t){return t>=0&&t<DashboardPage.COUNT?t:-1;}
    /**
     * A change of mode opens that mode's screen (when it is shown); a mode set to 원래 화면 brings back the screen from
     * before the first jump, unless the driver moved off the jumped-to screen meanwhile. state = {page, back (-1 none)};
     * only a change between two known modes moves anything.
     */
    public static int[] onMode(int prev,int mode,int page,int back,int[] targets){
        if(prev<0||mode<0||prev==mode)return new int[]{page,back};
        int from=target(prev,targets),to=target(mode,targets);
        if(back>=0&&from>=0&&page!=from)back=-1;
        if(to>=0)return new int[]{to,back>=0?back:page};
        if(back>=0)return new int[]{DashboardPage.resolve(back),-1};
        return new int[]{page,back};
    }
    private static int target(int mode,int[] targets){
        int k=slot(mode),t=k<0?-1:validTarget(targets[k]);
        return t>=0&&DashboardPage.isShown(t)?t:-1;
    }
}
