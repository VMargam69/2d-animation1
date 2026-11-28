package com.example.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.model.AnimationScene
import com.example.model.DialogueOverlayBeat
import com.example.model.TextBubbleStyle
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-Kotlin evaluated animation scalars for a single [DialogueOverlayBeat] at a given frame.
 */
data class DialogueAnimationEval(
    val bubbleScale: Float,
    val bubbleAlpha: Float,
    val subtitleAlpha: Float,
    val subtitleSlideOffsetRatio: Float
)

/**
 * Optional, opt-in Animated Comic Speech Bubble & Cinema Subtitle Overlay Engine.
 *
 * Uses Android [StaticLayout] + [TextPaint] for crisp native Unicode script shaping
 * across Odia (`ଓଡ଼ିଆ`), Hindi (`हिन्दी`), and English.
 * Strictly opt-in: overlays where `showSpeechBubble == false && showSubtitle == false`
 * are never rendered.
 */
object DialogueOverlayRenderer {

    private val fillPaintLocal = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    private val strokePaintLocal = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    }
    private val textPaintLocal = ThreadLocal.withInitial {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }
    private val rectLocal = ThreadLocal.withInitial { RectF() }
    private val pathLocal = ThreadLocal.withInitial { Path() }

    /**
     * Pure-Kotlin filter returning only opted-in overlays active at [frame].
     */
    fun selectActiveOverlays(
        overlays: List<DialogueOverlayBeat>,
        frame: Int
    ): List<DialogueOverlayBeat> {
        if (overlays.isEmpty()) return emptyList()
        return overlays.filter { beat ->
            beat.isOptedIn && frame >= beat.startFrame && frame < beat.endFrame
        }
    }

    /**
     * Pure-Kotlin pop-in overshoot (speech bubble) & fade/slide (subtitle) curve evaluator.
     */
    fun evaluateOverlayAnimation(
        beat: DialogueOverlayBeat,
        frame: Int,
        fps: Int
    ): DialogueAnimationEval {
        val duration = (beat.endFrame - beat.startFrame).coerceAtLeast(1)
        val localF = (frame - beat.startFrame).coerceIn(0, duration)
        val remaining = (beat.endFrame - 1 - frame).coerceAtLeast(0)
        val enterFrames = (fps * 0.25f).toInt().coerceIn(4, 10)
        val exitFrames = (fps * 0.20f).toInt().coerceIn(3, 8)

        val enterT = (localF.toFloat() / enterFrames.toFloat()).coerceIn(0f, 1f)
        val exitT = (remaining.toFloat() / exitFrames.toFloat()).coerceIn(0f, 1f)

        // Elastic comic pop-in overshoot for speech bubble (peaks ~1.08 before settling to 1.0)
        val popScale = if (enterT < 1f) {
            val ease = 1f - (1f - enterT) * (1f - enterT)
            val overshoot = sin(enterT * PI).toFloat() * 0.14f
            (0.25f + 0.75f * ease + overshoot).coerceIn(0.2f, 1.15f)
        } else {
            1f
        }
        val bubbleScale = if (exitT < 1f) {
            popScale * (0.65f + 0.35f * exitT)
        } else {
            popScale
        }
        val bubbleAlpha = min(enterT * 1.6f, exitT * 1.4f).coerceIn(0f, 1f)

        // Smooth fade + gentle slide-up for cinema subtitle bar
        val subFade = min(enterT, exitT).coerceIn(0f, 1f)
        val slideRatio = (1f - enterT) * (1f - enterT)

        return DialogueAnimationEval(
            bubbleScale = bubbleScale,
            bubbleAlpha = bubbleAlpha,
            subtitleAlpha = subFade,
            subtitleSlideOffsetRatio = slideRatio
        )
    }

    /**
     * Renders all opted-in active speech bubbles and subtitles for [scene] at [frame].
     */
    fun drawDialogueOverlays(
        canvas: Canvas,
        width: Float,
        height: Float,
        scene: AnimationScene,
        frame: Int,
        fps: Int,
        effectiveCamera: EvaluatedCamera,
        gesturePreview: StageGesturePreview? = null
    ) {
        val active = selectActiveOverlays(scene.dialogueOverlays, frame)
        if (active.isEmpty()) return

        var subtitleSlotIndex = 0
        for (beat in active) {
            val anim = evaluateOverlayAnimation(beat, frame, fps)
            if (beat.showSpeechBubble) {
                val anchor = resolveSpeakerScreenAnchor(
                    scene = scene,
                    beat = beat,
                    frame = frame,
                    width = width,
                    height = height,
                    camera = effectiveCamera,
                    gesturePreview = gesturePreview
                )
                drawComicSpeechBubble(
                    canvas = canvas,
                    width = width,
                    height = height,
                    anchorX = anchor.first,
                    anchorY = anchor.second,
                    beat = beat,
                    anim = anim
                )
            }
            if (beat.showSubtitle) {
                drawSubtitleBar(
                    canvas = canvas,
                    width = width,
                    height = height,
                    beat = beat,
                    anim = anim,
                    stackIndex = subtitleSlotIndex
                )
                subtitleSlotIndex++
            }
        }
    }

    private fun resolveSpeakerScreenAnchor(
        scene: AnimationScene,
        beat: DialogueOverlayBeat,
        frame: Int,
        width: Float,
        height: Float,
        camera: EvaluatedCamera,
        gesturePreview: StageGesturePreview?
    ): Pair<Float, Float> {
        val speakerClip = scene.clips.firstOrNull { clip ->
            frame in clip.startFrame until clip.endFrame && (
                (beat.speakerClipId != null && clip.id == beat.speakerClipId) ||
                    (beat.speakerCharacterId != null && clip.characterId == beat.speakerCharacterId)
                )
        }
        if (speakerClip == null) {
            return (width * 0.5f) to (height * 0.30f)
        }
        val eval = KeyframeEvaluator.evaluate(speakerClip, frame)
        val wxNorm = if (gesturePreview?.draggedClipId == speakerClip.id) {
            (eval.x + gesturePreview.clipDeltaX).coerceIn(0.05f, 0.95f)
        } else eval.x
        val wyNorm = if (gesturePreview?.draggedClipId == speakerClip.id) {
            (eval.y + gesturePreview.clipDeltaY).coerceIn(0.05f, 0.95f)
        } else eval.y

        // Position anchor above the speaker's head in world space, then project through active camera
        val headOffsetNorm = (0.20f * eval.scaleY.coerceIn(0.5f, 2.2f)).coerceIn(0.10f, 0.36f)
        val worldX = wxNorm * width
        val worldY = (wyNorm - headOffsetNorm) * height

        if (camera.isIdentity) {
            return worldX.coerceIn(width * 0.14f, width * 0.86f) to worldY.coerceIn(height * 0.12f, height * 0.72f)
        }
        val dx = worldX - camera.x * width
        val dy = worldY - camera.y * height
        val rad = (-camera.rotation * PI / 180.0).toFloat()
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        val rx = (dx * c - dy * s) * camera.zoom + width * 0.5f
        val ry = (dx * s + dy * c) * camera.zoom + height * 0.5f
        return rx.coerceIn(width * 0.14f, width * 0.86f) to ry.coerceIn(height * 0.12f, height * 0.72f)
    }

    @Suppress("DEPRECATION")
    private fun buildUnicodeLayout(
        text: CharSequence,
        paint: TextPaint,
        maxWidthPx: Int,
        alignment: Layout.Alignment
    ): StaticLayout {
        val safeWidth = maxWidthPx.coerceAtLeast(48)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(true)
                .build()
        } else {
            StaticLayout(text, paint, safeWidth, alignment, 1.15f, 0f, true)
        }
    }

    private fun drawComicSpeechBubble(
        canvas: Canvas,
        width: Float,
        height: Float,
        anchorX: Float,
        anchorY: Float,
        beat: DialogueOverlayBeat,
        anim: DialogueAnimationEval
    ) {
        if (anim.bubbleAlpha <= 0.01f) return
        val unit = (min(width, height) / 720f).coerceIn(0.55f, 2.4f)
        val tp = textPaintLocal.get()!!
        tp.textSize = (22f * unit).coerceIn(13f, 52f)
        tp.color = ProceduralRigRenderer.parseColorSafe(beat.textColorHex, Color.parseColor("#0F172A"))
        tp.alpha = (anim.bubbleAlpha * 255f).toInt().coerceIn(0, 255)

        val maxTextWidth = (width * 0.44f).toInt().coerceIn(140, 560)
        val layout = buildUnicodeLayout(beat.text, tp, maxTextWidth, Layout.Alignment.ALIGN_CENTER)
        var measuredTextW = 0f
        for (line in 0 until layout.lineCount) {
            measuredTextW = kotlin.math.max(measuredTextW, layout.getLineWidth(line))
        }
        val padX = 20f * unit
        val padY = 14f * unit
        val bubbleW = (measuredTextW + padX * 2f).coerceIn(110f * unit, width * 0.56f)
        val bubbleH = (layout.height + padY * 2f).coerceAtLeast(46f * unit)
        val tailH = 16f * unit

        val centerX = anchorX.coerceIn(bubbleW * 0.5f + 14f * unit, width - bubbleW * 0.5f - 14f * unit)
        val bubbleBottom = (anchorY - tailH).coerceIn(bubbleH + 12f * unit, height * 0.72f)
        val bubbleTop = bubbleBottom - bubbleH
        val bubbleLeft = centerX - bubbleW * 0.5f
        val bubbleRight = centerX + bubbleW * 0.5f
        val cornerR = (if (beat.bubbleStyle == TextBubbleStyle.THOUGHT) bubbleH * 0.42f else 18f * unit)

        val fillPaint = fillPaintLocal.get()!!
        fillPaint.color = ProceduralRigRenderer.parseColorSafe(beat.bubbleFillHex, Color.WHITE)
        fillPaint.alpha = (anim.bubbleAlpha * 248f).toInt().coerceIn(0, 255)

        val strokePaint = strokePaintLocal.get()!!
        strokePaint.color = Color.parseColor("#0F172A")
        strokePaint.strokeWidth = (2.6f * unit).coerceAtLeast(1.5f)
        strokePaint.alpha = (anim.bubbleAlpha * 240f).toInt().coerceIn(0, 255)

        val rect = rectLocal.get()!!
        rect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)

        canvas.save()
        canvas.scale(anim.bubbleScale, anim.bubbleScale, anchorX, anchorY)

        // Subtle drop shadow
        val shadowPaint = fillPaintLocal.get()!!
        val savedFillColor = fillPaint.color
        val savedFillAlpha = fillPaint.alpha
        shadowPaint.color = Color.BLACK
        shadowPaint.alpha = (anim.bubbleAlpha * 68f).toInt().coerceIn(0, 255)
        rect.offset(3f * unit, 4f * unit)
        canvas.drawRoundRect(rect, cornerR, cornerR, shadowPaint)
        rect.offset(-3f * unit, -4f * unit)

        // Main bubble fill + border
        fillPaint.color = savedFillColor
        fillPaint.alpha = savedFillAlpha
        canvas.drawRoundRect(rect, cornerR, cornerR, fillPaint)
        canvas.drawRoundRect(rect, cornerR, cornerR, strokePaint)

        // Pointer tail toward speaker head
        val tailTipX = anchorX.coerceIn(bubbleLeft + 22f * unit, bubbleRight - 22f * unit)
        if (beat.bubbleStyle == TextBubbleStyle.THOUGHT) {
            canvas.drawCircle(tailTipX, bubbleBottom + tailH * 0.35f, 5.5f * unit, fillPaint)
            canvas.drawCircle(tailTipX, bubbleBottom + tailH * 0.35f, 5.5f * unit, strokePaint)
            canvas.drawCircle(tailTipX, bubbleBottom + tailH * 0.92f, 3.2f * unit, fillPaint)
            canvas.drawCircle(tailTipX, bubbleBottom + tailH * 0.92f, 3.2f * unit, strokePaint)
        } else {
            val tailPath = pathLocal.get()!!
            tailPath.reset()
            tailPath.moveTo(tailTipX - 9f * unit, bubbleBottom - 1.5f * unit)
            tailPath.lineTo(tailTipX, bubbleBottom + tailH)
            tailPath.lineTo(tailTipX + 10f * unit, bubbleBottom - 1.5f * unit)
            tailPath.close()
            canvas.drawPath(tailPath, fillPaint)
            canvas.drawLine(tailTipX - 9f * unit, bubbleBottom, tailTipX, bubbleBottom + tailH, strokePaint)
            canvas.drawLine(tailTipX, bubbleBottom + tailH, tailTipX + 10f * unit, bubbleBottom, strokePaint)
        }

        // Draw crisp multi-line Unicode text centered inside bubble
        canvas.save()
        canvas.translate(centerX - maxTextWidth * 0.5f, bubbleTop + (bubbleH - layout.height) * 0.5f)
        layout.draw(canvas)
        canvas.restore()

        canvas.restore()
    }

    private fun drawSubtitleBar(
        canvas: Canvas,
        width: Float,
        height: Float,
        beat: DialogueOverlayBeat,
        anim: DialogueAnimationEval,
        stackIndex: Int
    ) {
        if (anim.subtitleAlpha <= 0.01f) return
        val unit = (min(width, height) / 720f).coerceIn(0.55f, 2.4f)
        val tp = textPaintLocal.get()!!
        tp.textSize = (24f * unit).coerceIn(14f, 54f)
        tp.color = ProceduralRigRenderer.parseColorSafe(beat.subtitleTextHex, Color.parseColor("#F8FAFC"))
        tp.alpha = (anim.subtitleAlpha * 255f).toInt().coerceIn(0, 255)

        val displayLine = if (beat.speakerName.isNotBlank()) {
            "${beat.speakerName}: ${beat.text}"
        } else {
            beat.text
        }
        val maxTextW = (width * 0.80f).toInt().coerceIn(180, 1400)
        val layout = buildUnicodeLayout(displayLine, tp, maxTextW, Layout.Alignment.ALIGN_CENTER)
        var lineMaxW = 0f
        for (i in 0 until layout.lineCount) {
            lineMaxW = kotlin.math.max(lineMaxW, layout.getLineWidth(i))
        }

        val padX = 22f * unit
        val padY = 10f * unit
        val boxW = (lineMaxW + padX * 2f).coerceIn(160f * unit, width * 0.90f)
        val boxH = layout.height + padY * 2f
        val slidePx = anim.subtitleSlideOffsetRatio * 18f * unit
        val bottomMargin = (26f * unit) + stackIndex * (boxH + 8f * unit)
        val boxBottom = height - bottomMargin + slidePx
        val boxTop = boxBottom - boxH
        val boxLeft = (width - boxW) * 0.5f
        val boxRight = boxLeft + boxW

        val bgPaint = fillPaintLocal.get()!!
        bgPaint.color = ProceduralRigRenderer.parseColorSafe(beat.subtitleBgHex, Color.parseColor("#0F172A"))
        bgPaint.alpha = (anim.subtitleAlpha * 204f).toInt().coerceIn(0, 255)

        val rect = rectLocal.get()!!
        rect.set(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rect, 12f * unit, 12f * unit, bgPaint)

        canvas.save()
        canvas.translate((width - maxTextW) * 0.5f, boxTop + padY)
        layout.draw(canvas)
        canvas.restore()
    }
}
