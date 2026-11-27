package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import com.example.engine.FacialExpressionEngine
import com.example.engine.ProceduralRigRenderer
import com.example.engine.StudioCapabilityRegistry
import com.example.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 100% offline local JSON storage for the IK Pose Library (`filesDir/pose_library.json`)
 * plus lightweight cached pose silhouette thumbnails (`filesDir/pose_thumbnails/`).
 *
 * Keeps FrameCraft's local JSON persistence architecture intact with zero Room DB additions,
 * atomic temp-file writes to survive app restarts cleanly, and automatic capability syncing
 * into [StudioCapabilityRegistry] so exported `.fcblueprint.json` files include saved poses.
 */
class PoseLibraryStorage(
    private val storageFile: File,
    private val thumbnailsDir: File
) {
    constructor(context: Context) : this(
        storageFile = File(context.filesDir, POSE_LIBRARY_FILENAME),
        thumbnailsDir = File(context.filesDir, POSE_THUMBNAILS_DIR)
    )

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val docAdapter = moshi.adapter(PoseLibraryDocument::class.java).indent("  ")

    private val thumbnailCache: LruCache<String, Bitmap> by lazy {
        object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                runCatching { value.allocationByteCount }.getOrElse { value.byteCount }.coerceAtLeast(1024)
        }
    }

    /**
     * Loads all saved poses from `filesDir/pose_library.json` and syncs [StudioCapabilityRegistry].
     */
    suspend fun loadPoses(): List<SavedPose> = withContext(Dispatchers.IO) {
        loadPosesBlocking()
    }

    fun loadPosesBlocking(): List<SavedPose> {
        val poses = runCatching {
            if (!storageFile.exists() || storageFile.length() == 0L) {
                emptyList()
            } else {
                val raw = storageFile.readText(Charsets.UTF_8)
                deserializeFromJson(raw)
            }
        }.getOrDefault(emptyList())
        StudioCapabilityRegistry.registerSavedPoses(poses)
        return poses
    }

    /**
     * Saves a new or updated [SavedPose], optionally rendering a lightweight offline thumbnail
     * from [rig] if supplied, atomically persists `pose_library.json`, and updates [StudioCapabilityRegistry].
     */
    suspend fun savePose(
        pose: SavedPose,
        rig: CharacterRigDefinition? = null,
        orientation: CharacterOrientation = CharacterOrientation.FRONT
    ): List<SavedPose> = withContext(Dispatchers.IO) {
        val thumbPath = if (rig != null) {
            renderAndSavePoseThumbnail(pose, rig, orientation) ?: pose.thumbnailPath
        } else {
            pose.thumbnailPath
        }
        val finalizedPose = pose.copy(
            name = pose.name.trim().ifBlank { "Custom Pose" }.take(36),
            thumbnailPath = thumbPath
        )
        val existing = loadPosesBlocking().filterNot { it.id == finalizedPose.id }
        val updated = listOf(finalizedPose) + existing
        writePosesBlocking(updated)
        updated
    }

    /**
     * Renames an existing saved pose (and optionally updates its category) on disk.
     */
    suspend fun renamePose(
        poseId: String,
        newName: String,
        newCategory: PoseCategory? = null
    ): List<SavedPose> = withContext(Dispatchers.IO) {
        val cleanName = newName.trim().ifBlank { return@withContext loadPosesBlocking() }.take(36)
        val updated = loadPosesBlocking().map { p ->
            if (p.id == poseId) {
                p.copy(name = cleanName, category = newCategory ?: p.category)
            } else p
        }
        writePosesBlocking(updated)
        updated
    }

    /**
     * Deletes a saved pose from `pose_library.json` and cleans up its cached thumbnail PNG.
     */
    suspend fun deletePose(poseId: String): List<SavedPose> = withContext(Dispatchers.IO) {
        val current = loadPosesBlocking()
        val removed = current.firstOrNull { it.id == poseId }
        val updated = current.filterNot { it.id == poseId }
        writePosesBlocking(updated)
        removed?.thumbnailPath?.let { path ->
            runCatching { thumbnailCache.remove(path) }
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
        updated
    }

    fun writePosesBlocking(poses: List<SavedPose>) {
        storageFile.parentFile?.mkdirs()
        val json = serializeToJson(poses)
        val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
        tmpFile.writeText(json, Charsets.UTF_8)
        if (!tmpFile.renameTo(storageFile)) {
            storageFile.writeText(json, Charsets.UTF_8)
            tmpFile.delete()
        }
        StudioCapabilityRegistry.registerSavedPoses(poses)
    }

    fun serializeToJson(poses: List<SavedPose>): String =
        docAdapter.toJson(PoseLibraryDocument(schemaVersion = 1, poses = poses))

    fun deserializeFromJson(rawJson: String): List<SavedPose> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            docAdapter.fromJson(rawJson)?.poses.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Loads a cached pose thumbnail bitmap from memory or disk.
     */
    fun loadCachedThumbnail(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        runCatching { thumbnailCache.get(path) }.getOrNull()?.let {
            if (!it.isRecycled) return it
        }
        val file = File(path)
        if (!file.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        runCatching { thumbnailCache.put(path, bmp) }
        return bmp
    }

    /**
     * Renders a crisp, lightweight 160x160 pose preview thumbnail offline from the posed rig
     * using [ProceduralRigRenderer] and writes it to `filesDir/pose_thumbnails/<id>.png`.
     */
    fun renderAndSavePoseThumbnail(
        pose: SavedPose,
        rig: CharacterRigDefinition,
        orientation: CharacterOrientation = CharacterOrientation.FRONT
    ): String? {
        return runCatching {
            thumbnailsDir.mkdirs()
            val size = THUMBNAIL_SIZE_PX
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Dark studio radial backdrop
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size * 0.5f,
                    size * 0.45f,
                    size * 0.65f,
                    Color.parseColor("#1D2A3F"),
                    Color.parseColor("#0D131E"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

            // Subtle ground reflection line
            val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3338BDF8")
                strokeWidth = 2f
            }
            canvas.drawLine(size * 0.16f, size * 0.87f, size * 0.84f, size * 0.87f, floorPaint)

            val combinedOffsets = pose.ikJointOffsets.copy(
                headNodOffset = (pose.ikJointOffsets.headNodOffset + pose.headTilt).coerceIn(-65f, 65f)
            )
            val baseFace = FacialExpressionEngine.evaluateOrganicFace(
                activePresetId = null,
                previousPresetId = null,
                fallbackExpression = pose.expression,
                framesSinceKey = 24,
                globalFrame = 0,
                fps = 24
            )
            val gazedFace = baseFace.copy(
                eyeLookX = pose.gazeTarget.x.coerceIn(-1f, 1f),
                eyeLookY = pose.gazeTarget.y.coerceIn(-1f, 1f)
            )

            canvas.save()
            canvas.translate(size * 0.5f, size * 0.56f)
            ProceduralRigRenderer.drawCharacter(
                canvas = canvas,
                rig = rig.copy(isCustom = false, customImagePath = null, spriteAtlasRig = null, skeletonRig = null),
                orientation = orientation,
                preset = AnimationPresetType.IDLE,
                phase = 0f,
                rigScale = size / 240f,
                alpha = 1f,
                poseOffsets = combinedOffsets,
                facialExpression = pose.expression,
                handPose = pose.handPose,
                organicFace = gazedFace
            )
            canvas.restore()

            val safeId = pose.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(42)
            val outFile = File(thumbnailsDir, "$safeId.png")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            runCatching { thumbnailCache.put(outFile.absolutePath, bmp) }
            outFile.absolutePath
        }.getOrNull()
    }

    companion object {
        const val POSE_LIBRARY_FILENAME = "pose_library.json"
        const val POSE_THUMBNAILS_DIR = "pose_thumbnails"
        const val THUMBNAIL_SIZE_PX = 160
    }
}
