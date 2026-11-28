package com.example.engine

import com.example.model.*
import kotlin.math.abs

data class EvaluatedNodeTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Float = 1f
)

/**
 * Universal Declarative Custom Animation Engine.
 *
 * Evaluates AI-invented or user-authored [CustomAnimationDef] definitions targeting:
 * 1. Any Inline SVG sub-group `<g id="...">` (e.g., `tree_canopy`, `bird_wing_l`, `animal_tail`),
 * 2. Any Character Rig bone (`HEAD`, `TORSO`, `LEFT_ARM`, `LEFT_FOREARM`, `RIGHT_ARM`,
 *    `RIGHT_FOREARM`, `LEFT_LEG`, `LEFT_KNEE`, `RIGHT_LEG`, `RIGHT_KNEE`, plus [IkHandleId] aliases),
 * 3. Root clip transform (`ROOT` / `CLIP`).
 *
 * Supports `ONCE`, `LOOP`, and `PING_PONG` playback modes with exact per-keyframe
 * `cubicBezier` easing via [KeyframeEvaluator.evaluateEasing], blending smoothly on top of
 * built-in presets and manual IK overrides.
 */
object UniversalCustomAnimationEngine {

    /**
     * Evaluates every track inside [def] at [localFrame] (relative to clip start) and [fps].
     */
    fun evaluateAllTracks(
        def: CustomAnimationDef,
        localFrame: Int,
        fps: Int,
        speedMultiplier: Float = 1f
    ): Map<String, EvaluatedNodeTransform> {
        if (def.tracks.isEmpty()) return emptyMap()
        val normT = computeNormalizedTime(
            localFrame = localFrame,
            fps = fps,
            durationSec = def.durationSec,
            loopMode = def.loopMode,
            speedMultiplier = speedMultiplier
        )
        val out = LinkedHashMap<String, EvaluatedNodeTransform>(def.tracks.size)
        for (track in def.tracks) {
            if (track.targetNodeId.isBlank() || track.keyframes.isEmpty()) continue
            out[track.targetNodeId] = evaluateTrack(track.keyframes, normT)
        }
        return out
    }

    /**
     * Computes normalized cycle time `t in 0f..1f` according to `ONCE`, `LOOP`, or `PING_PONG`.
     */
    fun computeNormalizedTime(
        localFrame: Int,
        fps: Int,
        durationSec: Float,
        loopMode: CustomAnimLoopMode,
        speedMultiplier: Float = 1f
    ): Float {
        val safeFps = fps.coerceIn(1, 120).toFloat()
        val safeDur = durationSec.coerceAtLeast(0.05f)
        val elapsedSec = (localFrame.coerceAtLeast(0).toFloat() * speedMultiplier.coerceIn(0.1f, 8f)) / safeFps
        val cycles = elapsedSec / safeDur
        return when (loopMode) {
            CustomAnimLoopMode.ONCE -> cycles.coerceIn(0f, 1f)
            CustomAnimLoopMode.LOOP -> {
                val frac = cycles - kotlin.math.floor(cycles.toDouble()).toFloat()
                frac.coerceIn(0f, 1f)
            }
            CustomAnimLoopMode.PING_PONG -> {
                val period2 = cycles % 2f
                val normalized = if (period2 < 0f) period2 + 2f else period2
                if (normalized <= 1f) normalized else (2f - normalized)
            }
        }
    }

    /**
     * Evaluates a single track's sorted keyframes at normalized time [normT] using exact cubic Bézier easing.
     */
    fun evaluateTrack(
        keyframes: List<CustomAnimKeyframe>,
        normT: Float
    ): EvaluatedNodeTransform {
        if (keyframes.isEmpty()) return EvaluatedNodeTransform()
        val sorted = if (keyframes.size <= 1) keyframes else keyframes.sortedBy { it.t }
        val first = sorted.first()
        if (sorted.size == 1 || normT <= first.t) {
            return first.toEvaluated()
        }
        val last = sorted.last()
        if (normT >= last.t) {
            return last.toEvaluated()
        }

        var prev = first
        var next = last
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (normT in a.t..b.t) {
                prev = a
                next = b
                break
            }
        }

        val span = (next.t - prev.t).coerceAtLeast(1e-4f)
        val rawU = ((normT - prev.t) / span).coerceIn(0f, 1f)
        val bezier = prev.cubicBezier
        val eased = if (bezier.size >= 4) {
            KeyframeEvaluator.evaluateEasing(
                easing = EasingType.CUSTOM_CUBIC,
                rawT = rawU,
                p1x = bezier[0],
                p1y = bezier[1],
                p2x = bezier[2],
                p2y = bezier[3],
                isAtEnd = normT >= next.t
            )
        } else {
            KeyframeEvaluator.evaluateEasing(EasingType.EASE_IN_OUT, rawU)
        }

