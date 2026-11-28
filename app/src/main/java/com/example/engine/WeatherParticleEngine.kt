package com.example.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.model.WeatherParticleConfig
import com.example.model.WeatherParticleStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Single deterministic weather particle evaluated for a specific frame index.
 */
data class WeatherParticleSample(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val angleDeg: Float,
    val alpha: Float,
    val colorArgb: Int,
    val lengthPx: Float = 0f
)

/**
 * Deterministic, frame-indexed offline Weather & Particle Engine.
 *
 * Supports 7 atmospheric styles (`RAIN`, `FOG`, `FALLING_LEAVES`, `FIRE_SPARKS`,
 * `SMOKE`, `DUST_MOTES`, `SNOW`) with customizable `density`, `windSpeed`,
 * `windAngleDeg`, `sizeScale`, `opacity`, and `[startSec, endSec]` time window.
 *
 * Every particle position is computed purely from `(frameIndex, fps, seed, particleIndex)`
 * with zero mutable simulation state, guaranteeing 100% identical output between
 * interactive scrubbing, live playback, and offline MP4/GIF/PNG exports.
 */
object WeatherParticleEngine {

    private val scratchPaint = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    private val scratchRect = ThreadLocal.withInitial { RectF() }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun hash01(seed: Int, idx: Int, channel: Int): Float {
        var x = seed * 73856093 xor (idx * 19349663) xor (channel * 83492791)
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = ((x ushr 16) xor x) * 0x45d9f3b
        x = (x ushr 16) xor x
        return ((x and 0x7FFFFFFF) % 100000) / 100000f
    }

    private fun wrap01(v: Float): Float {
        val m = v % 1f
        return if (m < 0f) m + 1f else m
    }

    /**
     * Returns whether [config] is active at [frameIndex] (respecting `enabled`, `style`, and `[startSec, endSec]`).
     */
    fun isWindowActive(config: WeatherParticleConfig, frameIndex: Int, fps: Int): Boolean {
        if (!config.isActive) return false
        val safeFps = fps.coerceAtLeast(1).toFloat()
        val timeSec = frameIndex.coerceAtLeast(0) / safeFps
        if (timeSec < config.startSec) return false
        if (config.endSec >= 0f && timeSec > config.endSec) return false
        return true
    }

