package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CameraBoxEngine
import com.example.model.CameraBoxClip
import com.example.model.EasingType
import com.example.ui.theme.*
import kotlin.math.roundToInt

internal val CameraPeachAccent = Color(0xFFF59E6C)
internal val CameraPeachDarkText = Color(0xFF1A1410)

/**
 * Compact 28dp action strip shown above the timeline tracks when a Camera Box clip (Cam A/B/C)
 * is selected, providing 1-tap access to + Cam, Easing/Instant Cut (Hold), Split, Duplicate, and Delete.
 */
@Composable
internal fun CameraBoxQuickActionsStrip(
    selectedBox: CameraBoxClip,
    isLocked: Boolean,
    onAddCameraBox: () -> Unit,
    onCycleEasing: (String) -> Unit,
    onSplitCameraBox: (String) -> Unit,
    onDuplicateCameraBox: (String) -> Unit,
    onDeleteCameraBox: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val easingLabel = when (selectedBox.easing) {
        EasingType.HOLD -> "Hold (Cut)"
        EasingType.LINEAR -> "Linear"
        EasingType.EASE_IN_OUT -> "Ease In-Out"
        EasingType.CUSTOM_CUBIC -> "Cubic"
        else -> selectedBox.easing.shortLabel
    }
    Surface(
        color = StudioObsidian.copy(alpha = 0.92f),
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .testTag("camera_box_quick_actions_strip")
    ) {
        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Surface(
                    color = CameraPeachAccent,
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.height(20.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = "Cam ${selectedBox.label}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CameraPeachDarkText
                        )
                    }
                }
            }
            item {
                CameraStripChip(
                    label = "+ Cam",
                    icon = Icons.Rounded.Add,
                    accent = CameraPeachAccent,
                    enabled = !isLocked,
                    testTag = "cam_strip_add_btn",
                    onClick = onAddCameraBox
                )
            }
            item {
                CameraStripChip(
                    label = easingLabel,
                    icon = if (selectedBox.easing == EasingType.HOLD) Icons.Rounded.FlashOn else Icons.Rounded.ShowChart,
                    accent = if (selectedBox.easing == EasingType.HOLD) AmberKeyframe else ElectricCyan,
                    enabled = !isLocked,
                    testTag = "cam_strip_easing_btn",
                    onClick = { onCycleEasing(selectedBox.id) }
                )
            }
            item {
                CameraStripChip(
                    label = "Split",
                    icon = Icons.Rounded.ContentCut,
                    accent = StudioTextPrimary,
                    enabled = !isLocked,
                    testTag = "cam_strip_split_btn",
                    onClick = { onSplitCameraBox(selectedBox.id) }
                )
            }
            item {
                CameraStripChip(
                    label = "Duplicate",
                    icon = Icons.Rounded.ContentCopy,
                    accent = StudioTextPrimary,
                    enabled = !isLocked,
                    testTag = "cam_strip_dup_btn",
                    onClick = { onDuplicateCameraBox(selectedBox.id) }
                )
            }
            item {
                CameraStripChip(
                    label = "Delete",
                    icon = Icons.Rounded.DeleteOutline,
                    accent = Color(0xFFEF4444),
                    enabled = !isLocked,
                    testTag = "cam_strip_delete_btn",
                    onClick = { onDeleteCameraBox(selectedBox.id) }
                )
            }
        }
    }
}

@Composable
private fun CameraStripChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
 * Individual orange trimmable/draggable Camera Box clip (`Cam A`, `Cam B`, `Cam C`) on the
 * dedicated Camera Track row with `<` and `>` trim handles, body drag, and tap-to-select.
 */
