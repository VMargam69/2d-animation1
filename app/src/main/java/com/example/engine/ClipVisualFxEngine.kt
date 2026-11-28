package com.example.engine

import android.graphics.*
import com.example.model.*
import kotlin.math.*

/**
 * 100% offline, deterministic visual effects engine shared identically by:
 * - Live 30fps EditorCanvas preview
 * - Offline MP4 / GIF / PNG export (SceneRenderer)
 * - Director Script Receiver & JVM unit tests
 *
 * Covers:
 * 1. Animated Tears FX (glistening eye well + falling teardrop streams anchored to character eyes)
 * 2. Dynamic Ground & Drop Shadows (auto-scales & softens naturally during jumps)
 * 3. Per-Clip Colour Adjust (fast 4x5 ColorMatrix: brightness, contrast, saturation, hue, warmth)
 * 4. Interactive Clip Masking (all shapes + center/size/feather/invert)
 */
object ClipVisualFxEngine {

    data class TearEyeAnchor(
        val x: Float,
        val y: Float,
        val sideSign: Float = 1f // -1 left eye, +1 right eye
    )

    data class TearDropSample(
        val x: Float,
        val y: Float,
        val radiusX: Float,
        val radiusY: Float,
        val alpha: Float,
        val isStreamRibbon: Boolean = false,
        val streamStartY: Float = y,
        val streamEndY: Float = y
    )

    data class ShadowGeometry(
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val effectiveOpacity: Float,
        val softness: Float,
        val isGroundMode: Boolean,
        val jumpFactor: Float // 1f on ground, shrinks cleanly as character jumps higher
    )

    // =========================================================================
    // 1. DETERMINISTIC ANIMATED TEARS FX
    // =========================================================================

