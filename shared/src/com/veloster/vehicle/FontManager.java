package com.veloster.vehicle;

import com.veloster.i18n.UiText;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.StatFs;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 설정 → 글꼴: lists the factory set, the fonts inside the app (BundledFonts) and the fonts the head unit already has
 * (/system/fonts; files on the storage or a USB stick are not offered); previews them; and makes the chosen family the navigation's system font. SystemFonts builds the change; it runs as root through
 * the OEM /system/bin/amossu (setuid root; owner-confirmed on 2026-11-25) and is read back before it counts. The new
 * font shows after a reboot, requested the way My Apps → 강제 재시작 does. Scanning, loading, applying and rebooting
 * are called off the UI thread.
 */
public final class FontManager {
    public static final String AMOSSU="/system/bin/amossu",PREFS="vn_fonts";
    /** One row of the list. A font inside the app has bundled set and no family until it is applied; its typefaces load
     *  when the row is picked (load), so the six of them are not all held in memory. */
    public static final class Entry{
        public final SystemFonts.Family family;public final BundledFonts.Font bundled;public final String name,detail;public final boolean usable;
        public volatile Typeface regular,bold;
        Entry(SystemFonts.Family family,BundledFonts.Font bundled,String name,String detail,Typeface regular,Typeface bold,boolean usable){
            this.family=family;this.bundled=bundled;this.name=name;this.detail=detail;this.regular=regular;this.bold=bold;this.usable=usable;}
        public boolean stock(){return family!=null&&family.stock();}
        /** All 11,172 Hangul syllables (every bundled font has them). */
        public boolean hangul(){return bundled!=null||family!=null&&family.regular!=null&&family.regular.fullHangul();}
        public boolean variable(){return family!=null&&family.regular!=null&&family.regular.variable;}
        /** Stable across scans: the source and the regular file. */
        public String key(){return bundled!=null?"app:"+bundled.regular:family.source+":"+(family.regular==null?"":family.regular.path);}
        public boolean needsLoad(){return bundled!=null&&regular==null;}
    }
    /** What a scan found. */
    public static final class Snapshot{
        public final List<Entry> entries;public final int current;public final String currentName;public final boolean pending,root;public final long free;
        Snapshot(List<Entry> entries,int current,String currentName,boolean pending,boolean root,long free){
            this.entries=entries;this.current=current;this.currentName=currentName;this.pending=pending;this.root=root;this.free=free;}
    }

    private final Context context;
    public FontManager(Context c){context=c.getApplicationContext();}
    private SharedPreferences prefs(){return context.getSharedPreferences(PREFS,0);}

    // ---- scan ---------------------------------------------------------------------------------------------------
    private static boolean fontName(String n){String l=n.toLowerCase(Locale.US);return (l.endsWith(".ttf")||l.endsWith(".otf"))&&!n.startsWith(".");}
    private static List<FontFile> read(File dir,Set<String> skip){
        List<FontFile> out=new ArrayList<FontFile>();File[] files=dir.listFiles();if(files==null)return out;
        for(File f:files){
            if(!f.isFile()||!fontName(f.getName())||skip.contains(f.getName()))continue;
            try{out.add(FontFile.read(f));}catch(IOException e){android.util.Log.i("VehicleDash","font skipped "+f+": "+e.getMessage());}
            catch(RuntimeException e){android.util.Log.w("VehicleDash","font skipped "+f,e);}
        }
        return out;
    }
    static Typeface load(FontFile f){
        if(f==null)return null;
        try{return Typeface.createFromFile(f.path);}catch(RuntimeException e){android.util.Log.w("VehicleDash","font preview "+f.path+": "+e);return null;}
    }

