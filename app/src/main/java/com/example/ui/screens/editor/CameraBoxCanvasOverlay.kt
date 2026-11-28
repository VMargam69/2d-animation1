package com.example.ui.screens.editor

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.engine.CameraBoxEngine
import com.example.engine.CameraBoxHandle
import com.example.model.CameraBoxClip
import com.example.model.EasingType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reusable zero-allocation paint pool for drawing interactive Multi-Camera Boxes (A, B, C...)
 * in Director / Stage Mode directly inside [EditorCanvas].
 *
 * Never invoked during playback or MP4/GIF/PNG export.
 */
internal class CameraBoxOverlayPaints {
    val peachColor = 0xFFF59E6C.toInt()
    val peachGlowColor = 0x44F59E6C.toInt()
    val badgeTextColor = 0xFF1A1410.toInt()
    val whiteColor = 0xFFFFFFFF.toInt()
    val darkStrokeColor = 0xFF0F172A.toInt()

    val motionPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
        color = peachColor
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }

    val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    val thirdsGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0x33F59E6C
    }

    val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.6f
        color = peachColor
    }

    val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = peachColor
    }

    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = badgeTextColor
    }

    val infoChipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC121620.toInt()
    }

    val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = peachColor
    }

    val path = Path()
    val rect = RectF()
}

/**
 * Draws all visual Camera Boxes (`A`, `B`, `C`...) over the wide Director stage:
 * - Subtle dashed motion path (`A -> B -> C`) with directional arrowheads between box centers
 * - Dimmed (~45% opacity) inactive boxes with top-left solid peach badge (`#F59E6C`)
 * - Highlighted active/selected box with 4 corner handles, 4 edge handles, and top rotation stem
 */
