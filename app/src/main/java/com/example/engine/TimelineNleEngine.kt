package com.example.engine

import com.example.model.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class SnapDragMode {
    MOVE,
    TRIM_LEFT,
    TRIM_RIGHT
}

enum class SnapSource(val priority: Int) {
    PLAYHEAD(0),
    CLIP_START(1),
    CLIP_END(1),
    CAMERA_EDGE(2),
    RULER_MARKER(3)
}

data class SnapTarget(
    val frame: Int,
    val source: SnapSource
)

data class MagneticSnapResult(
    val snappedStartFrame: Int,
    val snappedEndFrame: Int,
    val snappedGuideFrame: Int? = null,
    val snapSource: SnapSource? = null
) {
    val isSnapped: Boolean get() = snappedGuideFrame != null
}

data class OverlapResolution(
    val resolvedStartFrame: Int,
    val resolvedDurationFrames: Int,
    val needsNewLane: Boolean = false,
    val collidedClipId: String? = null
)

data class ClipOperationResult(
    val project: ProjectDocument,
    val primaryClipId: String? = null,
    val createdClipId: String? = null,
    val createdLayerId: String? = null,
    val changed: Boolean = false,
    val message: String = ""
)

/**
 * Pure, deterministic Universal Pro NLE Timeline Engine powering CapCut/Premiere-grade
 * clip editing across ALL FrameCraft track types (Camera, Character, Text, Image, Prop/SVG,
 * Shape, Drawing, and Audio).
 *
 * All functions are side-effect free and operate cleanly on [ProjectDocument] so every action
 * integrates seamlessly with ViewModel Undo/Redo snapshots.
 */
object TimelineNleEngine {

    const val MIN_CLIP_DURATION_FRAMES = 4
    const val DEFAULT_SNAP_TOLERANCE_DP = 10f

    // -------------------------------------------------------------------------
    // 1. Track Compatibility Matrix
    // -------------------------------------------------------------------------

    /**
     * Returns true when a clip from [sourceType] can legally drop onto a track of [targetType].
     * - AUDIO tracks only accept AUDIO clips (and AUDIO clips only drop onto AUDIO tracks).
     * - Visual tracks always accept clips of the same [LayerType].
     * - An empty unlocked visual track ([targetHasClips] == false) can also adopt any dropped visual clip.
     */
    fun areLayerTypesCompatible(
        sourceType: LayerType,
        targetType: LayerType,
        targetHasClips: Boolean = true
    ): Boolean {
        if (sourceType == LayerType.AUDIO || targetType == LayerType.AUDIO) {
            return sourceType == LayerType.AUDIO && targetType == LayerType.AUDIO
        }
        if (sourceType == targetType) return true
        return !targetHasClips
    }

    // -------------------------------------------------------------------------
    // 2. Magnetic Snapping Engine (Playhead, Other-Track Edges, Camera, Markers)
    // -------------------------------------------------------------------------

    fun collectSnapTargets(
        scene: AnimationScene,
        playheadFrame: Int,
        majorMarkerStep: Int = 6,
        excludeClipId: String? = null,
        excludeCameraBoxId: String? = null
    ): List<SnapTarget> {
        val targets = ArrayList<SnapTarget>(32)
        targets.add(SnapTarget(playheadFrame.coerceAtLeast(0), SnapSource.PLAYHEAD))

        for (clip in scene.clips) {
            if (clip.id == excludeClipId) continue
            targets.add(SnapTarget(clip.startFrame, SnapSource.CLIP_START))
            targets.add(SnapTarget(clip.endFrame, SnapSource.CLIP_END))
        }

        for (box in scene.cameraBoxes) {
            if (box.id == excludeCameraBoxId) continue
            targets.add(SnapTarget(box.startFrame, SnapSource.CAMERA_EDGE))
            targets.add(SnapTarget(box.endFrame, SnapSource.CAMERA_EDGE))
        }

        val step = majorMarkerStep.coerceAtLeast(1)
        val total = scene.durationFrames.coerceAtLeast(1)
        var marker = 0
        while (marker <= total) {
            targets.add(SnapTarget(marker, SnapSource.RULER_MARKER))
            marker += step
        }
        return targets
    }

