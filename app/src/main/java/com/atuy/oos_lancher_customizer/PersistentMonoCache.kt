package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Palette-independent ALPHA_8 cache in the launcher's device-protected storage.
 * No writes to launcher SQLite or shared BitmapInfo: stock icon consumers stay unchanged.
 */
internal class PersistentMonoCache {
    companion object {
        const val SIZE = 192
        private const val SCHEMA = "mono-v9-hybrid-stock"
        private val SOURCES = listOf("apk-native", "oplus-live", "oos15", "pixel", "generated")
        private const val MAX_FILES = 384
        private val ROOTS = listOf("/data/oplus/uxicons",
            "/my_product/media/theme/uxicons", "/my_stock/media/theme/uxicons",
            "/my_region/media/theme/uxicons", "/my_carrier/media/theme/uxicons")
        private val DENSITIES = listOf("", "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        private val writer = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue(32), { r -> Thread(r, "OOS-MonoCache").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }

        internal fun pack(bitmap: Bitmap): ByteArray {
            require(bitmap.config == Bitmap.Config.ALPHA_8 && bitmap.width == SIZE && bitmap.height == SIZE)
            val stride = bitmap.rowBytes
            val padded = ByteArray(stride * SIZE)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(padded))
            return ByteArray(SIZE * SIZE).also { packed ->
                for (y in 0 until SIZE) padded.copyInto(packed, y * SIZE, y * stride, y * stride + SIZE)
            }
        }

        internal fun unpack(bytes: ByteArray): Bitmap? {
            if (bytes.size != SIZE * SIZE) return null
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
            val stride = bitmap.rowBytes
            val padded = ByteArray(stride * SIZE)
            for (y in 0 until SIZE) bytes.copyInto(padded, y * stride, y * SIZE, (y + 1) * SIZE)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
            return bitmap
        }
    }

    private fun directory(context: Context) =
        File(context.createDeviceProtectedStorageContext().cacheDir, "oos-customizer-mono-v1")

    fun key(context: Context, component: ComponentName, user: UserHandle): String? = runCatching {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true &&
            !File("/data/oplus/uxicons").canRead()) return null
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong())
        val app = pm.getPackageInfo(component.packageName, flags)
        val launcher = pm.getPackageInfo("com.android.launcher", flags)
        val module = pm.getPackageInfo("com.atuy.oos_lancher_customizer", flags)
        val signature = buildString {
            append(SCHEMA).append('|').append(Build.FINGERPRINT)
            append('|').append(component.flattenToString()).append('|').append(user)
            append('|').append(app.longVersionCode).append('|').append(app.lastUpdateTime)
            append('|').append(launcher.longVersionCode).append('|').append(launcher.lastUpdateTime)
            append('|').append(module.longVersionCode).append('|').append(module.lastUpdateTime)
            append('|').append(context.resources.displayMetrics.densityDpi)
            val config = context.resources.configuration
            append('|').append(config.locales.toLanguageTags())
            append('|').append(config.uiMode).append('|').append(config.orientation)
            append('|').append(config.screenLayout).append('|').append(config.colorMode)
            append('|').append(config.screenWidthDp).append('|').append(config.screenHeightDp)
            append('|').append(config.mcc).append('|').append(config.mnc)
            for (root in ROOTS) for (density in DENSITIES) {
                val dir = File(if (density.isEmpty()) root else "$root/$density", component.packageName)
                // Include every mono variant, including Activity-prefixed files.
                append('|').append(dir.path).append(':').append(dir.lastModified())
                dir.listFiles()?.filter { it.name.contains("monochrome") && it.name.endsWith(".png") }
                    ?.sortedBy { it.name }?.forEach {
                        append('|').append(it.name).append(':').append(it.length()).append(':').append(it.lastModified())
                    }
            }
        }
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    data class Entry(val alpha: Bitmap, val source: String)

    fun read(context: Context, key: String): Entry? = runCatching {
        val file = File(directory(context), "$key.alpha")
        if (!file.isFile || file.length() != (SIZE * SIZE + 1).toLong()) return null
        val bytes = AtomicFile(file).readFully()
        if (bytes.size != SIZE * SIZE + 1) return null
        val source = SOURCES.getOrNull(bytes[0].toInt()) ?: return null
        val alpha = unpack(bytes.copyOfRange(1, bytes.size)) ?: return null
        Entry(alpha, source)
    }.getOrNull()

    fun write(context: Context, key: String, bitmap: Bitmap, source: String) {
        val dir = runCatching { directory(context) }.getOrNull() ?: return
        val sourceId = SOURCES.indexOf(source)
        if (sourceId < 0) return
        val bytes = runCatching { byteArrayOf(sourceId.toByte()) + pack(bitmap) }.getOrNull() ?: return
        writer.execute {
            runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
                val file = AtomicFile(File(dir, "$key.alpha"))
                val output = file.startWrite()
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
                val files = dir.listFiles { f -> f.name.endsWith(".alpha") }.orEmpty()
                if (files.size > MAX_FILES) {
                    files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
                }
            }
        }
    }
}
