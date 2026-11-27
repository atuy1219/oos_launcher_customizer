package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAssetMonoRendererTest {
    private class Probe : Drawable() {
        var drawn = Rect()
        override fun draw(canvas: Canvas) {
            drawn = Rect(bounds)
            canvas.drawRect(bounds, Paint().apply { color = Color.WHITE })
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @Test fun oos15UsesActualNegativeInset() {
        val source = Probe()
        val expected = Probe()
        val reference = InsetDrawable(expected, -AdaptiveIconDrawable.getExtraInsetFraction())
        reference.setBounds(0, 0, 192, 192)
        val target = Bitmap.createBitmap(192, 192, Bitmap.Config.ALPHA_8)
        reference.draw(Canvas(target))
        val mask = NativeAssetMonoRenderer.renderOos15(source, 192)
        assertEquals(expected.drawn, source.drawn)
        assertEquals(Rect(), source.bounds)
        mask.recycle()
        target.recycle()
    }

    @Test fun pixelVectorUsesThreeNativeInsets() {
        val source = Probe()
        val expected = Probe()
        val extra = AdaptiveIconDrawable.getExtraInsetFraction()
        val reference = InsetDrawable(InsetDrawable(InsetDrawable(expected, 0.2f),
            extra / (1f + 2f * extra)), -extra)
        reference.setBounds(0, 0, 192, 192)
        val target = Bitmap.createBitmap(192, 192, Bitmap.Config.ALPHA_8)
        reference.draw(Canvas(target))
        val mask = NativeAssetMonoRenderer.renderPixel(source, 192)
        assertEquals(expected.drawn, source.drawn)
        assertTrue(source.drawn.width() < 192)
        assertEquals(Rect(), source.bounds)
        mask.recycle()
        target.recycle()
    }

    @Test fun liveOemSquareUsesAdaptiveInsetAndPreservesAlpha() {
        val artwork = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.argb(128, 255, 255, 255) }
        Canvas(artwork).drawRect(80f, 80f, 160f, 160f, paint)
        val resources = android.content.res.Resources.getSystem()
        val glyph = android.graphics.drawable.BitmapDrawable(resources, artwork)
        val reference = InsetDrawable(
            android.graphics.drawable.BitmapDrawable(resources, artwork),
            -AdaptiveIconDrawable.getExtraInsetFraction()
        )
        val target = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        reference.setBounds(0, 0, 192, 192)
        reference.draw(Canvas(target))
        val expected = target.extractAlpha()
        val actual = OplusLiveMonoRenderer.render(glyph, 192) {}
        assertArrayEquals(PersistentMonoCache.pack(expected), PersistentMonoCache.pack(actual))
        val bytes = PersistentMonoCache.pack(actual)
        assertEquals(128, bytes[96 * 192 + 96].toInt() and 255)
        assertEquals(0, bytes[0].toInt() and 255)
        // This point is outside the unexpanded glyph (64..128), but
        // inside the native expanded glyph (48..144).
        assertEquals(128, bytes[96 * 192 + 56].toInt() and 255)
        assertEquals(Rect(), glyph.bounds)
        actual.recycle()
        expected.recycle()
        target.recycle()
        artwork.recycle()
    }
}
