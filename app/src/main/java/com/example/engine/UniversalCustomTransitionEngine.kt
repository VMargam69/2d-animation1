package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class EvaluatedCustomTransition(
    val rawProgress: Float,
    val easedProgress: Float,
    val outgoingDx: Float,
    val outgoingDy: Float,
    val outgoingScale: Float,
    val outgoingRotation: Float,
    val outgoingAlpha: Float,
    val incomingDx: Float,
    val incomingDy: Float,
    val incomingScale: Float,
    val incomingRotation: Float,
    val incomingAlpha: Float,
    val flashAlpha: Float,
    val flashColorHex: String?
)

/**
 * Universal Declarative Custom Transition Engine.
 *
 * Evaluates and composites [CustomTransitionDef] transitions defined in `.fcscript.json`
 * (`NONE`, `WIPE_ANGLE`, `IRIS_CIRCLE`, `SPLIT_CURTAIN`, `CUSTOM_SVG_PATH`, outgoing/incoming
 * motion transforms, flash overlay, and exact `cubicBezier` easing) smoothly onto Android
 * [Canvas] for both live preview and MP4 / GIF / PNG Sequence export.
 */
object UniversalCustomTransitionEngine {

    /**
     * Pure-math evaluation of [def] at [rawProgress] in `0f..1f` (unit-testable on JVM).
     */
    fun evaluate(def: CustomTransitionDef, rawProgress: Float): EvaluatedCustomTransition {
        val raw = rawProgress.coerceIn(0f, 1f)
        val bez = def.cubicBezier
        val p = if (bez.size >= 4) {
            KeyframeEvaluator.evaluateEasing(
                easing = EasingType.CUSTOM_CUBIC,
                rawT = raw,
                p1x = bez[0],
                p1y = bez[1],
                p2x = bez[2],
                p2y = bez[3],
                isAtEnd = raw >= 1f
            )
        } else {
            KeyframeEvaluator.evaluateEasing(EasingType.EASE_IN_OUT, raw)
        }
        val clampedP = p.coerceIn(0f, 1f)

        // Outgoing moves from identity (0,0,1,0,1) -> def.outgoing as p goes 0 -> 1
        val outDx = lerp(0f, def.outgoing.dx, p)
        val outDy = lerp(0f, def.outgoing.dy, p)
        val outScale = lerp(1f, def.outgoing.scale.coerceIn(0.1f, 4f), p).coerceAtLeast(0.05f)
        val outRot = lerp(0f, def.outgoing.rotation, p)
        val outAlpha = lerp(1f, def.outgoing.alpha.coerceIn(0f, 1f), clampedP).coerceIn(0f, 1f)

        // Incoming moves from def.incoming -> identity (0,0,1,0,1) as p goes 0 -> 1
        val inv = 1f - p
        val inDx = def.incoming.dx * inv
        val inDy = def.incoming.dy * inv
        val inScale = lerp(def.incoming.scale.coerceIn(0.1f, 4f), 1f, p).coerceAtLeast(0.05f)
        val inRot = def.incoming.rotation * inv
        val inAlpha = lerp(
            if (def.clipPathType == CustomTransitionClipType.NONE) 0f else def.incoming.alpha.coerceIn(0f, 1f),
            1f,
            clampedP
        ).coerceIn(0f, 1f)

        // Smooth bell curve peaking at raw = 0.5 for flash overlay
        val flashPeak = def.flashOverlay?.peakAlpha?.coerceIn(0f, 1f) ?: 0f
        val flashBell = sin(raw * PI).toFloat().coerceIn(0f, 1f)
        val flashAlpha = (flashPeak * flashBell * flashBell).coerceIn(0f, 1f)

        return EvaluatedCustomTransition(
            rawProgress = raw,
            easedProgress = p,
            outgoingDx = outDx,
            outgoingDy = outDy,
            outgoingScale = outScale,
            outgoingRotation = outRot,
            outgoingAlpha = outAlpha,
            incomingDx = inDx,
            incomingDy = inDy,
            incomingScale = inScale,
            incomingRotation = inRot,
            incomingAlpha = inAlpha,
            flashAlpha = flashAlpha,
            flashColorHex = def.flashOverlay?.colorHex
        )
    }