    public Snapshot scan(){
        String xml=text(new File(SystemFonts.XML));
        Set<String> skip=new HashSet<String>(SystemFonts.stockFiles());skip.addAll(SystemFonts.referenced(text(new File(SystemFonts.FALLBACK))));
        // a bundled font already copied in shows on its own row, not again as an installed one
        for(BundledFonts.Font b:BundledFonts.FONTS){skip.add(BundledFonts.installedRegular(b));String bold=BundledFonts.installedBold(b);if(bold!=null)skip.add(bold);
            Collections.addAll(skip,b.aliases);}
        rememberBoot(xml);
        String now=SystemFonts.defaultRegular(xml);
        List<Entry> entries=new ArrayList<Entry>();int current=-1;
        File stockRegular=new File(SystemFonts.FONTS,SystemFonts.STOCK_REGULAR);FontFile stock=null;
        try{if(stockRegular.isFile())stock=FontFile.read(stockRegular);}catch(IOException e){android.util.Log.i("VehicleDash","stock font: "+e.getMessage());}
        SystemFonts.Family stockFamily=SystemFonts.stock(stock);
        entries.add(new Entry(stockFamily,null,UiText.t("순정 글꼴 (Mobis · Roboto)"),UiText.t("공장 출고 설정"),load(stock),null,missingStock().isEmpty()));
        if(SystemFonts.STOCK_REGULAR.equals(now))current=0;
        for(BundledFonts.Font b:BundledFonts.FONTS){
            boolean alias=b.aliases.length>0&&new File(SystemFonts.FONTS,b.aliases[0]).isFile();
            boolean installed=alias||new File(SystemFonts.FONTS,BundledFonts.installedRegular(b)).isFile();
            entries.add(new Entry(null,b,UiText.t(b.name),bundledDetail(b,installed),null,null,true));
            if(now!=null&&(now.equals(BundledFonts.installedRegular(b))||b.aliases.length>0&&now.equals(b.aliases[0])))current=entries.size()-1;
        }
        List<SystemFonts.Family> families=new ArrayList<SystemFonts.Family>();
        families.addAll(SystemFonts.group(read(new File(SystemFonts.FONTS),skip),SystemFonts.SYSTEM));
        for(SystemFonts.Family f:families){
            Typeface r=load(f.regular),b=f.bold==null?null:load(f.bold);
            entries.add(new Entry(f,null,f.name,detail(f,r!=null),r,b,r!=null));
            if(current<0&&now!=null&&f.source==SystemFonts.SYSTEM&&now.equals(f.installedName(f.regular)))current=entries.size()-1;
        }
        String currentName=current>=0?entries.get(current).name:now==null?UiText.t("확인 불가"):now;
        return new Snapshot(entries,current,currentName,pending(),new File(AMOSSU).isFile(),free());
    }
    static String bundledDetail(BundledFonts.Font b,boolean installed){
        return UiText.t(installed?"앱에 포함 · 내비에 설치됨":"앱에 포함")+" · "+UiText.t(b.kind==BundledFonts.HANDWRITING?"손글씨":"고딕")
            +" · "+(b.bold!=null?"R·B":"R")+" · "+UiText.t("한글 전체")+String.format(Locale.US," · %.1f MB",b.bytes/1048576.0);
    }
    static String detail(SystemFonts.Family f,boolean usable){
        StringBuilder b=new StringBuilder();
        b.append(UiText.t("내비에 설치됨"));
        b.append(" · ").append(f.weights());
        FontFile r=f.regular;
        b.append(" · ").append(r.fullHangul()?UiText.t("한글 전체"):r.hangul>0?UiText.t("한글 일부"):UiText.t("한글 없음"));
        if(r.variable)b.append(" · ").append(UiText.t("가변"));
        if(!usable)b.append(" · ").append(UiText.t("사용 불가"));
        return b.toString();
    }
    private List<String> missingStock(){List<String> l=new ArrayList<String>();for(String n:SystemFonts.stockFiles())if(!new File(SystemFonts.FONTS,n).isFile())l.add(n);return l;}
    private static long free(){try{StatFs s=new StatFs("/system");return (long)s.getAvailableBlocks()*s.getBlockSize();}catch(RuntimeException e){return -1;}}

    /** Loads a bundled row's typefaces straight from the APK and drops the other bundled rows' (one held at a time). */
    public void load(Snapshot s,Entry e){
        if(e.bundled==null||e.regular!=null)return;
        for(Entry o:s.entries)if(o!=e&&o.bundled!=null){o.regular=null;o.bold=null;}
        try{
            Typeface b=e.bundled.bold==null?null:Typeface.createFromAsset(context.getAssets(),BundledFonts.DIR+"/"+e.bundled.bold);
            e.bold=b;e.regular=Typeface.createFromAsset(context.getAssets(),BundledFonts.DIR+"/"+e.bundled.regular);
        }catch(RuntimeException x){android.util.Log.w("VehicleDash","bundled font "+e.bundled.regular+": "+x);}
    }

