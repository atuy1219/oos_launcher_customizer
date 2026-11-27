package com.atuy.oos_lancher_customizer

import android.content.Context

/** A single immutable snapshot for a render and its cache identity. */
internal data class IconSourceScales(
    val apkNative: OplusMonoScale = OplusMonoScale.NATIVE,
    val oos15: OplusMonoScale = OplusMonoScale.NATIVE,
    val pixel: OplusMonoScale = OplusMonoScale.NATIVE,
    val oplusLive: OplusMonoScale = OplusMonoScale.NATIVE,
    val generated: OplusMonoScale = OplusMonoScale.NATIVE
) {
    fun forSource(source: String): OplusMonoScale = when (source) {
        "apk-native" -> apkNative
        "oos15" -> oos15
        "pixel" -> pixel
        "oplus-live" -> oplusLive
        "generated" -> generated
        else -> OplusMonoScale.NATIVE
    }

    val cacheToken: String get() =
        listOf(apkNative, oos15, pixel, oplusLive, generated).joinToString("|") { it.cacheToken }

    companion object {
        const val APK = "oos_customizer_apk_native_mono_scale"
        const val OOS15 = "oos_customizer_oos15_mono_scale"
        const val PIXEL = "oos_customizer_pixel_mono_scale"
        const val LIVE = OplusMonoScale.KEY
        const val GENERATED = "oos_customizer_generated_mono_scale"
        val KEYS = listOf(APK, OOS15, PIXEL, LIVE, GENERATED)
        val DEFAULT = IconSourceScales()

        fun read(context: Context) = IconSourceScales(
            OplusMonoScale.read(context, APK),
            OplusMonoScale.read(context, OOS15),
            OplusMonoScale.read(context, PIXEL),
            OplusMonoScale.read(context, LIVE),
            OplusMonoScale.read(context, GENERATED)
        )
    }
}
