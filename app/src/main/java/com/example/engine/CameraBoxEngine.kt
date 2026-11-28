package com.example.engine

import com.example.model.CameraBoxClip
import com.example.model.CameraKeyframe
import com.example.model.EasingType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class CameraBoxHandle {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    EDGE_TOP,
    EDGE_BOTTOM,
    EDGE_LEFT,
    EDGE_RIGHT,
    ROTATE_STEM
}

data class CameraBoxGeometry(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDeg: Float,
    val topLeft: Pair<Float, Float>,
    val topRight: Pair<Float, Float>,
    val bottomRight: Pair<Float, Float>,
    val bottomLeft: Pair<Float, Float>,
    val edgeTop: Pair<Float, Float>,
    val edgeRight: Pair<Float, Float>,
    val edgeBottom: Pair<Float, Float>,
    val edgeLeft: Pair<Float, Float>,
    val rotateHandle: Pair<Float, Float>
) {
    val corners: List<Pair<Float, Float>>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    val edges: List<Pair<Float, Float>>
        get() = listOf(edgeTop, edgeRight, edgeBottom, edgeLeft)

    val aspectRatio: Float
        get() = width / height.coerceAtLeast(1e-4f)
}

/**
 * Pure, deterministic math & evaluation engine for Visual On-Canvas Multi-Camera Boxes (A, B, C...).
 *
 * Coordinate guarantees:
 * - In Director / Stage Mode, the full 0..1 scene canvas is displayed zoomed out at [DIRECTOR_STAGE_ZOOM]
 *   so off-screen framing margins and all camera boxes are visible simultaneously.
 * - Every Camera Box maintains an EXACT aspect ratio lock equal to `stageWidth / stageHeight`
 *   (matching 16:9, 9:16, 1:1, 4:5) because `boxPxW = stageW / zoom` and `boxPxH = stageH / zoom`.
 * - In Camera Preview Mode, Playback, and MP4/GIF/PNG export, [evaluateCameraBoxes] produces the
 *   exact [EvaluatedCamera] crop/pan/zoom/rotation transform matching the visual box geometry.
 */
object CameraBoxEngine {

    const val DIRECTOR_STAGE_ZOOM = 0.72f
    const val MIN_ZOOM = 0.35f
    const val MAX_ZOOM = 3.5f
    const val MIN_BOX_DURATION_FRAMES = 4
    const val MIN_CENTER_NORM = -0.35f
    const val MAX_CENTER_NORM = 1.35f

    /** Converts a 0-based index into alphabetical camera badge labels: 0->"A", 1->"B", ..., 26->"AA". */
    fun indexToLabel(index: Int): String {
        var n = index.coerceAtLeast(0)
        val sb = StringBuilder()
        while (true) {
            sb.append(('A'.code + (n % 26)).toChar())
            n = (n / 26) - 1
            if (n < 0) break
        }
        return sb.reverse().toString()
    }

    /** Sorts boxes chronologically by startFrame and assigns clean sequential A, B, C... labels. */
    fun relabelChronologically(boxes: List<CameraBoxClip>): List<CameraBoxClip> =
        boxes.sortedWith(compareBy<CameraBoxClip> { it.startFrame }.thenBy { it.endFrame })
            .mapIndexed { idx, box -> box.copy(label = indexToLabel(idx)) }

    /** Maps normalized stage coordinates (0..1) into Director Stage viewport pixels. */
    fun stageToDirectorPx(
        nx: Float,
        ny: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM
    ): Pair<Float, Float> {
        val w = canvasWidth.coerceAtLeast(1f)
        val h = canvasHeight.coerceAtLeast(1f)
        val px = w * 0.5f + (nx - 0.5f) * w * directorZoom
        val py = h * 0.5f + (ny - 0.5f) * h * directorZoom
        return px to py
    }

    /** Maps Director Stage viewport pixels back into normalized stage coordinates. */
    fun directorPxToStage(
        px: Float,
        py: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM
    ): Pair<Float, Float> {
        val w = canvasWidth.coerceAtLeast(1f)
        val h = canvasHeight.coerceAtLeast(1f)
        val z = directorZoom.coerceAtLeast(0.1f)
        val nx = 0.5f + (px - w * 0.5f) / (w * z)
        val ny = 0.5f + (py - h * 0.5f) / (h * z)
        return nx to ny
    }

