package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

class ThemedIcons : XposedModule() {

    companion object {
        private const val TAG = "OOS16_ThemedIcons"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val BTV_CLASS = "com.android.launcher3.BubbleTextView"
        private const val PREVIEW_MANAGER_CLASS = "com.android.launcher3.folder.PreviewItemManager"
        private const val BIG_PREVIEW_MANAGER_CLASS =
            "com.android.launcher3.folder.big.BigFolderPreviewItemManager"
        private const val BIG_FOLDER_ITEM_CLASS =
            "com.android.launcher3.folder.big.BigFolderItemIcon"
        private const val FLOATING_ICON_VIEW_CLASS = "com.android.launcher3.views.FloatingIconView"
    }

    private val iconCache = IconBitmapCache()
    private val oplusSource = OplusMonochromeSource()
    private val monoCache = PersistentMonoCache()
    @Volatile private var environment: IconEnvironment? = null

    private data class Binding(
        val key: String, val state: Drawable.ConstantState?, val original: WeakReference<Drawable>,
        val version: Int?
    ) {
        fun restore(context: Context): Drawable? =
            state?.newDrawable(context.resources)?.mutate() ?: original.get()
    }
    private data class PreviewBinding(val owner: WeakReference<Any>, val item: Any, val source: Binding?)
    private val bubbles = Collections.synchronizedMap(WeakHashMap<View, Binding>())
    private val folders = Collections.synchronizedMap(WeakHashMap<View, Binding>())
    private val previews = Collections.synchronizedMap(WeakHashMap<Any, PreviewBinding>())
    private val pendingPreviews = Collections.synchronizedMap(
        WeakHashMap<Any, MutableMap<Any, Unit>>()
    )

    private fun markPreviewPending(owner: Any, params: Any) {
        synchronized(pendingPreviews) {
            pendingPreviews.getOrPut(owner) { WeakHashMap() }[params] = Unit
        }
    }

    private fun clearPreviewPending(owner: Any, params: Any) {
        synchronized(pendingPreviews) {
            pendingPreviews[owner]?.remove(params)
        }
    }

    private fun reconcilePendingPreviews(owner: Any?) {
        if (owner == null) return
        val pending = synchronized(pendingPreviews) {
            pendingPreviews[owner]?.keys?.toList().orEmpty()
        }
        for (params in pending) {
            val entry = previews[params] ?: continue
            if (entry.owner.get() !== owner) {
                clearPreviewPending(owner, params)
                continue
            }
            afterPreviewSetDrawable(owner, arrayOf(params, entry.item))
        }
    }

    private fun prepare(context: Context) {
        if (environment != null) return
        synchronized(this) {
            if (environment == null) environment = IconEnvironment(
                context.applicationContext ?: context,
                invalidate = {
                    iconCache.clear()
                    bigFolderViewCache.clear()
                    ThemedIconPolicy.invalidateTheme()
                },
                refresh = ::refreshVisibleIcons,
                trim = { iconCache.clear(); ThemedIconPolicy.trimSnapshots() },
                retry = { iconCache.clearFailures(); refreshVisibleIcons() }
            )
        }
    }

    private fun binding(
        map: MutableMap<View, Binding>, view: View, item: Any, drawable: Drawable
    ): Binding? {
        val key = ThemedIconPolicy.itemKey(item)
        if (ThemedIconPolicy.isKnownThemedDrawable(drawable)) return map[view]?.takeIf { it.key == key }
        return originalBinding(key, drawable, resolveItemType(item) != 0).also { map[view] = it }
    }

    private fun originalBinding(key: String, drawable: Drawable, keepState: Boolean = true): Binding {
        // Regular apps can be reloaded from their manifest; do not retain a
        // second full-color bitmap merely to refresh their themed view.
        if (!keepState) return Binding(key, null, WeakReference(drawable), null)
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?: (CachedReflection.call(drawable, "getBitmap") as? Bitmap)
        return Binding(key, drawable.constantState, WeakReference(drawable),
            bitmap?.generationId ?: drawable.constantState?.hashCode())
    }

