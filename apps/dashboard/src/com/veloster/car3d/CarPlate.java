package com.veloster.car3d;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * The 3D car's number plate picture (both plates share it): a Korean long plate in one of the PlateText styles with
 * the owner's text. The texture is 4:1 and the plate 520 x 110 mm (4.73:1), so letters are drawn a little narrower.
 * Plate idea from the i30 PD build's 3D car (sung9155/i30-pd-dashboard, MIT); the styles and band are ours.
 */
public final class CarPlate {
    /** Texture size: power of two for GLES 2.0 mipmaps, wide enough for Hangul to read in a close view. */
    public static final int W = 512, H = 128;
    static final float SQUEEZE = (W / (float) H) / (520f / 110f);
    private static final int BAND_BLUE_TOP = 0xff2f6fd0, BAND_BLUE_BOTTOM = 0xff1b4796, TAEGEUK_RED = 0xffd42a32, TAEGEUK_BLUE = 0xff1f4fa8;

    private CarPlate() {}

    public static Bitmap draw(String text, int style) {
        style = PlateText.validStyle(style);
        String t = PlateText.clean(text);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, W, H);
        p.setShader(new LinearGradient(0, 0, 0, H, PlateText.FACE_TOP[style], PlateText.FACE_BOTTOM[style], Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        float left = 14;
        if (PlateText.band(style)) left = band(c, p, style);
        // the pressed rim
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(PlateText.RIM[style]);
        r.set(3, 3, W - 3, H - 3); c.drawRoundRect(r, 9, 9, p);
        p.setStyle(Paint.Style.FILL);
        // letters: as large as fit, centred in the space right of the band
        p.setColor(PlateText.INK[style]);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextScaleX(SQUEEZE);
        p.setTextSize(100);
        float room = W - left - 18, size = Math.min(H * 0.78f, 100 * room / Math.max(1f, p.measureText(t)));
        p.setTextSize(size);
        c.drawText(t, (left + W - 4) / 2f, H / 2f + size * 0.36f, p);
        return bmp;
    }

    /** The left band: blue (reflective film) or a deeper blue on the sky-blue EV plate, the taegeuk, then KOR or EV. */
    private static float band(Canvas c, Paint p, int style) {
        float bw = W * 40f / 520f * 1.25f;   // about 40 mm, widened back for the squeezed texture
        p.setShader(new LinearGradient(0, 0, 0, H, BAND_BLUE_TOP, BAND_BLUE_BOTTOM, Shader.TileMode.CLAMP));
        c.drawRect(5, 5, bw, H - 5, p);
        p.setShader(null);
        float cx = bw / 2 + 2, cy = H * 0.36f, rad = Math.min(bw * 0.34f, H * 0.2f);
        RectF o = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
        p.setColor(0xfff4f6f8); c.drawCircle(cx, cy, rad + 2, p);   // white ring around the emblem
        p.setColor(TAEGEUK_RED); c.drawArc(o, 180, 180, true, p);   // red above, blue below,
        p.setColor(TAEGEUK_BLUE); c.drawArc(o, 0, 180, true, p);
        float h = rad / 2;                                          // and the two small circles that make the wave
        p.setColor(TAEGEUK_RED); c.drawCircle(cx + h, cy, h, p);
        p.setColor(TAEGEUK_BLUE); c.drawCircle(cx - h, cy, h, p);
        p.setColor(0xfff4f6f8); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextAlign(Paint.Align.CENTER); p.setTextScaleX(SQUEEZE);
        String tag = style == PlateText.EV ? "EV" : "KOR";
        float ts = H * 0.2f; p.setTextSize(ts);
        while (ts > 8 && p.measureText(tag) > bw - 8) { ts -= 1; p.setTextSize(ts); }
        c.drawText(tag, cx, H * 0.82f, p);
        p.setTextScaleX(1);
        return bw + 4;
    }
}
