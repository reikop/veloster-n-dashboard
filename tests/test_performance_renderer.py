"""Render the actual new dashboard page; fixtures never enter the APK."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from graphics_test_support import GRAPHICS
from test_quick_entry_runtime import java_tools

ROOT = Path(__file__).resolve().parents[1]
HARNESS = '''import com.veloster.vehicle.*;import com.veloster.i18n.UiText;import android.graphics.Canvas;
public class PerformancePreview {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static Telemetry fixture(){
  Telemetry t=new Telemetry();t.speed(75,1000);t.units(new byte[]{2,60,108},1000);
  t.rpm(new byte[]{0,0x22,0x38,0,0,0,0,0},1000);t.temperatures(new byte[]{(byte)176,(byte)185},1000);
  byte[] a=new byte[40];a[4]=3;a[7]=55;a[1]=10;a[20]=(byte)0xff;a[21]=(byte)0x79;a[22]=1;a[29]=35;a[31]=37;a[33]=36;a[35]=35;t.additional(a,1000);
  t.gforce(new byte[]{(byte)0x89,0x31,0,0,0x7d,0x1f,0,0},1000);
  t.hscanJsn(0xc7,7,new byte[]{0,0,74,8,32,0,0,0},1000);t.hscanJsn(0xc7,0x23,new byte[]{0,0,100,0,0,0,0,0},1000);return t;
 }
 public static void main(String[] args)throws Exception{
  int rendered=0;
  for(String language:new String[]{"ko","en"})for(int height:new int[]{480,425})for(String state:new String[]{"received","waiting","stale","invalid"}){
   UiText.configure(language,language);Telemetry t=state.equals("waiting")?new Telemetry():fixture();
   if(state.equals("invalid")){t.additional(null,1000);t.rpm(null,1000);t.temperatures(null,1000);t.gforce(null,1000);}
   Canvas c=new Canvas(800,height);PitRenderer renderer=new PitRenderer();renderer.setPerformancePage(true);renderer.draw(c,800,height,false,t,state.equals("stale")?61000:1000);
   check(c.texts.contains("PERFORMANCE")&&c.texts.contains("TPMS"),"new page rendered");
   for(String text:c.texts){check(!text.contains("NaN")&&!text.contains("Infinity"),"no invalid numeric text");if(language.equals("en"))check(!text.matches(".*[가-힣].*"),"English translation: "+text);}
   check(!c.texts.contains("D")&&!c.texts.contains("SPORT"),"no unsupported transmission/mode");
   if(state.equals("received")){check(c.texts.contains("2,190")&&c.texts.contains("75")&&c.texts.contains("3"),"received headline");check(c.texts.contains("14.20 V")&&c.texts.contains("+13.5 °")&&c.texts.contains("37"),"battery steering tires");}
   if(state.equals("waiting")||state.equals("stale")){check(!c.texts.contains("75")&&!c.texts.contains("2,190")&&!c.texts.contains("37"),"no absent/expired readings");int unknown=0;for(String text:c.texts)if(text.startsWith("—"))unknown++;check(unknown==17,"all 17 values unknown: "+unknown);}
   if(state.equals("invalid"))check(!c.texts.contains("2,190")&&!c.texts.contains("37"),"invalid packet clears readings");
   c.write(args[0]+"/performance-"+language+"-"+height+"-"+state+".png");rendered++;
  }
  UiText.configure("en","en");
  for(int page=0;page<4;page++){
   Canvas c=new Canvas(800,480);PitRenderer r=new PitRenderer();if(page==2)r.setGaugeLayout(new GaugeLayout());if(page==3)r.setGraphLayout(new GraphLayout());r.draw(c,800,480,page==1,fixture(),1000);
   check(!c.texts.contains("PERFORMANCE"),"existing page remains selectable");c.write(args[0]+"/existing-page-"+page+".png");
  }
  System.out.println("PASS "+rendered+" performance renders and 4 existing page renders");
 }
}'''

def render(folder):
    java, javac = java_tools()
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='vn-render-') as tmp:
        root = Path(tmp)
        sources = []
        for name, body in GRAPHICS.items():
            p = root / 'android/graphics' / (name + '.java')
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('package android.graphics;\n' + body)
            sources.append(p)
        for rel, body in (('android/content/Context.java','package android.content; public class Context {}'),
                          ('android/util/Log.java','package android.util; public class Log {public static int w(String a,String b){return 0;}public static int w(String a,String b,Throwable t){return 0;}public static int i(String a,String b){return 0;}}'),
                          ('com/veloster/vehicle/Fonts.java','package com.veloster.vehicle; public final class Fonts {public static volatile android.graphics.Typeface MEDIUM,BOLD;}'),
                          ('com/veloster/vehicle/ShiftBeep.java','package com.veloster.vehicle; final class ShiftBeep {static final int LATCH=1,UP=2,TAP=3;}')):   # the cassette keys' sound ids only
            q = root / rel; q.parent.mkdir(parents=True, exist_ok=True); q.write_text(body); sources.append(q)
        p = root / 'PerformancePreview.java'
        p.write_text(HARNESS)
        sources.append(p)
        sources += [ROOT / 'shared/src/com/veloster/vehicle' / (name + '.java') for name in ('Telemetry','PitRenderer','KeyPath','PerformanceRenderer','DashboardPage','ShiftGuide','MediaState','GaugeLayout','GaugeRenderer','OverviewLayout','GraphLayout','GraphRenderer','VehicleProfile','Theme','ThemedPaint','ThemeShapes','DriveStats','Records','RecordsRenderer','TaxiMeter','TaxiRenderer','TaxiTariff','TaxiZones','TaxiZoneData','DriveModes','RaceRenderer','LambdaCluster','TachoCluster','ClusterKit','RevMatch','ShiftCoach','GearRatios','MilLamp','Tiles','NightCluster','VfdCluster','HudCluster','GCircleCluster','WarpCluster','DragCluster','CassetteCluster','ShowRenderer','CircuitCluster','Circuits','CircuitData','CircuitLap','LapTimer','TrackPath','GpsFix')]
        sources.append(ROOT / 'shared/src/com/veloster/i18n/UiText.java')
        result = subprocess.run([javac,'-encoding','UTF-8','-d',str(root),*map(str,sources)],capture_output=True,text=True)
        if result.returncode: raise AssertionError(result.stderr)
        result = subprocess.run([java,'-Djava.awt.headless=true','-cp',str(root),'PerformancePreview',str(folder.resolve())],capture_output=True,text=True,timeout=40)
        if result.returncode: raise AssertionError(result.stdout + result.stderr)
        return result.stdout.strip()

class PerformanceRendererTests(unittest.TestCase):
    def test_rendered_states(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIn('PASS 16',render(tmp))

if __name__ == '__main__':
    import sys
    if len(sys.argv)==3 and sys.argv[1]=='--out': print(render(sys.argv[2]))
    else: unittest.main()