    /**
     * Pure deterministic tear droplet & stream solver driven strictly by [globalFrame], [fps], and [seed].
     * Guarantees identical tear positions across timeline scrubbing, live playback, and MP4/GIF/PNG exports.
     */
    fun computeTearDrops(
        config: TearsFxConfig,
        eyeX: Float,
        eyeY: Float,
        scale: Float,
        globalFrame: Int,
        fps: Int = 24,
        seed: Int = 0,
        sideSign: Float = 1f
    ): List<TearDropSample> {
        if (!config.isActive || scale <= 0.001f) return emptyList()
        val intensity = config.intensity.coerceIn(0f, 1f)
        val speed = config.speed.coerceIn(0.5f, 2.0f)
        val safeFps = fps.coerceAtLeast(1).toFloat()
        val tSec = globalFrame.coerceAtLeast(0).toFloat() / safeFps
        val phaseShift = ((seed and 0xFF) / 255f) * 3.1f + if (sideSign < 0f) 0.37f else 0f

        val drops = ArrayList<TearDropSample>(8)

        // Base glistening tear pool along lower eyelid (present in all active styles)
        val shimmer = 0.72f + 0.28f * sin((tSec * 4.2f * speed + phaseShift).toDouble()).toFloat()
        drops.add(
            TearDropSample(
                x = eyeX,
                y = eyeY + 0.6f * scale,
                radiusX = (2.8f + 1.4f * intensity) * scale,
                radiusY = (1.15f + 0.55f * intensity) * scale,
                alpha = (0.58f + 0.35f * intensity) * shimmer
            )
        )

        when (config.style) {
            TearsFxStyle.OFF -> return emptyList()

            TearsFxStyle.GLISTENING -> {
                // Subtle shimmering corner droplet poised to spill
                val wobble = sin((tSec * 3.1f * speed + phaseShift).toDouble()).toFloat()
                drops.add(
                    TearDropSample(
                        x = eyeX + sideSign * (1.8f * scale),
                        y = eyeY + (1.7f + 0.45f * wobble) * scale,
                        radiusX = (1.1f + 0.6f * intensity) * scale,
                        radiusY = (1.35f + 0.7f * intensity) * scale,
                        alpha = (0.65f + 0.3f * intensity) * (0.8f + 0.2f * wobble)
                    )
                )
            }

            TearsFxStyle.FLOWING -> {
                // 3 staggered teardrops rolling smoothly down the cheek
                val dropCount = 3
                val travelDist = (26f + 14f * intensity) * scale
                for (i in 0 until dropCount) {
                    val cycle = ((tSec * 0.95f * speed + i.toFloat() / dropCount + phaseShift * 0.21f) % 1f + 1f) % 1f
                    // Accelerate slightly under gravity
                    val easedY = cycle * cycle * 0.65f + cycle * 0.35f
                    val driftX = sideSign * sin((cycle * PI).toDouble()).toFloat() * (2.6f * scale)
                    val fade = when {
                        cycle < 0.12f -> cycle / 0.12f
                        cycle > 0.78f -> ((1f - cycle) / 0.22f).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    val sizeScale = (0.75f + 0.45f * intensity) * (1f - 0.22f * cycle)
                    drops.add(
                        TearDropSample(
                            x = eyeX + sideSign * 1.1f * scale + driftX,
                            y = eyeY + 1.4f * scale + easedY * travelDist,
                            radiusX = 1.45f * scale * sizeScale,
                            radiusY = 2.25f * scale * sizeScale,
                            alpha = (0.72f + 0.25f * intensity) * fade
                        )
                    )
                }
            }

            TearsFxStyle.HEAVY_STREAM -> {
                // Anime/cartoon waterfall stream + 4 rapid splash droplets
                val streamLen = (30f + 18f * intensity) * scale
                val pulse = 0.85f + 0.15f * sin((tSec * 12f * speed + phaseShift).toDouble()).toFloat()
                drops.add(
                    TearDropSample(
                        x = eyeX + sideSign * 1.2f * scale,
                        y = eyeY + streamLen * 0.5f,
                        radiusX = (2.1f + 1.1f * intensity) * scale * pulse,
                        radiusY = streamLen * 0.5f,
                        alpha = (0.55f + 0.32f * intensity),
                        isStreamRibbon = true,
                        streamStartY = eyeY + 0.8f * scale,
                        streamEndY = eyeY + streamLen
                    )
                )

                val dropCount = 4
                val travelDist = (34f + 22f * intensity) * scale
                for (i in 0 until dropCount) {
                    val cycle = ((tSec * 1.65f * speed + i.toFloat() / dropCount + phaseShift * 0.17f) % 1f + 1f) % 1f
                    val easedY = cycle * cycle * 0.72f + cycle * 0.28f
                    val splashX = sideSign * (1.8f + 5.2f * cycle) * scale * (0.75f + 0.25f * (i % 2))
                    val fade = when {
                        cycle < 0.08f -> cycle / 0.08f
                        cycle > 0.75f -> ((1f - cycle) / 0.25f).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    drops.add(
                        TearDropSample(
                            x = eyeX + splashX,
                            y = eyeY + 1.2f * scale + easedY * travelDist,
                            radiusX = (1.5f + 0.75f * intensity) * scale,
                            radiusY = (2.4f + 1.1f * intensity) * scale,
                            alpha = (0.78f + 0.2f * intensity) * fade
                        )
                    )
                }
            }
        }
        return drops
    }

    /**
     * Renders anime/cartoon tear pools, ribbons, and specular teardrops at the character's eye coordinates.
     */
    fun drawTearsAtEyes(
        canvas: Canvas,
        config: TearsFxConfig,
        eyeAnchors: List<TearEyeAnchor>,
        scale: Float,
        globalFrame: Int,
        fps: Int,
        alphaInt: Int = 255,
        seed: Int = 0
    ) {
        if (!config.isActive || eyeAnchors.isEmpty() || alphaInt <= 2) return
        val alphaFactor = (alphaInt / 255f).coerceIn(0f, 1f)
        val tearFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (0.65f * scale).coerceAtLeast(0.6f)
            color = Color.rgb(125, 211, 252)
        }
        val dropPath = Path()

        for (anchor in eyeAnchors) {
            val samples = computeTearDrops(
                config = config,
                eyeX = anchor.x,
                eyeY = anchor.y,
                scale = scale,
                globalFrame = globalFrame,
                fps = fps,
                seed = seed,
                sideSign = anchor.sideSign
            )
            for (sample in samples) {
                val a = (sample.alpha * alphaFactor * 255f).toInt().coerceIn(0, 255)
                if (a <= 2) continue

                if (sample.isStreamRibbon) {
                    val waveAmp = 1.6f * scale
                    val phase = (globalFrame.toFloat() / fps.coerceAtLeast(1)) * 10f * config.speed
                    dropPath.reset()
                    val topY = sample.streamStartY
                    val botY = sample.streamEndY
                    val midY = (topY + botY) * 0.5f
                    val hwTop = sample.radiusX * 0.9f
                    val hwBot = sample.radiusX * 0.45f
                    val waveX = sin(phase.toDouble()).toFloat() * waveAmp

                    dropPath.moveTo(sample.x - hwTop, topY)
                    dropPath.quadTo(sample.x - hwTop + waveX, midY, sample.x - hwBot, botY)
                    dropPath.quadTo(sample.x, botY + sample.radiusX * 0.6f, sample.x + hwBot, botY)
                    dropPath.quadTo(sample.x + hwTop + waveX, midY, sample.x + hwTop, topY)
                    dropPath.close()

                    tearFill.shader = LinearGradient(
                        sample.x, topY, sample.x, botY,
                        Color.argb(a, 186, 230, 253),
                        Color.argb((a * 0.35f).toInt(), 56, 189, 248),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(dropPath, tearFill)
                    tearFill.shader = null

                    // Vertical specular streak inside heavy stream
                    highlightPaint.alpha = (a * 0.72f).toInt().coerceIn(0, 255)
                    canvas.drawLine(
                        sample.x - hwTop * 0.25f,
                        topY + 1.5f * scale,
                        sample.x - hwBot * 0.25f + waveX * 0.4f,
                        botY - 3f * scale,
                        highlightPaint.apply {
                            style = Paint.Style.STROKE
                            strokeWidth = (0.9f * scale).coerceAtLeast(0.8f)
                            strokeCap = Paint.Cap.ROUND
                        }
                    )
                    highlightPaint.style = Paint.Style.FILL
                } else if (sample.radiusY > sample.radiusX * 1.15f) {
                    // Classic teardrop silhouette (pointed top, rounded bulb bottom)
                    dropPath.reset()
                    val topY = sample.y - sample.radiusY
                    val botY = sample.y + sample.radiusY
                    dropPath.moveTo(sample.x, topY)
                    dropPath.cubicTo(
                        sample.x + sample.radiusX * 1.15f, sample.y - sample.radiusY * 0.1f,
                        sample.x + sample.radiusX * 1.1f, botY,
                        sample.x, botY
                    )
                    dropPath.cubicTo(
                        sample.x - sample.radiusX * 1.1f, botY,
                        sample.x - sample.radiusX * 1.15f, sample.y - sample.radiusY * 0.1f,
                        sample.x, topY
                    )
                    dropPath.close()

                    tearFill.color = Color.argb(a, 165, 228, 255)
                    canvas.drawPath(dropPath, tearFill)

                    outlinePaint.alpha = (a * 0.78f).toInt().coerceIn(0, 255)
                    canvas.drawPath(dropPath, outlinePaint)

                    // Crisp white anime highlight dot
                    highlightPaint.alpha = (a * 0.92f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(
                        sample.x - sample.radiusX * 0.28f,
                        sample.y + sample.radiusY * 0.2f,
                        (sample.radiusX * 0.34f).coerceAtLeast(0.55f),
                        highlightPaint
                    )
                } else {
                    // Glistening tear pool oval along lower eyelid
                    tearFill.color = Color.argb(a, 186, 235, 255)
                    canvas.drawOval(
                        sample.x - sample.radiusX,
                        sample.y - sample.radiusY,
                        sample.x + sample.radiusX,
                        sample.y + sample.radiusY,
                        tearFill
                    )
                    highlightPaint.alpha = (a * 0.88f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(
                        sample.x - sample.radiusX * 0.32f,
                        sample.y - sample.radiusY * 0.15f,
                        (sample.radiusY * 0.52f).coerceAtLeast(0.5f),
                        highlightPaint
                    )
                }
            }
        }
    }

    // =========================================================================
    // 2. DYNAMIC GROUND / DROP SHADOW ENGINE
    // =========================================================================

    /**
     * Pure deterministic shadow geometry solver.
     * In [ShadowMode.GROUND_ELLIPSE] mode for characters, [bodyBobY] (negative when airborne/jumping)
     * naturally shrinks the shadow radius and softens its opacity as the character lifts off the ground.
     */
    fun computeShadowGeometry(
        config: ShadowConfig,
        isCharacter: Boolean,
        baseScale: Float,
        bodyBobY: Float,
        clipWidthPx: Float,
        clipHeightPx: Float
    ): ShadowGeometry {
        val safeOpacity = config.opacity.coerceIn(0f, 1f)
        val safeSoftness = config.softness.coerceIn(0f, 1f)
        val safeScaleX = config.scaleX.coerceIn(0.2f, 2.5f)
        val safeScaleY = config.scaleY.coerceIn(0.1f, 2.0f)

        // Character upward jump lift is negative bodyBobY; map cleanly to 0..1 altitude ratio
        val jumpLift = if (isCharacter) (-bodyBobY).coerceAtLeast(0f) else 0f
        val jumpFactor = (1f / (1f + jumpLift * 0.018f)).coerceIn(0.42f, 1f)

        return if (config.mode == ShadowMode.GROUND_ELLIPSE) {
            val baseRx = if (isCharacter) {
                (38f * baseScale).coerceAtLeast(18f)
            } else {
                (clipWidthPx * 0.42f).coerceAtLeast(18f)
            }
            val baseRy = if (isCharacter) {
                (26f * baseScale).coerceAtLeast(10f)
            } else {
                (clipHeightPx * 0.18f).coerceAtLeast(10f)
            }
            val feetY = if (isCharacter) {
                // Anchor directly beneath feet, compensating for bodyBobY so ground shadow stays on ground plane!
                56f * baseScale - bodyBobY * baseScale * 0.55f
            } else {
                clipHeightPx * 0.48f
            }
            ShadowGeometry(
                centerX = config.offsetX * clipWidthPx * 0.5f,
                centerY = feetY + config.offsetY * clipHeightPx * 0.35f,
                radiusX = baseRx * safeScaleX * jumpFactor,
                radiusY = baseRy * safeScaleY * jumpFactor,
                effectiveOpacity = (safeOpacity * (0.45f + 0.55f * jumpFactor)).coerceIn(0f, 1f),
                softness = (safeSoftness + (1f - jumpFactor) * 0.35f).coerceIn(0f, 1f),
                isGroundMode = true,
                jumpFactor = jumpFactor
            )
        } else {
            // Soft directional drop shadow behind the clip
            val rx = if (isCharacter) (42f * baseScale) else (clipWidthPx * 0.48f)
            val ry = if (isCharacter) (68f * baseScale) else (clipHeightPx * 0.48f)
            val defaultDropX = if (abs(config.offsetX) < 0.001f) 0.06f else config.offsetX
            val defaultDropY = if (abs(config.offsetY) < 0.001f) 0.08f else config.offsetY
            ShadowGeometry(
                centerX = defaultDropX * clipWidthPx * 0.5f,
                centerY = defaultDropY * clipHeightPx * 0.5f,
                radiusX = rx * safeScaleX,
                radiusY = ry * (safeScaleY.coerceAtLeast(0.45f)),
                effectiveOpacity = safeOpacity,
                softness = safeSoftness,
                isGroundMode = false,
                jumpFactor = 1f
            )
        }
    }

    /**
     * Draws a soft radial-gradient ground ellipse or directional drop shadow cleanly in clip-local coordinates.
     * Uses RadialGradient (hardware-accelerated and 100% consistent on both software Export Canvas & live GPU Canvas).
     */
    fun drawClipShadow(
        canvas: Canvas,
        config: ShadowConfig,
        isCharacter: Boolean,
        baseScale: Float,
        bodyBobY: Float,
        clipWidthPx: Float,
        clipHeightPx: Float,
        clipOpacity: Float
    ) {
        if (!config.isActive || clipOpacity <= 0.01f) return
        val geom = computeShadowGeometry(
            config = config,
            isCharacter = isCharacter,
            baseScale = baseScale,
            bodyBobY = bodyBobY,
            clipWidthPx = clipWidthPx,
            clipHeightPx = clipHeightPx
        )
        if (geom.radiusX <= 1f || geom.radiusY <= 1f) return

        val baseColor = ProceduralRigRenderer.parseColorSafe(config.colorHex, 0xFF0B101B.toInt())
        val r = Color.red(baseColor)
        val g = Color.green(baseColor)
        val b = Color.blue(baseColor)
        val peakAlpha = (geom.effectiveOpacity * clipOpacity * 255f).toInt().coerceIn(0, 255)
        if (peakAlpha <= 2) return

        // Inner core stop shrinks as softness increases for realistic penumbra feathering
        val coreStop = (0.72f - geom.softness * 0.58f).coerceIn(0.08f, 0.82f)
        val midStop = (coreStop + (1f - coreStop) * 0.55f).coerceIn(coreStop + 0.05f, 0.96f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    Color.argb(peakAlpha, r, g, b),
                    Color.argb((peakAlpha * 0.52f).toInt(), r, g, b),
                    Color.argb(0, r, g, b)
                ),
                floatArrayOf(coreStop, midStop, 1f),
                Shader.TileMode.CLAMP
            )
        }

        canvas.save()
        canvas.translate(geom.centerX, geom.centerY)
        canvas.scale(geom.radiusX, geom.radiusY)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    // =========================================================================
    // 3. PER-CLIP COLOUR ADJUST (PURE 4x5 COLOR MATRIX)
    // =========================================================================

    private fun identity4x5(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    private fun concat4x5(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            val rOff = row * 5
            for (col in 0 until 4) {
                out[rOff + col] =
                    a[rOff + 0] * b[0 + col] +
                    a[rOff + 1] * b[5 + col] +
                    a[rOff + 2] * b[10 + col] +
                    a[rOff + 3] * b[15 + col]
            }
            out[rOff + 4] =
                a[rOff + 0] * b[4] +
                a[rOff + 1] * b[9] +
                a[rOff + 2] * b[14] +
                a[rOff + 3] * b[19] +
                a[rOff + 4]
        }
        return out
    }

    /**
     * Pure 20-float 4x5 Android ColorMatrix builder combining saturation, hue rotation,
     * warmth, contrast, and brightness. 100% JVM unit-testable with zero shader overhead.
     */
    fun buildColorAdjustMatrix(adjust: ClipColorAdjust): FloatArray {
        if (!adjust.isActive) return identity4x5()
        var m = identity4x5()

        // 1. Saturation (standard Android ColorMatrix luminance weights)
        val sat = adjust.saturation.coerceIn(0f, 2.5f)
        if (abs(sat - 1f) > 0.001f) {
            val invSat = 1f - sat
            val rw = 0.213f * invSat
            val gw = 0.715f * invSat
            val bw = 0.072f * invSat
            val satM = floatArrayOf(
                rw + sat, gw, bw, 0f, 0f,
                rw, gw + sat, bw, 0f, 0f,
                rw, gw, bw + sat, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            m = concat4x5(satM, m)
        }

        // 2. Hue rotation around luminance axis
        val hueDeg = adjust.hueDeg.coerceIn(-180f, 180f)
        if (abs(hueDeg) > 0.5f) {
            val rad = hueDeg * (PI / 180.0)
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val lumR = 0.213f
            val lumG = 0.715f
            val lumB = 0.072f
            val hueM = floatArrayOf(
                lumR + cosA * (1f - lumR) + sinA * (-lumR),
                lumG + cosA * (-lumG) + sinA * (-lumG),
                lumB + cosA * (-lumB) + sinA * (1f - lumB),
                0f, 0f,

                lumR + cosA * (-lumR) + sinA * 0.143f,
                lumG + cosA * (1f - lumG) + sinA * 0.140f,
                lumB + cosA * (-lumB) + sinA * (-0.283f),
                0f, 0f,

                lumR + cosA * (-lumR) + sinA * (-(1f - lumR)),
                lumG + cosA * (-lumG) + sinA * lumG,
                lumB + cosA * (1f - lumB) + sinA * lumB,
                0f, 0f,

                0f, 0f, 0f, 1f, 0f
            )
            m = concat4x5(hueM, m)
        }

        // 3. Warmth (red/amber boost vs cool blue shift)
        val warmth = adjust.warmth.coerceIn(-1f, 1f)
        if (abs(warmth) > 0.005f) {
            val rScale = (1f + warmth * 0.18f).coerceIn(0.65f, 1.35f)
            val gScale = (1f + warmth * 0.04f).coerceIn(0.85f, 1.15f)
            val bScale = (1f - warmth * 0.20f).coerceIn(0.65f, 1.35f)
            val warmM = floatArrayOf(
                rScale, 0f, 0f, 0f, warmth * 12f,
                0f, gScale, 0f, 0f, warmth * 3f,
                0f, 0f, bScale, 0f, -warmth * 14f,
                0f, 0f, 0f, 1f, 0f
            )
            m = concat4x5(warmM, m)
        }

        // 4. Contrast (scales RGB around mid-gray 128)
        val contrast = adjust.contrast.coerceIn(0.2f, 2.5f)
        if (abs(contrast - 1f) > 0.005f) {
            val t = 128f * (1f - contrast)
            val contrastM = floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
            m = concat4x5(contrastM, m)
        }

        // 5. Brightness (smooth linear lift/darkening)
        val brightness = adjust.brightness.coerceIn(-1f, 1f)
        if (abs(brightness) > 0.005f) {
            val bOffset = brightness * 120f
            val brightM = floatArrayOf(
                1f, 0f, 0f, 0f, bOffset,
                0f, 1f, 0f, 0f, bOffset,
                0f, 0f, 1f, 0f, bOffset,
                0f, 0f, 0f, 1f, 0f
            )
            m = concat4x5(brightM, m)
        }

        return m
    }

    fun createColorAdjustFilter(adjust: ClipColorAdjust): ColorMatrixColorFilter? {
        if (!adjust.isActive) return null
        return ColorMatrixColorFilter(ColorMatrix(buildColorAdjustMatrix(adjust)))
    }

    // =========================================================================
    // 4. INTERACTIVE CLIP MASKING ENGINE (ALL SHAPES + INVERT + FEATHER)
    // =========================================================================

    /**
     * Populates [outPath] with the clip's active mask silhouette in clip-local coordinates centered at (0,0).
     * Supports [ClipMaskConfig] centerX/Y, width/height, and backward-compatible legacy [maskType]/[maskRadius].
     */
    fun buildClipMaskPath(
        type: ClipMaskType,
        config: ClipMaskConfig,
        legacyRadius: Float,
        refW: Float,
        refH: Float,
        outPath: Path
    ) {
        outPath.reset()
        val hasExplicitConfig = config.type != ClipMaskType.NONE
        val cx = config.centerX.coerceIn(-0.6f, 0.6f) * refW
        val cy = config.centerY.coerceIn(-0.6f, 0.6f) * refH
        val halfW = if (hasExplicitConfig) {
            (refW * config.width.coerceIn(0.08f, 1.6f) * 0.5f)
        } else {
            (min(refW, refH) * legacyRadius.coerceIn(0.12f, 1.0f))
        }
        val halfH = if (hasExplicitConfig) {
            (refH * config.height.coerceIn(0.08f, 1.6f) * 0.5f)
        } else {
            (min(refW, refH) * legacyRadius.coerceIn(0.12f, 1.0f))
        }
        val r = min(halfW, halfH)

        when (type) {
            ClipMaskType.NONE -> Unit
            ClipMaskType.CIRCLE -> outPath.addCircle(cx, cy, r, Path.Direction.CW)
            ClipMaskType.RECTANGLE -> outPath.addRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, Path.Direction.CW)
            ClipMaskType.ROUNDED_RECT -> {
                val corner = (r * 0.28f).coerceAtLeast(6f)
                outPath.addRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, corner, corner, Path.Direction.CW)
            }
            ClipMaskType.OVAL -> outPath.addOval(cx - halfW, cy - halfH, cx + halfW, cy + halfH, Path.Direction.CW)
            ClipMaskType.STAR -> {
                val points = 5
                val outerR = r * 1.06f
                val innerR = r * 0.48f
                for (i in 0 until points * 2) {
                    val rad = if (i % 2 == 0) outerR else innerR
                    val angle = -Math.PI / 2.0 + i * Math.PI / points
                    val px = cx + (cos(angle) * rad).toFloat()
                    val py = cy + (sin(angle) * rad).toFloat()
                    if (i == 0) outPath.moveTo(px, py) else outPath.lineTo(px, py)
                }
                outPath.close()
            }
            ClipMaskType.ARCH -> {
                outPath.moveTo(cx - halfW, cy + halfH)
                outPath.lineTo(cx - halfW, cy - halfH * 0.15f)
                outPath.arcTo(
                    cx - halfW,
                    cy - halfH,
                    cx + halfW,
                    cy + halfH * 0.65f,
                    180f,
                    180f,
                    false
                )
                outPath.lineTo(cx + halfW, cy + halfH)
                outPath.close()
            }
            ClipMaskType.LINEAR_SPLIT -> {
                // Clean vertical/horizontal split wipe controlled by centerX & width
                val splitX = cx + (halfW - refW * 0.36f)
                outPath.addRect(-refW * 1.5f, -refH * 1.5f, splitX, refH * 1.5f, Path.Direction.CW)
            }
            ClipMaskType.DIAGONAL_SPLIT -> {
                val w = refW * 1.2f
                val h = refH * 1.2f
                outPath.moveTo(cx - w, cy - h)
                outPath.lineTo(cx + halfW, cy - h)
                outPath.lineTo(cx - halfW * 0.35f, cy + h)
                outPath.lineTo(cx - w, cy + h)
                outPath.close()
            }
        }
    }

    /**
     * Applies the clip's mask path (including [ClipMaskConfig.invert]) cleanly to [canvas].
     * Uses EVEN_ODD outer-minus-inner path for inverted masks so it works identically on all Android API levels.
     */
    fun applyClipMask(
        canvas: Canvas,
        type: ClipMaskType,
        config: ClipMaskConfig,
        legacyRadius: Float,
        refW: Float,
        refH: Float,
        scratchPath: Path
    ) {
        if (type == ClipMaskType.NONE) return
        buildClipMaskPath(type, config, legacyRadius, refW, refH, scratchPath)
        if (config.invert) {
            val outerW = max(refW, refH) * 3f
            scratchPath.addRect(-outerW, -outerW, outerW, outerW, Path.Direction.CCW)
            scratchPath.fillType = Path.FillType.EVEN_ODD
        } else {
            scratchPath.fillType = Path.FillType.WINDING
        }
        canvas.clipPath(scratchPath)
    }

    /**
     * Draws an optional subtle soft feathered vignette along the inside edge of the active mask
     * when [ClipMaskConfig.feather] > 0.02f.
     */
    fun drawMaskFeatherEdge(
        canvas: Canvas,
        type: ClipMaskType,
        config: ClipMaskConfig,
        legacyRadius: Float,
        refW: Float,
        refH: Float,
        scratchPath: Path,
        bgColor: Int
    ) {
        val feather = config.feather.coerceIn(0f, 1f)
        if (type == ClipMaskType.NONE || feather <= 0.02f) return
        buildClipMaskPath(type, config, legacyRadius, refW, refH, scratchPath)
        val featherPx = (min(refW, refH) * 0.14f * feather).coerceAtLeast(3f)
        val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = featherPx * 2f
            color = Color.argb((feather * 165f).toInt().coerceIn(0, 220), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
            maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(scratchPath, featherPaint)
    }
}
