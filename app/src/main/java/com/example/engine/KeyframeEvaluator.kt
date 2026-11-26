package com.example.engine

import com.example.model.CameraKeyframe
import com.example.model.EasingType
import com.example.model.FacialExpression
import com.example.model.NormalizedPoint
import com.example.model.PoseKeyframeOffsets
import com.example.model.TimelineClip
import com.example.model.TransformKeyframe
import com.example.model.TransitionType
import kotlin.math.PI
import kotlin.math.cos

data class EvaluatedTransform(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
    val opacity: Float,
    val poseOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
    val expressionOverride: FacialExpression? = null
)

data class EvaluatedCamera(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val zoom: Float = 1.0f,
    val rotation: Float = 0f
) {
    val isIdentity: Boolean
        get() = kotlin.math.abs(x - 0.5f) < 0.0005f &&
            kotlin.math.abs(y - 0.5f) < 0.0005f &&
            kotlin.math.abs(zoom - 1.0f) < 0.0005f &&
            kotlin.math.abs(rotation) < 0.05f
}

object KeyframeEvaluator {

    fun evaluate(clip: TimelineClip, globalFrame: Int): EvaluatedTransform {
        val localFrame = (globalFrame - clip.startFrame).coerceAtLeast(0)
        val sorted = clip.keyframes.sortedBy { it.frame }
        val base = evaluateKeyframes(sorted, localFrame)
        return applyTransitions(base, clip, localFrame)
    }

