package com.example.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.VolumeOff
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.AudioWaveformData
import com.example.engine.AudioWaveformExtractor
import com.example.model.TimelineClip
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class AudioTrimEdge { LEFT, RIGHT }

private const val MIN_AUDIO_CLIP_FRAMES = 6

/**
 * Audio timeline clip with:
 * - Viewport-culled mirrored visual waveform synced to exact playback frames (`fps` + speed + trim).
 * - Interactive left and right visual trim handles that update the waveform in real time
 *   with zero ViewModel allocations during drag, committing once on release.
 * - Live floating trim timecode badge while dragging a trim handle.
 */
@Composable
internal fun TrimmableAudioTimelineClip(
    clip: TimelineClip,
    fps: Int,
    sceneDurationFrames: Int,
    frameWidthDp: Dp,
    frameWidthPx: Float,
    clipHeight: Dp,
    horizontalScrollPxProvider: () -> Float,
    layerColor: Color,
    isLayerVisible: Boolean,
    isLayerLocked: Boolean,
    isSelected: Boolean,
    currentFrame: Int,
    onSelectClip: (String) -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onTrimAudioClip: (
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ) -> Unit
) {
    val audioPath = clip.audioPath
    val waveform by produceState<AudioWaveformData?>(
        initialValue = AudioWaveformExtractor.getCached(audioPath),
        key1 = audioPath
    ) {
        value = if (!audioPath.isNullOrBlank()) {
            AudioWaveformExtractor.extractWaveform(audioPath)
        } else {
            null
        }
    }

    val safeFps = fps.coerceAtLeast(1)
    val safeSpeed = clip.speedMultiplier.coerceIn(0.1f, 4f)
    val msPerFrame = (1000f * safeSpeed) / safeFps.toFloat()
    val waveformDurationMs = waveform?.durationMs ?: 0L
    val visAlpha = if (isLayerVisible) 1f else 0.35f

    var liveLeftDeltaFrames by remember(clip.id) { mutableIntStateOf(0) }
    var liveRightDeltaFrames by remember(clip.id) { mutableIntStateOf(0) }
    var activeTrimEdge by remember(clip.id) { mutableStateOf<AudioTrimEdge?>(null) }

    val latestClip by rememberUpdatedState(clip)
    val latestOnTrim by rememberUpdatedState(onTrimAudioClip)
    val latestOnSelect by rememberUpdatedState(onSelectClip)

    val effStartFrame = (clip.startFrame + liveLeftDeltaFrames).coerceAtLeast(0)
    val effDurationFrames = (clip.durationFrames - liveLeftDeltaFrames + liveRightDeltaFrames)
        .coerceAtLeast(MIN_AUDIO_CLIP_FRAMES)
    val effTrimStartMs = (clip.audioTrimStartMs + (liveLeftDeltaFrames * msPerFrame).roundToLong())
        .coerceAtLeast(0L)
    val effTrimEndMs = remember(
        clip.audioTrimEndMs,
        effTrimStartMs,
        effDurationFrames,
        liveRightDeltaFrames,
        waveformDurationMs,
        msPerFrame
    ) {
        if (liveRightDeltaFrames != 0) {
            val candidateEnd = effTrimStartMs + (effDurationFrames * msPerFrame).roundToLong()
            when {
                waveformDurationMs > 0L && candidateEnd >= waveformDurationMs - 20L -> 0L
                waveformDurationMs > 0L -> min(candidateEnd, waveformDurationMs).coerceAtLeast(effTrimStartMs + 50L)
                else -> candidateEnd.coerceAtLeast(effTrimStartMs + 50L)
            }
        } else if (clip.audioTrimEndMs > 0L) {
            clip.audioTrimEndMs.coerceAtLeast(effTrimStartMs + 50L)
        } else {
            0L
        }
    }

    val clipOffsetX = frameWidthDp * effStartFrame
    val clipWidth = (frameWidthDp * effDurationFrames).coerceAtLeast(42.dp)
    val isTrimming = activeTrimEdge != null

    val borderColor = when {
        isTrimming -> AmberKeyframe
        isSelected -> ElectricCyan
        else -> layerColor.copy(alpha = 0.62f * visAlpha)
    }

    Box(
        modifier = Modifier
            .offset(x = clipOffsetX, y = 3.dp)
            .width(clipWidth)
            .height(clipHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected || isTrimming) layerColor.copy(alpha = 0.24f * visAlpha)
                else layerColor.copy(alpha = 0.13f * visAlpha)
            )
            .border(
                width = if (isSelected || isTrimming) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onSelectClip(clip.id) }
            .testTag("timeline_clip_${clip.id}")
    ) {
        // 1. Live Trimmed Mirrored Waveform Canvas
        AudioClipWaveformCanvas(
            clipId = clip.id,
            waveform = waveform,
            clipDurationFrames = effDurationFrames,
            fps = safeFps,
            speedMultiplier = safeSpeed,
            trimStartMs = effTrimStartMs,
            trimEndMs = effTrimEndMs,
            frameWidthPx = frameWidthPx,
            clipStartPx = effStartFrame * frameWidthPx,
            horizontalScrollPxProvider = horizontalScrollPxProvider,
            layerColor = layerColor,
            visAlpha = visAlpha,
            isSelected = isSelected || isTrimming,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Compact Translucent Title / Trim Pill (inset past left trim handle)
        Surface(
            color = StudioObsidian.copy(alpha = 0.78f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 13.dp, top = 2.dp, end = 13.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (isLayerVisible) Icons.Rounded.GraphicEq else Icons.Rounded.VolumeOff,
                    contentDescription = null,
                    tint = if (isTrimming) AmberKeyframe else if (isLayerVisible) layerColor else StudioTextMuted,
                    modifier = Modifier.size(10.dp)
                )
                val displayLabel = if (isTrimming) {
                    val startS = effTrimStartMs / 1000f
                    val resolvedEndMs = AudioWaveformExtractor.effectiveTrimEndMs(
                        audioDurationMs = waveformDurationMs,
                        trimStartMs = effTrimStartMs,
                        trimEndMs = effTrimEndMs
                    ).takeIf { it > 0L } ?: (effTrimStartMs + (effDurationFrames * msPerFrame).roundToLong())
                    val endS = resolvedEndMs / 1000f
                    String.format(Locale.US, "%.1fs – %.1fs", startS, endS)
                } else {
                    clip.name
                }
                Text(
                    text = displayLabel,
                    fontSize = 9.5.sp,
                    color = if (isTrimming) AmberKeyframe else StudioTextPrimary.copy(alpha = visAlpha),
                    fontWeight = if (isSelected || isTrimming) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 3. Keyframe Diamond Nodes (inset from trim handles)
        clip.keyframes.forEach { kf ->
            val adjustedLocalFrame = kf.frame - liveLeftDeltaFrames
            if (adjustedLocalFrame in 0 until effDurationFrames) {
                val kfGlobalFrame = effStartFrame + adjustedLocalFrame
                val isNodeSelected = isSelected && currentFrame == kfGlobalFrame
                val rawX = frameWidthDp * adjustedLocalFrame - 9.dp
                val minX = 10.dp
                val maxX = (clipWidth - 30.dp).coerceAtLeast(minX)
                val clampedX = rawX.coerceIn(minX, maxX)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = clampedX, y = 24.dp)
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

        // 4. Visual Left & Right Audio Trim Handles (unlocked layers only)
        if (!isLayerLocked) {
            // LEFT TRIM HANDLE
            AudioTrimHandle(
                isLeft = true,
                isActive = activeTrimEdge == AudioTrimEdge.LEFT,
                isSelected = isSelected,
                accentColor = layerColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .pointerInput(clip.id, frameWidthPx, msPerFrame, waveformDurationMs) {
                        var dragAccumPx = 0f
                        var currentDelta = 0
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccumPx = 0f
                                currentDelta = 0
                                activeTrimEdge = AudioTrimEdge.LEFT
                                latestOnSelect(latestClip.id)
                            },
                            onDragEnd = {
                                val c = latestClip
                                val finalDelta = currentDelta
                                activeTrimEdge = null
                                liveLeftDeltaFrames = 0
                                if (finalDelta != 0) {
                                    val committedStart = (c.startFrame + finalDelta).coerceAtLeast(0)
                                    val committedDur = (c.durationFrames - finalDelta).coerceAtLeast(MIN_AUDIO_CLIP_FRAMES)
                                    val committedTrimStart = (c.audioTrimStartMs + (finalDelta * msPerFrame).roundToLong()).coerceAtLeast(0L)
                                    val committedTrimEnd = if (c.audioTrimEndMs > 0L) {
                                        c.audioTrimEndMs.coerceAtLeast(committedTrimStart + 50L)
                                    } else {
                                        0L
                                    }
                                    latestOnTrim(c.id, committedStart, committedDur, committedTrimStart, committedTrimEnd)
                                }
                            },
                            onDragCancel = {
                                activeTrimEdge = null
                                liveLeftDeltaFrames = 0
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val c = latestClip
                            dragAccumPx += dragAmount
                            val rawDelta = (dragAccumPx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                            val minByTimeline = -c.startFrame
                            val minBySource = -floor(c.audioTrimStartMs / msPerFrame).toInt()
                            val minLeftDelta = max(minByTimeline, minBySource)
                            val maxLeftDelta = (c.durationFrames - MIN_AUDIO_CLIP_FRAMES).coerceAtLeast(0)
                            val clamped = rawDelta.coerceIn(minLeftDelta, maxLeftDelta)
                            if (clamped != currentDelta) {
                                currentDelta = clamped
                                liveLeftDeltaFrames = clamped
                            }
                        }
                    }
                    .testTag("audio_trim_left_${clip.id}")
            )

            // RIGHT TRIM HANDLE
            AudioTrimHandle(
                isLeft = false,
                isActive = activeTrimEdge == AudioTrimEdge.RIGHT,
                isSelected = isSelected,
                accentColor = layerColor,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .pointerInput(clip.id, frameWidthPx, msPerFrame, waveformDurationMs, sceneDurationFrames) {
                        var dragAccumPx = 0f
                        var currentDelta = 0
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccumPx = 0f
                                currentDelta = 0
                                activeTrimEdge = AudioTrimEdge.RIGHT
                                latestOnSelect(latestClip.id)
                            },
                            onDragEnd = {
                                val c = latestClip
                                val finalDelta = currentDelta
                                activeTrimEdge = null
                                liveRightDeltaFrames = 0
                                if (finalDelta != 0) {
                                    val committedDur = (c.durationFrames + finalDelta).coerceAtLeast(MIN_AUDIO_CLIP_FRAMES)
                                    val rawEndMs = c.audioTrimStartMs + (committedDur * msPerFrame).roundToLong()
                                    val committedTrimEnd = when {
                                        waveformDurationMs > 0L && rawEndMs >= waveformDurationMs - 20L -> 0L
                                        waveformDurationMs > 0L -> min(rawEndMs, waveformDurationMs).coerceAtLeast(c.audioTrimStartMs + 50L)
                                        else -> rawEndMs.coerceAtLeast(c.audioTrimStartMs + 50L)
                                    }
                                    latestOnTrim(c.id, c.startFrame, committedDur, c.audioTrimStartMs, committedTrimEnd)
                                }
                            },
                            onDragCancel = {
                                activeTrimEdge = null
                                liveRightDeltaFrames = 0
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val c = latestClip
                            dragAccumPx += dragAmount
                            val rawDelta = (dragAccumPx / frameWidthPx.coerceAtLeast(1f)).roundToInt()
                            val minRightDelta = -(c.durationFrames - MIN_AUDIO_CLIP_FRAMES).coerceAtLeast(0)
                            val maxTotalDur = if (waveformDurationMs > 0L) {
                                val remainingAudioFrames = ceil(
                                    (waveformDurationMs - c.audioTrimStartMs).coerceAtLeast(0L) / msPerFrame
                                ).toInt().coerceAtLeast(MIN_AUDIO_CLIP_FRAMES)
                                max(remainingAudioFrames, c.durationFrames)
                            } else {
                                max(sceneDurationFrames - c.startFrame, c.durationFrames + 48)
                            }
                            val maxRightDelta = (maxTotalDur - c.durationFrames).coerceAtLeast(0)
                            val clamped = rawDelta.coerceIn(minRightDelta, maxRightDelta)
                            if (clamped != currentDelta) {
                                currentDelta = clamped
                                liveRightDeltaFrames = clamped
                            }
                        }
                    }
                    .testTag("audio_trim_right_${clip.id}")
            )
        }
    }
}

@Composable
private fun AudioTrimHandle(
    isLeft: Boolean,
    isActive: Boolean,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val handleFill = when {
        isActive -> AmberKeyframe
        isSelected -> ElectricCyan
        else -> accentColor.copy(alpha = 0.78f)
    }
    val shape = if (isLeft) {
        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    }

    // Generous 18dp touch grab area with a sleek 9dp visual bracket flush to clip edge
    Box(
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd,
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(if (isActive || isSelected) 10.dp else 8.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(handleFill)
        ) {
            // Crisp vertical grip tick inside trim bracket
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(StudioObsidian.copy(alpha = 0.85f))
            )
        }
    }
}

/**
 * Viewport-culled, frame-accurate visual waveform renderer for audio timeline clips.
 */
@Composable
private fun AudioClipWaveformCanvas(
    clipId: String,
    waveform: AudioWaveformData?,
    clipDurationFrames: Int,
    fps: Int,
    speedMultiplier: Float,
    trimStartMs: Long,
    trimEndMs: Long,
    frameWidthPx: Float,
    clipStartPx: Float,
    horizontalScrollPxProvider: () -> Float,
    layerColor: Color,
    visAlpha: Float,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barStepPx = with(density) { 3.dp.toPx() }.coerceAtLeast(2f)
    val barWidthPx = with(density) { 1.85.dp.toPx() }.coerceAtLeast(1.2f)
    val minHalfBarPx = with(density) { 1.2.dp.toPx() }
    val peakCrestCapPx = with(density) { 2.dp.toPx() }

    Canvas(
        modifier = modifier.testTag("audio_waveform_$clipId")
    ) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        val centerY = h * 0.56f
        val maxHalfHeight = (h * 0.39f).coerceAtLeast(4f)

        val scrollPx = horizontalScrollPxProvider()
        val visibleStartX = (scrollPx - clipStartPx - 120f).coerceAtLeast(0f)
        val visibleEndX = (scrollPx + 2200f - clipStartPx).coerceAtMost(w)
        if (visibleEndX <= visibleStartX) return@Canvas

        // Center zero-crossing reference line
        drawLine(
            color = layerColor.copy(alpha = 0.24f * visAlpha),
            start = Offset(visibleStartX, centerY),
            end = Offset(visibleEndX, centerY),
            strokeWidth = 1f
        )

        if (waveform == null || waveform.isEmpty) return@Canvas

        val safeFrameW = frameWidthPx.coerceAtLeast(1f)
        val baseBarColor = if (isSelected) ElectricCyan else layerColor
        val loudPeakColor = if (isSelected) Color(0xFF7DD3FC) else Color(0xFF6EE7B7)
        val crestTipColor = AmberKeyframe.copy(alpha = 0.92f * visAlpha)

        val firstBarIndex = floor(visibleStartX / barStepPx).toInt().coerceAtLeast(0)
        var x = firstBarIndex * barStepPx + barStepPx * 0.5f

        while (x <= visibleEndX) {
            val startLocalFrame = (x - barStepPx * 0.5f) / safeFrameW
            val endLocalFrame = (x + barStepPx * 0.5f) / safeFrameW
            val amp = AudioWaveformExtractor.samplePeakInFrameRange(
                waveform = waveform,
                startLocalFrame = startLocalFrame,
                endLocalFrame = endLocalFrame,
                clipDurationFrames = clipDurationFrames,
                fps = fps,
                speedMultiplier = speedMultiplier,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs
            )

            if (amp < 0f) {
                drawLine(
                    color = StudioTextMuted.copy(alpha = 0.22f * visAlpha),
                    start = Offset(x, centerY - 0.8f),
                    end = Offset(x, centerY + 0.8f),
                    strokeWidth = barWidthPx * 0.8f,
                    cap = StrokeCap.Round
                )
            } else {
                val halfH = max(minHalfBarPx, amp * maxHalfHeight)
                val barColor = if (amp >= 0.62f) {
                    loudPeakColor.copy(alpha = (0.85f + amp * 0.15f) * visAlpha)
                } else {
                    baseBarColor.copy(alpha = (0.48f + amp * 0.42f) * visAlpha)
                }

                drawLine(
                    color = barColor,
                    start = Offset(x, centerY - halfH),
                    end = Offset(x, centerY + halfH),
                    strokeWidth = barWidthPx,
                    cap = StrokeCap.Round
                )

                // Lip-sync peak crest accent on strong vocal/transient spikes
                if (amp >= 0.72f && halfH > peakCrestCapPx * 2f) {
                    drawLine(
                        color = crestTipColor,
                        start = Offset(x, centerY - halfH),
                        end = Offset(x, centerY - halfH + peakCrestCapPx),
                        strokeWidth = barWidthPx,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = crestTipColor,
                        start = Offset(x, centerY + halfH - peakCrestCapPx),
                        end = Offset(x, centerY + halfH),
                        strokeWidth = barWidthPx,
                        cap = StrokeCap.Round
                    )
                }
            }
            x += barStepPx
        }

        // Crisp end-of-audio vertical indicator when clip extends past effective audio end
        val effectiveEndMs = AudioWaveformExtractor.effectiveTrimEndMs(
            audioDurationMs = waveform.durationMs,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs
        )
        if (effectiveEndMs > trimStartMs) {
            val playableSpanMs = effectiveEndMs - trimStartMs
            val safeSpeed = speedMultiplier.coerceIn(0.1f, 4f)
            val audioEndFrames = (playableSpanMs / 1000f) * fps.coerceAtLeast(1) / safeSpeed
            val audioEndX = audioEndFrames * safeFrameW
            if (audioEndX in (visibleStartX + 2f)..(visibleEndX - 2f) && audioEndX < w - 3f) {
                drawLine(
                    color = AmberKeyframe.copy(alpha = 0.55f * visAlpha),
                    start = Offset(audioEndX, 3f),
                    end = Offset(audioEndX, h - 3f),
                    strokeWidth = 1.2f
                )
            }
        }
    }
}
