package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable

/** Dedicated source geometry from the supplied launcher APKs.
 * The module still owns the final home-screen palette and circular mask.
 */
internal object NativeAssetMonoRenderer {
    /**
     * OOS 15.8.24 UxIconLoaderUtil.handleIconThemeDrawable wraps the
     * monochrome foreground in InsetDrawable(-extra). In the standard
     * non-custom theme UxCustomAdaptiveIconConfig uses the default icon size
     * for its foreground size, so its normalized foreground frame equals
     * the background frame. Old device-specific custom sizes are not
     * portable to the current launcher and are intentionally not invented.
     */
    fun renderOos15(source: Drawable, size: Int): Bitmap =
        render(source, size) { glyph ->
            InsetDrawable(glyph, -AdaptiveIconDrawable.getExtraInsetFraction())
        }

    /**
     * Pixel 17-907 IconProvider installs the vector as a monochrome layer:
     * inner 0.2 inset, then extra/(1+2*extra) to compensate adaptive padding.
     * MonoIconThemeController subsequently applies the outer negative inset.
     * Keep each actual inset operation (including integer rounding).
     */
    fun renderPixel(source: Drawable, size: Int): Bitmap =
        render(source, size) { glyph ->
            val extra = AdaptiveIconDrawable.getExtraInsetFraction()
            val padded = InsetDrawable(glyph, 0.2f)
            val adaptiveCompensation = InsetDrawable(padded, extra / (1f + 2f * extra))
            InsetDrawable(adaptiveCompensation, -extra)
        }

    private fun render(source: Drawable, size: Int, wrap: (Drawable) -> Drawable): Bitmap {
        require(size > 0)
        val glyph = source.constantState?.newDrawable()?.mutate() ?: source
        val old = Rect(glyph.bounds)
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        try {
            val drawable = wrap(glyph)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(mask))
            return mask
        } catch (error: Throwable) {
            mask.recycle()
            throw error
        } finally {
            glyph.bounds = old
        }
    }
}
