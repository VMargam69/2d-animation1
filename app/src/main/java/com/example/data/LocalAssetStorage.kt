package com.example.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.engine.SpriteAtlasEngine
import com.example.model.AnimationScene
import com.example.model.AtlasPartSlot
import com.example.model.CharacterRigDefinition
import com.example.model.CustomRigMode
import com.example.model.MediaAssetType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalAssetStorage(private val context: Context) {

    private val assetsDir: File by lazy {
        File(context.filesDir, "offline_media_assets").apply { mkdirs() }
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val atlasSlicesCache = LruCache<String, Map<AtlasPartSlot, Bitmap>>(6)

    private val rigMetadataMoshi: Moshi by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLoads = ConcurrentHashMap.newKeySet<String>()

    private val _bitmapVersion = MutableStateFlow(0)
    /** Increments whenever an async background bitmap decode finishes so Compose Canvas redraws without blocking the UI thread. */
    val bitmapVersion: StateFlow<Int> = _bitmapVersion.asStateFlow()

    data class ImportedAssetResult(
        val filePath: String,
        val displayName: String,
        val mimeType: String,
        val mediaType: MediaAssetType
    )

    data class ExportedAtlasResult(
        val internalPath: String,
        val publicDisplayPath: String,
        val resolutionPx: Int
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
        val exactKey = "file:$path"
        val keyPrefix = "file:$path:"
        bitmapCache.snapshot().keys.forEach { key ->
            if (key == exactKey || key.startsWith(keyPrefix)) {
                bitmapCache.remove(key)
            }
        }
        atlasSlicesCache.remove(path)
        runCatching {
            val file = File(path)
            if (file.exists() && file.parentFile?.absolutePath == assetsDir.absolutePath) {
                file.delete()
            }
        }
    }

    /**
     * Non-blocking bitmap lookup for the UI draw thread.
     * Returns immediately if already decoded in [bitmapCache]; otherwise schedules a deduplicated
     * decode on [Dispatchers.IO] and bumps [bitmapVersion] once ready so the Canvas invalidates smoothly.
     */
    fun getCachedOrRequestBitmap(
        path: String?,
        bundledKey: String?,
        maxDimension: Int = 1280
    ): Bitmap? {
        if (!path.isNullOrBlank()) {
            val cacheKey = "file:$path:$maxDimension"
            bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
            if (inFlightLoads.add(cacheKey)) {
                ioScope.launch {
                    try {
                        val decoded = loadBitmapFromFile(path, maxDimension)
                        if (decoded != null) {
                            _bitmapVersion.value = _bitmapVersion.value + 1
                        }
                    } finally {
                        inFlightLoads.remove(cacheKey)
                    }
                }
            }
            return null
        }
        if (!bundledKey.isNullOrBlank()) {
            val resId = BuiltInCatalog.bundledBackgroundDrawableRes(bundledKey) ?: return null
            val cacheKey = "res:$resId"
            bitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
            if (inFlightLoads.add(cacheKey)) {
                ioScope.launch {
                    try {
                        val decoded = loadBundledBackgroundBitmap(bundledKey)
                        if (decoded != null) {
                            _bitmapVersion.value = _bitmapVersion.value + 1
                        }
                    } finally {
                        inFlightLoads.remove(cacheKey)
                    }
                }
            }
            return null
        }
        return null
    }

    /** Proactively decodes all bitmaps referenced by [scene] on Dispatchers.IO so playback & scrubbing never stall. */
    fun prefetchSceneAssets(
        scene: AnimationScene,
        charactersById: Map<String, CharacterRigDefinition>
    ) {
        ioScope.launch {
            var loadedAny = false
            if (!scene.backgroundImagePath.isNullOrBlank()) {
                if (loadBitmapFromFile(scene.backgroundImagePath) != null) loadedAny = true
            } else if (!scene.backgroundBundledKey.isNullOrBlank()) {
                if (loadBundledBackgroundBitmap(scene.backgroundBundledKey) != null) loadedAny = true
            }
            for (clip in scene.clips) {
                if (!clip.imagePath.isNullOrBlank()) {
                    if (loadBitmapFromFile(clip.imagePath) != null) loadedAny = true
                }
                if (!clip.bundledBgKey.isNullOrBlank()) {
                    if (loadBundledBackgroundBitmap(clip.bundledBgKey) != null) loadedAny = true
                }
                if (!clip.customPropImagePath.isNullOrBlank()) {
                    if (loadBitmapFromFile(clip.customPropImagePath) != null) loadedAny = true
                }
                val rig = clip.characterId?.let { charactersById[it] }
                val rigPath = rig?.customImagePath
                if (!rigPath.isNullOrBlank()) {
                    if (rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO) {
                        if (loadOrSliceAtlasBlocking(rigPath) != null) loadedAny = true
                    } else if (loadBitmapFromFile(rigPath) != null) {
                        loadedAny = true
                    }
                }
            }
            if (loadedAny) {
                _bitmapVersion.value = _bitmapVersion.value + 1
            }
        }
    }

    /**
     * Non-blocking O(1) lookup for sliced sprite-atlas parts on the UI/Canvas thread.
     * Schedules async decode + normalized (0..1) slicing on Dispatchers.IO if not yet cached.
     */
    fun getCachedOrSliceAtlas(path: String?): Map<AtlasPartSlot, Bitmap>? {
        if (path.isNullOrBlank()) return null
        atlasSlicesCache.get(path)?.let { return it }
        val key = "atlas:$path"
        if (inFlightLoads.add(key)) {
            ioScope.launch {
                try {
                    val slices = loadOrSliceAtlasBlocking(path)
                    if (slices != null) {
                        _bitmapVersion.value = _bitmapVersion.value + 1
                    }
                } finally {
                    inFlightLoads.remove(key)
                }
            }
        }
        return null
    }

    /** Blocking decode + normalized slicing for Dispatchers.IO prefetch and background export threads. */
    fun loadOrSliceAtlasBlocking(path: String?): Map<AtlasPartSlot, Bitmap>? {
        if (path.isNullOrBlank()) return null
        atlasSlicesCache.get(path)?.let { return it }
        val fullBmp = loadBitmapFromFile(path, maxDimension = 2048) ?: return null
        val slices = SpriteAtlasEngine.sliceAtlasIntoParts(fullBmp)
        atlasSlicesCache.put(path, slices)
        return slices
    }

    fun invalidateAtlasSlices(path: String?) {
        if (path.isNullOrBlank()) return
        atlasSlicesCache.remove(path)
    }

    suspend fun inspectImageDimensions(path: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            opts.outWidth to opts.outHeight
        }.getOrDefault(0 to 0)
    }

    /**
     * Saves a clean transparent ARGB_8888 PNG sprite atlas offline, updates the slice cache,
     * and optionally exports a copy into Pictures/FrameCraft via MediaStore (zero broad permissions).
     */
    suspend fun exportTransparentAtlasPng(
        bitmap: Bitmap,
        textureName: String,
        saveToGallery: Boolean = true
    ): ExportedAtlasResult? = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = textureName.trim()
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .ifBlank { "CustomHero" }
            val fileName = "${safeName}_${bitmap.width}x${bitmap.height}_${System.currentTimeMillis() % 10000}.png"
            val internalFile = File(assetsDir, fileName)
            FileOutputStream(internalFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // Pre-populate slice cache immediately so preview/stage is instant
            val slices = SpriteAtlasEngine.sliceAtlasIntoParts(bitmap)
            atlasSlicesCache.put(internalFile.absolutePath, slices)
            _bitmapVersion.value = _bitmapVersion.value + 1

            var publicLabel = internalFile.name
            if (saveToGallery) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FrameCraft")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        publicLabel = "Pictures/FrameCraft/$fileName"
                    }
                } else {
                    val extDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "FrameCraft").apply { mkdirs() }
                    val extFile = File(extDir, fileName)
                    FileOutputStream(extFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    publicLabel = extFile.absolutePath
                }
            }

            ExportedAtlasResult(
                internalPath = internalFile.absolutePath,
                publicDisplayPath = publicLabel,
                resolutionPx = bitmap.width
            )
        }.getOrNull()
    }

    /** Saves a local JSON rig metadata sidecar file alongside the exported PNG sprite atlas. */
    suspend fun saveAtlasRigMetadataJson(character: CharacterRigDefinition): String? = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = character.name.trim()
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .ifBlank { "CustomHero" }
            val jsonFile = File(assetsDir, "${safeName}_${character.id.take(8)}.rig.json")
            val adapter = rigMetadataMoshi.adapter(CharacterRigDefinition::class.java).indent("  ")
            jsonFile.writeText(adapter.toJson(character))
            jsonFile.absolutePath
        }.getOrNull()
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
            _bitmapVersion.value = _bitmapVersion.value + 1
            outFile.absolutePath
        }.getOrNull()
    }

    fun shutdown() {
        ioScope.cancel()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
