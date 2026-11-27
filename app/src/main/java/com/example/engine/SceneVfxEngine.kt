package com.example.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.example.model.SceneLightingMood
import com.example.model.SceneParticleType
import com.example.model.SceneVfxConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class CameraShakeOffset(
    val dxNorm: Float = 0f,
    val dyNorm: Float = 0f,
    val rollDeg: Float = 0f
) {
    val isZero: Boolean get() = dxNorm == 0f && dyNorm == 0f && rollDeg == 0f
}

/**
 * Deterministic, zero-AI Scene VFX & Atmosphere Engine.
 * Every particle position, twinkle phase, color grade, vignette, and camera shake offset
 * is computed purely from (frame, fps, config, sceneSeed) so Live Preview and MP4/GIF/PNG
 * exports render frame-for-frame identically with zero GC churn.
 */
object SceneVfxEngine {

    private class VfxScratch {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        val rect = RectF()

        // Cached shaders keyed by viewport size + mood / quantized vignette to eliminate per-frame allocations
        var cachedMoodW: Int = -1
        var cachedMoodH: Int = -1
        var cachedMood: SceneLightingMood = SceneLightingMood.NONE
        var cachedMoodShaderPrimary: Shader? = null
        var cachedMoodShaderSecondary: Shader? = null

        var cachedVigW: Int = -1
        var cachedVigH: Int = -1
        var cachedVigStep: Int = -1
        var cachedVigShader: Shader? = null
    }

    private val scratchLocal = ThreadLocal.withInitial { VfxScratch() }

    private val CONFETTI_COLORS = intArrayOf(
        0xFF38BDF8.toInt(),
        0xFFF43F5E.toInt(),
        0xFFFACC15.toInt(),
        0xFF34D399.toInt(),
        0xFFA78BFA.toInt(),
        0xFFFB923C.toInt()
    )

    private val PETAL_COLORS = intArrayOf(
        0xFFF9A8D4.toInt(),
        0xFFF472B6.toInt(),
        0xFFFCE7F3.toInt(),
        0xFFFBCFE8.toInt()
    )

    /**
     * Multi-harmonic deterministic camera shake (organic handheld tremor + subtle roll).
     */
    fun computeCameraShake(
        frame: Int,
        fps: Int,
        intensity: Float,
        seed: Int = 1337
    ): CameraShakeOffset {
        val clamped = intensity.coerceIn(0f, 1f)
        if (clamped <= 0.005f) return CameraShakeOffset()

        val safeFps = fps.coerceIn(12, 60).toFloat()
        val t = frame / safeFps
        val s1 = (seed and 0xFF) * 0.137f
        val s2 = ((seed ushr 8) and 0xFF) * 0.193f

        // Layered non-repeating frequencies emulate organic physical tremor
        val rawX = 0.55f * sin(t * 27.3f + s1) +
            0.30f * sin(t * 49.7f + s2 * 1.7f) +
            0.15f * cos(t * 81.1f + s1 * 2.3f)
        val rawY = 0.55f * cos(t * 31.1f + s2) +
            0.30f * sin(t * 53.9f + s1 * 1.3f) +
            0.15f * sin(t * 77.3f + s2 * 2.1f)
        val rawRoll = 0.65f * sin(t * 19.4f + s1 + s2) +
            0.35f * cos(t * 41.2f + s2)

        val ampXY = clamped * clamped * 0.032f + clamped * 0.012f
        val ampRoll = clamped * 3.2f
        return CameraShakeOffset(
            dxNorm = rawX * ampXY,
            dyNorm = rawY * ampXY,
            rollDeg = rawRoll * ampRoll
        )
    }