    /**
     * Computes the exact aspect-ratio-locked rectangle, 4 corner handles, 4 edge handles,
     * and top rotation stem handle in Director viewport pixel coordinates.
     */
    fun computeBoxGeometry(
        box: CameraBoxClip,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM,
        stemLengthPx: Float = 26f
    ): CameraBoxGeometry {
        val w = canvasWidth.coerceAtLeast(1f)
        val h = canvasHeight.coerceAtLeast(1f)
        val z = box.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val (cx, cy) = stageToDirectorPx(box.centerX, box.centerY, w, h, directorZoom)
        val boxW = (w * directorZoom) / z
        val boxH = (h * directorZoom) / z
        val hw = boxW * 0.5f
        val hh = boxH * 0.5f

        val rad = box.rotation * (PI.toFloat() / 180f)
        val cosR = cos(rad)
        val sinR = sin(rad)

        fun localToWorld(lx: Float, ly: Float): Pair<Float, Float> {
            val rx = lx * cosR - ly * sinR
            val ry = lx * sinR + ly * cosR
            return (cx + rx) to (cy + ry)
        }

        return CameraBoxGeometry(
            centerX = cx,
            centerY = cy,
            width = boxW,
            height = boxH,
            rotationDeg = box.rotation,
            topLeft = localToWorld(-hw, -hh),
            topRight = localToWorld(hw, -hh),
            bottomRight = localToWorld(hw, hh),
            bottomLeft = localToWorld(-hw, hh),
            edgeTop = localToWorld(0f, -hh),
            edgeRight = localToWorld(hw, 0f),
            edgeBottom = localToWorld(0f, hh),
            edgeLeft = localToWorld(-hw, 0f),
            rotateHandle = localToWorld(0f, -hh - stemLengthPx)
        )
    }

    /** Hit-tests a single camera box's handles (rotate stem > corners > edges > interior center). */
    fun hitTestHandle(
        box: CameraBoxClip,
        touchPxX: Float,
        touchPxY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM,
        handleRadiusPx: Float = 28f,
        stemLengthPx: Float = 26f
    ): CameraBoxHandle? {
        val geom = computeBoxGeometry(box, canvasWidth, canvasHeight, directorZoom, stemLengthPx)

        if (hypot(touchPxX - geom.rotateHandle.first, touchPxY - geom.rotateHandle.second) <= handleRadiusPx) {
            return CameraBoxHandle.ROTATE_STEM
        }

        val cornerPairs = listOf(
            CameraBoxHandle.TOP_LEFT to geom.topLeft,
            CameraBoxHandle.TOP_RIGHT to geom.topRight,
            CameraBoxHandle.BOTTOM_RIGHT to geom.bottomRight,
            CameraBoxHandle.BOTTOM_LEFT to geom.bottomLeft
        )
        var bestCorner: CameraBoxHandle? = null
        var bestCornerDist = handleRadiusPx
        for ((handle, pt) in cornerPairs) {
            val d = hypot(touchPxX - pt.first, touchPxY - pt.second)
            if (d <= bestCornerDist) {
                bestCornerDist = d
                bestCorner = handle
            }
        }
        if (bestCorner != null) return bestCorner

        val edgePairs = listOf(
            CameraBoxHandle.EDGE_TOP to geom.edgeTop,
            CameraBoxHandle.EDGE_RIGHT to geom.edgeRight,
            CameraBoxHandle.EDGE_BOTTOM to geom.edgeBottom,
            CameraBoxHandle.EDGE_LEFT to geom.edgeLeft
        )
        var bestEdge: CameraBoxHandle? = null
        var bestEdgeDist = handleRadiusPx * 0.92f
        for ((handle, pt) in edgePairs) {
            val d = hypot(touchPxX - pt.first, touchPxY - pt.second)
            if (d <= bestEdgeDist) {
                bestEdgeDist = d
                bestEdge = handle
            }
        }
        if (bestEdge != null) return bestEdge

        // Interior rotated hit-test (center drag)
        val dx = touchPxX - geom.centerX
        val dy = touchPxY - geom.centerY
        val rad = -geom.rotationDeg * (PI.toFloat() / 180f)
        val localX = dx * cos(rad) - dy * sin(rad)
        val localY = dx * sin(rad) + dy * cos(rad)
        if (abs(localX) <= geom.width * 0.5f && abs(localY) <= geom.height * 0.5f) {
            return CameraBoxHandle.CENTER
        }
        return null
    }

