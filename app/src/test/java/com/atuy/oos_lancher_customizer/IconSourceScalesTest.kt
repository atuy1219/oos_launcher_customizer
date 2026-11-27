package com.atuy.oos_lancher_customizer

import org.junit.Assert.*
import org.junit.Test

class IconSourceScalesTest {
    @Test fun allFiveSourcesAreIndependent() {
        val scales = IconSourceScales(
            OplusMonoScale.parse("1.1")!!, OplusMonoScale.parse("1.2")!!,
            OplusMonoScale.parse("0.7")!!, OplusMonoScale.parse("1.4")!!,
            OplusMonoScale.parse("0.9")!!
        )
        assertEquals(listOf("1.1","1.2","0.7","1.4","0.9"),
            listOf("apk-native","oos15","pixel","oplus-live","generated")
                .map { scales.forSource(it).cacheToken })
        assertEquals(5, IconSourceScales.KEYS.distinct().size)
        assertNotEquals(scales.cacheToken, scales.copy(pixel = OplusMonoScale.NATIVE).cacheToken)
    }

    @Test fun defaultAndNativeAreEquivalent() {
        assertEquals(OplusMonoScale.NATIVE, OplusMonoScale.parse("default"))
        assertEquals("native|native|native|native|native", IconSourceScales.DEFAULT.cacheToken)
        assertEquals("oos_customizer_live_mono_scale", IconSourceScales.LIVE)
    }
}