    fun evaluateKeyframes(sortedKeyframes: List<TransformKeyframe>, localFrame: Int): EvaluatedTransform {
        if (sortedKeyframes.isEmpty()) {
            return EvaluatedTransform(0.5f, 0.55f, 0f, 1f, 1f, 1f)
        }
        val first = sortedKeyframes.first()
        if (sortedKeyframes.size == 1 || localFrame <= first.frame) {
            return first.toEvaluated()
        }
        val last = sortedKeyframes.last()
        if (localFrame >= last.frame) {
            return last.toEvaluated()
        }

        var prev = first
        var next = last
        for (i in 0 until sortedKeyframes.size - 1) {
            val a = sortedKeyframes[i]
            val b = sortedKeyframes[i + 1]
            if (localFrame in a.frame..b.frame) {
                prev = a
                next = b
                break
            }
        }

        val span = (next.frame - prev.frame).coerceAtLeast(1)
        val rawT = ((localFrame - prev.frame).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val t = when (prev.easing) {
            EasingType.HOLD -> if (localFrame >= next.frame) 1f else 0f
            EasingType.LINEAR -> rawT
            EasingType.EASE_IN_OUT -> (0.5f - 0.5f * cos(rawT * PI)).toFloat()
        }

        val pPose = prev.poseOffsets
        val nPose = next.poseOffsets
        val interpPose = PoseKeyframeOffsets(
            headNodOffset = lerp(pPose.headNodOffset, nPose.headNodOffset, t),
            torsoTiltOffset = lerp(pPose.torsoTiltOffset, nPose.torsoTiltOffset, t),
            leftArmOffset = lerp(pPose.leftArmOffset, nPose.leftArmOffset, t),
            leftForearmOffset = lerp(pPose.leftForearmOffset, nPose.leftForearmOffset, t),
            rightArmOffset = lerp(pPose.rightArmOffset, nPose.rightArmOffset, t),
            rightForearmOffset = lerp(pPose.rightForearmOffset, nPose.rightForearmOffset, t),
            leftLegOffset = lerp(pPose.leftLegOffset, nPose.leftLegOffset, t),
            leftKneeOffset = lerp(pPose.leftKneeOffset, nPose.leftKneeOffset, t),
            rightLegOffset = lerp(pPose.rightLegOffset, nPose.rightLegOffset, t),
            rightKneeOffset = lerp(pPose.rightKneeOffset, nPose.rightKneeOffset, t)
        )

        return EvaluatedTransform(
            x = lerp(prev.x, next.x, t),
            y = lerp(prev.y, next.y, t),
            rotation = lerp(prev.rotation, next.rotation, t),
            scaleX = lerp(prev.scaleX, next.scaleX, t),
            scaleY = lerp(prev.scaleY, next.scaleY, t),
            opacity = lerp(prev.opacity, next.opacity, t).coerceIn(0f, 1f),
            poseOffsets = interpPose,
            expressionOverride = if (t >= 1f) (next.expressionOverride ?: prev.expressionOverride) else prev.expressionOverride
        )
    }

    fun evaluateCamera(cameraKeyframes: List<CameraKeyframe>, globalFrame: Int): EvaluatedCamera {
        if (cameraKeyframes.isEmpty()) return EvaluatedCamera()
        val sorted = cameraKeyframes.sortedBy { it.frame }
        val first = sorted.first()
        if (sorted.size == 1 || globalFrame <= first.frame) {
            return EvaluatedCamera(first.x, first.y, first.zoom.coerceIn(0.35f, 3.5f), first.rotation)
        }
        val last = sorted.last()
        if (globalFrame >= last.frame) {
            return EvaluatedCamera(last.x, last.y, last.zoom.coerceIn(0.35f, 3.5f), last.rotation)
        }
        var prev = first
        var next = last
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (globalFrame in a.frame..b.frame) {
                prev = a
                next = b
                break
            }
        }
        val span = (next.frame - prev.frame).coerceAtLeast(1)
        val rawT = ((globalFrame - prev.frame).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val t = when (prev.easing) {
            EasingType.HOLD -> if (globalFrame >= next.frame) 1f else 0f
            EasingType.LINEAR -> rawT
            EasingType.EASE_IN_OUT -> (0.5f - 0.5f * cos(rawT * PI)).toFloat()
        }
        return EvaluatedCamera(
            x = lerp(prev.x, next.x, t),
            y = lerp(prev.y, next.y, t),
            zoom = lerp(prev.zoom, next.zoom, t).coerceIn(0.35f, 3.5f),
            rotation = lerp(prev.rotation, next.rotation, t)
        )
    }

    fun sampleMotionPath(clip: TimelineClip, steps: Int = 24): List<NormalizedPoint> {
        val sorted = clip.keyframes.sortedBy { it.frame }
        if (sorted.size < 2) return sorted.map { NormalizedPoint(it.x, it.y) }
        val startF = sorted.first().frame
        val endF = sorted.last().frame
        val span = (endF - startF).coerceAtLeast(1)
        val result = ArrayList<NormalizedPoint>(steps + 1)
        for (i in 0..steps) {
            val frame = startF + ((span * i) / steps)
            val eval = evaluateKeyframes(sorted, frame)
            result.add(NormalizedPoint(eval.x, eval.y))
        }
        return result
    }

    private fun applyTransitions(
        base: EvaluatedTransform,
        clip: TimelineClip,
        localFrame: Int
    ): EvaluatedTransform {
        var result = base
        val transLen = clip.transitionFrames.coerceIn(1, (clip.durationFrames / 2).coerceAtLeast(1))

        if (clip.transitionIn != TransitionType.NONE && localFrame < transLen) {
            val p = (localFrame.toFloat() / transLen.toFloat()).coerceIn(0f, 1f)
            result = applyTransitionFactor(result, clip.transitionIn, p)
        }
        val framesFromEnd = (clip.durationFrames - 1 - localFrame).coerceAtLeast(0)
        if (clip.transitionOut != TransitionType.NONE && framesFromEnd < transLen) {
            val p = (framesFromEnd.toFloat() / transLen.toFloat()).coerceIn(0f, 1f)
            result = applyTransitionFactor(result, clip.transitionOut, p)
        }
        return result
    }

    private fun applyTransitionFactor(
        t: EvaluatedTransform,
        type: TransitionType,
        progress: Float
    ): EvaluatedTransform {
        val smooth = (0.5f - 0.5f * cos(progress * PI)).toFloat()
        return when (type) {
            TransitionType.NONE -> t
            TransitionType.FADE -> t.copy(opacity = (t.opacity * smooth).coerceIn(0f, 1f))
            TransitionType.SLIDE_LEFT -> t.copy(
                x = t.x - (1f - smooth) * 0.35f,
                opacity = (t.opacity * smooth).coerceIn(0f, 1f)
            )
            TransitionType.SLIDE_UP -> t.copy(
                y = t.y + (1f - smooth) * 0.28f,
                opacity = (t.opacity * smooth).coerceIn(0f, 1f)
            )
            TransitionType.ZOOM_POP -> {
                val scaleMult = 0.35f + 0.65f * smooth
                t.copy(
                    scaleX = t.scaleX * scaleMult,
                    scaleY = t.scaleY * scaleMult,
                    opacity = (t.opacity * smooth).coerceIn(0f, 1f)
                )
            }
        }
    }

    private fun TransformKeyframe.toEvaluated() = EvaluatedTransform(
        x = x,
        y = y,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        opacity = opacity.coerceIn(0f, 1f),
        poseOffsets = poseOffsets,
        expressionOverride = expressionOverride
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
