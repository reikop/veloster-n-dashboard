package com.veloster.vehicle;

/**
 * Stable preference IDs, the screen list (which screens are shown and in what order) and the header tab strip in the
 * dashboard's 800x480 space. The tabs sit in a horizontally scrollable strip between the N / PIT logo and the gear
 * button; `scroll` is the strip offset in pixels (0 = first tab at the left edge). Only shown screens get a tab; the
 * order and hidden set come from 설정 → 화면 (idea from the i30 PD build's screen list, sung9155/i30-pd-dashboard, MIT).
 * No Android: host-tested.
 */
public final class DashboardPage {
    public static final int OVERVIEW=0,F1=1,GAUGES=2,GRAPHS=3,PERFORMANCE=4,TAXI=5,RECORDS=6,RACE=7,LAMBDA=8,TACHO=9,NEON=10,COUNT=11;
    /** Strip bounds and tab spacing. */
    public static final float STRIP_LEFT=138,STRIP_RIGHT=486,GAP=6,TOP=8,BOTTOM=46;
    /** Gear button (settings) right of the strip. */
    public static final float GEAR_LEFT=492,GEAR_WIDTH=44;
    /** Each page's tab width, by page id. */
    private static final int[] WIDTH={100,72,84,110,120,84,72,84,84,72,120};
    /** Korean names (UiText at the call) and the settings list's second line. */
    public static final String[] NAMES={"전체 조망","F1","게이지","그래프","퍼포먼스","택시","기록","레이스","람다","타코","네온 드라이브"};
    public static final String[] DESC={"모든 값 한눈에","F1 스타일 계기판","고른 값 게이지","시간 그래프","N 퍼포먼스 화면","택시 미터기","가속 측정·최고값","변속 표시·공기압·G볼","Λ RPM·속도계","원형 RPM·다운시프트","도트 드라이브 화면"};
    private static final int ALL=(1<<COUNT)-1;
    /** Display order and hidden mask in use (VehicleDash sets them from preferences). */
    private static int[] order=naturalOrder();
    private static int hidden;
    private static int[] shown=slots(order,0);
    private DashboardPage(){}
    public static int normalize(int page){return page>=0&&page<COUNT?page:OVERVIEW;}
    public static int width(int page){return WIDTH[normalize(page)];}
    public static boolean gear(float x,float y){return x>=GEAR_LEFT&&x<=GEAR_LEFT+GEAR_WIDTH&&y>=TOP&&y<=BOTTOM;}

    // ---- screen list ---------------------------------------------------------------------------------------------
    public static int[] naturalOrder(){int[] o=new int[COUNT];for(int i=0;i<COUNT;i++)o[i]=i;return o;}
    /** A saved order repaired to a permutation of every page; pages added later go after a shorter saved order. */
    public static int[] validOrder(int[] o){
        if(o==null)return naturalOrder();
        boolean[] seen=new boolean[COUNT];int[] out=new int[COUNT];int n=0;
        for(int p:o){if(p<0||p>=COUNT||seen[p])return naturalOrder();seen[p]=true;out[n++]=p;}
        for(int p=0;p<COUNT;p++)if(!seen[p])out[n++]=p;
        return out;
    }
    /** A saved hidden mask, or 0 (all shown) when out of range or hiding every page. */
    public static int validHidden(int h){return (h&~ALL)!=0||(h&ALL)==ALL?0:h;}
    public static void configure(int[] newOrder,int newHidden){order=validOrder(newOrder);hidden=validHidden(newHidden);shown=slots(order,hidden);}
    public static int[] order(){return order.clone();}
    public static int hidden(){return hidden;}
    public static boolean isShown(int page){return (hidden&(1<<normalize(page)))==0;}
    /** Shown pages in display order (the tabs). */
    public static int[] shown(){return shown.clone();}
    private static int[] slots(int[] o,int h){int n=0;for(int p:o)if((h&(1<<p))==0)n++;int[] r=new int[n];n=0;for(int p:o)if((h&(1<<p))==0)r[n++]=p;return r;}
    /** Flip a page's visibility; the last shown page stays shown. */
    public static int toggle(int h,int page){int next=h^(1<<normalize(page));return (next&ALL)==ALL?h:next;}
    /** The order with the page at position i swapped with its neighbour i+d (d = ±1); unchanged at the ends. */
    public static int[] move(int[] o,int i,int d){o=validOrder(o);int j=i+d;if(i<0||i>=o.length||j<0||j>=o.length)return o;int t=o[i];o[i]=o[j];o[j]=t;return o;}
    /** page when shown, else the next shown page in display order. */
    public static int resolve(int page){
        page=normalize(page);if(isShown(page))return page;
        int at=0;for(int i=0;i<order.length;i++)if(order[i]==page)at=i;
        for(int k=1;k<=order.length;k++){int p=order[(at+k)%order.length];if(isShown(p))return p;}
        return OVERVIEW;
    }
    public static String csv(int[] o){StringBuilder s=new StringBuilder();for(int i=0;i<o.length;i++){if(i>0)s.append(',');s.append(o[i]);}return s.toString();}
    public static int[] parse(String s){if(s==null||s.length()==0)return null;String[] p=s.split(",");int[] o=new int[p.length];try{for(int i=0;i<p.length;i++)o[i]=Integer.parseInt(p[i].trim());}catch(NumberFormatException e){return null;}return o;}

    // ---- header strip --------------------------------------------------------------------------------------------
    /** Left edge of a page's tab inside the strip content (before scrolling); -1 when the page has no tab. */
    static float contentLeft(int page){
        float x=0;
        for(int p:shown){if(p==normalize(page))return x;x+=WIDTH[p]+GAP;}
        return -1;
    }
    public static float contentWidth(){float x=-GAP;for(int p:shown)x+=WIDTH[p]+GAP;return Math.max(0,x);}
    public static float maxScroll(){return Math.max(0,contentWidth()-(STRIP_RIGHT-STRIP_LEFT));}
    public static float clampScroll(float scroll){return Math.max(0,Math.min(maxScroll(),scroll));}
    /** Screen left edge of a page's tab at a scroll offset. */
    public static float left(int page,float scroll){return STRIP_LEFT+contentLeft(page)-scroll;}
    public static float left(int page){return left(page,0);}
    /** Smallest scroll change that shows the whole tab of `page`. */
    public static float reveal(int page,float scroll){
        float l=contentLeft(page);if(l<0)return clampScroll(scroll);
        float r=l+width(page),view=STRIP_RIGHT-STRIP_LEFT;
        if(l<scroll)return clampScroll(l);
        if(r>scroll+view)return clampScroll(r-view);
        return clampScroll(scroll);
    }
    public static boolean inStrip(float x,float y){return x>=STRIP_LEFT&&x<=STRIP_RIGHT&&y>=TOP&&y<=BOTTOM;}
    public static int hit(float x,float y,float scroll){
        if(!inStrip(x,y))return -1;
        for(int p:shown){float l=left(p,scroll);if(x>=l&&x<=l+WIDTH[p])return p;}
        return -1;
    }
    public static int hit(float x,float y){return hit(x,y,0);}
    public static int tap(float downX,float downY,float x,float y,float scroll){
        int page=hit(x,y,scroll);return page>=0&&page==hit(downX,downY,scroll)?page:-1;
    }
    public static int tap(float downX,float downY,float x,float y){return tap(downX,downY,x,y,0);}
}
