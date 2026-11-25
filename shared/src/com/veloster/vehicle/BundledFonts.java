package com.veloster.vehicle;

/**
 * 글꼴 관리의 앱 포함 글꼴: a few open-licence Korean fonts inside the dashboard APK (assets/fonts), offered in 설정 →
 * 글꼴 without a USB stick. Kept to a short list (about 53 MB of files, about 17 MB in the APK); more fonts come
 * from USB or the head unit storage. Every file has all 11,172 modern Hangul syllables (checked by SystemFontsTest
 * against these hashes, which FontManager checks again before a file is installed).
 *
 * Licences: SIL Open Font License 1.1 (assets/fonts/OFL-*.txt), except 부산단디체 (Busan Metropolitan City: free use in any
 * medium, no fee for copying or distribution) and 이서윤체 (Heungkuk Life: free use, no sale, no modified redistribution),
 * whose terms are quoted in assets/fonts/LICENSE-*.txt; those files are renamed to ASCII only. Asta Sans © 2024 The Asta Sans Project Authors (42dot,
 * Hyundai Motor Group; the typeface the Pleos Connect design guide names). Nanum fonts © NAVER Corporation (NHN), Reserved Font
 * Names include "Nanum"; Pretendard © 2021 Kil Hyung-jin, Reserved Font Name Pretendard; Poor Story (서툰이야기) © YoonDesign Inc. The files are unmodified.
 * Sources: Asta Sans static TTFs from github.com/42dot/Asta-Sans at 69ecccd58dcb8b506f665867733b17773bc4bb9f (fonts/ttf/); Nanum Pen/Brush Script and Poor Story from github.com/google/fonts (ofl/); NanumSquare Neo
 * (TTF/) from hangeul.naver.com/hangeul_static/webfont/zips/nanum-square-neo.zip;
 * 부산단디고딕 M/B, 부산단디명조 M/B, 부산단디손글씨 from the Busan city zips (busan.go.kr/bhbusan); 이서윤체 from Sandoll Cloud;
 * Pretendard 1.3.9 static TTFs (dist/public/static/alternative/) from the npm package pretendard (github.com/orioncactus/pretendard).
 */
public final class BundledFonts {
    private BundledFonts(){}
    public static final String DIR="fonts";
    public static final int GOTHIC=0,HANDWRITING=1,MYEONGJO=2;
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
        new Font("프리텐다드 (Pretendard)","Pretendard",GOTHIC,"OFL-Pretendard.txt",5387580L,
            "Pretendard-Regular.ttf","6d0af5258997aec7354a6e340fc2325ba321c410ca48b3af858c8c3d6e92a324",
            "Pretendard-Bold.ttf","c16b88c670d23e83fa1170c954cbc4822d3b8dad3c3cde15d798a94b43d97985"),
        new Font("나눔스퀘어 네오","NanumSquare Neo",GOTHIC,"OFL-Nanum.txt",4362600L,
            "NanumSquareNeo-bRg.ttf","864de849b9d0af7efbd9e855044f5ab3bbf44cc88ddff6b9873ee830b9d3a61f",
            "NanumSquareNeo-cBd.ttf","4749fa5691157cf56a59d297b45e88894a646846048018cd7a4117ffb2869767"),
        new Font("부산단디고딕","BusanDandiGothic",GOTHIC,"LICENSE-BusanDandi.txt",5472452L,
            "BusanDandiGothic-M.ttf","3f847f330a7ded6bf4a3e775a9f6abc835b8d14b1e10f488e0c4ae369bae04f3",
            "BusanDandiGothic-B.ttf","90b552ce6883da61fe88dfe6a8dcff346f3b80025969bae2ff18278c4b5e49d5"),
        new Font("부산단디명조","BusanDandiMyeongjo",MYEONGJO,"LICENSE-BusanDandi.txt",14020464L,
            "BusanDandiMyeongjo-M.ttf","620c79bce13632d1fe280710809c458b9fcd4b8042a8e0b77df40a1ed1e014fc",
            "BusanDandiMyeongjo-B.ttf","ca16e0c6b69e9edcf2857449e6bc0e7532ab4627676d772a8fbc34a60320cfe6"),
        new Font("나눔손글씨 펜","Nanum Pen",HANDWRITING,"OFL-Nanum.txt",3201664L,
            "NanumPenScript-Regular.ttf","6f0d1ab29c7894010dc88831fb7a0a51edb79136e450344183de5b1a8b52bd43",null,null),
        new Font("나눔손글씨 붓","Nanum Brush Script",HANDWRITING,"OFL-Nanum.txt",3564804L,
            "NanumBrushScript-Regular.ttf","27ceaf578c96f594cdf07fe0181b251790acbb746a164e45c1f6473f89911a31",null,null),
        new Font("서툰이야기 (Poor Story)","Poor Story",HANDWRITING,"OFL-PoorStory.txt",3966768L,
            "PoorStory-Regular.ttf","831ab87f7b5463f9cd83ac249bf386816f3a478f1d226427c88cac907adb7ee2",null,null),
        new Font("부산단디손글씨","BusanDandiScript",HANDWRITING,"LICENSE-BusanDandi.txt",9270948L,
            "BusanDandiScript-R.ttf","aa6438fa308a64fbfef2ce64a32d7c03310788f7be1083cf41be8232f8edc6b8",null,null),
        new Font("이서윤체","LeeSeoyun",HANDWRITING,"LICENSE-LeeSeoyun.txt",1320324L,
            "LeeSeoyun.ttf","8eae3d64050446b2094c62b64a8793bc3a163d1881edddf88993210fd915f700",null,null),
    };
    /** The name the regular file gets in /system/fonts (SystemFonts.Family.installedName for a copied family). */
    public static String installedRegular(Font f){return SystemFonts.PREFIX+SystemFonts.slug(f.family)+"-Regular.ttf";}
    public static String installedBold(Font f){return f.bold==null?null:SystemFonts.PREFIX+SystemFonts.slug(f.family)+"-Bold.ttf";}
}
