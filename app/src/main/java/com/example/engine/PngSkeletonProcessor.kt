package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.SkeletonRigConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * On-device offline processor for converting PNG/image cutouts into articulated
 * skeletal characters: corner-sampled background transparency cleanup & automatic
 * 12-joint humanoid skeleton placement based on alpha silhouette bounds.
 */
object PngSkeletonProcessor {

    /**
     * Scans the opaque bounding box of [bitmap] and automatically fits a realistic
     * 12-joint humanoid skeleton proportional to the character silhouette.
     */
    fun autoDetectHumanoidSkeleton(bitmap: Bitmap): SkeletonRigConfig {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val step = max(1, minOf(w, h) / 160)

        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0
        var foundOpaque = false

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (alpha > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    foundOpaque = true
                }
                x += step
            }
            y += step
        }

        if (!foundOpaque || maxX <= minX || maxY <= minY) {
            return SkeletonRigConfig(enabled = true)
        }

        val left = minX.toFloat() / w
        val right = maxX.toFloat() / w
        val top = minY.toFloat() / h
        val bottom = maxY.toFloat() / h
        val bw = (right - left).coerceAtLeast(0.15f)
        val bh = (bottom - top).coerceAtLeast(0.25f)
        val cx = (left + right) * 0.5f

        return SkeletonRigConfig(
            enabled = true,
            headX = cx,
            headY = (top + bh * 0.11f).coerceIn(0.05f, 0.95f),
            neckX = cx,
            neckY = (top + bh * 0.22f).coerceIn(0.08f, 0.95f),
            torsoX = cx,
            torsoY = (top + bh * 0.38f).coerceIn(0.12f, 0.95f),
            pelvisX = cx,
            pelvisY = (top + bh * 0.54f).coerceIn(0.20f, 0.95f),
            leftShoulderX = (cx - bw * 0.24f).coerceIn(0.05f, 0.95f),
            leftShoulderY = (top + bh * 0.26f).coerceIn(0.08f, 0.95f),
            leftHandX = (cx - bw * 0.42f).coerceIn(0.04f, 0.96f),
            leftHandY = (top + bh * 0.52f).coerceIn(0.12f, 0.96f),
            rightShoulderX = (cx + bw * 0.24f).coerceIn(0.05f, 0.95f),
            rightShoulderY = (top + bh * 0.26f).coerceIn(0.08f, 0.95f),
            rightHandX = (cx + bw * 0.42f).coerceIn(0.04f, 0.96f),
            rightHandY = (top + bh * 0.52f).coerceIn(0.12f, 0.96f),
            leftHipX = (cx - bw * 0.14f).coerceIn(0.05f, 0.95f),
            leftHipY = (top + bh * 0.56f).coerceIn(0.20f, 0.95f),
            leftFootX = (cx - bw * 0.17f).coerceIn(0.05f, 0.95f),
            leftFootY = (top + bh * 0.94f).coerceIn(0.30f, 0.98f),
            rightHipX = (cx + bw * 0.14f).coerceIn(0.05f, 0.95f),
            rightHipY = (top + bh * 0.56f).coerceIn(0.20f, 0.95f),
            rightFootX = (cx + bw * 0.17f).coerceIn(0.05f, 0.95f),
            rightFootY = (top + bh * 0.94f).coerceIn(0.30f, 0.98f)
        )
    }

    /**
     * Removes solid/uniform corner background pixels on-device (for non-transparent PNG/JPG)
     * with soft edge feathering and saves a clean transparent PNG file offline.
     */
    fun removeSolidBackgroundToPng(source: Bitmap, outFile: File, tolerance: Int = 36): String? {
        return runCatching {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            // Sample top-left corner pixel as background reference
            val ref = pixels.firstOrNull() ?: return null
            val refA = (ref ushr 24) and 0xFF
            if (refA < 16) {
                // Already transparent PNG; save as-is
                FileOutputStream(outFile).use { fos ->
                    source.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                return outFile.absolutePath
            }
            val refR = (ref ushr 16) and 0xFF
            val refG = (ref ushr 8) and 0xFF
            val refB = ref and 0xFF
            val tol = tolerance.coerceIn(8, 120)
            val feather = (tol + 18).coerceAtMost(150)

            for (i in pixels.indices) {
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                if (a == 0) continue
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val diff = (abs(r - refR) + abs(g - refG) + abs(b - refB)) / 3
                if (diff <= tol) {
                    pixels[i] = Color.TRANSPARENT
                } else if (diff < feather) {
                    val newAlpha = ((diff - tol).toFloat() / (feather - tol).toFloat() * a).toInt().coerceIn(0, 255)
                    pixels[i] = (newAlpha shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val cleaned = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cleaned.setPixels(pixels, 0, w, 0, 0, w, h)
            FileOutputStream(outFile).use { fos ->
                cleaned.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            cleaned.recycle()
            outFile.absolutePath
        }.getOrNull()
    }
}
