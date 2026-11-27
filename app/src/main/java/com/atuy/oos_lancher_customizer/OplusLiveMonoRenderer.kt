package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 1x1 monochrome foreground geometry decoded from Launcher 17.3.12:
 * tr.m.I / o / a0 and UxMorphAdaptiveIconDrawable.g.
 * The module intentionally keeps its existing circular mask and palette.
 */
internal object OplusLiveMonoRenderer {
    data class Geometry(val width: Int, val height: Int, val left: Int, val top: Int)

    internal fun geometry(width: Int, height: Int, size: Int): Geometry {
        require(width > 0 && height > 0 && size > 0)
        val x = size.toFloat() / width
        val y = size.toFloat() / height
        // The native square-format mono path fills the frame for near-square
        // art (< 0.1 ratio difference). Other art is centered, scaled down only
        // when it exceeds the available frame; no independent X/Y stretch.
        val scale = if (abs(1f - width.toFloat() / height) < 0.1f) max(x, y)
                    else min(1f, min(x, y))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        return Geometry(w, h, (size - w) / 2, (size - h) / 2)
    }

    fun render(source: Drawable, size: Int, diagnostic: (String) -> Unit): Bitmap {
        require(size == PersistentMonoCache.SIZE)
        val glyph = source.constantState?.newDrawable()?.mutate() ?: source
        val old = Rect(glyph.bounds)
        val pixels = (glyph as? BitmapDrawable)?.bitmap
        val width = glyph.intrinsicWidth.takeIf { it > 0 }
            ?: pixels?.width ?: old.width().takeIf { it > 0 } ?: size
        val height = glyph.intrinsicHeight.takeIf { it > 0 }
            ?: pixels?.height ?: old.height().takeIf { it > 0 } ?: size
        val g = geometry(width, height, size)
        // Rasterize to ARGB first, as tr.m.o does. Non-game themed artwork is
        // recolored with SRC_IN by tr.m.a0: its shape comes from alpha, not RGB.
        // OEM masks use adaptive-layer coordinates. Match Launcher3's
        // ClippedMonoDrawable negative inset before applying our circular mask.
        val drawable = InsetDrawable(glyph, -AdaptiveIconDrawable.getExtraInsetFraction())
        val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(g.left, g.top, g.left + g.width, g.top + g.height)
            drawable.draw(Canvas(flat))
            val alpha = flat.extractAlpha()
            try {
                val data = PersistentMonoCache.pack(alpha)
                var low = 255
                var high = 0
                var solid = 0
                var empty = 0
                var count = 0
                // Only inspect the visible circular region, not outside padding.
                val r = size / 2f
                for (y in 0 until size) for (x in 0 until size) {
                    val dx = x + 0.5f - r
                    val dy = y + 0.5f - r
                    if (dx * dx + dy * dy > r * r) continue
                    val a = data[y * size + x].toInt() and 255
                    low = min(low, a)
                    high = max(high, a)
                    if (a == 255) solid++
                    if (a == 0) empty++
                    count++
                }
                diagnostic("intrinsic=${width}x$height bitmap=${pixels?.width}x${pixels?.height} frame=$g glyphBounds=${glyph.bounds} alpha=$low..$high solid=$solid empty=$empty visible=$count")
                return alpha
            } catch (error: Throwable) {
                alpha.recycle()
                throw error
            }
        } finally {
            glyph.bounds = old
            flat.recycle()
        }
    }
}