        return EvaluatedNodeTransform(
            dx = lerp(prev.dx, next.dx, eased),
            dy = lerp(prev.dy, next.dy, eased),
            rotation = lerp(prev.rotation, next.rotation, eased),
            scaleX = lerp(prev.scaleX, next.scaleX, eased),
            scaleY = lerp(prev.scaleY, next.scaleY, eased),
            opacity = lerp(prev.opacity, next.opacity, eased).coerceIn(0f, 1f)
        )
    }

    /**
     * Maps evaluated bone tracks onto [PoseKeyframeOffsets] and additively blends them with
     * any existing manual IK [baseOffsets] so AI custom animations and manual IK work together seamlessly.
     */
    fun blendIntoPoseOffsets(
        baseOffsets: PoseKeyframeOffsets,
        evaluatedNodes: Map<String, EvaluatedNodeTransform>
    ): PoseKeyframeOffsets {
        if (evaluatedNodes.isEmpty()) return baseOffsets

        var headNod = baseOffsets.headNodOffset
        var torsoTilt = baseOffsets.torsoTiltOffset
        var leftArm = baseOffsets.leftArmOffset
        var leftForearm = baseOffsets.leftForearmOffset
        var rightArm = baseOffsets.rightArmOffset
        var rightForearm = baseOffsets.rightForearmOffset
        var leftLeg = baseOffsets.leftLegOffset
        var leftKnee = baseOffsets.leftKneeOffset
        var rightLeg = baseOffsets.rightLegOffset
        var rightKnee = baseOffsets.rightKneeOffset

        for ((rawKey, tr) in evaluatedNodes) {
            val angle = tr.rotation
            if (abs(angle) < 1e-4f) continue
            when (rawKey.trim().uppercase().replace('-', '_').replace(' ', '_')) {
                "HEAD", "NECK", "HEAD_NOD" -> headNod += angle
                "TORSO", "CHEST", "SPINE", "BODY", "HIPS" -> torsoTilt += angle
                "LEFT_ARM", "LEFT_SHOULDER", "L_ARM", "LEFT_UPPER_ARM" -> leftArm += angle
                "LEFT_FOREARM", "LEFT_ELBOW", "LEFT_HAND", "L_FOREARM", "L_ELBOW", "L_HAND" -> leftForearm += angle
                "RIGHT_ARM", "RIGHT_SHOULDER", "R_ARM", "RIGHT_UPPER_ARM" -> rightArm += angle
                "RIGHT_FOREARM", "RIGHT_ELBOW", "RIGHT_HAND", "R_FOREARM", "R_ELBOW", "R_HAND" -> rightForearm += angle
                "LEFT_LEG", "LEFT_HIP", "LEFT_THIGH", "L_LEG" -> leftLeg += angle
                "LEFT_KNEE", "LEFT_FOOT", "LEFT_SHIN", "L_KNEE", "L_FOOT" -> leftKnee += angle
                "RIGHT_LEG", "RIGHT_HIP", "RIGHT_THIGH", "R_LEG" -> rightLeg += angle
                "RIGHT_KNEE", "RIGHT_FOOT", "RIGHT_SHIN", "R_KNEE", "R_FOOT" -> rightKnee += angle
            }
        }

        return PoseKeyframeOffsets(
            headNodOffset = headNod.coerceIn(-65f, 65f),
            torsoTiltOffset = torsoTilt.coerceIn(-55f, 55f),
            leftArmOffset = leftArm.coerceIn(-160f, 160f),
            leftForearmOffset = leftForearm.coerceIn(-145f, 145f),
            rightArmOffset = rightArm.coerceIn(-160f, 160f),
            rightForearmOffset = rightForearm.coerceIn(-145f, 145f),
            leftLegOffset = leftLeg.coerceIn(-120f, 120f),
            leftKneeOffset = leftKnee.coerceIn(-135f, 135f),
            rightLegOffset = rightLeg.coerceIn(-120f, 120f),
            rightKneeOffset = rightKnee.coerceIn(-135f, 135f)
        )
    }

    /**
     * Extracts optional root-level (`ROOT` or `CLIP`) additive transform offsets from evaluated tracks.
     */
    fun rootTransformOrNull(evaluatedNodes: Map<String, EvaluatedNodeTransform>): EvaluatedNodeTransform? {
        return evaluatedNodes["ROOT"]
            ?: evaluatedNodes["CLIP"]
            ?: evaluatedNodes.entries.firstOrNull {
                it.key.equals("root", ignoreCase = true) || it.key.equals("clip", ignoreCase = true)
            }?.value
    }

    /**
     * Merges evaluated [UniversalKeyframeEngine] sub-part/bone node transforms cleanly on top of
     * base [CustomAnimationDef] tracks (additive dx/dy/rotation, multiplicative scale/opacity).
     */
    fun mergeUniversalNodeTransforms(
        baseNodes: Map<String, EvaluatedNodeTransform>,
        universalNodes: Map<String, EvaluatedNodeTransform>
    ): Map<String, EvaluatedNodeTransform> {
        if (universalNodes.isEmpty()) return baseNodes
        if (baseNodes.isEmpty()) return universalNodes
        val merged = LinkedHashMap<String, EvaluatedNodeTransform>(baseNodes)
        for ((key, u) in universalNodes) {
            val matchKey = merged.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
            val cur = merged[matchKey]
            merged[matchKey] = if (cur == null) {
                u
            } else {
                EvaluatedNodeTransform(
                    dx = cur.dx + u.dx,
                    dy = cur.dy + u.dy,
                    rotation = cur.rotation + u.rotation,
                    scaleX = (cur.scaleX * u.scaleX).coerceIn(0.05f, 8f),
                    scaleY = (cur.scaleY * u.scaleY).coerceIn(0.05f, 8f),
                    opacity = (cur.opacity * u.opacity).coerceIn(0f, 1f)
                )
            }
        }
        return merged
    }

    private fun CustomAnimKeyframe.toEvaluated() = EvaluatedNodeTransform(
        dx = dx,
        dy = dy,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        opacity = opacity.coerceIn(0f, 1f)
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
