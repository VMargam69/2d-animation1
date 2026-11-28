package com.example.engine

import com.example.model.ParallaxDepthPreset
import kotlin.math.abs

/**
 * Pure-Kotlin 2.5D Multi-Plane Parallax Camera math.
 *
 * Scales camera pan, zoom, and subtle roll proportionally by a layer's [parallaxDepth]:
 * - `0.2f` (Far): distant sky/mountains drift slowly and zoom gently.
 * - `0.6f` (Mid): mid-ground trees/buildings move at medium parallax speed.
 * - `1.0f` (Stage): primary character stage plane (exact 1:1 camera transform).
 * - `1.35f` (Near): foreground elements sweep past faster for cinematic depth.
 */
object ParallaxCameraMath {

    const val MIN_DEPTH = 0.05f
    const val MAX_DEPTH = 2.0f
    const val STAGE_DEPTH = 1.0f

    fun clampDepth(depth: Float): Float = depth.coerceIn(MIN_DEPTH, MAX_DEPTH)

    fun isStagePlane(depth: Float): Boolean = abs(depth - STAGE_DEPTH) < 0.005f

    /**
     * Computes the depth-scaled camera transform for a layer at [parallaxDepth].
     * When [parallaxDepth] == 1.0f, returns [baseCamera] unchanged.
     */
    fun computeLayerCamera(
        baseCamera: EvaluatedCamera,
        parallaxDepth: Float
    ): EvaluatedCamera {
        val d = clampDepth(parallaxDepth)
        if (isStagePlane(d) || baseCamera.isIdentity) return baseCamera

        val effX = (0.5f + (baseCamera.x - 0.5f) * d).coerceIn(-1.5f, 2.5f)
        val effY = (0.5f + (baseCamera.y - 0.5f) * d).coerceIn(-1.5f, 2.5f)
        val effZoom = (1f + (baseCamera.zoom - 1f) * d).coerceIn(0.35f, 4.5f)
        val effRotation = baseCamera.rotation * d.coerceAtMost(1.25f)

        return baseCamera.copy(
            x = effX,
            y = effY,
            zoom = effZoom,
            rotation = effRotation
        )
    }

    fun nearestPreset(depth: Float): ParallaxDepthPreset {
        return ParallaxDepthPreset.entries.minByOrNull { abs(it.depth - depth) }
            ?: ParallaxDepthPreset.STAGE
    }
}
