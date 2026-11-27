package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PersistentMonoCacheTest {
    @Test fun alphaRoundTripPreservesEveryPixel() {
        val size = PersistentMonoCache.SIZE
        val bytes = ByteArray(size * size) { (it % 251).toByte() }
        val bitmap = PersistentMonoCache.unpack(bytes)!!
        assertEquals(Bitmap.Config.ALPHA_8, bitmap.config)
        assertArrayEquals(bytes, PersistentMonoCache.pack(bitmap))
        bitmap.recycle()
    }

    @Test fun truncatedFileIsRejected() {
        assertNull(PersistentMonoCache.unpack(ByteArray(5)))
    }
}
