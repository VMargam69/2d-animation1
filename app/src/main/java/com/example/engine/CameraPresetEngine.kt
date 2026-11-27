package com.example.engine

import com.example.model.CameraKeyframe
import com.example.model.EasingType
import kotlin.math.roundToInt

enum class CameraPresetCategory(val displayName: String) {
    ALL("All"),
    FRAMING("Framing"),
    TRACKING("Tracking"),
    DYNAMIC("Dynamic"),
    ORGANIC("Organic")
}

data class CameraControlPreset(
    val id: String,
    val name: String,
    val shortLabel: String,
    val category: CameraPresetCategory,
    val description: String,
    val defaultSpanFrames: Int
)

/**
 * Deterministic, offline-first Advanced Camera Control Preset engine.
 * Generates multi-keyframe pan, zoom, and rotation sequences persisted directly
 * in [com.example.model.AnimationScene.cameraKeyframes] for live preview and export.
 */
object CameraPresetEngine {

    val presets: List<CameraControlPreset> = listOf(
        CameraControlPreset(
            id = "push_in",
            name = "Cinematic Push-In",
            shortLabel = "Push-In",
            category = CameraPresetCategory.FRAMING,
            description = "Smooth slow zoom toward the stage center",
            defaultSpanFrames = 48
        ),
        CameraControlPreset(
            id = "pull_out",
            name = "Wide Pull-Out",
            shortLabel = "Pull-Out",
            category = CameraPresetCategory.FRAMING,
            description = "Start tight and reveal the full scene",
            defaultSpanFrames = 48
        ),
        CameraControlPreset(
            id = "crane_reveal",
            name = "Crane Up Reveal",
            shortLabel = "Crane Up",
            category = CameraPresetCategory.FRAMING,
            description = "Rise from low close-up to establishing wide shot",
            defaultSpanFrames = 54
        ),
        CameraControlPreset(
            id = "pan_lr",
            name = "Smooth Pan L→R",
            shortLabel = "Pan L→R",
            category = CameraPresetCategory.TRACKING,
            description = "Steady horizontal tracking sweep left to right",
            defaultSpanFrames = 56
        ),
        CameraControlPreset(
            id = "pan_rl",
            name = "Smooth Pan R→L",
            shortLabel = "Pan R→L",
            category = CameraPresetCategory.TRACKING,
            description = "Steady horizontal tracking sweep right to left",
            defaultSpanFrames = 56
        ),
        CameraControlPreset(
            id = "whip_pan",
            name = "Action Whip Pan",
            shortLabel = "Whip Pan",
            category = CameraPresetCategory.TRACKING,
            description = "Fast snap pan across stage with elastic settle",
            defaultSpanFrames = 24
        ),
        CameraControlPreset(
            id = "crash_zoom",
            name = "Crash Zoom Impact",
            shortLabel = "Crash Zoom",
            category = CameraPresetCategory.DYNAMIC,
            description = "Punchy elastic zoom-in with slight dramatic roll",
            defaultSpanFrames = 20
        ),
        CameraControlPreset(
            id = "dutch_sweep",
            name = "Dutch Roll Sweep",
            shortLabel = "Dutch Sweep",
            category = CameraPresetCategory.DYNAMIC,
            description = "Cinematic tilted sweep with dynamic roll recovery",
            defaultSpanFrames = 52
        ),
        CameraControlPreset(
            id = "dolly_pulse",
            name = "Vertigo Zoom Pulse",
            shortLabel = "Vertigo Pulse",
            category = CameraPresetCategory.DYNAMIC,
            description = "Tension zoom-in peak that eases back to frame",
            defaultSpanFrames = 44
        ),
        CameraControlPreset(
            id = "beat_cut_zoom",
            name = "Beat Cut Punch-In",
            shortLabel = "Beat Cut",
            category = CameraPresetCategory.DYNAMIC,
            description = "Snappy stepped close-up cuts using Hold keyframes",
            defaultSpanFrames = 42
        ),
        CameraControlPreset(
            id = "orbit_sway",
            name = "Cinematic Arc Sway",
            shortLabel = "Arc Sway",
            category = CameraPresetCategory.ORGANIC,
            description = "Gentle curved pan with synchronized subtle roll",
            defaultSpanFrames = 64
        ),
        CameraControlPreset(
            id = "handheld_drift",
            name = "Handheld Breathing",
            shortLabel = "Handheld",
            category = CameraPresetCategory.ORGANIC,
            description = "Natural multi-key documentary drift & micro-roll",
            defaultSpanFrames = 60
        )
    )

    private val presetsById: Map<String, CameraControlPreset> = presets.associateBy { it.id }

    fun presetById(id: String): CameraControlPreset? = presetsById[id]

