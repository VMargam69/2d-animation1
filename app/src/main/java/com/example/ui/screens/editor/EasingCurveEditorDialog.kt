package com.example.ui.screens.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.KeyframeEvaluator
import com.example.model.EasingType
import com.example.model.TimelineClip
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.hypot

private data class QuickMotionChipDef(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color
)

private val QUICK_MOTION_PRESETS = listOf(
    QuickMotionChipDef("bounce_in", "Bounce In", Icons.Rounded.South, AmberKeyframe),
    QuickMotionChipDef("slide_overshoot", "Slide Overshoot", Icons.Rounded.East, ElectricCyan),
    QuickMotionChipDef("float_hover", "Float Hover", Icons.Rounded.Air, EmeraldLayer),
    QuickMotionChipDef("dash_across", "Dash Across", Icons.Rounded.FlashOn, OrangeLayer),
    QuickMotionChipDef("pop_scale", "Pop Scale", Icons.Rounded.ZoomOutMap, VioletLayer),
    QuickMotionChipDef("hero_landing", "Hero Landing", Icons.Rounded.Bolt, PinkLayer)
)

/**
 * Advanced Interactive Easing Curve Editor & 1-Tap Quick Motion Presets Dialog.
 * Features a live animated cubic Bézier graph with draggable P1/P2 handles,
 * real-time puck preview, preset chips for all 8 EasingType curves, and
 * 1-tap multi-keyframe Quick Motion choreography presets.
 */