internal fun DrawScope.drawDirectorCameraBoxesOverlay(
    boxes: List<CameraBoxClip>,
    selectedBoxId: String?,
    livePreviewBox: CameraBoxClip?,
    activeHandle: CameraBoxHandle?,
    paints: CameraBoxOverlayPaints,
    densityPx: Float
) {
    if (boxes.isEmpty()) return
    val w = size.width
    val h = size.height
    val stemLen = 24f * densityPx

    val resolvedBoxes = boxes.map { b ->
        if (livePreviewBox != null && b.id == livePreviewBox.id) livePreviewBox else b
    }.sortedBy { it.startFrame }

    val effectiveSelectedId = selectedBoxId?.takeIf { id -> resolvedBoxes.any { it.id == id } }
        ?: resolvedBoxes.first().id

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas

        // 1. Subtle dashed motion path connecting camera box centers (A -> B -> C)
        if (resolvedBoxes.size >= 2) {
            paints.motionPathPaint.strokeWidth = 1.9f * densityPx
            paints.motionPathPaint.alpha = 175
            paints.path.reset()
            val centers = resolvedBoxes.map { b ->
                CameraBoxEngine.stageToDirectorPx(b.centerX, b.centerY, w, h)
            }
            for (i in 0 until centers.size - 1) {
                val (x1, y1) = centers[i]
                val (x2, y2) = centers[i + 1]
                val isHoldCut = resolvedBoxes[i].easing == EasingType.HOLD

                paints.path.reset()
                paints.path.moveTo(x1, y1)
                paints.path.lineTo(x2, y2)
                paints.motionPathPaint.alpha = if (isHoldCut) 110 else 185
                canvas.drawPath(paints.path, paints.motionPathPaint)

                // Midpoint directional chevron along A -> B -> C
                val mx = (x1 + x2) * 0.5f
                val my = (y1 + y2) * 0.5f
                val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
                val arrowSize = 5.5f * densityPx
                paints.handleFillPaint.color = paints.peachColor
                paints.handleFillPaint.alpha = if (isHoldCut) 130 else 210
                paints.path.reset()
                paints.path.moveTo(mx + cos(ang) * arrowSize, my + sin(ang) * arrowSize)
                paints.path.lineTo(
                    mx + cos(ang + 2.45f) * arrowSize,
                    my + sin(ang + 2.45f) * arrowSize
                )
                paints.path.lineTo(
                    mx + cos(ang - 2.45f) * arrowSize,
                    my + sin(ang - 2.45f) * arrowSize
                )
                paints.path.close()
                canvas.drawPath(paints.path, paints.handleFillPaint)
            }

            // Center anchor dots on each camera box
            for ((idx, pt) in centers.withIndex()) {
                val isSel = resolvedBoxes[idx].id == effectiveSelectedId
                paints.handleFillPaint.color = paints.peachColor
                paints.handleFillPaint.alpha = if (isSel) 245 else 135
                canvas.drawCircle(pt.first, pt.second, (if (isSel) 3.8f else 2.8f) * densityPx, paints.handleFillPaint)
            }
        }

        // 2. Draw inactive camera boxes first (dimmed ~45% opacity), then selected box on top
        val orderedForDraw = resolvedBoxes.sortedBy { if (it.id == effectiveSelectedId) 1 else 0 }
        for (box in orderedForDraw) {
            val isSelected = box.id == effectiveSelectedId
            val geom = CameraBoxEngine.computeBoxGeometry(
                box = box,
                canvasWidth = w,
                canvasHeight = h,
                stemLengthPx = stemLen
            )
            val alphaMult = if (isSelected) 1f else 0.45f
            val hw = geom.width * 0.5f
            val hh = geom.height * 0.5f

            canvas.save()
            canvas.translate(geom.centerX, geom.centerY)
            canvas.rotate(geom.rotationDeg)

            // Soft wash fill for active box
            if (isSelected) {
                paints.boxFillPaint.color = paints.peachColor
                paints.boxFillPaint.alpha = 18
                paints.rect.set(-hw, -hh, hw, hh)
                canvas.drawRect(paints.rect, paints.boxFillPaint)

                // Subtle inner rule-of-thirds composition hairlines
                paints.thirdsGridPaint.strokeWidth = 0.9f * densityPx
                paints.thirdsGridPaint.alpha = 64
                val thirdW = geom.width / 3f
                val thirdH = geom.height / 3f
                canvas.drawLine(-hw + thirdW, -hh, -hw + thirdW, hh, paints.thirdsGridPaint)
                canvas.drawLine(-hw + thirdW * 2f, -hh, -hw + thirdW * 2f, hh, paints.thirdsGridPaint)
                canvas.drawLine(-hw, -hh + thirdH, hw, -hh + thirdH, paints.thirdsGridPaint)
                canvas.drawLine(-hw, -hh + thirdH * 2f, hw, -hh + thirdH * 2f, paints.thirdsGridPaint)
            }

            // Outer glow + crisp aspect-ratio-locked camera frame border
            paints.rect.set(-hw, -hh, hw, hh)
            if (isSelected) {
                paints.boxBorderPaint.color = paints.peachGlowColor
                paints.boxBorderPaint.strokeWidth = 5.5f * densityPx
                paints.boxBorderPaint.alpha = 140
                canvas.drawRect(paints.rect, paints.boxBorderPaint)
            }
            paints.boxBorderPaint.color = paints.peachColor
            paints.boxBorderPaint.strokeWidth = (if (isSelected) 2.2f else 1.5f) * densityPx
            paints.boxBorderPaint.alpha = (255 * alphaMult).toInt()
            canvas.drawRect(paints.rect, paints.boxBorderPaint)

            // Top-left solid peach/orange badge tag (#F59E6C) inside the camera rectangle ("A", "B", "C"...)
            val badgeSize = (if (isSelected) 22f else 18f) * densityPx
            val badgeInset = 4f * densityPx
            val badgeCorner = 5f * densityPx
            paints.rect.set(
                -hw + badgeInset,
                -hh + badgeInset,
                -hw + badgeInset + badgeSize,
                -hh + badgeInset + badgeSize
            )
            paints.badgeFillPaint.color = paints.peachColor
            paints.badgeFillPaint.alpha = (255 * if (isSelected) 1f else 0.55f).toInt()
            canvas.drawRoundRect(paints.rect, badgeCorner, badgeCorner, paints.badgeFillPaint)

            paints.badgeTextPaint.textSize = (if (isSelected) 12.5f else 10.5f) * densityPx
            paints.badgeTextPaint.alpha = (255 * if (isSelected) 1f else 0.72f).toInt()
            val textY = paints.rect.centerY() - (paints.badgeTextPaint.descent() + paints.badgeTextPaint.ascent()) * 0.5f
            canvas.drawText(box.label, paints.rect.centerX(), textY, paints.badgeTextPaint)

            // Compact live zoom/tilt badge in top-right corner of the active box
            if (isSelected && geom.width >= 96f * densityPx) {
                val info = "${(box.zoom * 100).toInt()}%${if (kotlin.math.abs(box.rotation) >= 0.5f) " • ${box.rotation.toInt()}°" else ""}"
                paints.infoTextPaint.textSize = 9.5f * densityPx
                val tw = paints.infoTextPaint.measureText(info)
                val padX = 5f * densityPx
                val chipH = 16f * densityPx
                paints.rect.set(
                    hw - badgeInset - tw - padX * 2f,
                    -hh + badgeInset,
                    hw - badgeInset,
                    -hh + badgeInset + chipH
                )
                canvas.drawRoundRect(paints.rect, 4f * densityPx, 4f * densityPx, paints.infoChipBgPaint)
                val iy = paints.rect.centerY() - (paints.infoTextPaint.descent() + paints.infoTextPaint.ascent()) * 0.5f
                canvas.drawText(info, paints.rect.right - padX, iy, paints.infoTextPaint)
            }

            // Interactive Handles on the selected/active Camera Box
            if (isSelected) {
                // Top Rotation Stem + Circular Knob
                paints.stemPaint.strokeWidth = 2.0f * densityPx
                paints.stemPaint.alpha = 240
                canvas.drawLine(0f, -hh, 0f, -hh - stemLen, paints.stemPaint)

                val rotRadius = (if (activeHandle == CameraBoxHandle.ROTATE_STEM) 8.5f else 7.2f) * densityPx
                val rotCy = -hh - stemLen
                paints.handleFillPaint.color = if (activeHandle == CameraBoxHandle.ROTATE_STEM) paints.peachColor else paints.whiteColor
                paints.handleFillPaint.alpha = 255
                canvas.drawCircle(0f, rotCy, rotRadius, paints.handleFillPaint)
                paints.handleStrokePaint.color = paints.peachColor
                paints.handleStrokePaint.strokeWidth = 2.2f * densityPx
                canvas.drawCircle(0f, rotCy, rotRadius, paints.handleStrokePaint)

                // Small rotation arc cue inside the rotation knob
                paints.handleStrokePaint.color = if (activeHandle == CameraBoxHandle.ROTATE_STEM) paints.badgeTextColor else paints.darkStrokeColor
                paints.handleStrokePaint.strokeWidth = 1.4f * densityPx
                val arcR = rotRadius * 0.48f
                paints.rect.set(-arcR, rotCy - arcR, arcR, rotCy + arcR)
                canvas.drawArc(paints.rect, -210f, 250f, false, paints.handleStrokePaint)

                // 4 Edge Midpoint Pill Handles (Top, Bottom, Left, Right)
                val pillLong = 14f * densityPx
                val pillShort = 4.6f * densityPx
                val pillCorner = pillShort * 0.5f

                fun drawEdgePill(cx: Float, cy: Float, horizontal: Boolean, isLit: Boolean) {
                    val rx = if (horizontal) pillLong * 0.5f else pillShort * 0.5f
                    val ry = if (horizontal) pillShort * 0.5f else pillLong * 0.5f
                    paints.rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
                    paints.handleFillPaint.color = if (isLit) paints.peachColor else paints.whiteColor
                    paints.handleFillPaint.alpha = 255
                    canvas.drawRoundRect(paints.rect, pillCorner, pillCorner, paints.handleFillPaint)
                    paints.handleStrokePaint.color = paints.peachColor
                    paints.handleStrokePaint.strokeWidth = 1.6f * densityPx
                    canvas.drawRoundRect(paints.rect, pillCorner, pillCorner, paints.handleStrokePaint)
                }

                drawEdgePill(0f, -hh, horizontal = true, isLit = activeHandle == CameraBoxHandle.EDGE_TOP)
                drawEdgePill(0f, hh, horizontal = true, isLit = activeHandle == CameraBoxHandle.EDGE_BOTTOM)
                drawEdgePill(-hw, 0f, horizontal = false, isLit = activeHandle == CameraBoxHandle.EDGE_LEFT)
                drawEdgePill(hw, 0f, horizontal = false, isLit = activeHandle == CameraBoxHandle.EDGE_RIGHT)

                // 4 Corner Handles (TL, TR, BR, BL)
                val cornerPts = listOf(
                    Triple(-hw, -hh, CameraBoxHandle.TOP_LEFT),
                    Triple(hw, -hh, CameraBoxHandle.TOP_RIGHT),
                    Triple(hw, hh, CameraBoxHandle.BOTTOM_RIGHT),
                    Triple(-hw, hh, CameraBoxHandle.BOTTOM_LEFT)
                )
                for ((cx, cy, handle) in cornerPts) {
                    val isLit = activeHandle == handle
                    val r = (if (isLit) 7.4f else 6.0f) * densityPx
                    paints.handleFillPaint.color = if (isLit) paints.peachColor else paints.whiteColor
                    paints.handleFillPaint.alpha = 255
                    canvas.drawCircle(cx, cy, r, paints.handleFillPaint)
                    paints.handleStrokePaint.color = paints.peachColor
                    paints.handleStrokePaint.strokeWidth = 2.2f * densityPx
                    canvas.drawCircle(cx, cy, r, paints.handleStrokePaint)
                }
            }

            canvas.restore()
        }
    }
}
