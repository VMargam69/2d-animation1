package com.example.ui.screens.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.FacialExpressionEngine
import com.example.engine.FacialExpressionPreset
import com.example.engine.ProceduralRigRenderer
import com.example.engine.SnapDragMode
import com.example.engine.TimelineNleEngine
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    playbackSpeed: Float = 1.0f,
    selectedClipId: String?,
    selectedLayerId: String?,
    timelineZoom: Float,
    onSelectScene: (Int) -> Unit,
    onAddScene: () -> Unit,
    onDuplicateScene: () -> Unit = {},
    onTogglePlayback: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onToggleLoop: () -> Unit,
    onCyclePlaybackSpeed: () -> Unit = {},
    onToggleKeyframe: () -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onCycleSelectedKeyframeEasing: () -> Unit,
    onOpenEasingEditor: () -> Unit = onCycleSelectedKeyframeEasing,
    onAdjustTimelineZoom: (Float) -> Unit,
    onSelectClip: (String) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onBringLayerToFront: (String) -> Unit = {},
    onSendLayerToBack: (String) -> Unit = {},
    onBringLayerForward: (String) -> Unit = {},
    onSendLayerBackward: (String) -> Unit = {},
    onDuplicateLayer: (String) -> Unit = {},
    onDeleteLayer: (String) -> Unit = {},
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
    onDropFacialExpression: (
        layerId: String,
        clipId: String?,
        globalFrame: Int,
        presetId: String
    ) -> Unit = { _, _, _, _ -> },
    onSetFacialShapeOverride: (
        mouth: MouthShapeOverride?,
        eye: EyeShapeOverride?,
        eyebrow: EyebrowShapeOverride?
    ) -> Unit = { _, _, _ -> },
    onClearFacialShapeOverrides: () -> Unit = {},
    onToggleCameraLayerEnabled: () -> Unit = {},
    onToggleCameraLayerLocked: () -> Unit = {},
    onSelectCameraKeyframe: (frame: Int) -> Unit = {},
    onStampCameraKeyframe: () -> Unit = {},
    onOpenCameraStudio: () -> Unit = {},
    isCameraKeyMode: Boolean = false,
    selectedCameraBoxId: String? = null,
    onAddCameraBox: () -> Unit = {},
    onSelectCameraBox: (String?) -> Unit = {},
    onTrimOrMoveCameraBox: (boxId: String, newStartFrame: Int, newEndFrame: Int) -> Unit = { _, _, _ -> },
    onDuplicateCameraBox: (String) -> Unit = {},
    onSplitCameraBox: (String) -> Unit = {},
    onDeleteCameraBox: (String) -> Unit = {},
    onDeleteCameraBoxRipple: (boxId: String, ripple: Boolean) -> Unit = { id, _ -> onDeleteCameraBox(id) },
    onTrimCameraBoxToPlayhead: (String) -> Unit = {},
    onCycleCameraBoxEasing: (String) -> Unit = {},
    onSetTimelineZoom: (Float) -> Unit = {},
    onToggleClipHoldCut: (String) -> Unit = {},
    onSplitClip: (String) -> Unit = {},
    onDuplicateClip: (String) -> Unit = {},
    onTrimClipToPlayhead: (String) -> Unit = {},
    onDeleteClip: (clipId: String, ripple: Boolean) -> Unit = { _, _ -> },
    onTrimOrMoveClip: (clipId: String, newStartFrame: Int, newDurationFrames: Int, isMoveOnly: Boolean) -> Unit = { _, _, _, _ -> },
    onMoveClipAcrossTracks: (
        clipId: String,
        targetLayerId: String?,
        targetStartFrame: Int,
        createNewTrack: Boolean,
        insertAtTop: Boolean
    ) -> Unit = { _, _, _, _, _ -> },
    isMultiSelectMode: Boolean = false,
    selectedClipIds: Set<String> = emptySet(),
    onToggleMultiSelectMode: () -> Unit = {},
    onToggleClipMultiSelect: (String) -> Unit = {},
    onSelectAllClips: () -> Unit = {},
    onDeleteSelectedClips: () -> Unit = {},
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
    var showFacialTray by remember { mutableStateOf(true) }
    var showBulkDeleteClipsConfirm by remember { mutableStateOf(false) }
    var openLayerMenuId by remember { mutableStateOf<String?>(null) }
    val hasAudioTrack = remember(scene.layers, scene.clips) {
        scene.layers.any { it.type == LayerType.AUDIO } || scene.clips.any { !it.audioPath.isNullOrBlank() }
    }
    val hasCharacterLayer = remember(scene.layers, scene.clips) {
        scene.layers.any { it.type == LayerType.CHARACTER && !it.isLocked } &&
            scene.clips.any { it.characterId != null }
    }
    val activeCharClip = remember(selectedClip, scene.clips, scene.layers) {
        selectedClip?.takeIf { it.characterId != null }
            ?: scene.clips.firstOrNull { clip ->
                clip.characterId != null &&
                    scene.layers.any { l -> l.id == clip.layerId && l.type == LayerType.CHARACTER && !l.isLocked }
            }
    }
    val activeCharEval = remember(activeCharClip, currentFrame) {
        activeCharClip?.let { com.example.engine.KeyframeEvaluator.evaluate(it, currentFrame) }
    }
    val activeFacialPresetId = remember(activeCharClip, currentFrame) {
        if (activeCharClip?.characterId == null) null
        else {
            val localFrame = (currentFrame - activeCharClip.startFrame).coerceAtLeast(0)
            activeCharClip.keyframes
                .filter { it.frame <= localFrame && (it.facialPresetId != null || it.expressionOverride != null) }
                .maxByOrNull { it.frame }
                ?.let { kf ->
                    kf.facialPresetId ?: kf.expressionOverride?.let {
                        FacialExpressionEngine.presetForExpression(it).id
                    }
                }
        }
    }
    val activeMouthOverride = activeCharEval?.mouthShapeOverride
        ?: activeCharClip?.mouthShapeOverride
        ?: MouthShapeOverride.AUTO
    val activeEyeOverride = activeCharEval?.eyeShapeOverride
        ?: activeCharClip?.eyeShapeOverride
        ?: EyeShapeOverride.AUTO
    val activeEyebrowOverride = activeCharEval?.eyebrowShapeOverride
        ?: activeCharClip?.eyebrowShapeOverride
        ?: EyebrowShapeOverride.AUTO
    val evaluatedCamHere = remember(scene, currentFrame) {
        com.example.engine.KeyframeEvaluator.evaluateCamera(scene, currentFrame)
    }

    // Drag-and-drop state for facial expressions AND universal cross-track clip drag-and-drop
    var timelineRootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var trackViewportRootLeftPx by remember { mutableFloatStateOf(0f) }
    var trackViewportWidthPx by remember { mutableFloatStateOf(0f) }
    val layerRowBoundsY = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    var newTrackTopBoundsY by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var newTrackBottomBoundsY by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var draggingPreset by remember { mutableStateOf<FacialExpressionPreset?>(null) }
    var dragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    var activeClipDrag by remember { mutableStateOf<ActiveClipDragState?>(null) }
    var trimSnapGuideFrame by remember { mutableStateOf<Int?>(null) }

    val snapTolerancePx = with(density) { TimelineNleEngine.DEFAULT_SNAP_TOLERANCE_DP.dp.toPx() }
    val edgeAutoScrollZonePx = with(density) { 44.dp.toPx() }
    val majorMarkerStep = if (timelineZoom < 0.8f) 12 else 6

    val latestTimelineZoom by rememberUpdatedState(timelineZoom)
    val latestOnSetTimelineZoom by rememberUpdatedState(onSetTimelineZoom)
    val latestOnAdjustTimelineZoom by rememberUpdatedState(onAdjustTimelineZoom)
    val latestOnMoveClipAcrossTracks by rememberUpdatedState(onMoveClipAcrossTracks)

    // Derived live target & magnetic snap preview while a timeline clip is being long-press dragged
    data class ClipDragPreview(
        val snappedStartFrame: Int,
        val snappedGuideFrame: Int?,
        val hoveredLayer: AnimationLayer?,
        val hoveredNewTrackTop: Boolean,
        val hoveredNewTrackBottom: Boolean,
        val isCompatible: Boolean,
        val destinationLabel: String
    )

    val clipDragPreview = remember(
        activeClipDrag, sortedLayers, clipsByLayer, scene, currentFrame,
        horizontalScroll.value, frameWidthPx, snapTolerancePx, majorMarkerStep,
        newTrackTopBoundsY, newTrackBottomBoundsY
    ) {
        val drag = activeClipDrag ?: return@remember null
        val dxPx = (drag.currentPointerRoot.x - drag.initialPointerRoot.x) +
            (horizontalScroll.value.toFloat() - drag.initialScrollPx)
        val deltaFrames = (dxPx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
        val rawStart = (drag.initialStartFrame + deltaFrames).coerceAtLeast(0)
        val targets = TimelineNleEngine.collectSnapTargets(
            scene = scene,
            playheadFrame = currentFrame,
            majorMarkerStep = majorMarkerStep,
            excludeClipId = drag.clip.id
        )
        val snap = TimelineNleEngine.computeMagneticSnap(
            candidateStartFrame = rawStart,
            candidateEndFrame = rawStart + drag.clip.durationFrames,
            mode = SnapDragMode.MOVE,
            snapTargets = targets,
            frameWidthPx = frameWidthPx,
            snapTolerancePx = snapTolerancePx
        )

        val py = drag.currentPointerRoot.y
        val inTopNew = newTrackTopBoundsY?.let { py in (it.first - 6f)..(it.second + 6f) } == true
        val inBottomNew = !inTopNew && newTrackBottomBoundsY?.let { py in (it.first - 6f)..(it.second + 6f) } == true
        val matchedLayer = if (!inTopNew && !inBottomNew) {
            sortedLayers.firstOrNull { l ->
                val b = layerRowBoundsY[l.id] ?: return@firstOrNull false
                py in (b.first - 4f)..(b.second + 4f)
            }
        } else null

        val isCompat = when {
            inTopNew || inBottomNew -> true
            matchedLayer == null -> true
            matchedLayer.id == drag.sourceLayer.id -> !matchedLayer.isLocked
            matchedLayer.isLocked -> false
            else -> {
                val otherClipsExist = clipsByLayer[matchedLayer.id].orEmpty().any { it.id != drag.clip.id }
                TimelineNleEngine.areLayerTypesCompatible(
                    sourceType = drag.sourceLayer.type,
                    targetType = matchedLayer.type,
                    targetHasClips = otherClipsExist
                )
            }
        }
        val destLabel = when {
            inTopNew -> "+ New ${drag.sourceLayer.type.displayName} Track (Top)"
            inBottomNew -> "+ New ${drag.sourceLayer.type.displayName} Track (Bottom)"
            matchedLayer == null -> "Track: ${drag.sourceLayer.name}"
            matchedLayer.isLocked -> "${matchedLayer.name} (Locked)"
            !isCompat -> "Incompatible track (${matchedLayer.type.displayName})"
            matchedLayer.id == drag.sourceLayer.id -> "Track: ${matchedLayer.name}"
            else -> "Move -> ${matchedLayer.name}"
        }
        ClipDragPreview(
            snappedStartFrame = snap.snappedStartFrame,
            snappedGuideFrame = snap.snappedGuideFrame,
            hoveredLayer = matchedLayer,
            hoveredNewTrackTop = inTopNew,
            hoveredNewTrackBottom = inBottomNew,
            isCompatible = isCompat,
            destinationLabel = destLabel
        )
    }

    val effectiveSnapGuideFrame = clipDragPreview?.snappedGuideFrame ?: trimSnapGuideFrame

    // Gentle horizontal edge auto-scroll while dragging any clip near left/right viewport edge
    LaunchedEffect(activeClipDrag != null) {
        while (activeClipDrag != null) {
            val drag = activeClipDrag ?: break
            if (trackViewportWidthPx > edgeAutoScrollZonePx * 2.5f) {
                val relX = drag.currentPointerRoot.x - trackViewportRootLeftPx
                when {
                    relX < edgeAutoScrollZonePx -> {
                        val intensity = ((edgeAutoScrollZonePx - relX) / edgeAutoScrollZonePx).coerceIn(0.2f, 1.2f)
                        val step = (14f * intensity).roundToInt().coerceAtLeast(3)
                        val next = (horizontalScroll.value - step).coerceAtLeast(0)
                        if (next != horizontalScroll.value) {
                            horizontalScroll.scrollTo(next)
                        }
                    }
                    relX > trackViewportWidthPx - edgeAutoScrollZonePx -> {
                        val intensity = ((relX - (trackViewportWidthPx - edgeAutoScrollZonePx)) / edgeAutoScrollZonePx)
                            .coerceIn(0.2f, 1.2f)
                        val step = (14f * intensity).roundToInt().coerceAtLeast(3)
                        val next = (horizontalScroll.value + step).coerceAtMost(horizontalScroll.maxValue)
                        if (next != horizontalScroll.value) {
                            horizontalScroll.scrollTo(next)
                        }
                    }
                }
            }
            delay(16L)
        }
    }

    val hoveredDropTarget = remember(
        draggingPreset, dragPointerRoot, sortedLayers, clipsByLayer,
        trackViewportRootLeftPx, horizontalScroll.value, frameWidthPx, scene.durationFrames, currentFrame
    ) {
        if (draggingPreset == null) return@remember null
        val py = dragPointerRoot.y
        val px = dragPointerRoot.x
        val matchedLayer = sortedLayers.firstOrNull { layer ->
            if (layer.type != LayerType.CHARACTER || layer.isLocked) return@firstOrNull false
            val bounds = layerRowBoundsY[layer.id] ?: return@firstOrNull false
            py in (bounds.first - 6f)..(bounds.second + 6f)
        } ?: return@remember null
        val targetFrame = if (px >= trackViewportRootLeftPx && frameWidthPx > 0.5f) {
            val trackX = (px - trackViewportRootLeftPx) + horizontalScroll.value.toFloat()
            (trackX / frameWidthPx).toInt().coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
        } else {
            currentFrame.coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
        }
        val layerClips = clipsByLayer[matchedLayer.id].orEmpty().filter { it.characterId != null }
        val targetClip = layerClips.firstOrNull { targetFrame in it.startFrame until it.endFrame }
            ?: layerClips.firstOrNull()
        if (targetClip == null) null else Triple(matchedLayer, targetClip, targetFrame)
    }

    val layerRailWidth by animateDpAsState(
        targetValue = if (isLayerRailCollapsed) 66.dp else 146.dp,
        label = "timeline_layer_rail_width"
    )
    val latestOnSeekFrame by rememberUpdatedState(onSeekFrame)
    val latestOnDropFacial by rememberUpdatedState(onDropFacialExpression)

    Surface(
        color = StudioCharcoal,
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_horizontal_timeline")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    timelineRootTopLeft = coords.positionInRoot()
                }
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
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Duplicate Scene",
                                        color = AmberKeyframe,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.FileCopy,
                                        contentDescription = "Duplicate scene",
                                        tint = AmberKeyframe,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    sceneDropdownOpen = false
                                    onDuplicateScene()
                                },
                                modifier = Modifier.testTag("duplicate_scene_btn")
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

                        // Variable Playback Speed Pill (0.25x .. 2x)
                        val isCustomSpeed = kotlin.math.abs(playbackSpeed - 1.0f) > 0.05f
                        val speedLabel = if (playbackSpeed == playbackSpeed.toInt().toFloat()) {
                            "${playbackSpeed.toInt()}x"
                        } else {
                            "${playbackSpeed}x"
                        }
                        Surface(
                            color = if (isCustomSpeed) AmberKeyframeDim else StudioPanel,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isCustomSpeed) AmberKeyframe else StudioBorderSubtle
                            ),
                            modifier = Modifier
                                .height(24.dp)
                                .clickable(onClick = onCyclePlaybackSpeed)
                                .testTag("timeline_playback_speed_btn")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = speedLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCustomSpeed) AmberKeyframe else StudioTextSecondary,
                                    maxLines = 1
                                )
                            }
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
                                    onOpenEasingEditor()
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

                            // Faces: Toggle Draggable Algorithmic Facial Expression Library
                            Surface(
                                color = if (showFacialTray) VioletLayer.copy(alpha = 0.24f) else StudioPanel,
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (showFacialTray) VioletLayer else StudioBorderSubtle
                                ),
                                modifier = Modifier
                                    .height(26.dp)
                                    .clickable { showFacialTray = !showFacialTray }
                                    .testTag("timeline_faces_tray_toggle")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FaceRetouchingNatural,
                                        contentDescription = "Toggle facial expressions tray",
                                        tint = if (showFacialTray) VioletLayer else StudioTextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Faces",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (showFacialTray) VioletLayer else StudioTextPrimary,
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

                        // Multi-Select Toggle Chip for bulk deleting timeline clips
                        Surface(
                            color = if (isMultiSelectMode) CoralPlayhead.copy(alpha = 0.22f) else StudioPanel,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isMultiSelectMode) CoralPlayhead else StudioBorderSubtle
                            ),
                            modifier = Modifier
                                .height(26.dp)
                                .clickable(onClick = onToggleMultiSelectMode)
                                .testTag("timeline_multi_select_toggle_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMultiSelectMode) Icons.Rounded.Done else Icons.Rounded.Checklist,
                                    contentDescription = if (isMultiSelectMode) "Exit clip multi-select" else "Multi-select timeline clips",
                                    tint = if (isMultiSelectMode) CoralPlayhead else StudioTextSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isMultiSelectMode) "Done" else "Select",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMultiSelectMode) CoralPlayhead else StudioTextPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (isMultiSelectMode) {
                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(StudioPanel)
                        .padding(horizontal = 8.dp)
                        .testTag("timeline_multi_select_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Checklist,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${selectedClipIds.size} clip${if (selectedClipIds.size == 1) "" else "s"} selected",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = StudioCharcoal,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onSelectAllClips)
                            .testTag("timeline_select_all_clips_btn")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Select All",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ElectricCyan
                            )
                        }
                    }
                    Surface(
                        color = StudioCharcoal,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, StudioBorderSubtle),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(onClick = onToggleMultiSelectMode)
                            .testTag("timeline_cancel_multi_select_btn")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                fontSize = 10.sp,
                                color = StudioTextSecondary
                            )
                        }
                    }
                    Surface(
                        color = if (selectedClipIds.isNotEmpty()) CoralPlayhead else StudioCharcoal,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selectedClipIds.isNotEmpty()) CoralPlayhead else StudioBorderSubtle
                        ),
                        modifier = Modifier
                            .height(26.dp)
                            .clickable(enabled = selectedClipIds.isNotEmpty()) {
                                showBulkDeleteClipsConfirm = true
                            }
                            .testTag("timeline_bulk_delete_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete selected timeline clips",
                                tint = if (selectedClipIds.isNotEmpty()) Color.White else StudioTextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Delete (${selectedClipIds.size})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedClipIds.isNotEmpty()) Color.White else StudioTextMuted
                            )
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

            if (showFacialTray) {
                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                FacialExpressionTray(
                    activePresetId = activeFacialPresetId,
                    hasCharacterLayer = hasCharacterLayer,
                    activeMouthOverride = activeMouthOverride,
                    activeEyeOverride = activeEyeOverride,
                    activeEyebrowOverride = activeEyebrowOverride,
                    onTapPreset = { preset ->
                        val fallbackCharClip = selectedClip?.takeIf { it.characterId != null }
                            ?: scene.clips.firstOrNull { clip ->
                                clip.characterId != null &&
                                    scene.layers.any { l -> l.id == clip.layerId && l.type == LayerType.CHARACTER && !l.isLocked }
                            }
                        if (fallbackCharClip != null) {
                            latestOnDropFacial(
                                fallbackCharClip.layerId,
                                fallbackCharClip.id,
                                currentFrame,
                                preset.id
                            )
                        }
                    },
                    onSetMouthOverride = { shape -> onSetFacialShapeOverride(shape, null, null) },
                    onSetEyeOverride = { shape -> onSetFacialShapeOverride(null, shape, null) },
                    onSetEyebrowOverride = { shape -> onSetFacialShapeOverride(null, null, shape) },
                    onClearOverrides = onClearFacialShapeOverrides,
                    onDragStart = { preset, rootPos ->
                        draggingPreset = preset
                        dragPointerRoot = rootPos
                    },
                    onDragMove = { rootPos ->
                        dragPointerRoot = rootPos
                    },
                    onDragEnd = {
                        val preset = draggingPreset
                        val target = hoveredDropTarget
                        if (preset != null && target != null) {
                            val (layer, clip, targetFrame) = target
                            latestOnDropFacial(layer.id, clip.id, targetFrame, preset.id)
                        }
                        draggingPreset = null
                    },
                    onDragCancel = {
                        draggingPreset = null
                    }
                )
            }

            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

            val selectedCamBox = remember(scene.cameraBoxes, selectedCameraBoxId) {
                selectedCameraBoxId?.let { id -> scene.cameraBoxes.firstOrNull { it.id == id } }
            }
            val selectedClipLayer = remember(selectedClip, scene.layers) {
                selectedClip?.let { c -> scene.layers.firstOrNull { it.id == c.layerId } }
            }
            if ((selectedCamBox != null || selectedClip != null) && !isPlaying) {
                UniversalClipActionStrip(
                    selectedCameraBox = selectedCamBox,
                    isCameraLocked = scene.cameraLayerLocked,
                    selectedClip = if (selectedCamBox == null) selectedClip else null,
                    selectedClipLayer = if (selectedCamBox == null) selectedClipLayer else null,
                    currentFrame = currentFrame,
                    onAddCameraBox = onAddCameraBox,
                    onCycleCameraBoxEasing = onCycleCameraBoxEasing,
                    onSplitCameraBox = onSplitCameraBox,
                    onDuplicateCameraBox = onDuplicateCameraBox,
                    onTrimCameraBoxToPlayhead = onTrimCameraBoxToPlayhead,
                    onDeleteCameraBox = onDeleteCameraBoxRipple,
                    onToggleClipHoldCut = onToggleClipHoldCut,
                    onSplitClip = onSplitClip,
                    onDuplicateClip = onDuplicateClip,
                    onTrimClipToPlayhead = onTrimClipToPlayhead,
                    onDeleteClip = onDeleteClip
                )
                HorizontalDivider(
                    color = if (selectedCamBox != null) CameraPeachAccent.copy(alpha = 0.35f) else ElectricCyan.copy(alpha = 0.32f),
                    thickness = 1.dp
                )
            }

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
                        // Dedicated Orange Camera Track Header Row (pinned at top of Layer Rail)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .background(
                                    if (isCameraKeyMode) CameraPeachAccent.copy(alpha = 0.16f)
                                    else StudioObsidian.copy(alpha = 0.45f)
                                )
                                .clickable(onClick = onOpenCameraStudio)
                                .padding(horizontal = if (isLayerRailCollapsed) 3.dp else 5.dp)
                                .testTag("camera_control_layer_header"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = if (isLayerRailCollapsed) Arrangement.Center else Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (scene.cameraLayerEnabled) CameraPeachAccent else StudioTextMuted.copy(alpha = 0.4f))
                            )
                            if (!isLayerRailCollapsed) {
                                Icon(
                                    imageVector = Icons.Rounded.Videocam,
                                    contentDescription = "Camera track",
                                    tint = if (scene.cameraLayerEnabled) CameraPeachAccent else StudioTextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Camera",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (scene.cameraLayerEnabled) CameraPeachAccent else StudioTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                // Quick + button to add Camera Box A, B, C...
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(CameraPeachAccent.copy(alpha = 0.22f))
                                        .clickable(enabled = !scene.cameraLayerLocked, onClick = onAddCameraBox)
                                        .testTag("camera_track_quick_add_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Add Camera Box",
                                        tint = if (!scene.cameraLayerLocked) CameraPeachAccent else StudioTextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(2.dp))
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = onToggleCameraLayerEnabled)
                                    .testTag("camera_layer_vis_btn")
                            ) {
                                Icon(
                                    imageVector = if (scene.cameraLayerEnabled) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = "Toggle camera layer active",
                                    tint = if (scene.cameraLayerEnabled) CameraPeachAccent else StudioTextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (!isLayerRailCollapsed) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = onToggleCameraLayerLocked)
                                        .testTag("camera_layer_lock_btn")
                                ) {
                                    Icon(
                                        imageVector = if (scene.cameraLayerLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = "Toggle camera layer lock",
                                        tint = if (scene.cameraLayerLocked) AmberKeyframe else StudioTextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = CameraPeachAccent.copy(alpha = 0.32f), thickness = 1.dp)

                        if (activeClipDrag != null) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(StudioObsidian.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "+ New Track",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ElectricCyan,
                                    maxLines = 1
                                )
                            }
                            HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.5f), thickness = 1.dp)
                        }

                        sortedLayers.forEachIndexed { layerIdx, layer ->
                            val layerColor = Color(ProceduralRigRenderer.parseColorSafe(layer.colorHex, 0xFF38BDF8.toInt()))
                            val isSelLayer = layer.id == selectedLayerId
                            val isAudioLayer = layer.type == LayerType.AUDIO
                            val rowHeight = layerRowHeight(layer.type)
                            val isAtFront = layerIdx == 0
                            val isAtBack = layerIdx == sortedLayers.lastIndex
                            val isMenuOpen = openLayerMenuId == layer.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .background(if (isSelLayer) StudioCard else Color.Transparent)
                                    .padding(start = 0.dp, end = if (isLayerRailCollapsed) 2.dp else 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // Far-left 3-dot (⋮) Z-Order & Actions trigger + anchored dark popup menu
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(rowHeight)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { openLayerMenuId = layer.id }
                                        .testTag("layer_menu_btn_${layer.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "Layer options for ${layer.name}",
                                        tint = if (isMenuOpen) ElectricCyan else StudioTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    DropdownMenu(
                                        expanded = isMenuOpen,
                                        onDismissRequest = { openLayerMenuId = null },
                                        shape = RoundedCornerShape(14.dp),
                                        containerColor = Color(0xFF1A1E26),
                                        border = BorderStroke(1.dp, Color(0xFF2C3444))
                                    ) {
                                        val enabledText = Color(0xFFF1F5F9)
                                        val enabledIcon = Color(0xFFCBD5E1)
                                        val disabledTint = Color(0xFF64748B).copy(alpha = 0.45f)
                                        val deleteRed = Color(0xFFEF4444)

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Bring To Front",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (!isAtFront) enabledText else disabledTint
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.FlipToFront,
                                                    contentDescription = "Bring To Front",
                                                    tint = if (!isAtFront) enabledIcon else disabledTint,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            enabled = !isAtFront,
                                            onClick = {
                                                openLayerMenuId = null
                                                onBringLayerToFront(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_front_${layer.id}")
                                        )

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Send To Back",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (!isAtBack) enabledText else disabledTint
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.FlipToBack,
                                                    contentDescription = "Send To Back",
                                                    tint = if (!isAtBack) enabledIcon else disabledTint,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            enabled = !isAtBack,
                                            onClick = {
                                                openLayerMenuId = null
                                                onSendLayerToBack(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_back_${layer.id}")
                                        )

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Bring Forward",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (!isAtFront) enabledText else disabledTint
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.MoveUp,
                                                    contentDescription = "Bring Forward",
                                                    tint = if (!isAtFront) enabledIcon else disabledTint,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            enabled = !isAtFront,
                                            onClick = {
                                                openLayerMenuId = null
                                                onBringLayerForward(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_forward_${layer.id}")
                                        )

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Send Backward",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (!isAtBack) enabledText else disabledTint
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.MoveDown,
                                                    contentDescription = "Send Backward",
                                                    tint = if (!isAtBack) enabledIcon else disabledTint,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            enabled = !isAtBack,
                                            onClick = {
                                                openLayerMenuId = null
                                                onSendLayerBackward(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_backward_${layer.id}")
                                        )

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Duplicate Layer",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = enabledText
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentCopy,
                                                    contentDescription = "Duplicate layer",
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                openLayerMenuId = null
                                                onDuplicateLayer(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_duplicate_${layer.id}")
                                        )

                                        HorizontalDivider(
                                            color = Color(0xFF2C3444),
                                            thickness = 1.dp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )

                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Delete",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = deleteRed
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Delete layer",
                                                    tint = deleteRed,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                openLayerMenuId = null
                                                onDeleteLayer(layer.id)
                                            },
                                            modifier = Modifier.testTag("layer_menu_delete_${layer.id}")
                                        )
                                    }
                                }

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
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(22.dp)
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
                                            .size(20.dp)
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
                        if (activeClipDrag != null) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(StudioObsidian.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "+ New Track",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ElectricCyan,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                VerticalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Right Horizontally Scrollable Ruler + Tracks + 2-Finger Pinch-to-Zoom + Magnetic Snap + Playhead
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coords ->
                            trackViewportRootLeftPx = coords.positionInRoot().x
                            trackViewportWidthPx = coords.size.width.toFloat()
                        }
                        .pointerInput(Unit) {
                            // Smooth 2-finger pinch-to-zoom without interfering with 1-finger scroll/drag/trim
                            awaitEachGesture {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val zoomFactor = event.calculateZoom()
                                        if (abs(zoomFactor - 1f) > 0.008f) {
                                            val nextZoom = (latestTimelineZoom * zoomFactor).coerceIn(0.4f, 2.6f)
                                            latestOnSetTimelineZoom(nextZoom)
                                            pressed.forEach { it.consume() }
                                        }
                                    } else if (pressed.isEmpty()) {
                                        break
                                    }
                                }
                            }
                        }
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

                        // Layer Tracks with Dedicated Camera Control Layer + Selectable Keyframe Diamond Nodes
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(verticalScroll)
                        ) {
                            // Dedicated Orange Camera Track (Trimmable Cam A/B/C Boxes + Legacy Keyframes)
                            val camBoxesSorted = remember(scene.cameraBoxes) {
                                scene.cameraBoxes.sortedBy { it.startFrame }
                            }
                            val camKeysSorted = remember(scene.cameraKeyframes) {
                                scene.cameraKeyframes.sortedBy { it.frame }
                            }
                            val camVisAlpha = if (scene.cameraLayerEnabled) 1f else 0.35f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .background(
                                        if (isCameraKeyMode) CameraPeachAccent.copy(alpha = 0.10f)
                                        else StudioObsidian.copy(alpha = 0.55f)
                                    )
                                    .pointerInput(scene.durationFrames, frameWidthPx) {
                                        detectTapGestures(
                                            onTap = { pos ->
                                                val target = (pos.x / frameWidthPx).toInt()
                                                    .coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
                                                latestOnSeekFrame(target)
                                            },
                                            onDoubleTap = {
                                                onOpenCameraStudio()
                                            }
                                        )
                                    }
                                    .testTag("camera_control_track_row")
                            ) {
                                if (camBoxesSorted.isNotEmpty()) {
                                    camBoxesSorted.forEach { box ->
                                        val boxSnapTargets = remember(scene, currentFrame, majorMarkerStep, box.id) {
                                            TimelineNleEngine.collectSnapTargets(
                                                scene = scene,
                                                playheadFrame = currentFrame,
                                                majorMarkerStep = majorMarkerStep,
                                                excludeCameraBoxId = box.id
                                            )
                                        }
                                        CameraBoxTimelineClipBar(
                                            box = box,
                                            isSelected = box.id == selectedCameraBoxId,
                                            isLocked = scene.cameraLayerLocked,
                                            visAlpha = camVisAlpha,
                                            frameWidthDp = frameWidthDp,
                                            frameWidthPx = frameWidthPx,
                                            sceneDurationFrames = scene.durationFrames,
                                            snapTargets = boxSnapTargets,
                                            snapTolerancePx = snapTolerancePx,
                                            onActiveSnapGuideChange = { trimSnapGuideFrame = it },
                                            onSelect = { onSelectCameraBox(box.id) },
                                            onCycleEasing = { onCycleCameraBoxEasing(box.id) },
                                            onTrimOrMove = { newStart, newEnd ->
                                                onTrimOrMoveCameraBox(box.id, newStart, newEnd)
                                            }
                                        )
                                    }
                                } else if (camKeysSorted.isNotEmpty()) {
                                    val firstFrame = camKeysSorted.first().frame
                                    val lastFrame = camKeysSorted.last().frame
                                    val barStartDp = frameWidthDp * firstFrame
                                    val barWidthDp = (frameWidthDp * (lastFrame - firstFrame).coerceAtLeast(1) + 22.dp)
                                        .coerceAtLeast(48.dp)
                                    Box(
                                        modifier = Modifier
                                            .offset(x = barStartDp, y = 4.dp)
                                            .width(barWidthDp)
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AmberKeyframe.copy(alpha = 0.16f * camVisAlpha))
                                            .border(
                                                width = if (isCameraKeyMode) 1.3.dp else 1.dp,
                                                color = AmberKeyframe.copy(alpha = 0.58f * camVisAlpha),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                    ) {
                                        Text(
                                            text = "Cam ${(evaluatedCamHere.zoom * 100).toInt()}% • ${evaluatedCamHere.rotation.toInt()}°",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AmberKeyframe.copy(alpha = camVisAlpha),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }

                                    camKeysSorted.forEach { camKf ->
                                        val isCamKfHere = currentFrame == camKf.frame
                                        val rawX = frameWidthDp * camKf.frame - 9.dp
                                        val clampedX = rawX.coerceAtLeast(0.dp)
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .offset(x = clampedX, y = 7.dp)
                                                .size(20.dp)
                                                .clickable { onSelectCameraKeyframe(camKf.frame) }
                                                .testTag("camera_kf_node_${camKf.frame}")
                                        ) {
                                            Canvas(modifier = Modifier.size(12.dp)) {
                                                rotate(45f) {
                                                    drawRect(
                                                        color = if (isCamKfHere) ElectricCyan else AmberKeyframe.copy(alpha = camVisAlpha),
                                                        topLeft = Offset(2.2f, 2.2f),
                                                        size = Size(size.width - 4.4f, size.height - 4.4f)
                                                    )
                                                    drawRect(
                                                        color = StudioObsidian,
                                                        topLeft = Offset(2.2f, 2.2f),
                                                        size = Size(size.width - 4.4f, size.height - 4.4f),
                                                        style = Stroke(width = 1.6f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        Surface(
                                            color = CameraPeachAccent.copy(alpha = 0.22f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, CameraPeachAccent),
                                            modifier = Modifier
                                                .height(22.dp)
                                                .clickable(enabled = !scene.cameraLayerLocked, onClick = onAddCameraBox)
                                                .testTag("camera_track_add_box_btn")
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier.padding(horizontal = 7.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Add,
                                                    contentDescription = "Add Camera Box",
                                                    tint = CameraPeachAccent,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = "Camera A",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CameraPeachAccent
                                                )
                                            }
                                        }
                                        Surface(
                                            color = AmberKeyframe.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, AmberKeyframe.copy(alpha = 0.50f)),
                                            modifier = Modifier
                                                .height(22.dp)
                                                .clickable(onClick = onStampCameraKeyframe)
                                                .testTag("camera_track_add_kf_btn")
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            ) {
                                                Text(
                                                    text = "+ Key",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AmberKeyframe
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Add visual A/B/C Camera Boxes or Presets",
                                            fontSize = 9.5.sp,
                                            color = StudioTextMuted,
                                            maxLines = 1,
                                            modifier = Modifier.clickable(onClick = onOpenCameraStudio)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = CameraPeachAccent.copy(alpha = 0.32f), thickness = 1.dp)

                            val commitActiveClipDrop: () -> Unit = {
                                val drag = activeClipDrag
                                val preview = clipDragPreview
                                activeClipDrag = null
                                trimSnapGuideFrame = null
                                if (drag != null && preview != null && preview.isCompatible) {
                                    when {
                                        preview.hoveredNewTrackTop -> latestOnMoveClipAcrossTracks(
                                            drag.clip.id, null, preview.snappedStartFrame, true, true
                                        )
                                        preview.hoveredNewTrackBottom -> latestOnMoveClipAcrossTracks(
                                            drag.clip.id, null, preview.snappedStartFrame, true, false
                                        )
                                        else -> {
                                            val destLayerId = preview.hoveredLayer?.id ?: drag.sourceLayer.id
                                            latestOnMoveClipAcrossTracks(
                                                drag.clip.id, destLayerId, preview.snappedStartFrame, false, true
                                            )
                                        }
                                    }
                                }
                            }

                            if (activeClipDrag != null) {
                                val dragAccentCol = Color(
                                    ProceduralRigRenderer.parseColorSafe(
                                        activeClipDrag!!.sourceLayer.colorHex,
                                        0xFF38BDF8.toInt()
                                    )
                                )
                                NewTrackDropZoneRow(
                                    label = "+ Drop here for New ${activeClipDrag!!.sourceLayer.type.displayName} Track (Top)",
                                    isHovered = clipDragPreview?.hoveredNewTrackTop == true,
                                    accentColor = dragAccentCol,
                                    onBoundsChanged = { top, bottom -> newTrackTopBoundsY = top to bottom },
                                    testTag = "timeline_new_track_drop_top"
                                )
                                HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.5f), thickness = 1.dp)
                            } else {
                                newTrackTopBoundsY = null
                                newTrackBottomBoundsY = null
                            }

                            sortedLayers.forEach { layer ->
                                val layerColor = Color(ProceduralRigRenderer.parseColorSafe(layer.colorHex, 0xFF38BDF8.toInt()))
                                val layerClips = clipsByLayer[layer.id].orEmpty()
                                val rowHeight = layerRowHeight(layer.type)
                                val clipHeight = rowHeight - 6.dp

                                val isExprDragActive = draggingPreset != null
                                val isCharDropEligible = isExprDragActive && layer.type == LayerType.CHARACTER && !layer.isLocked
                                val isExprDropHovered = hoveredDropTarget?.first?.id == layer.id
                                val exprDragAccent = draggingPreset?.let {
                                    Color(ProceduralRigRenderer.parseColorSafe(it.accentHex, 0xFF38BDF8.toInt()))
                                } ?: ElectricCyan

                                val clipDrag = activeClipDrag
                                val isClipDropHovered = clipDrag != null && clipDragPreview?.hoveredLayer?.id == layer.id
                                val isClipTrackCompatible = clipDrag != null && !layer.isLocked && (
                                    layer.id == clipDrag.sourceLayer.id ||
                                        TimelineNleEngine.areLayerTypesCompatible(
                                            sourceType = clipDrag.sourceLayer.type,
                                            targetType = layer.type,
                                            targetHasClips = layerClips.any { it.id != clipDrag.clip.id }
                                        )
                                    )
                                val clipDropBorderColor = when {
                                    isClipDropHovered && clipDragPreview?.isCompatible == true -> ElectricCyan
                                    isClipDropHovered -> Color(0xFFEF4444)
                                    clipDrag != null && isClipTrackCompatible -> layerColor.copy(alpha = 0.40f)
                                    else -> Color.Transparent
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .onGloballyPositioned { coords ->
                                            val topY = coords.positionInRoot().y
                                            layerRowBoundsY[layer.id] = topY to (topY + coords.size.height)
                                        }
                                        .background(
                                            when {
                                                isClipDropHovered && clipDragPreview?.isCompatible == true -> ElectricCyan.copy(alpha = 0.18f)
                                                isClipDropHovered -> Color(0xFFEF4444).copy(alpha = 0.15f)
                                                isExprDropHovered -> exprDragAccent.copy(alpha = 0.22f)
                                                isCharDropEligible -> ElectricCyan.copy(alpha = 0.08f)
                                                clipDrag != null && isClipTrackCompatible -> layerColor.copy(alpha = 0.06f)
                                                !layer.isVisible -> StudioObsidian.copy(alpha = 0.72f)
                                                else -> StudioCharcoal
                                            }
                                        )
                                        .then(
                                            when {
                                                isClipDropHovered -> Modifier.border(1.6.dp, clipDropBorderColor)
                                                clipDrag != null && isClipTrackCompatible -> Modifier.border(1.dp, clipDropBorderColor)
                                                isExprDropHovered -> Modifier.border(1.5.dp, exprDragAccent)
                                                isCharDropEligible -> Modifier.border(1.dp, ElectricCyan.copy(alpha = 0.35f))
                                                else -> Modifier
                                            }
                                        )
                                ) {
                                    layerClips.forEach { clip ->
                                        val isMultiChecked = isMultiSelectMode && clip.id in selectedClipIds
                                        val isSelected = if (isMultiSelectMode) isMultiChecked else (clip.id == selectedClipId)
                                        val isAudioClip = layer.type == LayerType.AUDIO || !clip.audioPath.isNullOrBlank()
                                        val isThisClipDragging = activeClipDrag?.clip?.id == clip.id
                                        val clipTapHandler: (String) -> Unit = { clickedId ->
                                            if (isMultiSelectMode) onToggleClipMultiSelect(clickedId) else onSelectClip(clickedId)
                                        }
                                        val clipSnapTargets = remember(scene, currentFrame, majorMarkerStep, clip.id) {
                                            TimelineNleEngine.collectSnapTargets(
                                                scene = scene,
                                                playheadFrame = currentFrame,
                                                majorMarkerStep = majorMarkerStep,
                                                excludeClipId = clip.id
                                            )
                                        }

                                        if (isAudioClip) {
                                            TrimmableAudioTimelineClip(
                                                clip = clip,
                                                fps = project.fps,
                                                sceneDurationFrames = scene.durationFrames,
                                                frameWidthDp = frameWidthDp,
                                                frameWidthPx = frameWidthPx,
                                                clipHeight = clipHeight,
                                                horizontalScrollPxProvider = { horizontalScroll.value.toFloat() },
                                                layerColor = if (isMultiChecked) CoralPlayhead else layerColor,
                                                isLayerVisible = layer.isVisible,
                                                isLayerLocked = layer.isLocked,
                                                isSelected = isSelected,
                                                currentFrame = currentFrame,
                                                onSelectClip = clipTapHandler,
                                                onSelectKeyframeNode = onSelectKeyframeNode,
                                                onTrimAudioClip = onTrimAudioClip,
                                                waveformSensitivity = waveformSensitivity,
                                                isBeingDragged = isThisClipDragging,
                                                snapTargets = clipSnapTargets,
                                                snapTolerancePx = snapTolerancePx,
                                                onActiveSnapGuideChange = { trimSnapGuideFrame = it },
                                                onStartLongPressDrag = { draggedClip, rootPos ->
                                                    onSelectClip(draggedClip.id)
                                                    activeClipDrag = ActiveClipDragState(
                                                        clip = draggedClip,
                                                        sourceLayer = layer,
                                                        initialPointerRoot = rootPos,
                                                        currentPointerRoot = rootPos,
                                                        initialScrollPx = horizontalScroll.value.toFloat(),
                                                        initialStartFrame = draggedClip.startFrame
                                                    )
                                                },
                                                onUpdateLongPressDrag = { rootPos ->
                                                    activeClipDrag = activeClipDrag?.copy(currentPointerRoot = rootPos)
                                                },
                                                onEndLongPressDrag = commitActiveClipDrop,
                                                onCancelLongPressDrag = {
                                                    activeClipDrag = null
                                                    trimSnapGuideFrame = null
                                                }
                                            )
                                        } else {
                                            UniversalTimelineClipBar(
                                                clip = clip,
                                                layer = layer,
                                                layerColor = layerColor,
                                                isSelected = isSelected,
                                                isMultiSelectMode = isMultiSelectMode,
                                                isMultiChecked = isMultiChecked,
                                                isBeingDragged = isThisClipDragging,
                                                currentFrame = currentFrame,
                                                sceneDurationFrames = scene.durationFrames,
                                                frameWidthDp = frameWidthDp,
                                                frameWidthPx = frameWidthPx,
                                                clipHeight = clipHeight,
                                                snapTargets = clipSnapTargets,
                                                snapTolerancePx = snapTolerancePx,
                                                onTapClip = clipTapHandler,
                                                onSelectKeyframeNode = onSelectKeyframeNode,
                                                onTrimClip = { cid, newStart, newDur ->
                                                    onTrimOrMoveClip(cid, newStart, newDur, false)
                                                },
                                                onActiveSnapGuideChange = { trimSnapGuideFrame = it },
                                                onStartLongPressDrag = { draggedClip, srcLayer, rootPos ->
                                                    onSelectClip(draggedClip.id)
                                                    activeClipDrag = ActiveClipDragState(
                                                        clip = draggedClip,
                                                        sourceLayer = srcLayer,
                                                        initialPointerRoot = rootPos,
                                                        currentPointerRoot = rootPos,
                                                        initialScrollPx = horizontalScroll.value.toFloat(),
                                                        initialStartFrame = draggedClip.startFrame
                                                    )
                                                },
                                                onUpdateLongPressDrag = { rootPos ->
                                                    activeClipDrag = activeClipDrag?.copy(currentPointerRoot = rootPos)
                                                },
                                                onEndLongPressDrag = commitActiveClipDrop,
                                                onCancelLongPressDrag = {
                                                    activeClipDrag = null
                                                    trimSnapGuideFrame = null
                                                }
                                            )
                                        }
                                    }

                                    // Live ghost preview bar on destination track while cross-track dragging
                                    if (clipDrag != null && clipDragPreview != null && isClipDropHovered && clipDragPreview.isCompatible) {
                                        val ghostStart = clipDragPreview.snappedStartFrame
                                        val ghostLeftDp = frameWidthDp * ghostStart
                                        val ghostWidthDp = (frameWidthDp * clipDrag.clip.durationFrames).coerceAtLeast(36.dp)
                                        Box(
                                            contentAlignment = Alignment.CenterStart,
                                            modifier = Modifier
                                                .offset(x = ghostLeftDp, y = 3.dp)
                                                .width(ghostWidthDp)
                                                .height(clipHeight)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ElectricCyan.copy(alpha = 0.24f))
                                                .border(1.5.dp, ElectricCyan, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp)
                                        ) {
                                            Text(
                                                text = "F${ghostStart + 1}–F${ghostStart + clipDrag.clip.durationFrames}",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // Vertical snap-frame drop indicator line inside hovered Character track (facial expression drag)
                                    if (isExprDropHovered && hoveredDropTarget != null) {
                                        val snapFrame = hoveredDropTarget.third
                                        Box(
                                            modifier = Modifier
                                                .offset(x = frameWidthDp * snapFrame, y = 0.dp)
                                                .width(2.5.dp)
                                                .fillMaxHeight()
                                                .background(exprDragAccent)
                                        )
                                    }
                                }
                                HorizontalDivider(color = StudioBorderSubtle.copy(alpha = 0.6f), thickness = 1.dp)
                            }

                            if (activeClipDrag != null) {
                                val dragAccentCol = Color(
                                    ProceduralRigRenderer.parseColorSafe(
                                        activeClipDrag!!.sourceLayer.colorHex,
                                        0xFF38BDF8.toInt()
                                    )
                                )
                                NewTrackDropZoneRow(
                                    label = "+ Drop here for New ${activeClipDrag!!.sourceLayer.type.displayName} Track (Bottom)",
                                    isHovered = clipDragPreview?.hoveredNewTrackBottom == true,
                                    accentColor = dragAccentCol,
                                    onBoundsChanged = { top, bottom -> newTrackBottomBoundsY = top to bottom },
                                    testTag = "timeline_new_track_drop_bottom"
                                )
                            }
                        }
                    }

                    // Crisp Vertical Cyan Magnetic Snap Guide Line across all tracks
                    effectiveSnapGuideFrame?.let { snapF ->
                        MagneticSnapGuideOverlay(
                            snapFrame = snapF,
                            frameWidthDp = frameWidthDp
                        )
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

        // Floating lifted clip ghost card following finger during long-press cross-track drag
        val activeDragNow = activeClipDrag
        val dragPreviewNow = clipDragPreview
        if (activeDragNow != null && dragPreviewNow != null) {
            val dragAccent = Color(
                ProceduralRigRenderer.parseColorSafe(
                    activeDragNow.sourceLayer.colorHex,
                    0xFF38BDF8.toInt()
                )
            )
            FloatingDraggedClipGhost(
                clip = activeDragNow.clip,
                accentColor = dragAccent,
                localOffsetPx = activeDragNow.currentPointerRoot - timelineRootTopLeft,
                previewStartFrame = dragPreviewNow.snappedStartFrame,
                destinationLabel = dragPreviewNow.destinationLabel,
                isCompatible = dragPreviewNow.isCompatible,
                isSnapped = dragPreviewNow.snappedGuideFrame != null
            )
        }

        // Floating expression drag ghost badge following finger over TimelinePanel
        draggingPreset?.let { activePreset ->
            FloatingExpressionDragBadge(
                preset = activePreset,
                localOffsetPx = dragPointerRoot - timelineRootTopLeft,
                targetFrame = hoveredDropTarget?.third,
                targetLayerName = hoveredDropTarget?.first?.name
            )
        }
        }
    }

    if (showBulkDeleteClipsConfirm && selectedClipIds.isNotEmpty()) {
        val count = selectedClipIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteClipsConfirm = false },
            containerColor = StudioCharcoal,
            title = {
                Text(
                    text = if (count == 1) "Delete 1 Timeline Clip?" else "Delete $count Timeline Clips?",
                    color = StudioTextPrimary
                )
            },
            text = {
                Text(
                    text = "Remove $count selected ${if (count == 1) "clip" else "clips"} from \"${scene.name}\"? You can restore them with Undo.",
                    color = StudioTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteClipsConfirm = false
                        onDeleteSelectedClips()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralPlayhead),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("timeline_bulk_delete_confirm_btn")
                ) {
                    Text("Delete ($count)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBulkDeleteClipsConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }
}

private fun layerRowHeight(type: LayerType): Dp =
    if (type == LayerType.AUDIO) 52.dp else 40.dp

private fun shortEasingLabel(easing: EasingType): String = easing.shortLabel

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
