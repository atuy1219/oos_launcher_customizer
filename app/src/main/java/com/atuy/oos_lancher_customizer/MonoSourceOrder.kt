package com.atuy.oos_lancher_customizer

internal object MonoSourceOrder {
    const val KEY = "oos_customizer_mono_priority"
    val DEFAULT = listOf("apk-native", "oos15", "pixel", "oplus-live", "generated")

    fun parse(value: String?): List<String> {
        val values = value?.split(',')?.map { it.trim() }.orEmpty()
        return if (values.size == DEFAULT.size && values.toSet() == DEFAULT.toSet()) values else DEFAULT
    }
}
