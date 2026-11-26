package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Exact uncolored OEM resource loader in System Launcher 17.3.12 (classes2.dex). */
internal class OplusMonochromeSource {
    data class Result(val drawable: Drawable?, val retry: Boolean)
    private var loader: Method? = null

    fun initialize(classLoader: ClassLoader) {
        loader = runCatching {
            Class.forName("tr.m", false, classLoader)
                .getDeclaredMethod("L", Context::class.java, ComponentName::class.java, String::class.java)
                .takeIf { Modifier.isStatic(it.modifiers) && Drawable::class.java.isAssignableFrom(it.returnType) }
                ?.apply { isAccessible = true }
        }.getOrNull()
        Log.i("OOS_LiveMono", "Native UXIcon loader available: ${loader != null}")
    }

    fun load(context: Context, component: ComponentName): Result {
        val method = loader ?: return Result(null, false)
        return runCatching {
            // The native mono strategy passes an empty suffix for the 1x1 fallback.
            val drawable = method.invoke(null, context, component, "") as? Drawable
            Result(drawable?.constantState?.newDrawable(context.resources)?.mutate() ?: drawable, false)
        }.getOrElse {
            Log.w("OOS_LiveMono", "Deferred OEM icon: $component", it)
            Result(null, true)
        }
    }
}