    private fun refreshVisibleIcons() {
        val views = synchronized(bubbles) { bubbles.entries.map { it.key to it.value } }
        for ((view, source) in views) {
            val item = ThemedIconPolicy.itemForView(view) ?: readField(view, "mInfo") ?: continue
            if (!view.isAttachedToWindow || ThemedIconPolicy.itemKey(item) != source.key) continue
            val icon = invokeNoArg(view, "getIcon") as? Drawable ?: continue
            runCatching {
                CachedReflection.method(view.javaClass, "setIcon", Drawable::class.java)?.invoke(view, icon)
            }.onFailure { moduleLog("Cannot refresh themed home icon", it) }
        }
        val items = synchronized(folders) { folders.keys.toList() }
        for (view in items) {
            // Some large-folder child Views are drawn by their parent without
            // being attached independently.
            val parent = invokeNoArg(view, "getParentFolderIcon") as? View
            if (view.isAttachedToWindow || parent?.isAttachedToWindow == true) {
                reconcileBigFolderItem(view)
                parent?.invalidate()
            }
        }
        val entries = synchronized(previews) { previews.entries.map { it.key to it.value } }
        for ((params, entry) in entries) {
            val owner = entry.owner.get() ?: continue
            afterPreviewSetDrawable(owner, arrayOf(params, entry.item))
            ThemedIconPolicy.folderIconForPreview(owner)?.invalidate()
        }
    }

