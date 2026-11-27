package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import com.example.model.EyeShapeOverride
import com.example.model.EyebrowShapeOverride
import com.example.model.MouthShapeOverride
import com.example.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

private enum class FacialTrayMode { PRESETS, OVERRIDES }
private enum class OverrideChannel(val label: String) { MOUTH("Mouth"), EYES("Eyes"), BROWS("Brows") }

/**
 * Compact, horizontally scrollable Facial Expression Studio strip directly above the timeline:
 *  - **Presets** tab: Hold & Drag full algorithmic facial expression presets onto Character layers,
 *    or tap to stamp at playhead.
 *  - **Overrides** tab: Instant 1-tap independent **Mouth**, **Eye**, and **Eyebrow** shape
 *    overrides layered directly on top of any running character animation.
 */
@Composable
fun FacialExpressionTray(
    activePresetId: String?,
    hasCharacterLayer: Boolean,
    activeMouthOverride: MouthShapeOverride = MouthShapeOverride.AUTO,
    activeEyeOverride: EyeShapeOverride = EyeShapeOverride.AUTO,
    activeEyebrowOverride: EyebrowShapeOverride = EyebrowShapeOverride.AUTO,
    onTapPreset: (FacialExpressionPreset) -> Unit,
    onSetMouthOverride: (MouthShapeOverride) -> Unit = {},
    onSetEyeOverride: (EyeShapeOverride) -> Unit = {},
    onSetEyebrowOverride: (EyebrowShapeOverride) -> Unit = {},
    onClearOverrides: () -> Unit = {},
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

    var trayMode by remember { mutableStateOf(FacialTrayMode.PRESETS) }
    var activeChannel by remember { mutableStateOf(OverrideChannel.MOUTH) }
    val activeOverrideCount = remember(activeMouthOverride, activeEyeOverride, activeEyebrowOverride) {
        (if (activeMouthOverride != MouthShapeOverride.AUTO) 1 else 0) +
            (if (activeEyeOverride != EyeShapeOverride.AUTO) 1 else 0) +
            (if (activeEyebrowOverride != EyebrowShapeOverride.AUTO) 1 else 0)
    }

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
        // Compact Mode Toggle: Presets | Overrides (Mouth • Eyes • Brows)
        Surface(
            color = StudioObsidian,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, StudioBorderSubtle)
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val presetsSelected = trayMode == FacialTrayMode.PRESETS
                Surface(
                    color = if (presetsSelected) ElectricCyan.copy(alpha = 0.22f) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .height(26.dp)
                        .clickable { trayMode = FacialTrayMode.PRESETS }
                        .testTag("facial_tray_mode_presets")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragIndicator,
                            contentDescription = "Full expression presets",
                            tint = if (presetsSelected) ElectricCyan else StudioTextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Presets",
                            fontSize = 10.sp,
                            fontWeight = if (presetsSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (presetsSelected) ElectricCyan else StudioTextSecondary
                        )
                    }
                }
                val overridesSelected = trayMode == FacialTrayMode.OVERRIDES
                Surface(
                    color = if (overridesSelected) AmberKeyframe.copy(alpha = 0.22f) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .height(26.dp)
                        .clickable { trayMode = FacialTrayMode.OVERRIDES }
                        .testTag("facial_tray_mode_overrides")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Facial shape overrides",
                            tint = if (overridesSelected || activeOverrideCount > 0) AmberKeyframe else StudioTextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (activeOverrideCount > 0) "Parts ($activeOverrideCount)" else "Overrides",
                            fontSize = 10.sp,
                            fontWeight = if (overridesSelected || activeOverrideCount > 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (overridesSelected || activeOverrideCount > 0) AmberKeyframe else StudioTextSecondary
                        )
                    }
                }
            }
        }

        if (trayMode == FacialTrayMode.PRESETS) {
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
        } else {
            // Instant 1-tap Facial Shape Overrides: Mouth | Eyes | Brows
            OverrideChannel.entries.forEach { channel ->
                val isSelected = activeChannel == channel
                val hasActive = when (channel) {
                    OverrideChannel.MOUTH -> activeMouthOverride != MouthShapeOverride.AUTO
                    OverrideChannel.EYES -> activeEyeOverride != EyeShapeOverride.AUTO
                    OverrideChannel.BROWS -> activeEyebrowOverride != EyebrowShapeOverride.AUTO
                }
                val channelAccent = when (channel) {
                    OverrideChannel.MOUTH -> PinkLayer
                    OverrideChannel.EYES -> ElectricCyan
                    OverrideChannel.BROWS -> AmberKeyframe
                }
                Surface(
                    color = if (isSelected) channelAccent.copy(alpha = 0.22f) else StudioCard,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.3.dp else 1.dp,
                        color = if (isSelected) channelAccent else if (hasActive) channelAccent.copy(alpha = 0.5f) else StudioBorderSubtle
                    ),
                    modifier = Modifier
                        .height(28.dp)
                        .clickable { activeChannel = channel }
                        .testTag("facial_override_tab_${channel.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (hasActive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(channelAccent, CircleShape)
                            )
                        }
                        Text(
                            text = channel.label,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected || hasActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) channelAccent else StudioTextPrimary
                        )
                    }
                }
            }

            VerticalDivider(color = StudioBorderSubtle, modifier = Modifier.height(22.dp))

            when (activeChannel) {
                OverrideChannel.MOUTH -> {
                    MouthShapeOverride.entries.forEach { shape ->
                        val isActive = activeMouthOverride == shape
                        FacialOverrideChip(
                            label = shape.shortLabel,
                            isActive = isActive,
                            accent = PinkLayer,
                            tag = "mouth_override_chip_${shape.name.lowercase()}",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSetMouthOverride(shape)
                            }
                        )
                    }
                }
                OverrideChannel.EYES -> {
                    EyeShapeOverride.entries.forEach { shape ->
                        val isActive = activeEyeOverride == shape
                        FacialOverrideChip(
                            label = shape.shortLabel,
                            isActive = isActive,
                            accent = ElectricCyan,
                            tag = "eye_override_chip_${shape.name.lowercase()}",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSetEyeOverride(shape)
                            }
                        )
                    }
                }
                OverrideChannel.BROWS -> {
                    EyebrowShapeOverride.entries.forEach { shape ->
                        val isActive = activeEyebrowOverride == shape
                        FacialOverrideChip(
                            label = shape.shortLabel,
                            isActive = isActive,
                            accent = AmberKeyframe,
                            tag = "brow_override_chip_${shape.name.lowercase()}",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSetEyebrowOverride(shape)
                            }
                        )
                    }
                }
            }

            if (activeOverrideCount > 0) {
                Surface(
                    color = CoralPlayhead.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CoralPlayhead.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .height(28.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClearOverrides()
                        }
                        .testTag("facial_overrides_reset_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset facial overrides",
                            tint = CoralPlayhead,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Reset",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CoralPlayhead
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FacialOverrideChip(
    label: String,
    isActive: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (isActive) accent.copy(alpha = 0.24f) else StudioCard,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(
            width = if (isActive) 1.4.dp else 1.dp,
            color = if (isActive) accent else StudioBorderSubtle
        ),
        modifier = Modifier
            .height(29.dp)
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 9.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) accent else StudioTextPrimary,
                maxLines = 1
            )
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