    fun computeMagneticSnap(
        candidateStartFrame: Int,
        candidateEndFrame: Int,
        mode: SnapDragMode,
        snapTargets: List<SnapTarget>,
        frameWidthPx: Float,
        snapTolerancePx: Float,
        minDurationFrames: Int = MIN_CLIP_DURATION_FRAMES,
        maxFrame: Int = Int.MAX_VALUE
    ): MagneticSnapResult {
        val safeFrameW = frameWidthPx.coerceAtLeast(1f)
        val tolFrames = (snapTolerancePx.coerceAtLeast(0f) / safeFrameW)
        val duration = (candidateEndFrame - candidateStartFrame).coerceAtLeast(minDurationFrames)

        if (tolFrames <= 0.001f || snapTargets.isEmpty()) {
            return MagneticSnapResult(
                snappedStartFrame = candidateStartFrame.coerceAtLeast(0),
                snappedEndFrame = candidateEndFrame.coerceAtLeast(candidateStartFrame + minDurationFrames)
            )
        }

        fun findBestTarget(edgeFrame: Int): SnapTarget? {
            var best: SnapTarget? = null
            var bestDist = Float.MAX_VALUE
            for (t in snapTargets) {
                val dist = abs(t.frame - edgeFrame).toFloat()
                if (dist <= tolFrames) {
                    val isBetter = best == null ||
                        dist < bestDist - 0.001f ||
                        (abs(dist - bestDist) <= 0.001f && t.source.priority < best.source.priority)
                    if (isBetter) {
                        best = t
                        bestDist = dist
                    }
                }
            }
            return best
        }

        return when (mode) {
            SnapDragMode.MOVE -> {
                val startHit = findBestTarget(candidateStartFrame)
                val endHit = findBestTarget(candidateEndFrame)
                val chosenEdgeAndTarget: Pair<Boolean, SnapTarget>? = when {
                    startHit != null && endHit != null -> {
                        val dStart = abs(startHit.frame - candidateStartFrame)
                        val dEnd = abs(endHit.frame - candidateEndFrame)
                        if (dStart < dEnd || (dStart == dEnd && startHit.source.priority <= endHit.source.priority)) {
                            true to startHit
                        } else {
                            false to endHit
                        }
                    }
                    startHit != null -> true to startHit
                    endHit != null -> false to endHit
                    else -> null
                }
                if (chosenEdgeAndTarget == null) {
                    val clampedStart = candidateStartFrame.coerceAtLeast(0)
                    MagneticSnapResult(clampedStart, clampedStart + duration)
                } else {
                    val (isStartEdge, target) = chosenEdgeAndTarget
                    val rawStart = if (isStartEdge) target.frame else (target.frame - duration)
                    val clampedStart = rawStart.coerceAtLeast(0)
                    val snappedGuide = if (isStartEdge) clampedStart else (clampedStart + duration)
                    MagneticSnapResult(
                        snappedStartFrame = clampedStart,
                        snappedEndFrame = clampedStart + duration,
                        snappedGuideFrame = snappedGuide,
                        snapSource = target.source
                    )
                }
            }

            SnapDragMode.TRIM_LEFT -> {
                val hit = findBestTarget(candidateStartFrame)
                val maxStart = (candidateEndFrame - minDurationFrames).coerceAtLeast(0)
                if (hit == null) {
                    val s = candidateStartFrame.coerceIn(0, maxStart)
                    MagneticSnapResult(s, candidateEndFrame)
                } else {
                    val s = hit.frame.coerceIn(0, maxStart)
                    MagneticSnapResult(
                        snappedStartFrame = s,
                        snappedEndFrame = candidateEndFrame,
                        snappedGuideFrame = if (s == hit.frame) hit.frame else null,
                        snapSource = if (s == hit.frame) hit.source else null
                    )
                }
            }

            SnapDragMode.TRIM_RIGHT -> {
                val hit = findBestTarget(candidateEndFrame)
                val minEnd = candidateStartFrame + minDurationFrames
                val safeMaxEnd = max(maxFrame, minEnd)
                if (hit == null) {
                    val e = candidateEndFrame.coerceIn(minEnd, safeMaxEnd)
                    MagneticSnapResult(candidateStartFrame, e)
                } else {
                    val e = hit.frame.coerceIn(minEnd, safeMaxEnd)
                    MagneticSnapResult(
                        snappedStartFrame = candidateStartFrame,
                        snappedEndFrame = e,
                        snappedGuideFrame = if (e == hit.frame) hit.frame else null,
                        snapSource = if (e == hit.frame) hit.source else null
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. Smart Overlap / Collision Resolution
    // -------------------------------------------------------------------------

    fun resolveTrackOverlap(
        existingClipsOnLayer: List<TimelineClip>,
        movingClipId: String?,
        desiredStartFrame: Int,
        durationFrames: Int
    ): OverlapResolution {
        val safeStart = desiredStartFrame.coerceAtLeast(0)
        val safeDur = durationFrames.coerceAtLeast(MIN_CLIP_DURATION_FRAMES)
        val others = existingClipsOnLayer
            .filter { it.id != movingClipId }
            .sortedBy { it.startFrame }

        fun findCollision(start: Int, dur: Int): TimelineClip? {
            val end = start + dur
            return others.firstOrNull { other -> start < other.endFrame && end > other.startFrame }
        }

        val initialCollision = findCollision(safeStart, safeDur)
            ?: return OverlapResolution(safeStart, safeDur, needsNewLane = false, collidedClipId = null)

        // Option 1: Snap cleanly immediately before the collided clip
        val beforeStart = initialCollision.startFrame - safeDur
        val beforeClear = beforeStart >= 0 && findCollision(beforeStart, safeDur) == null

        // Option 2: Snap cleanly immediately after the collided clip (or first free adjacent slot)
        var afterStart = initialCollision.endFrame
        var guard = 0
        while (guard < others.size + 2) {
            val nextHit = findCollision(afterStart, safeDur) ?: break
            afterStart = nextHit.endFrame
            guard++
        }

        val chosenStart = when {
            beforeClear && abs(beforeStart - safeStart) <= abs(afterStart - safeStart) -> beforeStart
            else -> afterStart
        }

        val displacement = abs(chosenStart - safeStart)
        val maxComfortableSnap = max(safeDur, 48)
        val suggestNewLane = !beforeClear && displacement > maxComfortableSnap

        return OverlapResolution(
            resolvedStartFrame = chosenStart.coerceAtLeast(0),
            resolvedDurationFrames = safeDur,
            needsNewLane = suggestNewLane,
            collidedClipId = initialCollision.id
        )
    }

    // -------------------------------------------------------------------------
    // 4. Universal Trim & Keyframe Anchoring
    // -------------------------------------------------------------------------

    /**
     * Updates a clip's [startFrame] and [durationFrames] while keeping world-space keyframe timings
     * anchored on left-trims and proportionally syncing audio trim offsets when applicable.
     */
    fun trimClipBounds(
        clip: TimelineClip,
        newStartFrame: Int,
        newDurationFrames: Int,
        fps: Int,
        isAudioClip: Boolean = !clip.audioPath.isNullOrBlank(),
        preserveWorldKeyframes: Boolean = true
    ): TimelineClip {
        val clampedStart = newStartFrame.coerceAtLeast(0)
        val clampedDur = newDurationFrames.coerceAtLeast(MIN_CLIP_DURATION_FRAMES)
        val deltaStart = clampedStart - clip.startFrame
        val durationChanged = clampedDur != clip.durationFrames

        val updatedKeyframes = if (preserveWorldKeyframes && deltaStart != 0) {
            val shifted = clip.keyframes
                .map { kf -> kf.copy(frame = kf.frame - deltaStart) }
                .filter { it.frame in 0 until clampedDur }
                .sortedBy { it.frame }
            val hasZero = shifted.any { it.frame == 0 }
            if (hasZero) {
                shifted
            } else {
                val anchorEval = KeyframeEvaluator.evaluate(clip, clampedStart)
                val anchorKf = evalToKeyframe(localFrame = 0, eval = anchorEval)
                (listOf(anchorKf) + shifted).distinctBy { it.frame }.sortedBy { it.frame }
            }
        } else if (durationChanged) {
            val trimmed = clip.keyframes.filter { it.frame in 0 until clampedDur }
            if (trimmed.isNotEmpty()) trimmed
            else listOf(evalToKeyframe(0, KeyframeEvaluator.evaluate(clip, clampedStart)))
        } else {
            clip.keyframes
        }

        val updatedSimTracks = if (preserveWorldKeyframes && deltaStart != 0) {
            clip.simultaneousTracks.mapNotNull { st ->
                val shiftedStart = st.startLocalFrame - deltaStart
                val shiftedEnd = shiftedStart + st.durationFrames
                if (shiftedEnd <= 0 || shiftedStart >= clampedDur) {
                    null
                } else {
                    val clippedStart = shiftedStart.coerceAtLeast(0)
                    val clippedEnd = shiftedEnd.coerceAtMost(clampedDur)
                    st.copy(
                        startLocalFrame = clippedStart,
                        durationFrames = (clippedEnd - clippedStart).coerceAtLeast(1)
                    )
                }
            }
        } else {
            clip.simultaneousTracks
        }

        return if (isAudioClip) {
            val safeFps = fps.coerceAtLeast(1)
            val safeSpeed = clip.speedMultiplier.coerceIn(0.1f, 4f)
            val msPerFrame = (1000.0 * safeSpeed) / safeFps.toDouble()
            val newTrimStartMs = if (preserveWorldKeyframes && deltaStart != 0) {
                (clip.audioTrimStartMs + (deltaStart * msPerFrame).roundToLong()).coerceAtLeast(0L)
            } else {
                clip.audioTrimStartMs
            }
            val newTrimEndMs = if (durationChanged || (preserveWorldKeyframes && deltaStart != 0)) {
                val computedEnd = newTrimStartMs + (clampedDur * msPerFrame).roundToLong()
                if (clip.audioTrimEndMs > 0L) {
                    if (deltaStart == 0 && clampedDur != clip.durationFrames) {
                        computedEnd.coerceAtLeast(newTrimStartMs + 50L)
                    } else {
                        clip.audioTrimEndMs.coerceAtLeast(newTrimStartMs + 50L)
                    }
                } else if (clampedDur < clip.durationFrames) {
                    computedEnd.coerceAtLeast(newTrimStartMs + 50L)
                } else {
                    0L
                }
            } else {
                clip.audioTrimEndMs
            }
            clip.copy(
                startFrame = clampedStart,
                durationFrames = clampedDur,
                keyframes = updatedKeyframes,
                simultaneousTracks = updatedSimTracks,
                audioTrimStartMs = newTrimStartMs,
                audioTrimEndMs = newTrimEndMs
            )
        } else {
            clip.copy(
                startFrame = clampedStart,
                durationFrames = clampedDur,
                keyframes = updatedKeyframes,
                simultaneousTracks = updatedSimTracks
            )
        }
    }

    /**
     * Applies a trim or horizontal move to any clip, enforcing locked-layer safety, overlap
     * resolution on the same layer, and automatic scene duration expansion if needed.
     */
    fun trimOrMoveClip(
        project: ProjectDocument,
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        isMoveOnly: Boolean
    ): ClipOperationResult {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }

        val clampedDur = newDurationFrames.coerceAtLeast(MIN_CLIP_DURATION_FRAMES)
        val clampedStart = newStartFrame.coerceAtLeast(0)
        val isAudio = layer?.type == LayerType.AUDIO || !clip.audioPath.isNullOrBlank()

        val sameLayerClips = scene.clips.filter { it.layerId == clip.layerId }
        val resolvedStart = if (isMoveOnly) {
            val overlap = resolveTrackOverlap(
                existingClipsOnLayer = sameLayerClips,
                movingClipId = clip.id,
                desiredStartFrame = clampedStart,
                durationFrames = clampedDur
            )
            overlap.resolvedStartFrame
        } else {
            clampedStart
        }

        if (resolvedStart == clip.startFrame && clampedDur == clip.durationFrames) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false)
        }

        val updatedClip = trimClipBounds(
            clip = clip,
            newStartFrame = resolvedStart,
            newDurationFrames = clampedDur,
            fps = project.fps,
            isAudioClip = isAudio,
            preserveWorldKeyframes = !isMoveOnly
        )

        val updated = updateActiveScene(project) { sc ->
            val nextDuration = max(sc.durationFrames, updatedClip.endFrame)
            sc.copy(
                durationFrames = nextDuration,
                clips = sc.clips.map { if (it.id == clipId) updatedClip else it }
            )
        }

        return ClipOperationResult(
            project = updated,
            primaryClipId = clipId,
            changed = true,
            message = if (isMoveOnly) {
                "Moved ${clip.name} to F${updatedClip.startFrame + 1}"
            } else {
                "Trimmed ${clip.name} (F${updatedClip.startFrame + 1}–F${updatedClip.endFrame})"
            }
        )
    }

    // -------------------------------------------------------------------------
    // 5. Universal Split (Preserves Keyframes, Expressions, IK & Audio Trims)
    // -------------------------------------------------------------------------

    fun splitClipUniversal(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int
    ): ClipOperationResult {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }
        if (globalFrame <= clip.startFrame + 2 || globalFrame >= clip.endFrame - 2) {
            return ClipOperationResult(
                project = project,
                primaryClipId = clipId,
                changed = false,
                message = "Move playhead inside the clip to split"
            )
        }

        val splitLocal = globalFrame - clip.startFrame
        val evalAtSplit = KeyframeEvaluator.evaluate(clip, globalFrame)
        val splitAnchorKf = evalToKeyframe(localFrame = 0, eval = evalAtSplit)

        val firstKfs = clip.keyframes.filter { it.frame < splitLocal }.ifEmpty { listOf(splitAnchorKf) }
        val secondKfs = (listOf(splitAnchorKf) + clip.keyframes
            .filter { it.frame > splitLocal }
            .map { it.copy(frame = it.frame - splitLocal) })
            .distinctBy { it.frame }
            .sortedBy { it.frame }

        val firstSimTracks = clip.simultaneousTracks.mapNotNull { st ->
            if (st.startLocalFrame >= splitLocal) null
            else st.copy(durationFrames = min(st.durationFrames, splitLocal - st.startLocalFrame).coerceAtLeast(1))
        }
        val secondDuration = clip.endFrame - globalFrame
        val secondSimTracks = clip.simultaneousTracks.mapNotNull { st ->
            val stEnd = st.startLocalFrame + st.durationFrames
            if (stEnd <= splitLocal) null
            else {
                val newStart = (st.startLocalFrame - splitLocal).coerceAtLeast(0)
                val newEnd = (stEnd - splitLocal).coerceAtMost(secondDuration)
                st.copy(
                    id = UUID.randomUUID().toString(),
                    startLocalFrame = newStart,
                    durationFrames = (newEnd - newStart).coerceAtLeast(1)
                )
            }
        }

        val isAudio = layer?.type == LayerType.AUDIO || !clip.audioPath.isNullOrBlank()
        val firstTrimStartMs = clip.audioTrimStartMs
        val firstTrimEndMs: Long
        val secondTrimStartMs: Long
        val secondTrimEndMs: Long

        if (isAudio) {
            val safeFps = project.fps.coerceAtLeast(1)
            val safeSpeed = clip.speedMultiplier.coerceIn(0.1f, 4f)
            val msPerFrame = (1000.0 * safeSpeed) / safeFps.toDouble()
            val splitOffsetMs = (splitLocal * msPerFrame).roundToLong().coerceAtLeast(1L)
            val splitPointMs = firstTrimStartMs + splitOffsetMs
            firstTrimEndMs = if (clip.audioTrimEndMs > 0L) {
                min(splitPointMs, clip.audioTrimEndMs).coerceAtLeast(firstTrimStartMs + 20L)
            } else {
                splitPointMs
            }
            secondTrimStartMs = firstTrimEndMs
            secondTrimEndMs = if (clip.audioTrimEndMs > 0L) {
                clip.audioTrimEndMs.coerceAtLeast(secondTrimStartMs + 20L)
            } else {
                0L
            }
        } else {
            firstTrimEndMs = clip.audioTrimEndMs
            secondTrimStartMs = clip.audioTrimStartMs
            secondTrimEndMs = clip.audioTrimEndMs
        }

        val secondId = UUID.randomUUID().toString()
        val firstPart = clip.copy(
            durationFrames = splitLocal,
            keyframes = firstKfs,
            simultaneousTracks = firstSimTracks,
            audioTrimStartMs = firstTrimStartMs,
            audioTrimEndMs = firstTrimEndMs
        )
        val secondPart = clip.copy(
            id = secondId,
            name = "${clip.name} (2)",
            startFrame = globalFrame,
            durationFrames = secondDuration,
            keyframes = secondKfs,
            simultaneousTracks = secondSimTracks,
            audioTrimStartMs = secondTrimStartMs,
            audioTrimEndMs = secondTrimEndMs
        )

        val updated = updateActiveScene(project) { sc ->
            sc.copy(
                clips = sc.clips.flatMap { if (it.id == clipId) listOf(firstPart, secondPart) else listOf(it) }
            )
        }
        return ClipOperationResult(
            project = updated,
            primaryClipId = clipId,
            createdClipId = secondId,
            changed = true,
            message = "Split ${clip.name} at F${globalFrame + 1}"
        )
    }

    // -------------------------------------------------------------------------
    // 6. Universal Duplicate (Same Track After End Frame, or Adjacent Lane)
    // -------------------------------------------------------------------------

    fun duplicateClipUniversal(
        project: ProjectDocument,
        clipId: String
    ): ClipOperationResult {
        val scene = project.activeScene
        val original = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val srcLayer = scene.layers.firstOrNull { it.id == original.layerId }
        if (srcLayer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }

        val desiredStart = original.endFrame
        val desiredEnd = desiredStart + original.durationFrames
        val sameLayerOthers = scene.clips.filter { it.layerId == original.layerId && it.id != original.id }
        val spaceOccupied = sameLayerOthers.any { other ->
            desiredStart < other.endFrame && desiredEnd > other.startFrame
        }

        val newClipId = UUID.randomUUID().toString()
        val clonedTracks = original.simultaneousTracks.map { it.copy(id = UUID.randomUUID().toString()) }
        val clonedStrokes = original.drawingStrokes.map { it.copy(id = UUID.randomUUID().toString()) }

        return if (!spaceOccupied && srcLayer != null) {
            val copyClip = original.copy(
                id = newClipId,
                name = "${original.name} Copy",
                startFrame = desiredStart,
                simultaneousTracks = clonedTracks,
                drawingStrokes = clonedStrokes
            )
            val updated = updateActiveScene(project) { sc ->
                sc.copy(
                    durationFrames = max(sc.durationFrames, copyClip.endFrame),
                    clips = sc.clips + copyClip
                )
            }
            ClipOperationResult(
                project = updated,
                primaryClipId = newClipId,
                createdClipId = newClipId,
                changed = true,
                message = "Duplicated ${original.name} on ${srcLayer.name}"
            )
        } else {
            val newLayerId = UUID.randomUUID().toString()
            val updated = updateActiveScene(project) { sc ->
                val sortedLayers = sc.layers.sortedBy { it.orderIndex }.toMutableList()
                val srcIdx = sortedLayers.indexOfFirst { it.id == original.layerId }
                val insertIdx = if (srcIdx >= 0) (srcIdx + 1).coerceAtMost(sortedLayers.size) else sortedLayers.size
                val newLayer = AnimationLayer(
                    id = newLayerId,
                    name = "${srcLayer?.name ?: original.name} Copy",
                    type = srcLayer?.type ?: LayerType.CHARACTER,
                    colorHex = srcLayer?.colorHex ?: LayerType.CHARACTER.defaultColorHex,
                    blendMode = srcLayer?.blendMode ?: LayerBlendMode.NORMAL,
                    parallaxDepth = srcLayer?.parallaxDepth ?: 1.0f
                )
                sortedLayers.add(insertIdx, newLayer)
                val reindexed = sortedLayers.mapIndexed { idx, l -> l.copy(orderIndex = idx) }
                val copyClip = original.copy(
                    id = newClipId,
                    layerId = newLayerId,
                    name = "${original.name} Copy",
                    startFrame = desiredStart,
                    simultaneousTracks = clonedTracks,
                    drawingStrokes = clonedStrokes
                )
                sc.copy(
                    durationFrames = max(sc.durationFrames, copyClip.endFrame),
                    layers = reindexed,
                    clips = sc.clips + copyClip
                )
            }
            ClipOperationResult(
                project = updated,
                primaryClipId = newClipId,
                createdClipId = newClipId,
                createdLayerId = newLayerId,
                changed = true,
                message = "Duplicated ${original.name} onto new lane"
            )
        }
    }

    // -------------------------------------------------------------------------
    // 7. Universal Delete & Ripple Delete (Closes Track Gap Automatically)
    // -------------------------------------------------------------------------

    fun deleteClipUniversal(
        project: ProjectDocument,
        clipId: String,
        ripple: Boolean = false
    ): ClipOperationResult {
        val scene = project.activeScene
        val target = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val layer = scene.layers.firstOrNull { it.id == target.layerId }
        if (layer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }

        val updated = updateActiveScene(project) { sc ->
            val removedStart = target.startFrame
            val removedDuration = target.durationFrames
            val remaining = sc.clips.filterNot { it.id == clipId }

            val adjustedClips = if (ripple) {
                val earlierMaxEnd = remaining
                    .filter { it.layerId == target.layerId && it.endFrame <= removedStart }
                    .maxOfOrNull { it.endFrame } ?: 0
                val followingSorted = remaining
                    .filter { it.layerId == target.layerId && it.startFrame >= removedStart }
                    .sortedBy { it.startFrame }

                val shiftedById = HashMap<String, TimelineClip>(followingSorted.size)
                var minAllowedStart = max(earlierMaxEnd, removedStart - 0)
                // First following clip shifts left by full removedDuration (closing gap cleanly)
                for ((idx, c) in followingSorted.withIndex()) {
                    val shiftedStart = if (idx == 0) {
                        (c.startFrame - removedDuration).coerceAtLeast(earlierMaxEnd)
                    } else {
                        (c.startFrame - removedDuration).coerceAtLeast(minAllowedStart)
                    }
                    val shiftedClip = c.copy(startFrame = shiftedStart)
                    shiftedById[c.id] = shiftedClip
                    minAllowedStart = shiftedClip.endFrame
                }
                remaining.map { shiftedById[it.id] ?: it }
            } else {
                remaining
            }

            val nextFocus = if (sc.cameraFocusClipId == clipId) null else sc.cameraFocusClipId
            val nextUniversal = sc.universalKeyframeTracks.filterNot { it.targetId == clipId }
            sc.copy(
                clips = adjustedClips,
                cameraFocusClipId = nextFocus,
                universalKeyframeTracks = nextUniversal
            )
        }

        return ClipOperationResult(
            project = updated,
            changed = true,
            message = if (ripple) "Ripple-deleted ${target.name}" else "Deleted ${target.name}"
        )
    }

    fun deleteCameraBoxUniversal(
        project: ProjectDocument,
        boxId: String,
        ripple: Boolean = false
    ): ClipOperationResult {
        val scene = project.activeScene
        if (scene.cameraLayerLocked) {
            return ClipOperationResult(project, changed = false, message = "Camera Control layer is locked")
        }
        val target = scene.cameraBoxes.firstOrNull { it.id == boxId }
            ?: return ClipOperationResult(project, changed = false)

        val updated = updateActiveScene(project) { sc ->
            val remaining = sc.cameraBoxes.filterNot { it.id == boxId }.sortedBy { it.startFrame }
            val adjusted = if (ripple) {
                val gap = target.durationFrames
                remaining.map { box ->
                    if (box.startFrame >= target.startFrame) {
                        val ns = (box.startFrame - gap).coerceAtLeast(0)
                        val ne = (ns + box.durationFrames).coerceAtLeast(ns + CameraBoxEngine.MIN_BOX_DURATION_FRAMES)
                        box.copy(startFrame = ns, endFrame = ne)
                    } else {
                        box
                    }
                }
            } else {
                remaining
            }
            val relabeled = CameraBoxEngine.relabelChronologically(adjusted)
            val syncedKfs = CameraBoxEngine.toCameraKeyframes(relabeled)
            sc.copy(cameraBoxes = relabeled, cameraKeyframes = syncedKfs)
        }
        return ClipOperationResult(
            project = updated,
            changed = true,
            message = if (ripple) "Ripple-deleted Cam ${target.label}" else "Deleted Cam ${target.label}"
        )
    }

    // -------------------------------------------------------------------------
    // 8. Universal Hold (Cut) / Easing Toggle
    // -------------------------------------------------------------------------

    fun toggleClipHoldCutAtFrame(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int
    ): ClipOperationResult {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }

        val localFrame = (globalFrame - clip.startFrame).coerceIn(0, (clip.durationFrames - 1).coerceAtLeast(0))
        val exactKf = clip.keyframes.firstOrNull { it.frame == localFrame }
        val activeKf = exactKf ?: clip.keyframes.filter { it.frame <= localFrame }.maxByOrNull { it.frame }
        val currentlyHold = activeKf?.easing == EasingType.HOLD
        val nextEasing = if (currentlyHold) EasingType.EASE_IN_OUT else EasingType.HOLD

        val updated = updateActiveScene(project) { sc ->
            sc.copy(
                clips = sc.clips.map { c ->
                    if (c.id != clipId) c
                    else {
                        val nextKfs = if (exactKf != null) {
                            c.keyframes.map { kf ->
                                if (kf.frame == localFrame) kf.copy(easing = nextEasing) else kf
                            }
                        } else {
                            val eval = KeyframeEvaluator.evaluate(c, clip.startFrame + localFrame)
                            val stamped = evalToKeyframe(localFrame = localFrame, eval = eval).copy(easing = nextEasing)
                            (c.keyframes + stamped).sortedBy { it.frame }
                        }
                        c.copy(keyframes = nextKfs)
                    }
                }
            )
        }

        return ClipOperationResult(
            project = updated,
            primaryClipId = clipId,
            changed = true,
            message = if (nextEasing == EasingType.HOLD) {
                "${clip.name}: Hold (Instant Cut) at F${clip.startFrame + localFrame + 1}"
            } else {
                "${clip.name}: Smooth Ease In-Out at F${clip.startFrame + localFrame + 1}"
            }
        )
    }

