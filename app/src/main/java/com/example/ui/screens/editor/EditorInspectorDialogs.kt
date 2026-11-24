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
                Text("Transform & Keyframe", color = StudioTextPrimary)
                Text("${clip.name} • Frame ${currentFrame + 1}", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
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
                LabeledSlider("Scale", eval.scaleX, 0.3f..2.5f, String.format(Locale.US, "%.2fx", eval.scaleX)) { v ->
                    onUpdateTransform { it.copy(scaleX = v, scaleY = v) }
                }
                LabeledSlider("Opacity", eval.opacity, 0f..1f, "${(eval.opacity * 100).toInt()}%") { v ->
                    onUpdateTransform { it.copy(opacity = v) }
                }

                Text("Keyframe Interpolation", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EasingType.entries.forEach { easing ->
                        FilterChip(
                            selected = currentEasing == easing,
                            onClick = { onUpdateTransform { it.copy(easing = easing) } },
                            label = { Text(easing.displayName, style = MaterialTheme.typography.labelMedium) }
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
