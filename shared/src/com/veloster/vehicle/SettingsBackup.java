package com.veloster.vehicle;

import java.util.Map;
import java.util.TreeMap;

/**
 * 설정 백업: every preference of the app (screens, tiles, theme, floating window, records) as one text file on the head
 * unit's storage, where it survives 삭제. Written when the dashboard goes to the background; after a fresh install the
 * dashboard asks once whether to put it back. One value per line: file TAB key TAB type TAB value.
 * Plain Java (host-tested); VehicleDash reads and writes the preferences themselves (backupSave, backupOffer).
 */
public final class SettingsBackup {
    private SettingsBackup(){}
    /** Preference (pit_vehicle_ui): the question was answered, or there was nothing to ask. Nothing is written before it,
     *  so a fresh install cannot overwrite the backup with its defaults while the question is still open. */
    public static final String ASKED="backup_asked",PREFS="pit_vehicle_ui";
    public static final String DIR="vndashboard",FILE="settings.txt";

    // ---- text ---------------------------------------------------------------------------------------------------
    /** One value as a line, or null for a type that is not kept (string sets: the app has none). */
    public static String line(String file,String key,Object v){
        String t=v instanceof Integer?"i":v instanceof Boolean?"b":v instanceof Float?"f":v instanceof Long?"l":v instanceof String?"s":null;
        return t==null?null:file+"\t"+esc(key)+"\t"+t+"\t"+esc(String.valueOf(v));
    }
    /** {file, key, value} of a line, or null when it is not one (a damaged line is skipped, not guessed). */
    public static Object[] parse(String line){
        String[] p=line.split("\t",-1);if(p.length!=4||p[0].isEmpty()||p[2].length()!=1)return null;
        String s=unesc(p[3]);Object v;
        try{switch(p[2].charAt(0)){
            case 'i':v=Integer.valueOf(s);break;case 'f':v=Float.valueOf(s);break;case 'l':v=Long.valueOf(s);break;
            case 'b':if(!s.equals("true")&&!s.equals("false"))return null;v=Boolean.valueOf(s);break;
            case 's':v=s;break;default:return null;}
        }catch(NumberFormatException e){return null;}
        return new Object[]{p[0],unesc(p[1]),v};
    }
    /** All files' values as text, in a fixed order (so "unchanged" is a string compare). */
    public static String text(Map<String,? extends Map<String,?>> files){
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,? extends Map<String,?>> f:new TreeMap<String,Map<String,?>>(files).entrySet())
            for(Map.Entry<String,?> e:new TreeMap<String,Object>(f.getValue()).entrySet()){String l=line(f.getKey(),e.getKey(),e.getValue());if(l!=null)b.append(l).append('\n');}
        return b.toString();
    }
    /** The values of a backup text, per file. */
    public static Map<String,Map<String,Object>> values(String text){
        Map<String,Map<String,Object>> out=new TreeMap<String,Map<String,Object>>();
        for(String l:text.split("\n")){Object[] p=parse(l);if(p==null)continue;
            Map<String,Object> m=out.get(p[0]);if(m==null)out.put((String)p[0],m=new TreeMap<String,Object>());
            m.put((String)p[1],p[2]);}
        return out;
    }
    private static String esc(String s){return s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n").replace("\r","\\r");}
    private static String unesc(String s){
        if(s.indexOf('\\')<0)return s;
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);
            if(c!='\\'||i+1>=s.length()){b.append(c);continue;}
            char n=s.charAt(++i);b.append(n=='t'?'\t':n=='n'?'\n':n=='r'?'\r':n);}
        return b.toString();
    }

    // ---- the file ------------------------------------------------------------------------------------------------
    /** Writes text to f through a temporary file: the old backup stays whole until the new one is complete. */
    public static void write(java.io.File f,String text) throws java.io.IOException {
        java.io.File tmp=new java.io.File(f.getPath()+".tmp"),dir=f.getParentFile();
        if(dir.mkdirs()){dir.setReadable(true,false);dir.setWritable(true,false);dir.setExecutable(true,false);}   // open to every app id: a reinstalled app may get another id (storage that keeps owners)
        java.io.FileOutputStream out=new java.io.FileOutputStream(tmp);
        try{out.write(text.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
        tmp.setReadable(true,false);
        if(!tmp.renameTo(f)){f.delete();if(!tmp.renameTo(f))throw new java.io.IOException("rename "+tmp);}   // a file system that does not rename over a file
    }
    public static String read(java.io.File f) throws java.io.IOException {
        byte[] b=new byte[(int)f.length()];
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(f));try{in.readFully(b);}finally{in.close();}
        return new String(b,"UTF-8");
    }
}
