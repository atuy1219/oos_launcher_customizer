package com.atuy.oos_lancher_customizer

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.provider.Settings
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import java.io.File

/** One observer set for the launcher process; bursts share one UI refresh. */
internal class IconEnvironment(
    private val context: Context,
    private val invalidate: () -> Unit,
    private val refresh: () -> Unit,
    private val trim: () -> Unit,
    private val retry: () -> Unit = refresh,
    private val onScaleChanged: () -> Boolean = { false }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private val retryDelays = longArrayOf(0L, 100L, 500L, 2000L)
    private var retryAttempts = 0
    private var retryScheduled = false
    private val uxRoot = File("/data/oplus/uxicons")
    private var uxObserver: FileObserver? = null
    private val uxChanged = Runnable {
        watchUxIcons()
        changed(true)
    }

    private fun watchUxIcons() {
        uxObserver?.stopWatching()
        uxObserver = null
        if (!uxRoot.isDirectory || !uxRoot.canRead()) return
        runCatching {
            val paths = listOf(uxRoot) + uxRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            val mask = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
                FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
            uxObserver = object : FileObserver(paths, mask) {
                override fun onEvent(event: Int, path: String?) {
                    // Coalesce multi-file downloads, and re-arm for new package dirs.
                    if (path != null && path.contains('.') && path.endsWith(".png") &&
                        !path.contains("monochrome")) return
                    handler.removeCallbacks(uxChanged)
                    handler.postDelayed(uxChanged, 250L)
                }
            }.also { it.startWatching() }
        }.onFailure { ModuleDiagnostics.w("OOS_IconEnvironment", "UXIcon observer unavailable", it) }
    }
    private val retryTask = Runnable {
        retryScheduled = false
        retry()
    }

    fun requestSourceRetry() {
        handler.post {
            if (!retryScheduled && retryAttempts < retryDelays.size) {
                retryScheduled = true
                handler.postDelayed(retryTask, retryDelays[retryAttempts++])
            }
        }
    }
    private data class Appearance(val config: Configuration, val background: Int, val foreground: Int)
    private fun appearance(): Appearance {
        val config = context.resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return Appearance(Configuration(config),
            context.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100),
            context.getColor(if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent1_600))
    }
    private var current = appearance()

    private fun resetSourceRetries() {
        handler.removeCallbacks(retryTask)
        retryScheduled = false
        retryAttempts = 0
    }

    private fun onUserUnlocked() {
        val scaleChanged = onScaleChanged()
        val hadObserver = uxObserver != null
        watchUxIcons()
        if (scaleChanged || (!hadObserver && uxObserver != null)) {
            // The source directory has become visible for the first time.
            changed(true)
            return
        }
        // Do not discard successful APK-based renders just because CE storage
        // became available. If the palette changed, perform the normal refresh.
        if (appearance() != current) {
            changed()
        } else {
            resetSourceRetries()
            retry()
        }
    }

    private fun changed(force: Boolean = false) {
        val next = appearance()
        if (!force && next == current) return
        current = next
        resetSourceRetries()
        invalidate()
        if (!scheduled) {
            scheduled = true
            handler.post { scheduled = false; refresh() }
        }
    }

    private val configuration = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) { changed() }
        override fun onLowMemory() { trim() }
        override fun onTrimMemory(level: Int) {
            // UI_HIDDEN is a routine transition; trim only on actual background pressure.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trim()
        }
    }
    private val wallpaper = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed() }
    }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
        }
    }
    private val packages = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackageAdded(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackageChanged(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changed(true) }
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changed(true) }
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) { changed(true) }
    }

    private val scaleObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (onScaleChanged()) changed(true)
        }
    }

    init {
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(OplusMonoScale.KEY), false, scaleObserver)
        }.onFailure { ModuleDiagnostics.w("OOS_IconEnvironment", "Scale observer unavailable", it) }
        watchUxIcons()
        context.registerComponentCallbacks(configuration)
        runCatching {
            context.registerReceiver(wallpaper, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { ModuleDiagnostics.w("OOS_IconEnvironment", "Wallpaper observer unavailable", it) }
        runCatching {
            context.registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { ModuleDiagnostics.w("OOS_IconEnvironment", "Unlock observer unavailable", it) }
        runCatching {
            context.getSystemService(LauncherApps::class.java)?.registerCallback(packages, handler)
        }.onFailure { ModuleDiagnostics.w("OOS_IconEnvironment", "Package observer unavailable", it) }
    }
}
