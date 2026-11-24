package com.example.ui.screens.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.ProceduralRigRenderer
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Bottom-aligned horizontal timeline displaying animation layers with 1-tap visibility/lock
 * toggles and individually selectable keyframe diamond nodes with quick easing/navigation actions.
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
    onStepFrame: (Int) -> Unit,
    onSeekFrame: (Int) -> Unit,
    onToggleLoop: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onCycleSelectedKeyframeEasing: () -> Unit,
    onOpenTransformForKeyframe: () -> Unit,
    onAdjustTimelineZoom: (Float) -> Unit,
    onSelectClip: (String) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onOpenLayerManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = project.activeScene
    val sortedLayers = scene.layers.sortedByDescending { it.orderIndex }
    val selectedClip = scene.clips.firstOrNull { it.id == selectedClipId }
    val selectedKeyframe = selectedClip?.keyframes?.firstOrNull {
        it.frame == (currentFrame - selectedClip.startFrame)
    }
    val hasKeyframeHere = selectedKeyframe != null

    val frameWidthDp = (10.dp * timelineZoom).coerceIn(6.dp, 24.dp)
    val density = LocalDensity.current
    val frameWidthPx = with(density) { frameWidthDp.toPx() }
    val totalTrackWidthDp = frameWidthDp * scene.durationFrames.coerceAtLeast(24)

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    Surface(
        color = StudioCharcoal,
        border = BorderStroke(1.dp, StudioBorderSubtle),
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_horizontal_timeline")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Scene Tabs & Timecode Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioPanel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(project.scenes) { idx, sc ->
                        val active = idx == project.activeSceneIndex
                        Surface(
                            color = if (active) ElectricCyanDim else StudioCard,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, if (active) ElectricCyan else StudioBorderSubtle),
                            modifier = Modifier
                                .clickable { onSelectScene(idx) }
                                .testTag("scene_tab_$idx")
                        ) {
                            Text(
                                text = sc.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) StudioTextPrimary else StudioTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item {
                        IconButton(
                            onClick = onAddScene,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("add_scene_btn")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add scene", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                LiveTimecodeBadge(
                    playbackFrameFlow = playbackFrameFlow,
                    fps = project.fps,
                    durationFrames = scene.durationFrames
                )
            }

            // 2. Transport Controls + Selected Keyframe Node Inspector Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioCharcoal)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = { onSeekFrame(0) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("timeline_jump_start_btn")
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Jump to start", tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { onStepFrame(-1) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("timeline_prev_frame_btn")
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous frame", tint = StudioTextPrimary, modifier = Modifier.size(19.dp))
                    }
                    FilledIconButton(
                        onClick = onTogglePlayback,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isPlaying) CoralPlayhead else ElectricCyan,
                            contentColor = if (isPlaying) StudioTextPrimary else StudioObsidian
                        ),
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("timeline_play_pause_btn")
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = { onStepFrame(1) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("timeline_next_frame_btn")
                    ) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Next frame", tint = StudioTextPrimary, modifier = Modifier.size(19.dp))
                    }
                    IconButton(
                        onClick = onToggleLoop,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("timeline_loop_btn")
                    ) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "Toggle loop",
                            tint = if (isLooping) EmeraldLayer else StudioTextMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Right side: Keyframe Node Actions (Easing cycler when on a node + Add/Remove Key + Zoom)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (selectedKeyframe != null) {
                        Surface(
                            color = ElectricCyanDim,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ElectricCyan),
                            modifier = Modifier
                                .clickable(onClick = onCycleSelectedKeyframeEasing)
                                .testTag("selected_keyframe_easing_btn")
                        ) {
                            Text(
                                text = selectedKeyframe.easing.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenTransformForKeyframe,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Edit keyframe transform", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        }
                    }

                    Surface(
                        color = if (hasKeyframeHere) AmberKeyframeDim else StudioPanel,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (hasKeyframeHere) AmberKeyframe else StudioBorder),
                        modifier = Modifier
                            .clickable(onClick = onToggleKeyframe)
                            .testTag("timeline_keyframe_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Diamond,
                                contentDescription = "Toggle keyframe",
                                tint = if (hasKeyframeHere) AmberKeyframe else StudioTextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (hasKeyframeHere) "Del KF" else "+ Key",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasKeyframeHere) AmberKeyframe else StudioTextPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = { onAdjustTimelineZoom(-0.25f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Zoom out timeline", tint = StudioTextSecondary, modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = { onAdjustTimelineZoom(0.25f) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Zoom in timeline", tint = StudioTextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
            }

            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

            // 3. Synchronized Layer Visibility Column & Horizontal Keyframe Track Area
            Row(modifier = Modifier.fillMaxSize()) {
                // Fixed Left Column: Layers with Prominent Visibility Eye & Lock Controls
                Column(
                    modifier = Modifier
                        .width(124.dp)
                        .fillMaxHeight()
                        .background(StudioPanel)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clickable(onClick = onOpenLayerManager)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("LAYERS", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                        Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Manage layers", tint = ElectricCyan, modifier = Modifier.size(14.dp))
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .background(if (isSelLayer) StudioCard else Color.Transparent)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(22.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (layer.isVisible) layerColor else StudioTextMuted.copy(alpha = 0.4f))
                                )
                                Text(
                                    text = layer.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (layer.isVisible) StudioTextPrimary else StudioTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onToggleLayerVisibility(layer.id) },
                                    modifier = Modifier
                                        .size(26.dp)
                                        .testTag("layer_vis_${layer.id}")
                                ) {
                                    Icon(
                                        if (layer.isVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = "Toggle layer visibility",
                                        tint = if (layer.isVisible) ElectricCyan else StudioTextMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onToggleLayerLock(layer.id) },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .testTag("layer_lock_${layer.id}")
                                ) {
                                    Icon(
                                        if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = "Toggle layer lock",
                                        tint = if (layer.isLocked) AmberKeyframe else StudioTextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.6f), thickness = 1.dp)
                        }
                    }
                }

                VerticalDivider(color = StudioBorder, thickness = 1.dp)

                // Right Horizontally Scrollable Ruler + Tracks + Selectable Keyframe Nodes + Playhead
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScroll)
                ) {
                    Column(modifier = Modifier.width(totalTrackWidthDp)) {
                        // Frame Ruler (tap or scrub playhead)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .background(StudioObsidian)
                                .pointerInput(scene.durationFrames, frameWidthPx) {
                                    detectTapGestures { pos ->
                                        onSeekFrame((pos.x / frameWidthPx).toInt())
                                    }
                                }
                                .pointerInput(scene.durationFrames, frameWidthPx) {
                                    detectHorizontalDragGestures { change, _ ->
                                        change.consume()
                                        onSeekFrame((change.position.x / frameWidthPx).toInt())
                                    }
                                }
                                .testTag("timeline_frame_ruler")
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val step = if (timelineZoom < 0.8f) 12 else 6
                                for (f in 0 until scene.durationFrames) {
                                    val x = f * frameWidthPx
                                    val isMajor = f % step == 0
                                    drawLine(
                                        color = if (isMajor) StudioTextSecondary else StudioBorderSubtle,
                                        start = Offset(x, if (isMajor) 8f else 17f),
                                        end = Offset(x, size.height),
                                        strokeWidth = if (isMajor) 1.5f else 1f
                                    )
                                }
                            }
                            val labelStep = if (timelineZoom < 0.8f) 12 else 6
                            for (f in 0 until scene.durationFrames step labelStep) {
                                Text(
                                    text = "${f + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextMuted,
                                    modifier = Modifier.offset(x = frameWidthDp * f + 3.dp, y = 2.dp)
                                )
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
                                val layerClips = scene.clips.filter { it.layerId == layer.id }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .background(if (layer.isVisible) StudioCharcoal else StudioObsidian.copy(alpha = 0.72f))
                                ) {
                                    layerClips.forEach { clip ->
                                        val isSelected = clip.id == selectedClipId
                                        val clipOffsetX = frameWidthDp * clip.startFrame
                                        val clipWidth = (frameWidthDp * clip.durationFrames).coerceAtLeast(32.dp)
                                        val visAlpha = if (layer.isVisible) 1f else 0.35f

                                        Box(
                                            modifier = Modifier
                                                .offset(x = clipOffsetX, y = 3.dp)
                                                .width(clipWidth)
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isSelected) layerColor.copy(alpha = 0.38f * visAlpha)
                                                    else layerColor.copy(alpha = 0.20f * visAlpha)
                                                )
                                                .border(
                                                    width = if (isSelected) 1.6.dp else 1.dp,
                                                    color = if (isSelected) ElectricCyan else layerColor.copy(alpha = 0.65f * visAlpha),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable { onSelectClip(clip.id) }
                                                .testTag("timeline_clip_${clip.id}")
                                        ) {
                                            Text(
                                                text = clip.name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextPrimary.copy(alpha = visAlpha),
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
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
                                                        .offset(x = clampedX, y = 11.dp)
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            onSelectKeyframeNode(clip.id, kfGlobalFrame)
                                                        }
                                                        .testTag("keyframe_node_${clip.id}_${kf.frame}")
                                                ) {
                                                    Canvas(modifier = Modifier.size(12.dp)) {
                                                        val dPath = Path().apply {
                                                            moveTo(size.width / 2f, 0f)
                                                            lineTo(size.width, size.height / 2f)
                                                            lineTo(size.width / 2f, size.height)
                                                            lineTo(0f, size.height / 2f)
                                                            close()
                                                        }
                                                        drawPath(
                                                            dPath,
                                                            color = if (isNodeSelected) ElectricCyan else AmberKeyframe.copy(alpha = visAlpha)
                                                        )
                                                        if (isNodeSelected) {
                                                            drawPath(
                                                                dPath,
                                                                color = Color.White,
                                                                style = Stroke(width = 2f)
                                                            )
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

                    // GPU-accelerated Playhead Indicator Line & Handle (zero recomposition during playback!)
                    LivePlayheadOverlay(
                        playbackFrameFlow = playbackFrameFlow,
                        frameWidthPx = frameWidthPx
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTimecodeBadge(
    playbackFrameFlow: StateFlow<Int>,
    fps: Int,
    durationFrames: Int
) {
    val frame by playbackFrameFlow.collectAsState()
    val seconds = frame.toFloat() / fps.coerceAtLeast(1).toFloat()
    val timecode = String.format(Locale.US, "%02d:%04.1f", (seconds / 60).toInt(), seconds % 60f)
    Text(
        text = "$timecode • F${frame + 1}/$durationFrames",
        style = MaterialTheme.typography.labelSmall,
        color = ElectricCyan
    )
}

@Composable
private fun LivePlayheadOverlay(
    playbackFrameFlow: StateFlow<Int>,
    frameWidthPx: Float
) {
    val liveFrameState = playbackFrameFlow.collectAsState()
    val halfWidthPx = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = liveFrameState.value * frameWidthPx - halfWidthPx
            }
            .width(12.dp)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(11.dp)
                .clip(CircleShape)
                .background(CoralPlayhead)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(CoralPlayhead)
        )
    }
}
