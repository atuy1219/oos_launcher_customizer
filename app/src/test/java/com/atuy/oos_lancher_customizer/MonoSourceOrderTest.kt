package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertEquals
import org.junit.Test

class MonoSourceOrderTest {
    @Test fun validPermutationIsPreserved() {
        val order = listOf("pixel", "generated", "apk-native", "oplus-live", "oos15")
        assertEquals(order, MonoSourceOrder.parse(order.joinToString(",")))
    }

    @Test fun invalidOrDuplicateOrderUsesDefault() {
        for (value in listOf(null, "", "generated", "pixel,pixel,apk-native,oplus-live,oos15")) {
            assertEquals(MonoSourceOrder.DEFAULT, MonoSourceOrder.parse(value))
        }
    }
}
