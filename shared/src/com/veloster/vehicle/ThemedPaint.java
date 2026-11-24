package com.veloster.vehicle;

import android.graphics.Paint;

/** A Paint whose colour goes through the current theme (Theme.map): the renderers keep their N palette literals and
 *  every other theme is a remap; N maps every colour to itself. Bitmaps and shaders are not remapped. */
final class ThemedPaint extends Paint {
    ThemedPaint(int flags){super(flags);}
    @Override public void setColor(int color){super.setColor(Theme.current().map(color));}
    /** A colour that must not be remapped (a theme swatch in the settings list). */
    void setRawColor(int color){super.setColor(color);}
}