    /**
     * Composites [outgoingBitmap] -> [incomingBitmap] on [canvas] using [def] at [rawProgress] (`0..1`).
     * Either bitmap may be null (e.g., fade/wipe in from dark stage or out to dark stage).
     */
    fun compositeBitmaps(
        canvas: Canvas,
        width: Int,
        height: Int,
        outgoingBitmap: Bitmap?,
        incomingBitmap: Bitmap?,
        def: CustomTransitionDef,
        rawProgress: Float,
        backdropColor: Int = Color.BLACK
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val state = evaluate(def, rawProgress)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val fullRect = RectF(0f, 0f, w, h)

        canvas.drawColor(backdropColor)

        // 1. Draw Outgoing Scene
        if (outgoingBitmap != null && !outgoingBitmap.isRecycled && state.outgoingAlpha > 0.01f) {
            canvas.save()
            canvas.translate(w * 0.5f + state.outgoingDx * w, h * 0.5f + state.outgoingDy * h)
            canvas.rotate(state.outgoingRotation)
            canvas.scale(state.outgoingScale, state.outgoingScale)
            canvas.translate(-w * 0.5f, -h * 0.5f)
            paint.alpha = (state.outgoingAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(outgoingBitmap, null, fullRect, paint)
            canvas.restore()
        }

        // 2. Draw Incoming Scene clipped by clipPathType
        if (incomingBitmap != null && !incomingBitmap.isRecycled && state.incomingAlpha > 0.01f) {
            canvas.save()
            val clipPath = buildClipPath(def, w, h, state.easedProgress.coerceIn(0f, 1f))
            if (clipPath != null) {
                canvas.clipPath(clipPath)
            }
            canvas.translate(w * 0.5f + state.incomingDx * w, h * 0.5f + state.incomingDy * h)
            canvas.rotate(state.incomingRotation)
            canvas.scale(state.incomingScale, state.incomingScale)
            canvas.translate(-w * 0.5f, -h * 0.5f)
            paint.alpha = (state.incomingAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(incomingBitmap, null, fullRect, paint)
            canvas.restore()
        }

        // 3. Draw Flash Overlay
        if (state.flashAlpha > 0.01f && !state.flashColorHex.isNullOrBlank()) {
            val baseColor = parseColorSafe(state.flashColorHex, Color.WHITE)
            val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    (state.flashAlpha * 255f).toInt().coerceIn(0, 255),
                    Color.red(baseColor),
                    Color.green(baseColor),
                    Color.blue(baseColor)
                )
            }
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }
    }

    /**
     * Applies a custom scene-in or scene-out transition directly onto the live/export [canvas]
     * when rendering a single scene frame (no separate adjacent scene bitmap required).
     */
    fun applySingleSceneMaskAndFlash(
        canvas: Canvas,
        width: Int,
        height: Int,
        def: CustomTransitionDef,
        rawProgress: Float,
        isEntering: Boolean
    ) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val progress = if (isEntering) rawProgress.coerceIn(0f, 1f) else (1f - rawProgress.coerceIn(0f, 1f))
        val state = evaluate(def, if (isEntering) rawProgress else (1f - rawProgress))

