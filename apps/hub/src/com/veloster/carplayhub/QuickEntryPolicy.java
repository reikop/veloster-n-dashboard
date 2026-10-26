package com.veloster.carplayhub;

/** Android-independent decisions for an explicitly enabled quick-entry service. */
public final class QuickEntryPolicy {
    public static final int OFF=0, NOTIFICATION=1, FLOATING=2;
    private QuickEntryPolicy() {}
    public static int mode(int value){return value==NOTIFICATION||value==FLOATING?value:OFF;}
    public static boolean restore(int value){return mode(value)!=OFF;}
    public static boolean visible(int value,boolean screenOn,boolean locked){return mode(value)==FLOATING&&screenOn&&!locked;}
    public static float fraction(float value){
        if(Float.isNaN(value)||Float.isInfinite(value))return 0.5f;
        return Math.max(0f,Math.min(1f,value));
    }
    public static int bound(int position,int span,int size){return Math.max(0,Math.min(Math.max(0,span-size),position));}
    public static int y(float fraction,int height,int size){return bound(Math.round(fraction(fraction)*Math.max(0,height-size)),height,size);}
    public static float relativeY(int position,int height,int size){
        int space=Math.max(0,height-size);return space==0?0.5f:fraction((float)bound(position,height,size)/space);
    }
    public static boolean right(int x,int width,int size){return x+size/2>=width/2;}
    public static int edge(boolean right,int width,int size){return right?Math.max(0,width-size):0;}
}