    /**
     * Generates deterministic [CameraKeyframe]s for [presetId] starting at [startFrame],
     * scaled by [intensity] (`0.4f..1.8f`) and clamped cleanly inside `[0, maxFrame]`.
     */
    fun generateKeyframes(
        presetId: String,
        startFrame: Int,
        sceneDurationFrames: Int,
        intensity: Float = 1.0f,
        customSpanFrames: Int? = null
    ): List<CameraKeyframe> {
        val preset = presetById(presetId) ?: return emptyList()
        val maxFrame = (sceneDurationFrames - 1).coerceAtLeast(12)
        val safeIntensity = intensity.coerceIn(0.4f, 1.8f)
        val desiredSpan = (customSpanFrames ?: preset.defaultSpanFrames).coerceIn(12, 120)
        val f0 = startFrame.coerceIn(0, (maxFrame - 10).coerceAtLeast(0))
        val span = (maxFrame - f0).coerceIn(10, desiredSpan)
        val f1 = (f0 + (span * 0.25f).roundToInt()).coerceIn(f0 + 1, maxFrame)
        val fMid = (f0 + (span * 0.50f).roundToInt()).coerceIn(f1, maxFrame)
        val f3 = (f0 + (span * 0.75f).roundToInt()).coerceIn(fMid, maxFrame)
        val fEnd = (f0 + span).coerceAtMost(maxFrame)

        fun z(delta: Float): Float = (1.0f + delta * safeIntensity).coerceIn(0.45f, 3.2f)
        fun px(delta: Float): Float = (0.50f + delta * safeIntensity).coerceIn(0.08f, 0.92f)
        fun py(delta: Float): Float = (0.50f + delta * safeIntensity).coerceIn(0.08f, 0.92f)
        fun r(deg: Float): Float = (deg * safeIntensity).coerceIn(-180f, 180f)

        val rawKeys: List<CameraKeyframe> = when (presetId) {
            "push_in" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = py(-0.04f), zoom = z(0.40f), rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "pull_out" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = py(-0.04f), zoom = z(0.55f), rotation = 0f, easing = EasingType.EASE_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_IN_OUT)
            )
            "crane_reveal" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = py(0.14f), zoom = z(0.48f), rotation = r(-2.5f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fMid, x = 0.50f, y = py(0.03f), zoom = z(0.24f), rotation = r(1.0f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = py(-0.06f), zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "pan_lr" -> listOf(
                CameraKeyframe(frame = f0, x = px(-0.15f), y = 0.50f, zoom = z(0.30f), rotation = 0f, easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = px(0.15f), y = 0.50f, zoom = z(0.30f), rotation = 0f, easing = EasingType.EASE_IN_OUT)
            )
            "pan_rl" -> listOf(
                CameraKeyframe(frame = f0, x = px(0.15f), y = 0.50f, zoom = z(0.30f), rotation = 0f, easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = px(-0.15f), y = 0.50f, zoom = z(0.30f), rotation = 0f, easing = EasingType.EASE_IN_OUT)
            )
            "whip_pan" -> listOf(
                CameraKeyframe(frame = f0, x = px(-0.18f), y = 0.50f, zoom = z(0.32f), rotation = r(-4f), easing = EasingType.EASE_IN),
                CameraKeyframe(frame = fMid, x = px(0.19f), y = 0.50f, zoom = z(0.38f), rotation = r(5f), easing = EasingType.ELASTIC),
                CameraKeyframe(frame = fEnd, x = px(0.14f), y = 0.50f, zoom = z(0.28f), rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "crash_zoom" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.ELASTIC),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = py(-0.05f), zoom = z(0.68f), rotation = r(-3.2f), easing = EasingType.EASE_OUT)
            )
            "dutch_sweep" -> listOf(
                CameraKeyframe(frame = f0, x = px(-0.07f), y = 0.50f, zoom = z(0.20f), rotation = r(-8.5f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fMid, x = px(0.07f), y = py(-0.03f), zoom = z(0.28f), rotation = r(7.0f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = z(0.08f), rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "dolly_pulse" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_OUT),
                CameraKeyframe(frame = fMid, x = 0.50f, y = py(-0.04f), zoom = z(0.56f), rotation = r(2.5f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "beat_cut_zoom" -> listOf(
                CameraKeyframe(frame = f0, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.HOLD),
                CameraKeyframe(frame = f1, x = px(-0.06f), y = py(-0.04f), zoom = z(0.36f), rotation = r(-2f), easing = EasingType.HOLD),
                CameraKeyframe(frame = fMid, x = px(0.06f), y = py(-0.06f), zoom = z(0.62f), rotation = r(2.5f), easing = EasingType.HOLD),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = 1.0f, rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "orbit_sway" -> listOf(
                CameraKeyframe(frame = f0, x = px(-0.10f), y = py(0.02f), zoom = z(0.24f), rotation = r(-4.5f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = f1, x = 0.50f, y = py(-0.04f), zoom = z(0.30f), rotation = 0f, easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = f3, x = px(0.10f), y = py(0.02f), zoom = z(0.24f), rotation = r(4.5f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = z(0.16f), rotation = 0f, easing = EasingType.EASE_OUT)
            )
            "handheld_drift" -> listOf(
                CameraKeyframe(frame = f0, x = px(-0.025f), y = py(0.015f), zoom = z(0.12f), rotation = r(-1.6f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = f1, x = px(0.022f), y = py(-0.018f), zoom = z(0.16f), rotation = r(1.3f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fMid, x = px(-0.015f), y = py(-0.022f), zoom = z(0.13f), rotation = r(-1.1f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = f3, x = px(0.028f), y = py(0.016f), zoom = z(0.17f), rotation = r(1.8f), easing = EasingType.EASE_IN_OUT),
                CameraKeyframe(frame = fEnd, x = 0.50f, y = 0.50f, zoom = z(0.12f), rotation = 0f, easing = EasingType.EASE_IN_OUT)
            )
            else -> emptyList()
        }

        return rawKeys.distinctBy { it.frame }.sortedBy { it.frame }
    }
}