@Composable
internal fun CameraBoxTimelineClipBar(
    box: CameraBoxClip,
    isSelected: Boolean,
    isLocked: Boolean,
    visAlpha: Float,
    frameWidthDp: Dp,
    frameWidthPx: Float,
    sceneDurationFrames: Int,
    onSelect: () -> Unit,
    onCycleEasing: () -> Unit,
    onTrimOrMove: (newStartFrame: Int, newEndFrame: Int) -> Unit
) {
    var previewStart by remember(box.id, box.startFrame) { mutableIntStateOf(box.startFrame) }
    var previewEnd by remember(box.id, box.endFrame) { mutableIntStateOf(box.endFrame) }
    var isDragging by remember(box.id) { mutableStateOf(false) }

    val latestBox by rememberUpdatedState(box)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnTrimOrMove by rememberUpdatedState(onTrimOrMove)

    val displayStart = if (isDragging) previewStart else box.startFrame
    val displayEnd = if (isDragging) previewEnd else box.endFrame
    val spanFrames = (displayEnd - displayStart).coerceAtLeast(CameraBoxEngine.MIN_BOX_DURATION_FRAMES)
    val clipLeftDp = frameWidthDp * displayStart
    val clipWidthDp = (frameWidthDp * spanFrames).coerceAtLeast(52.dp)

    val borderCol = if (isSelected) Color.White else CameraPeachAccent.copy(alpha = 0.85f * visAlpha)
    val fillCol = if (isSelected) {
        CameraPeachAccent.copy(alpha = 0.34f * visAlpha)
    } else {
        CameraPeachAccent.copy(alpha = 0.20f * visAlpha)
    }

    val easingShort = when (box.easing) {
        EasingType.HOLD -> "Cut"
        EasingType.LINEAR -> "Lin"
        EasingType.EASE_IN_OUT -> "Smooth"
        EasingType.CUSTOM_CUBIC -> "Cubic"
        else -> box.easing.shortLabel
    }

    Box(
        modifier = Modifier
            .offset(x = clipLeftDp, y = 3.dp)
            .width(clipWidthDp)
            .height(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(fillCol)
            .border(
                width = if (isSelected) 1.6.dp else 1.dp,
                color = borderCol,
                shape = RoundedCornerShape(7.dp)
            )
            .testTag("camera_box_clip_${box.label}")
    ) {
        // Center draggable + tappable body
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp)
                .pointerInput(box.id, isLocked, frameWidthPx, sceneDurationFrames) {
                    detectTapGestures(
                        onTap = { latestOnSelect() },
                        onDoubleTap = {
                            latestOnSelect()
                            if (!isLocked) onCycleEasing()
                        }
                    )
                }
                .pointerInput(box.id, isLocked, frameWidthPx, sceneDurationFrames) {
                    if (isLocked) return@pointerInput
                    var accumDx = 0f
                    var origStart = 0
                    var origEnd = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            latestOnSelect()
                            accumDx = 0f
                            origStart = latestBox.startFrame
                            origEnd = latestBox.endFrame
                            previewStart = origStart
                            previewEnd = origEnd
                            isDragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount
                            val deltaF = (accumDx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                            val dur = (origEnd - origStart).coerceAtLeast(CameraBoxEngine.MIN_BOX_DURATION_FRAMES)
                            val maxStart = (sceneDurationFrames - dur).coerceAtLeast(0)
                            val nextStart = (origStart + deltaF).coerceIn(0, maxStart)
                            previewStart = nextStart
                            previewEnd = (nextStart + dur).coerceAtMost(sceneDurationFrames.coerceAtLeast(nextStart + dur))
                        },
                        onDragEnd = {
                            val s = previewStart
                            val e = previewEnd
                            isDragging = false
                            if (s != latestBox.startFrame || e != latestBox.endFrame) {
                                latestOnTrimOrMove(s, e)
                            }
                        },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            // Solid peach badge ("A", "B", "C")
            Surface(
                color = CameraPeachAccent.copy(alpha = visAlpha),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = box.label,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CameraPeachDarkText
                    )
                }
            }
            Text(
                text = "Cam ${box.label}",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = StudioTextPrimary.copy(alpha = visAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (clipWidthDp >= 94.dp) {
                Text(
                    text = "• $easingShort",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (box.easing == EasingType.HOLD) AmberKeyframe else CameraPeachAccent.copy(alpha = visAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Left '<' Trim Handle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(13.dp)
                .fillMaxHeight()
                .background(CameraPeachAccent.copy(alpha = (if (isSelected) 0.92f else 0.55f) * visAlpha))
                .pointerInput(box.id, isLocked, frameWidthPx, sceneDurationFrames) {
                    if (isLocked) return@pointerInput
                    var accumDx = 0f
                    var origStart = 0
                    var origEnd = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            latestOnSelect()
                            accumDx = 0f
                            origStart = latestBox.startFrame
                            origEnd = latestBox.endFrame
                            previewStart = origStart
                            previewEnd = origEnd
                            isDragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount
                            val deltaF = (accumDx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                            val maxStart = (origEnd - CameraBoxEngine.MIN_BOX_DURATION_FRAMES).coerceAtLeast(0)
                            previewStart = (origStart + deltaF).coerceIn(0, maxStart)
                            previewEnd = origEnd
                        },
                        onDragEnd = {
                            val s = previewStart
                            val e = previewEnd
                            isDragging = false
                            if (s != latestBox.startFrame) {
                                latestOnTrimOrMove(s, e)
                            }
                        },
                        onDragCancel = { isDragging = false }
                    )
                }
                .testTag("camera_box_trim_left_${box.label}")
        ) {
            Text(
                text = "<",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CameraPeachDarkText
            )
        }

        // Right '>' Trim Handle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(13.dp)
                .fillMaxHeight()
                .background(CameraPeachAccent.copy(alpha = (if (isSelected) 0.92f else 0.55f) * visAlpha))
                .pointerInput(box.id, isLocked, frameWidthPx, sceneDurationFrames) {
                    if (isLocked) return@pointerInput
                    var accumDx = 0f
                    var origStart = 0
                    var origEnd = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            latestOnSelect()
                            accumDx = 0f
                            origStart = latestBox.startFrame
                            origEnd = latestBox.endFrame
                            previewStart = origStart
                            previewEnd = origEnd
                            isDragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount
                            val deltaF = (accumDx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                            val minEnd = origStart + CameraBoxEngine.MIN_BOX_DURATION_FRAMES
                            previewStart = origStart
                            previewEnd = (origEnd + deltaF).coerceIn(minEnd, sceneDurationFrames.coerceAtLeast(minEnd))
                        },
                        onDragEnd = {
                            val s = previewStart
                            val e = previewEnd
                            isDragging = false
                            if (e != latestBox.endFrame) {
                                latestOnTrimOrMove(s, e)
                            }
                        },
                        onDragCancel = { isDragging = false }
                    )
                }
                .testTag("camera_box_trim_right_${box.label}")
        ) {
            Text(
                text = ">",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CameraPeachDarkText
            )
        }
    }
}
