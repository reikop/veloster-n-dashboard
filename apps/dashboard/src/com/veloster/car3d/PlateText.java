package com.veloster.car3d;

/**
 * The 내 차 number plate's text and colours (Korean registration plates, the long 520 x 110 mm type), without Android:
 * host-tested. CarPlate draws the picture from these.
 *
 * Styles: the 2020 reflective-film plate (white, black letters, a blue band on the left with the taegeuk and KOR), the
 * older white long plate, the electric-car plate (sky blue, black letters, taegeuk and EV on the left), the commercial
 * plate (yellow, black letters) and the old green plate (white letters).
 */
public final class PlateText {
    public static final int FILM = 0, WHITE = 1, EV = 2, COMMERCIAL = 3, GREEN = 4, STYLES = 5;
    /** Korean source strings (translations/en.json). */
    public static final String[] NAMES = {"반사필름 (2020~)", "흰색", "전기차", "영업용", "녹색 (구형)"};
    /** Plate face (top, bottom of a soft vertical gradient), letters, rim. */
    static final int[] FACE_TOP = {0xfff7f8f6, 0xfff4f5f2, 0xff8ccdf2, 0xfff6cf2a, 0xff2c7a50};
    static final int[] FACE_BOTTOM = {0xffe9ecec, 0xffe6e8e6, 0xff4f9fe0, 0xffe9b912, 0xff1d5e3c};
    static final int[] INK = {0xff121316, 0xff121316, 0xff101820, 0xff15130c, 0xfff2f4f0};
    static final int[] RIM = {0xff6c7076, 0xff5a5e63, 0xff2f6ea8, 0xff8a6c08, 0xff0f3a24};
    /** Styles with the band on the left (taegeuk plus KOR or EV). */
    public static boolean band(int style) { return style == FILM || style == EV; }
    /** Longest text drawn; what shows when the owner has entered none. */
    public static final int MAX = 10;
    public static final String DEFAULT = "12가 3456";

    private PlateText() {}

    public static int validStyle(int s) { return s >= 0 && s < STYLES ? s : FILM; }

    /** What is drawn: trimmed, inner spaces as one, no control characters, at most MAX characters; blank = DEFAULT. */
    public static String clean(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) for (int i = 0; i < s.length() && b.length() < MAX; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
            } else b.append(c);
        }
        String t = b.toString().trim();
        return t.length() == 0 ? DEFAULT : t;
    }
}
