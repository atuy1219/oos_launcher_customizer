package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserManager
import java.io.File
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
        ModuleDiagnostics.i("OOS_LiveMono", "Native UXIcon loader available: ${loader != null}")
    }

    fun load(context: Context, component: ComponentName): Result {
        val method = loader ?: return Result(null, false)
        return runCatching {
            // A source directory inaccessible during Direct Boot is not proof
            // that an OEM glyph is absent. Do not synthesize prematurely.
            val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
            if (!unlocked && !File("/data/oplus/uxicons").canRead()) {
                return Result(null, true)
            }
            // Same two calls as MorphIcon_Mono: explicit dimensions, then 1x1.
            val drawable = (method.invoke(null, context, component, "_1x1") as? Drawable)
                ?: (method.invoke(null, context, component, "") as? Drawable)
            Result(drawable?.constantState?.newDrawable(context.resources)?.mutate() ?: drawable, false)
        }.getOrElse {
            ModuleDiagnostics.w("OOS_LiveMono", "Deferred OEM icon: $component", it)
            Result(null, true)
        }
    }
}
