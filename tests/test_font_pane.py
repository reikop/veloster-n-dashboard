"""Render the real 설정 → 글꼴 pane (SettingsPanel) with Java2D doubles and a stand-in font list; no device, no fonts read."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from graphics_test_support import GRAPHICS
from test_quick_entry_runtime import java_tools

ROOT = Path(__file__).resolve().parents[1]
# FontManager and FloatPanel need Android; the pane only reads these members.
STUBS = {
'com/veloster/vehicle/FontManager.java': '''package com.veloster.vehicle; import android.graphics.Typeface; import java.util.List;
public final class FontManager {
 public static final class Entry{public final String name,detail;public final Typeface regular,bold;public final boolean usable;
  public boolean load;public Entry(String n,String d,Typeface r,Typeface b,boolean u){name=n;detail=d;regular=r;bold=b;usable=u;}public boolean needsLoad(){return load;}}
 public static final class Snapshot{public final List<Entry> entries;public final int current;public final String currentName;public final boolean pending,root;public final long free;
  public Snapshot(List<Entry> e,int c,String n,boolean p,boolean r,long f){entries=e;current=c;currentName=n;pending=p;root=r;free=f;}}
}''',
'com/veloster/vehicle/FloatPanel.java': '''package com.veloster.vehicle; public final class FloatPanel {
 public static final int OFF=-1,MUSIC=9;public static int shape=OFF,lastShape=0,skin=-1,count=4,size=1,alpha=0,dn=0,skinDay=0,skinNight=0;public static boolean musicStrip;
 public static final String[] SIZE_NAMES={"a"},ALPHA_NAMES={"a"},SHAPE_NAMES={"a"},SKIN_NAMES={"a"};
 public static int[] counts(int s){return new int[]{4};}public static boolean round(int s){return false;}public static boolean presetMatch(int i){return false;}
 public static float width(int a,int b,int c){return 0;}public static float height(int a,int b,int c){return 0;}
 public static void drawStill(android.graphics.Canvas c,int a,int b,int d,Object e,Object f,long g){}public static Object example(){return null;}}''',
'android/util/Log.java': 'package android.util; public class Log {public static int w(String a,String b){return 0;}public static int w(String a,String b,Throwable t){return 0;}public static int i(String a,String b){return 0;}}',
}
HARNESS = '''import com.veloster.vehicle.*;import com.veloster.i18n.UiText;import android.graphics.*;import java.util.*;
public class FontPanePreview {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static final Set<String> SAMPLE=new HashSet<String>(Arrays.asList("가나다라 벨로스터 N","목적지까지 12.4 km","서울특별시 강남구 테헤란로 152","ABCDEFG abcdefg 0123456789"));
 public static void main(String[] args)throws Exception{
  int rendered=0;
  for(String language:new String[]{"ko","en"})for(int state=0;state<4;state++){
   UiText.configure(language,language);
   SettingsPanel.State s=new SettingsPanel.State();s.cat=SettingsPanel.FONT;
   List<FontManager.Entry> l=new ArrayList<FontManager.Entry>();
   l.add(new FontManager.Entry(UiText.t("순정 글꼴 (Mobis · Roboto)"),UiText.t("공장 출고 설정"),Typeface.DEFAULT,Typeface.DEFAULT_BOLD,true));
   FontManager.Entry app=new FontManager.Entry(UiText.t("부산단디고딕"),UiText.t("앱에 포함")+" · "+UiText.t("고딕")+" · R·B · "+UiText.t("한글 전체")+" · 1.4 MB",null,null,true);app.load=true;l.add(app);
   l.add(new FontManager.Entry("Asta Sans",UiText.t("내비에 설치됨")+" · R·B·L · "+UiText.t("한글 전체"),Typeface.DEFAULT,Typeface.DEFAULT_BOLD,true));
   for(int i=0;i<6;i++)l.add(new FontManager.Entry("Installed Font "+i,UiText.t("내비에 설치됨")+" · R · "+UiText.t(i==2?"한글 없음":"한글 일부"),i==4?null:Typeface.DEFAULT,null,i!=4));
   if(state==0){s.fontBusy=true;}
   else{s.fonts=new FontManager.Snapshot(l,2,"Asta Sans",state==2,state!=3,state==3?-1:12_400_000L);s.fontPick=state==3?7:state==2?1:2;s.fontTop=state==3?2:0;}
   Canvas c=new Canvas(800,480);new SettingsPanel().draw(c,s,1000);
   check(c.texts.contains(UiText.t("글꼴"))&&c.texts.contains(UiText.t("선택한 글꼴 적용"))&&c.texts.contains(UiText.t("재부팅")),"pane drawn: "+c.texts);
   if(state==0)check(c.texts.contains(UiText.t("글꼴을 찾는 중…")),"busy before the first scan");
   if(state==2)check(c.texts.contains(UiText.t("불러오는 중…")),"bundled font loading");
   if(state==1)check(c.texts.contains("● Asta Sans")&&c.texts.contains(SAMPLE.iterator().next())||c.texts.containsAll(SAMPLE),"current marked and preview drawn");
   if(state==2)check(c.texts.contains(UiText.t("재부팅하면 화면에 반영됩니다")),"pending reboot shown");
   if(state==3)check(c.texts.contains(UiText.t("이 글꼴을 열지 못했습니다"))&&c.texts.contains("Installed Font 4"),"second page and unusable preview");
   for(String t:c.texts){check(!t.contains("NaN")&&!t.contains("null"),"bad text: "+t);if(language.equals("en")&&!SAMPLE.contains(t))check(!t.matches(".*[가-힣].*"),"English: "+t);}
   c.write(args[0]+"/fonts-"+language+"-"+state+".png");rendered++;
  }
  // hit-testing matches the drawing
  check(SettingsPanel.hit(SettingsPanel.FONT,300,90)==SettingsPanel.FONT_PICK,"first row");
  check(SettingsPanel.hit(SettingsPanel.FONT,300,90+5*50)==SettingsPanel.FONT_PICK+5,"sixth row");
  check(SettingsPanel.hit(SettingsPanel.FONT,200,385)==SettingsPanel.FONT_UP&&SettingsPanel.hit(SettingsPanel.FONT,270,385)==SettingsPanel.FONT_DOWN&&SettingsPanel.hit(SettingsPanel.FONT,400,385)==SettingsPanel.FONT_SCAN,"list buttons");
  check(SettingsPanel.hit(SettingsPanel.FONT,560,436)==SettingsPanel.FONT_APPLY&&SettingsPanel.hit(SettingsPanel.FONT,700,436)==SettingsPanel.FONT_REBOOT,"apply and reboot");
  check(SettingsPanel.hit(SettingsPanel.FONT,600,150)==SettingsPanel.NONE,"preview is not a button");
  for(int i=0;i<SettingsPanel.CATS;i++)check(SettingsPanel.hit(SettingsPanel.FONT,90,60+i*40+18)==100+i,"category "+i);
  check(SettingsPanel.hit(SettingsPanel.FONT,90,444)==SettingsPanel.DONE,"done below the nine categories");
  System.out.println("PASS "+rendered+" font pane renders and hit tests");
 }
}'''


def render(folder):
    java, javac = java_tools()
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='vn-fonts-render-') as tmp:
        root = Path(tmp)
        sources = []
        for name, body in GRAPHICS.items():
            if name == 'Paint':
                body = body.replace('public void setStrokeCap(Cap c){}', 'public void setStrokeCap(Cap c){}public void setFakeBoldText(boolean b){}')
            p = root / 'android/graphics' / (name + '.java')
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('package android.graphics;\n' + body)
            sources.append(p)
        for rel, body in STUBS.items():
            q = root / rel
            q.parent.mkdir(parents=True, exist_ok=True)
            q.write_text(body)
            sources.append(q)
        p = root / 'FontPanePreview.java'
        p.write_text(HARNESS)
        sources.append(p)
        sources += [ROOT / 'shared/src/com/veloster/vehicle' / (name + '.java') for name in
                    ('SettingsPanel', 'DashboardPage', 'Theme', 'ThemedPaint', 'ThemeShapes', 'DriveModes', 'SpeedChime', 'Records', 'DriveStats', 'VehicleProfile', 'ShiftGuide', 'Telemetry', 'GearRatios', 'ShiftCoach', 'RevMatch')]
        sources.append(ROOT / 'shared/src/com/veloster/i18n/UiText.java')
        result = subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(root), *map(str, sources)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)
        result = subprocess.run([java, '-Djava.awt.headless=true', '-cp', str(root), 'FontPanePreview', str(folder.resolve())], capture_output=True, text=True, timeout=40)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        return result.stdout.strip()


class FontPaneTests(unittest.TestCase):
    def test_rendered_states(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIn('PASS 8', render(tmp))


if __name__ == '__main__':
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == '--out':
        print(render(sys.argv[2]))
    else:
        unittest.main()
