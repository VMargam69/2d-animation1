package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SceneRenderer(
    private val bitmapProvider: (path: String?, bundledKey: String?) -> Bitmap?
) {

    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        scene: AnimationScene,
        frame: Int,
        fps: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        onionSkinEnabled: Boolean = false,
        showMotionPaths: Boolean = false,
        selectedClipId: String? = null,
        drawEditorOverlays: Boolean = false
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // 1. Base Scene Background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ProceduralRigRenderer.parseColorSafe(scene.backgroundColorHex, 0xFF151B28.toInt())
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val sceneBgBitmap = bitmapProvider(scene.backgroundImagePath, scene.backgroundBundledKey)
        if (sceneBgBitmap != null && !sceneBgBitmap.isRecycled) {
            drawBitmapCenterCrop(canvas, sceneBgBitmap, w, h, 255)
        }

        // 2. Subtle stage grid when empty & in editor mode
        if (drawEditorOverlays && sceneBgBitmap == null) {
            drawSubtleStageGrid(canvas, w, h)
        }

        val visibleLayers = scene.layers
            .filter { it.isVisible }
            .sortedBy { it.orderIndex }

        // 3. Onion Skinning (previous frames tinted coral, next frame tinted emerald)
        if (onionSkinEnabled && drawEditorOverlays) {
            val ghostOffsets = listOf(
                Triple(-2, 0.16f, 0xFFF43F5E.toInt()),
                Triple(-1, 0.28f, 0xFFF43F5E.toInt()),
                Triple(1, 0.20f, 0xFF10B981.toInt())
            )
            for ((offset, ghostAlpha, tintColor) in ghostOffsets) {
                val ghostFrame = frame + offset
                if (ghostFrame in 0 until scene.durationFrames) {
                    drawLayersAtFrame(
                        canvas, w, h, visibleLayers, scene.clips, ghostFrame,
                        charactersById, alphaMultiplier = ghostAlpha, tintOverlay = tintColor,
                        skipBackgroundLayers = true
                    )
                }
            }
        }

        // 4. Current Frame Layers
        drawLayersAtFrame(
            canvas, w, h, visibleLayers, scene.clips, frame,
            charactersById, alphaMultiplier = 1f, tintOverlay = null,
            skipBackgroundLayers = false
        )

        // 5. Editor Overlays (Motion Paths + Selection Gizmo)
        if (drawEditorOverlays) {
            if (showMotionPaths) {
                drawMotionPaths(canvas, w, h, scene, visibleLayers, selectedClipId)
            }
            if (selectedClipId != null) {
                val selectedClip = scene.clips.firstOrNull { it.id == selectedClipId }
                val layerVisible = visibleLayers.any { it.id == selectedClip?.layerId }
                if (selectedClip != null && layerVisible && frame in selectedClip.startFrame until selectedClip.endFrame) {
                    val eval = KeyframeEvaluator.evaluate(selectedClip, frame)
                    drawSelectionGizmo(canvas, w, h, eval)
                }
            }
        }

        canvas.restore()
    }

    private fun drawLayersAtFrame(
        canvas: Canvas,
        w: Float,
        h: Float,
        visibleLayers: List<AnimationLayer>,
        clips: List<TimelineClip>,
        frame: Int,
        charactersById: Map<String, CharacterRigDefinition>,
        alphaMultiplier: Float,
        tintOverlay: Int?,
        skipBackgroundLayers: Boolean
    ) {
        val baseScale = min(w, h) / 420f

        for (layer in visibleLayers) {
            if (skipBackgroundLayers && layer.type == LayerType.BACKGROUND) continue
            if (layer.type == LayerType.AUDIO) continue

            val activeClips = clips.filter {
                it.layerId == layer.id && frame >= it.startFrame && frame < it.endFrame
            }
            for (clip in activeClips) {
                val transform = KeyframeEvaluator.evaluate(clip, frame)
                val effectiveAlpha = (transform.opacity * alphaMultiplier).coerceIn(0f, 1f)
                if (effectiveAlpha <= 0.01f) continue

                if (layer.type == LayerType.BACKGROUND) {
                    val bgBmp = bitmapProvider(clip.imagePath, clip.bundledBgKey)
                    if (bgBmp != null && !bgBmp.isRecycled) {
                        drawBitmapCenterCrop(canvas, bgBmp, w, h, (effectiveAlpha * 255).toInt())
                    }
                    continue
                }

                canvas.save()
                canvas.translate(transform.x * w, transform.y * h)
                canvas.rotate(transform.rotation)
                val sx = transform.scaleX * if (clip.flipHorizontal) -1f else 1f
                val sy = transform.scaleY * if (clip.flipVertical) -1f else 1f
                canvas.scale(sx, sy)

                when (layer.type) {
                    LayerType.CHARACTER -> {
                        val rig = clip.characterId?.let { charactersById[it] }
                        if (rig != null) {
                            val localFrame = (frame - clip.startFrame).coerceAtLeast(0)
                            val cycleFrames = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
                            val rawPhase = (localFrame * clip.speedMultiplier) / cycleFrames
                            val phase = if (clip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
                            val customBmp = bitmapProvider(rig.customImagePath, null)
                            ProceduralRigRenderer.drawCharacter(
                                canvas = canvas,
                                rig = rig,
                                orientation = clip.orientation,
                                preset = clip.animationPresetType,
                                phase = phase,
                                rigScale = baseScale,
                                alpha = effectiveAlpha,
                                customBitmap = customBmp,
                                tintOverlayColor = tintOverlay
                            )
                        }
                    }
                    LayerType.IMAGE -> {
                        val bmp = bitmapProvider(clip.imagePath, clip.bundledBgKey)
                        if (bmp != null && !bmp.isRecycled) {
                            val maxDim = min(w, h) * 0.42f
                            val ratio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
                            val dw = if (ratio >= 1f) maxDim else maxDim * ratio
                            val dh = if (ratio >= 1f) maxDim / ratio else maxDim
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                                alpha = (effectiveAlpha * 255).toInt()
                                if (tintOverlay != null) {
                                    colorFilter = PorterDuffColorFilter(tintOverlay, PorterDuff.Mode.SRC_ATOP)
                                }
                            }
                            canvas.drawBitmap(bmp, null, RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f), paint)
                        }
                    }
                    LayerType.SHAPE -> {
                        drawShapeClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay)
                    }
                    LayerType.TEXT -> {
                        drawTextClip(canvas, clip, baseScale, effectiveAlpha, tintOverlay)
                    }
                    LayerType.DRAWING -> {
                        canvas.restore()
                        drawStrokesClip(canvas, w, h, clip, effectiveAlpha, tintOverlay)
                        continue
                    }
                    else -> Unit
                }
                canvas.restore()
            }
        }
    }

    private fun drawShapeClip(
        canvas: Canvas,
        clip: TimelineClip,
        s: Float,
        alpha: Float,
        tintOverlay: Int?
    ) {
        val kind = clip.shapeKind ?: ShapeKind.CIRCLE
        val alphaInt = (alpha * 255).toInt()
        val fillColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.shapeFillHex, 0xFF38BDF8.toInt())
        val strokeColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.shapeStrokeHex, 0xFFF8FAFC.toInt())

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(alphaInt, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * s
            color = Color.argb(alphaInt, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor))
        }

        val r = 56f * s
        when (kind) {
            ShapeKind.RECTANGLE -> {
                val rect = RectF(-r, -r * 0.72f, r, r * 0.72f)
                canvas.drawRoundRect(rect, 14f * s, 14f * s, fill)
                canvas.drawRoundRect(rect, 14f * s, 14f * s, stroke)
            }
            ShapeKind.CIRCLE -> {
                canvas.drawCircle(0f, 0f, r * 0.85f, fill)
                canvas.drawCircle(0f, 0f, r * 0.85f, stroke)
            }
            ShapeKind.STAR -> {
                val path = Path()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) r else r * 0.44f
                    val angle = (-PI / 2.0 + i * PI / 5.0)
                    val px = (cos(angle) * rad).toFloat()
                    val py = (sin(angle) * rad).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            ShapeKind.SPEECH_BUBBLE -> {
                val bubble = RectF(-r * 1.2f, -r * 0.72f, r * 1.2f, r * 0.52f)
                canvas.drawRoundRect(bubble, 20f * s, 20f * s, fill)
                canvas.drawRoundRect(bubble, 20f * s, 20f * s, stroke)
                val tail = Path().apply {
                    moveTo(-22f * s, r * 0.48f)
                    lineTo(-42f * s, r * 0.92f)
                    lineTo(4f * s, r * 0.48f)
                    close()
                }
                canvas.drawPath(tail, fill)
            }
            ShapeKind.ARROW -> {
                val path = Path().apply {
                    moveTo(-r, -r * 0.25f)
                    lineTo(r * 0.2f, -r * 0.25f)
                    lineTo(r * 0.2f, -r * 0.6f)
                    lineTo(r, 0f)
                    lineTo(r * 0.2f, r * 0.6f)
                    lineTo(r * 0.2f, r * 0.25f)
                    lineTo(-r, r * 0.25f)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawTextClip(
        canvas: Canvas,
        clip: TimelineClip,
        s: Float,
        alpha: Float,
        tintOverlay: Int?
    ) {
        val text = clip.textContent?.ifBlank { "Text" } ?: "Text"
        val baseColor = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(clip.textColorHex, 0xFFFFFFFF.toInt())
        val alphaInt = (alpha * 255).toInt()
        val textSizePx = clip.textSizeSp * s * 1.15f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alphaInt * 0.65f).toInt(), 0, 0, 0)
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alphaInt, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 2.5f * s, 2.5f * s, shadowPaint)
        canvas.drawText(text, 0f, 0f, paint)
    }

    private fun drawStrokesClip(
        canvas: Canvas,
        w: Float,
        h: Float,
        clip: TimelineClip,
        alpha: Float,
        tintOverlay: Int?
    ) {
        val alphaInt = (alpha * 255).toInt()
        val minDim = min(w, h)
        for (strokeData in clip.drawingStrokes) {
            if (strokeData.points.size < 2) continue
            val col = tintOverlay ?: ProceduralRigRenderer.parseColorSafe(strokeData.colorHex, 0xFF38BDF8.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (strokeData.strokeWidthRatio * minDim).coerceAtLeast(3f)
                color = Color.argb(alphaInt, Color.red(col), Color.green(col), Color.blue(col))
            }
            val path = Path()
            strokeData.points.forEachIndexed { idx, pt ->
                val px = pt.x * w
                val py = pt.y * h
                if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawMotionPaths(
        canvas: Canvas,
        w: Float,
        h: Float,
        scene: AnimationScene,
        visibleLayers: List<AnimationLayer>,
        selectedClipId: String?
    ) {
        val visibleIds = visibleLayers.map { it.id }.toSet()
        for (clip in scene.clips) {
            if (clip.layerId !in visibleIds || clip.keyframes.size < 2) continue
            val isSel = clip.id == selectedClipId
            val pts = KeyframeEvaluator.sampleMotionPath(clip, 28)
            val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (isSel) 3.5f else 2f
                color = if (isSel) 0xFFF59E0B.toInt() else 0x8838BDF8.toInt()
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            }
            val path = Path()
            pts.forEachIndexed { i, pt ->
                if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h)
            }
            canvas.drawPath(path, pathPaint)

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isSel) 0xFFF59E0B.toInt() else 0xFF38BDF8.toInt()
            }
            for (kf in clip.keyframes) {
                canvas.drawCircle(kf.x * w, kf.y * h, if (isSel) 6.5f else 4.5f, dotPaint)
            }
        }
    }

    private fun drawSelectionGizmo(canvas: Canvas, w: Float, h: Float, eval: EvaluatedTransform) {
        val cx = eval.x * w
        val cy = eval.y * h
        val halfBox = min(w, h) * 0.16f * ((abs(eval.scaleX) + abs(eval.scaleY)) / 2f).coerceIn(0.4f, 2.2f)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0xFF38BDF8.toInt()
            pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
        }
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF0A0D12.toInt()
        }
        val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0xFF38BDF8.toInt()
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(eval.rotation)
        val box = RectF(-halfBox, -halfBox * 1.15f, halfBox, halfBox * 1.15f)
        canvas.drawRect(box, borderPaint)

        val corners = listOf(
            box.left to box.top,
            box.right to box.top,
            box.left to box.bottom,
            box.right to box.bottom
        )
        for ((hx, hy) in corners) {
            canvas.drawCircle(hx, hy, 7f, handleFill)
            canvas.drawCircle(hx, hy, 7f, handleStroke)
        }
        // Center pivot reticle
        canvas.drawCircle(0f, 0f, 5f, handleStroke)
        canvas.restore()
    }

    private fun drawSubtleStageGrid(canvas: Canvas, w: Float, h: Float) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x16FFFFFF
            strokeWidth = 1f
        }
        val step = (min(w, h) / 10f).coerceAtLeast(24f)
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }
    }

    private fun drawBitmapCenterCrop(canvas: Canvas, bitmap: Bitmap, dstW: Float, dstH: Float, alphaInt: Int) {
        val srcW = bitmap.width.toFloat().coerceAtLeast(1f)
        val srcH = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = (dstW - drawW) / 2f
        val top = (dstH - drawH) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = alphaInt
        }
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