    /**
     * Hit-tests all camera boxes on stage, prioritizing the currently selected box's handles first
     * so overlapping boxes remain easy to resize/rotate.
     */
    fun hitTestBoxes(
        boxes: List<CameraBoxClip>,
        selectedBoxId: String?,
        touchPxX: Float,
        touchPxY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM,
        handleRadiusPx: Float = 28f,
        stemLengthPx: Float = 26f
    ): Pair<CameraBoxClip, CameraBoxHandle>? {
        if (boxes.isEmpty()) return null
        val selected = selectedBoxId?.let { id -> boxes.firstOrNull { it.id == id } }
        if (selected != null) {
            val h = hitTestHandle(
                selected, touchPxX, touchPxY, canvasWidth, canvasHeight,
                directorZoom, handleRadiusPx, stemLengthPx
            )
            if (h != null) return selected to h
        }
        // Check remaining boxes in reverse order (topmost drawn box wins)
        for (box in boxes.asReversed()) {
            if (box.id == selectedBoxId) continue
            val h = hitTestHandle(
                box, touchPxX, touchPxY, canvasWidth, canvasHeight,
                directorZoom, handleRadiusPx, stemLengthPx
            )
            if (h != null) return box to h
        }
        return null
    }

    /** Applies center pan drag in Director viewport pixels. */
    fun applyPanDrag(
        baseBox: CameraBoxClip,
        totalDeltaPxX: Float,
        totalDeltaPxY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM
    ): CameraBoxClip {
        val w = (canvasWidth * directorZoom).coerceAtLeast(1f)
        val h = (canvasHeight * directorZoom).coerceAtLeast(1f)
        return baseBox.copy(
            centerX = (baseBox.centerX + totalDeltaPxX / w).coerceIn(MIN_CENTER_NORM, MAX_CENTER_NORM),
            centerY = (baseBox.centerY + totalDeltaPxY / h).coerceIn(MIN_CENTER_NORM, MAX_CENTER_NORM)
        )
    }

