package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.model.MediaAssetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LocalAssetStorage(private val context: Context) {

    private val assetsDir: File by lazy {
        File(context.filesDir, "offline_media_assets").apply { mkdirs() }
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    data class ImportedAssetResult(
        val filePath: String,
        val displayName: String,
        val mimeType: String,
        val mediaType: MediaAssetType
    )

    suspend fun importUriToOfflineStorage(uri: Uri, preferredType: MediaAssetType? = null): ImportedAssetResult? =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val rawName = queryDisplayName(uri) ?: "asset_${System.currentTimeMillis()}"
                val cleanBase = rawName.substringBeforeLast('.').ifBlank { "Asset" }
                val ext = rawName.substringAfterLast('.', "").ifBlank {
                    when {
                        mimeType.contains("png") -> "png"
                        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                        mimeType.contains("webp") -> "webp"
                        mimeType.contains("gif") -> "gif"
                        mimeType.contains("svg") -> "svg"
                        mimeType.contains("video") -> "mp4"
                        mimeType.contains("audio") -> "mp3"
                        else -> "bin"
                    }
                }
                val fileName = "${UUID.randomUUID()}.${ext.lowercase()}"
                val outFile = File(assetsDir, fileName)
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext null

                val inferredType = preferredType ?: when {
                    mimeType.contains("gif") || ext.equals("gif", true) -> MediaAssetType.GIF
                    mimeType.contains("svg") || ext.equals("svg", true) -> MediaAssetType.SVG
                    mimeType.startsWith("video/") -> MediaAssetType.VIDEO
                    mimeType.startsWith("audio/") -> MediaAssetType.AUDIO
                    else -> MediaAssetType.IMAGE
                }

                ImportedAssetResult(
                    filePath = outFile.absolutePath,
                    displayName = cleanBase,
                    mimeType = mimeType,
                    mediaType = inferredType
                )
            } catch (_: Exception) {
                null
            }
        }

    fun deleteOfflineFile(path: String?) {
        if (path.isNullOrBlank()) return
        bitmapCache.remove("file:$path")
        runCatching {
            val file = File(path)
            if (file.exists() && file.parentFile?.absolutePath == assetsDir.absolutePath) {
                file.delete()
            }
        }
    }

    fun loadBitmapFromFile(path: String?, maxDimension: Int = 1280): Bitmap? {
        if (path.isNullOrBlank()) return null
        val cacheKey = "file:$path:$maxDimension"
        bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)?.also { bmp ->
                bitmapCache.put(cacheKey, bmp)
            }
        }.getOrNull()
    }

    fun loadBundledBackgroundBitmap(key: String?): Bitmap? {
        val resId = BuiltInCatalog.bundledBackgroundDrawableRes(key) ?: return null
        val cacheKey = "res:$resId"
        bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        return runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeResource(context.resources, resId, opts)?.also { bmp ->
                bitmapCache.put(cacheKey, bmp)
            }
        }.getOrNull()
    }

    suspend fun cleanPngBackground(sourcePath: String, tolerance: Int): String? = withContext(Dispatchers.IO) {
        val bmp = loadBitmapFromFile(sourcePath, maxDimension = 1024) ?: return@withContext null
        val outFile = File(assetsDir, "cutout_${UUID.randomUUID()}.png")
        com.example.engine.PngSkeletonProcessor.removeSolidBackgroundToPng(bmp, outFile, tolerance)
    }

    suspend fun saveBlank2048ModularChartTemplate(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = com.example.engine.PropAndModularChartRenderer.generateBlank2048ChartTemplate(2048)
            val outFile = File(assetsDir, "FrameCraft_2048x2048_Modular_Chart_Template.png")
            FileOutputStream(outFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmapCache.put("file:${outFile.absolutePath}:1280", bmp)
            outFile.absolutePath
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
