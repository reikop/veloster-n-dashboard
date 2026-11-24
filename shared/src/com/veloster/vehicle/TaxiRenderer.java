package com.veloster.vehicle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.veloster.i18n.UiText;
import java.util.Locale;

/**
 * Taxi meter page below the common header: a Korean colour LCD meter (galloping horse, large white fare)
 * or a Japanese meter (空車/賃走/支払 window, green fare in 円), a KR/JP switch, pastel buttons (vacant, hired,
 * surcharge, pay) and a paper receipt after paying. Draws {@link TaxiMeter} state only.
 */
public final class TaxiRenderer {
    private static final int BEZEL = 0xff1c1f22, SCREEN = 0xff000000, WHITE = 0xfff4f6f8, MUTED = 0xff8a96a0,
        GREEN = 0xff39e46a, CYAN = 0xff3fd7ff, RED = 0xffff3b3b, AMBER = 0xffffc23c, TAG = 0xff2f6fe0, OFF = 0xff3a4046;
    /** Buttons: vacant, hired, surcharge, pay (800x480 space), shared with the touch handling. */
    public static final RectF[] BUTTONS = {
        new RectF(24, 414, 202, 470), new RectF(214, 414, 392, 470), new RectF(404, 414, 582, 470), new RectF(594, 414, 776, 470)};
    public static final int VACANT_BUTTON = 0, HIRE_BUTTON = 1, SURCHARGE_BUTTON = 2, PAY_BUTTON = 3;
    private static final int[] BUTTON_FILL = {0xffffc4dc, 0xffbcdcff, 0xffffb6cc, 0xffbdf2ae};
    private static final int JP_SCREEN = 0xff2b2f34;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Region switch inside the screen, top right: KR | JP (Japanese layout: left of the key column). */
    public static final RectF REGION = new RectF(684, 78, 762, 106), JP_REGION = new RectF(576, 82, 654, 110);
    public static final int REGION_BUTTON = 4, RECEIPT_BUTTON = 5;
    /** Japanese key column (空車, 実車, 支払, 合計, 領収) and on-screen soft keys (取消, 割増). */
    public static final RectF[] JP_KEYS = {
        new RectF(688, 76, 778, 148), new RectF(688, 154, 778, 226), new RectF(688, 232, 778, 304),
        new RectF(688, 310, 778, 382), new RectF(688, 388, 778, 460)};
    private static final int[] JP_KEY_ACTION = {VACANT_BUTTON, HIRE_BUTTON, PAY_BUTTON, PAY_BUTTON, RECEIPT_BUTTON};
    public static final RectF[] JP_SOFT = {new RectF(44, 404, 164, 448), new RectF(176, 404, 296, 448)};
    private static final int[] JP_SOFT_ACTION = {VACANT_BUTTON, SURCHARGE_BUTTON};

