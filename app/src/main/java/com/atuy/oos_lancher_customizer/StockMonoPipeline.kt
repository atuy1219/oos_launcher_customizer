package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * ColorOS 17 framework adapter. Only explicit renders requested by the existing
 * home-surface policy enter this scope; unrelated framework callers are untouched.
 * On incompatible firmware the caller keeps its previous renderer.
 */
internal class StockMonoPipeline {
    private data class Request(
        val context: Context, val component: ComponentName, val raw: Drawable,
        val order: List<String>, var source: String? = null,
        var inTheme: Boolean = false, var inApp: Boolean = false,
        var generated: Boolean = false, var applied: Boolean = false,
        var deferred: Boolean = false
    )

    private val requests = ThreadLocal<Request>()
    private var extension: Constructor<*>? = null
    private var getLoader: Method? = null
    private var load: Method? = null
    @Volatile private var ready = false

    fun install(
        classLoader: ClassLoader,
        hook: (Method, String, (XposedInterface.Chain) -> Any?) -> Unit
    ) {
        runCatching {
            val loader = Class.forName("android.app.OplusUXIconLoaderImpl", false, classLoader)
            val style = Class.forName("android.app.uxicons.style.CustomStyle", false, classLoader)
            val loadContext = Class.forName("android.app.uxicons.UxIconLoadContext", false, classLoader)
            val pmExt = Class.forName("android.app.UxIconPackageManagerExt", false, classLoader)
            val pmInterface = Class.forName("android.content.res.IUxIconPackageManagerExt", false, classLoader)
            val bool = Boolean::class.javaPrimitiveType!!
            val int = Int::class.javaPrimitiveType!!
            val drawable = Drawable::class.java
            val resources = android.content.res.Resources::class.java
            val theme = style.getDeclaredMethod("tryLoadThemeContent", loadContext, drawable)
            val find = loader.getDeclaredMethod("findAppDrawable", PackageManager::class.java,
                String::class.java, String::class.java, resources, bool, bool, bool)
            val app = style.getDeclaredMethod("buildAppIconContent", drawable, loadContext, bool)
            val desk = style.getDeclaredMethod("buildDeskResult", loadContext, drawable)
            val mono = style.getDeclaredMethod("getMonochromeDrawable", drawable, int)
            extension = pmExt.getDeclaredConstructor(PackageManager::class.java, Context::class.java)
                .apply { isAccessible = true }
            getLoader = loader.getDeclaredMethod("getLoader").apply { isAccessible = true }
            load = loader.getDeclaredMethod("loadUxIcon", pmInterface, String::class.java,
                String::class.java, int, ApplicationInfo::class.java, bool).apply { isAccessible = true }

            hook(theme, "stock-mono-theme") { chain ->
                val request = requests.get()
                if (request == null) chain.proceed()
                else {
                    request.inTheme = true
                    try {
                        val result = chain.proceed()
                        if (result is Drawable && request.source in EXTERNAL) request.applied = true
                        result
                    } finally {
                        request.inTheme = false
                    }
                }
            }
            hook(find, "stock-mono-find") { chain ->
                val request = requests.get()
                if (request == null || !request.inTheme) chain.proceed()
                else select(request, chain)
            }
            for (method in listOf(app, desk)) {
                hook(method, "stock-mono-app-${method.name}") { chain ->
                    val request = requests.get()
                    if (request == null) chain.proceed()
                    else {
                        request.inApp = true
                        try {
                            val result = chain.proceed()
                            if (result is Drawable &&
                                ((request.source == "generated" && request.generated) ||
                                 (request.source == "apk-native" && method == app &&
                                  (chain.args[0] as? AdaptiveIconDrawable)?.monochrome != null))) {
                                request.applied = true
                            }
                            result
                        } finally {
                            request.inApp = false
                        }
                    }
                }
            }
            hook(mono, "stock-mono-generated") { chain ->
                val request = requests.get()
                if (request == null || !request.inApp || request.source != "generated") {
                    chain.proceed()
                } else {
                    val replacement = runCatching {
                        val alpha = Pixel17MonochromeRenderer.render(request.raw,
                            PersistentMonoCache.SIZE, request.context.resources,
                            matchThemeBackground = true, forceGenerated = true)
                        try {
                            val bitmap = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
                            Canvas(bitmap).drawBitmap(alpha, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = chain.args[1] as Int
                            })
                            BitmapDrawable(request.context.resources, bitmap)
                        } finally {
                            alpha.recycle()
                        }
                    }.getOrNull()
                    if (replacement == null) chain.proceed()
                    else {
                        request.generated = true
                        replacement
                    }
                }
            }
            ready = true
            ModuleDiagnostics.i(TAG, "ColorOS 17 scoped monochrome hooks installed")
        }.onFailure { ModuleDiagnostics.w(TAG, "Framework adapter unavailable; retaining legacy path", it) }
    }

    private fun select(request: Request, chain: XposedInterface.Chain): Any? {
        for (source in request.order) {
            when (source) {
                "apk-native" -> if ((request.raw as? AdaptiveIconDrawable)?.monochrome != null) {
                    request.source = source
                    return null
                }
                "oos15" -> {
                    if (!Oos15MonochromeAssets.isReady(request.context)) {
                        request.deferred = true
                        return null
                    }
                    val asset = Oos15MonochromeAssets.load(request.context,
                        request.component.packageName, request.component)
                    if (asset != null) {
                        request.source = source
                        return BitmapDrawable(request.context.resources, asset)
                    }
                }
                "pixel" -> {
                    val asset = PixelLauncherThemedAssets.load(request.component.packageName)
                    if (asset != null) {
                        request.source = source
                        val extra = AdaptiveIconDrawable.getExtraInsetFraction()
                        // The framework applies its own outer negative inset.
                        return InsetDrawable(InsetDrawable(BitmapDrawable(request.context.resources, asset), 0.2f),
                            extra / (1f + 2f * extra))
                    }
                }
                "oplus-live" -> {
                    val asset = chain.proceed()
                    if (asset is Drawable) {
                        request.source = source
                        return asset
                    }
                }
                "generated" -> {
                    request.source = source
                    return null
                }
            }
        }
        return null
    }

    fun render(context: Context, component: ComponentName, raw: Drawable, order: List<String>): Drawable? {
        if (!ready || requests.get() != null) return null
        return runCatching {
            val pm = context.packageManager
            val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()
            val info = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(flags))
            val ext = extension!!.newInstance(pm, context)
            val request = Request(context, component, raw.constantState?.newDrawable(context.resources)?.mutate()
                ?: raw, order)
            requests.set(request)
            try {
                val result = load!!.invoke(getLoader!!.invoke(null), ext, component.packageName,
                    component.className, info.iconResource, info.applicationInfo, true) as? Drawable
                if (result == null || request.deferred || !request.applied) null
                else {
                    ModuleDiagnostics.i(TAG, "stock source pkg=${component.packageName}: ${request.source}")
                    result
                }
            } finally {
                requests.remove()
            }
        }.onFailure { ModuleDiagnostics.w(TAG, "Scoped stock render failed: $component", it) }.getOrNull()
    }

    fun rasterize(drawable: Drawable): Bitmap {
        val size = PersistentMonoCache.SIZE
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val bounds = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(result))
            return result
        } catch (error: Throwable) {
            result.recycle()
            throw error
        } finally {
            drawable.bounds = bounds
        }
    }

    companion object {
        private const val TAG = "OOS_StockMono"
        private val EXTERNAL = setOf("oos15", "pixel", "oplus-live")
    }
}
