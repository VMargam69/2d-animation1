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
fun CharacterActingAndPropsDialog(
    clip: TimelineClip,
    currentFrame: Int,
    isIkPosingMode: Boolean,
    onUpdateClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onUpdateTransform: ((TransformKeyframe) -> TransformKeyframe) -> Unit,
    onResetPoseAtFrame: () -> Unit,
    onToggleIkPosingMode: () -> Unit,
    onImportCustomPropPng: ((String) -> Unit) -> Unit,
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
                Text("Expression, Hands, Blink & Props", color = StudioTextPrimary)
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
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Facial Expression Override (keyframeable)
                Text("Facial Expression Override (Keyframeable)", style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(FacialExpression.entries) { expr ->
                        FilterChip(
                            selected = activeExpression == expr,
                            onClick = {
                                onUpdateClip { it.copy(facialExpression = expr) }
                                onUpdateTransform { it.copy(expressionOverride = expr) }
                            },
                            label = { Text(expr.displayName) },
                            modifier = Modifier.testTag("expression_chip_${expr.name.lowercase()}")
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
                        Text("Secondary Motion Sway Physics", style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
                        Switch(
                            checked = clip.propSwayEnabled,
                            onCheckedChange = { sway -> onUpdateClip { it.copy(propSwayEnabled = sway) } }
                        )
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
                                onToggleIkPosingMode()
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
                LabeledSlider("Left Leg Hip", pose.leftLegOffset, -110f..110f, "${pose.leftLegOffset.toInt()}°") { v ->
                    onUpdateTransform { it.copy(poseOffsets = it.poseOffsets.copy(leftLegOffset = v)) }
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
    onUpdateCamera: ((CameraKeyframe) -> CameraKeyframe) -> Unit,
    onDeleteCameraKeyframeAtFrame: () -> Unit,
    onResetAllCameraKeyframes: () -> Unit,
    onToggleCameraKeyMode: () -> Unit,
    onDismiss: () -> Unit
) {
    val evalCam = remember(scene, currentFrame) {
        KeyframeEvaluator.evaluateCamera(scene, currentFrame)
    }
    val hasKfAtFrame = scene.cameraKeyframes.any { it.frame == currentFrame }
    val trackableClips = remember(scene.clips) {
        scene.clips.filter { it.characterId != null || it.imagePath != null || it.shapeKind != null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPanel,
        title = {
            Column {
                Text("Camera Keyframing & Chroma Key", color = StudioTextPrimary)
                Text(
                    "Frame ${currentFrame + 1} • ${scene.cameraKeyframes.size} Camera Keyframe(s)" +
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
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Chroma Key Green / Blue Screen Background Mode
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

                HorizontalDivider(color = StudioBorderSubtle)

                // 2. Camera Focus Tracking (Auto-Follow Character/Subject)
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

                // 3. Stage Camera Keyframe Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stage Camera Transform", style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
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
                            if (isCameraKeyMode) "Stage Pan ON" else "Pan Camera on Stage",
                            color = AmberKeyframe,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                LabeledSlider(
                    "Camera Zoom",
                    evalCam.zoom,
                    0.5f..2.5f,
                    "${(evalCam.zoom * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(zoom = v) }
                }
                LabeledSlider(
                    "Camera Focus X",
                    evalCam.x,
                    0.15f..0.85f,
                    "${(evalCam.x * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(x = v) }
                }
                LabeledSlider(
                    "Camera Focus Y",
                    evalCam.y,
                    0.15f..0.85f,
                    "${(evalCam.y * 100).toInt()}%"
                ) { v ->
                    onUpdateCamera { it.copy(y = v) }
                }
                LabeledSlider(
                    "Camera Roll / Dutch Tilt",
                    evalCam.rotation,
                    -45f..45f,
                    "${evalCam.rotation.toInt()}°"
                ) { v ->
                    onUpdateCamera { it.copy(rotation = v) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateCamera { it } },
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
                            border = BorderStroke(1.dp, CoralPlayhead)
                        ) {
                            Text("Del KF", color = CoralPlayhead)
                        }
                    }
                }

                if (scene.cameraKeyframes.isNotEmpty()) {
                    TextButton(
                        onClick = onResetAllCameraKeyframes,
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
