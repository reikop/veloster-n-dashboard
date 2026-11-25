package com.veloster.vehicle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 글꼴 관리 (설정 → 글꼴): the head unit's system font list and what changing it takes, in plain Java (host-tested).
 * Android 4.2 reads /system/etc/system_fonts.xml once, when zygote starts: the first family (devicedefault) and
 * sans-serif are what the navigation, the OEM screens and most apps draw with. A change is a new XML plus the font files
 * in /system/fonts, and it shows after a reboot. FontManager runs the script built here as root (the OEM amossu).
 *
 * Kept as the 2026-10-28 Asta Sans install did it: devicedefault, sans-serif, Droid Sans, sans-serif-light/-thin/
 * -condensed take the chosen font; serif, monospace and the fallback chain (Hangul fallback included) stay as they are.
 * Files this manager copies are named VN-*; no other file in /system/fonts is ever removed.
 */
public final class SystemFonts {
    private SystemFonts(){}
    public static final String FONTS="/system/fonts",XML="/system/etc/system_fonts.xml",FALLBACK="/system/etc/fallback_fonts.xml",
        WORK="/data/local/tmp/vn-fonts",PREFIX="VN-",OK="VN_FONT_OK";
    /** Where a family comes from: the factory set, already in /system/fonts, or a TTF/OTF on the head unit storage or USB. */
    public static final int STOCK=0,SYSTEM=1,STORAGE=2;
    private static final Pattern SAFE=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,80}");

    /** The factory families and files (JSN.KOR.SOP.V141.250902 /system/etc/system_fonts.xml, comments left out). */
    private static final String[][] STOCK_FAMILIES={
        {"devicedefault","|","Mobis-Regular.ttf","Mobis-Bold.ttf"},
        {"sans-serif","arial","helvetica","tahoma","verdana","|","Roboto-Regular.ttf","Roboto-Bold.ttf","Roboto-Italic.ttf","Roboto-BoldItalic.ttf"},
        {"serif","times","times new roman","palatino","georgia","baskerville","goudy","fantasy","cursive","ITC Stone Serif","|",
            "DroidSerif-Regular.ttf","DroidSerif-Bold.ttf","DroidSerif-Italic.ttf","DroidSerif-BoldItalic.ttf"},
        {"Droid Sans","|","DroidSans.ttf","DroidSans-Bold.ttf"},
        {"monospace","courier","courier new","monaco","|","DroidSansMono.ttf"},
        {"sans-serif-light","|","Roboto-Light.ttf","Roboto-LightItalic.ttf"},
        {"sans-serif-thin","|","Roboto-Thin.ttf","Roboto-ThinItalic.ttf"},
        {"sans-serif-condensed","|","RobotoCondensed-Regular.ttf","RobotoCondensed-Bold.ttf","RobotoCondensed-Italic.ttf","RobotoCondensed-BoldItalic.ttf"},
    };
    /** The stock families the chosen font replaces (their indexes in STOCK_FAMILIES); 2 serif and 4 monospace stay. */
    private static final int[] REPLACED={0,1,3,5,6,7};
    public static final String STOCK_REGULAR="Mobis-Regular.ttf";

    /** One choice in the list: a family with its regular, bold and light files (bold and light may be missing). */
    public static final class Family{
        public final int source;public final String name,dir;public final FontFile regular,bold,light;
        Family(int source,String name,String dir,FontFile regular,FontFile bold,FontFile light){this.source=source;this.name=name;this.dir=dir;this.regular=regular;this.bold=bold;this.light=light;}
        public boolean stock(){return source==STOCK;}
        /** Every file, regular first, without repeats. */
        public List<FontFile> files(){List<FontFile> l=new ArrayList<FontFile>();for(FontFile f:new FontFile[]{regular,bold,light})if(f!=null&&!l.contains(f))l.add(f);return l;}
        public long bytes(){long n=0;for(FontFile f:files())n+=f.bytes;return n;}
        /** The name a file has (or gets) in /system/fonts. */
        public String installedName(FontFile f){
            if(source!=STORAGE)return base(f.path);
            String role=f==regular?"Regular":f==bold?"Bold":"Light",ext=base(f.path).toLowerCase(Locale.US).endsWith(".otf")?".otf":".ttf";
            return PREFIX+slug(regular.family)+"-"+role+ext;   // the font's own (Latin) family name, not the Korean display name
        }
        /** For the list: R·B·L, the Hangul coverage and the warnings. */
        public String weights(){return "R"+(bold!=null?"·B":"")+(light!=null&&light!=regular?"·L":"");}
    }
    public static Family stock(FontFile regular){return new Family(STOCK,"",FONTS,regular,null,null);}

    /** Files of one source grouped by family name per folder; the regular, bold and light picked by weight. */
    public static List<Family> group(Collection<FontFile> files,int source){
        Map<String,List<FontFile>> by=new LinkedHashMap<String,List<FontFile>>();
        for(FontFile f:files){
            if(source==SYSTEM&&!SAFE.matcher(base(f.path)).matches())continue;
            String key=dir(f.path)+"\u0000"+f.family.toLowerCase(Locale.US);
            List<FontFile> l=by.get(key);if(l==null)by.put(key,l=new ArrayList<FontFile>());l.add(f);
        }
        List<Family> out=new ArrayList<Family>();
        for(List<FontFile> l:by.values()){
            FontFile regular=closest(l,400,0,1000),bold=closest(l,700,600,1000),light=closest(l,300,0,350);
            if(regular==null)continue;   // italic only
            if(bold==regular)bold=null;
            if(light==null)light=regular;
            String name=regular.displayFamily!=null?regular.displayFamily:regular.family;
            out.add(new Family(source,name,dir(regular.path),regular,bold,light));
        }
        return out;
    }
    /** The upright file whose weight is nearest to want within [lo, hi]; the lighter one on a tie. */
    static FontFile closest(List<FontFile> l,int want,int lo,int hi){
        FontFile best=null;
        for(FontFile f:l){
            if(f.italic||f.weight<lo||f.weight>hi)continue;
            if(best==null||Math.abs(f.weight-want)<Math.abs(best.weight-want)||Math.abs(f.weight-want)==Math.abs(best.weight-want)&&f.weight<best.weight)best=f;
        }
        return best;
    }

    // ---- system_fonts.xml -----------------------------------------------------------------------------------------
    public static String stockXml(){return xml(null,null,null);}
    /** The XML with the replaced families on these /system/fonts names (null: the stock XML). */
    public static String xml(String regular,String bold,String light){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- N Dashboard font manager: ")
            .append(regular==null?"factory fonts":regular).append(" -->\n<familyset>\n");
        for(int i=0;i<STOCK_FAMILIES.length;i++){
            String[] fam=STOCK_FAMILIES[i];int bar=indexOf(fam,"|");
            b.append("    <family>\n        <nameset>\n");
            for(int k=0;k<bar;k++)b.append("            <name>").append(fam[k]).append("</name>\n");
            b.append("        </nameset>\n        <fileset>\n");
            List<String> files=new ArrayList<String>();
            if(regular!=null&&replaced(i)){
                boolean thin=i==5||i==6;
                files.add(thin&&light!=null?light:regular);
                if(bold!=null)files.add(bold);
            }else for(int k=bar+1;k<fam.length;k++)files.add(fam[k]);
            for(String f:files)b.append("            <file>").append(f).append("</file>\n");
            b.append("        </fileset>\n    </family>\n");
        }
        return b.append("</familyset>\n").toString();
    }
    private static boolean replaced(int i){for(int r:REPLACED)if(r==i)return true;return false;}
    private static int indexOf(String[] a,String v){for(int i=0;i<a.length;i++)if(a[i].equals(v))return i;return -1;}
    /** The files the stock XML names (they must all be in /system/fonts before 순정 is written). */
    public static Set<String> stockFiles(){Set<String> s=new LinkedHashSet<String>();for(String[] f:STOCK_FAMILIES){int bar=indexOf(f,"|");for(int k=bar+1;k<f.length;k++)s.add(f[k]);}return s;}

    private static final Pattern FAMILY=Pattern.compile("<family>(.*?)</family>",Pattern.DOTALL),
        NAME=Pattern.compile("<name>\\s*([^<]*?)\\s*</name>"),FILE=Pattern.compile("<file[^>]*>\\s*([^<]*?)\\s*</file>"),
        COMMENT=Pattern.compile("<!--.*?-->",Pattern.DOTALL);
    /** Each family's files keyed by its first name, in order (a lenient read of system_fonts / fallback_fonts.xml). */
    public static LinkedHashMap<String,List<String>> families(String xml){
        LinkedHashMap<String,List<String>> out=new LinkedHashMap<String,List<String>>();
        if(xml==null)return out;
        Matcher m=FAMILY.matcher(COMMENT.matcher(xml).replaceAll(""));int n=0;
        while(m.find()){
            Matcher name=NAME.matcher(m.group(1)),file=FILE.matcher(m.group(1));
            String key=name.find()?name.group(1):"#"+n;n++;
            List<String> files=new ArrayList<String>();while(file.find())files.add(file.group(1));
            out.put(key,files);
        }
        return out;
    }
    /** The regular file of the default family (devicedefault, else the first), or null. */
    public static String defaultRegular(String xml){
        LinkedHashMap<String,List<String>> f=families(xml);
        List<String> d=f.get("devicedefault");if(d==null&&!f.isEmpty())d=f.values().iterator().next();
        return d==null||d.isEmpty()?null:d.get(0);
    }
    /** Every file the XML names. */
    public static Set<String> referenced(String xml){
        Set<String> s=new LinkedHashSet<String>();for(List<String> l:families(xml).values())s.addAll(l);return s;
    }

    // ---- the change ---------------------------------------------------------------------------------------------
    /** What applying a family writes: the XML, the copies (source path → /system/fonts name), the names that must exist
     *  afterwards and the VN-* files no longer used. */
    public static final class Plan{
        public final String xml;public final List<String[]> copies=new ArrayList<String[]>();public final Set<String> required=new LinkedHashSet<String>();
        public final List<String> remove=new ArrayList<String>();public long copyBytes;
        Plan(String xml){this.xml=xml;}
    }
    /**
     * @param keep  names the running system may still open (the XML now and the one this boot started with): never removed
     * @param ours  the VN-* files now in /system/fonts
     */
    public static Plan plan(Family f,Collection<String> keep,Collection<String> ours){
        Plan p;
        if(f.stock()){p=new Plan(stockXml());p.required.addAll(stockFiles());}
        else{
            String r=f.installedName(f.regular),b=f.bold==null?null:f.installedName(f.bold),l=f.light==null||f.light==f.regular?null:f.installedName(f.light);
            p=new Plan(xml(r,b,l));
            for(FontFile x:f.files()){
                String name=f.installedName(x);
                if(!SAFE.matcher(name).matches())throw new IllegalArgumentException("unsafe font file name "+name);
                p.required.add(name);
                if(f.source==STORAGE){p.copies.add(new String[]{x.path,name});p.copyBytes+=x.bytes;}
            }
        }
        Set<String> used=referenced(p.xml);
        for(String name:ours)if(name.startsWith(PREFIX)&&SAFE.matcher(name).matches()&&!used.contains(name)&&!keep.contains(name)&&!p.required.contains(name))p.remove.add(name);
        return p;
    }

    /** The root shell script (mksh, Android 4.2 toolbox: no mkdir -p) that backs up, copies, checks and swaps the XML. */
    public static String script(Plan p,String stagedXml,String stagedRestore){
        StringBuilder b=new StringBuilder("set -e\n");
        b.append("W=").append(WORK).append('\n');
        b.append("[ -d \"$W\" ] || mkdir \"$W\"\n");
        b.append("[ -e \"$W/original-system_fonts.xml\" ] || cp ").append(XML).append(" \"$W/original-system_fonts.xml\"\n");
        b.append("[ -e \"$W/restore.sh\" ] || cp ").append(q(stagedRestore)).append(" \"$W/restore.sh\"\n");
        b.append("cp ").append(XML).append(" \"$W/previous-system_fonts.xml\"\n");
        b.append("mount -o remount,rw /system\n");
        b.append("trap 'mount -o remount,ro /system' EXIT\n");
        for(String[] c:p.copies){
            String to=FONTS+"/"+c[1];
            b.append("rm -f ").append(q(to+".vn-new")).append('\n');
            b.append("cp ").append(q(c[0])).append(' ').append(q(to+".vn-new")).append('\n');
            b.append("chmod 644 ").append(q(to+".vn-new")).append('\n');
            b.append("chown 0.0 ").append(q(to+".vn-new")).append('\n');
            b.append("mv ").append(q(to+".vn-new")).append(' ').append(q(to)).append('\n');
        }
        for(String r:p.required)b.append("test -s ").append(q(FONTS+"/"+r)).append('\n');
        b.append("cp ").append(q(stagedXml)).append(' ').append(XML).append(".vn-new\n");
        b.append("chmod 644 ").append(XML).append(".vn-new\n");
        b.append("chown 0.0 ").append(XML).append(".vn-new\n");
        b.append("mv ").append(XML).append(".vn-new ").append(XML).append('\n');
        for(String r:p.remove)b.append("rm -f ").append(q(FONTS+"/"+r)).append('\n');
        b.append("sync\necho ").append(OK).append('\n');
        return b.toString();
    }
    /** Kept at WORK/restore.sh: puts back the XML from before the first change (adb shell /system/bin/amossu WORK/restore.sh). */
    public static String restoreScript(){
        return "#!/system/bin/sh\n"
            +"# N Dashboard font manager: put back system_fonts.xml as it was before the first change, then reboot.\n"
            +"#   adb shell /system/bin/amossu "+WORK+"/restore.sh\n"
            +"set -e\nmount -o remount,rw /system\ntrap 'mount -o remount,ro /system' EXIT\n"
            +"cp "+WORK+"/original-system_fonts.xml "+XML+".vn-new\nchmod 644 "+XML+".vn-new\nchown 0.0 "+XML+".vn-new\n"
            +"mv "+XML+".vn-new "+XML+"\nsync\necho VN_FONT_RESTORED\n";
    }
    /** A single-quoted shell word. */
    static String q(String s){return "'"+s.replace("'","'\\''")+"'";}

    static String base(String path){int i=path.lastIndexOf('/');return i<0?path:path.substring(i+1);}
    static String dir(String path){int i=path.lastIndexOf('/');return i<=0?"/":path.substring(0,i);}
    /** ASCII letters and digits of a family name (at most 32), or a stable stand-in for a name with none. */
    static String slug(String name){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<name.length()&&b.length()<32;i++){char c=name.charAt(i);if(c<128&&Character.isLetterOrDigit(c))b.append(c);}
        return b.length()>0?b.toString():"Font"+Integer.toHexString(name.hashCode()&0x7fffffff);
    }
}
