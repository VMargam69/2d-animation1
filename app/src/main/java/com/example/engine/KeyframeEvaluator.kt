package com.example.engine

import com.example.model.EasingType
import com.example.model.NormalizedPoint
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
    val opacity: Float
)

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

        return EvaluatedTransform(
            x = lerp(prev.x, next.x, t),
            y = lerp(prev.y, next.y, t),
            rotation = lerp(prev.rotation, next.rotation, t),
            scaleX = lerp(prev.scaleX, next.scaleX, t),
            scaleY = lerp(prev.scaleY, next.scaleY, t),
            opacity = lerp(prev.opacity, next.opacity, t).coerceIn(0f, 1f)
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
        opacity = opacity.coerceIn(0f, 1f)
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
