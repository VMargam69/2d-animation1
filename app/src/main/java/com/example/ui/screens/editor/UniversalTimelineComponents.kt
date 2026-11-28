package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live transient state while a timeline clip (any track type, including Audio) is lifted
 * via ~220ms long-press for simultaneous horizontal & vertical cross-track drag-and-drop.
 */
internal data class ActiveClipDragState(
    val clip: TimelineClip,
    val sourceLayer: AnimationLayer,
    val initialPointerRoot: Offset,
    val currentPointerRoot: Offset,
    val initialScrollPx: Float,
    val initialStartFrame: Int
)

/**
 * Universal Contextual Clip Action Strip shown above the timeline tracks whenever ANY clip
 * on ANY track (Camera, Character, Text/Title, Image/BG, Prop/SVG, Shape, Drawing, Audio)
 * is selected while playback is paused.
 */
@Composable
internal fun UniversalClipActionStrip(
    selectedCameraBox: CameraBoxClip?,
    isCameraLocked: Boolean,
    selectedClip: TimelineClip?,
    selectedClipLayer: AnimationLayer?,
    currentFrame: Int,
    onAddCameraBox: () -> Unit,
    onCycleCameraBoxEasing: (String) -> Unit,
    onSplitCameraBox: (String) -> Unit,
    onDuplicateCameraBox: (String) -> Unit,
    onTrimCameraBoxToPlayhead: (String) -> Unit,
    onDeleteCameraBox: (boxId: String, ripple: Boolean) -> Unit,
    onToggleClipHoldCut: (String) -> Unit,
    onSplitClip: (String) -> Unit,
    onDuplicateClip: (String) -> Unit,
    onTrimClipToPlayhead: (String) -> Unit,
    onDeleteClip: (clipId: String, ripple: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val deleteRed = Color(0xFFEF4444)
    val rippleOrange = Color(0xFFF97316)

    if (selectedCameraBox != null) {
        val easingLabel = when (selectedCameraBox.easing) {
            EasingType.HOLD -> "Hold (Cut)"
            EasingType.LINEAR -> "Linear"
            EasingType.EASE_IN_OUT -> "Ease In-Out"
            EasingType.CUSTOM_CUBIC -> "Cubic"
            else -> selectedCameraBox.easing.shortLabel
        }
        Surface(
            color = StudioObsidian.copy(alpha = 0.94f),
            modifier = modifier
                .fillMaxWidth()
                .height(32.dp)
                .testTag("camera_box_quick_actions_strip")
        ) {
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    TrackBadgePill(
                        text = "Cam ${selectedCameraBox.label}",
                        bgColor = CameraPeachAccent,
                        textColor = CameraPeachDarkText
                    )
                }
                item {
                    NleActionChip(
                        label = "Cam",
                        icon = Icons.Rounded.Add,
                        accent = CameraPeachAccent,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_add_btn",
                        onClick = onAddCameraBox
                    )
                }
                item {
                    NleActionChip(
                        label = easingLabel,
                        icon = if (selectedCameraBox.easing == EasingType.HOLD) Icons.Rounded.FlashOn else Icons.Rounded.ShowChart,
                        accent = if (selectedCameraBox.easing == EasingType.HOLD) AmberKeyframe else ElectricCyan,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_easing_btn",
                        onClick = { onCycleCameraBoxEasing(selectedCameraBox.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Split",
                        icon = Icons.Rounded.ContentCut,
                        accent = StudioTextPrimary,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_split_btn",
                        onClick = { onSplitCameraBox(selectedCameraBox.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Duplicate",
                        icon = Icons.Rounded.ContentCopy,
                        accent = StudioTextPrimary,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_dup_btn",
                        onClick = { onDuplicateCameraBox(selectedCameraBox.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Trim",
                        icon = Icons.Rounded.ContentPasteGo,
                        accent = ElectricCyan,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_trim_btn",
                        onClick = { onTrimCameraBoxToPlayhead(selectedCameraBox.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Delete",
                        icon = Icons.Rounded.DeleteOutline,
                        accent = deleteRed,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_delete_btn",
                        onClick = { onDeleteCameraBox(selectedCameraBox.id, false) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Ripple Del",
                        icon = Icons.Rounded.DeleteSweep,
                        accent = rippleOrange,
                        enabled = !isCameraLocked,
                        testTag = "cam_strip_ripple_delete_btn",
                        onClick = { onDeleteCameraBox(selectedCameraBox.id, true) }
                    )
                }
            }
        }
        return
    }

    if (selectedClip != null) {
        val isLocked = selectedClipLayer?.isLocked == true
        val isAudio = selectedClipLayer?.type == LayerType.AUDIO || !selectedClip.audioPath.isNullOrBlank()
        val accentColor = Color(
            ProceduralRigRenderer.parseColorSafe(
                selectedClipLayer?.colorHex ?: LayerType.CHARACTER.defaultColorHex,
                0xFF38BDF8.toInt()
            )
        )
        val localFrame = (currentFrame - selectedClip.startFrame).coerceAtLeast(0)
        val activeEasing = selectedClip.keyframes
            .filter { it.frame <= localFrame }
            .maxByOrNull { it.frame }
            ?.easing ?: EasingType.LINEAR
        val isHoldActive = activeEasing == EasingType.HOLD

        Surface(
            color = StudioObsidian.copy(alpha = 0.94f),
            modifier = modifier
                .fillMaxWidth()
                .height(32.dp)
                .testTag("universal_clip_quick_actions_strip")
        ) {
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    TrackBadgePill(
                        text = selectedClip.name.take(12),
                        bgColor = accentColor,
                        textColor = StudioObsidian
                    )
                }
                if (isLocked) {
                    item {
                        NleActionChip(
                            label = "Locked",
                            icon = Icons.Rounded.Lock,
                            accent = AmberKeyframe,
                            enabled = false,
                            testTag = "clip_strip_locked_badge",
                            onClick = {}
                        )
                    }
                }
                if (!isAudio) {
                    item {
                        NleActionChip(
                            label = if (isHoldActive) "Hold (Cut)" else "Smooth",
                            icon = if (isHoldActive) Icons.Rounded.FlashOn else Icons.Rounded.ShowChart,
                            accent = if (isHoldActive) AmberKeyframe else ElectricCyan,
                            enabled = !isLocked,
                            testTag = "clip_strip_hold_cut_btn",
                            onClick = { onToggleClipHoldCut(selectedClip.id) }
                        )
                    }
                }
                item {
                    NleActionChip(
                        label = "Split",
                        icon = Icons.Rounded.ContentCut,
                        accent = StudioTextPrimary,
                        enabled = !isLocked,
                        testTag = "clip_strip_split_btn",
                        onClick = { onSplitClip(selectedClip.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Duplicate",
                        icon = Icons.Rounded.ContentCopy,
                        accent = StudioTextPrimary,
                        enabled = !isLocked,
                        testTag = "clip_strip_dup_btn",
                        onClick = { onDuplicateClip(selectedClip.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Trim",
                        icon = Icons.Rounded.ContentPasteGo,
                        accent = ElectricCyan,
                        enabled = !isLocked,
                        testTag = "clip_strip_trim_btn",
                        onClick = { onTrimClipToPlayhead(selectedClip.id) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Delete",
                        icon = Icons.Rounded.DeleteOutline,
                        accent = deleteRed,
                        enabled = !isLocked,
                        testTag = "clip_strip_delete_btn",
                        onClick = { onDeleteClip(selectedClip.id, false) }
                    )
                }
                item {
                    NleActionChip(
                        label = "Ripple Del",
                        icon = Icons.Rounded.DeleteSweep,
                        accent = rippleOrange,
                        enabled = !isLocked,
                        testTag = "clip_strip_ripple_delete_btn",
                        onClick = { onDeleteClip(selectedClip.id, true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackBadgePill(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.height(20.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun NleActionChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.42f
    Surface(
        color = StudioPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f * alpha)),
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent.copy(alpha = alpha),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent.copy(alpha = alpha),
                maxLines = 1
            )
        }
    }
}

/**
 * Universal Pro NLE Timeline Clip Bar for all visual track types (Character, Text, Image/BG,
 * Prop/SVG, Shape, Drawing) featuring:
 * - Left `<` and Right `>` magnetic-snapping trim handles
 * - ~220ms Long-Press with Haptic feedback lifting the clip into Cross-Track Drag & Drop
 * - Preserved tap-to-select, multi-select badges, and individual keyframe diamond nodes.
 */
@Composable
internal fun UniversalTimelineClipBar(
    clip: TimelineClip,
    layer: AnimationLayer,
    layerColor: Color,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    isMultiChecked: Boolean,
    isBeingDragged: Boolean,
    currentFrame: Int,
    sceneDurationFrames: Int,
    frameWidthDp: Dp,
    frameWidthPx: Float,
    clipHeight: Dp,
    snapTargets: List<SnapTarget>,
    snapTolerancePx: Float,
    onTapClip: (String) -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onTrimClip: (clipId: String, newStartFrame: Int, newDurationFrames: Int) -> Unit,
    onActiveSnapGuideChange: (Int?) -> Unit,
    onStartLongPressDrag: (clip: TimelineClip, layer: AnimationLayer, pointerRoot: Offset) -> Unit,
    onUpdateLongPressDrag: (pointerRoot: Offset) -> Unit,
    onEndLongPressDrag: () -> Unit,
    onCancelLongPressDrag: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var previewStart by remember(clip.id, clip.startFrame) { mutableIntStateOf(clip.startFrame) }
    var previewDuration by remember(clip.id, clip.durationFrames) { mutableIntStateOf(clip.durationFrames) }
    var activeTrimSide by remember(clip.id) { mutableIntStateOf(0) } // 0=none, -1=left, +1=right
    var barRootPosition by remember(clip.id) { mutableStateOf(Offset.Zero) }

    val latestClip by rememberUpdatedState(clip)
    val latestLayer by rememberUpdatedState(layer)
    val latestSnapTargets by rememberUpdatedState(snapTargets)
    val latestOnTap by rememberUpdatedState(onTapClip)
    val latestOnTrim by rememberUpdatedState(onTrimClip)
    val latestOnSnapGuide by rememberUpdatedState(onActiveSnapGuideChange)
    val latestOnStartDrag by rememberUpdatedState(onStartLongPressDrag)
    val latestOnUpdateDrag by rememberUpdatedState(onUpdateLongPressDrag)
    val latestOnEndDrag by rememberUpdatedState(onEndLongPressDrag)
    val latestOnCancelDrag by rememberUpdatedState(onCancelLongPressDrag)

    val displayStart = if (activeTrimSide != 0) previewStart else clip.startFrame
    val displayDuration = if (activeTrimSide != 0) previewDuration else clip.durationFrames
    val clipOffsetX = frameWidthDp * displayStart
    val clipWidth = (frameWidthDp * displayDuration).coerceAtLeast(36.dp)
    val visAlpha = (if (layer.isVisible) 1f else 0.35f) * (if (isBeingDragged) 0.28f else 1f)
    val isTrimming = activeTrimSide != 0
    val trimHandleWidth = if (!layer.isLocked && clipWidth >= 46.dp) 13.dp else 0.dp

    Box(
        modifier = Modifier
            .offset(x = clipOffsetX, y = 3.dp)
            .width(clipWidth)
            .height(clipHeight)
            .onGloballyPositioned { coords ->
                barRootPosition = coords.positionInRoot()
            }
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isTrimming -> AmberKeyframe.copy(alpha = 0.26f)
                    isMultiChecked -> CoralPlayhead.copy(alpha = 0.34f * visAlpha)
                    isSelected -> layerColor.copy(alpha = 0.38f * visAlpha)
                    else -> layerColor.copy(alpha = 0.20f * visAlpha)
                }
            )
            .border(
                width = if (isSelected || isMultiChecked || isTrimming) 1.5.dp else 1.dp,
                color = when {
                    isTrimming -> AmberKeyframe
                    isMultiChecked -> CoralPlayhead
                    isSelected -> ElectricCyan
                    else -> layerColor.copy(alpha = 0.62f * visAlpha)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .testTag("timeline_clip_${clip.id}")
    ) {
        // Center body: Tap to select, OR Hold (~220ms + haptic) to lift & cross-track drag!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = trimHandleWidth)
                .pointerInput(clip.id, layer.isLocked, isMultiSelectMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        val slop = viewConfiguration.touchSlop

                        // Wait up to 220ms: if lifted -> tap; if moved > slop -> cancel to let parent scroll!
                        var releasedEarly = false
                        var cancelledByMove = false
                        withTimeoutOrNull(220L) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    releasedEarly = true
                                    break
                                }
                                if ((change.position - downPos).getDistance() > slop) {
                                    cancelledByMove = true
                                    break
                                }
                            }
                        }

                        if (releasedEarly) {
                            latestOnTap(latestClip.id)
                            return@awaitEachGesture
                        }
                        if (cancelledByMove || latestLayer.isLocked || isMultiSelectMode) {
                            return@awaitEachGesture
                        }

                        // ~220ms Long-Press fired! Lift clip with haptic feedback and enter cross-track drag
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val startRoot = barRootPosition + Offset(trimHandleWidth.toPx(), 0f) + downPos
                        latestOnStartDrag(latestClip, latestLayer, startRoot)

                        var endedCleanly = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                endedCleanly = true
                                break
                            }
                            change.consume()
                            val currentRoot = barRootPosition + Offset(trimHandleWidth.toPx(), 0f) + change.position
                            latestOnUpdateDrag(currentRoot)
                        }
                        if (endedCleanly) {
                            latestOnEndDrag()
                        } else {
                            latestOnCancelDrag()
                        }
                    }
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                if (isMultiSelectMode) {
                    Icon(
                        imageVector = if (isMultiChecked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isMultiChecked) "Selected clip ${clip.name}" else "Unselected clip ${clip.name}",
                        tint = if (isMultiChecked) CoralPlayhead else StudioTextSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Text(
                    text = if (isTrimming) "F${displayStart + 1}–F${displayStart + displayDuration}" else clip.name,
                    fontSize = 9.5.sp,
                    color = if (isTrimming) AmberKeyframe else StudioTextPrimary.copy(alpha = visAlpha),
                    fontWeight = if (isSelected || isTrimming) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Individually Selectable Keyframe Diamond Nodes (preserved & shifted smoothly during left-trim)
        val trimLeftDelta = displayStart - clip.startFrame
        clip.keyframes.forEach { kf ->
            val adjustedLocal = kf.frame - trimLeftDelta
            if (adjustedLocal in 0 until displayDuration) {
                val kfGlobalFrame = displayStart + adjustedLocal
                val isNodeSelected = isSelected && currentFrame == kfGlobalFrame
                val rawX = frameWidthDp * adjustedLocal - 9.dp
                val minX = trimHandleWidth
                val maxX = (clipWidth - trimHandleWidth - 20.dp).coerceAtLeast(minX)
                val clampedX = rawX.coerceIn(minX, maxX)
                val kfPreset = kf.facialPresetId?.let { FacialExpressionEngine.presetById(it) }
                    ?: kf.expressionOverride?.takeIf { it != FacialExpression.AUTO }?.let {
                        FacialExpressionEngine.presetForExpression(it)
                    }
                val nodeColor = when {
                    isNodeSelected -> ElectricCyan
                    kfPreset != null -> Color(
                        ProceduralRigRenderer.parseColorSafe(kfPreset.accentHex, 0xFFFBBF24.toInt())
                    ).copy(alpha = visAlpha)
                    kf.easing == EasingType.HOLD -> Color(0xFFF97316).copy(alpha = visAlpha)
                    else -> AmberKeyframe.copy(alpha = visAlpha)
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = clampedX, y = 10.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { onSelectKeyframeNode(clip.id, kfGlobalFrame) }
                        .testTag("keyframe_node_${clip.id}_${kf.frame}")
                ) {
                    Canvas(modifier = Modifier.size(11.dp)) {
                        val side = size.minDimension * 0.66f
                        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                        rotate(45f) {
                            drawRect(color = nodeColor, topLeft = topLeft, size = Size(side, side))
                            if (isNodeSelected || kfPreset != null) {
                                drawRect(
                                    color = if (isNodeSelected) Color.White else StudioObsidian.copy(alpha = 0.85f),
                                    topLeft = topLeft,
                                    size = Size(side, side),
                                    style = Stroke(width = if (isNodeSelected) 2f else 1.2f)
                                )
                            }
                        }
                    }
                }

                if (kfPreset != null && clipWidth >= 72.dp && !isTrimming) {
                    val tagOffsetX = (frameWidthDp * adjustedLocal + 6.dp)
                        .coerceIn(trimHandleWidth + 2.dp, (clipWidth - trimHandleWidth - 40.dp).coerceAtLeast(trimHandleWidth + 2.dp))
                    Box(
                        modifier = Modifier
                            .offset(x = tagOffsetX, y = 14.dp)
                            .background(StudioObsidian.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                            .border(0.75.dp, nodeColor.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = kfPreset.shortLabel,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = nodeColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Left '<' and Right '>' Magnetic-Snapping Trim Handles
        if (trimHandleWidth > 0.dp) {
            // LEFT TRIM HANDLE '<'
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(trimHandleWidth)
                    .fillMaxHeight()
                    .background(
                        when {
                            activeTrimSide == -1 -> AmberKeyframe
                            isSelected -> ElectricCyan.copy(alpha = 0.85f)
                            else -> layerColor.copy(alpha = 0.42f * visAlpha)
                        }
                    )
                    .pointerInput(clip.id, frameWidthPx, snapTolerancePx, sceneDurationFrames) {
                        var accumDx = 0f
                        var origStart = 0
                        var origEnd = 0
                        detectHorizontalDragGestures(
                            onDragStart = {
                                latestOnTap(latestClip.id)
                                accumDx = 0f
                                origStart = latestClip.startFrame
                                origEnd = latestClip.endFrame
                                previewStart = origStart
                                previewDuration = latestClip.durationFrames
                                activeTrimSide = -1
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                accumDx += dragAmount
                                val deltaF = (accumDx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                                val maxStart = (origEnd - TimelineNleEngine.MIN_CLIP_DURATION_FRAMES).coerceAtLeast(0)
                                val rawStart = (origStart + deltaF).coerceIn(0, maxStart)
                                val snapped = TimelineNleEngine.computeMagneticSnap(
                                    candidateStartFrame = rawStart,
                                    candidateEndFrame = origEnd,
                                    mode = SnapDragMode.TRIM_LEFT,
                                    snapTargets = latestSnapTargets,
                                    frameWidthPx = frameWidthPx,
                                    snapTolerancePx = snapTolerancePx
                                )
                                previewStart = snapped.snappedStartFrame
                                previewDuration = (origEnd - snapped.snappedStartFrame)
                                    .coerceAtLeast(TimelineNleEngine.MIN_CLIP_DURATION_FRAMES)
                                latestOnSnapGuide(snapped.snappedGuideFrame)
                            },
                            onDragEnd = {
                                val committedStart = previewStart
                                val committedDur = previewDuration
                                activeTrimSide = 0
                                latestOnSnapGuide(null)
                                if (committedStart != latestClip.startFrame || committedDur != latestClip.durationFrames) {
                                    latestOnTrim(latestClip.id, committedStart, committedDur)
                                }
                            },
                            onDragCancel = {
                                activeTrimSide = 0
                                latestOnSnapGuide(null)
                            }
                        )
                    }
                    .testTag("clip_trim_left_${clip.id}")
            ) {
                Text(
                    text = "<",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StudioObsidian
                )
            }

            // RIGHT TRIM HANDLE '>'
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(trimHandleWidth)
                    .fillMaxHeight()
                    .background(
                        when {
                            activeTrimSide == 1 -> AmberKeyframe
                            isSelected -> ElectricCyan.copy(alpha = 0.85f)
                            else -> layerColor.copy(alpha = 0.42f * visAlpha)
                        }
                    )
                    .pointerInput(clip.id, frameWidthPx, snapTolerancePx, sceneDurationFrames) {
                        var accumDx = 0f
                        var origStart = 0
                        var origEnd = 0
                        detectHorizontalDragGestures(
                            onDragStart = {
                                latestOnTap(latestClip.id)
                                accumDx = 0f
                                origStart = latestClip.startFrame
                                origEnd = latestClip.endFrame
                                previewStart = origStart
                                previewDuration = latestClip.durationFrames
                                activeTrimSide = 1
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                accumDx += dragAmount
                                val deltaF = (accumDx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                                val minEnd = origStart + TimelineNleEngine.MIN_CLIP_DURATION_FRAMES
                                val maxEnd = maxOf(sceneDurationFrames + 48, origEnd + 48)
                                val rawEnd = (origEnd + deltaF).coerceIn(minEnd, maxEnd)
                                val snapped = TimelineNleEngine.computeMagneticSnap(
                                    candidateStartFrame = origStart,
                                    candidateEndFrame = rawEnd,
                                    mode = SnapDragMode.TRIM_RIGHT,
                                    snapTargets = latestSnapTargets,
                                    frameWidthPx = frameWidthPx,
                                    snapTolerancePx = snapTolerancePx,
                                    maxFrame = maxEnd
                                )
                                previewStart = origStart
                                previewDuration = (snapped.snappedEndFrame - origStart)
                                    .coerceAtLeast(TimelineNleEngine.MIN_CLIP_DURATION_FRAMES)
                                latestOnSnapGuide(snapped.snappedGuideFrame)
                            },
                            onDragEnd = {
                                val committedStart = previewStart
                                val committedDur = previewDuration
                                activeTrimSide = 0
                                latestOnSnapGuide(null)
                                if (committedDur != latestClip.durationFrames) {
                                    latestOnTrim(latestClip.id, committedStart, committedDur)
                                }
                            },
                            onDragCancel = {
                                activeTrimSide = 0
                                latestOnSnapGuide(null)
                            }
                        )
                    }
                    .testTag("clip_trim_right_${clip.id}")
            ) {
                Text(
                    text = ">",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StudioObsidian
                )
            }
        }
    }
}

/**
 * Glowing "+ Drop here for New Track" row revealed while a clip long-press drag is active.
 */
@Composable
internal fun NewTrackDropZoneRow(
    label: String,
    isHovered: Boolean,
    accentColor: Color,
    onBoundsChanged: (topY: Float, bottomY: Float) -> Unit,
    testTag: String
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onGloballyPositioned { coords ->
                val top = coords.positionInRoot().y
                onBoundsChanged(top, top + coords.size.height)
            }
            .background(
                if (isHovered) accentColor.copy(alpha = 0.24f) else StudioObsidian.copy(alpha = 0.72f)
            )
            .border(
                width = if (isHovered) 1.5.dp else 1.dp,
                color = if (isHovered) accentColor else ElectricCyan.copy(alpha = 0.45f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 12.dp)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AddCircleOutline,
                contentDescription = label,
                tint = if (isHovered) accentColor else ElectricCyan,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (isHovered) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isHovered) Color.White else ElectricCyan
            )
        }
    }
}

/**
 * Elevated floating shadow preview card following the finger during a long-press cross-track drag.
 */
@Composable
internal fun FloatingDraggedClipGhost(
    clip: TimelineClip,
    accentColor: Color,
    localOffsetPx: Offset,
    previewStartFrame: Int,
    destinationLabel: String,
    isCompatible: Boolean,
    isSnapped: Boolean
) {
    val badgeBorder = if (isCompatible) {
        if (isSnapped) ElectricCyan else accentColor
    } else {
        Color(0xFFEF4444)
    }
    Surface(
        color = Color(0xFF141923).copy(alpha = 0.96f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, badgeBorder),
        shadowElevation = 12.dp,
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (localOffsetPx.x - 84f).roundToInt().coerceAtLeast(8),
                    y = (localOffsetPx.y - 52f).roundToInt().coerceAtLeast(4)
                )
            }
            .testTag("floating_dragged_clip_ghost")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(badgeBorder)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = clip.name,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = "F${previewStartFrame + 1}–F${previewStartFrame + clip.durationFrames}",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSnapped) ElectricCyan else AmberKeyframe
                    )
                    if (isSnapped) {
                        Text(
                            text = "SNAP",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ElectricCyan
                        )
                    }
                }
                Text(
                    text = destinationLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCompatible) StudioTextSecondary else Color(0xFFFCA5A5),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Crisp cyan vertical magnetic snap guide line spanning the full timeline track height
 * whenever a dragged or trimmed clip snaps to Playhead, another clip's edge, or a ruler marker.
 */
@Composable
internal fun MagneticSnapGuideOverlay(
    snapFrame: Int,
    frameWidthDp: Dp
) {
    Box(
        modifier = Modifier
            .offset(x = frameWidthDp * snapFrame - 1.dp)
            .width(2.dp)
            .fillMaxHeight()
            .background(ElectricCyan)
            .testTag("timeline_magnetic_snap_guide")
    ) {
        Canvas(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(8.dp)
        ) {
            rotate(45f) {
                drawRect(
                    color = ElectricCyan,
                    topLeft = Offset(1.5f, 1.5f),
                    size = Size(size.width - 3f, size.height - 3f)
                )
            }
        }
    }
}