    /**
     * Renders any of the 7 natural atmospheric particle systems deterministically onto [canvas].
     */
    fun drawSceneParticles(
        canvas: Canvas,
        width: Float,
        height: Float,
        frame: Int,
        fps: Int,
        config: SceneVfxConfig,
        seed: Int = 42
    ) {
        if (config.particleType == SceneParticleType.NONE) return
        val intensity = config.particleIntensity.coerceIn(0.05f, 1f)
        val speed = config.particleSpeed.coerceIn(0.25f, 2.5f)
        val safeFps = fps.coerceIn(12, 60).toFloat()
        val timeSec = (frame / safeFps) * speed
        val scratch = scratchLocal.get()!!
        val minDim = min(width, height).coerceAtLeast(1f)

        when (config.particleType) {
            SceneParticleType.NONE -> Unit
            SceneParticleType.RAIN -> drawRain(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.SNOW -> drawSnow(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.FIREFLIES -> drawFireflies(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.PETALS -> drawPetals(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.SPARKLES -> drawSparkles(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.CONFETTI -> drawConfetti(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
            SceneParticleType.EMBERS -> drawEmbers(canvas, width, height, minDim, timeSec, intensity, seed, scratch)
        }
    }

    /**
     * Renders cinematic color-grading mood overlays and smooth radial vignette.
     */
    fun drawLightingAndVignette(
        canvas: Canvas,
        width: Float,
        height: Float,
        config: SceneVfxConfig
    ) {
        val scratch = scratchLocal.get()!!
        val paint = scratch.overlayPaint

        val intW = width.toInt().coerceAtLeast(1)
        val intH = height.toInt().coerceAtLeast(1)

        // 1. Cinematic Color-Grading Mood (cached shaders per mood & viewport dimensions)
        if (config.lightingMood != SceneLightingMood.NONE) {
            if (scratch.cachedMood != config.lightingMood || scratch.cachedMoodW != intW || scratch.cachedMoodH != intH) {
                scratch.cachedMood = config.lightingMood
                scratch.cachedMoodW = intW
                scratch.cachedMoodH = intH
                scratch.cachedMoodShaderSecondary = null
                scratch.cachedMoodShaderPrimary = when (config.lightingMood) {
                    SceneLightingMood.NONE -> null
                    SceneLightingMood.GOLDEN_HOUR -> {
                        val rad = max(width, height) * 0.62f
                        scratch.cachedMoodShaderSecondary = RadialGradient(
                            width * 0.85f, height * 0.14f, rad,
                            Color.argb(72, 255, 224, 130),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                        LinearGradient(
                            0f, 0f, width, height,
                            intArrayOf(
                                Color.argb(66, 255, 196, 86),
                                Color.argb(30, 251, 146, 60),
                                Color.argb(54, 120, 45, 92)
                            ),
                            floatArrayOf(0f, 0.52f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    SceneLightingMood.MIDNIGHT_BLUE -> LinearGradient(
                        0f, 0f, 0f, height,
                        intArrayOf(
                            Color.argb(95, 12, 24, 68),
                            Color.argb(64, 24, 54, 118),
                            Color.argb(104, 7, 13, 38)
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    SceneLightingMood.NOIR_DRAMATIC -> LinearGradient(
                        0f, 0f, width, height,
                        intArrayOf(
                            Color.argb(82, 10, 14, 22),
                            Color.argb(46, 30, 36, 48),
                            Color.argb(115, 4, 6, 12)
                        ),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    SceneLightingMood.NEON_CYBER -> LinearGradient(
                        0f, 0f, width, height,
                        intArrayOf(
                            Color.argb(68, 192, 38, 211),
                            Color.argb(26, 20, 16, 56),
                            Color.argb(76, 6, 182, 212)
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    SceneLightingMood.WARM_CINEMA -> LinearGradient(
                        0f, 0f, 0f, height,
                        intArrayOf(
                            Color.argb(56, 234, 88, 12),
                            Color.argb(24, 180, 83, 9),
                            Color.argb(64, 15, 62, 74)
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
            scratch.cachedMoodShaderPrimary?.let { shader ->
                paint.shader = shader
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            scratch.cachedMoodShaderSecondary?.let { shader ->
                paint.shader = shader
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            paint.shader = null
        }

        // 2. Smooth Adjustable Radial Vignette (cached per quantized 2% step & viewport size)
        val vig = config.vignetteIntensity.coerceIn(0f, 1f)
        if (vig > 0.01f) {
            val vigStep = (vig * 50f).toInt().coerceIn(1, 50)
            if (scratch.cachedVigStep != vigStep || scratch.cachedVigW != intW || scratch.cachedVigH != intH) {
                scratch.cachedVigStep = vigStep
                scratch.cachedVigW = intW
                scratch.cachedVigH = intH
                val cx = width * 0.5f
                val cy = height * 0.5f
                val radius = hypot(cx, cy).coerceAtLeast(1f)
                val qVig = vigStep / 50f
                val edgeAlpha = (qVig * 215f).toInt().coerceIn(0, 230)
                val midAlpha = (qVig * 62f).toInt().coerceIn(0, 110)
                scratch.cachedVigShader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(midAlpha, 4, 6, 14),
                        Color.argb(edgeAlpha, 2, 4, 10)
                    ),
                    floatArrayOf(0.42f, 0.76f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = scratch.cachedVigShader
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
        }
    }

    private fun drawRain(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (28 + (intensity * 105f).toInt()).coerceIn(20, 140)
        val paint = scratch.strokePaint
        for (i in 0 until count) {
            val depth = 0.45f + hash01(seed, i, 1) * 0.55f
            val x0 = hash01(seed, i, 2)
            val y0 = hash01(seed, i, 3)
            val fallRate = (0.95f + depth * 0.85f)
            val yNorm = positiveMod(y0 + t * fallRate, 1.18f) - 0.09f
            val xNorm = positiveMod(x0 - t * 0.20f * depth, 1.12f) - 0.06f

            val px = xNorm * w
            val py = yNorm * h
            val len = minDim * (0.036f + depth * 0.042f)
            val dx = -len * 0.22f
            val dy = len

            paint.strokeWidth = (minDim * 0.0022f * depth).coerceIn(1.1f, 3.5f)
            paint.color = Color.argb((75 + depth * 125).toInt(), 186, 230, 253)
            canvas.drawLine(px, py, px + dx, py + dy, paint)
        }
    }

    private fun drawSnow(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (24 + (intensity * 88f).toInt()).coerceIn(18, 120)
        val paint = scratch.fillPaint
        for (i in 0 until count) {
            val depth = 0.35f + hash01(seed, i, 11) * 0.65f
            val x0 = hash01(seed, i, 12)
            val y0 = hash01(seed, i, 13)
            val swayPhase = hash01(seed, i, 14) * (2f * PI.toFloat())
            val yNorm = positiveMod(y0 + t * (0.14f + depth * 0.16f), 1.10f) - 0.05f
            val sway = sin(t * (1.2f + depth) + swayPhase) * 0.038f * depth
            val xNorm = positiveMod(x0 + sway + t * 0.025f, 1.08f) - 0.04f

            val radius = minDim * (0.0038f + depth * 0.0075f)
            val px = xNorm * w
            val py = yNorm * h

            // Soft outer halo
            paint.color = Color.argb((38 + depth * 48).toInt(), 224, 242, 254)
            canvas.drawCircle(px, py, radius * 1.9f, paint)
            // Crisp snow core
            paint.color = Color.argb((125 + depth * 115).toInt(), 248, 250, 252)
            canvas.drawCircle(px, py, radius, paint)
        }
    }

    private fun drawFireflies(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (14 + (intensity * 42f).toInt()).coerceIn(12, 60)
        val paint = scratch.fillPaint
        for (i in 0 until count) {
            val x0 = hash01(seed, i, 21)
            val y0 = 0.15f + hash01(seed, i, 22) * 0.78f
            val phase = hash01(seed, i, 23) * (2f * PI.toFloat())
            val freq = 0.65f + hash01(seed, i, 24) * 1.15f
            val sizeFactor = 0.65f + hash01(seed, i, 25) * 0.75f

            val px = positiveMod(x0 + sin(t * freq * 0.7f + phase) * 0.065f + t * 0.014f, 1.06f) * w - 0.03f * w
            val py = (y0 + cos(t * freq * 0.9f + phase * 1.3f) * 0.055f) * h
            val pulse = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * (2.4f + freq) + phase))
            val r = minDim * 0.0055f * sizeFactor

            paint.color = Color.argb((pulse * 52f).toInt(), 250, 204, 21)
            canvas.drawCircle(px, py, r * 3.8f, paint)
            paint.color = Color.argb((pulse * 120f).toInt(), 253, 224, 71)
            canvas.drawCircle(px, py, r * 1.9f, paint)
            paint.color = Color.argb((pulse * 240f).toInt(), 254, 249, 195)
            canvas.drawCircle(px, py, r * 0.75f, paint)
        }
    }

    private fun drawPetals(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (16 + (intensity * 48f).toInt()).coerceIn(14, 70)
        val paint = scratch.fillPaint
        val rect = scratch.rect
        for (i in 0 until count) {
            val depth = 0.45f + hash01(seed, i, 31) * 0.55f
            val x0 = hash01(seed, i, 32)
            val y0 = hash01(seed, i, 33)
            val phase = hash01(seed, i, 34) * (2f * PI.toFloat())
            val yNorm = positiveMod(y0 + t * (0.14f + depth * 0.14f), 1.14f) - 0.07f
            val xNorm = positiveMod(x0 + t * (0.09f + depth * 0.07f) + sin(t * 2.1f + phase) * 0.04f, 1.14f) - 0.07f

            val px = xNorm * w
            val py = yNorm * h
            val rx = minDim * (0.008f + depth * 0.010f)
            val ry = rx * (0.45f + 0.35f * kotlin.math.abs(cos(t * 2.8f + phase)))
            val rotDeg = (t * (55f + depth * 85f) + phase * 57f) % 360f

            val baseColor = PETAL_COLORS[i % PETAL_COLORS.size]
            paint.color = (baseColor and 0x00FFFFFF) or (((145 + depth * 95).toInt()) shl 24)

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(rotDeg)
            rect.set(-rx, -ry, rx, ry)
            canvas.drawOval(rect, paint)
            canvas.restore()
        }
    }

    private fun drawSparkles(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (16 + (intensity * 44f).toInt()).coerceIn(14, 64)
        val paint = scratch.fillPaint
        val path = scratch.path
        for (i in 0 until count) {
            val x0 = hash01(seed, i, 41)
            val y0 = hash01(seed, i, 42)
            val phase = hash01(seed, i, 43) * (2f * PI.toFloat())
            val rate = 1.6f + hash01(seed, i, 44) * 2.6f
            val twinkle = max(0f, sin(t * rate + phase))
            val crisp = twinkle * twinkle
            if (crisp < 0.08f) continue

            val px = positiveMod(x0 + sin(t * 0.4f + phase) * 0.015f, 1f) * w
            val py = positiveMod(y0 - t * 0.03f, 1f) * h
            val r = minDim * (0.007f + hash01(seed, i, 45) * 0.014f) * crisp
            val inner = r * 0.24f

            // Soft magical glow
            paint.color = Color.argb((crisp * 75f).toInt(), 196, 181, 253)
            canvas.drawCircle(px, py, r * 1.4f, paint)

            // 4-point star sparkle
            path.reset()
            path.moveTo(px, py - r)
            path.lineTo(px + inner, py - inner)
            path.lineTo(px + r, py)
            path.lineTo(px + inner, py + inner)
            path.lineTo(px, py + r)
            path.lineTo(px - inner, py + inner)
            path.lineTo(px - r, py)
            path.lineTo(px - inner, py - inner)
            path.close()

            paint.color = Color.argb((crisp * 245f).toInt(), 255, 255, 255)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawConfetti(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (22 + (intensity * 64f).toInt()).coerceIn(18, 92)
        val paint = scratch.fillPaint
        val rect = scratch.rect
        for (i in 0 until count) {
            val depth = 0.45f + hash01(seed, i, 51) * 0.55f
            val x0 = hash01(seed, i, 52)
            val y0 = hash01(seed, i, 53)
            val phase = hash01(seed, i, 54) * (2f * PI.toFloat())
            val yNorm = positiveMod(y0 + t * (0.24f + depth * 0.24f), 1.14f) - 0.07f
            val xNorm = positiveMod(x0 + sin(t * 2.6f + phase) * 0.045f, 1.08f) - 0.04f

            val px = xNorm * w
            val py = yNorm * h
            val rw = minDim * (0.008f + depth * 0.007f)
            val rh = rw * (0.35f + 0.65f * kotlin.math.abs(sin(t * 4.5f + phase)))
            val rotDeg = (t * (110f + depth * 120f) + phase * 60f) % 360f

            val col = CONFETTI_COLORS[i % CONFETTI_COLORS.size]
            paint.color = (col and 0x00FFFFFF) or (((175 + depth * 75).toInt()) shl 24)

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(rotDeg)
            rect.set(-rw, -rh, rw, rh)
            canvas.drawRoundRect(rect, 2f, 2f, paint)
            canvas.restore()
        }
    }

    private fun drawEmbers(
        canvas: Canvas,
        w: Float,
        h: Float,
        minDim: Float,
        t: Float,
        intensity: Float,
        seed: Int,
        scratch: VfxScratch
    ) {
        val count = (18 + (intensity * 56f).toInt()).coerceIn(16, 80)
        val paint = scratch.fillPaint
        for (i in 0 until count) {
            val depth = 0.4f + hash01(seed, i, 61) * 0.6f
            val x0 = hash01(seed, i, 62)
            val y0 = hash01(seed, i, 63)
            val phase = hash01(seed, i, 64) * (2f * PI.toFloat())
            // Embers rise upward from bottom
            val life = positiveMod(y0 + t * (0.20f + depth * 0.22f), 1f)
            val yNorm = 1.06f - life * 1.14f
            val xNorm = positiveMod(x0 + sin(t * 3.1f + phase) * 0.04f + t * 0.03f, 1.08f) - 0.04f
            val fade = (1f - life).coerceIn(0f, 1f) * (0.55f + 0.45f * sin(t * 7f + phase))
            if (fade <= 0.05f) continue

            val px = xNorm * w
            val py = yNorm * h
            val r = minDim * (0.0032f + depth * 0.0046f) * (0.5f + 0.5f * (1f - life))

            paint.color = Color.argb((fade * 90f).toInt(), 239, 68, 68)
            canvas.drawCircle(px, py, r * 3.0f, paint)
            paint.color = Color.argb((fade * 190f).toInt(), 251, 146, 60)
            canvas.drawCircle(px, py, r * 1.5f, paint)
            paint.color = Color.argb((fade * 245f).toInt(), 254, 240, 138)
            canvas.drawCircle(px, py, r * 0.7f, paint)
        }
    }

    private fun hash01(seed: Int, index: Int, channel: Int): Float {
        var h = seed * 374761393 + index * 668265263 + channel * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0x7FFFFFFF).toFloat()) / 2147483647f
    }

    private fun positiveMod(value: Float, mod: Float): Float {
        val r = value % mod
        return if (r < 0f) r + mod else r
    }
}
