import com.veloster.vehicle.SettingsBackup;
import java.util.Map;
import java.util.TreeMap;

/** 설정 백업: the text keeps every value and its type, damaged lines are skipped, the file is replaced whole. */
public final class SettingsBackupTest {
    static int checks;
    static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
    public static void main(String[] a) throws Exception {
        Map<String,Object> ui=new TreeMap<String,Object>();
        ui.put("theme","n");ui.put("tile_classic_0",17);ui.put("mode_color",true);ui.put("speed_cal_ratio",0.9731f);ui.put("regen_odo",Float.NaN);
        ui.put("last",1234567890123L);ui.put("note","tab\there\nnew line \\ back");ui.put("empty","");ui.put("neg",-1);
        Map<String,Object> rec=new TreeMap<String,Object>();rec.put("run_0","5.36@2026-11-21,5.37@2026-11-21");
        Map<String,Map<String,Object>> all=new TreeMap<String,Map<String,Object>>();all.put("pit_vehicle_ui",ui);all.put("pit_vehicle_records",rec);
        String text=SettingsBackup.text(all);
        Map<String,Map<String,Object>> back=SettingsBackup.values(text);
        check(back.equals(all),"every value and type comes back: "+back);
        check(back.get("pit_vehicle_ui").get("tile_classic_0") instanceof Integer&&back.get("pit_vehicle_ui").get("last") instanceof Long
            &&back.get("pit_vehicle_ui").get("speed_cal_ratio") instanceof Float&&back.get("pit_vehicle_ui").get("mode_color") instanceof Boolean,"types");
        check(SettingsBackup.text(back).equals(text),"the same settings give the same text (no rewrite)");
        check(SettingsBackup.line("f","k",new java.util.HashSet<String>())==null,"a string set is not kept");
        // damaged or foreign lines are skipped, the rest still restores
        Map<String,Map<String,Object>> part=SettingsBackup.values("garbage\nf\tk\ti\tnot a number\nf\tk2\tb\tmaybe\nf\tk3\tx\t1\n\tk\ti\t1\nf\tok\ti\t7\n");
        check(part.size()==1&&part.get("f").size()==1&&part.get("f").get("ok").equals(7),"only the good line: "+part);
        check(SettingsBackup.values("").isEmpty(),"an empty file restores nothing");
        // the file: written whole, replaced whole
        java.io.File dir=java.nio.file.Files.createTempDirectory("backup").toFile(),f=new java.io.File(new java.io.File(dir,"sub"),"settings.txt");
        SettingsBackup.write(f,text);check(SettingsBackup.read(f).equals(text),"written and read");
        SettingsBackup.write(f,"f\tk\ti\t1\n");check(SettingsBackup.read(f).equals("f\tk\ti\t1\n")&&f.getParentFile().list().length==1,"replaced, no temporary file left");
        f.delete();f.getParentFile().delete();dir.delete();
        System.out.println("PASS "+checks+" settings backup checks");
    }
}