    /**
     * Resizes a camera box from any corner or edge handle while strictly preserving the project's
     * aspect ratio (`boxWidth / boxHeight == canvasWidth / canvasHeight`).
     * Smaller box = zoomed IN (higher zoom); larger box = zoomed OUT (lower zoom).
     */
    fun applyAspectLockedResize(
        box: CameraBoxClip,
        handle: CameraBoxHandle,
        pointerPxX: Float,
        pointerPxY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM
    ): CameraBoxClip {
        val w = canvasWidth.coerceAtLeast(1f)
        val h = canvasHeight.coerceAtLeast(1f)
        val (cx, cy) = stageToDirectorPx(box.centerX, box.centerY, w, h, directorZoom)
        val dx = pointerPxX - cx
        val dy = pointerPxY - cy

        // Transform pointer vector into the camera box's unrotated local space
        val rad = -box.rotation * (PI.toFloat() / 180f)
        val localX = dx * cos(rad) - dy * sin(rad)
        val localY = dx * sin(rad) + dy * cos(rad)

        val stageHalfW = (w * directorZoom * 0.5f).coerceAtLeast(1f)
        val stageHalfH = (h * directorZoom * 0.5f).coerceAtLeast(1f)

        val rx = abs(localX) / stageHalfW
        val ry = abs(localY) / stageHalfH

        val normalizedScale = when (handle) {
            CameraBoxHandle.TOP_LEFT,
            CameraBoxHandle.TOP_RIGHT,
            CameraBoxHandle.BOTTOM_LEFT,
            CameraBoxHandle.BOTTOM_RIGHT -> max(0.05f, (rx + ry) * 0.5f)
            CameraBoxHandle.EDGE_LEFT,
            CameraBoxHandle.EDGE_RIGHT -> max(0.05f, rx)
            CameraBoxHandle.EDGE_TOP,
            CameraBoxHandle.EDGE_BOTTOM -> max(0.05f, ry)
            else -> 1f / box.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        }

        val newZoom = (1f / normalizedScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return box.copy(zoom = newZoom)
    }

    /** Applies Dutch tilt rotation from dragging the top rotation stem handle. */
    fun applyRotationDrag(
        box: CameraBoxClip,
        pointerPxX: Float,
        pointerPxY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        directorZoom: Float = DIRECTOR_STAGE_ZOOM,
        snapCardinalDeg: Float = 2.0f
    ): CameraBoxClip {
        val (cx, cy) = stageToDirectorPx(box.centerX, box.centerY, canvasWidth, canvasHeight, directorZoom)
        val dx = pointerPxX - cx
        val dy = pointerPxY - cy
        if (hypot(dx, dy) < 4f) return box
        // Top stem is at -90 deg in screen coords, so add +90 deg so straight-up = 0 deg
        var angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * (180.0 / PI)).toFloat() + 90f
        while (angleDeg > 180f) angleDeg -= 360f
        while (angleDeg < -180f) angleDeg += 360f

        // Gentle magnetic snap to crisp horizon angles (0, ±90, ±180)
        val cardinals = floatArrayOf(0f, 90f, -90f, 180f, -180f)
        for (c in cardinals) {
            if (abs(angleDeg - c) <= snapCardinalDeg) {
                angleDeg = if (c == -180f) 180f else c
                break
            }
        }
        return box.copy(rotation = angleDeg.coerceIn(-180f, 180f))
    }

    /**
     * Evaluates the smooth interpolated camera transform across all Camera Boxes (`A -> B -> C`)
     * at [globalFrame], supporting `LINEAR`, `EASE_IN_OUT`, `HOLD` (instant cut), `CUSTOM_CUBIC`,
     * and every [EasingType].
     *
     * Rules:
     * - If `prev.easing == EasingType.HOLD`, camera holds strictly at `prev` until `globalFrame >= next.startFrame`,
     *   producing a frame-accurate instant cut to `next`.
     * - If `prev` and `next` are contiguous/overlapping (`prev.endFrame >= next.startFrame`),
     *   camera interpolates smoothly from `prev` to `next` over `[prev.startFrame .. next.startFrame]`.
     * - If there is a gap (`prev.endFrame < next.startFrame`), camera holds `prev` during
     *   `[prev.startFrame .. prev.endFrame]` and interpolates across the gap `[prev.endFrame .. next.startFrame]`.
     */
    fun evaluateCameraBoxes(
        boxes: List<CameraBoxClip>,
        globalFrame: Int
    ): EvaluatedCamera {
        if (boxes.isEmpty()) return EvaluatedCamera()
        val sorted = ensureSortedBoxes(boxes)
        val first = sorted.first()
        if (sorted.size == 1 || globalFrame <= first.startFrame) {
            return first.toEvaluatedCamera()
        }
        val last = sorted.last()
        if (globalFrame >= last.startFrame) {
            return last.toEvaluatedCamera()
        }

        var prev = first
        var next = last
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (globalFrame in a.startFrame until b.startFrame) {
                prev = a
                next = b
                break
            }
        }

        if (prev.easing == EasingType.HOLD) {
            return prev.toEvaluatedCamera()
        }

        val transitionStart = if (prev.endFrame in (prev.startFrame + 1) until next.startFrame) {
            if (globalFrame <= prev.endFrame) {
                return prev.toEvaluatedCamera()
            }
            prev.endFrame
        } else {
            prev.startFrame
        }