    // getDrawableByIndex may run every frame. Do not allocate a new themed
    // Drawable from ConstantState on every draw.
    private data class BigFolderCacheEntry(
        val original: WeakReference<Drawable>,
        val itemKey: String,
        val themed: WeakReference<Drawable>
    )
    private val bigFolderViewCache =
        Collections.synchronizedMap(WeakHashMap<View, BigFolderCacheEntry>())
    @Volatile
    private var hooksInstalled = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE || hooksInstalled) return

        runCatching {
            installHooks(param.defaultClassLoader)
            hooksInstalled = true
        }.onFailure {
            moduleLog("critical: ${it.message}", it)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        ModuleDiagnostics.install { level, message, error ->
            if (error == null) log(level, null, message)
            else log(level, null, message, error)
        }
        moduleLog("icon diagnostics enabled")
        oplusSource.initialize(classLoader)
        val bubbleTextViewClass = Class.forName(BTV_CLASS, false, classLoader)
        hookAllNamedMethods(bubbleTextViewClass, "setIcon", "bubble-setIcon") { chain ->
            val args = chain.args.toTypedArray()
            beforeBubbleSetIcon(chain.thisObject, args)
            chain.proceed(args)
        }
        moduleLog("hooked BubbleTextView#setIcon")
        // setIcon can precede tag/item binding. Reconcile on the binding
        // completion and attachment, without touching draw or animation frames.
        for (name in listOf("applyFromWorkspaceItem", "applyFromWorkspaceItemInfo", "onAttachedToWindow")) {
            hookOptionalNamedMethods(bubbleTextViewClass, name, "bubble-ready-$name") { chain ->
                val result = chain.proceed()
                reconcileBubble(chain.thisObject)
                result
            }
        }

        runCatching {
            val previewManagerClass = Class.forName(PREVIEW_MANAGER_CLASS, false, classLoader)
            hookOptionalNamedMethods(previewManagerClass, "setDrawable", "folder-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                val result = chain.proceed()
                afterPreviewSetDrawable(chain.thisObject, args)
                result
            }
            installPreviewDrawHooks(previewManagerClass, "folder")
            moduleLog("hooked PreviewItemManager#setDrawable")
        }.onFailure {
            moduleLog("PreviewItemManager hook skipped: ${it.message}")
        }

        // BigFolderItemIcon is a plain View rather than BubbleTextView. Its
        // Drawable cannot be captured by the ordinary setIcon/preview hooks.
        // Register the exact ColorOS 16.6.20 image paths, including the preview
        // manager override that does not have to call super#setDrawable.
        runCatching {
            val bigManager = Class.forName(BIG_PREVIEW_MANAGER_CLASS, false, classLoader)
            hookOptionalNamedMethods(bigManager, "setDrawable", "big-preview-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                val result = chain.proceed(args)
                afterPreviewSetDrawable(chain.thisObject, args)
                result
            }
            installPreviewDrawHooks(bigManager, "big-preview")
            moduleLog("hooked BigFolderPreviewItemManager#setDrawable")
        }.onFailure {
            moduleLog("big preview hook skipped: ${it.message}", it)
        }

        runCatching {
            val iconClass = Class.forName(BIG_FOLDER_ITEM_CLASS, false, classLoader)
            hookOptionalNamedMethods(iconClass, "setDrawable", "big-item-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                beforeBigFolderSetDrawable(chain.thisObject, args)
                chain.proceed(args)
            }
            for (methodName in listOf("getDrawable", "getDrawableByIndex")) {
                hookOptionalNamedMethods(iconClass, methodName, "big-item-$methodName") { chain ->
                    val result = chain.proceed()
                    themedBigFolderDrawable(chain.thisObject, result as? Drawable) ?: result
                }
            }
            // setItemInfo/setParentFolderIcon may be called after setDrawable.
            // Reconcile the stored field once both bindings become available.
            for (methodName in listOf("setItemInfo", "setParentFolderIcon", "onAttachedToWindow")) {
                hookOptionalNamedMethods(iconClass, methodName, "big-item-$methodName") { chain ->
                    val result = chain.proceed()
                    reconcileBigFolderItem(chain.thisObject)
                    result
                }
            }
            // Some versions draw the backing field directly rather than calling a getter.
            hookOptionalNamedMethods(iconClass, "onDraw", "big-item-ready-draw") { chain ->
                reconcileBigFolderItem(chain.thisObject)
                chain.proceed()
            }
            moduleLog("hooked BigFolderItemIcon drawable/binding paths")
        }.onFailure {
            moduleLog("BigFolderItemIcon hooks skipped: ${it.message}", it)
        }

        runCatching {
            val floatingIconViewClass = Class.forName(FLOATING_ICON_VIEW_CLASS, false, classLoader)
            hookAllNamedMethods(floatingIconViewClass, "setIcon", "floating-setIcon") { chain ->
                val args = chain.args.toTypedArray()
                beforeFloatingSetIcon(chain.thisObject, args)
                chain.proceed(args)
            }
            moduleLog("hooked FloatingIconView#setIcon")
        }.onFailure {
            moduleLog("FloatingIconView hook skipped: ${it.message}")
        }


    }

    private fun hookOptionalNamedMethods(
        clazz: Class<*>, methodName: String, hookIdPrefix: String,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        if (clazz.declaredMethods.none { it.name == methodName }) return
        runCatching { hookAllNamedMethods(clazz, methodName, hookIdPrefix, interceptor) }
            .onFailure { moduleLog("optional hook skipped: ${clazz.name}#$methodName", it) }
    }

    private fun installPreviewDrawHooks(clazz: Class<*>, prefix: String) {
        for (name in listOf("draw", "drawParams", "drawForFolderIconSnapshot", "buildPreviewDrawableForPage")) {
            hookOptionalNamedMethods(clazz, name, "$prefix-ready-$name") { chain ->
                val owner = chain.thisObject
                reconcilePendingPreviews(owner)
                // ColorOS 17 builds a RenderNode from an ArrayList of params.
                // Cloned params may never have passed setDrawable.
                for (arg in chain.args) {
                    if (arg !is ArrayList<*>) continue
                    for (params in arg.toList()) {
                        if (params == null) continue
                        val drawable = readField(params, "drawable") as? Drawable ?: continue
                        if (ThemedIconPolicy.isCompositeFolderObject(params) ||
                            ThemedIconPolicy.isCompositeFolderObject(drawable)) {
                            val children = invokeNoArg(params, "getCurrentStackedParams") as? Iterable<*>
                            children?.forEach { child ->
                                if (child != null) {
                                    val childItem = readField(child, "item")
                                    val childDrawable = readField(child, "drawable") as? Drawable
                                    if (childItem != null && childDrawable != null &&
                                        !ThemedIconPolicy.isThemedDrawable(childDrawable)) {
                                        afterPreviewSetDrawable(owner, arrayOf(child, childItem))
                                    }
                                }
                            }
                            continue
                        }
                        val item = readField(params, "item") ?: continue
                        if (ThemedIconPolicy.isThemedDrawable(drawable)) continue
                        afterPreviewSetDrawable(owner, arrayOf(params, item))
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun reconcileBubble(owner: Any?) {
        val view = owner as? View ?: return
        val item = ThemedIconPolicy.itemForView(view) ?: readField(view, "mInfo") ?: return
        if (!ThemedIconPolicy.shouldThemeView(view, item)) return
        val drawable = invokeNoArg(view, "getIcon") as? Drawable ?: return
        if (ThemedIconPolicy.isThemedDrawable(drawable)) return
        runCatching {
            CachedReflection.method(view.javaClass, "setIcon", Drawable::class.java)?.invoke(view, drawable)
        }.onFailure { moduleLog("Cannot reconcile bound home icon", it) }
    }

    private fun hookAllNamedMethods(
        clazz: Class<*>,
        methodName: String,
        hookIdPrefix: String,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        check(methods.isNotEmpty()) { "${clazz.name}#$methodName not found" }

        methods.forEach { method ->
            method.isAccessible = true
            val signature = method.parameterTypes.joinToString(",") { it.name }
            hook(method)
                .setId("$hookIdPrefix($signature)")
                .intercept { chain -> interceptor(chain) }
        }
    }

    private fun beforeBubbleSetIcon(owner: Any?, args: Array<Any?>) {
        val view = owner as? View ?: return
        val drawable = args.firstOrNull() as? Drawable ?: return
        val itemInfo = ThemedIconPolicy.itemForView(view)
            ?: readField(view, "mInfo")
            ?: return
        val pkg = resolvePackageName(itemInfo) ?: return

        if (!shouldApplyToPackage(pkg) || !ThemedIconPolicy.shouldThemeView(view, itemInfo)) return

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        val original = binding(bubbles, view, itemInfo, drawable)
        // Skip already themed drawables to avoid repeated monochrome conversion.
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            return
        }

        val themed = themedDrawable(context, pkg, drawable, itemInfo, original) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

    }

    private fun beforeBigFolderSetDrawable(owner: Any?, args: Array<Any?>) {
        val original = args.firstOrNull() as? Drawable ?: return
        val themed = themedBigFolderDrawable(owner, original) ?: return
        args[0] = themed
    }

    private fun reconcileBigFolderItem(owner: Any?) {
        val view = owner as? View ?: return
        val original = readField(view, "drawable") as? Drawable ?: return
        if (ThemedIconPolicy.isThemedDrawable(original)) return
        val themed = themedBigFolderDrawable(view, original) ?: return
        if (themed !== original) {
            writeField(view, "drawable", themed)
            view.invalidate()
        }
    }

    private fun themedBigFolderDrawable(owner: Any?, original: Drawable?): Drawable? {
        val view = owner as? View ?: return null
        val source = original ?: return null
        if (ThemedIconPolicy.isCompositeFolderObject(source)) return null
        val info = ThemedIconPolicy.itemForView(view) ?: return null
        if (!ThemedIconPolicy.shouldThemeView(view, info)) return null
        val pkg = resolvePackageName(info) ?: return null
        if (!shouldApplyToPackage(pkg)) return null

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        if (ThemedIconPolicy.isThemedDrawable(source)) {
            ThemedIconPolicy.rememberDrawable(source, info)
            return source
        }
        val originalBinding = binding(folders, view, info, source)
        val key = ThemedIconPolicy.itemKey(info)
        val cached = bigFolderViewCache[view]
        val reused = cached?.themed?.get()
        if (cached != null && cached.original.get() === source &&
            cached.itemKey == key && reused != null) {
            reused.bounds = source.bounds
            return reused
        }

        // A BigFolderItemIcon getter can be called during animations. Prefer
        // the exact themed snapshot already recorded for the item rather than
        // applying the monochrome filter twice or loading the package again.
        val existing = ThemedIconPolicy.themedStateFor(info)
        if (existing != null) {
            val restored = existing.newDrawable(view.resources).mutate()
            restored.bounds = source.bounds
            ThemedIconPolicy.rememberDrawable(restored, info)
            bigFolderViewCache[view] = BigFolderCacheEntry(WeakReference(source), key, WeakReference(restored))
            return restored
        }

        val themed = themedDrawable(context, pkg, source, info, originalBinding) ?: return null
        themed.bounds = source.bounds
        ThemedIconPolicy.rememberDrawable(themed, info)
        bigFolderViewCache[view] = BigFolderCacheEntry(WeakReference(source), key, WeakReference(themed))
        return themed
    }

    private fun afterPreviewSetDrawable(owner: Any?, args: Array<Any?>) {
        if (owner == null) return
        val previewParams = args.firstOrNull() ?: return
        val drawable = readField(previewParams, "drawable") as? Drawable
        if (ThemedIconPolicy.isCompositeFolderObject(previewParams) ||
            ThemedIconPolicy.isCompositeFolderObject(drawable)) {
            // Keep the native StackedDrawable and its layout/animation. Only
            // its leaf params are eligible for monochrome replacement.
            previews.remove(previewParams)
            clearPreviewPending(owner, previewParams)
            val children = invokeNoArg(previewParams, "getCurrentStackedParams") as? Iterable<*>
            children?.forEach { child ->
                if (child != null && child !== previewParams) {
                    val childItem = readField(child, "item")
                    if (childItem != null) afterPreviewSetDrawable(owner, arrayOf(child, childItem))
                }
            }
            return
        }
        if (drawable != null && drawable === readField(owner, "mHolderDrawable")) {
            previews.remove(previewParams)
            clearPreviewPending(owner, previewParams)
            return
        }
        // A reused param can change its item during layout reconstruction.
        // Prefer its actual current binding over an old retry entry.
        val itemInfo = readField(previewParams, "item") ?: args.getOrNull(1) ?: return
        if (!ThemedIconPolicy.shouldThemeItem(itemInfo)) return
        val pkg = resolvePackageName(itemInfo) ?: return
        if (!shouldApplyToPackage(pkg)) return
        val key = ThemedIconPolicy.itemKey(itemInfo)
        val previous = previews[previewParams]?.source
        val original = when {
            drawable == null || ThemedIconPolicy.isKnownThemedDrawable(drawable) ->
                previous?.takeIf { it.key == key }
            previous != null && previous.key == key && previous.original.get() === drawable -> previous
            else -> originalBinding(key, drawable, resolveItemType(itemInfo) != 0)
        }
        // Remember the binding before checking folder ownership or context:
        // both can be populated after setDrawable during the initial model bind.
        previews[previewParams] = PreviewBinding(WeakReference(owner), itemInfo, original)
        val folder = ThemedIconPolicy.folderIconForPreview(owner)
        val context = folder?.context ?: resolveContext(owner)
        if (context != null) prepare(context)
        if (drawable == null || context == null ||
            !ThemedIconPolicy.shouldThemeFolderPreview(owner, itemInfo)) {
            markPreviewPending(owner, previewParams)
            return
        }
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            clearPreviewPending(owner, previewParams)
            return
        }
        val themed = themedDrawable(context, pkg, drawable, itemInfo, original)
        if (themed == null) {
            markPreviewPending(owner, previewParams)
            return
        }
        themed.bounds = drawable.bounds
        // A replaced preview drawable must notify the owning folder, not the
        // discarded original drawable's callback.
        themed.callback = drawable.callback ?: folder
        writeField(previewParams, "drawable", themed)
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        clearPreviewPending(owner, previewParams)
        invalidateFolderPreviewCache(owner, folder)

    }

    private fun invalidateFolderPreviewCache(owner: Any, folder: View?) {
        // invalidate() alone redraws the old RenderNode. The exact method and
        // fields below are present in System Launcher 17.3.12.
        runCatching {
            val clear = CachedReflection.method(owner.javaClass, "clearPreviewDrawables")
            if (clear != null) clear.invoke(owner)
            else (readField(owner, "mPreviewCacheDrawables") as? MutableMap<*, *>)?.clear()
            (readField(owner, "mResizeCacheDrawable") as? MutableMap<*, *>)?.clear()
        }.onFailure { moduleLog("Cannot invalidate folder preview cache", it) }
        folder?.invalidate()
    }

    private fun beforeFloatingSetIcon(owner: Any?, args: Array<Any?>) {
        val view = owner as? View ?: return
        val drawable = args.firstOrNull() as? Drawable ?: return
        val itemInfo = resolveFloatingItemInfo(owner) ?: return
        val pkg = resolvePackageName(itemInfo) ?: return
        val originalView = readField(owner, "mOriginalIcon") as? View
        if (!shouldApplyToPackage(pkg) ||
            !ThemedIconPolicy.shouldThemeSurfaceTarget(originalView, itemInfo)) return

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        val themed = ThemedIconPolicy.themedStateFor(itemInfo)
            ?.newDrawable(context.resources)?.mutate()
            ?: themedDrawable(context, pkg, drawable, itemInfo) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

        // Keep OOS' load result coherent with the drawable passed to ClipIconView.
        val loadResult = readField(owner, "mIconLoadResult")
        if (loadResult != null) {
            runCatching { writeField(loadResult, "drawable", themed) }
            runCatching { writeField(loadResult, "isThemed", true) }
        }

    }

    private fun resolveFloatingItemInfo(owner: Any): Any? {
        val loadResult = readField(owner, "mIconLoadResult")
        if (loadResult != null) {
            readField(loadResult, "itemInfo")?.let { return it }
        }

        val originalIcon = readField(owner, "mOriginalIcon") as? View
        if (originalIcon != null) {
            runCatching { originalIcon.tag }.getOrNull()?.let { return it }
        }

        val btvDrawable = readField(owner, "mBtvDrawable") as? View
        if (btvDrawable != null) {
            runCatching { btvDrawable.tag }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun shouldApplyToPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == TARGET_PACKAGE) return false
        return true
    }

    private fun findField(owner: Any, name: String): Field? =
        CachedReflection.field(owner.javaClass, name)

    private fun readField(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { findField(owner, name)?.get(owner) }.getOrNull()
    }

    private fun writeField(owner: Any, name: String, value: Any?) {
        val field = findField(owner, name) ?: return
        field.set(owner, value)
    }

    private fun findNoArgMethod(owner: Any, name: String): Method? =
        CachedReflection.method(owner.javaClass, name)

    private fun invokeNoArg(owner: Any, name: String): Any? {
        return runCatching { findNoArgMethod(owner, name)?.invoke(owner) }.getOrNull()
    }

    private fun resolveContext(owner: Any?): Context? = CachedReflection.context(owner)

    private fun resolvePackageName(itemInfo: Any): String? {
        (readField(itemInfo, "componentName") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getMTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (readField(itemInfo, "packageName") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetPackage") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        return resolveComponentName(itemInfo)?.packageName
    }

    private fun resolveComponentName(itemInfo: Any): ComponentName? {
        (readField(itemInfo, "componentName") as? ComponentName)?.let { return it }
        (invokeNoArg(itemInfo, "getTargetComponent") as? ComponentName)?.let { return it }
        (invokeNoArg(itemInfo, "getMTargetComponent") as? ComponentName)?.let { return it }
        return ((invokeNoArg(itemInfo, "getIntent") ?: readField(itemInfo, "intent")) as? Intent)?.component
    }

    private fun resolveUserHandle(itemInfo: Any): UserHandle? {
        return readField(itemInfo, "user") as? UserHandle
    }

    private fun loadOriginalIcon(
        context: Context,
        component: ComponentName,
        user: UserHandle
    ): Drawable? {
        return runCatching {
            val pm = context.packageManager
            // APK resources can be available before credential unlock. Query
            // both Direct Boot activity classes explicitly instead of using the
            // default state-dependent filtering.
            val flags = PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()
            val manifest = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(flags))
            val appInfo = if (user == android.os.Process.myUserHandle()) {
                manifest.applicationInfo
            } else {
                val apps = context.getSystemService(LauncherApps::class.java)
                apps?.getActivityList(component.packageName, user)
                    ?.firstOrNull { it.componentName == component }?.applicationInfo
                    ?: return@runCatching null
            }
            val res = pm.getResourcesForApplication(appInfo)
            // Never substitute an OEM-normalized icon or default activity icon.
            res.getDrawableForDensity(
                manifest.iconResource, context.resources.displayMetrics.densityDpi, null
            )
        }.getOrElse { error ->
            moduleLog("raw resource unavailable pkg=${component.packageName}: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun resolveBestSourceDrawable(
        context: Context,
        itemInfo: Any,
        fallback: Drawable
    ): Drawable? {
        val itemType = resolveItemType(itemInfo)
        if (isShortcutLikeItem(itemInfo)) {
            val id = (invokeNoArg(itemInfo, "getDeepShortcutId") as? String)
                ?: (readField(itemInfo, "deepShortcutId") as? String)
            val pkg = resolvePackageName(itemInfo)
            if (!id.isNullOrEmpty() && pkg != null) {
                val raw = runCatching {
                    val apps = context.getSystemService(LauncherApps::class.java)
                    val query = LauncherApps.ShortcutQuery()
                        .setPackage(pkg).setShortcutIds(listOf(id))
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                    val user = resolveUserHandle(itemInfo) ?: android.os.Process.myUserHandle()
                    val info = apps.getShortcuts(query, user)?.firstOrNull()
                    info?.let { apps.getShortcutIconDrawable(it,
                        context.resources.displayMetrics.densityDpi) }
                }.getOrNull()
                return raw
            }
            // A custom legacy shortcut can have its own launcher-owned artwork.
            return if (id.isNullOrEmpty()) fallback else null
        }
        if (itemType != 0) return fallback
        val component = resolveComponentName(itemInfo) ?: return null
        val user = resolveUserHandle(itemInfo) ?: android.os.Process.myUserHandle()
        return loadOriginalIcon(context, component, user)
    }

    private fun resolveItemType(itemInfo: Any): Int? {
        return (readField(itemInfo, "itemType") as? Number)?.toInt()
    }

    private fun isShortcutLikeItem(itemInfo: Any): Boolean {
        val itemType = resolveItemType(itemInfo)
        if (itemType == 1 || itemType == 6) return true

        val deepShortcut = invokeNoArg(itemInfo, "getDeepShortcutId") as? String
        if (!deepShortcut.isNullOrEmpty()) return true

        val shortcutId = readField(itemInfo, "deepShortcutId") as? String
        return !shortcutId.isNullOrEmpty()
    }

    private fun circleMasked(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size * 0.5f
        canvas.drawCircle(r, r, r, paint)
        return output
    }

    private fun themedDrawable(
        context: Context, pkg: String, fallback: Drawable, item: Any,
        originalBinding: Binding? = null
    ): Drawable? {
        // Never turn a composite overflow slot or cached folder RenderNode
        // into a single application icon, regardless of the caller.
        if (ThemedIconPolicy.isCompositeFolderObject(fallback)) return null
        // Ordinary apps use only verified APK resources, which do not require
        // credential unlock. Launcher-owned shortcut artwork is different.
        if (isShortcutLikeItem(item)) {
            val unlocked = runCatching {
                context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
            }.getOrDefault(false)
            if (!unlocked) return null
        }
        val component = resolveComponentName(item)
        val user = resolveUserHandle(item) ?: android.os.Process.myUserHandle()
        val shortcutId = (invokeNoArg(item, "getDeepShortcutId") as? String)
            ?: (readField(item, "deepShortcutId") as? String)
        val key = when {
            resolveItemType(item) == 0 && component != null -> "app|$user|$component"
            !shortcutId.isNullOrEmpty() -> "shortcut|$user|$pkg|$shortcutId"
            else -> {
                val version = originalBinding?.version ?: this.originalBinding("", fallback).version
                "${ThemedIconPolicy.itemKey(item)}|$version"
            }
        }
        val bitmap = iconCache.getOrLoad(key) {
            val diskKey = if (!isShortcutLikeItem(item) && component != null &&
                user == android.os.Process.myUserHandle()) {
                monoCache.key(context, component, user)
            } else null
            if (diskKey != null) {
                monoCache.read(context, diskKey)?.let { entry ->
                    try {
                        moduleLog("theme source pkg=$pkg: ${entry.source} cache=disk component=$component")
                        return@getOrLoad colorizeMono(context, entry.alpha)
                    } finally {
                        entry.alpha.recycle()
                    }
                }
            }
            val unthemed = if (ThemedIconPolicy.isKnownThemedDrawable(fallback)) {
                originalBinding?.restore(context) ?: fallback
            } else fallback
            val source = resolveBestSourceDrawable(context, item, unthemed)
            if (source == null || ThemedIconPolicy.isKnownThemedDrawable(source)) {
                environment?.requestSourceRetry()
                return@getOrLoad null
            }
            generateRawThemedBitmap(context, pkg, source,
                isShortcutLikeItem(item), component, diskKey)
        } ?: return null
        return bitmap.toDrawable(context.resources)
    }

    private fun isNightMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun moduleLog(msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(Log.INFO, null, "[$TAG] $msg")
        } else {
            log(Log.ERROR, null, "[$TAG] $msg", throwable)
        }
    }

    private fun toSoftwareBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    private fun extractSoftwareBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            return toSoftwareBitmap(drawable.bitmap)
        }
        val reflected = CachedReflection.call(drawable, "getBitmap") as? Bitmap
        return toSoftwareBitmap(reflected)
    }

    private fun generateRawThemedBitmap(
        context: Context,
        pkg: String,
        original: Drawable,
        isShortcut: Boolean,
        component: ComponentName?,
        diskKey: String?
    ): Bitmap? {
        val hasNativeMono = (original as? AdaptiveIconDrawable)?.monochrome != null
        // Keep the established home-screen artwork for packages with a known
        // compatibility glyph. A live OEM asset may be a different design,
        // not just a higher-resolution version of that glyph.
        if (!isShortcut && !hasNativeMono && !Oos15MonochromeAssets.isReady(context)) {
            environment?.requestSourceRetry()
            return null
        }
        val legacy = if (!isShortcut && !hasNativeMono) {
            Oos15MonochromeAssets.load(context, pkg, component)
        } else null
        val pixel = if (!isShortcut && !hasNativeMono && legacy == null) {
            PixelLauncherThemedAssets.load(pkg)
        } else null
        val oplus = if (!isShortcut && !hasNativeMono && legacy == null && pixel == null &&
            component != null) {
            oplusSource.load(context, component)
        } else OplusMonochromeSource.Result(null, false)
        if (oplus.retry) {
            environment?.requestSourceRetry()
            return null
        }
        val sourceName = when {
            hasNativeMono -> "apk-native"
            oplus.drawable != null -> "oplus-live"
            legacy != null -> "oos15"
            pixel != null -> "pixel"
            else -> "generated"
        }
        val alpha = runCatching {
            when {
                oplus.drawable != null -> rasterizeGlyph(context, oplus.drawable, 1f)
                legacy != null -> rasterizeGlyph(context, BitmapDrawable(context.resources, legacy),
                    Oos15MonochromeAssets.GLYPH_SCALE)
                pixel != null -> rasterizeGlyph(context, BitmapDrawable(context.resources, pixel),
                    PixelLauncherThemedAssets.GLYPH_SCALE)
                else -> runCatching {
                    Pixel17MonochromeRenderer.render(original, PersistentMonoCache.SIZE, context.resources,
                        matchThemeBackground = true, requireNative = hasNativeMono)
                }.recoverCatching {
                    if (hasNativeMono) throw it
                    val software = extractSoftwareBitmap(original) ?: throw it
                    Pixel17MonochromeRenderer.render(BitmapDrawable(context.resources, software),
                        PersistentMonoCache.SIZE, context.resources, matchThemeBackground = true)
                }.getOrThrow()
            }
        }.onFailure {
            moduleLog("skip mono render pkg=$pkg: ${it.message}")
            environment?.requestSourceRetry()
        }.getOrNull() ?: return null
        try {
            val result = colorizeMono(context, alpha)
            // Only persist if the sources still match the key captured before rendering.
            if (diskKey != null && component != null &&
                diskKey == monoCache.key(context, component, android.os.Process.myUserHandle())) {
                monoCache.write(context, diskKey, alpha, sourceName)
            }
            moduleLog("theme source pkg=$pkg: $sourceName cache=miss component=$component raw=${original.javaClass.name} shortcut=$isShortcut")
            return result
        } finally {
            alpha.recycle()
        }
    }

    private fun rasterizeGlyph(context: Context, source: Drawable, scale: Float): Bitmap {
        val glyph = source.constantState?.newDrawable(context.resources)?.mutate() ?: source
        val old = Rect(glyph.bounds)
        val size = PersistentMonoCache.SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        try {
            val side = (size * scale).roundToInt()
            val offset = (size - side) / 2
            glyph.setBounds(offset, offset, offset + side, offset + side)
            glyph.draw(Canvas(bitmap))
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        } finally {
            glyph.bounds = old
        }
    }

    private fun colorizeMono(context: Context, alpha: Bitmap): Bitmap {
        val dark = isNightMode(context)
        val bgColor = context.getColor(if (dark) android.R.color.system_neutral1_800
            else android.R.color.system_accent1_100)
        val iconColor = context.getColor(if (dark) android.R.color.system_accent1_100
            else android.R.color.system_accent1_600)
        val size = PersistentMonoCache.SIZE
        val bitmap = createBitmap(size, size)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(bgColor)
            // ALPHA_8 uses Paint.color as the foreground; no luminance conversion.
            canvas.drawBitmap(alpha, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = iconColor
            })
            return circleMasked(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

}