    // ---- boots: a change shows after the next one; files the running system may open are kept -------------------
    static String bootId(){String s=text(new File("/proc/sys/kernel/random/boot_id"));return s==null?"":s.trim();}
    /** The first scan after a boot keeps the files that boot started with (SystemFonts.plan never removes them). */
    private void rememberBoot(String xml){
        String id=bootId();SharedPreferences p=prefs();
        if(id.length()>0&&!id.equals(p.getString("boot_id",""))&&xml!=null){
            StringBuilder b=new StringBuilder();for(String n:SystemFonts.referenced(xml))b.append(n).append('\n');
            p.edit().putString("boot_id",id).putString("boot_files",b.toString()).commit();
        }
    }
    /** Applied during this boot: the screen still shows the old font until the reboot. */
    public boolean pending(){String id=bootId();return id.length()>0&&id.equals(prefs().getString("applied_boot",""));}

    // ---- apply --------------------------------------------------------------------------------------------------
    /** Makes e the system font. Returns the message for the owner; throws with the reason when nothing or not all of it
     *  was done (the XML is swapped last, after every file is in place, so a failure leaves the old font in use). */
    public String apply(Entry e)throws Exception{
        if(!new File(AMOSSU).isFile())throw new IOException(UiText.t("루트 실행 도구(amossu)가 없습니다"));
        File dir=new File(context.getFilesDir(),"fonts");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("staging folder");
        File stage=new File(dir,"stage");
        try{
            SystemFonts.Family f=e.bundled==null?e.family:installedAlias(e.bundled);
            if(f==null)f=extract(e.bundled,stage);
            if(f.stock()){List<String> m=missingStock();if(!m.isEmpty())throw new IOException(UiText.t("순정 글꼴 파일이 없습니다: ")+m);}
            else{
                if(e.bundled==null&&e.regular==null)throw new IOException(UiText.t("이 글꼴을 미리보기로 열지 못했습니다"));
                for(FontFile x:f.files())if(!new File(x.path).isFile())throw new IOException(UiText.t("글꼴 파일이 없어졌습니다: ")+x.path);
            }
            String xmlNow=text(new File(SystemFonts.XML));
            if(xmlNow==null)throw new IOException(UiText.t("현재 글꼴 설정을 읽지 못했습니다"));
            Set<String> keep=new HashSet<String>(SystemFonts.referenced(xmlNow));
            for(String n:prefs().getString("boot_files","").split("\n"))if(n.length()>0)keep.add(n);
            List<String> ours=new ArrayList<String>();String[] names=new File(SystemFonts.FONTS).list();
            if(names!=null)for(String n:names)if(n.startsWith(SystemFonts.PREFIX))ours.add(n);
            SystemFonts.Plan plan=SystemFonts.plan(f,keep,ours);
            long free=free();
            if(free>=0&&plan.copyBytes+512*1024>free)
                throw new IOException(UiText.t("내비 시스템 공간이 부족합니다")+String.format(Locale.US," (%.1f / %.1f MB)",plan.copyBytes/1048576.0,free/1048576.0));
            File xml=new File(dir,"system_fonts.xml"),restore=new File(dir,"restore.sh"),script=new File(dir,"apply.sh");
            write(xml,plan.xml);write(restore,SystemFonts.restoreScript());
            write(script,SystemFonts.script(plan,xml.getPath(),restore.getPath()));
            String out=root(script.getPath());
            if(!out.contains(SystemFonts.OK))throw new IOException(UiText.t("루트 작업이 끝나지 않았습니다: ")+tail(out));
            // read back: the XML and every copied file
            if(!plan.xml.equals(text(new File(SystemFonts.XML))))throw new IOException(UiText.t("글꼴 설정을 다시 읽어 보니 다릅니다"));
            for(String[] c:plan.copies)if(!sha256(new File(c[0])).equals(sha256(new File(SystemFonts.FONTS,c[1]))))
                throw new IOException(UiText.t("복사한 글꼴 파일이 원본과 다릅니다: ")+c[1]);
            prefs().edit().putString("applied_boot",bootId()).putString("applied_name",e.name).commit();
            appendLog(dir,"applied "+e.name+" files="+plan.required+" removed="+plan.remove);
            return e.name+UiText.t(" 글꼴로 바꿨습니다. 재부팅해야 화면에 반영됩니다.");
        }finally{delete(stage);}
    }
    /** The bundled font as the files a manual install left in /system/fonts (its aliases), or null when they are not all there. */
    static SystemFonts.Family installedAlias(BundledFonts.Font b){
        if(b.aliases.length==0)return null;
        List<FontFile> files=new ArrayList<FontFile>();
        for(String n:b.aliases){File x=new File(SystemFonts.FONTS,n);if(!x.isFile())return null;
            try{FontFile ff=FontFile.read(x);if(!ff.family.equals(b.family))return null;files.add(ff);}catch(IOException e){return null;}}
        List<SystemFonts.Family> g=SystemFonts.group(files,SystemFonts.SYSTEM);
        return g.size()==1?g.get(0):null;
    }
    /** A bundled font's files out of the APK into stage, each checked against its SHA-256, as a family to copy in. */
    SystemFonts.Family extract(BundledFonts.Font b,File stage)throws IOException{
        delete(stage);if(!stage.mkdirs())throw new IOException("staging folder");
        List<FontFile> files=new ArrayList<FontFile>();
        for(String name:b.files()){
            File to=new File(stage,name);
            InputStream in=context.getAssets().open(BundledFonts.DIR+"/"+name);
            try{OutputStream out=new FileOutputStream(to);try{byte[] buf=new byte[65536];for(int n;(n=in.read(buf))>0;)out.write(buf,0,n);}finally{out.close();}}finally{in.close();}
            if(!b.sha(name).equals(sha256(to)))throw new IOException(UiText.t("앱에 든 글꼴 파일이 손상됐습니다: ")+name);
            files.add(FontFile.read(to));
        }
        List<SystemFonts.Family> g=SystemFonts.group(files,SystemFonts.STORAGE);
        if(g.size()!=1)throw new IOException("bundled font family "+b.family);
        return g.get(0);
    }
    private static void delete(File f){File[] l=f.listFiles();if(l!=null)for(File x:l)delete(x);if(f.exists()&&!f.delete())android.util.Log.w("VehicleDash","not deleted "+f);}
    private static String tail(String s){s=s.trim();return s.length()>200?"…"+s.substring(s.length()-200):s;}

