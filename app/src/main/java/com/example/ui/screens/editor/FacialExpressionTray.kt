package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.FacialExpressionEngine
import com.example.engine.FacialExpressionPreset
import com.example.engine.ProceduralRigRenderer
import com.example.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Compact, horizontally scrollable library of pre-defined algorithmic facial expression keyframes.
 *
 * Interaction model:
 *  - Hold & Drag any expression chip onto a Character layer in [TimelinePanel] to place
 *    an organic facial keyframe at the exact target frame.
 *  - Tap any expression chip to apply it immediately to the active Character clip at the playhead.
 */
@Composable
fun FacialExpressionTray(
    activePresetId: String?,
    hasCharacterLayer: Boolean,
    onTapPreset: (FacialExpressionPreset) -> Unit,
    onDragStart: (preset: FacialExpressionPreset, rootPos: Offset) -> Unit,
    onDragMove: (rootPos: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val latestOnTap by rememberUpdatedState(onTapPreset)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragMove by rememberUpdatedState(onDragMove)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(StudioPanel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .testTag("facial_expression_tray"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Subtle studio affordance pill explaining drag-and-drop
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .background(StudioObsidian.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DragIndicator,
                contentDescription = null,
                tint = if (hasCharacterLayer) ElectricCyan else StudioTextMuted,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (hasCharacterLayer) "Hold & Drag" else "Add Character",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (hasCharacterLayer) StudioTextSecondary else StudioTextMuted,
                maxLines = 1
            )
        }

        FacialExpressionEngine.presets.forEach { preset ->
            val accent = remember(preset.accentHex) {
                Color(ProceduralRigRenderer.parseColorSafe(preset.accentHex, 0xFF38BDF8.toInt()))
            }
            val isActive = preset.id == activePresetId
            var chipRootPos by remember(preset.id) { mutableStateOf(Offset.Zero) }

            Surface(
                color = if (isActive) accent.copy(alpha = 0.22f) else StudioCard,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = if (isActive) 1.4.dp else 1.dp,
                    color = if (isActive) accent else StudioBorderSubtle
                ),
                modifier = Modifier
                    .height(32.dp)
                    .onGloballyPositioned { coords ->
                        chipRootPos = coords.positionInRoot()
                    }
                    .pointerInput(preset.id) {
                        detectTapGestures(
                            onTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOnTap(preset)
                            }
                        )
                    }
                    .pointerInput(preset.id) {
                        var currentRootPos = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = { localOffset ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentRootPos = chipRootPos + localOffset
                                latestOnDragStart(preset, currentRootPos)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentRootPos += dragAmount
                                latestOnDragMove(currentRootPos)
                            },
                            onDragEnd = {
                                latestOnDragEnd()
                            },
                            onDragCancel = {
                                latestOnDragCancel()
                            }
                        )
                    }
                    .testTag("facial_preset_chip_${preset.id}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    FacialExpressionMiniGlyph(
                        preset = preset,
                        accent = accent,
                        size = 20.dp
                    )
                    Text(
                        text = preset.shortLabel,
                        fontSize = 10.5.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isActive) accent else StudioTextPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Floating drag ghost pill shown while dragging a facial expression preset over the timeline.
 */
@Composable
fun FloatingExpressionDragBadge(
    preset: FacialExpressionPreset,
    localOffsetPx: Offset,
    targetFrame: Int?,
    targetLayerName: String?,
    modifier: Modifier = Modifier
) {
    val accent = remember(preset.accentHex) {
        Color(ProceduralRigRenderer.parseColorSafe(preset.accentHex, 0xFF38BDF8.toInt()))
    }
    val hasValidTarget = targetFrame != null && targetLayerName != null

    Surface(
        color = StudioObsidian.copy(alpha = 0.94f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.6.dp, if (hasValidTarget) accent else StudioBorder),
        shadowElevation = 10.dp,
        modifier = modifier
            .graphicsLayer {
                translationX = (localOffsetPx.x - 56f).coerceAtLeast(4f)
                translationY = (localOffsetPx.y - 44f).coerceAtLeast(2f)
            }
            .testTag("facial_drag_ghost_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FacialExpressionMiniGlyph(preset = preset, accent = accent, size = 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = preset.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1
                )
                Text(
                    text = if (hasValidTarget) {
                        "Drop on $targetLayerName • F${targetFrame!! + 1}"
                    } else {
                        "Drag onto a Character track"
                    },
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (hasValidTarget) StudioTextPrimary else StudioTextSecondary,
                    maxLines = 1
                )
            }
            if (hasValidTarget) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accent, CircleShape)
                )
            }
        }
    }
}