    // -------------------------------------------------------------------------
    // 9. Universal Trim to Playhead (Both Clips & Camera Boxes)
    // -------------------------------------------------------------------------

    /**
     * Trims start or end edge of [clipId] cleanly to [globalFrame].
     * When [trimStart] is null, automatically chooses the edge closest to the playhead (or extends
     * start/end if playhead lies outside the clip).
     */
    fun trimClipToPlayhead(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int,
        trimStart: Boolean? = null
    ): ClipOperationResult {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Layer is locked")
        }

        val targetFrame = globalFrame.coerceAtLeast(0)
        val shouldTrimStart = trimStart ?: when {
            targetFrame <= clip.startFrame -> true
            targetFrame >= clip.endFrame -> false
            else -> (targetFrame - clip.startFrame) < (clip.endFrame - targetFrame)
        }

        val newStart: Int
        val newDuration: Int
        if (shouldTrimStart) {
            val maxStart = (clip.endFrame - MIN_CLIP_DURATION_FRAMES).coerceAtLeast(0)
            newStart = targetFrame.coerceIn(0, maxStart)
            newDuration = (clip.endFrame - newStart).coerceAtLeast(MIN_CLIP_DURATION_FRAMES)
        } else {
            newStart = clip.startFrame
            val minEnd = clip.startFrame + MIN_CLIP_DURATION_FRAMES
            val clampedEnd = targetFrame.coerceAtLeast(minEnd)
            newDuration = (clampedEnd - newStart).coerceAtLeast(MIN_CLIP_DURATION_FRAMES)
        }