    /**
     * Computes smooth fade-in / fade-out envelope (0..1) inside `[startSec, endSec]`.
     */
    fun computeWindowAlpha(config: WeatherParticleConfig, frameIndex: Int, fps: Int): Float {
        if (!isWindowActive(config, frameIndex, fps)) return 0f
        val safeFps = fps.coerceAtLeast(1).toFloat()
        val timeSec = frameIndex.coerceAtLeast(0) / safeFps
        val fadeSec = 0.35f
        val inAlpha = if (config.startSec > 0f) {
            ((timeSec - config.startSec) / fadeSec).coerceIn(0f, 1f)
        } else 1f
        val outAlpha = if (config.endSec >= 0f) {
            ((config.endSec - timeSec) / fadeSec).coerceIn(0f, 1f)
        } else 1f
        return (inAlpha * outAlpha * config.opacity.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /**
     * Deterministically generates all particle samples for [frameIndex] without side effects.
     */
    fun sampleParticles(
        config: WeatherParticleConfig,
        frameIndex: Int,
        fps: Int,
        width: Float,
        height: Float
    ): List<WeatherParticleSample> {
        val windowAlpha = computeWindowAlpha(config, frameIndex, fps)
        if (windowAlpha <= 0.005f || width <= 1f || height <= 1f) return emptyList()

        val safeFps = fps.coerceAtLeast(1).toFloat()
        val tSec = frameIndex.coerceAtLeast(0) / safeFps
        val density = config.density.coerceIn(0.05f, 1.0f)
        val windSpeed = config.windSpeed.coerceIn(0f, 3.0f)
        val windRad = (config.windAngleDeg.coerceIn(-75f, 75f) * PI / 180.0).toFloat()
        val windSin = sin(windRad)
        val windCos = cos(windRad).coerceAtLeast(0.25f)
        val sizeScale = config.sizeScale.coerceIn(0.3f, 3.0f)
        val baseScale = (kotlin.math.min(width, height) / 720f).coerceIn(0.45f, 2.5f)
        val seed = config.seed

        val maxCount = when (config.style) {
            WeatherParticleStyle.NONE -> 0
            WeatherParticleStyle.RAIN -> 130
            WeatherParticleStyle.FOG -> 14
            WeatherParticleStyle.FALLING_LEAVES -> 42
            WeatherParticleStyle.FIRE_SPARKS -> 65
            WeatherParticleStyle.SMOKE -> 26
            WeatherParticleStyle.DUST_MOTES -> 58
            WeatherParticleStyle.SNOW -> 90
        }
        val count = (maxCount * density).roundToInt().coerceAtLeast(1)
        val out = ArrayList<WeatherParticleSample>(count)

        for (i in 0 until count) {
            val r0 = hash01(seed, i, 1)
            val r1 = hash01(seed, i, 2)
            val r2 = hash01(seed, i, 3)
            val r3 = hash01(seed, i, 4)

            when (config.style) {
                WeatherParticleStyle.RAIN -> {
                    val speedY = (1.55f + r2 * 0.95f) * (0.75f + windSpeed * 0.45f)
                    val speedX = speedY * windSin * (0.8f + windSpeed * 0.45f)
                    val nx = wrap01(r0 + tSec * speedX)
                    val ny = wrap01(r1 + tSec * speedY * windCos)
                    val len = (18f + r2 * 18f) * sizeScale * baseScale
                    val strokeW = (1.4f + r3 * 1.2f) * sizeScale * baseScale
                    val alpha = ((0.45f + r3 * 0.45f) * windowAlpha).coerceIn(0f, 1f)
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = strokeW,
                            angleDeg = config.windAngleDeg,
                            alpha = alpha,
                            colorArgb = rgb(175, 224, 255),
                            lengthPx = len
                        )
                    )
                }

                WeatherParticleStyle.FOG -> {
                    val driftX = (0.025f + r2 * 0.035f) * (0.4f + windSpeed) * (if (windSin >= 0f) 1f else -1f)
                    val nx = wrap01(r0 + tSec * driftX)
                    val ny = 0.20f + r1 * 0.72f + sin(tSec * (0.4f + r3 * 0.3f) + r0 * 6.28f) * 0.025f
                    val puffSize = (150f + r2 * 170f) * sizeScale * baseScale
                    val alpha = ((0.14f + r3 * 0.16f) * windowAlpha).coerceIn(0f, 1f)
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = puffSize,
                            angleDeg = 0f,
                            alpha = alpha,
                            colorArgb = rgb(222, 232, 244),
                            lengthPx = puffSize * 1.9f
                        )
                    )
                }

                WeatherParticleStyle.FALLING_LEAVES -> {
                    val fallSpeed = 0.16f + r2 * 0.14f
                    val swayX = sin(tSec * (2.2f + r3 * 1.8f) + r0 * 6.28f) * 0.045f
                    val windDrift = tSec * windSin * (0.14f + windSpeed * 0.22f)
                    val nx = wrap01(r0 + swayX + windDrift)
                    val ny = wrap01(r1 + tSec * fallSpeed)
                    val tumble = (r3 * 360f + tSec * (65f + r2 * 90f) * (if (i % 2 == 0) 1f else -1f)) % 360f
                    val leafSize = (9f + r2 * 8f) * sizeScale * baseScale
                    val palette = when (i % 4) {
                        0 -> rgb(249, 115, 22)
                        1 -> rgb(234, 88, 12)
                        2 -> rgb(245, 158, 11)
                        else -> rgb(185, 28, 28)
                    }
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = leafSize,
                            angleDeg = tumble,
                            alpha = ((0.72f + r3 * 0.26f) * windowAlpha).coerceIn(0f, 1f),
                            colorArgb = palette,
                            lengthPx = leafSize * 1.65f
                        )
                    )
                }

                WeatherParticleStyle.FIRE_SPARKS -> {
                    val riseSpeed = 0.24f + r2 * 0.34f
                    val life = wrap01(r1 + tSec * riseSpeed)
                    val ny = 1.02f - life * 1.04f
                    val jitter = sin(tSec * (5.5f + r3 * 4f) + r0 * 12f) * 0.025f
                    val windDrift = life * windSin * (0.12f + windSpeed * 0.28f)
                    val nx = wrap01(r0 + jitter + windDrift)
                    val sparkSize = (2.6f + r2 * 3.8f) * (1.1f - life * 0.55f) * sizeScale * baseScale
                    val fade = (1f - life).coerceIn(0f, 1f)
                    val col = if (r3 > 0.5f) rgb(255, 214, 102) else rgb(255, 112, 42)
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = sparkSize.coerceAtLeast(1.2f),
                            angleDeg = config.windAngleDeg,
                            alpha = (fade * (0.65f + r3 * 0.35f) * windowAlpha).coerceIn(0f, 1f),
                            colorArgb = col,
                            lengthPx = sparkSize * 2.4f
                        )
                    )
                }

                WeatherParticleStyle.SMOKE -> {
                    val riseSpeed = 0.09f + r2 * 0.09f
                    val life = wrap01(r1 + tSec * riseSpeed)
                    val ny = 1.05f - life * 1.05f
                    val curlX = sin(tSec * (1.2f + r3) + r0 * 6.28f) * 0.04f
                    val windDrift = life * windSin * (0.14f + windSpeed * 0.26f)
                    val nx = wrap01(r0 + curlX + windDrift)
                    val puffSize = (28f + life * 72f + r2 * 24f) * sizeScale * baseScale
                    val fade = sin(life * PI).toFloat().coerceIn(0f, 1f)
                    val shade = (148 + (r3 * 55).toInt()).coerceIn(130, 215)
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = puffSize,
                            angleDeg = (r3 * 180f + tSec * 14f) % 360f,
                            alpha = (fade * 0.34f * windowAlpha).coerceIn(0f, 1f),
                            colorArgb = rgb(shade, shade + 4, shade + 10)
                        )
                    )
                }

                WeatherParticleStyle.DUST_MOTES -> {
                    val driftX = (0.015f + r2 * 0.025f) * windSin * (0.5f + windSpeed)
                    val driftY = cos(tSec * (0.7f + r3 * 0.6f) + r1 * 6.28f) * 0.03f
                    val nx = wrap01(r0 + tSec * driftX + sin(tSec * 0.55f + i) * 0.02f)
                    val ny = wrap01(r1 + driftY - tSec * 0.012f)
                    val shimmer = 0.45f + 0.55f * abs(sin(tSec * (1.8f + r3 * 2.2f) + r0 * 9f))
                    val moteSize = (2.2f + r2 * 3.4f) * sizeScale * baseScale
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = moteSize,
                            angleDeg = 0f,
                            alpha = (shimmer * 0.65f * windowAlpha).coerceIn(0f, 1f),
                            colorArgb = rgb(254, 243, 180)
                        )
                    )
                }

                WeatherParticleStyle.SNOW -> {
                    val fallSpeed = 0.14f + r2 * 0.16f
                    val sway = sin(tSec * (1.5f + r3 * 1.4f) + r0 * 6.28f) * 0.026f
                    val windDrift = tSec * windSin * (0.09f + windSpeed * 0.18f)
                    val nx = wrap01(r0 + sway + windDrift)
                    val ny = wrap01(r1 + tSec * fallSpeed)
                    val flakeSize = (2.8f + r2 * 4.8f) * sizeScale * baseScale
                    out.add(
                        WeatherParticleSample(
                            x = nx * width,
                            y = ny * height,
                            sizePx = flakeSize,
                            angleDeg = 0f,
                            alpha = ((0.55f + r3 * 0.42f) * windowAlpha).coerceIn(0f, 1f),
                            colorArgb = rgb(236, 248, 255)
                        )
                    )
                }

                WeatherParticleStyle.NONE -> Unit
            }
        }
        return out
    }

    /**
     * Draws deterministic weather particles onto [canvas] in full-stage coordinates.
     */
    fun drawWeather(
        canvas: Canvas,
        width: Float,
        height: Float,
        frameIndex: Int,
        fps: Int,
        config: WeatherParticleConfig
    ) {
        if (!isWindowActive(config, frameIndex, fps)) return
        val particles = sampleParticles(config, frameIndex, fps, width, height)
        if (particles.isEmpty()) return

        val paint = scratchPaint.get()!!
        val rect = scratchRect.get()!!

        when (config.style) {
            WeatherParticleStyle.RAIN -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val rad = (config.windAngleDeg.coerceIn(-75f, 75f) * PI / 180.0).toFloat()
                val dxDir = sin(rad)
                val dyDir = cos(rad).coerceAtLeast(0.25f)
                for (p in particles) {
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 255f).roundToInt().coerceIn(0, 255)
                    paint.strokeWidth = p.sizePx
                    canvas.drawLine(
                        p.x,
                        p.y,
                        p.x + dxDir * p.lengthPx,
                        p.y + dyDir * p.lengthPx,
                        paint
                    )
                }
                paint.style = Paint.Style.FILL
            }

            WeatherParticleStyle.FOG -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    val rx = p.lengthPx * 0.5f
                    val ry = p.sizePx * 0.42f
                    // Soft 2-ring concentric haze without BlurMaskFilter (safe on hardware/software Canvas)
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 145f).roundToInt().coerceIn(0, 255)
                    rect.set(p.x - rx, p.y - ry, p.x + rx, p.y + ry)
                    canvas.drawOval(rect, paint)

                    paint.alpha = (p.alpha * 200f).roundToInt().coerceIn(0, 255)
                    rect.set(p.x - rx * 0.62f, p.y - ry * 0.62f, p.x + rx * 0.62f, p.y + ry * 0.62f)
                    canvas.drawOval(rect, paint)
                }
            }

            WeatherParticleStyle.FALLING_LEAVES -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(p.angleDeg)
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 255f).roundToInt().coerceIn(0, 255)
                    rect.set(-p.lengthPx * 0.5f, -p.sizePx * 0.38f, p.lengthPx * 0.5f, p.sizePx * 0.38f)
                    canvas.drawOval(rect, paint)
                    // Subtle center leaf vein
                    paint.color = Color.rgb(120, 42, 10)
                    paint.alpha = (p.alpha * 130f).roundToInt().coerceIn(0, 255)
                    paint.strokeWidth = (p.sizePx * 0.12f).coerceAtLeast(1f)
                    canvas.drawLine(-p.lengthPx * 0.36f, 0f, p.lengthPx * 0.38f, 0f, paint)
                    canvas.restore()
                }
            }

            WeatherParticleStyle.FIRE_SPARKS -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    // Soft outer glow halo
                    paint.color = Color.rgb(255, 92, 24)
                    paint.alpha = (p.alpha * 95f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 2.1f, paint)
                    // Bright core
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx, paint)
                }
            }

            WeatherParticleStyle.SMOKE -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 140f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 0.62f, paint)
                    paint.alpha = (p.alpha * 190f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x + p.sizePx * 0.14f, p.y - p.sizePx * 0.08f, p.sizePx * 0.42f, paint)
                }
            }

            WeatherParticleStyle.DUST_MOTES -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 85f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 1.9f, paint)
                    paint.alpha = (p.alpha * 240f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 0.85f, paint)
                }
            }

            WeatherParticleStyle.SNOW -> {
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    paint.color = p.colorArgb
                    paint.alpha = (p.alpha * 110f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 1.45f, paint)
                    paint.alpha = (p.alpha * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.sizePx * 0.85f, paint)
                }
            }

            WeatherParticleStyle.NONE -> Unit
        }
    }
}