        val span = (next.startFrame - transitionStart).coerceAtLeast(1)
        val rawT = ((globalFrame - transitionStart).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val t = KeyframeEvaluator.evaluateEasing(
            easing = prev.easing,
            rawT = rawT,
            p1x = prev.easingP1X,
            p1y = prev.easingP1Y,
            p2x = prev.easingP2X,
            p2y = prev.easingP2Y,
            isAtEnd = globalFrame >= next.startFrame
        )
        return EvaluatedCamera(
            x = prev.centerX + (next.centerX - prev.centerX) * t,
            y = prev.centerY + (next.centerY - prev.centerY) * t,
            zoom = (prev.zoom + (next.zoom - prev.zoom) * t).coerceIn(MIN_ZOOM, MAX_ZOOM),
            rotation = lerpAngleDeg(prev.rotation, next.rotation, t)
        )
    }

    /** Returns the Camera Box active at [globalFrame] (preferring the box whose span contains the frame). */
    fun activeCameraBoxAtFrame(boxes: List<CameraBoxClip>, globalFrame: Int): CameraBoxClip? {
        if (boxes.isEmpty()) return null
        val sorted = ensureSortedBoxes(boxes)
        sorted.firstOrNull { globalFrame in it.startFrame until it.endFrame }?.let { return it }
        return sorted.lastOrNull { it.startFrame <= globalFrame } ?: sorted.first()
    }

    /** Converts camera boxes into equivalent [CameraKeyframe]s for legacy consumers. */
    fun toCameraKeyframes(boxes: List<CameraBoxClip>): List<CameraKeyframe> =
        ensureSortedBoxes(boxes).map { b ->
            CameraKeyframe(
                frame = b.startFrame,
                x = b.centerX,
                y = b.centerY,
                zoom = b.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
                rotation = b.rotation,
                easing = b.easing,
                easingP1X = b.easingP1X,
                easingP1Y = b.easingP1Y,
                easingP2X = b.easingP2X,
                easingP2Y = b.easingP2Y
            )
        }

    /** Converts existing [CameraKeyframe]s into chronological visual Camera Boxes (A, B, C...). */
    fun fromCameraKeyframes(
        keyframes: List<CameraKeyframe>,
        sceneDurationFrames: Int
    ): List<CameraBoxClip> {
        if (keyframes.isEmpty()) return emptyList()
        val sorted = keyframes.sortedBy { it.frame }
        val maxEnd = sceneDurationFrames.coerceAtLeast(MIN_BOX_DURATION_FRAMES)
        return sorted.mapIndexed { idx, kf ->
            val nextStart = sorted.getOrNull(idx + 1)?.frame
            val end = (nextStart ?: maxEnd).coerceAtLeast(kf.frame + MIN_BOX_DURATION_FRAMES)
            CameraBoxClip(
                id = "cam_box_${indexToLabel(idx).lowercase()}_${kf.frame}",
                label = indexToLabel(idx),
                startFrame = kf.frame.coerceAtLeast(0),
                endFrame = end.coerceAtMost(maxEnd.coerceAtLeast(kf.frame + MIN_BOX_DURATION_FRAMES)),
                centerX = kf.x.coerceIn(MIN_CENTER_NORM, MAX_CENTER_NORM),
                centerY = kf.y.coerceIn(MIN_CENTER_NORM, MAX_CENTER_NORM),
                zoom = kf.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
                rotation = kf.rotation.coerceIn(-180f, 180f),
                easing = kf.easing,
                easingP1X = kf.easingP1X,
                easingP1Y = kf.easingP1Y,
                easingP2X = kf.easingP2X,
                easingP2Y = kf.easingP2Y
            )
        }
    }

    private fun CameraBoxClip.toEvaluatedCamera() = EvaluatedCamera(
        x = centerX,
        y = centerY,
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        rotation = rotation
    )

    private fun ensureSortedBoxes(boxes: List<CameraBoxClip>): List<CameraBoxClip> {
        if (boxes.size <= 1) return boxes
        for (i in 0 until boxes.size - 1) {
            if (boxes[i].startFrame > boxes[i + 1].startFrame) {
                return boxes.sortedWith(compareBy<CameraBoxClip> { it.startFrame }.thenBy { it.endFrame })
            }
        }
        return boxes
    }

    /** Shortest-arc angular interpolation so e.g. 170° -> -170° rotates +20° instead of spinning 340°. */
    private fun lerpAngleDeg(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return a + diff * t
    }
}