/**
 * Clean studio vector mini-face icon rendered directly from [FacialExpressionPreset.target],
 * avoiding emoji inconsistency and visually communicating brow/eye/mouth shape at a glance.
 */
@Composable
fun FacialExpressionMiniGlyph(
    preset: FacialExpressionPreset,
    accent: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = minOf(w, h) * 0.45f
        val t = preset.target

        // Subtle face disc with accent rim
        drawCircle(
            color = accent.copy(alpha = 0.18f),
            radius = r,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = accent.copy(alpha = 0.85f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = w * 0.065f)
        )

        val strokeW = w * 0.075f
        val strokeStyle = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eyeY = cy - r * 0.10f
        val eyeSpan = r * 0.36f
        val leftX = cx - eyeSpan
        val rightX = cx + eyeSpan

        // Eyebrows driven by target lift & tilt
        fun drawMiniBrow(ex: Float, isLeft: Boolean) {
            val lift = (if (isLeft) t.leftBrowLift else t.rightBrowLift) * r * 0.16f
            val tilt = (if (isLeft) t.leftBrowTiltDeg else t.rightBrowTiltDeg) * (PI.toFloat() / 180f)
            val bw = r * 0.24f
            val dy = sin(tilt) * bw
            val by = eyeY - r * 0.30f - lift
            val start = if (isLeft) Offset(ex - bw, by - dy) else Offset(ex - bw, by + dy)
            val end = if (isLeft) Offset(ex + bw, by + dy) else Offset(ex + bw, by - dy)
            drawLine(color = accent, start = start, end = end, strokeWidth = strokeW * 0.85f, cap = StrokeCap.Round)
        }
        drawMiniBrow(leftX, isLeft = true)
        drawMiniBrow(rightX, isLeft = false)

        // Eyes (crescent happy, closed sleepy/wink, or open dot/oval)
        fun drawMiniEye(ex: Float, lidClose: Float, open: Float) {
            val ew = r * 0.19f
            if (lidClose >= 0.78f && t.happyEyeArc >= 0.45f) {
                drawArc(
                    color = accent,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(ex - ew, eyeY - ew * 0.55f),
                    size = Size(ew * 2f, ew * 1.4f),
                    style = strokeStyle
                )
            } else if (lidClose >= 0.78f) {
                drawArc(
                    color = accent,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(ex - ew, eyeY - ew * 0.5f),
                    size = Size(ew * 2f, ew * 1.1f),
                    style = strokeStyle
                )
            } else {
                val er = (r * 0.13f * open.coerceIn(0.65f, 1.32f))
                drawCircle(color = accent, radius = er, center = Offset(ex + t.gazeBiasX * r * 0.08f, eyeY))
                if (t.showTear) {
                    drawCircle(
                        color = Color(0xFF38BDF8),
                        radius = r * 0.08f,
                        center = Offset(ex + ew * 0.7f, eyeY + r * 0.25f)
                    )
                }
            }
        }
        drawMiniEye(leftX, t.leftEyelidClose, t.leftEyeOpenness)
        drawMiniEye(rightX, t.rightEyelidClose, t.rightEyeOpenness)

        // Mouth driven by smileCurve & mouthOpen
        val mouthY = cy + r * 0.36f
        val mw = r * 0.36f * t.mouthWidth.coerceIn(0.70f, 1.18f)
        val curve = t.smileCurve * r * 0.26f
        if (t.showGrittedTeeth) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - mw, mouthY - r * 0.10f),
                size = Size(mw * 2f, r * 0.22f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.08f, r * 0.08f),
                style = strokeStyle
            )
        } else if (t.mouthOpen > 0.28f) {
            val mh = (r * 0.14f + t.mouthOpen * r * 0.22f)
            val mouthPath = Path().apply {
                moveTo(cx - mw, mouthY - curve * 0.35f)
                quadraticTo(cx, mouthY - mh * 0.55f, cx + mw, mouthY - curve * 0.35f)
                quadraticTo(cx, mouthY + mh * 1.05f + curve * 0.25f, cx - mw, mouthY - curve * 0.35f)
                close()
            }
            drawPath(path = mouthPath, color = accent.copy(alpha = 0.40f))
            drawPath(path = mouthPath, color = accent, style = strokeStyle)
        } else {
            val lipPath = Path().apply {
                moveTo(cx - mw, mouthY - curve * 0.45f)
                quadraticTo(cx, mouthY + curve * 0.65f, cx + mw, mouthY - curve * 0.45f)
            }
            drawPath(path = lipPath, color = accent, style = strokeStyle)
        }
    }
}
