package com.veloster.vehicle;

/** Three selectable time-series plots; preferences are independent of gauge cards. */
public final class GraphLayout {
    public static final int SLOTS=3;
    private final int[] signals={1,2,Telemetry.STEERING};
    private long window=60000;
    public int signal(int slot){check(slot);return signals[slot];}
    public void signal(int slot,int signal){check(slot);if(signal<0||signal>=Telemetry.LABELS.length)throw new IllegalArgumentException("graph signal");signals[slot]=signal;}
    public long window(){return window;}
    public void window(long value){if(value!=60000&&value!=300000)throw new IllegalArgumentException("graph window");window=value;}
    public static float top(int slot){check(slot);return 86+130*slot;}
    private static void check(int slot){if(slot<0||slot>=SLOTS)throw new IllegalArgumentException("graph slot");}
    public int hit(float x,float y){if(x<18||x>=782)return -1;for(int i=0;i<SLOTS;i++)if(y>=top(i)&&y<top(i)+124)return i;return -1;}
}
