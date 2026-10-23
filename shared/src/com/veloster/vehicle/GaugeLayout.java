package com.veloster.vehicle;

/** Six or twelve display slots. Preferences never write vehicle settings. */
public final class GaugeLayout {
    public static final int SLOTS=12;
    private static final int[] DEFAULT_SIGNALS={0,7,9,1,2,10,11,12,15,16,17,13};
    private final int[] signals=new int[SLOTS], styles=new int[SLOTS];
    private boolean dense;
    public GaugeLayout(){reset();}
    private static void slot(int i){if(i<0||i>=SLOTS)throw new IllegalArgumentException("slot");}
    public int signal(int i){slot(i);return signals[i];}
    public int style(int i){slot(i);return styles[i];}
    public int count(){return dense?12:6;}
    public int columns(){return dense?4:3;}
    public boolean dense(){return dense;}
    public void dense(boolean value){dense=value;}
    public float left(int i){slot(i);return 12+(i%columns())*(dense?196:262);}
    public float top(int i){slot(i);return 80+(i/columns())*(dense?132:198);}
    public float width(){return dense?188:252;}
    public float height(){return dense?124:190;}
    public void set(int i,int signal,int style){
        slot(i);
        if(signal<0||signal>=Telemetry.LABELS.length||style<0||style>2)throw new IllegalArgumentException("gauge selection");
        signals[i]=signal;styles[i]=style;
    }
    public void swap(int a,int b){slot(a);slot(b);int n=signals[a];signals[a]=signals[b];signals[b]=n;n=styles[a];styles[a]=styles[b];styles[b]=n;}
    public void reset(){for(int i=0;i<SLOTS;i++){signals[i]=DEFAULT_SIGNALS[i];styles[i]=i<3?0:2;}}
    /** Card bounds in the 800 x 480 logical canvas. Gaps are not touch targets. */
    public int hit(float x,float y){
        for(int i=0;i<count();i++){float l=left(i),t=top(i);if(x>=l&&x<l+width()&&y>=t&&y<t+height())return i;}
        return -1;
    }
}