    /** Runs a script through amossu and returns stdout and stderr together. */
    static String root(String... args)throws IOException,InterruptedException{
        String[] cmd=new String[args.length+1];cmd[0]=AMOSSU;System.arraycopy(args,0,cmd,1,args.length);
        Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try{p.getOutputStream().close();String out=new String(readAll(p.getInputStream()),"UTF-8");int code=p.waitFor();
            return out+(code==0?"":"\nexit "+code);}
        finally{p.destroy();}
    }

    // ---- reboot -------------------------------------------------------------------------------------------------
    /** The Android reboot request of My Apps → 강제 재시작 (PowerManager.reboot with the REBOOT permission). */
    public void reboot(){
        if(context.checkCallingOrSelfPermission("android.permission.REBOOT")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("REBOOT permission unavailable");
        ((android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE)).reboot(null);
    }

    // ---- files --------------------------------------------------------------------------------------------------
    static String text(File f){
        try{InputStream in=new FileInputStream(f);try{return new String(readAll(in),"UTF-8");}finally{in.close();}}catch(IOException e){return null;}
    }
    static byte[] readAll(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];for(int n;(n=in.read(buf))>0;)b.write(buf,0,n);return b.toByteArray();
    }
    private static void write(File f,String s)throws IOException{
        FileOutputStream out=new FileOutputStream(f);try{out.write(s.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
    }
    static String sha256(File f)throws IOException{
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");InputStream in=new FileInputStream(f);
            try{byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}finally{in.close();}
            StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e.toString());}
    }
    private static void appendLog(File dir,String line){
        try{FileOutputStream out=new FileOutputStream(new File(dir,"history.txt"),true);
            try{out.write((new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new java.util.Date())+" "+line+"\n").getBytes("UTF-8"));}finally{out.close();}}
        catch(IOException e){android.util.Log.w("VehicleDash","font history",e);}
    }
}
