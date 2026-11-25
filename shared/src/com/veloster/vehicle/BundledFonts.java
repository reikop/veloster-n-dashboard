package com.veloster.vehicle;

/**
 * 글꼴 관리의 앱 포함 글꼴: a few open-licence Korean fonts inside the dashboard APK (assets/fonts), offered in 설정 →
 * 글꼴 without a USB stick. Kept small on purpose (about 22.7 MB of files, about 8.2 MB in the APK); more fonts come
 * from USB or the head unit storage. Every file has all 11,172 modern Hangul syllables (checked by SystemFontsTest
 * against these hashes, which FontManager checks again before a file is installed).
 *
 * Licences: SIL Open Font License 1.1 (assets/fonts/OFL-*.txt). Asta Sans © 2024 The Asta Sans Project Authors (42dot,
 * Hyundai Motor Group; the typeface the Pleos Connect design guide names). Nanum fonts © NAVER Corporation (NHN), Reserved Font
 * Names include "Nanum"; Poor Story (서툰이야기) © YoonDesign Inc. The files are unmodified.
 * Sources: Asta Sans static TTFs from github.com/42dot/Asta-Sans at 69ecccd58dcb8b506f665867733b17773bc4bb9f (fonts/ttf/); NanumGothic, Nanum Pen/Brush Script and Poor Story from github.com/google/fonts (ofl/); NanumSquare and
 * NanumSquareRound from hangeul.naver.com/hangeul_static/webfont/zips/nanum-square(-round).zip.
 */
public final class BundledFonts {
    private BundledFonts(){}
    public static final String DIR="fonts";
    public static final int GOTHIC=0,HANDWRITING=1;
    public static final class Font{
        /** The name shown, the font's own family name (its VN-* names in /system/fonts come from it), the kind, the
         *  licence file, and the regular and bold assets with their SHA-256 (bold may be null). */
        public final String name,family,license,regular,regularSha,bold,boldSha;public final int kind;public final long bytes;
        /** Regular and bold names this font may already have in /system/fonts from a manual install (used as they are). */
        public final String[] aliases;
        Font(String name,String family,int kind,String license,long bytes,String regular,String regularSha,String bold,String boldSha,String... aliases){
            this.name=name;this.family=family;this.kind=kind;this.license=license;this.bytes=bytes;
            this.regular=regular;this.regularSha=regularSha;this.bold=bold;this.boldSha=boldSha;this.aliases=aliases;}
        public String[] files(){return bold==null?new String[]{regular}:new String[]{regular,bold};}
        public String sha(String file){return file.equals(regular)?regularSha:file.equals(bold)?boldSha:null;}
    }
    public static final Font[] FONTS={
        // 2026-10-28 the head unit got AstaSans-Regular/Bold/Light.ttf by hand (Google Fonts static files): reused, not copied twice
        new Font("Asta Sans (Pleos · 42dot)","Asta Sans",GOTHIC,"OFL-AstaSans.txt",5112464L,
            "AstaSans-Regular.ttf","c5585d2f702b91fccb7887c5e2a653fe2e9169ab7c74a4a901b38ff88cee2f27",
            "AstaSans-Bold.ttf","79ea4d9014d134f6ca521df8de1a03a15ccd795def6b894c4b2fbc2f185b650f","AstaSans-Regular.ttf","AstaSans-Bold.ttf"),
        new Font("나눔고딕","NanumGothic",GOTHIC,"OFL-Nanum.txt",4128612L,
            "NanumGothic-Regular.ttf","76f45ef4a6bcff344c837c95a7dcc26e017e38b5846d5ae0cdcb5b86be2e2d31",
            "NanumGothic-Bold.ttf","f96298f9fb18e364d2370f4c3ce948ac67a2b61af992d7234bc15c42b033c674"),
        new Font("나눔스퀘어","NanumSquare",GOTHIC,"OFL-Nanum.txt",1457140L,
            "NanumSquareR.ttf","5a51deae5237435d9a0bc0cc6cc30619a914b29801f895698cfdacadcad06e94",
            "NanumSquareB.ttf","f737d58294faec9c632189af3a2a3e48e49c03c0256de09db61e879e2857bfbf"),
        new Font("나눔스퀘어라운드","NanumSquareRound",GOTHIC,"OFL-Nanum.txt",2094224L,
            "NanumSquareRoundR.ttf","52862fdf05b55ba886ca21998222d9598ef84c975e68046b9a8d8f1af09228a0",
            "NanumSquareRoundB.ttf","61fd37816a1ee1a9336876742392d80bdafd340e5124a088aeda20446e2e15de"),
        new Font("나눔손글씨 펜","Nanum Pen",HANDWRITING,"OFL-Nanum.txt",3201664L,
            "NanumPenScript-Regular.ttf","6f0d1ab29c7894010dc88831fb7a0a51edb79136e450344183de5b1a8b52bd43",null,null),
        new Font("나눔손글씨 붓","Nanum Brush Script",HANDWRITING,"OFL-Nanum.txt",3564804L,
            "NanumBrushScript-Regular.ttf","27ceaf578c96f594cdf07fe0181b251790acbb746a164e45c1f6473f89911a31",null,null),
        new Font("서툰이야기 (Poor Story)","Poor Story",HANDWRITING,"OFL-PoorStory.txt",3966768L,
            "PoorStory-Regular.ttf","831ab87f7b5463f9cd83ac249bf386816f3a478f1d226427c88cac907adb7ee2",null,null),
    };
    /** The name the regular file gets in /system/fonts (SystemFonts.Family.installedName for a copied family). */
    public static String installedRegular(Font f){return SystemFonts.PREFIX+SystemFonts.slug(f.family)+"-Regular.ttf";}
    public static String installedBold(Font f){return f.bold==null?null:SystemFonts.PREFIX+SystemFonts.slug(f.family)+"-Bold.ttf";}
}