        // Draw dark matte outside the reveal shape
        val revealPath = buildClipPath(def, w, h, state.easedProgress.coerceIn(0f, 1f))
        if (revealPath != null) {
            val mattePath = Path().apply {
                addRect(0f, 0f, w, h, Path.Direction.CW)
                op(revealPath, Path.Op.DIFFERENCE)
            }
            val mattePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.BLACK
            }
            canvas.drawPath(mattePath, mattePaint)
        } else {
            val dimAlpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
            if (dimAlpha > 2) {
                val dimPaint = Paint().apply {
                    style = Paint.Style.FILL
                    color = Color.argb(dimAlpha, 0, 0, 0)
                }
                canvas.drawRect(0f, 0f, w, h, dimPaint)
            }
        }

        // Flash overlay
        if (state.flashAlpha > 0.01f && !state.flashColorHex.isNullOrBlank()) {
            val baseColor = parseColorSafe(state.flashColorHex, Color.WHITE)
            val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    (state.flashAlpha * 255f).toInt().coerceIn(0, 255),
                    Color.red(baseColor),
                    Color.green(baseColor),
                    Color.blue(baseColor)
                )
            }
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }
    }

    /**
     * Builds the clip [Path] representing the revealed area of the incoming scene at [progress] (`0..1`).
     */
    fun buildClipPath(
        def: CustomTransitionDef,
        w: Float,
        h: Float,
        progress: Float
    ): Path? {
        val p = progress.coerceIn(0f, 1f)
        return when (def.clipPathType) {
            CustomTransitionClipType.NONE -> null
            CustomTransitionClipType.WIPE_ANGLE -> {
                val diag = hypot(w, h)
                val cx = w * 0.5f
                val cy = h * 0.5f
                val path = Path().apply {
                    addRect(-diag, -diag, -diag + diag * 2f * p, diag, Path.Direction.CW)
                }
                val matrix = Matrix().apply {
                    postRotate(def.angleDeg)
                    postTranslate(cx, cy)
                }
                path.transform(matrix)
                path
            }
            CustomTransitionClipType.IRIS_CIRCLE -> {
                val maxR = hypot(w * 0.5f, h * 0.5f) * 1.05f
                Path().apply {
                    addCircle(w * 0.5f, h * 0.5f, maxR * p, Path.Direction.CW)
                }
            }
            CustomTransitionClipType.SPLIT_CURTAIN -> {
                val halfOpenW = (w * 0.5f) * p
                Path().apply {
                    addRect(w * 0.5f - halfOpenW, 0f, w * 0.5f + halfOpenW, h, Path.Direction.CW)
                }
            }
            CustomTransitionClipType.CUSTOM_SVG_PATH -> {
                val parsed = parseNormalizedSvgPath(def.customSvgPath, w, h)
                if (parsed == null) {
                    val maxR = hypot(w * 0.5f, h * 0.5f) * 1.05f
                    Path().apply { addCircle(w * 0.5f, h * 0.5f, maxR * p, Path.Direction.CW) }
                } else {
                    val scale = (0.02f + p * 1.65f)
                    val matrix = Matrix().apply {
                        postTranslate(-w * 0.5f, -h * 0.5f)
                        postScale(scale, scale)
                        postRotate(def.angleDeg * (1f - p))
                        postTranslate(w * 0.5f, h * 0.5f)
                    }
                    parsed.transform(matrix)
                    parsed
                }
            }
        }
    }

    /**
     * Safely parses a subset of SVG path commands (`M`, `L`, `H`, `V`, `Q`, `C`, `Z`) in normalized
     * `0..1` coordinates (or `0..100` if coordinates exceed `1.5`) into an Android [Path].
     */
    fun parseNormalizedSvgPath(d: String?, w: Float, h: Float): Path? {
        if (d.isNullOrBlank() || d.length > 4096) return null
        val tokens = tokenizeSvgPath(d)
        if (tokens.isEmpty()) return null

        // Auto-detect if path uses 0..100 coordinates instead of 0..1
        val maxCoord = tokens.mapNotNull { it.toFloatOrNull() }.maxOfOrNull { abs(it) } ?: 1f
        val normDiv = if (maxCoord > 1.5f) 100f else 1f
        val sx = w / normDiv
        val sy = h / normDiv

        val path = Path()
        var idx = 0
        var cmd = ' '
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var drewAny = false

        fun nextFloat(): Float? {
            if (idx >= tokens.size) return null
            val v = tokens[idx].toFloatOrNull() ?: return null
            idx++
            return v
        }

        while (idx < tokens.size) {
            val tok = tokens[idx]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                idx++
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = nextFloat() ?: break
                    val y = nextFloat() ?: break
                    curX = if (cmd == 'm') curX + x else x
                    curY = if (cmd == 'm') curY + y else y
                    startX = curX
                    startY = curY
                    path.moveTo(curX * sx, curY * sy)
                    cmd = if (cmd == 'm') 'l' else 'L'
                    drewAny = true
                }
                'L', 'l' -> {
                    val x = nextFloat() ?: break
                    val y = nextFloat() ?: break
                    curX = if (cmd == 'l') curX + x else x
                    curY = if (cmd == 'l') curY + y else y
                    path.lineTo(curX * sx, curY * sy)
                    drewAny = true
                }
                'H', 'h' -> {
                    val x = nextFloat() ?: break
                    curX = if (cmd == 'h') curX + x else x
                    path.lineTo(curX * sx, curY * sy)
                    drewAny = true
                }
                'V', 'v' -> {
                    val y = nextFloat() ?: break
                    curY = if (cmd == 'v') curY + y else y
                    path.lineTo(curX * sx, curY * sy)
                    drewAny = true
                }
                'Q', 'q' -> {
                    val x1 = nextFloat() ?: break
                    val y1 = nextFloat() ?: break
                    val x = nextFloat() ?: break
                    val y = nextFloat() ?: break
                    val ax1 = if (cmd == 'q') curX + x1 else x1
                    val ay1 = if (cmd == 'q') curY + y1 else y1
                    curX = if (cmd == 'q') curX + x else x
                    curY = if (cmd == 'q') curY + y else y
                    path.quadTo(ax1 * sx, ay1 * sy, curX * sx, curY * sy)
                    drewAny = true
                }
                'C', 'c' -> {
                    val x1 = nextFloat() ?: break
                    val y1 = nextFloat() ?: break
                    val x2 = nextFloat() ?: break
                    val y2 = nextFloat() ?: break
                    val x = nextFloat() ?: break
                    val y = nextFloat() ?: break
                    val ax1 = if (cmd == 'c') curX + x1 else x1
                    val ay1 = if (cmd == 'c') curY + y1 else y1
                    val ax2 = if (cmd == 'c') curX + x2 else x2
                    val ay2 = if (cmd == 'c') curY + y2 else y2
                    curX = if (cmd == 'c') curX + x else x
                    curY = if (cmd == 'c') curY + y else y
                    path.cubicTo(ax1 * sx, ay1 * sy, ax2 * sx, ay2 * sy, curX * sx, curY * sy)
                    drewAny = true
                }
                'Z', 'z' -> {
                    path.close()
                    curX = startX
                    curY = startY
                    cmd = ' '
                }
                else -> idx++
            }
        }
        return if (drewAny) path else null
    }

    internal fun tokenizeSvgPath(d: String): List<String> {
        val regex = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        return regex.findAll(d).map { it.value }.toList()
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return runCatching {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            Color.parseColor(clean)
        }.getOrDefault(fallback)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
