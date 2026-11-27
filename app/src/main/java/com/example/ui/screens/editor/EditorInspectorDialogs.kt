package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.KeyframeEvaluator
import com.example.model.*
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun ProjectSettingsDialog(
    project: ProjectDocument,
    onDismiss: () -> Unit,
    onApply: (
        name: String,
        ratio: AspectRatioPreset,
        width: Int,
        height: Int,
        fps: Int,
        durationFrames: Int,
        quality: PreviewQuality
    ) -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var aspectRatio by remember { mutableStateOf(project.aspectRatioPreset) }
    var widthText by remember { mutableStateOf(project.width.toString()) }
    var heightText by remember { mutableStateOf(project.height.toString()) }
    var fps by remember { mutableIntStateOf(project.fps) }
    var duration by remember { mutableIntStateOf(project.activeScene.durationFrames) }
    var quality by remember { mutableStateOf(project.previewQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Project & Canvas Settings", color = StudioTextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Canvas Aspect Ratio", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AspectRatioPreset.entries) { preset ->
                        FilterChip(
                            selected = aspectRatio == preset,
                            onClick = {
                                aspectRatio = preset
                                if (preset != AspectRatioPreset.CUSTOM) {
                                    widthText = preset.defaultWidth.toString()
                                    heightText = preset.defaultHeight.toString()
                                }
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }

                if (aspectRatio == AspectRatioPreset.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Width px") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Height px") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("Frame Rate (FPS)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(12, 24, 30, 60).forEach { rate ->
                        FilterChip(
                            selected = fps == rate,
                            onClick = { fps = rate },
                            label = { Text("$rate") }
                        )
                    }
                }

                Text(
                    "Scene Duration: $duration frames (${String.format(Locale.US, "%.1fs", duration.toFloat() / fps)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextSecondary
                )
                Slider(
                    value = duration.toFloat(),
                    onValueChange = { duration = it.toInt() },
                    valueRange = 24f..480f
                )

                Text("Preview Quality Mode", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewQuality.entries.forEach { q ->
                        FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(q.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = widthText.toIntOrNull() ?: aspectRatio.defaultWidth
                    val h = heightText.toIntOrNull() ?: aspectRatio.defaultHeight
                    onApply(name, aspectRatio, w, h, fps, duration, quality)
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian),
                modifier = Modifier.testTag("apply_project_settings_btn")
            ) {
                Text("Apply", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) }
        }
    )
}

@Composable
fun TransformInspectorDialog(
    clip: TimelineClip,
    currentFrame: Int,
    onUpdateTransform: ((TransformKeyframe) -> TransformKeyframe) -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onUpdateClip: (((TimelineClip) -> TimelineClip) -> Unit)? = null,
    isIkPosingMode: Boolean = false,
    onToggleIkPosingMode: (() -> Unit)? = null,
    onOpenEasingCurveEditor: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val eval = remember(clip, currentFrame) { KeyframeEvaluator.evaluate(clip, currentFrame) }
    val localFrame = (currentFrame - clip.startFrame).coerceAtLeast(0)
    val currentEasing = clip.keyframes.lastOrNull { it.frame <= localFrame }?.easing ?: EasingType.LINEAR

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = {
            Column {
                Text("Transform & Stage Controls", color = StudioTextPrimary)
                Text("${clip.name} • Frame ${currentFrame + 1}", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabeledSlider("Position X", eval.x, 0.05f..0.95f, "${(eval.x * 100).toInt()}%") { v ->
                    onUpdateTransform { it.copy(x = v) }
                }
                LabeledSlider("Position Y", eval.y, 0.05f..0.95f, "${(eval.y * 100).toInt()}%") { v ->
                    onUpdateTransform { it.copy(y = v) }
                }
                LabeledSlider("Rotation", eval.rotation, -180f..180f, "${eval.rotation.toInt()}°") { v ->
                    onUpdateTransform { it.copy(rotation = v) }
                }
                LabeledSlider("Size (Scale)", eval.scaleX, 0.3f..2.5f, String.format(Locale.US, "%.2fx", eval.scaleX)) { v ->
                    onUpdateTransform { it.copy(scaleX = v, scaleY = v) }
                }
                LabeledSlider("Fade (Opacity)", eval.opacity, 0f..1f, "${(eval.opacity * 100).toInt()}%") { v ->
                    onUpdateTransform { it.copy(opacity = v) }
                }

                if (onUpdateClip != null) {
                    if (clip.characterId != null) {
                        LabeledSlider(
                            "Black Shade",
                            clip.blackShade,
                            0f..1f,
                            "${(clip.blackShade * 100).toInt()}%"
                        ) { v ->
                            onUpdateClip { it.copy(blackShade = v) }
                        }
                    }
                    LabeledSlider(
                        "Depth Order",
                        clip.depthOrder.toFloat(),
                        -10f..10f,
                        if (clip.depthOrder >= 0) "+${clip.depthOrder}" else "${clip.depthOrder}"
                    ) { v ->
                        onUpdateClip { it.copy(depthOrder = v.toInt()) }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Keyframe Interpolation", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                    if (onOpenEasingCurveEditor != null) {
                        TextButton(
                            onClick = onOpenEasingCurveEditor,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("inspector_open_curve_editor_btn")
                        ) {
                            Icon(Icons.Rounded.ShowChart, null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Curve Editor", color = ElectricCyan, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EasingType.entries) { easing ->
                        FilterChip(
                            selected = currentEasing == easing,
                            onClick = { onUpdateTransform { it.copy(easing = easing) } },
                            label = { Text(easing.shortLabel, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onFlipHorizontal, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Flip, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flip H")
                    }
                    OutlinedButton(onClick = onFlipVertical, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.SwapVert, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flip V")
                    }
                }

                if (clip.characterId != null && onToggleIkPosingMode != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Stage IK Limb Posing", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                        Switch(
                            checked = isIkPosingMode,
                            onCheckedChange = { onToggleIkPosingMode() },
                            modifier = Modifier.testTag("transform_ik_toggle")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun SpeedAndDurationDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Speed & Clip Timing", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledSlider(
                    "Playback Speed",
                    clip.speedMultiplier,
                    0.3f..2.5f,
                    String.format(Locale.US, "%.1fx", clip.speedMultiplier)
                ) { v ->
                    onUpdateClip { it.copy(speedMultiplier = (v * 10f).toInt() / 10f) }
                }
                LabeledSlider(
                    "Clip Duration",
                    clip.durationFrames.toFloat(),
                    12f..360f,
                    "${clip.durationFrames}F"
                ) { v ->
                    onUpdateClip { it.copy(durationFrames = v.toInt()) }
                }
                LabeledSlider(
                    "Start Frame",
                    clip.startFrame.toFloat(),
                    0f..240f,
                    "F${clip.startFrame + 1}"
                ) { v ->
                    onUpdateClip { it.copy(startFrame = v.toInt()) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Loop Preset Motion", color = StudioTextPrimary)
                    Switch(
                        checked = clip.loopAnimation,
                        onCheckedChange = { checked -> onUpdateClip { it.copy(loopAnimation = checked) } }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun TransitionsDialog(
    clip: TimelineClip,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Clip Transitions", color = StudioTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Transition In", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TransitionType.entries) { tr ->
                        FilterChip(
                            selected = clip.transitionIn == tr,
                            onClick = { onUpdateClip { it.copy(transitionIn = tr) } },
                            label = { Text(tr.displayName) }
                        )
                    }
                }
                Text("Transition Out", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TransitionType.entries) { tr ->
                        FilterChip(
                            selected = clip.transitionOut == tr,
                            onClick = { onUpdateClip { it.copy(transitionOut = tr) } },
                            label = { Text(tr.displayName) }
                        )
                    }
                }
                LabeledSlider(
                    "Transition Length",
                    clip.transitionFrames.toFloat(),
                    4f..36f,
                    "${clip.transitionFrames}F"
                ) { v ->
                    onUpdateClip { it.copy(transitionFrames = v.toInt()) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Apply", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun LayerManagerDialog(
    layers: List<AnimationLayer>,
    onAddLayer: (LayerType, String) -> Unit,
    onRenameLayer: (String, String) -> Unit,
    onReorderLayer: (String, Boolean) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newLayerName by remember { mutableStateOf("") }
    var newLayerType by remember { mutableStateOf(LayerType.CHARACTER) }
    var renamingLayer by remember { mutableStateOf<AnimationLayer?>(null) }
    val sorted = layers.sortedByDescending { it.orderIndex }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = { Text("Layer Manager", color = StudioTextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newLayerName,
                        onValueChange = { newLayerName = it },
                        placeholder = { Text("New layer name...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onAddLayer(newLayerType, newLayerName.ifBlank { newLayerType.displayName })
                            newLayerName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
                    ) {
                        Text("Add")
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LayerType.entries) { lt ->
                        FilterChip(
                            selected = newLayerType == lt,
                            onClick = { newLayerType = lt },
                            label = { Text(lt.displayName) }
                        )
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                sorted.forEach { layer ->
                    Surface(
                        color = StudioCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { renamingLayer = layer }
                            ) {
                                Text(layer.name, style = MaterialTheme.typography.titleSmall, color = StudioTextPrimary)
                                Text(layer.type.displayName, style = MaterialTheme.typography.labelSmall, color = StudioTextMuted)
                            }
                            IconButton(onClick = { onReorderLayer(layer.id, true) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Move layer up", tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onReorderLayer(layer.id, false) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Move layer down", tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onToggleVisibility(layer.id) }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    if (layer.isVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    "Toggle visibility",
                                    tint = if (layer.isVisible) ElectricCyan else StudioTextMuted,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            IconButton(onClick = { onToggleLock(layer.id) }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                    "Toggle lock",
                                    tint = if (layer.isLocked) AmberKeyframe else StudioTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (layers.size > 1) {
                                IconButton(onClick = { onDeleteLayer(layer.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, "Delete layer", tint = CoralPlayhead, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Close")
            }
        }
    )

    renamingLayer?.let { target ->
        var text by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renamingLayer = null },
            containerColor = StudioPanel,
            title = { Text("Rename Layer", color = StudioTextPrimary) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { onRenameLayer(target.id, text); renamingLayer = null }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingLayer = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun CharacterActingAndPropsDialog(
    clip: TimelineClip,
    currentFrame: Int,
    isIkPosingMode: Boolean,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onUpdateTransform: ((TransformKeyframe) -> TransformKeyframe) -> Unit,
    onResetPoseAtFrame: () -> Unit,
    onToggleIkPosingMode: () -> Unit,
    onImportCustomPropPng: ((String) -> Unit) -> Unit,
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val eval = remember(clip, currentFrame) { KeyframeEvaluator.evaluate(clip, currentFrame) }
    val activeExpression = eval.expressionOverride ?: clip.facialExpression
    val activeHandPose = eval.handPoseOverride ?: clip.handPose
    val activeBlinkOverride = eval.blinkOverride ?: if (clip.forceBlink) true else null
    val pose = eval.poseOffsets

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = {
            Column {
                Text("Character Stage, Face & Props", color = StudioTextPrimary)
                Text(
                    "${clip.name} • Frame ${currentFrame + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 0. Character Stage Controls: Size, Fade, Black Shade, Depth, Flip, IK
                Text("Character Stage Controls", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                LabeledSlider(
                    "Size",
                    eval.scaleX,
                    0.3f..2.5f,
                    String.format(Locale.US, "%.2fx", eval.scaleX)
                ) { v ->
                    onUpdateTransform { it.copy(scaleX = v, scaleY = v) }
                }
                LabeledSlider(
                    "Fade",
                    eval.opacity,
                    0f..1f,
                    "${(eval.opacity * 100).toInt()}%"
                ) { v ->
                    onUpdateTransform { it.copy(opacity = v) }
                }
                LabeledSlider(
                    "Black Shade",
                    clip.blackShade,
                    0f..1f,
                    "${(clip.blackShade * 100).toInt()}%"
                ) { v ->
                    onUpdateClip { it.copy(blackShade = v) }
                }
                LabeledSlider(
                    "Depth",
                    clip.depthOrder.toFloat(),
                    -10f..10f,
                    if (clip.depthOrder >= 0) "+${clip.depthOrder}" else "${clip.depthOrder}"
                ) { v ->
                    onUpdateClip { it.copy(depthOrder = v.toInt()) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onFlipHorizontal,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("char_stage_flip_h")
                    ) {
                        Icon(Icons.Rounded.Flip, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flip H", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onFlipVertical,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("char_stage_flip_v")
                    ) {
                        Icon(Icons.Rounded.SwapVert, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flip V", style = MaterialTheme.typography.labelSmall)
                    }
                    FilterChip(
                        selected = isIkPosingMode,
                        onClick = onToggleIkPosingMode,
                        label = { Text("IK") },
                        leadingIcon = { Icon(Icons.Rounded.AccessibilityNew, null, modifier = Modifier.size(15.dp)) },
                        modifier = Modifier.testTag("char_stage_ik_toggle")
                    )
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 1. Facial Expression Override (keyframeable)
                Text("Facial Expression Override (Keyframeable)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(FacialExpression.entries) { expr ->
                        FilterChip(
                            selected = activeExpression == expr,
                            onClick = {
                                val mappedPresetId = if (expr == FacialExpression.AUTO) null
                                    else com.example.engine.FacialExpressionEngine.presetForExpression(expr).id
                                onUpdateClip { it.copy(facialExpression = expr) }
                                onUpdateTransform {
                                    it.copy(
                                        expressionOverride = expr,
                                        facialPresetId = mappedPresetId
                                    )
                                }
                            },
                            label = { Text(expr.displayName) },
                            modifier = Modifier.testTag("expression_chip_${expr.name.lowercase()}")
                        )
                    }
                }

                // 1A. Independent Facial Shape Overrides (Mouth • Eyes • Eyebrows layered over animation)
                val activeMouth = eval.mouthShapeOverride ?: clip.mouthShapeOverride
                val activeEye = eval.eyeShapeOverride ?: clip.eyeShapeOverride
                val activeBrow = eval.eyebrowShapeOverride ?: clip.eyebrowShapeOverride
                val hasAnyPartOverride = activeMouth != MouthShapeOverride.AUTO ||
                    activeEye != EyeShapeOverride.AUTO ||
                    activeBrow != EyebrowShapeOverride.AUTO

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Facial Shape Overrides (Mouth • Eyes • Brows)",
                        style = MaterialTheme.typography.labelLarge,
                        color = AmberKeyframe
                    )
                    if (hasAnyPartOverride) {
                        TextButton(
                            onClick = {
                                onUpdateClip {
                                    it.copy(
                                        mouthShapeOverride = MouthShapeOverride.AUTO,
                                        eyeShapeOverride = EyeShapeOverride.AUTO,
                                        eyebrowShapeOverride = EyebrowShapeOverride.AUTO
                                    )
                                }
                                onUpdateTransform {
                                    it.copy(
                                        mouthShapeOverride = MouthShapeOverride.AUTO,
                                        eyeShapeOverride = EyeShapeOverride.AUTO,
                                        eyebrowShapeOverride = EyebrowShapeOverride.AUTO
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("dialog_reset_facial_overrides_btn")
                        ) {
                            Text("Reset", color = CoralPlayhead, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("Mouth Shape", style = MaterialTheme.typography.labelSmall, color = PinkLayer)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(MouthShapeOverride.entries) { shape ->
                        FilterChip(
                            selected = activeMouth == shape,
                            onClick = {
                                onUpdateClip { it.copy(mouthShapeOverride = shape) }
                                onUpdateTransform { it.copy(mouthShapeOverride = shape) }
                            },
                            label = { Text(shape.shortLabel) },
                            modifier = Modifier.testTag("dialog_mouth_shape_${shape.name.lowercase()}")
                        )
                    }
                }
                Text("Eye Shape", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EyeShapeOverride.entries) { shape ->
                        FilterChip(
                            selected = activeEye == shape,
                            onClick = {
                                onUpdateClip { it.copy(eyeShapeOverride = shape) }
                                onUpdateTransform { it.copy(eyeShapeOverride = shape) }
                            },
                            label = { Text(shape.shortLabel) },
                            modifier = Modifier.testTag("dialog_eye_shape_${shape.name.lowercase()}")
                        )
                    }
                }
                Text("Eyebrow Shape", style = MaterialTheme.typography.labelSmall, color = AmberKeyframe)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EyebrowShapeOverride.entries) { shape ->
                        FilterChip(
                            selected = activeBrow == shape,
                            onClick = {
                                onUpdateClip { it.copy(eyebrowShapeOverride = shape) }
                                onUpdateTransform { it.copy(eyebrowShapeOverride = shape) }
                            },
                            label = { Text(shape.shortLabel) },
                            modifier = Modifier.testTag("dialog_brow_shape_${shape.name.lowercase()}")
                        )
                    }
                }

                // 1B. Eye Blink Control (keyframeable)
                Text("Eye Blink Control (Keyframeable)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = activeBlinkOverride == null,
                        onClick = {
                            onUpdateClip { it.copy(forceBlink = false) }
                            onUpdateTransform { it.copy(blinkOverride = null) }
                        },
                        label = { Text("Auto Blink") },
                        modifier = Modifier.testTag("blink_chip_auto")
                    )
                    FilterChip(
                        selected = activeBlinkOverride == false,
                        onClick = {
                            onUpdateClip { it.copy(forceBlink = false) }
                            onUpdateTransform { it.copy(blinkOverride = false) }
                        },
                        label = { Text("Eyes Open") },
                        modifier = Modifier.testTag("blink_chip_open")
                    )
                    FilterChip(
                        selected = activeBlinkOverride == true,
                        onClick = {
                            onUpdateClip { it.copy(forceBlink = true) }
                            onUpdateTransform { it.copy(blinkOverride = true) }
                        },
                        label = { Text("Closed / Blink") },
                        modifier = Modifier.testTag("blink_chip_closed")
                    )
                }

                // 1C. Hand Pose Swap (keyframeable)
                Text("Hand Gesture Swap (Keyframeable)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(HandPoseType.entries) { hand ->
                        FilterChip(
                            selected = activeHandPose == hand,
                            onClick = {
                                onUpdateClip { it.copy(handPose = hand) }
                                onUpdateTransform { it.copy(handPoseOverride = hand) }
                            },
                            label = { Text(hand.displayName) },
                            modifier = Modifier.testTag("hand_pose_chip_${hand.name.lowercase()}")
                        )
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 2. Hand & Head Prop Attachment System
                Text("Attach Prop to Hand or Head", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CharacterPropType.entries) { prop ->
                        FilterChip(
                            selected = clip.propType == prop,
                            onClick = {
                                if (prop == CharacterPropType.CUSTOM_PNG && clip.customPropImagePath.isNullOrBlank()) {
                                    onImportCustomPropPng { savedPath ->
                                        onUpdateClip { it.copy(propType = CharacterPropType.CUSTOM_PNG, customPropImagePath = savedPath) }
                                    }
                                } else {
                                    onUpdateClip { it.copy(propType = prop) }
                                }
                            },
                            label = { Text(prop.displayName) },
                            modifier = Modifier.testTag("prop_chip_${prop.name.lowercase()}")
                        )
                    }
                }

                if (clip.propType != CharacterPropType.NONE) {
                    Text("Attachment Socket", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PropAttachmentPoint.entries.forEach { socket ->
                            FilterChip(
                                selected = clip.propAttachment == socket,
                                onClick = { onUpdateClip { it.copy(propAttachment = socket) } },
                                label = { Text(socket.displayName) }
                            )
                        }
                    }

                    if (clip.propType == CharacterPropType.CUSTOM_PNG) {
                        OutlinedButton(
                            onClick = {
                                onImportCustomPropPng { savedPath ->
                                    onUpdateClip { it.copy(propType = CharacterPropType.CUSTOM_PNG, customPropImagePath = savedPath) }
                                }
                            },
                            border = BorderStroke(1.dp, ElectricCyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (clip.customPropImagePath == null) "Choose Custom Prop PNG" else "Change Custom Prop PNG", color = ElectricCyan)
                        }
                    }

                    LabeledSlider(
                        "Prop Size Scale",
                        clip.propScale,
                        0.4f..2.2f,
                        String.format(Locale.US, "%.2fx", clip.propScale)
                    ) { v ->
                        onUpdateClip { it.copy(propScale = v) }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Secondary Motion Sway Physics", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                            Text(
                                "Spring-pendulum physics for hand/head props & cape/scarf",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextMuted
                            )
                        }
                        Switch(
                            checked = clip.propSwayEnabled,
                            onCheckedChange = { sway -> onUpdateClip { it.copy(propSwayEnabled = sway) } },
                            modifier = Modifier.testTag("prop_sway_physics_switch")
                        )
                    }

                    if (clip.propSwayEnabled) {
                        LabeledSlider(
                            "Sway Physics Strength",
                            clip.propSwayStrength,
                            0.0f..2.0f,
                            "${(clip.propSwayStrength * 100).toInt()}%"
                        ) { v ->
                            onUpdateClip { it.copy(propSwayStrength = v) }
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 3. Direct Stage IK Limb & Head Pose Offsets at Current Keyframe
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Keyframe IK Limb & Head Pose", style = MaterialTheme.typography.labelLarge, color = AmberKeyframe)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (!isIkPosingMode) {
                                    onToggleIkPosingMode()
                                }
                                onDismiss()
                            },
                            border = BorderStroke(1.dp, ElectricCyan),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (isIkPosingMode) "Stage IK Active" else "Pose on Stage", color = ElectricCyan, style = MaterialTheme.typography.labelSmall)
                        }
                        if (pose.hasCustomOffsets) {
                            TextButton(
                                onClick = onResetPoseAtFrame,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text("Reset Pose", color = CoralPlayhead, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                LabeledSlider("Head Nod Angle", pose.headNodOffset, -55f..55f, "${pose.headNodOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(headNodOffset = v)) }
                }
                LabeledSlider("Torso Lean Angle", pose.torsoTiltOffset, -45f..45f, "${pose.torsoTiltOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(torsoTiltOffset = v)) }
                }
                LabeledSlider("Right Arm Shoulder", pose.rightArmOffset, -160f..160f, "${pose.rightArmOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(rightArmOffset = v)) }
                }
                LabeledSlider("Right Forearm Elbow", pose.rightForearmOffset, -150f..150f, "${pose.rightForearmOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(rightForearmOffset = v)) }
                }
                LabeledSlider("Left Arm Shoulder", pose.leftArmOffset, -160f..160f, "${pose.leftArmOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(leftArmOffset = v)) }
                }
                LabeledSlider("Left Forearm Elbow", pose.leftForearmOffset, -150f..150f, "${pose.leftForearmOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(leftForearmOffset = v)) }
                }
                LabeledSlider("Right Leg Hip", pose.rightLegOffset, -110f..110f, "${pose.rightLegOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(rightLegOffset = v)) }
                }
                LabeledSlider("Right Knee Bend", pose.rightKneeOffset, -135f..135f, "${pose.rightKneeOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(rightKneeOffset = v)) }
                }
                LabeledSlider("Left Leg Hip", pose.leftLegOffset, -110f..110f, "${pose.leftLegOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(leftLegOffset = v)) }
                }
                LabeledSlider("Left Knee Bend", pose.leftKneeOffset, -135f..135f, "${pose.leftKneeOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(leftKneeOffset = v)) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun CameraAndChromaKeyDialog(
    scene: AnimationScene,
    currentFrame: Int,
    isCameraKeyMode: Boolean,
    onSetChromaKeyMode: (ChromaKeyMode) -> Unit,
    onSetCameraFocusClip: (String?) -> Unit = {},
    onToggleCameraLayerEnabled: () -> Unit = {},
    onToggleCameraLayerLocked: () -> Unit = {},
    onSelectCameraKeyframe: (Int) -> Unit = {},
    onUpdateCameraEasing: (EasingType) -> Unit = {},
    onUpdateCamera: ((CameraKeyframe) -> CameraKeyframe) -> Unit,
    onDeleteCameraKeyframeAtFrame: () -> Unit,
    onResetAllCameraKeyframes: () -> Unit,
    onToggleCameraKeyMode: () -> Unit,
    onApplyCinematicPreset: (presetId: String, label: String, intensity: Float) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit
) {
    val evalCam = remember(scene, currentFrame) {
        KeyframeEvaluator.evaluateCamera(scene, currentFrame)
    }
    val activeCamKf = remember(scene.cameraKeyframes, currentFrame) {
        scene.cameraKeyframes.firstOrNull { it.frame == currentFrame }
    }
    val hasKfAtFrame = activeCamKf != null
    val activeCamEasing = activeCamKf?.easing
        ?: scene.cameraKeyframes.filter { it.frame <= currentFrame }.maxByOrNull { it.frame }?.easing
        ?: EasingType.EASE_IN_OUT
    val trackableClips = remember(scene.clips) {
        scene.clips.filter { it.characterId != null || it.imagePath != null || it.shapeKind != null }
    }
    var selectedCategory by remember { mutableStateOf(com.example.engine.CameraPresetCategory.ALL) }
    var presetIntensity by remember { mutableFloatStateOf(1.0f) }
    val filteredPresets = remember(selectedCategory) {
        if (selectedCategory == com.example.engine.CameraPresetCategory.ALL) {
            com.example.engine.CameraPresetEngine.presets
        } else {
            com.example.engine.CameraPresetEngine.presets.filter { it.category == selectedCategory }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = {
            Column {
                Text("Advanced Camera Layer & Chroma", color = StudioTextPrimary)
                Text(
                    "Frame ${currentFrame + 1} • ${scene.cameraKeyframes.size} Keyframe(s) • " +
                        (if (scene.cameraLayerEnabled) "Layer ON" else "Bypassed") +
                        (if (scene.cameraLayerLocked) " • Locked" else "") +
                        if (evalCam.trackedClipName != null) " • Tracking ${evalCam.trackedClipName}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberKeyframe
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 0. Camera Control Layer Enable / Lock Controls + Saved Keyframe Jumps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = scene.cameraLayerEnabled,
                        onClick = onToggleCameraLayerEnabled,
                        label = { Text(if (scene.cameraLayerEnabled) "Camera ON" else "Camera OFF") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (scene.cameraLayerEnabled) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = "Toggle camera control layer",
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        modifier = Modifier.testTag("dialog_camera_layer_enable_chip")
                    )
                    FilterChip(
                        selected = scene.cameraLayerLocked,
                        onClick = onToggleCameraLayerLocked,
                        label = { Text(if (scene.cameraLayerLocked) "Locked" else "Unlocked") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (scene.cameraLayerLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = "Toggle camera control layer lock",
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        modifier = Modifier.testTag("dialog_camera_layer_lock_chip")
                    )
                }

                if (scene.cameraKeyframes.isNotEmpty()) {
                    Text("Camera Keyframes (Tap to Jump)", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(scene.cameraKeyframes.sortedBy { it.frame }, key = { it.frame }) { kf ->
                            FilterChip(
                                selected = kf.frame == currentFrame,
                                onClick = { onSelectCameraKeyframe(kf.frame) },
                                label = {
                                    Text(
                                        "F${kf.frame + 1} • ${(kf.zoom * 100).toInt()}% • ${kf.rotation.toInt()}°",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag("dialog_camera_kf_jump_${kf.frame}")
                            )
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 1. Advanced Camera Control Presets (Categorized + Adjustable Intensity)
                Text("Advanced Camera Control Presets", style = MaterialTheme.typography.labelLarge, color = AmberKeyframe)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(com.example.engine.CameraPresetCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("camera_preset_cat_${cat.name.lowercase()}")
                        )
                    }
                }
                LabeledSlider(
                    "Preset Motion Intensity",
                    presetIntensity,
                    0.4f..1.8f,
                    String.format(Locale.US, "%.1fx", presetIntensity)
                ) { v ->
                    presetIntensity = v
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredPresets, key = { it.id }) { preset ->
                        Surface(
                            color = StudioCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AmberKeyframe.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .width(142.dp)
                                .clickable(enabled = !scene.cameraLayerLocked) {
                                    onApplyCinematicPreset(preset.id, preset.name, presetIntensity)
                                }
                                .testTag("camera_preset_${preset.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MovieFilter,
                                        contentDescription = preset.name,
                                        tint = AmberKeyframe,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = preset.shortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StudioTextPrimary,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextMuted,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 2. Stage Camera Keyframe Transform (Pan, Zoom, Rotation & Easing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Keyframed Pan / Zoom / Rotation", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                    OutlinedButton(
                        onClick = {
                            onToggleCameraKeyMode()
                            onDismiss()
                        },
                        border = BorderStroke(1.dp, AmberKeyframe),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.OpenWith, null, tint = AmberKeyframe, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isCameraKeyMode) "Stage Pan ON" else "Stage Camera HUD",
                            color = AmberKeyframe,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                LabeledSlider(
                    "Camera Zoom",
                    evalCam.zoom,
                    0.35f..3.5f,
                    "${(evalCam.zoom * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(zoom = v) }
                }
                LabeledSlider(
                    "Camera Pan X",
                    evalCam.x,
                    0.05f..0.95f,
                    "${(evalCam.x * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(x = v) }
                }
                LabeledSlider(
                    "Camera Pan Y",
                    evalCam.y,
                    0.05f..0.95f,
                    "${(evalCam.y * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(y = v) }
                }
                LabeledSlider(
                    "Camera Rotation / Roll",
                    evalCam.rotation,
                    -180f..180f,
                    "${evalCam.rotation.toInt()}°"
                ) { v ->
                    onUpdateCamera { it.copy(rotation = v) }
                }

                // Per-Keyframe Camera Easing Curve Selector
                Text("Keyframe Transition Easing", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                val camEasings = remember {
                    listOf(
                        EasingType.EASE_IN_OUT,
                        EasingType.LINEAR,
                        EasingType.EASE_IN,
                        EasingType.EASE_OUT,
                        EasingType.BOUNCE,
                        EasingType.ELASTIC,
                        EasingType.HOLD
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(camEasings) { easing ->
                        FilterChip(
                            selected = activeCamEasing == easing,
                            onClick = { onUpdateCameraEasing(easing) },
                            label = { Text(easing.shortLabel, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("camera_easing_${easing.name.lowercase()}")
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateCamera { it } },
                        enabled = !scene.cameraLayerLocked,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberKeyframe, contentColor = StudioObsidian),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("record_camera_kf_btn")
                    ) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (hasKfAtFrame) "Update Cam KF" else "Stamp Cam KF", fontWeight = FontWeight.Bold)
                    }
                    if (hasKfAtFrame) {
                        OutlinedButton(
                            onClick = onDeleteCameraKeyframeAtFrame,
                            enabled = !scene.cameraLayerLocked,
                            border = BorderStroke(1.dp, CoralPlayhead)
                        ) {
                            Text("Del KF", color = CoralPlayhead)
                        }
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 3. Camera Focus Tracking (Auto-Follow Character/Subject)
                Text("Camera Focus Tracking (Auto-Follow)", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = scene.cameraFocusClipId == null,
                            onClick = { onSetCameraFocusClip(null) },
                            label = { Text("Free Camera") },
                            modifier = Modifier.testTag("camera_focus_none_chip")
                        )
                    }
                    items(trackableClips, key = { it.id }) { clip ->
                        FilterChip(
                            selected = scene.cameraFocusClipId == clip.id,
                            onClick = { onSetCameraFocusClip(clip.id) },
                            label = { Text("Track: ${clip.name.take(18)}") },
                            modifier = Modifier.testTag("camera_focus_clip_${clip.id}")
                        )
                    }
                }

                HorizontalDivider(color = StudioBorderSubtle)

                // 4. Chroma Key Green / Blue Screen Background Mode
                Text("Chroma Key Compositing Background", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ChromaKeyMode.entries) { mode ->
                        FilterChip(
                            selected = scene.chromaKeyMode == mode,
                            onClick = { onSetChromaKeyMode(mode) },
                            label = { Text(mode.displayName) },
                            modifier = Modifier.testTag("chroma_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                if (scene.cameraKeyframes.isNotEmpty()) {
                    TextButton(
                        onClick = onResetAllCameraKeyframes,
                        enabled = !scene.cameraLayerLocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset All Camera Keyframes (100% Center View)", color = CoralPlayhead)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = StudioObsidian)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
            Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
        )
    }
}