    /** Action under a touch for the region's layout, or -1. */
    public static int hit(float x, float y, int region) {
        if (region == TaxiMeter.JAPAN) {
            for (int i = 0; i < JP_KEYS.length; i++) if (JP_KEYS[i].contains(x, y)) return JP_KEY_ACTION[i];
            for (int i = 0; i < JP_SOFT.length; i++) if (JP_SOFT[i].contains(x, y)) return JP_SOFT_ACTION[i];
            return JP_REGION.contains(x, y) ? REGION_BUTTON : -1;
        }
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].contains(x, y)) return i;
        if (REGION.contains(x, y)) return REGION_BUTTON;
        return -1;
    }

    /**
     * Touch slot under a point, for the pressed look: Korean buttons 0-3, Japanese keys 10-14, soft keys 20-21,
     * region switch 30; -1 for none.
     */
    public static int slot(float x, float y, int region) {
        if (region == TaxiMeter.JAPAN) {
            for (int i = 0; i < JP_KEYS.length; i++) if (JP_KEYS[i].contains(x, y)) return 10 + i;
            for (int i = 0; i < JP_SOFT.length; i++) if (JP_SOFT[i].contains(x, y)) return 20 + i;
            return JP_REGION.contains(x, y) ? 30 : -1;
        }
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i].contains(x, y)) return i;
        return REGION.contains(x, y) ? 30 : -1;
    }

    /** Whether an action does something in the meter's current state (disabled buttons are drawn dim). */
    public static boolean enabled(int action, TaxiMeter m) {
        int state = m.state();
        if (action == HIRE_BUTTON) return state != TaxiMeter.HIRED;
        if (action == PAY_BUTTON) return state != TaxiMeter.VACANT;
        if (action == RECEIPT_BUTTON) return state == TaxiMeter.PAID;
        return true;   // vacant (clear) and surcharge always respond
    }

    private int pressed = -1;
    /** Slot currently held down (see {@link #slot}), -1 for none. */
    public void setPressed(int slot) { pressed = slot; }

    /** Pressed: darker and shifted down; disabled: veiled. Drawn over a button face. */
    private void feedback(Canvas c, float l, float t, float r, float b, float rad, boolean down, boolean off) {
        if (down) { p.setColor(0x55000000); rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p); }
        else if (off) { p.setColor(0x8c15181b); rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p); }
    }

    /** Korean meters show the receipt on 지불; Japanese ones settle on 支払 and print with 領収. */
    public static boolean payShowsReceipt(int region) { return region != TaxiMeter.JAPAN; }

    /**
     * Draws the page; speed is the received km/h (NaN when unknown); hour is the wall-clock hour; now drives
     * the horse; receipt shows the paper receipt over the meter (after paying).
     */
    public void draw(Canvas c, TaxiMeter m, float speed, int hour, long now, boolean receipt) {
        boolean japan = m.region() == TaxiMeter.JAPAN;
        if (japan) japan(c, m, speed, hour);
        else {
            p.setStyle(Paint.Style.FILL); p.setColor(0xff0b0d0e); c.drawRect(0, 52, 800, 480, p);
            round(c, 18, 60, 782, 404, 14, BEZEL);
            round(c, 30, 70, 770, 394, 6, SCREEN);
            korea(c, m, speed, hour, now);
            buttons(c, m);
        }
        region(c, japan ? JP_REGION : REGION, japan);
        if (receipt && m.state() == TaxiMeter.PAID) receipt(c, m, hour, japan);
    }

    /** The table in use on one line: area (GPS or not), base fare and distance, step distance and time. */
    static String tariffNote(TaxiMeter m) {
        TaxiMeter.Tariff t = m.tariff();
        String area = UiText.t(m.area()) + (t.area != null && t.area.borrowed ? "(" + UiText.t("서울 요금") + ")" : "");
        String gps = m.located() ? UiText.t("GPS 지역") : UiText.t("GPS 없음 · 마지막 지역");
        return String.format(Locale.US, UiText.t("%s 중형 (%s): 기본 %,d원/%.1fkm · %.0fm당 100원 · %.2fkm/h 이하 %.0f초당 100원"),
            area, gps, t.baseFare, t.baseM / 1000f, t.stepM, t.slowKmh, t.stepS)
            + (m.autoOutside() ? " · " + UiText.t("시계외 자동") : "");
    }

    /** Korean colour LCD meter: galloping horse, speed, large white fare, surcharges and status numbers. */
    private void korea(Canvas c, TaxiMeter m, float speed, int hour, long now) {
        int state = m.state();
        boolean running = state == TaxiMeter.HIRED;
        horse(c, 38, 78, running, speed, now, state == TaxiMeter.VACANT);
        text(c, String.format(Locale.US, "%.2f km", m.distanceKm()), 258, 112, 22, CYAN, Paint.Align.LEFT, true);
        String sp = Float.isNaN(speed) ? "—" : String.format(Locale.US, "%.1f", speed);
        text(c, sp + " Km/h", 600, 112, 28, GREEN, Paint.Align.RIGHT, true);
        int fare = state == TaxiMeter.VACANT ? m.tariff().baseFare : m.fare();
        bigFare(c, String.valueOf(fare), 600, 240, state == TaxiMeter.VACANT ? 0xff6a7076 : WHITE);
        text(c, UiText.t("원"), 612, 190, 32, WHITE, Paint.Align.LEFT, true);
        text(c, "(WON)", 612, 216, 14, MUTED, Paint.Align.LEFT, false);
        float night = m.nightNow(hour), total = m.rate(hour);
        text(c, UiText.t("심야"), 722, 150, 15, night > 0 ? AMBER : OFF, Paint.Align.CENTER, true);
        text(c, Math.round(night * 100) + "%", 722, 174, 20, night > 0 ? AMBER : OFF, Paint.Align.CENTER, true);
        text(c, UiText.t("시계외"), 722, 210, 15, m.outOfCity() ? AMBER : OFF, Paint.Align.CENTER, true);
        text(c, m.outOfCity() ? Math.round(m.outRate() * 100) + "%" : "0%", 722, 234, 20, m.outOfCity() ? AMBER : OFF, Paint.Align.CENTER, true);
        text(c, Math.round(total * 100) + "%", 52, 252, 30, GREEN, Paint.Align.LEFT, true);
        text(c, time(m.elapsedMs()), 52, 296, 34, RED, Paint.Align.LEFT, true);
        String tag = state == TaxiMeter.VACANT ? UiText.t("빈차") : state == TaxiMeter.HIRED ? UiText.t("주행") : UiText.t("지불");
        round(c, 52, 312, 140, 342, 6, state == TaxiMeter.VACANT ? 0xffd0405a : state == TaxiMeter.HIRED ? TAG : 0xff2f9e4a);
        text(c, tag, 96, 334, 17, WHITE, Paint.Align.CENTER, true);
        if (running) text(c, String.format(Locale.US, UiText.t("다음 100원까지 %.0f m"), m.toNextStepM(hour)), 156, 334, 15, AMBER, Paint.Align.LEFT, false);
        String info = "1." + UiText.t("거리") + " " + String.format(Locale.US, "%.2f", m.distanceKm()) + "km   2."
            + UiText.t("운행 시간") + " " + time(m.elapsedMs()) + "   3." + UiText.t("저속 시간") + " " + time(m.slowMs())
            + "   4." + UiText.t("심야 할증은 시계로 자동 적용");
        text(c, info, 52, 366, 13, WHITE, Paint.Align.LEFT, false);
        text(c, tariffNote(m), 52, 384, 11, MUTED, Paint.Align.LEFT, false);
    }

    /**
     * Japanese touch-screen meter: grey screen with a status tag, a green progress bar toward the next step,
     * the fare in white on a blue band, grey info rows and on-screen soft keys (取消, 割増); a column of keys
     * with coloured lamps on the right (空車, 実車, 支払, 合計, 領収 positions). Labels follow the UI language
     * (Korean or English) for readability; the receipt stays Japanese.
     */
    private void japan(Canvas c, TaxiMeter m, float speed, int hour) {
        int state = m.state();
        p.setStyle(Paint.Style.FILL); p.setColor(0xff0b0d0e); c.drawRect(0, 52, 800, 480, p);
        round(c, 18, 60, 782, 474, 14, BEZEL);
        round(c, 30, 70, 676, 462, 6, JP_SCREEN);
        // Status tag and surcharge marks.
        int tagFill = state == TaxiMeter.VACANT ? 0xffd8262e : state == TaxiMeter.HIRED ? 0xff2aa7a0 : 0xffe0a020;
        round(c, 44, 82, 132, 114, 5, tagFill);
        text(c, state == TaxiMeter.VACANT ? ui("빈차", "VACANT") : state == TaxiMeter.HIRED ? ui("주행", "HIRED") : ui("지불", "PAY"), 88, 106, 20, WHITE, Paint.Align.CENTER, true);
        float rate = m.rate(hour), night = m.nightNow(hour);
        jpMark(c, 44, 126, ui("할증", "Surcharge"), rate > 0);
        jpMark(c, 44, 162, ui("심야", "Night"), night > 0);
        // Progress toward the next 100 yen.
        float step = m.tariff().stepM, left = state == TaxiMeter.HIRED ? Math.min(step, m.toNextStepM(hour)) : step;
        round(c, 146, 92, 560, 100, 4, 0xff3c434a);
        if (state == TaxiMeter.HIRED) round(c, 146, 92, 146 + 414 * (1 - left / step), 100, 4, 0xff4cdc6a);
        // Fare band.
        round(c, 146, 116, 662, 206, 6, 0xff1d3f9a);
        round(c, 146, 116, 662, 136, 6, 0xff2b58c4);
        text(c, state == TaxiMeter.PAID ? ui("합계", "Total") : ui("요금", "Fare"), 160, 196, 18, WHITE, Paint.Align.LEFT, true);
        int fare = state == TaxiMeter.VACANT ? m.tariff().baseFare : m.fare();
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(78);
        String fareText = String.format(Locale.US, "%,d", fare);
        float fw = p.measureText(fareText), size = fw > 380 ? 78 * 380 / fw : 78;
        text(c, fareText, 628, 196, size, state == TaxiMeter.VACANT ? 0xff8fa0c8 : WHITE, Paint.Align.RIGHT, true);
        text(c, "円", 652, 196, 18, WHITE, Paint.Align.RIGHT, true);
        // Info rows.
        jpRow(c, 218, ui("거리", "Distance"), String.format(Locale.US, "%.2f km", m.distanceKm()));
        jpRow(c, 256, ui("시간", "Time"), time(m.elapsedMs()) + "   (" + ui("저속", "slow") + " " + time(m.slowMs()) + ")");
        String sp = Float.isNaN(speed) ? "—" : String.format(Locale.US, "%.0f km/h", speed);
        jpRow(c, 294, ui("속도", "Speed"), sp);
        jpRow(c, 332, ui("할증", "Surcharge"), rate > 0 ? Math.round(rate * 100) + "%" + (night > 0 ? " (" + ui("심야", "night") + ")" : "") : ui("없음", "None"));
        text(c, ui("도쿄 23구 기준(2022.11): 첫 1.096km 500엔 · 255m마다 100엔 · 10km/h 이하 95초마다 100엔 · 심야 22–5시 20% · 참고용",
            "Tokyo (Nov 2022): 500 yen/1.096 km · 100 yen per 255 m or 95 s at ≤10 km/h · night 22–5 h +20% · reference"), 44, 388, 11, 0xffa8b0b8, Paint.Align.LEFT, false);
        // Soft keys on the screen.
        String[] soft = {ui("취소", "Clear"), ui("할증", "Surcharge")};
        for (int i = 0; i < JP_SOFT.length; i++) {
            RectF r = JP_SOFT[i];
            boolean on = i == 1 && m.outOfCity(), down = pressed == 20 + i;
            round(c, r.left, r.top, r.right, r.bottom, 7, on ? 0xffffe0a0 : 0xfff2f4f6);
            feedback(c, r.left, r.top, r.right, r.bottom, 7, down, !enabled(JP_SOFT_ACTION[i], m));
            text(c, soft[i], r.centerX(), r.centerY() + 7 + (down ? 2 : 0), 18, 0xff1f2328, Paint.Align.CENTER, true);
        }
        // Key column with lamps.
        String[] keys = {ui("빈차", "Vacant"), ui("주행", "Hired"), ui("지불", "Pay"), ui("합계", "Total"), ui("영수증", "Receipt")};
        int[] keyText = {0xffd8262e, 0xff2457d6, 0xff2a2e33, 0xff2a2e33, 0xff2a2e33};
        int[] lamp = {0xffff3b3b, 0xff3a6dff, 0xffd8dade, 0xffc8875a, 0xffc8875a};
        boolean[] lit = {state == TaxiMeter.VACANT, state == TaxiMeter.HIRED, state == TaxiMeter.PAID, false, false};
        for (int i = 0; i < JP_KEYS.length; i++) {
            RectF r = JP_KEYS[i];
            round(c, r.left, r.top, r.left + 52, r.bottom, 6, lit[i] ? 0xffffffff : 0xffe4e6ea);
            if (lit[i]) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setColor(keyText[i] == 0xff2a2e33 ? 0xffe0a020 : keyText[i]);
                rect.set(r.left + 1, r.top + 1, r.left + 51, r.bottom - 1); c.drawRoundRect(rect, 6, 6, p); p.setStyle(Paint.Style.FILL); }
            boolean down = pressed == 10 + i, off = !enabled(JP_KEY_ACTION[i], m);
            feedback(c, r.left, r.top, r.left + 52, r.bottom, 6, down, off);
            p.setTextSize(18); p.setTypeface(Typeface.DEFAULT_BOLD);
            float kw = p.measureText(keys[i]), ks = kw > 46 ? 18 * 46 / kw : 18;   // long labels shrink to the key
            text(c, keys[i], r.left + 26, r.centerY() + 7 + (down ? 2 : 0), ks, keyText[i], Paint.Align.CENTER, true);
            float lx = r.left + 72, ly = r.centerY();
            p.setColor(0xff111315); c.drawCircle(lx, ly, 17, p);
            p.setColor(down ? 0xffffffff : lit[i] || (i >= 3 && !off) ? lamp[i] : (lamp[i] & 0x00ffffff) | 0x55000000); c.drawCircle(lx, ly, down ? 15 : 14, p);
            p.setColor(0x66ffffff); c.drawCircle(lx - 4, ly - 5, 4, p);
        }
    }

    /** Japanese-meter labels shown in the UI language so they are readable (the receipt stays Japanese). */
    private static String ui(String ko, String en) { return "ko".equals(UiText.language()) ? ko : en; }

    private void jpMark(Canvas c, float x, float y, String s, boolean on) {
        round(c, x, y, x + 88, y + 28, 5, on ? 0xffff8a1e : 0xff3a4046);
        text(c, s, x + 44, y + 20, 15, on ? 0xff1b1f24 : 0xff8a949c, Paint.Align.CENTER, true);
    }

    private void jpRow(Canvas c, float y, String label, String value) {
        round(c, 146, y, 662, y + 32, 4, 0xff43494f);
        text(c, label, 160, y + 23, 17, 0xffe8ecf0, Paint.Align.LEFT, true);
        text(c, value, 648, y + 23, 19, WHITE, Paint.Align.RIGHT, true);
    }

    /** Large fare figures right-aligned at `right`, shrunk to fit 340 px. */
    private void bigFare(Canvas c, String s, float right, float baseline, int colour) {
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(118);
        float fw = p.measureText(s), size = fw > 340 ? 118 * 340 / fw : 118;
        text(c, s, right, baseline, size, colour, Paint.Align.RIGHT, true);
    }

    private void region(Canvas c, RectF box, boolean japan) {
        round(c, box.left, box.top, box.right, box.bottom, 6, pressed == 30 ? 0xff3a4046 : 0xff1a1d20);
        float mid = box.centerX();
        round(c, japan ? mid : box.left, box.top, japan ? box.right : mid, box.bottom, 6, 0xff2f6fe0);
        text(c, "KR", (box.left + mid) / 2, box.bottom - 9, 13, japan ? MUTED : WHITE, Paint.Align.CENTER, true);
        text(c, "JP", (mid + box.right) / 2, box.bottom - 9, 13, japan ? WHITE : MUTED, Paint.Align.CENTER, true);
    }

    /** Korean pastel buttons under the screen; the current state's button is outlined. */
    private void buttons(Canvas c, TaxiMeter m) {
        int state = m.state();
        String[] labels = {UiText.t("빈차"), UiText.t("주행"), UiText.t("할증"), UiText.t("지불")};
        int[] fill = BUTTON_FILL;
        boolean[] active = {state == TaxiMeter.VACANT, state == TaxiMeter.HIRED, m.outOfCity(), state == TaxiMeter.PAID};
        for (int i = 0; i < BUTTONS.length; i++) {
            RectF r = BUTTONS[i];
            boolean down = pressed == i, off = !enabled(i, m);
            round(c, r.left, r.top, r.right, r.bottom, 8, fill[i]);
            if (active[i]) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(WHITE);
                rect.set(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f); c.drawRoundRect(rect, 8, 8, p);
                p.setStyle(Paint.Style.FILL);
            } else if (!off) {
                p.setColor(0x26000000); rect.set(r.left, r.top, r.right, r.bottom); c.drawRoundRect(rect, 8, 8, p);
            }
            feedback(c, r.left, r.top, r.right, r.bottom, 8, down, off);
            text(c, labels[i], r.centerX(), r.centerY() + 9 + (down ? 2 : 0), 25, off ? 0xff5c6168 : 0xff1b1f24, Paint.Align.CENTER, true);
        }
    }

    // ------------------------------------------------------------ receipt
    private final android.graphics.Path tear = new android.graphics.Path();
    private final java.text.SimpleDateFormat receiptDate = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final java.text.SimpleDateFormat receiptTime = new java.text.SimpleDateFormat("HH:mm", Locale.US);

    /** Paper receipt over the meter after paying: ride times, distance, fare breakdown and total. Tap closes it. */
    private void receipt(Canvas c, TaxiMeter m, int hour, boolean japan) {
        if (japan) { receiptJapan(c, m, hour); return; }
        p.setStyle(Paint.Style.FILL); p.setColor(0xb0000000); c.drawRect(0, 52, 800, 480, p);
        float l = 262, r = 538, t = 60, b = 472;
        p.setColor(0x55000000); c.drawRect(l + 5, t + 6, r + 5, b + 6, p);
        p.setColor(0xfffbfaf4); c.drawRect(l, t, r, b, p);
        // Torn bottom edge.
        p.setColor(0xb0000000);
        for (float x = l; x < r; x += 12) { tear.reset(); tear.moveTo(x, b); tear.lineTo(x + 6, b - 6); tear.lineTo(x + 12, b); tear.close(); c.drawPath(tear, p); }
        int ink = 0xff26282c, soft = 0xff6b6f76;
        String unit = japan ? "円" : UiText.t("원");
        receiptDate.setTimeZone(java.util.TimeZone.getDefault()); receiptTime.setTimeZone(java.util.TimeZone.getDefault());
        long start = m.startWall(), end = m.endWall();
        float y = t + 40;
        mono(c, japan ? "領 収 書" : UiText.t("영수증"), 400, y, 24, ink, Paint.Align.CENTER, true);
        y += 22; mono(c, japan ? "タクシー料金(参考表示)" : UiText.t("택시 요금 (참고용)"), 400, y, 13, soft, Paint.Align.CENTER, false);
        y += 16; dash(c, l + 16, r - 16, y);
        y += 26; row(c, japan ? "日付" : UiText.t("날짜"), start > 0 ? receiptDate.format(new java.util.Date(start)) : "—", y, ink);
        y += 24; row(c, japan ? "乗車" : UiText.t("승차"), start > 0 ? receiptTime.format(new java.util.Date(start)) : "—", y, ink);
        y += 24; row(c, japan ? "降車" : UiText.t("하차"), end > 0 ? receiptTime.format(new java.util.Date(end)) : "—", y, ink);
        y += 24; row(c, japan ? "時間" : UiText.t("운행 시간"), time(m.elapsedMs()), y, ink);
        y += 24; row(c, japan ? "距離" : UiText.t("거리"), String.format(Locale.US, "%.2f km", m.distanceKm()), y, ink);
        y += 14; dash(c, l + 16, r - 16, y);
        y += 26; row(c, japan ? "初乗運賃" : UiText.t("기본요금"), String.format(Locale.US, "%,d %s", m.baseCharged(), unit), y, ink);
        y += 24; row(c, japan ? "加算運賃" : UiText.t("거리·시간 요금"), String.format(Locale.US, "%,d %s", m.stepsCharged(), unit), y, ink);
        float rate = m.rate(hour);
        y += 24; row(c, japan ? "割増" : UiText.t("할증"), rate > 0 ? Math.round(rate * 100) + "%" : (japan ? "なし" : UiText.t("없음")), y, ink);
        y += 14; dash(c, l + 16, r - 16, y);
        y += 36;
        mono(c, japan ? "合計" : UiText.t("합계"), l + 22, y, 20, ink, Paint.Align.LEFT, true);
        mono(c, String.format(Locale.US, "%,d %s", m.fare(), unit), r - 22, y, 26, ink, Paint.Align.RIGHT, true);
        y += 30; mono(c, japan ? "ご乗車ありがとうございました" : UiText.t("이용해 주셔서 감사합니다"), 400, y, 13, soft, Paint.Align.CENTER, false);
        y += 20; mono(c, japan ? "画面をタッチすると閉じます" : UiText.t("화면을 누르면 닫힙니다"), 400, y, 11, soft, Paint.Align.CENTER, false);
    }

    private final java.text.SimpleDateFormat jpDate = new java.text.SimpleDateFormat("yyyy年MM月dd日", Locale.US);

    /**
     * Japanese thermal receipt (領収書): narrow paper, receipt number, date and times, fare lines with yen
     * amounts written "¥1900円", fare total and tolls, a large 合計, and "上記の通り領収致しました".
     */
    private void receiptJapan(Canvas c, TaxiMeter m, int hour) {
        float l = 290, r = 510, t = 58, b = 474, cx = (l + r) / 2;
        p.setStyle(Paint.Style.FILL); p.setColor(0xb0000000); c.drawRect(0, 52, 800, 480, p);
        p.setColor(0x55000000); c.drawRect(l + 5, t + 6, r + 5, b + 6, p);
        p.setColor(0xfffdfdfb); c.drawRect(l, t, r, b, p);
        int ink = 0xff2e3034, soft = 0xff6b6f76;
        jpDate.setTimeZone(java.util.TimeZone.getDefault()); receiptTime.setTimeZone(java.util.TimeZone.getDefault());
        long start = m.startWall(), end = m.endWall();
        float y = t + 34;
        mono(c, "領 収 書", cx, y, 22, ink, Paint.Align.CENTER, true);
        mono(c, String.format(Locale.US, "No.%04d", m.receiptNo()), r - 10, y - 10, 11, soft, Paint.Align.RIGHT, false);
        y += 22; mono(c, start > 0 ? jpDate.format(new java.util.Date(start)) : "", cx + 30, y, 13, ink, Paint.Align.CENTER, false);
        y += 22; jpLine(c, l, r, "日付", start > 0 ? receiptTime.format(new java.util.Date(start)) + "–" + (end > 0 ? receiptTime.format(new java.util.Date(end)) : "") : "", y, ink);
        y += 19; jpLine(c, l, r, "距離", String.format(Locale.US, "%.2fkm", m.distanceKm()), y, ink);
        y += 19; jpLine(c, l, r, "時間", time(m.elapsedMs()), y, ink);
        y += 19; jpLine(c, l, r, "初乗運賃", yen(m.baseCharged()), y, ink);
        y += 19; jpLine(c, l, r, "加算運賃", yen(m.stepsCharged()), y, ink);
        float rate = m.rate(hour);
        y += 19; jpLine(c, l, r, "割増", rate > 0 ? Math.round(rate * 100) + "%" : "なし", y, ink);
        y += 10; dash(c, l + 12, r - 12, y);
        y += 22; jpLine(c, l, r, "運賃料金計", yen(m.fare()), y, ink);
        y += 19; jpLine(c, l, r, "通行料他", yen(0), y, ink);
        y += 40;
        mono(c, "合 計", l + 14, y, 20, ink, Paint.Align.LEFT, true);
        mono(c, yen(m.fare()), r - 12, y, 26, ink, Paint.Align.RIGHT, true);
        y += 22; mono(c, "上記の通り領収致しました", cx, y, 12, ink, Paint.Align.CENTER, false);
        y += 14; dash(c, l + 12, r - 12, y);
        y += 26; mono(c, "VELOSTER N TAXI METER", cx, y, 13, ink, Paint.Align.CENTER, true);
        y += 18; mono(c, "参考表示 · 公認メーターではありません", cx, y, 10, soft, Paint.Align.CENTER, false);
        y += 26; mono(c, "画面をタッチすると閉じます", cx, y, 11, soft, Paint.Align.CENTER, false);
    }

    private static String yen(int v) { return "¥" + v + "円"; }

    private void jpLine(Canvas c, float l, float r, String label, String value, float y, int ink) {
        mono(c, label, l + 14, y, 13, ink, Paint.Align.LEFT, false);
        mono(c, value, r - 12, y, 13, ink, Paint.Align.RIGHT, false);
    }

    private void row(Canvas c, String label, String value, float y, int ink) {
        mono(c, label, 284, y, 15, ink, Paint.Align.LEFT, false);
        mono(c, value, 516, y, 15, ink, Paint.Align.RIGHT, false);
    }

    private void dash(Canvas c, float x0, float x1, float y) {
        p.setColor(0xff9a9ea6);
        for (float x = x0; x < x1; x += 8) c.drawRect(x, y, Math.min(x + 4, x1), y + 1.5f, p);
    }

    private void mono(Canvas c, String s, float x, float y, float size, int colour, Paint.Align align, boolean bold) {
        p.setStyle(Paint.Style.FILL); p.setColor(colour); p.setTextAlign(align); p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL)); c.drawText(s, x, y, p);
    }

    // ------------------------------------------------------------ horse
    /**
     * Galloping horse as a VFD-style dot matrix: each frame of the line-art strip
     * (res/drawable-nodpi/taxi_horse.png) is reduced once to a grid of dots (a dot is lit when any pixel of
     * its cell is bright, so thin lines stay continuous). Lit dots glow cyan, unlit ones show faintly.
     */
    static final int DOT_COLS = 46;
    static final float DOT_PITCH = 4.1f;
    /** Mean cell alpha (0-255) needed to light a dot. */
    static int DOT_LEVEL = 55;
    private boolean[][] dotFrames;
    private int dotRows;

    public void setHorse(Bitmap strip, int frames) {
        dotFrames = null;
        if (strip == null) return;
        int n = Math.max(1, frames), cw = strip.getWidth() / n, ch = strip.getHeight();
        dotRows = Math.max(1, Math.round(DOT_COLS * ch / (float) cw));
        int[] px = new int[cw * ch];
        boolean[][] out = new boolean[n][DOT_COLS * dotRows];
        for (int f = 0; f < n; f++) {
            strip.getPixels(px, 0, cw, f * cw, 0, cw, ch);
            for (int r = 0; r < dotRows; r++) {
                int y0 = r * ch / dotRows, y1 = Math.max(y0 + 1, (r + 1) * ch / dotRows);
                for (int col = 0; col < DOT_COLS; col++) {
                    int x0 = col * cw / DOT_COLS, x1 = Math.max(x0 + 1, (col + 1) * cw / DOT_COLS);
                    // Mean brightness of the cell: bright main contours light a dot, faint inner strokes do not.
                    int sum = 0, count = 0;
                    for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { sum += px[y * cw + x] >>> 24; count++; }
                    out[f][r * DOT_COLS + col] = sum > DOT_LEVEL * count;
                }
            }
        }
        dotFrames = out;
    }

    private double horsePhase;
    private float horseFps;
    private long horseLast = -1;

    /**
     * Advances the gallop with the speed: the rate eases toward the target so speed changes do not jump;
     * when the car stops the horse finishes its stride and stands on the first frame. Vacant/paid: standing.
     */
    private void horse(Canvas c, float x, float y, boolean running, float speed, long now, boolean dim) {
        if (dotFrames == null) return;
        int n = dotFrames.length;
        float dt = horseLast < 0 || now < horseLast ? 0f : Math.min(0.5f, (now - horseLast) / 1000f);
        horseLast = now;
        float target = running ? TaxiMeter.gallopFps(speed) : 0f;
        horseFps += (target - horseFps) * Math.min(1f, dt * 2.5f);
        int current = (int) Math.floor(horsePhase) % n;
        if (target == 0f && horseFps < 2.5f) {
            // Finish the stride at walking pace, then stand.
            if (current != 0) horsePhase += Math.max(2.5f, horseFps) * dt; else { horsePhase = Math.floor(horsePhase); horseFps = 0f; }
        } else horsePhase += horseFps * dt;
        if (horsePhase > 1e6) horsePhase -= Math.floor(horsePhase / n) * n;
        int frame = (int) Math.floor(horsePhase) % n;
        boolean[] dots = dotFrames[frame];
        int lit = dim ? 0xff1d6a78 : 0xff5ff0ff, glow = dim ? 0x00000000 : 0x445ff0ff;
        float r = DOT_PITCH * 0.36f;
        p.setStyle(Paint.Style.FILL);
        for (int row = 0; row < dotRows; row++) {
            for (int col = 0; col < DOT_COLS; col++) {
                float cx = x + col * DOT_PITCH + DOT_PITCH / 2, cy = y + row * DOT_PITCH + DOT_PITCH / 2;
                if (dots[row * DOT_COLS + col]) {
                    if (glow != 0) { p.setColor(glow); c.drawCircle(cx, cy, r * 1.9f, p); }
                    p.setColor(lit); c.drawCircle(cx, cy, r * 1.1f, p);
                } else {
                    p.setColor(0xff0b1a1e); c.drawCircle(cx, cy, r * 0.75f, p);
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers
    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void round(Canvas c, float l, float t, float r, float b, float rad, int fill) {
        p.setStyle(Paint.Style.FILL); p.setColor(fill); rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, p);
    }

    private void text(Canvas c, String s, float x, float y, float size, int colour, Paint.Align align, boolean bold) {
        p.setStyle(Paint.Style.FILL); p.setColor(colour); p.setTextAlign(align); p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); c.drawText(s, x, y, p);
    }
}
