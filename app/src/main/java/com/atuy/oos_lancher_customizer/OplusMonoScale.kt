package com.atuy.oos_lancher_customizer

import android.content.Context
import android.provider.Settings

/** Shell-accessible global setting. A number scales the longest side relative
 * to the 1x1 frame; native keeps the OEM geometry. */
internal data class OplusMonoScale private constructor(val factor: Float?) {
    val cacheToken: String get() = factor?.toString() ?: "native"

    companion object {
        const val KEY = "oos_customizer_live_mono_scale"
        val NATIVE = OplusMonoScale(null)

        internal fun parse(value: String?): OplusMonoScale? {
            val text = value?.trim() ?: return NATIVE
            if (text.isEmpty() || text.equals("native", ignoreCase = true) ||
                text.equals("default", ignoreCase = true)) return NATIVE
            val number = text.toFloatOrNull() ?: return null
            if (!number.isFinite() || number !in 0.1f..4f) return null
            return OplusMonoScale(number)
        }

        fun read(context: Context, key: String = KEY): OplusMonoScale {
            val raw = runCatching {
                Settings.Global.getString(context.contentResolver, key)
            }.onFailure {
                ModuleDiagnostics.w("OOS_LiveMono", "Cannot read global scale; using native", it)
            }.getOrNull()
            return parse(raw) ?: NATIVE.also {
                ModuleDiagnostics.w("OOS_LiveMono", "Invalid scale $key='$raw'; using native")
            }
        }
    }
}
