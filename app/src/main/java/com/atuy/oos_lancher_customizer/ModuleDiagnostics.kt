package com.atuy.oos_lancher_customizer

import android.util.Log

/** Vector persists the default module tag, not arbitrary logcat tags. */
internal object ModuleDiagnostics {
    @Volatile private var sink: ((Int, String, Throwable?) -> Unit)? = null

    fun install(logger: (Int, String, Throwable?) -> Unit) {
        sink = logger
    }

    private fun emit(level: Int, tag: String, message: String, error: Throwable?) {
        val logger = sink
        if (logger != null) {
            logger(level, "[$tag] $message", error)
        } else {
            Log.println(level, tag, if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
        }
    }

    fun i(tag: String, message: String) = emit(Log.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = emit(Log.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = emit(Log.ERROR, tag, message, error)
}
