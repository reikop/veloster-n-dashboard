import com.veloster.vehicle.Theme;

/** Theme selection, cycling and the N-palette remap. */
public final class ThemeTest {
    private static int checks;
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        Theme.select("n");check(Theme.current().map(0xffa4d9ef)==0xffa4d9ef,"N keeps colors");
        check(Theme.current().map(0x80a4d9ef)==0x80a4d9ef,"N keeps alpha");
        Theme.select("lambo");check(Theme.current().map(0xffa4d9ef)!=0xffa4d9ef,"lambo remaps the N accent");
        check((Theme.current().map(0x80a4d9ef)>>>24)==0x80,"remap keeps alpha");
        check(Theme.current().map(0xff3ddc84)==0xff3ddc84,"semantic green is shared");
        check(Theme.current().map(0xff123456)==0xff123456,"unknown color passes through");
        Theme.select("redblack");check(Theme.current().id.equals("n"),"removed red-black falls back to N");
        check(Theme.next("bogus").equals(Theme.IDS[1]),"an unknown id steps to the first theme after N");
        // Car-cluster themes: true black background so the screen does not glow at night.
        for(String id:new String[]{"ice","mono","amber","teal","night","rose","copper","red","green"}){Theme.select(id);
            check(Theme.current().id.equals(id)&&Theme.current().map(0xff040506)==0xff000000,id+": true black background");}
        Theme.select("bogus");check(Theme.current().id.equals("n"),"unknown id falls back to N");
        Theme.select(null);check(Theme.current().id.equals("n"),"null falls back to N");
        Theme.select("amber");check(Theme.current().dangerRing,"amber marks danger by shape");
        Theme.select("lambo");check(!Theme.current().dangerRing,"other themes keep the plain danger color");
        check(Theme.current().label.equals("LB")&&Theme.current().shape==Theme.SHAPE_HEX,"lambo label and hex cells");
        Theme.select("bmw");check(Theme.current().mStripe&&Theme.current().shape==Theme.SHAPE_ROUND&&Theme.current().label.equals("M"),"bmw stripe and round cells");
        Theme.select("rose");check(Theme.current().label.equals("RS")&&Theme.current().shape==Theme.SHAPE_ROUND&&!Theme.current().mStripe,"rose label and round cells without the M stripe");
        Theme.select("copper");check(Theme.current().label.equals("CU")&&Theme.current().shape==Theme.SHAPE_HEX,"copper label and hex cells");
        Theme.select("n");check(Theme.current().shape==Theme.SHAPE_CUT&&!Theme.current().mStripe,"N keeps today's cut corner");
        // Red and green: true black; red warns in yellow with the ring (its accent is red), green's lime stays apart from
        // the shared semantic green and the ECO accent.
        String[] order={"n","red","wine","copper","amber","champagne","navy","lambo","green","sage","emerald","teal","ice","graphite","bmw","night","purple","magenta","rose","mono"};check(java.util.Arrays.equals(Theme.IDS,order),"themes in colour order: N, then by hue, mono last");
        for(String id:new String[]{"graphite","champagne","sage"}){Theme.select(id);
            check(Theme.current().map(0xff040506)==0xff000000,id+": true black background");}
        Theme.select("navy");check(Theme.current().map(0xff040506)==0xff03060c,"navy: navy background");
        Theme.select("graphite");check(Theme.current().label.equals("GP")&&Theme.current().shape==Theme.SHAPE_CUT,"graphite label and cut cells");
        Theme.select("champagne");check(Theme.current().label.equals("CH")&&Theme.current().shape==Theme.SHAPE_ROUND&&Theme.current().digitWeight==0,"champagne round, light digits");
        Theme.select("navy");check(Theme.current().label.equals("NV")&&Theme.current().shape==Theme.SHAPE_ROUND,"navy round");
        Theme.select("sage");check(Theme.current().label.equals("SG")&&Theme.current().accent==0xff9dbfa6,"sage accent");
        for(int i=0;i<order.length;i++)check(Theme.next(order[i]).equals(order[(i+1)%order.length])&&Theme.of(order[i]).id.equals(order[i]),order[i]+": next follows the list and the id resolves");
        Theme.select("red");Theme red=Theme.current();
        check(red.label.equals("RD")&&red.map(0xff040506)==0xff000000&&red.dangerRing,"red: label, true black, danger ring");
        check(((red.danger>>16)&0xff)>0xe0&&((red.danger>>8)&0xff)>0xb0&&(red.accent&0xffffff)!=(red.danger&0xffffff),"red: warnings are yellow, not the red accent");
        Theme.select("green");Theme green=Theme.current();
        check(green.label.equals("GR")&&green.map(0xff040506)==0xff000000,"green: label, true black");
        check((green.accent&0xffffff)!=0x3ddc84&&(green.accent&0xffffff)!=0x6fe39a&&((green.accent>>16)&0xff)>0x80,"green: a lime accent, not the semantic or ECO green");
        for(String id:Theme.IDS){Theme.select(id);Theme t=Theme.current();check(t.map(t.danger)==t.danger,id+": danger is never remapped");}
        // A warning written on the accent-filled gear box must stay readable (WARM-UP / COOL-DOWN), and a calm
        // state must never be drawn in the danger colour (overview ESC/ABS/TCS "off").
        for(String id:Theme.IDS){Theme.select(id);Theme t=Theme.current();
            if(!id.equals("n"))check(Theme.contrast(t.readableOn(t.map(0xffa4d9ef),t.danger),t.map(0xffa4d9ef))>=3,id+": warning readable on the accent fill");
            check(t.calm(0xffa4d9ef)!=t.danger,id+": calm state is not the danger colour");}
        Theme.select("n");check(Theme.current().readableOn(0xffa4d9ef,0xffff4059)==0xffff4059,"N keeps today's colours (pixel-identical)");
        check(Theme.current().calm(0xffa4d9ef)==0xffa4d9ef,"N calm colour unchanged");
        // Drive-mode accent: over every theme, ECO and SPORT recolour the accent and its dim; NORMAL / off restore it.
        for(String id:Theme.IDS){Theme.select(id);Theme t=Theme.current();int own=t.map(0xffa4d9ef),ownDim=t.map(0xff233b46),bg=t.map(0xff040506);
            Theme.mode(3);check(t.map(0x80a4d9ef)==0x806fe39a&&t.map(0xff233b46)==0xff103a22,id+": ECO accent keeps alpha");
            Theme.mode(1);check(t.map(0xff8fd0f0)==0xffff5a36&&(0xff5a36)!=(t.danger&0xffffff),id+": SPORT accent, not the danger red");
            check(t.map(0xff040506)==bg&&t.map(t.danger)==t.danger,id+": mode leaves the rest alone");
            Theme.mode(0);check(t.map(0xffa4d9ef)==own&&t.map(0xff233b46)==ownDim,id+": NORMAL keeps the theme accent");
            Theme.mode(-1);check(t.map(0xffa4d9ef)==own,id+": off keeps the theme accent");}
        System.out.println("PASS "+checks+" theme checks");
    }
}