@Composable
fun EasingCurveEditorDialog(
    clip: TimelineClip,
    currentFrame: Int,
    onApplyEasing: (easing: EasingType, p1x: Float, p1y: Float, p2x: Float, p2y: Float) -> Unit,
    onApplyQuickMotion: (presetId: String, label: String) -> Unit,
    onDismiss: () -> Unit
) {
    val localFrame = (currentFrame - clip.startFrame).coerceAtLeast(0)
    val activeKf = remember(clip.keyframes, localFrame) {
        clip.keyframes.firstOrNull { it.frame == localFrame }
            ?: clip.keyframes.filter { it.frame <= localFrame }.maxByOrNull { it.frame }
            ?: clip.keyframes.firstOrNull()
    }

    var selectedEasing by remember(activeKf) { mutableStateOf(activeKf?.easing ?: EasingType.EASE_IN_OUT) }
    val initialHandles = remember(activeKf) {
        if (activeKf != null && activeKf.easing == EasingType.CUSTOM_CUBIC) {
            floatArrayOf(activeKf.easingP1X, activeKf.easingP1Y, activeKf.easingP2X, activeKf.easingP2Y)
        } else {
            KeyframeEvaluator.defaultBezierHandles(activeKf?.easing ?: EasingType.EASE_IN_OUT)
        }
    }
    var p1x by remember(activeKf) { mutableFloatStateOf(initialHandles[0]) }
    var p1y by remember(activeKf) { mutableFloatStateOf(initialHandles[1]) }
    var p2x by remember(activeKf) { mutableFloatStateOf(initialHandles[2]) }
    var p2y by remember(activeKf) { mutableFloatStateOf(initialHandles[3]) }

    // Continuous smooth preview cycle (0f..1f) so the animator immediately feels the easing curve
    val infiniteTransition = rememberInfiniteTransition(label = "easing_preview_loop")
    val previewPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "easing_preview_phase"
    )
    // Hold briefly at start/end so the eye reads acceleration & settle clearly
    val previewT = ((previewPhase - 0.08f) / 0.78f).coerceIn(0f, 1f)
    val evaluatedPreviewY = KeyframeEvaluator.evaluateEasing(
        easing = selectedEasing,
        rawT = previewT,
        p1x = p1x,
        p1y = p1y,
        p2x = p2x,
        p2y = p2y
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = StudioPanel,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .testTag("easing_curve_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElectricCyanDim)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ShowChart,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Easing Curve Editor",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StudioTextPrimary
                            )
                            Text(
                                text = "${clip.name} • Drag P1/P2 handles for custom motion",
                                fontSize = 11.sp,
                                color = StudioTextSecondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("easing_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close easing curve editor",
                            tint = StudioTextSecondary
                        )
                    }
                }

                // Interactive Curve Graph Canvas
                Surface(
                    color = StudioObsidian,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StudioBorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    var draggingHandle by remember { mutableIntStateOf(0) } // 1 = P1, 2 = P2

                    Box(modifier = Modifier.fillMaxSize()) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            val h = size.height.toFloat().coerceAtLeast(1f)
                                            // Map Y in [-0.35..1.35] so elastic/bounce overshoot fits nicely
                                            val p1Px = Offset(p1x * w, mapYToCanvas(p1y, h))
                                            val p2Px = Offset(p2x * w, mapYToCanvas(p2y, h))
                                            val d1 = hypot(start.x - p1Px.x, start.y - p1Px.y)
                                            val d2 = hypot(start.x - p2Px.x, start.y - p2Px.y)
                                            draggingHandle = when {
                                                d1 <= d2 && d1 < 72f -> 1
                                                d2 < 72f -> 2
                                                else -> 0
                                            }
                                            if (draggingHandle != 0) {
                                                selectedEasing = EasingType.CUSTOM_CUBIC
                                            }
                                        },
                                        onDragEnd = { draggingHandle = 0 },
                                        onDragCancel = { draggingHandle = 0 }
                                    ) { change, _ ->
                                        if (draggingHandle == 0) return@detectDragGestures
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        val nx = (change.position.x / w).coerceIn(0.02f, 0.98f)
                                        val ny = unmapCanvasToY(change.position.y, h).coerceIn(-0.45f, 1.45f)
                                        selectedEasing = EasingType.CUSTOM_CUBIC
                                        if (draggingHandle == 1) {
                                            p1x = nx
                                            p1y = ny
                                        } else {
                                            p2x = nx
                                            p2y = ny
                                        }
                                    }
                                }
                                .testTag("easing_curve_canvas")
                        ) {
                            val w = size.width
                            val h = size.height
                            val y0 = mapYToCanvas(0f, h)
                            val y1 = mapYToCanvas(1f, h)

                            // Subtle grid + unit box [0..1]
                            val gridColor = Color.White.copy(alpha = 0.08f)
                            for (i in 1..3) {
                                val gx = w * (i / 4f)
                                drawLine(gridColor, Offset(gx, y1), Offset(gx, y0), strokeWidth = 1f)
                                val gy = y0 + (y1 - y0) * (i / 4f)
                                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                            }
                            // Baseline (0.0) & Target (1.0) guides
                            drawLine(
                                color = Color.White.copy(alpha = 0.22f),
                                start = Offset(0f, y0),
                                end = Offset(w, y0),
                                strokeWidth = 1.2f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.22f),
                                start = Offset(0f, y1),
                                end = Offset(w, y1),
                                strokeWidth = 1.2f
                            )
                            // Linear reference diagonal
                            drawLine(
                                color = Color.White.copy(alpha = 0.16f),
                                start = Offset(0f, y0),
                                end = Offset(w, y1),
                                strokeWidth = 1.4f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                            )

                            // Tangent lines & draggable P1/P2 Bézier handles
                            val p1Pt = Offset(p1x * w, mapYToCanvas(p1y, h))
                            val p2Pt = Offset(p2x * w, mapYToCanvas(p2y, h))
                            drawLine(
                                color = ElectricCyan.copy(alpha = 0.45f),
                                start = Offset(0f, y0),
                                end = p1Pt,
                                strokeWidth = 1.8f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                            )
                            drawLine(
                                color = AmberKeyframe.copy(alpha = 0.45f),
                                start = Offset(w, y1),
                                end = p2Pt,
                                strokeWidth = 1.8f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                            )

                            // Sample and plot the actual active easing curve
                            val curvePath = Path()
                            val steps = 72
                            for (i in 0..steps) {
                                val u = i / steps.toFloat()
                                val v = KeyframeEvaluator.evaluateEasing(
                                    easing = selectedEasing,
                                    rawT = u,
                                    p1x = p1x,
                                    p1y = p1y,
                                    p2x = p2x,
                                    p2y = p2y,
                                    isAtEnd = i == steps
                                )
                                val cx = u * w
                                val cy = mapYToCanvas(v, h)
                                if (i == 0) curvePath.moveTo(cx, cy) else curvePath.lineTo(cx, cy)
                            }
                            // Soft cyan glow under curve
                            drawPath(
                                path = curvePath,
                                color = ElectricCyan.copy(alpha = 0.22f),
                                style = Stroke(width = 9f, cap = StrokeCap.Round)
                            )
                            drawPath(
                                path = curvePath,
                                color = ElectricCyan,
                                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                            )

                            // Animated preview point traveling along curve
                            val dotX = previewT * w
                            val dotY = mapYToCanvas(evaluatedPreviewY, h)
                            drawCircle(Color.White.copy(alpha = 0.24f), radius = 12f, center = Offset(dotX, dotY))
                            drawCircle(Color.White, radius = 5.5f, center = Offset(dotX, dotY))

                            // Endpoints
                            drawCircle(StudioTextSecondary, radius = 4.5f, center = Offset(0f, y0))
                            drawCircle(StudioTextSecondary, radius = 4.5f, center = Offset(w, y1))

                            // Draggable P1 & P2 control handles
                            drawCircle(StudioObsidian, radius = 9.5f, center = p1Pt)
                            drawCircle(ElectricCyan, radius = 7.5f, center = p1Pt)
                            drawCircle(StudioObsidian, radius = 9.5f, center = p2Pt)
                            drawCircle(AmberKeyframe, radius = 7.5f, center = p2Pt)
                        }

                        // Curve formula pill top-left
                        val badgeLabel = if (selectedEasing == EasingType.CUSTOM_CUBIC) {
                            String.format(Locale.US, "cubic(%.2f, %.2f, %.2f, %.2f)", p1x, p1y, p2x, p2y)
                        } else {
                            selectedEasing.displayName
                        }
                        Surface(
                            color = StudioCharcoal.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, StudioBorderSubtle),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ElectricCyan,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Live Linear Motion Puck Strip (lets animator feel acceleration & bounce immediately)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "LIVE MOTION PREVIEW",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = StudioTextMuted
                    )
                    Surface(
                        color = StudioObsidian,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp)
                        ) {
                            val cy = size.height * 0.5f
                            drawLine(
                                color = StudioBorder,
                                start = Offset(0f, cy),
                                end = Offset(size.width, cy),
                                strokeWidth = 3f,
                                cap = StrokeCap.Round
                            )
                            val puckX = (evaluatedPreviewY * size.width).coerceIn(-8f, size.width + 8f)
                            drawCircle(ElectricCyan.copy(alpha = 0.25f), radius = 14f, center = Offset(puckX, cy))
                            drawCircle(ElectricCyan, radius = 8f, center = Offset(puckX, cy))
                            drawCircle(Color.White, radius = 3.2f, center = Offset(puckX, cy))
                        }
                    }
                }

                // Easing Curve Preset Chips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "INTERPOLATION CURVES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = StudioTextMuted
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EasingType.entries.forEach { easing ->
                            val isSelected = selectedEasing == easing
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedEasing = easing
                                    val defaults = KeyframeEvaluator.defaultBezierHandles(easing)
                                    p1x = defaults[0]
                                    p1y = defaults[1]
                                    p2x = defaults[2]
                                    p2y = defaults[3]
                                },
                                label = {
                                    Text(
                                        text = easing.shortLabel,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = StudioCard,
                                    labelColor = StudioTextSecondary,
                                    selectedContainerColor = ElectricCyanDim,
                                    selectedLabelColor = ElectricCyan
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = StudioBorderSubtle,
                                    selectedBorderColor = ElectricCyan
                                ),
                                modifier = Modifier.testTag("easing_chip_${easing.name.lowercase()}")
                            )
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 1-Tap Quick Motion Choreography Presets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = AmberKeyframe,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "1-TAP QUICK MOTION PRESETS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = AmberKeyframe
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QUICK_MOTION_PRESETS.forEach { preset ->
                            Surface(
                                color = StudioCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, preset.accent.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        onApplyQuickMotion(preset.id, preset.label)
                                        onDismiss()
                                    }
                                    .testTag("quick_motion_${preset.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(preset.accent.copy(alpha = 0.18f))
                                    ) {
                                        Icon(
                                            imageVector = preset.icon,
                                            contentDescription = preset.label,
                                            tint = preset.accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = preset.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StudioTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, StudioBorder)
                    ) {
                        Text("Cancel", color = StudioTextSecondary)
                    }
                    Button(
                        onClick = {
                            onApplyEasing(selectedEasing, p1x, p1y, p2x, p2y)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = StudioObsidian
                        ),
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                            .testTag("easing_apply_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Apply Curve", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun mapYToCanvas(value: Float, height: Float): Float {
    // Maps normalized value [-0.35 .. 1.35] cleanly into [height .. 0]
    val minY = -0.35f
    val maxY = 1.35f
    val norm = (value - minY) / (maxY - minY)
    return height * (1f - norm)
}

private fun unmapCanvasToY(py: Float, height: Float): Float {
    val minY = -0.35f
    val maxY = 1.35f
    val norm = 1f - (py / height.coerceAtLeast(1f))
    return minY + norm * (maxY - minY)
}
