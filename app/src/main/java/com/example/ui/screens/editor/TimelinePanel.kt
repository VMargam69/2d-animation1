package com.example.ui.screens.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Compact, high-density CapCut Pro-style Timeline Panel:
 * - Single unified 42dp Transport + Scene + Keyframe strip (merges the old two-row header).
 * - Collapsible left LAYERS rail (116dp full <-> 40dp icon-only rail) to maximize track width.
 * - Deduplicated frame-scrubbing & GPU-translated playhead for lag-free interaction.
 */
@Composable
fun TimelinePanel(
    project: ProjectDocument,
    currentFrame: Int,
    playbackFrameFlow: StateFlow<Int>,
    isPlaying: Boolean,
    isLooping: Boolean,
    selectedClipId: String?,
    selectedLayerId: String?,
    timelineZoom: Float,
    onSelectScene: (Int) -> Unit,
    onAddScene: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onToggleLoop: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onCycleSelectedKeyframeEasing: () -> Unit,
    onAdjustTimelineZoom: (Float) -> Unit,
    onSelectClip: (String) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onOpenLayerManager: () -> Unit,
    onTrimAudioClip: (
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ) -> Unit = { _, _, _, _, _ -> },
    waveformSensitivity: Float = 1.0f,
    onWaveformSensitivityChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    val sortedLayers = remember(scene.layers) { scene.layers.sortedByDescending { it.orderIndex } }
    val clipsByLayer = remember(scene.clips) { scene.clips.groupBy { it.layerId } }
    val selectedClip = remember(scene.clips, selectedClipId) { scene.clips.firstOrNull { it.id == selectedClipId } }
    val selectedKeyframe = selectedClip?.keyframes?.firstOrNull {
        it.frame == (currentFrame - selectedClip.startFrame)
    }
    val hasKeyframeHere = selectedKeyframe != null
    val activeEasingLabel = remember(selectedClip, currentFrame, selectedKeyframe) {
        val easing = selectedKeyframe?.easing
            ?: selectedClip?.keyframes
                ?.filter { it.frame <= (currentFrame - selectedClip.startFrame) }
                ?.maxByOrNull { it.frame }
                ?.easing
            ?: EasingType.LINEAR
        shortEasingLabel(easing)
    }

    val frameWidthDp = (10.dp * timelineZoom).coerceIn(6.dp, 24.dp)
    val density = LocalDensity.current
    val frameWidthPx = with(density) { frameWidthDp.toPx() }
    val totalTrackWidthDp = frameWidthDp * scene.durationFrames.coerceAtLeast(24)

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    var sceneDropdownOpen by remember { mutableStateOf(false) }
    var isLayerRailCollapsed by remember { mutableStateOf(false) }
    var showSensitivitySlider by remember { mutableStateOf(false) }
    val hasAudioTrack = remember(scene.layers, scene.clips) {
        scene.layers.any { it.type == LayerType.AUDIO } || scene.clips.any { !it.audioPath.isNullOrBlank() }
    }
    val layerRailWidth by animateDpAsState(
        targetValue = if (isLayerRailCollapsed) 40.dp else 116.dp,
        label = "timeline_layer_rail_width"
    )
    val latestOnSeekFrame by rememberUpdatedState(onSeekFrame)

    Surface(
        color = StudioCharcoal,
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_horizontal_timeline")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Unified Single Control Strip (~42dp): Scene Switcher | Transport + Timecode | Keyframe + Easing + Zoom
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(StudioCharcoal)
            ) {
                val stripMinWidth = maxWidth
                Row(
                    modifier = Modifier
                        .widthIn(min = stripMinWidth)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // LEFT: Compact Scene Dropdown Chip
                    Box {
                        Surface(
                            color = StudioPanel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StudioBorderSubtle),
                            modifier = Modifier
                                .height(28.dp)
                                .clickable { sceneDropdownOpen = true }
                                .testTag("scene_switcher_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = scene.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StudioTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 64.dp)
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = "Switch scene",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = sceneDropdownOpen,
                            onDismissRequest = { sceneDropdownOpen = false },
                            containerColor = StudioPanel
                        ) {
                            project.scenes.forEachIndexed { idx, sc ->
                                val isActive = idx == project.activeSceneIndex
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = sc.name,
                                            color = if (isActive) ElectricCyan else StudioTextPrimary,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isActive) Icons.Rounded.Check else Icons.Rounded.Movie,
                                            contentDescription = null,
                                            tint = if (isActive) ElectricCyan else StudioTextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        sceneDropdownOpen = false
                                        onSelectScene(idx)
                                    },
                                    modifier = Modifier.testTag("scene_tab_$idx")
                                )
                            }
                            HorizontalDivider(color = StudioBorderSubtle)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "+ Add Scene",
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Add scene",
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    sceneDropdownOpen = false
                                    onAddScene()
                                },
                                modifier = Modifier.testTag("add_scene_btn")
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // CENTER: Play/Pause + Live Compact Timecode + Loop Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        FilledIconButton(
                            onClick = onTogglePlayback,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isPlaying) CoralPlayhead else ElectricCyan,
                                contentColor = if (isPlaying) StudioTextPrimary else StudioObsidian
                            ),
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("timeline_play_pause_btn")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        LiveCompactTimecode(
                            playbackFrameFlow = playbackFrameFlow,
                            fps = project.fps
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onToggleLoop)
                                .testTag("timeline_loop_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Repeat,
                                contentDescription = "Toggle loop",
                                tint = if (isLooping) ElectricCyan else StudioTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // RIGHT: Smart Keyframe Diamond Toggle + Easing Pill + Timeline Zoom (- / +)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            color = if (hasKeyframeHere) AmberKeyframeDim else StudioPanel,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (hasKeyframeHere) AmberKeyframe else StudioBorderSubtle
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clickable(onClick = onToggleKeyframe)
                                .testTag("timeline_keyframe_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Diamond,
                                    contentDescription = if (hasKeyframeHere) "Delete keyframe" else "Add keyframe",
                                    tint = if (hasKeyframeHere) AmberKeyframe else StudioTextSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (hasKeyframeHere) "−" else "+",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasKeyframeHere) AmberKeyframe else StudioTextPrimary
                                )
                            }
                        }

                        Surface(
                            color = if (hasKeyframeHere) ElectricCyanDim else StudioPanel,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (hasKeyframeHere) ElectricCyan else StudioBorderSubtle
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clickable(enabled = selectedClip != null) {
                                    if (hasKeyframeHere) {
                                        onCycleSelectedKeyframeEasing()
                                    } else if (selectedClip != null) {
                                        val prevKf = selectedClip.keyframes
                                            .filter { it.frame <= (currentFrame - selectedClip.startFrame) }
                                            .maxByOrNull { it.frame }
                                            ?: selectedClip.keyframes.firstOrNull()
                                        if (prevKf != null) {
                                            onSelectKeyframeNode(selectedClip.id, selectedClip.startFrame + prevKf.frame)
                                        }
                                    }
                                }
                                .testTag("selected_keyframe_easing_btn")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = activeEasingLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedClip != null) StudioTextPrimary else StudioTextMuted,
                                    maxLines = 1
                                )
                            }
                        }

                        if (hasAudioTrack) {
                            Surface(
                                color = if (showSensitivitySlider || waveformSensitivity != 1.0f) {
                                    EmeraldLayer.copy(alpha = 0.2f)
                                } else {
                                    StudioPanel
                                },
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (showSensitivitySlider || waveformSensitivity != 1.0f) {
                                        EmeraldLayer
                                    } else {
                                        StudioBorderSubtle
                                    }
                                ),
                                modifier = Modifier
                                    .height(26.dp)
                                    .clickable { showSensitivitySlider = !showSensitivitySlider }
                                    .testTag("timeline_waveform_sensitivity_toggle")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = "Waveform sensitivity",
                                        tint = if (showSensitivitySlider || waveformSensitivity != 1.0f) EmeraldLayer else StudioTextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1fx", waveformSensitivity),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (showSensitivitySlider || waveformSensitivity != 1.0f) EmeraldLayer else StudioTextPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(StudioPanel)
                                .border(1.dp, StudioBorderSubtle, RoundedCornerShape(7.dp))
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp, 26.dp)
                                    .clickable { onAdjustTimelineZoom(-0.25f) }
                                    .testTag("timeline_zoom_out_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Remove,
                                    contentDescription = "Zoom out timeline",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            VerticalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp, 26.dp)
                                    .clickable { onAdjustTimelineZoom(0.25f) }
                                    .testTag("timeline_zoom_in_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Zoom in timeline",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (hasAudioTrack && showSensitivitySlider) {
                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(StudioPanel)
                        .padding(horizontal = 10.dp)
                        .testTag("timeline_waveform_sensitivity_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = EmeraldLayer,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Waveform Gain",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextSecondary
                    )
                    Slider(
                        value = waveformSensitivity.coerceIn(0.4f, 3.0f),
                        onValueChange = { raw ->
                            val snapped = (raw * 10f).toInt() / 10f
                            onWaveformSensitivityChange(snapped.coerceIn(0.4f, 3.0f))
                        },
                        valueRange = 0.4f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = EmeraldLayer,
                            activeTrackColor = EmeraldLayer,
                            inactiveTrackColor = StudioBorderSubtle
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("timeline_waveform_sensitivity_slider")
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", waveformSensitivity),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldLayer
                    )
                    Surface(
                        color = StudioCard,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .height(22.dp)
                            .clickable { onWaveformSensitivityChange(1.0f) }
                            .testTag("timeline_waveform_sensitivity_reset")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "1.0x",
                                fontSize = 10.sp,
                                color = StudioTextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

            // 2. Synchronized Collapsible Layer Rail & Horizontal Keyframe Track Area
            Row(modifier = Modifier.fillMaxSize()) {
                // Collapsible Left Layer Column (116dp full <-> 40dp icon-only rail)
                Column(
                    modifier = Modifier
                        .width(layerRailWidth)
                        .fillMaxHeight()
                        .background(StudioPanel)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(horizontal = if (isLayerRailCollapsed) 2.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isLayerRailCollapsed) Arrangement.Center else Arrangement.SpaceBetween
                    ) {
                        if (!isLayerRailCollapsed) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(onClick = onOpenLayerManager)
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LAYERS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StudioTextSecondary,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Rounded.AddCircleOutline,
                                    contentDescription = "Manage layers",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { isLayerRailCollapsed = !isLayerRailCollapsed }
                                .testTag("timeline_layers_collapse_btn")
                        ) {
                            Icon(
                                imageVector = if (isLayerRailCollapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft,
                                contentDescription = if (isLayerRailCollapsed) "Expand layers" else "Collapse layers",
                                tint = StudioTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll)
                    ) {
                        sortedLayers.forEach { layer ->
                            val layerColor = Color(ProceduralRigRenderer.parseColorSafe(layer.colorHex, 0xFF38BDF8.toInt()))
                            val isSelLayer = layer.id == selectedLayerId
                            val isAudioLayer = layer.type == LayerType.AUDIO
                            val rowHeight = layerRowHeight(layer.type)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .background(if (isSelLayer) StudioCard else Color.Transparent)
                                    .padding(horizontal = if (isLayerRailCollapsed) 4.dp else 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isLayerRailCollapsed) Arrangement.Center else Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(if (isAudioLayer) 28.dp else 20.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (layer.isVisible) layerColor else StudioTextMuted.copy(alpha = 0.4f))
                                )
                                if (!isLayerRailCollapsed) {
                                    Text(
                                        text = layer.name,
                                        fontSize = 11.sp,
                                        color = if (layer.isVisible) StudioTextPrimary else StudioTextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.width(3.dp))
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .clickable { onToggleLayerVisibility(layer.id) }
                                        .testTag("layer_vis_${layer.id}")
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isAudioLayer && layer.isVisible -> Icons.Rounded.VolumeUp
                                            isAudioLayer -> Icons.Rounded.VolumeOff
                                            layer.isVisible -> Icons.Rounded.Visibility
                                            else -> Icons.Rounded.VisibilityOff
                                        },
                                        contentDescription = if (isAudioLayer) {
                                            if (layer.isVisible) "Mute audio layer" else "Unmute audio layer"
                                        } else {
                                            "Toggle layer visibility"
                                        },
                                        tint = if (layer.isVisible) {
                                            if (isAudioLayer) EmeraldLayer else ElectricCyan
                                        } else {
                                            StudioTextMuted
                                        },
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                if (!isLayerRailCollapsed) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable { onToggleLayerLock(layer.id) }
                                            .testTag("layer_lock_${layer.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                            contentDescription = "Toggle layer lock",
                                            tint = if (layer.isLocked) AmberKeyframe else StudioTextMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.6f), thickness = 1.dp)
                        }
                    }
                }

                VerticalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Right Horizontally Scrollable Ruler + Tracks + Selectable Keyframe Nodes + Playhead
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScroll)
                ) {
                    Column(modifier = Modifier.width(totalTrackWidthDp)) {
                        // Frame Ruler (tap or smooth horizontal scrub playhead)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(StudioObsidian)
                                .pointerInput(scene.durationFrames, frameWidthPx) {
                                    detectTapGestures { pos ->
                                        val target = (pos.x / frameWidthPx).toInt().coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
                                        latestOnSeekFrame(target)
                                    }
                                }
                                .pointerInput(scene.durationFrames, frameWidthPx) {
                                    var lastSeekedFrame = -1
                                    detectHorizontalDragGestures(
                                        onDragStart = { startOffset ->
                                            val target = (startOffset.x / frameWidthPx).toInt()
                                                .coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
                                            lastSeekedFrame = target
                                            latestOnSeekFrame(target)
                                        },
                                        onDragEnd = { lastSeekedFrame = -1 },
                                        onDragCancel = { lastSeekedFrame = -1 }
                                    ) { change, _ ->
                                        change.consume()
                                        val target = (change.position.x / frameWidthPx).toInt()
                                            .coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
                                        if (target != lastSeekedFrame) {
                                            lastSeekedFrame = target
                                            latestOnSeekFrame(target)
                                        }
                                    }
                                }
                                .testTag("timeline_frame_ruler")
                        ) {
                            val rulerTextPaint = remember(density) {
                                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = 0xFF94A3B8.toInt()
                                    textSize = with(density) { 9.5.dp.toPx() }
                                }
                            }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val step = if (timelineZoom < 0.8f) 12 else 6
                                val scrollPx = horizontalScroll.value.toFloat()
                                val startF = ((scrollPx - 200f) / frameWidthPx).toInt().coerceAtLeast(0)
                                val endF = ((scrollPx + 1800f) / frameWidthPx).toInt().coerceAtMost(scene.durationFrames - 1)
                                for (f in startF..endF) {
                                    val x = f * frameWidthPx
                                    val isMajor = f % step == 0
                                    drawLine(
                                        color = if (isMajor) StudioTextSecondary else StudioBorderSubtle,
                                        start = Offset(x, if (isMajor) 8f else 17f),
                                        end = Offset(x, size.height),
                                        strokeWidth = if (isMajor) 1.5f else 1f
                                    )
                                }
                                drawIntoCanvas { composeCanvas ->
                                    val nc = composeCanvas.nativeCanvas
                                    val firstMajor = (startF / step) * step
                                    var f = firstMajor
                                    while (f <= endF) {
                                        if (f >= 0) {
                                            nc.drawText("${f + 1}", f * frameWidthPx + 4f, 12f, rulerTextPaint)
                                        }
                                        f += step
                                    }
                                }
                                // Amber camera keyframe diamonds on ruler
                                for (camKf in scene.cameraKeyframes) {
                                    if (camKf.frame in startF..endF) {
                                        val cx = camKf.frame * frameWidthPx
                                        rotate(45f, pivot = Offset(cx, size.height - 6f)) {
                                            drawRect(
                                                color = AmberKeyframe,
                                                topLeft = Offset(cx - 3.5f, size.height - 9.5f),
                                                size = Size(7f, 7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                        // Layer Tracks with Individually Selectable Keyframe Diamond Nodes
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScroll)
                        ) {
                            sortedLayers.forEach { layer ->
                                val layerColor = Color(ProceduralRigRenderer.parseColorSafe(layer.colorHex, 0xFF38BDF8.toInt()))
                                val layerClips = clipsByLayer[layer.id].orEmpty()
                                val rowHeight = layerRowHeight(layer.type)
                                val clipHeight = rowHeight - 6.dp

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .background(if (layer.isVisible) StudioCharcoal else StudioObsidian.copy(alpha = 0.72f))
                                ) {
                                    layerClips.forEach { clip ->
                                        val isSelected = clip.id == selectedClipId
                                        val isAudioClip = layer.type == LayerType.AUDIO || !clip.audioPath.isNullOrBlank()

                                        if (isAudioClip) {
                                            TrimmableAudioTimelineClip(
                                                clip = clip,
                                                fps = project.fps,
                                                sceneDurationFrames = scene.durationFrames,
                                                frameWidthDp = frameWidthDp,
                                                frameWidthPx = frameWidthPx,
                                                clipHeight = clipHeight,
                                                horizontalScrollPxProvider = { horizontalScroll.value.toFloat() },
                                                layerColor = layerColor,
                                                isLayerVisible = layer.isVisible,
                                                isLayerLocked = layer.isLocked,
                                                isSelected = isSelected,
                                                currentFrame = currentFrame,
                                                onSelectClip = onSelectClip,
                                                onSelectKeyframeNode = onSelectKeyframeNode,
                                                onTrimAudioClip = onTrimAudioClip,
                                                waveformSensitivity = waveformSensitivity
                                            )
                                        } else {
                                            val clipOffsetX = frameWidthDp * clip.startFrame
                                            val clipWidth = (frameWidthDp * clip.durationFrames).coerceAtLeast(32.dp)
                                            val visAlpha = if (layer.isVisible) 1f else 0.35f

                                            Box(
                                                modifier = Modifier
                                                    .offset(x = clipOffsetX, y = 3.dp)
                                                    .width(clipWidth)
                                                    .height(clipHeight)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (isSelected) layerColor.copy(alpha = 0.38f * visAlpha)
                                                        else layerColor.copy(alpha = 0.20f * visAlpha)
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 1.dp,
                                                        color = if (isSelected) ElectricCyan else layerColor.copy(alpha = 0.62f * visAlpha),
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable { onSelectClip(clip.id) }
                                                    .testTag("timeline_clip_${clip.id}")
                                            ) {
                                                Text(
                                                    text = clip.name,
                                                    fontSize = 10.sp,
                                                    color = StudioTextPrimary.copy(alpha = visAlpha),
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                                )

                                                // Individually Selectable Keyframe Diamond Nodes
                                                clip.keyframes.forEach { kf ->
                                                    val kfGlobalFrame = clip.startFrame + kf.frame
                                                    val isNodeSelected = isSelected && currentFrame == kfGlobalFrame
                                                    val rawX = frameWidthDp * kf.frame - 9.dp
                                                    val clampedX = rawX.coerceIn(0.dp, (clipWidth - 20.dp).coerceAtLeast(0.dp))

                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier
                                                            .offset(x = clampedX, y = 10.dp)
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .clickable {
                                                                onSelectKeyframeNode(clip.id, kfGlobalFrame)
                                                            }
                                                            .testTag("keyframe_node_${clip.id}_${kf.frame}")
                                                    ) {
                                                        Canvas(modifier = Modifier.size(11.dp)) {
                                                            val side = size.minDimension * 0.66f
                                                            val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                                                            rotate(45f) {
                                                                drawRect(
                                                                    color = if (isNodeSelected) ElectricCyan else AmberKeyframe.copy(alpha = visAlpha),
                                                                    topLeft = topLeft,
                                                                    size = Size(side, side)
                                                                )
                                                                if (isNodeSelected) {
                                                                    drawRect(
                                                                        color = Color.White,
                                                                        topLeft = topLeft,
                                                                        size = Size(side, side),
                                                                        style = Stroke(width = 2f)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.6f), thickness = 1.dp)
                            }
                        }
                    }

                    // GPU-accelerated Playhead Indicator Line & Draggable Horizontal Scrub Handle
                    LivePlayheadOverlay(
                        playbackFrameFlow = playbackFrameFlow,
                        frameWidthPx = frameWidthPx,
                        maxFrame = (scene.durationFrames - 1).coerceAtLeast(0),
                        onSeekFrame = latestOnSeekFrame
                    )
                }
            }
        }
    }
}

private fun layerRowHeight(type: LayerType): Dp =
    if (type == LayerType.AUDIO) 52.dp else 36.dp

private fun shortEasingLabel(easing: EasingType): String = when (easing) {
    EasingType.LINEAR -> "Linear"
    EasingType.EASE_IN_OUT -> "Ease"
    EasingType.HOLD -> "Hold"
}

@Composable
private fun LiveCompactTimecode(
    playbackFrameFlow: StateFlow<Int>,
    fps: Int
) {
    val frame by playbackFrameFlow.collectAsState()
    val seconds = frame.toFloat() / fps.coerceAtLeast(1).toFloat()
    val timecode = String.format(Locale.US, "%02d:%04.1f", (seconds / 60).toInt(), seconds % 60f)
    Text(
        text = "$timecode • F${frame + 1}",
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = ElectricCyan,
        maxLines = 1
    )
}

@Composable
private fun LivePlayheadOverlay(
    playbackFrameFlow: StateFlow<Int>,
    frameWidthPx: Float,
    maxFrame: Int,
    onSeekFrame: (Int) -> Unit
) {
    val liveFrameState = playbackFrameFlow.collectAsState()
    val latestSeek by rememberUpdatedState(onSeekFrame)
    val handleWidthDp = 24.dp
    val halfWidthPx = with(LocalDensity.current) { (handleWidthDp / 2).toPx() }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = liveFrameState.value * frameWidthPx - halfWidthPx
            }
            .width(handleWidthDp)
            .fillMaxHeight()
    ) {
        // Draggable horizontal scrub head (smoothly updates current frame & stage canvas)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 24.dp, height = 28.dp)
                .pointerInput(frameWidthPx, maxFrame) {
                    var startFrame = 0
                    var dragAccumPx = 0f
                    var lastEmitted = -1
                    detectHorizontalDragGestures(
                        onDragStart = {
                            startFrame = liveFrameState.value
                            dragAccumPx = 0f
                            lastEmitted = startFrame
                        },
                        onDragEnd = { dragAccumPx = 0f },
                        onDragCancel = { dragAccumPx = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccumPx += dragAmount
                        val deltaFrames = (dragAccumPx / frameWidthPx.coerceAtLeast(1f)).toInt()
                        val target = (startFrame + deltaFrames).coerceIn(0, maxFrame)
                        if (target != lastEmitted) {
                            lastEmitted = target
                            latestSeek(target)
                        }
                    }
                }
                .testTag("timeline_playhead_scrub_handle")
        ) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CoralPlayhead)
                    .border(1.2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            )
        }

        // Crisp vertical playhead needle
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(CoralPlayhead)
        )
    }
}