        return trimOrMoveClip(
            project = project,
            clipId = clipId,
            newStartFrame = newStart,
            newDurationFrames = newDuration,
            isMoveOnly = false
        )
    }

    fun trimCameraBoxToPlayhead(
        project: ProjectDocument,
        boxId: String,
        globalFrame: Int,
        trimStart: Boolean? = null
    ): ClipOperationResult {
        val scene = project.activeScene
        if (scene.cameraLayerLocked) {
            return ClipOperationResult(project, changed = false, message = "Camera Control layer is locked")
        }
        val box = scene.cameraBoxes.firstOrNull { it.id == boxId }
            ?: return ClipOperationResult(project, changed = false)

        val targetFrame = globalFrame.coerceIn(0, scene.durationFrames.coerceAtLeast(CameraBoxEngine.MIN_BOX_DURATION_FRAMES))
        val shouldTrimStart = trimStart ?: when {
            targetFrame <= box.startFrame -> true
            targetFrame >= box.endFrame -> false
            else -> (targetFrame - box.startFrame) < (box.endFrame - targetFrame)
        }

        val (newStart, newEnd) = if (shouldTrimStart) {
            val s = targetFrame.coerceIn(0, (box.endFrame - CameraBoxEngine.MIN_BOX_DURATION_FRAMES).coerceAtLeast(0))
            s to box.endFrame
        } else {
            val e = targetFrame.coerceAtLeast(box.startFrame + CameraBoxEngine.MIN_BOX_DURATION_FRAMES)
            box.startFrame to e
        }

        if (newStart == box.startFrame && newEnd == box.endFrame) {
            return ClipOperationResult(project, changed = false)
        }

        val updated = updateActiveScene(project) { sc ->
            val nextDur = max(sc.durationFrames, newEnd)
            val updatedBoxes = sc.cameraBoxes.map { b ->
                if (b.id == boxId) b.copy(startFrame = newStart, endFrame = newEnd) else b
            }
            val relabeled = CameraBoxEngine.relabelChronologically(updatedBoxes)
            val syncedKfs = CameraBoxEngine.toCameraKeyframes(relabeled)
            sc.copy(durationFrames = nextDur, cameraBoxes = relabeled, cameraKeyframes = syncedKfs)
        }

        return ClipOperationResult(
            project = updated,
            changed = true,
            message = "Trimmed Cam ${box.label} to F${targetFrame + 1}"
        )
    }

    // -------------------------------------------------------------------------
    // 10. Cross-Track Drag & Drop (With Compatibility, Collision & +New Track)
    // -------------------------------------------------------------------------

    fun moveClipAcrossTracks(
        project: ProjectDocument,
        clipId: String,
        targetLayerId: String?,
        targetStartFrame: Int,
        createNewTrack: Boolean = false,
        insertNewTrackAtTop: Boolean = true
    ): ClipOperationResult {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId }
            ?: return ClipOperationResult(project, changed = false)
        val sourceLayer = scene.layers.firstOrNull { it.id == clip.layerId }
            ?: return ClipOperationResult(project, changed = false)

        if (sourceLayer.isLocked) {
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = "Source track is locked")
        }

        val desiredStart = targetStartFrame.coerceAtLeast(0)

        // Case A: Dropped onto "+ New Track" drop zone
        if (createNewTrack || targetLayerId == null) {
            val newLayerId = UUID.randomUUID().toString()
            val sameTypeCount = scene.layers.count { it.type == sourceLayer.type }
            val newLayerName = "${sourceLayer.type.displayName} ${sameTypeCount + 1}"
            val updated = updateActiveScene(project) { sc ->
                val sorted = sc.layers.sortedBy { it.orderIndex }.toMutableList()
                val newLayer = AnimationLayer(
                    id = newLayerId,
                    name = newLayerName,
                    type = sourceLayer.type,
                    colorHex = sourceLayer.colorHex,
                    blendMode = sourceLayer.blendMode,
                    parallaxDepth = sourceLayer.parallaxDepth
                )
                if (insertNewTrackAtTop) {
                    sorted.add(newLayer)
                } else {
                    sorted.add(0, newLayer)
                }
                val reindexed = sorted.mapIndexed { idx, l -> l.copy(orderIndex = idx) }
                val movedClip = clip.copy(layerId = newLayerId, startFrame = desiredStart)
                sc.copy(
                    durationFrames = max(sc.durationFrames, movedClip.endFrame),
                    layers = reindexed,
                    clips = sc.clips.map { if (it.id == clipId) movedClip else it }
                )
            }
            return ClipOperationResult(
                project = updated,
                primaryClipId = clipId,
                createdLayerId = newLayerId,
                changed = true,
                message = "Moved ${clip.name} to new track \"$newLayerName\""
            )
        }

        // Case B: Same-track horizontal reposition
        if (targetLayerId == sourceLayer.id) {
            return trimOrMoveClip(
                project = project,
                clipId = clipId,
                newStartFrame = desiredStart,
                newDurationFrames = clip.durationFrames,
                isMoveOnly = true
            )
        }

        // Case C: Cross-track move onto an existing target layer
        val targetLayer = scene.layers.firstOrNull { it.id == targetLayerId }
            ?: return ClipOperationResult(project, primaryClipId = clipId, changed = false)

        if (targetLayer.isLocked) {
            return ClipOperationResult(
                project = project,
                primaryClipId = clipId,
                changed = false,
                message = "${targetLayer.name} is locked"
            )
        }

        val targetClips = scene.clips.filter { it.layerId == targetLayer.id && it.id != clipId }
        val compatible = areLayerTypesCompatible(
            sourceType = sourceLayer.type,
            targetType = targetLayer.type,
            targetHasClips = targetClips.isNotEmpty()
        )
        if (!compatible) {
            val msg = if (sourceLayer.type == LayerType.AUDIO || targetLayer.type == LayerType.AUDIO) {
                "Audio clips can only drop onto Audio tracks"
            } else {
                "Incompatible track: drop onto a ${sourceLayer.type.displayName} track or + New Track"
            }
            return ClipOperationResult(project, primaryClipId = clipId, changed = false, message = msg)
        }

        val overlap = resolveTrackOverlap(
            existingClipsOnLayer = targetClips,
            movingClipId = clipId,
            desiredStartFrame = desiredStart,
            durationFrames = clip.durationFrames
        )

        // If target track is heavily packed, cleanly spawn an adjacent lane right next to it
        if (overlap.needsNewLane) {
            val newLayerId = UUID.randomUUID().toString()
            val laneName = "${targetLayer.name} (Lane)"
            val updated = updateActiveScene(project) { sc ->
                val sorted = sc.layers.sortedBy { it.orderIndex }.toMutableList()
                val targetIdx = sorted.indexOfFirst { it.id == targetLayer.id }
                val insertIdx = if (targetIdx >= 0) (targetIdx + 1).coerceAtMost(sorted.size) else sorted.size
                val newLane = AnimationLayer(
                    id = newLayerId,
                    name = laneName,
                    type = sourceLayer.type,
                    colorHex = sourceLayer.colorHex,
                    blendMode = targetLayer.blendMode,
                    parallaxDepth = targetLayer.parallaxDepth
                )
                sorted.add(insertIdx, newLane)
                val reindexed = sorted.mapIndexed { idx, l -> l.copy(orderIndex = idx) }
                val movedClip = clip.copy(layerId = newLayerId, startFrame = desiredStart)
                sc.copy(
                    durationFrames = max(sc.durationFrames, movedClip.endFrame),
                    layers = reindexed,
                    clips = sc.clips.map { if (it.id == clipId) movedClip else it }
                )
            }
            return ClipOperationResult(
                project = updated,
                primaryClipId = clipId,
                createdLayerId = newLayerId,
                changed = true,
                message = "Placed ${clip.name} on adjacent lane \"$laneName\""
            )
        }

        val finalStart = overlap.resolvedStartFrame
        val movedClip = clip.copy(layerId = targetLayer.id, startFrame = finalStart)
        val shouldAdoptType = targetClips.isEmpty() && targetLayer.type != sourceLayer.type

        val updated = updateActiveScene(project) { sc ->
            val nextLayers = if (shouldAdoptType) {
                sc.layers.map { l ->
                    if (l.id == targetLayer.id) l.copy(type = sourceLayer.type, colorHex = sourceLayer.colorHex)
                    else l
                }
            } else {
                sc.layers
            }
            sc.copy(
                durationFrames = max(sc.durationFrames, movedClip.endFrame),
                layers = nextLayers,
                clips = sc.clips.map { if (it.id == clipId) movedClip else it }
            )
        }

        val snapNote = if (overlap.collidedClipId != null) " (snapped adjacent)" else ""
        return ClipOperationResult(
            project = updated,
            primaryClipId = clipId,
            changed = true,
            message = "Moved ${clip.name} to ${targetLayer.name} at F${finalStart + 1}$snapNote"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun evalToKeyframe(localFrame: Int, eval: EvaluatedTransform): TransformKeyframe =
        TransformKeyframe(
            frame = localFrame,
            x = eval.x,
            y = eval.y,
            rotation = eval.rotation,
            scaleX = eval.scaleX,
            scaleY = eval.scaleY,
            opacity = eval.opacity,
            poseOffsets = eval.poseOffsets,
            expressionOverride = eval.expressionOverride,
            facialPresetId = eval.facialPresetId,
            mouthShapeOverride = eval.mouthShapeOverride,
            eyeShapeOverride = eval.eyeShapeOverride,
            eyebrowShapeOverride = eval.eyebrowShapeOverride,
            handPoseOverride = eval.handPoseOverride,
            armGestureOverride = eval.armGestureOverride,
            blinkOverride = eval.blinkOverride
        )

    private fun updateActiveScene(
        project: ProjectDocument,
        transform: (AnimationScene) -> AnimationScene
    ): ProjectDocument {
        if (project.scenes.isEmpty()) return project
        val idx = project.activeSceneIndex.coerceIn(0, project.scenes.lastIndex)
        val nextScenes = project.scenes.mapIndexed { i, sc -> if (i == idx) transform(sc) else sc }
        return project.copy(scenes = nextScenes, updatedAt = System.currentTimeMillis())
    }
}
