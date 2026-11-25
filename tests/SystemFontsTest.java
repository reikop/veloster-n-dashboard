import com.veloster.vehicle.BundledFonts;
import com.veloster.vehicle.FontFile;
import com.veloster.vehicle.SystemFonts;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 글꼴 관리: the TTF reader on synthetic fonts, family grouping, the system_fonts.xml it writes, the change plan, and
 *  the root script run for real by the host shell against a temporary /system (mount/chown stubbed). No device. */
public class SystemFontsTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}

    // ---- a minimal sfnt: name, OS/2, cmap (format 4 or 12), head, glyf -----------------------------------------
    static final class Out{final ByteArrayOutputStream b=new ByteArrayOutputStream();
        Out u16(int v){b.write(v>>8&255);b.write(v&255);return this;} Out u32(long v){u16((int)(v>>>16&0xffff));u16((int)(v&0xffff));return this;}
        Out bytes(byte[] d){b.write(d,0,d.length);return this;} int size(){return b.size();} byte[] get(){return b.toByteArray();}}
    static byte[] name(String family,String style,String typo,String korean){
        List<Object[]> recs=new ArrayList<Object[]>();
        recs.add(new Object[]{0x409,1,family});recs.add(new Object[]{0x409,2,style});
        if(typo!=null)recs.add(new Object[]{0x409,16,typo});
        if(korean!=null)recs.add(new Object[]{0x412,1,korean});
        Out strings=new Out();Out o=new Out();o.u16(0).u16(recs.size()).u16(6+12*recs.size());
        for(Object[] r:recs){byte[] s=((String)r[2]).getBytes(StandardCharsets.UTF_16BE);
            o.u16(3).u16(1).u16((Integer)r[0]).u16((Integer)r[1]).u16(s.length).u16(strings.size());strings.bytes(s);}
        return o.bytes(strings.get()).get();
    }
    static byte[] os2(int weight,boolean italic){Out o=new Out();o.u16(4).u16(500).u16(weight);for(int i=6;i<62;i+=2)o.u16(0);o.u16(italic?1:0x40);for(int i=64;i<96;i+=2)o.u16(0);return o.get();}
    /** Format 4: A–Z and a–z, then either all Hangul (by delta) or AC00–AC09 through a glyph array with two zero glyphs. */
    static byte[] cmap4(boolean allHangul){
        int[][] seg=allHangul?new int[][]{{'A','Z',1-'A'},{'a','z',30-'a'},{0xAC00,0xD7A3,100-0xAC00},{0xFFFF,0xFFFF,1}}
                             :new int[][]{{'A','Z',1-'A'},{'a','z',30-'a'},{0xAC00,0xAC09,0},{0xFFFF,0xFFFF,1}};
        int n=seg.length;Out t=new Out();
        Out body=new Out();
        for(int[] s:seg)body.u16(s[1]);body.u16(0);
        for(int[] s:seg)body.u16(s[0]);
        for(int[] s:seg)body.u16(s[2]&0xffff);
        for(int i=0;i<n;i++)body.u16(!allHangul&&i==2?2*(n-i):0);   // idRangeOffset → glyph array right after the offsets
        if(!allHangul)for(int k=0;k<10;k++)body.u16(k==3||k==7?0:200+k);
        int len=14+body.size();
        t.u16(4).u16(len).u16(0).u16(2*n).u16(4).u16(1).u16(2*n-4).bytes(body.get());
        Out o=new Out();o.u16(0).u16(1).u16(3).u16(1).u32(12).bytes(t.get());return o.get();
    }
    static byte[] cmap12(int hangulCount){
        Out t=new Out();t.u16(12).u16(0).u32(16+12*3).u32(0).u32(3);
        t.u32('A').u32('Z').u32(1);t.u32('a').u32('z').u32(30);t.u32(0xAC00).u32(0xAC00+hangulCount-1).u32(100);
        Out o=new Out();o.u16(0).u16(1).u16(3).u16(10).u32(12).bytes(t.get());return o.get();
    }
    static byte[] font(int magic,byte[] name,byte[] os2,byte[] cmap,boolean fvar){
        List<Object[]> tables=new ArrayList<Object[]>();
        tables.add(new Object[]{0x636D6170,cmap});tables.add(new Object[]{0x676C7966,new byte[4]});
        Out head=new Out();for(int i=0;i<54;i+=2)head.u16(0);tables.add(new Object[]{0x68656164,head.get()});
        tables.add(new Object[]{0x6E616D65,name});if(os2!=null)tables.add(new Object[]{0x4F532F32,os2});
        if(fvar)tables.add(new Object[]{0x66766172,new byte[16]});
        Out o=new Out();o.u32(magic).u16(tables.size()).u16(0).u16(0).u16(0);
        int off=12+16*tables.size();
        for(Object[] t:tables){byte[] d=(byte[])t[1];o.u32((Integer)t[0]).u32(0).u32(off).u32(d.length);off+=(d.length+3)&~3;}
        for(Object[] t:tables){byte[] d=(byte[])t[1];o.bytes(d);for(int p=d.length;(p&3)!=0;p++)o.b.write(0);}
        return o.get();
    }
    static FontFile f(String path,String family,String style,int weight,boolean italic)throws Exception{
        return FontFile.read(font(0x00010000,name(family,style,null,null),os2(weight,italic),cmap4(true),false),path);
    }
    static void rejects(byte[] data,String why){try{FontFile.read(data,"/x.ttf");throw new AssertionError("accepted "+why);}catch(FontFile.Rejected expected){}catch(Exception e){throw new AssertionError(why+": "+e);}}

    public static void main(String[] args)throws Exception{
        // reader
        FontFile a=FontFile.read(font(0x00010000,name("Asta Sans Light","Regular","Asta Sans","아스타 산스"),os2(300,false),cmap4(true),false),"/usb/Fonts/AstaSans-Light.ttf");
        check(a.family.equals("Asta Sans")&&a.displayFamily.equals("아스타 산스")&&a.weight==300&&!a.italic,"names and weight: "+a);
        check(a.fullHangul()&&a.hangul==FontFile.HANGUL_ALL&&a.latin&&!a.cff&&!a.variable,"format 4 full coverage: "+a);
        FontFile part=FontFile.read(font(0x00010000,name("Part","Bold",null,null),os2(700,true),cmap4(false),true),"/p.ttf");
        check(part.hangul==8&&!part.fullHangul()&&part.italic&&part.weight==700&&part.variable&&part.displayFamily.equals("Part"),"glyph array zeros and flags: "+part);
        FontFile twelve=FontFile.read(font(0x4F54544F,name("Twelve","Regular",null,null),null,cmap12(100),false),"/t.otf");
        check(twelve.hangul==100&&twelve.latin&&twelve.cff&&twelve.weight==400,"format 12 and OTTO: "+twelve);
        byte[] good=font(0x00010000,name("X","Regular",null,null),os2(400,false),cmap4(true),false);
        rejects(new byte[]{'t','t','c','f',0,1,0,0,0,0,0,0},"collection");
        rejects("not a font at all, really".getBytes(StandardCharsets.US_ASCII),"text");
        rejects(Arrays.copyOf(good,good.length/2),"truncated");
        byte[] bad=good.clone();bad[12+16*0+8]=(byte)0x7f;rejects(bad,"table offset outside the file");

        // grouping by family and weight
        List<FontFile> files=new ArrayList<FontFile>();
        files.add(f("/usb/Fonts/Nanum-Bold.ttf","Nanum","Bold",700,false));
        files.add(f("/usb/Fonts/Nanum-Regular.ttf","Nanum","Regular",400,false));
        files.add(f("/usb/Fonts/Nanum-Light.ttf","Nanum","Light",300,false));
        files.add(f("/usb/Fonts/Nanum-Italic.ttf","Nanum","Italic",400,true));
        files.add(f("/usb/Fonts/Medium.ttf","Solo","Medium",500,false));
        files.add(f("/usb/Fonts/OnlyItalic.ttf","Slant","Italic",400,true));
        files.add(f("/usb/other/Nanum-Regular.ttf","Nanum","Regular",400,false));
        List<SystemFonts.Family> fam=SystemFonts.group(files,SystemFonts.STORAGE);
        check(fam.size()==3,"italic-only family dropped, same name in another folder kept apart: "+fam.size());
        SystemFonts.Family nanum=fam.get(0);
        check(nanum.regular.path.endsWith("Nanum-Regular.ttf")&&nanum.bold.path.endsWith("Nanum-Bold.ttf")&&nanum.light.path.endsWith("Nanum-Light.ttf"),"R/B/L picked");
        check(nanum.files().size()==3&&nanum.weights().equals("R·B·L"),"three files");
        check(nanum.installedName(nanum.regular).equals("VN-Nanum-Regular.ttf")&&nanum.installedName(nanum.bold).equals("VN-Nanum-Bold.ttf")&&nanum.installedName(nanum.light).equals("VN-Nanum-Light.ttf"),"installed names");
        SystemFonts.Family solo=fam.get(1);
        check(solo.regular.weight==500&&solo.bold==null&&solo.light==solo.regular&&solo.files().size()==1&&solo.weights().equals("R"),"one medium file");
        SystemFonts.Family korean=SystemFonts.group(Collections.singletonList(FontFile.read(font(0x00010000,name("나눔","Regular",null,null),os2(400,false),cmap4(true),false),"/u/n.ttf")),SystemFonts.STORAGE).get(0);
        check(korean.installedName(korean.regular).matches("VN-Font[0-9a-f]+-Regular\\.ttf"),"non-ASCII family gets a stable ASCII name: "+korean.installedName(korean.regular));
        List<SystemFonts.Family> sys=SystemFonts.group(Arrays.asList(f("/system/fonts/AstaSans-Regular.ttf","Asta Sans","Regular",400,false),f("/system/fonts/bad name.ttf","Bad","Regular",400,false)),SystemFonts.SYSTEM);
        check(sys.size()==1&&sys.get(0).installedName(sys.get(0).regular).equals("AstaSans-Regular.ttf"),"system files keep their names; unsafe names are not offered");

        // system_fonts.xml
        Map<String,List<String>> stock=SystemFonts.families(SystemFonts.stockXml());
        check(stock.get("devicedefault").equals(Arrays.asList("Mobis-Regular.ttf","Mobis-Bold.ttf")),"stock default");
        check(stock.get("sans-serif").size()==4&&stock.get("serif").get(0).equals("DroidSerif-Regular.ttf")&&stock.get("monospace").equals(Collections.singletonList("DroidSansMono.ttf")),"stock families");
        check(new ArrayList<String>(stock.keySet()).equals(Arrays.asList("devicedefault","sans-serif","serif","Droid Sans","monospace","sans-serif-light","sans-serif-thin","sans-serif-condensed")),"stock family order");
        String xml=SystemFonts.xml("VN-Nanum-Regular.ttf","VN-Nanum-Bold.ttf","VN-Nanum-Light.ttf");
        Map<String,List<String>> m=SystemFonts.families(xml);
        for(String k:new String[]{"devicedefault","sans-serif","Droid Sans","sans-serif-condensed"})check(m.get(k).equals(Arrays.asList("VN-Nanum-Regular.ttf","VN-Nanum-Bold.ttf")),k);
        for(String k:new String[]{"sans-serif-light","sans-serif-thin"})check(m.get(k).equals(Arrays.asList("VN-Nanum-Light.ttf","VN-Nanum-Bold.ttf")),k);
        check(m.get("serif").equals(stock.get("serif"))&&m.get("monospace").equals(stock.get("monospace")),"serif and monospace untouched");
        check(SystemFonts.families(SystemFonts.xml("Solo.ttf",null,null)).get("devicedefault").equals(Collections.singletonList("Solo.ttf")),"regular only");
        check(SystemFonts.defaultRegular(xml).equals("VN-Nanum-Regular.ttf")&&SystemFonts.defaultRegular(SystemFonts.stockXml()).equals(SystemFonts.STOCK_REGULAR),"default regular");
        String asta="<?xml version='1.0'?>\n<!-- <family><nameset><name>x</name></nameset></family> -->\n<familyset>\n<family>\n<nameset>\n<name>devicedefault</name>\n</nameset>\n<fileset>\n<file>AstaSans-Regular.ttf</file>\n<file>AstaSans-Bold.ttf</file>\n</fileset>\n</family>\n</familyset>";
        check(SystemFonts.defaultRegular(asta).equals("AstaSans-Regular.ttf")&&SystemFonts.referenced(asta).size()==2,"the 10/28 Asta XML read, comments ignored");
        check(SystemFonts.defaultRegular(null)==null&&SystemFonts.referenced("garbage").isEmpty(),"unreadable XML");

        // plan
        List<String> ours=Arrays.asList("VN-Old-Regular.ttf","VN-Running-Regular.ttf","VN-Nanum-Regular.ttf","AstaSans-Regular.ttf");
        SystemFonts.Plan p=SystemFonts.plan(nanum,new HashSet<String>(Arrays.asList("VN-Running-Regular.ttf","AstaSans-Regular.ttf")),ours);
        check(p.copies.size()==3&&p.copies.get(0)[0].equals("/usb/Fonts/Nanum-Regular.ttf")&&p.copies.get(0)[1].equals("VN-Nanum-Regular.ttf"),"copies");
        check(p.required.size()==3&&p.copyBytes==nanum.bytes()&&p.xml.equals(xml),"required and xml");
        check(p.remove.equals(Collections.singletonList("VN-Old-Regular.ttf")),"only unused VN-* files go; the running one and others stay: "+p.remove);
        SystemFonts.Plan s=SystemFonts.plan(SystemFonts.stock(null),Collections.<String>emptySet(),ours);
        check(s.copies.isEmpty()&&s.required.equals(SystemFonts.stockFiles())&&s.xml.equals(SystemFonts.stockXml())&&s.remove.size()==3&&!s.remove.contains("AstaSans-Regular.ttf"),"stock plan");
        SystemFonts.Plan inst=SystemFonts.plan(sys.get(0),Collections.<String>emptySet(),Collections.<String>emptyList());
        check(inst.copies.isEmpty()&&inst.required.contains("AstaSans-Regular.ttf"),"installed family copies nothing");

        // the script, run by the host shell on a temporary /system
        File root=Files.createTempDirectory("vn-fonts-").toFile();
        try{
            File fonts=new File(root,"system/fonts"),etc=new File(root,"system/etc"),usb=new File(root,"usb/My 'Fonts'");
            fonts.mkdirs();etc.mkdirs();usb.mkdirs();new File(root,"data/local/tmp").mkdirs();
            write(new File(etc,"system_fonts.xml"),asta);write(new File(fonts,"VN-Old-Regular.ttf"),"old");write(new File(fonts,"AstaSans-Regular.ttf"),"asta");
            List<FontFile> src=new ArrayList<FontFile>();
            for(String w:new String[]{"Regular","Bold"}){File x=new File(usb,"Q-"+w+".ttf");byte[] d=font(0x00010000,name("Q",w,null,null),os2(w.equals("Bold")?700:400,false),cmap4(true),false);
                try(FileOutputStream o=new FileOutputStream(x)){o.write(d);}src.add(FontFile.read(x));}
            SystemFonts.Family q=SystemFonts.group(src,SystemFonts.STORAGE).get(0);
            SystemFonts.Plan plan=SystemFonts.plan(q,Collections.singleton("AstaSans-Regular.ttf"),Arrays.asList("VN-Old-Regular.ttf"));
            File stagedXml=new File(root,"staged.xml"),stagedRestore=new File(root,"restore.sh");write(stagedXml,plan.xml);write(stagedRestore,SystemFonts.restoreScript());
            String script=SystemFonts.script(plan,stagedXml.getPath(),stagedRestore.getPath());
            check(script.indexOf("previous-system_fonts.xml")<script.indexOf("remount,rw")&&script.indexOf("trap ")<script.indexOf(".vn-new'"),"backups before the remount, trap before copying");
            check(script.lastIndexOf("test -s")<script.indexOf("mv /system/etc/system_fonts.xml.vn-new")&&script.indexOf("rm -f '/system/fonts/VN-Old")>script.indexOf("mv /system/etc/system_fonts.xml.vn-new"),"XML swapped after the files are checked; old files removed last");
            File run=new File(root,"run.sh");
            String stubs="mount(){ echo \"mount $*\" >> \""+root+"/mounts\"; }\nchown(){ :; }\n";
            write(run,stubs+script.replace("/system/",root+"/system/").replace("/data/local/tmp/",root+"/data/local/tmp/"));
            Process pr=new ProcessBuilder("sh",run.getPath()).redirectErrorStream(true).start();
            String out=new String(pr.getInputStream().readAllBytes(),StandardCharsets.UTF_8);int code=pr.waitFor();
            check(code==0&&out.contains(SystemFonts.OK),"script ran: "+code+" "+out);
            check(read(new File(etc,"system_fonts.xml")).equals(plan.xml),"XML swapped");
            check(Arrays.equals(Files.readAllBytes(new File(fonts,"VN-Q-Bold.ttf").toPath()),Files.readAllBytes(new File(usb,"Q-Bold.ttf").toPath())),"bold copied from a path with a quote and a space");
            check(new File(fonts,"VN-Q-Regular.ttf").isFile()&&!new File(fonts,"VN-Old-Regular.ttf").exists()&&new File(fonts,"AstaSans-Regular.ttf").isFile(),"old VN file removed, others kept");
            File work=new File(root,"data/local/tmp/vn-fonts");
            check(read(new File(work,"original-system_fonts.xml")).equals(asta)&&read(new File(work,"previous-system_fonts.xml")).equals(asta)&&new File(work,"restore.sh").isFile(),"backups and restore script");
            check(read(new File(root,"mounts")).equals("mount -o remount,rw /system\nmount -o remount,ro /system\n"),"/system back to read-only: "+read(new File(root,"mounts")));
            check(new File(etc,"system_fonts.xml.vn-new").exists()==false&&!new File(fonts,"VN-Q-Bold.ttf.vn-new").exists(),"no temporary files left");
            // a second run keeps the first original; a missing source stops before the XML is touched
            String second=SystemFonts.xml("VN-Q-Regular.ttf",null,null);write(stagedXml,second);
            SystemFonts.Plan broken=SystemFonts.plan(q,Collections.<String>emptySet(),Collections.<String>emptyList());
            new File(usb,"Q-Bold.ttf").delete();
            write(run,stubs+SystemFonts.script(broken,stagedXml.getPath(),stagedRestore.getPath()).replace("/system/",root+"/system/").replace("/data/local/tmp/",root+"/data/local/tmp/"));
            new File(root,"mounts").delete();
            pr=new ProcessBuilder("sh",run.getPath()).redirectErrorStream(true).start();out=new String(pr.getInputStream().readAllBytes(),StandardCharsets.UTF_8);code=pr.waitFor();
            check(code!=0&&!out.contains(SystemFonts.OK),"missing file fails the script");
            check(read(new File(etc,"system_fonts.xml")).equals(plan.xml)&&read(new File(work,"original-system_fonts.xml")).equals(asta),"XML untouched on failure; original backup kept");
            check(read(new File(root,"mounts")).endsWith("remount,ro /system\n"),"read-only again after a failure");
            // restore.sh puts the first XML back
            write(run,stubs+SystemFonts.restoreScript().replace("/system/",root+"/system/").replace("/data/local/tmp/",root+"/data/local/tmp/"));
            pr=new ProcessBuilder("sh",run.getPath()).redirectErrorStream(true).start();out=new String(pr.getInputStream().readAllBytes(),StandardCharsets.UTF_8);code=pr.waitFor();
            check(code==0&&out.contains("VN_FONT_RESTORED")&&read(new File(etc,"system_fonts.xml")).equals(asta),"restore script: "+out);
        }finally{delete(root);}
        // the fonts inside the app: present, unchanged, full Hangul, the family and weights the catalog says
        File assets=new File(System.getProperty("vn.root","."),"apps/dashboard/assets/fonts");
        Set<String> listed=new HashSet<String>();long total=0;
        for(BundledFonts.Font b:BundledFonts.FONTS){
            List<FontFile> got=new ArrayList<FontFile>();long bytes=0;
            for(String name:b.files()){
                File x=new File(assets,name);check(x.isFile(),"asset "+x);listed.add(name);
                check(sha(x).equals(b.sha(name)),"SHA-256 of "+name);
                FontFile ff=FontFile.read(x);check(ff.family.equals(b.family)&&ff.fullHangul()&&!ff.italic,"font "+ff);got.add(ff);bytes+=x.length();
            }
            check(bytes==b.bytes,"size of "+b.name+": "+bytes);total+=bytes;
            check(new File(assets,b.license).isFile(),"licence "+b.license);
            List<SystemFonts.Family> g=SystemFonts.group(got,SystemFonts.STORAGE);
            check(g.size()==1&&(g.get(0).bold!=null)==(b.bold!=null),"one family, bold as listed: "+b.name);
            check(g.get(0).installedName(g.get(0).regular).equals(BundledFonts.installedRegular(b))&&(b.bold==null||g.get(0).installedName(g.get(0).bold).equals(BundledFonts.installedBold(b))),"installed names "+BundledFonts.installedRegular(b));
        }
        for(String n:assets.list())check(n.startsWith("OFL-")||listed.contains(n),"unlisted asset "+n);
        check(total<24L*1024*1024,"the bundled fonts stay small: "+total);
        System.out.println("PASS font reader (format 4/12, names, weight, rejects), family grouping, system_fonts.xml, change plan, the root script on a temporary /system and the "+BundledFonts.FONTS.length+" fonts inside the app");
    }
    static String sha(File f)throws Exception{java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");StringBuilder b=new StringBuilder();
        for(byte x:d.digest(Files.readAllBytes(f.toPath())))b.append(String.format("%02x",x&0xff));return b.toString();}
    static void write(File f,String s)throws Exception{Files.write(f.toPath(),s.getBytes(StandardCharsets.UTF_8));}
    static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
    static void delete(File f){File[] l=f.listFiles();if(l!=null)for(File x:l)delete(x);f.delete();}
}
