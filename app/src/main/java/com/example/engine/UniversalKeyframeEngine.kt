package com.example.engine

import com.example.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Evaluated value of a single [UniversalKeyframeTrack] at a deterministic [frameIndex].
 */
data class UniversalEvaluatedValue(
    val trackId: String,
    val targetId: String,
    val propertyPath: String,
    val domain: UniversalPropertyDomain,
    val valueType: UniversalValueType,
    val floatValue: Float? = null,
    val vec2X: Float? = null,
    val vec2Y: Float? = null,
    val autoOrientDeg: Float? = null,
    val colorArgb: Int? = null,
    val boolValue: Boolean? = null,
    val stringValue: String? = null
)

/**
 * Result of evaluating a curved Bezier [UniversalMotionPathSpec] at progress `u in 0..1`.
 */
data class EvaluatedMotionPoint(
    val x: Float,
    val y: Float,
    val tangentAngleDeg: Float = 0f,
    val hasTangent: Boolean = false
)

/**
 * Result of mapping a global [frameIndex] through a track's [UniversalLoopMode].
 */
data class LoopFrameResult(
    val effectiveFrame: Float,
    val completedCycles: Int,
    val isReversed: Boolean
)

/**
 * Evaluated background transform, 2.5D parallax, depth blur, ambient lighting,
 * day/night blend, and colour grading channels for a single frame.
 */
data class EvaluatedBackgroundGrading(
    val parallaxDepth: Float? = null,
    val bgScrollX: Float = 0f,
    val bgScrollY: Float = 0f,
    val bgScale: Float = 1f,
    val bgRotation: Float = 0f,
    val bgDepthBlur: Float = 0f,
    val ambientLightColorArgb: Int? = null,
    val ambientLightAlpha: Float = 0f,
    val dayNightBlend: Float = 0f,    // 0 = bright day / neutral, 1 = deep night
    val exposure: Float = 0f,         // -1..1 EV offset
    val contrast: Float = 1f,         // 0.2..2.2
    val saturation: Float = 1f,       // 0..2.2
    val temperature: Float = 0f,      // -1 (cool blue) .. +1 (warm amber)
    val vignette: Float = 0f          // 0..1 additional vignette
) {
    val hasBackgroundTransform: Boolean
        get() = abs(bgScrollX) > 0.0001f || abs(bgScrollY) > 0.0001f ||
            abs(bgScale - 1f) > 0.0005f || abs(bgRotation) > 0.01f || bgDepthBlur > 0.01f

    val hasColorGrading: Boolean
        get() = abs(exposure) > 0.005f || abs(contrast - 1f) > 0.005f ||
            abs(saturation - 1f) > 0.005f || abs(temperature) > 0.005f ||
            dayNightBlend > 0.005f || (ambientLightColorArgb != null && ambientLightAlpha > 0.005f) ||
            vignette > 0.005f
}

/**
 * Evaluated clip masking override (position, dimensions, feather, radius).
 */
data class EvaluatedClipMask(
    val hasCustomRectMask: Boolean = false,
    val maskX: Float = 0f,          // Normalized offset (-1..1) relative to clip center
    val maskY: Float = 0f,
    val maskW: Float = 1f,          // Normalized width factor (0.05..2.0)
    val maskH: Float = 1f,          // Normalized height factor (0.05..2.0)
    val feather: Float = 0f,        // 0..1 soft edge feathering
    val radiusOverride: Float? = null
)

/**
 * Evaluated keyframed overrides for a custom scene transition.
 */
data class EvaluatedTransitionKeyOverrides(
    val progressOverride: Float? = null,
    val wipeAngleOverrideDeg: Float? = null,
    val flashAlphaOverride: Float? = null
)

/**
 * Evaluated universal overrides merged cleanly on top of a character clip's base
 * [EvaluatedTransform], [SimultaneousCharacterBlend], and [PropSwayConfig].
 */
data class EvaluatedUniversalCharacterState(
    val transform: EvaluatedTransform,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val depthOrder: Int,
    val orientation: CharacterOrientation,
    val animationPreset: AnimationPresetType,
    val expression: FacialExpression,
    val facialPresetId: String?,
    val mouthOverride: MouthShapeOverride,
    val eyeOverride: EyeShapeOverride,
    val eyebrowOverride: EyebrowShapeOverride,
    val handPose: HandPoseType,
    val blinkOverride: Boolean?,
    val gazeTargetX: Float?,        // -1..1 eye look X
    val gazeTargetY: Float?,        // -1..1 eye look Y
    val poseOffsets: PoseKeyframeOffsets,
    val propSwayEnabled: Boolean,
    val propSwayConfig: PropSwayConfig,
    val propSwayStrength: Float,
    val tintArgb: Int?
)

/**
 * Frame-indexed snapshot of all active [UniversalKeyframeTrack] values for O(1) lookups
 * across renderer passes (live preview & MP4/GIF/PNG export).
 */
class UniversalFrameSnapshot(
    val frameIndex: Int,
    private val byTargetAndPath: Map<String, Map<String, UniversalEvaluatedValue>>,
    private val allValues: List<UniversalEvaluatedValue>
) {
    val isEmpty: Boolean get() = allValues.isEmpty()

    fun get(targetId: String, propertyPath: String): UniversalEvaluatedValue? {
        val targetMap = byTargetAndPath[targetId.lowercase()] ?: return null
        return targetMap[propertyPath.lowercase()]
    }

    fun getFirst(targetIds: List<String>, propertyPaths: List<String>): UniversalEvaluatedValue? {
        for (tid in targetIds) {
            val targetMap = byTargetAndPath[tid.lowercase()] ?: continue
            for (path in propertyPaths) {
                targetMap[path.lowercase()]?.let { return it }
            }
        }
        return null
    }

    fun entriesForTarget(targetId: String): Map<String, UniversalEvaluatedValue> =
        byTargetAndPath[targetId.lowercase()].orEmpty()

    fun allEvaluated(): List<UniversalEvaluatedValue> = allValues

    companion object {
        val EMPTY = UniversalFrameSnapshot(0, emptyMap(), emptyList())
    }
}

/**
 * Universal Advance Keyframe Engine (`UniversalKeyframeEngine`).
 *
 * 100% offline, deterministic by `frameIndex`, pure-Kotlin math (safe for JVM unit tests and
 * Android render threads alike). Unifies keyframing across ALL FrameCraft studio domains—
 * Characters, Backgrounds & 2.5D Parallax, Camera, Transitions, VFX & Weather, Masking,
 * Inline SVG `<g id="...">` sub-parts, and arbitrary future/custom property channels—while
 * wrapping and preserving backward compatibility with existing manual [KeyframeEvaluator] clips.
 */
object UniversalKeyframeEngine {

    /**
     * Dynamically introspected catalog of canonical property channels exposed in
     * [StudioCapabilityRegistry] and `.fcblueprint.json`.
     */
    val canonicalPropertyChannels: List<UniversalPropertyChannelSpec> = listOf(
        // Character & Transform channels
        UniversalPropertyChannelSpec("transform.x", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Normalized X stage position", "0.0..1.0"),
        UniversalPropertyChannelSpec("transform.y", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Normalized Y stage position (ground=0.82)", "0.0..1.0"),
        UniversalPropertyChannelSpec("transform.position", UniversalPropertyDomain.CHARACTER, UniversalValueType.VEC2, "2D position (supports curved Bezier motionPath + autoOrientToPath)", "[0..1, 0..1]"),
        UniversalPropertyChannelSpec("transform.scaleX", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Horizontal scale factor", "0.05..6.0"),
        UniversalPropertyChannelSpec("transform.scaleY", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Vertical scale factor", "0.05..6.0"),
        UniversalPropertyChannelSpec("transform.rotation", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Clockwise rotation in degrees", "-720..720"),
        UniversalPropertyChannelSpec("transform.opacity", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Opacity alpha", "0.0..1.0"),
        UniversalPropertyChannelSpec("transform.flipX", UniversalPropertyDomain.CHARACTER, UniversalValueType.BOOLEAN, "Horizontal mirror flip"),
        UniversalPropertyChannelSpec("transform.flipY", UniversalPropertyDomain.CHARACTER, UniversalValueType.BOOLEAN, "Vertical mirror flip"),
        UniversalPropertyChannelSpec("transform.zIndex", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Clip depth sorting order"),
        UniversalPropertyChannelSpec("character.orientation", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "Character facing angle (FRONT/LEFT/RIGHT/BACK/THREE_QUARTER_*)"),
        UniversalPropertyChannelSpec("character.preset", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "Animation preset name (WALK/RUN/TALKING/FLYING/...)"),
        UniversalPropertyChannelSpec("character.handPose", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "Hand gesture pose (OPEN/FIST/POINT/PEACE/THUMBS_UP/WAVE)"),
        UniversalPropertyChannelSpec("character.blink", UniversalPropertyDomain.CHARACTER, UniversalValueType.BOOLEAN, "Explicit eye blink toggle"),
        UniversalPropertyChannelSpec("character.gazeTarget", UniversalPropertyDomain.CHARACTER, UniversalValueType.VEC2, "Eye look direction (x, y)", "[-1..1, -1..1]"),
        UniversalPropertyChannelSpec("character.headTilt", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Head tilt/nod angle in degrees", "-45..45"),
        UniversalPropertyChannelSpec("character.tint", UniversalPropertyDomain.CHARACTER, UniversalValueType.COLOR, "Character color tint overlay (#AARRGGBB)"),
        UniversalPropertyChannelSpec("expression.preset", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "Facial expression enum or preset ID"),
        UniversalPropertyChannelSpec("expression.mouth", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "MouthShapeOverride enum"),
        UniversalPropertyChannelSpec("expression.eye", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "EyeShapeOverride enum"),
        UniversalPropertyChannelSpec("expression.eyebrow", UniversalPropertyDomain.CHARACTER, UniversalValueType.STRING, "EyebrowShapeOverride enum"),
        UniversalPropertyChannelSpec("ik.head", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK head/neck angle offset (deg)", "-45..45"),
        UniversalPropertyChannelSpec("ik.torso", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK torso/spine lean offset (deg)", "-55..55"),
        UniversalPropertyChannelSpec("ik.leftArm", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK left upper arm angle offset (deg)", "-155..155"),
        UniversalPropertyChannelSpec("ik.leftElbow", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK left forearm/elbow angle offset (deg)", "-145..145"),
        UniversalPropertyChannelSpec("ik.rightArm", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK right upper arm angle offset (deg)", "-155..155"),
        UniversalPropertyChannelSpec("ik.rightElbow", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK right forearm/elbow angle offset (deg)", "-145..145"),
        UniversalPropertyChannelSpec("ik.leftLeg", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK left upper leg/hip angle offset (deg)", "-110..110"),
        UniversalPropertyChannelSpec("ik.leftKnee", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK left knee angle offset (deg)", "-125..125"),
        UniversalPropertyChannelSpec("ik.rightLeg", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK right upper leg/hip angle offset (deg)", "-110..110"),
        UniversalPropertyChannelSpec("ik.rightKnee", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "IK right knee angle offset (deg)", "-125..125"),
        UniversalPropertyChannelSpec("propSway.enabled", UniversalPropertyDomain.CHARACTER, UniversalValueType.BOOLEAN, "Toggle prop sway spring physics"),
        UniversalPropertyChannelSpec("propSway.stiffness", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Prop sway spring stiffness", "10..120"),
        UniversalPropertyChannelSpec("propSway.damping", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Prop sway spring damping", "1..20"),
        UniversalPropertyChannelSpec("propSway.strength", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Prop sway intensity multiplier", "0..2"),
        UniversalPropertyChannelSpec("propSway.maxAngleDeg", UniversalPropertyDomain.CHARACTER, UniversalValueType.FLOAT, "Prop sway maximum angle (deg)", "5..80"),

        // Background & 2.5D Multi-Plane Parallax + Colour Grading
        UniversalPropertyChannelSpec("bg.parallaxDepth", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "2.5D Parallax depth factor (Far Sky=0.1 .. Near FG=1.5)", "0.1..1.5"),
        UniversalPropertyChannelSpec("bg.scrollX", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Seamless horizontal tiling scroll offset (supports OFFSET_REPEAT)", "-100..100"),
        UniversalPropertyChannelSpec("bg.scrollY", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Seamless vertical tiling scroll offset (supports OFFSET_REPEAT)", "-100..100"),
        UniversalPropertyChannelSpec("bg.scale", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Background zoom scale", "0.5..4.0"),
        UniversalPropertyChannelSpec("bg.rotation", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Background rotation in degrees", "-180..180"),
        UniversalPropertyChannelSpec("bg.blur", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Background rack-focus depth blur intensity", "0.0..1.0"),
        UniversalPropertyChannelSpec("bg.ambientLightColor", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.COLOR, "Ambient scene light tint (#AARRGGBB)"),
        UniversalPropertyChannelSpec("bg.dayNightBlend", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Day-to-Night atmosphere blend", "0.0..1.0"),
        UniversalPropertyChannelSpec("colourGrading.exposure", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Exposure EV shift", "-1.0..1.0"),
        UniversalPropertyChannelSpec("colourGrading.contrast", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Contrast multiplier", "0.2..2.2"),
        UniversalPropertyChannelSpec("colourGrading.saturation", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Saturation multiplier", "0.0..2.2"),
        UniversalPropertyChannelSpec("colourGrading.temperature", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Color temperature (-1 cool .. +1 warm)", "-1.0..1.0"),
        UniversalPropertyChannelSpec("colourGrading.vignette", UniversalPropertyDomain.BACKGROUND_PARALLAX, UniversalValueType.FLOAT, "Cinematic radial vignette intensity", "0.0..1.0"),

        // Camera
        UniversalPropertyChannelSpec("camera.x", UniversalPropertyDomain.CAMERA, UniversalValueType.FLOAT, "Camera pan X center", "0.0..1.0"),
        UniversalPropertyChannelSpec("camera.y", UniversalPropertyDomain.CAMERA, UniversalValueType.FLOAT, "Camera pan Y center", "0.0..1.0"),
        UniversalPropertyChannelSpec("camera.position", UniversalPropertyDomain.CAMERA, UniversalValueType.VEC2, "2D Camera path with optional Bezier motionPath", "[0..1, 0..1]"),
        UniversalPropertyChannelSpec("camera.zoom", UniversalPropertyDomain.CAMERA, UniversalValueType.FLOAT, "Camera zoom factor", "0.35..4.0"),
        UniversalPropertyChannelSpec("camera.rotation", UniversalPropertyDomain.CAMERA, UniversalValueType.FLOAT, "Camera Dutch roll angle in degrees", "-180..180"),

        // Transitions
        UniversalPropertyChannelSpec("transition.progress", UniversalPropertyDomain.TRANSITION, UniversalValueType.FLOAT, "Custom transition progress override", "0.0..1.0"),
        UniversalPropertyChannelSpec("transition.wipeAngle", UniversalPropertyDomain.TRANSITION, UniversalValueType.FLOAT, "Transition wipe/mask angle in degrees", "-360..360"),
        UniversalPropertyChannelSpec("transition.flashAlpha", UniversalPropertyDomain.TRANSITION, UniversalValueType.FLOAT, "Transition flash overlay peak alpha", "0.0..1.0"),

        // Masking
        UniversalPropertyChannelSpec("mask.x", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Mask X center offset", "-1.0..1.0"),
        UniversalPropertyChannelSpec("mask.y", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Mask Y center offset", "-1.0..1.0"),
        UniversalPropertyChannelSpec("mask.w", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Mask width scale", "0.05..2.0"),
        UniversalPropertyChannelSpec("mask.h", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Mask height scale", "0.05..2.0"),
        UniversalPropertyChannelSpec("mask.feather", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Mask edge softness", "0.0..1.0"),
        UniversalPropertyChannelSpec("mask.radius", UniversalPropertyDomain.MASK, UniversalValueType.FLOAT, "Clip mask radius", "0.08..0.95"),

        // VFX & Weather
        UniversalPropertyChannelSpec("vfx.intensity", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Scene VFX particle intensity", "0.0..1.0"),
        UniversalPropertyChannelSpec("vfx.speed", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Scene VFX particle speed", "0.2..3.0"),
        UniversalPropertyChannelSpec("vfx.vignette", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Scene VFX vignette", "0.0..1.0"),
        UniversalPropertyChannelSpec("vfx.cameraShake", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Deterministic camera shake amplitude", "0.0..1.0"),
        UniversalPropertyChannelSpec("weather.density", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Weather particle density", "0.0..1.0"),
        UniversalPropertyChannelSpec("weather.windSpeed", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Weather wind speed", "0.0..2.5"),
        UniversalPropertyChannelSpec("weather.windAngle", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Weather wind angle in degrees", "-65..65"),
        UniversalPropertyChannelSpec("weather.opacity", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Weather layer opacity", "0.0..1.0"),
        UniversalPropertyChannelSpec("weather.sizeScale", UniversalPropertyDomain.VFX_WEATHER, UniversalValueType.FLOAT, "Weather particle size scale", "0.4..2.4"),

        // Inline SVG Sub-Part Dynamic Extensions
        UniversalPropertyChannelSpec("svg.<nodeId>.rotation", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> pivot rotation (deg)", "-720..720"),
        UniversalPropertyChannelSpec("svg.<nodeId>.dx", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> normalized X offset", "-2..2"),
        UniversalPropertyChannelSpec("svg.<nodeId>.dy", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> normalized Y offset", "-2..2"),
        UniversalPropertyChannelSpec("svg.<nodeId>.scaleX", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> horizontal scale", "0.05..5.0"),
        UniversalPropertyChannelSpec("svg.<nodeId>.scaleY", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> vertical scale", "0.05..5.0"),
        UniversalPropertyChannelSpec("svg.<nodeId>.opacity", UniversalPropertyDomain.INLINE_SVG, UniversalValueType.FLOAT, "Inline SVG <g id='<nodeId>'> opacity", "0.0..1.0")
    )

    // ────────────────────────── 1. Curve Solver ──────────────────────────

    /**
     * Evaluates an interpolation curve for normalized segment progress `rawT in 0..1`.
     * Note: `ELASTIC`, `SPRING`, and `CUSTOM_CUBIC` can intentionally overshoot `< 0` or `> 1`
     * for realistic anticipation and follow-through motion.
     */
    fun evaluateCurve(
        rawT: Float,
        curve: UniversalCurveType,
        p1x: Float = 0.42f,
        p1y: Float = 0f,
        p2x: Float = 0.58f,
        p2y: Float = 1f,
        springStiffness: Float = 180f,
        springDamping: Float = 14f
    ): Float {
        val t = rawT.coerceIn(0f, 1f)
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return when (curve) {
            UniversalCurveType.LINEAR -> t
            UniversalCurveType.HOLD -> 0f
            UniversalCurveType.EASE_IN -> t * t * t
            UniversalCurveType.EASE_OUT -> 1f - (1f - t).pow(3)
            UniversalCurveType.EASE_IN_OUT ->
                if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
            UniversalCurveType.BOUNCE -> evaluateBounceOut(t)
            UniversalCurveType.ELASTIC -> evaluateElasticOut(t)
            UniversalCurveType.SPRING -> evaluateSpring(t, springStiffness, springDamping)
            UniversalCurveType.CUSTOM_CUBIC -> evaluateCubicBezier(t, p1x, p1y, p2x, p2y)
        }
    }

    /**
     * High-precision Cubic Bezier solver `B_x(u) = targetX -> B_y(u)` using
     * Newton-Raphson iterations with a guaranteed binary-search fallback.
     */
    fun evaluateCubicBezier(
        targetX: Float,
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float
    ): Float {
        val x = targetX.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val cx1 = p1x.coerceIn(0f, 1f)
        val cx2 = p2x.coerceIn(0f, 1f)
        // Fast linear short-circuit
        if (abs(cx1 - p1y) < 1e-5f && abs(cx2 - p2y) < 1e-5f) return x

        val ax = 3f * cx1 - 3f * cx2 + 1f
        val bx = 3f * cx2 - 6f * cx1
        val cx = 3f * cx1

        fun sampleX(u: Float): Float = ((ax * u + bx) * u + cx) * u
        fun sampleDx(u: Float): Float = (3f * ax * u + 2f * bx) * u + cx

        // Newton-Raphson pass (fast convergence for well-behaved curves)
        var u = x
        var converged = false
        for (i in 0 until 8) {
            val err = sampleX(u) - x
            if (abs(err) < 1e-5f) {
                converged = true
                break
            }
            val dx = sampleDx(u)
            if (abs(dx) < 1e-6f) break
            u = (u - err / dx).coerceIn(0f, 1f)
        }

        // Binary search fallback guarantees <= 1e-5 precision even for steep/flat tangents
        if (!converged || abs(sampleX(u) - x) > 1e-5f) {
            var lo = 0f
            var hi = 1f
            u = x
            for (i in 0 until 20) {
                val mid = (lo + hi) * 0.5f
                val mx = sampleX(mid)
                if (abs(mx - x) < 1e-5f) {
                    u = mid
                    break
                }
                if (mx < x) lo = mid else hi = mid
                u = mid
            }
        }

        val ay = 3f * p1y - 3f * p2y + 1f
        val by = 3f * p2y - 6f * p1y
        val cy = 3f * p1y
        return ((ay * u + by) * u + cy) * u
    }

    /**
     * Analytical damped harmonic spring curve (mass = 1).
     * Evaluates natural frequency `wn = sqrt(stiffness)` and damping ratio `zeta = damping / (2*wn)`.
     * Normalized cleanly so `t = 0 -> 0f` and `t = 1 -> 1f`.
     */
    fun evaluateSpring(
        t: Float,
        stiffness: Float = 180f,
        damping: Float = 14f
    ): Float {
        val clampedT = t.coerceIn(0f, 1f)
        if (clampedT <= 0f) return 0f
        if (clampedT >= 1f) return 1f

        val k = stiffness.coerceIn(10f, 2000f).toDouble()
        val c = damping.coerceIn(0.5f, 200f).toDouble()
        val wn = sqrt(k)
        val zeta = c / (2.0 * wn)

        fun rawSpring(tau: Double): Double {
            return when {
                zeta < 0.999 -> {
                    // Underdamped: natural spring overshoot & damped oscillation
                    val wd = wn * sqrt(1.0 - zeta * zeta)
                    val decay = exp(-zeta * wn * tau)
                    1.0 - decay * (cos(wd * tau) + (zeta * wn / wd) * sin(wd * tau))
                }
                zeta <= 1.001 -> {
                    // Critically damped: fastest non-oscillatory approach
                    val decay = exp(-wn * tau)
                    1.0 - decay * (1.0 + wn * tau)
                }
                else -> {
                    // Overdamped: heavy viscous settling
                    val s = sqrt(zeta * zeta - 1.0)
                    val r1 = -wn * (zeta - s)
                    val r2 = -wn * (zeta + s)
                    val a = r2 / (r2 - r1)
                    val b = -r1 / (r2 - r1)
                    1.0 - (a * exp(r1 * tau) + b * exp(r2 * tau))
                }
            }
        }

        // Normalize endpoint drift so the keyframe lands seamlessly on 1.0 at t=1.0
        val endVal = rawSpring(1.0)
        val valAtT = rawSpring(clampedT.toDouble())
        val residual = 1.0 - endVal
        // Blend out any tiny residual smoothly near the end (cubic ramp)
        val correction = residual * (clampedT * clampedT * clampedT)
        return (valAtT + correction).toFloat()
    }

    private fun evaluateBounceOut(t: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> {
                val u = t - 1.5f / d1
                n1 * u * u + 0.75f
            }
            t < 2.5f / d1 -> {
                val u = t - 2.25f / d1
                n1 * u * u + 0.9375f
            }
            else -> {
                val u = t - 2.625f / d1
                n1 * u * u + 0.984375f
            }
        }
    }

    private fun evaluateElasticOut(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = (2.0 * PI) / 3.0
        return (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0).toFloat()
    }

    // ────────────────────────── 2. Loop Mode Mapper ──────────────────────────

    /**
     * Maps a global [frameIndex] into `[firstFrame, lastFrame]` according to [loopMode],
     * returning completed cycles for [UniversalLoopMode.OFFSET_REPEAT].
     */
    fun resolveLoopFrame(
        frameIndex: Float,
        firstFrame: Float,
        lastFrame: Float,
        loopMode: UniversalLoopMode
    ): LoopFrameResult {
        val span = lastFrame - firstFrame
        if (span <= 0.0001f) {
            return LoopFrameResult(firstFrame, completedCycles = 0, isReversed = false)
        }
        return when (loopMode) {
            UniversalLoopMode.ONCE -> {
                LoopFrameResult(
                    effectiveFrame = frameIndex.coerceIn(firstFrame, lastFrame),
                    completedCycles = 0,
                    isReversed = false
                )
            }
            UniversalLoopMode.LOOP -> {
                if (frameIndex <= firstFrame) {
                    LoopFrameResult(firstFrame, completedCycles = 0, isReversed = false)
                } else {
                    val elapsed = frameIndex - firstFrame
                    val cycles = floor((elapsed / span).toDouble()).toInt()
                    val local = elapsed - cycles * span
                    LoopFrameResult(
                        effectiveFrame = (firstFrame + local).coerceIn(firstFrame, lastFrame),
                        completedCycles = cycles,
                        isReversed = false
                    )
                }
            }
            UniversalLoopMode.PING_PONG -> {
                if (frameIndex <= firstFrame) {
                    LoopFrameResult(firstFrame, completedCycles = 0, isReversed = false)
                } else {
                    val elapsed = frameIndex - firstFrame
                    val cycles = floor((elapsed / span).toDouble()).toInt()
                    val local = elapsed - cycles * span
                    val reversed = (cycles and 1) == 1
                    val eff = if (reversed) lastFrame - local else firstFrame + local
                    LoopFrameResult(
                        effectiveFrame = eff.coerceIn(firstFrame, lastFrame),
                        completedCycles = cycles,
                        isReversed = reversed
                    )
                }
            }
            UniversalLoopMode.OFFSET_REPEAT -> {
                val elapsed = frameIndex - firstFrame
                val cycles = floor((elapsed / span).toDouble()).toInt()
                val local = elapsed - cycles * span
                LoopFrameResult(
                    effectiveFrame = (firstFrame + local).coerceIn(firstFrame, lastFrame),
                    completedCycles = cycles,
                    isReversed = false
                )
            }
        }
    }

    // ────────────────────────── 3. Curved Bezier Motion Path ──────────────────────────

    /**
     * Evaluates a curved Bezier [UniversalMotionPathSpec] at normalized parameter `u in 0..1`
     * using de Casteljau's algorithm, plus accurate tangent orientation when `autoOrientToPath` is enabled.
     */
    fun evaluateMotionPath(
        spec: UniversalMotionPathSpec,
        u: Float
    ): EvaluatedMotionPoint? {
        val pts = spec.controlPoints
        if (pts.isEmpty()) return null
        if (pts.size == 1) {
            return EvaluatedMotionPoint(pts[0].x, pts[0].y, spec.orientOffsetDeg, hasTangent = false)
        }
        val clampedU = u.coerceIn(0f, 1f)
        val pos = deCasteljau(pts, clampedU)

        if (!spec.autoOrientToPath) {
            return EvaluatedMotionPoint(pos.x, pos.y, tangentAngleDeg = 0f, hasTangent = false)
        }

        // Compute clean tangent via central finite difference clamped to [0, 1]
        val eps = 0.004f
        val u0 = (clampedU - eps).coerceIn(0f, 1f)
        val u1 = (clampedU + eps).coerceIn(0f, 1f)
        val p0 = deCasteljau(pts, u0)
        val p1 = deCasteljau(pts, u1)
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val hasTangent = (dx * dx + dy * dy) > 1e-10f
        val angleDeg = if (hasTangent) {
            (atan2(dy.toDouble(), dx.toDouble()) * (180.0 / PI)).toFloat() + spec.orientOffsetDeg
        } else {
            spec.orientOffsetDeg
        }
        return EvaluatedMotionPoint(pos.x, pos.y, tangentAngleDeg = angleDeg, hasTangent = hasTangent)
    }

    private fun deCasteljau(points: List<NormalizedPoint>, t: Float): NormalizedPoint {
        var xs = FloatArray(points.size) { points[it].x }
        var ys = FloatArray(points.size) { points[it].y }
        var count = points.size
        while (count > 1) {
            val nextXs = FloatArray(count - 1)
            val nextYs = FloatArray(count - 1)
            for (i in 0 until count - 1) {
                nextXs[i] = xs[i] + (xs[i + 1] - xs[i]) * t
                nextYs[i] = ys[i] + (ys[i + 1] - ys[i]) * t
            }
            xs = nextXs
            ys = nextYs
            count--
        }
        return NormalizedPoint(xs[0], ys[0])
    }

    // ────────────────────────── 4. Core Track Evaluators ──────────────────────────

    /**
     * Evaluates a single [UniversalKeyframeTrack] deterministically at [frameIndex].
     */
    fun evaluateTrack(
        track: UniversalKeyframeTrack,
        frameIndex: Int
    ): UniversalEvaluatedValue? {
        if (!track.enabled || track.keyframes.isEmpty()) return null
        val sorted = if (isSortedByFrame(track.keyframes)) track.keyframes else track.keyframes.sortedBy { it.frame }
        val first = sorted.first()
        val last = sorted.last()

        val loop = resolveLoopFrame(
            frameIndex = frameIndex.toFloat(),
            firstFrame = first.frame.toFloat(),
            lastFrame = last.frame.toFloat(),
            loopMode = track.loopMode
        )

        // Locate surrounding segment [prev, next] around loop.effectiveFrame
        val seg = findSegment(sorted, loop.effectiveFrame)
        val prev = seg.first
        val next = seg.second
        val segSpan = (next.frame - prev.frame).toFloat()
        val rawT = if (segSpan <= 0.0001f) 0f else ((loop.effectiveFrame - prev.frame) / segSpan).coerceIn(0f, 1f)
        val curvedT = evaluateCurve(
            rawT = rawT,
            curve = prev.curve,
            p1x = prev.bezierP1X,
            p1y = prev.bezierP1Y,
            p2x = prev.bezierP2X,
            p2y = prev.bezierP2Y,
            springStiffness = prev.springStiffness,
            springDamping = prev.springDamping
        )

        // Motion path support for VEC2 position tracks (or any track with a motionPath attached)
        val motionSpec = track.motionPath?.takeIf { it.controlPoints.size >= 2 }
        if (motionSpec != null) {
            val totalSpan = (last.frame - first.frame).toFloat().coerceAtLeast(1f)
            val segStartFrac = (prev.frame - first.frame) / totalSpan
            val segEndFrac = (next.frame - first.frame) / totalSpan
            val pathU = (segStartFrac + (segEndFrac - segStartFrac) * curvedT).coerceIn(0f, 1f)
            val mp = evaluateMotionPath(motionSpec, pathU)
            if (mp != null) {
                val cycleDx = if (track.loopMode == UniversalLoopMode.OFFSET_REPEAT && loop.completedCycles != 0) {
                    (motionSpec.controlPoints.last().x - motionSpec.controlPoints.first().x) * loop.completedCycles
                } else 0f
                val cycleDy = if (track.loopMode == UniversalLoopMode.OFFSET_REPEAT && loop.completedCycles != 0) {
                    (motionSpec.controlPoints.last().y - motionSpec.controlPoints.first().y) * loop.completedCycles
                } else 0f
                return UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.VEC2,
                    vec2X = mp.x + cycleDx,
                    vec2Y = mp.y + cycleDy,
                    autoOrientDeg = if (motionSpec.autoOrientToPath && mp.hasTangent) mp.tangentAngleDeg else null
                )
            }
        }

        return when (track.valueType) {
            UniversalValueType.FLOAT -> {
                val v0 = prev.floatValue ?: 0f
                val v1 = next.floatValue ?: v0
                val baseVal = if (prev.curve == UniversalCurveType.HOLD && rawT < 1f) v0 else lerp(v0, v1, curvedT)
                val offsetDelta = if (track.loopMode == UniversalLoopMode.OFFSET_REPEAT && loop.completedCycles != 0) {
                    val fVal = first.floatValue ?: 0f
                    val lVal = last.floatValue ?: fVal
                    (lVal - fVal) * loop.completedCycles
                } else 0f
                UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.FLOAT,
                    floatValue = baseVal + offsetDelta
                )
            }
            UniversalValueType.VEC2 -> {
                val x0 = prev.vec2X ?: prev.floatValue ?: 0f
                val y0 = prev.vec2Y ?: 0f
                val x1 = next.vec2X ?: next.floatValue ?: x0
                val y1 = next.vec2Y ?: y0
                val bx = if (prev.curve == UniversalCurveType.HOLD && rawT < 1f) x0 else lerp(x0, x1, curvedT)
                val by = if (prev.curve == UniversalCurveType.HOLD && rawT < 1f) y0 else lerp(y0, y1, curvedT)
                val dxCycle = if (track.loopMode == UniversalLoopMode.OFFSET_REPEAT && loop.completedCycles != 0) {
                    ((last.vec2X ?: 0f) - (first.vec2X ?: 0f)) * loop.completedCycles
                } else 0f
                val dyCycle = if (track.loopMode == UniversalLoopMode.OFFSET_REPEAT && loop.completedCycles != 0) {
                    ((last.vec2Y ?: 0f) - (first.vec2Y ?: 0f)) * loop.completedCycles
                } else 0f
                UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.VEC2,
                    vec2X = bx + dxCycle,
                    vec2Y = by + dyCycle
                )
            }
            UniversalValueType.COLOR -> {
                val c0 = parseHexColorPure(prev.colorHex, 0x00000000)
                val c1 = parseHexColorPure(next.colorHex, c0)
                val argb = if (prev.curve == UniversalCurveType.HOLD && rawT < 1f) {
                    c0
                } else {
                    lerpColorArgb(c0, c1, curvedT.coerceIn(0f, 1f))
                }
                UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.COLOR,
                    colorArgb = argb
                )
            }
            UniversalValueType.BOOLEAN -> {
                val activeKf = if (rawT >= 0.9999f && next.frame > prev.frame) next else prev
                UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.BOOLEAN,
                    boolValue = activeKf.boolValue ?: (activeKf.floatValue?.let { it >= 0.5f } ?: false)
                )
            }
            UniversalValueType.STRING -> {
                val activeKf = if (rawT >= 0.9999f && next.frame > prev.frame) next else prev
                UniversalEvaluatedValue(
                    trackId = track.id,
                    targetId = track.targetId,
                    propertyPath = track.propertyPath,
                    domain = track.domain,
                    valueType = UniversalValueType.STRING,
                    stringValue = activeKf.stringValue
                )
            }
        }
    }

    /** Convenience float evaluator for unit tests and direct property channel queries. */
    fun evaluateFloat(track: UniversalKeyframeTrack, frameIndex: Int, defaultValue: Float = 0f): Float =
        evaluateTrack(track, frameIndex)?.floatValue ?: defaultValue

    /** Convenience Vec2 evaluator returning `(x, y)`. */
    fun evaluateVec2(track: UniversalKeyframeTrack, frameIndex: Int, defaultX: Float = 0f, defaultY: Float = 0f): Pair<Float, Float> {
        val v = evaluateTrack(track, frameIndex) ?: return defaultX to defaultY
        return (v.vec2X ?: defaultX) to (v.vec2Y ?: defaultY)
    }

    /**
     * Evaluates all enabled [UniversalKeyframeTrack]s in a scene at [frameIndex] once per frame,
     * returning a fast O(1) lookup [UniversalFrameSnapshot].
     */
    fun evaluateAllTracks(
        tracks: List<UniversalKeyframeTrack>,
        frameIndex: Int
    ): UniversalFrameSnapshot {
        if (tracks.isEmpty()) return UniversalFrameSnapshot.EMPTY
        val byTarget = HashMap<String, MutableMap<String, UniversalEvaluatedValue>>(tracks.size)
        val all = ArrayList<UniversalEvaluatedValue>(tracks.size)
        for (track in tracks) {
            if (!track.enabled || track.keyframes.isEmpty()) continue
            val eval = evaluateTrack(track, frameIndex) ?: continue
            val tKey = track.targetId.trim().lowercase()
            val pKey = track.propertyPath.trim().lowercase()
            val inner = byTarget.getOrPut(tKey) { LinkedHashMap() }
            inner[pKey] = eval
            all.add(eval)
        }
        return if (all.isEmpty()) UniversalFrameSnapshot.EMPTY else UniversalFrameSnapshot(frameIndex, byTarget, all)
    }

    // ────────────────────────── 5. Domain Resolvers ──────────────────────────

    /**
     * Resolves camera overrides (`camera.x`, `camera.y`, `camera.position`, `camera.zoom`, `camera.rotation`)
     * cleanly layered on top of existing [EvaluatedCamera].
     */
    fun resolveCameraOverrides(
        snapshot: UniversalFrameSnapshot,
        baseCamera: EvaluatedCamera
    ): EvaluatedCamera {
        if (snapshot.isEmpty) return baseCamera
        val camTargets = listOf("camera", "cam", "scene")
        val pos = snapshot.getFirst(camTargets, listOf("camera.position", "position"))
        val xVal = snapshot.getFirst(camTargets, listOf("camera.x", "x"))?.floatValue ?: pos?.vec2X
        val yVal = snapshot.getFirst(camTargets, listOf("camera.y", "y"))?.floatValue ?: pos?.vec2Y
        val zoomVal = snapshot.getFirst(camTargets, listOf("camera.zoom", "zoom"))?.floatValue
        val rotVal = snapshot.getFirst(camTargets, listOf("camera.rotation", "rotation", "roll"))?.floatValue
            ?: pos?.autoOrientDeg

        if (xVal == null && yVal == null && zoomVal == null && rotVal == null) return baseCamera
        return baseCamera.copy(
            x = (xVal ?: baseCamera.x).coerceIn(0.02f, 0.98f),
            y = (yVal ?: baseCamera.y).coerceIn(0.02f, 0.98f),
            zoom = (zoomVal ?: baseCamera.zoom).coerceIn(0.35f, 4.0f),
            rotation = (rotVal ?: baseCamera.rotation).coerceIn(-180f, 180f)
        )
    }

    /**
     * Resolves dynamic per-layer 2.5D parallax depth (`0.1f..1.5f`) from universal tracks targeting
     * `layer.id` (or `"background"`/`"bg"` for [LayerType.BACKGROUND]).
     */
    fun resolveLayerParallaxDepth(
        snapshot: UniversalFrameSnapshot,
        layer: AnimationLayer
    ): Float {
        if (snapshot.isEmpty) return layer.parallaxDepth
        val targetIds = if (layer.type == LayerType.BACKGROUND) {
            listOf(layer.id, "background", "bg", "scene")
        } else {
            listOf(layer.id)
        }
        val override = snapshot.getFirst(targetIds, listOf("parallaxDepth", "bg.parallaxDepth", "layer.parallaxDepth"))
            ?.floatValue
        return if (override != null) {
            override.coerceIn(0.1f, 1.5f)
        } else {
            layer.parallaxDepth
        }
    }

    /**
     * Resolves background transform (seamless scroll X/Y, scale, rotation, depth blur),
     * ambient light tint, day/night blend, and cinematic colour grading channels.
     */
    fun resolveBackgroundAndGrading(
        snapshot: UniversalFrameSnapshot
    ): EvaluatedBackgroundGrading {
        if (snapshot.isEmpty) return EvaluatedBackgroundGrading()
        val bgTargets = listOf("background", "bg", "scene", "colourGrading", "colorGrading")

        val parallax = snapshot.getFirst(bgTargets, listOf("bg.parallaxDepth", "parallaxDepth"))?.floatValue?.coerceIn(0.1f, 1.5f)
        val scrollX = snapshot.getFirst(bgTargets, listOf("bg.scrollX", "bgScrollX", "scrollX"))?.floatValue ?: 0f
        val scrollY = snapshot.getFirst(bgTargets, listOf("bg.scrollY", "bgScrollY", "scrollY"))?.floatValue ?: 0f
        val bgScale = (snapshot.getFirst(bgTargets, listOf("bg.scale", "bgScale", "scale"))?.floatValue ?: 1f).coerceIn(0.25f, 4.0f)
        val bgRot = snapshot.getFirst(bgTargets, listOf("bg.rotation", "bgRotation", "rotation"))?.floatValue ?: 0f
        val bgBlur = (snapshot.getFirst(bgTargets, listOf("bg.blur", "bg.depthBlur", "bgDepthBlur", "depthBlur"))?.floatValue ?: 0f).coerceIn(0f, 1f)

        val ambientEval = snapshot.getFirst(bgTargets, listOf("bg.ambientLightColor", "ambientLightColor", "scene.ambientLightColor"))
        val ambientArgb = ambientEval?.colorArgb
        val ambientAlpha = if (ambientArgb != null) {
            val extractedAlpha = ((ambientArgb ushr 24) and 0xFF) / 255f
            val explicitAlpha = snapshot.getFirst(bgTargets, listOf("bg.ambientLightAlpha", "ambientLightAlpha"))?.floatValue
            (explicitAlpha ?: if (extractedAlpha > 0.99f) 0.28f else extractedAlpha).coerceIn(0f, 0.85f)
        } else 0f

        val dayNight = (snapshot.getFirst(bgTargets, listOf("bg.dayNightBlend", "dayNightBlend", "scene.dayNightBlend"))?.floatValue ?: 0f).coerceIn(0f, 1f)
        val exposure = (snapshot.getFirst(bgTargets, listOf("colourGrading.exposure", "colorGrading.exposure", "exposure"))?.floatValue ?: 0f).coerceIn(-1f, 1f)
        val contrast = (snapshot.getFirst(bgTargets, listOf("colourGrading.contrast", "colorGrading.contrast", "contrast"))?.floatValue ?: 1f).coerceIn(0.2f, 2.2f)
        val saturation = (snapshot.getFirst(bgTargets, listOf("colourGrading.saturation", "colorGrading.saturation", "saturation"))?.floatValue ?: 1f).coerceIn(0f, 2.2f)
        val temperature = (snapshot.getFirst(bgTargets, listOf("colourGrading.temperature", "colorGrading.temperature", "temperature"))?.floatValue ?: 0f).coerceIn(-1f, 1f)
        val vignette = (snapshot.getFirst(bgTargets, listOf("colourGrading.vignette", "colorGrading.vignette", "bg.vignette"))?.floatValue ?: 0f).coerceIn(0f, 1f)

        return EvaluatedBackgroundGrading(
            parallaxDepth = parallax,
            bgScrollX = scrollX,
            bgScrollY = scrollY,
            bgScale = bgScale,
            bgRotation = bgRot,
            bgDepthBlur = bgBlur,
            ambientLightColorArgb = ambientArgb,
            ambientLightAlpha = ambientAlpha,
            dayNightBlend = dayNight,
            exposure = exposure,
            contrast = contrast,
            saturation = saturation,
            temperature = temperature,
            vignette = vignette
        )
    }

    /**
     * Resolves VFX and Weather keyframe overrides onto [SceneVfxConfig] and [WeatherParticleConfig]s.
     */
    fun resolveVfxAndWeather(
        snapshot: UniversalFrameSnapshot,
        baseVfx: SceneVfxConfig,
        baseWeatherLayers: List<WeatherParticleConfig>
    ): Pair<SceneVfxConfig, List<WeatherParticleConfig>> {
        if (snapshot.isEmpty) return baseVfx to baseWeatherLayers

        val vfxTargets = listOf("vfx", "scene")
        val vfxIntensity = snapshot.getFirst(vfxTargets, listOf("vfx.intensity", "intensity"))?.floatValue
        val vfxSpeed = snapshot.getFirst(vfxTargets, listOf("vfx.speed", "speed"))?.floatValue
        val vfxVignette = snapshot.getFirst(vfxTargets, listOf("vfx.vignette", "vignette"))?.floatValue
        val vfxShake = snapshot.getFirst(vfxTargets, listOf("vfx.cameraShake", "cameraShake"))?.floatValue

        val weatherTargets = listOf("weather", "vfx", "scene")
        val wDensity = snapshot.getFirst(weatherTargets, listOf("weather.density", "density"))?.floatValue
        val wWindSpeed = snapshot.getFirst(weatherTargets, listOf("weather.windSpeed", "windSpeed"))?.floatValue
        val wWindAngle = snapshot.getFirst(weatherTargets, listOf("weather.windAngle", "weather.windAngleDeg", "windAngle"))?.floatValue
        val wOpacity = snapshot.getFirst(weatherTargets, listOf("weather.opacity", "opacity"))?.floatValue
        val wSizeScale = snapshot.getFirst(weatherTargets, listOf("weather.sizeScale", "sizeScale"))?.floatValue

        val updatedPrimaryWeather = baseVfx.weatherConfig.let { w ->
            if (wDensity == null && wWindSpeed == null && wWindAngle == null && wOpacity == null && wSizeScale == null) w
            else w.copy(
                density = (wDensity ?: w.density).coerceIn(0f, 1f),
                windSpeed = (wWindSpeed ?: w.windSpeed).coerceIn(0f, 2.5f),
                windAngleDeg = (wWindAngle ?: w.windAngleDeg).coerceIn(-65f, 65f),
                opacity = (wOpacity ?: w.opacity).coerceIn(0f, 1f),
                sizeScale = (wSizeScale ?: w.sizeScale).coerceIn(0.4f, 2.4f)
            )
        }

        val updatedVfx = baseVfx.copy(
            particleIntensity = (vfxIntensity ?: baseVfx.particleIntensity).coerceIn(0f, 1f),
            particleSpeed = (vfxSpeed ?: baseVfx.particleSpeed).coerceIn(0.2f, 3f),
            vignetteIntensity = (vfxVignette ?: baseVfx.vignetteIntensity).coerceIn(0f, 1f),
            cameraShakeIntensity = (vfxShake ?: baseVfx.cameraShakeIntensity).coerceIn(0f, 1f),
            weatherConfig = updatedPrimaryWeather
        )

        val updatedLayers = if (baseWeatherLayers.isEmpty()) {
            baseWeatherLayers
        } else {
            baseWeatherLayers.mapIndexed { idx, layer ->
                val idxTargets = listOf("weather_$idx", "weather[$idx]", "weather", "scene")
                val d = snapshot.getFirst(idxTargets, listOf("weather.density", "density"))?.floatValue
                val ws = snapshot.getFirst(idxTargets, listOf("weather.windSpeed", "windSpeed"))?.floatValue
                val wa = snapshot.getFirst(idxTargets, listOf("weather.windAngle", "weather.windAngleDeg", "windAngle"))?.floatValue
                val op = snapshot.getFirst(idxTargets, listOf("weather.opacity", "opacity"))?.floatValue
                val ss = snapshot.getFirst(idxTargets, listOf("weather.sizeScale", "sizeScale"))?.floatValue
                if (d == null && ws == null && wa == null && op == null && ss == null) layer
                else layer.copy(
                    density = (d ?: layer.density).coerceIn(0f, 1f),
                    windSpeed = (ws ?: layer.windSpeed).coerceIn(0f, 2.5f),
                    windAngleDeg = (wa ?: layer.windAngleDeg).coerceIn(-65f, 65f),
                    opacity = (op ?: layer.opacity).coerceIn(0f, 1f),
                    sizeScale = (ss ?: layer.sizeScale).coerceIn(0.4f, 2.4f)
                )
            }
        }

        return updatedVfx to updatedLayers
    }

    /**
     * Resolves clip-level transform overrides (`transform.x/y/position/scaleX/scaleY/rotation/opacity/flipX/flipY/zIndex`)
     * for non-character clips (images, inline SVGs, shapes, text, drawings).
     */
    fun resolveGenericClipTransform(
        snapshot: UniversalFrameSnapshot,
        clip: TimelineClip,
        base: EvaluatedTransform
    ): Triple<EvaluatedTransform, Boolean, Boolean> {
        if (snapshot.isEmpty) return Triple(base, clip.flipHorizontal, clip.flipVertical)
        val targets = buildList {
            add(clip.id)
            clip.characterId?.let { add(it) }
            clip.inlineSvgAssetId?.let { add(it) }
        }
        val pos = snapshot.getFirst(targets, listOf("transform.position", "position"))
        val x = snapshot.getFirst(targets, listOf("transform.x", "x"))?.floatValue ?: pos?.vec2X
        val y = snapshot.getFirst(targets, listOf("transform.y", "y"))?.floatValue ?: pos?.vec2Y
        val sx = snapshot.getFirst(targets, listOf("transform.scaleX", "scaleX", "scale"))?.floatValue
        val sy = snapshot.getFirst(targets, listOf("transform.scaleY", "scaleY", "scale"))?.floatValue
        val rot = snapshot.getFirst(targets, listOf("transform.rotation", "rotation"))?.floatValue ?: pos?.autoOrientDeg
        val op = snapshot.getFirst(targets, listOf("transform.opacity", "opacity"))?.floatValue
        val flipX = snapshot.getFirst(targets, listOf("transform.flipX", "flipX"))?.boolValue ?: clip.flipHorizontal
        val flipY = snapshot.getFirst(targets, listOf("transform.flipY", "flipY"))?.boolValue ?: clip.flipVertical

        val out = if (x == null && y == null && sx == null && sy == null && rot == null && op == null) {
            base
        } else {
            base.copy(
                x = x ?: base.x,
                y = y ?: base.y,
                scaleX = (sx ?: base.scaleX).coerceIn(0.05f, 8f),
                scaleY = (sy ?: base.scaleY).coerceIn(0.05f, 8f),
                rotation = rot ?: base.rotation,
                opacity = (op ?: base.opacity).coerceIn(0f, 1f)
            )
        }
        return Triple(out, flipX, flipY)
    }

    /**
     * Resolves dynamic clip depth order (`transform.zIndex` / `zIndex`) for per-frame Z sorting.
     */
    fun resolveClipDepthOrder(snapshot: UniversalFrameSnapshot, clip: TimelineClip): Int {
        if (snapshot.isEmpty) return clip.depthOrder
        val targets = buildList {
            add(clip.id)
            clip.characterId?.let { add(it) }
            clip.inlineSvgAssetId?.let { add(it) }
        }
        val z = snapshot.getFirst(targets, listOf("transform.zIndex", "zIndex", "depthOrder"))?.floatValue
        return z?.toInt() ?: clip.depthOrder
    }

    /**
     * Resolves ALL character keyframe channels (transform, flipX, zIndex, orientation, preset,
     * facial expression + mouth/eye/brow overrides, handPose, blink, gazeTarget, headTilt,
     * every IK bone angle, propSway stiffness/damping/strength/maxAngleDeg, and tint).
     */
    fun resolveCharacterOverrides(
        snapshot: UniversalFrameSnapshot,
        clip: TimelineClip,
        baseTransform: EvaluatedTransform,
        baseMultiTrack: SimultaneousCharacterBlend
    ): EvaluatedUniversalCharacterState {
        val defaultPropSway = PropSwayConfig(
            stiffness = clip.propSwayStiffness,
            damping = clip.propSwayDamping,
            maxAngleDeg = clip.propSwayMaxAngleDeg
        )
        if (snapshot.isEmpty) {
            return EvaluatedUniversalCharacterState(
                transform = baseTransform,
                flipHorizontal = clip.flipHorizontal,
                flipVertical = clip.flipVertical,
                depthOrder = clip.depthOrder,
                orientation = clip.orientation,
                animationPreset = clip.animationPresetType,
                expression = baseMultiTrack.effectiveExpression,
                facialPresetId = baseMultiTrack.effectiveFacialPresetId,
                mouthOverride = baseMultiTrack.effectiveMouthOverride,
                eyeOverride = baseMultiTrack.effectiveEyeOverride,
                eyebrowOverride = baseMultiTrack.effectiveEyebrowOverride,
                handPose = baseMultiTrack.effectiveHandPose,
                blinkOverride = baseMultiTrack.effectiveBlink,
                gazeTargetX = null,
                gazeTargetY = null,
                poseOffsets = baseMultiTrack.blendedPoseOffsets,
                propSwayEnabled = clip.propSwayEnabled,
                propSwayConfig = defaultPropSway,
                propSwayStrength = clip.propSwayStrength,
                tintArgb = null
            )
        }

        val targets = buildList {
            add(clip.id)
            clip.characterId?.let { add(it) }
        }

        val (evalTransform, flipX, flipY) = resolveGenericClipTransform(snapshot, clip, baseTransform)
        val depthOrder = resolveClipDepthOrder(snapshot, clip)

        val orientation = parseEnumSafe<CharacterOrientation>(
            snapshot.getFirst(targets, listOf("character.orientation", "orientation"))?.stringValue
        ) ?: clip.orientation

        val preset = parseEnumSafe<AnimationPresetType>(
            snapshot.getFirst(targets, listOf("character.preset", "animationPreset", "preset"))?.stringValue
        ) ?: clip.animationPresetType

        val exprStr = snapshot.getFirst(targets, listOf("expression.preset", "character.expression", "expression"))?.stringValue
        val parsedExprEnum = parseEnumSafe<FacialExpression>(exprStr)
        val effectiveExpr = parsedExprEnum ?: baseMultiTrack.effectiveExpression
        val effectiveFacialPresetId = if (exprStr != null && parsedExprEnum == null) {
            exprStr
        } else {
            baseMultiTrack.effectiveFacialPresetId
        }

        val mouth = parseEnumSafe<MouthShapeOverride>(
            snapshot.getFirst(targets, listOf("expression.mouth", "mouth", "mouthShape"))?.stringValue
        ) ?: baseMultiTrack.effectiveMouthOverride

        val eye = parseEnumSafe<EyeShapeOverride>(
            snapshot.getFirst(targets, listOf("expression.eye", "expression.eyes", "eye", "eyeShape"))?.stringValue
        ) ?: baseMultiTrack.effectiveEyeOverride

        val brow = parseEnumSafe<EyebrowShapeOverride>(
            snapshot.getFirst(targets, listOf("expression.eyebrow", "expression.brows", "eyebrow", "eyebrowShape"))?.stringValue
        ) ?: baseMultiTrack.effectiveEyebrowOverride

        val handPose = parseEnumSafe<HandPoseType>(
            snapshot.getFirst(targets, listOf("character.handPose", "handPose"))?.stringValue
        ) ?: baseMultiTrack.effectiveHandPose

        val blink = snapshot.getFirst(targets, listOf("character.blink", "blink"))?.boolValue
            ?: baseMultiTrack.effectiveBlink

        val gazeVec = snapshot.getFirst(targets, listOf("character.gazeTarget", "gazeTarget", "gaze"))
        val gazeX = (snapshot.getFirst(targets, listOf("character.gazeX", "gaze.x"))?.floatValue ?: gazeVec?.vec2X)?.coerceIn(-1f, 1f)
        val gazeY = (snapshot.getFirst(targets, listOf("character.gazeY", "gaze.y"))?.floatValue ?: gazeVec?.vec2Y)?.coerceIn(-1f, 1f)

        val headTilt = snapshot.getFirst(targets, listOf("character.headTilt", "headTilt"))?.floatValue ?: 0f

        // IK Bone Angles (layer cleanly on top of blended pose offsets)
        val basePose = baseMultiTrack.blendedPoseOffsets
        val ikHead = snapshot.getFirst(targets, listOf("ik.head", "ik.neck", "ik.headNod"))?.floatValue
        val ikTorso = snapshot.getFirst(targets, listOf("ik.torso", "ik.chest", "ik.spine"))?.floatValue
        val ikLArm = snapshot.getFirst(targets, listOf("ik.leftArm", "ik.leftShoulder"))?.floatValue
        val ikLElbow = snapshot.getFirst(targets, listOf("ik.leftElbow", "ik.leftForearm"))?.floatValue
        val ikRArm = snapshot.getFirst(targets, listOf("ik.rightArm", "ik.rightShoulder"))?.floatValue
        val ikRElbow = snapshot.getFirst(targets, listOf("ik.rightElbow", "ik.rightForearm"))?.floatValue
        val ikLLeg = snapshot.getFirst(targets, listOf("ik.leftLeg", "ik.leftHip"))?.floatValue
        val ikLKnee = snapshot.getFirst(targets, listOf("ik.leftKnee"))?.floatValue
        val ikRLeg = snapshot.getFirst(targets, listOf("ik.rightLeg", "ik.rightHip"))?.floatValue
        val ikRKnee = snapshot.getFirst(targets, listOf("ik.rightKnee"))?.floatValue

        val mergedPose = basePose.copy(
            headNodOffset = ((ikHead ?: basePose.headNodOffset) + headTilt).coerceIn(-65f, 65f),
            torsoTiltOffset = (ikTorso ?: basePose.torsoTiltOffset).coerceIn(-65f, 65f),
            leftArmOffset = (ikLArm ?: basePose.leftArmOffset).coerceIn(-160f, 160f),
            leftForearmOffset = (ikLElbow ?: basePose.leftForearmOffset).coerceIn(-150f, 150f),
            rightArmOffset = (ikRArm ?: basePose.rightArmOffset).coerceIn(-160f, 160f),
            rightForearmOffset = (ikRElbow ?: basePose.rightForearmOffset).coerceIn(-150f, 150f),
            leftLegOffset = (ikLLeg ?: basePose.leftLegOffset).coerceIn(-120f, 120f),
            leftKneeOffset = (ikLKnee ?: basePose.leftKneeOffset).coerceIn(-130f, 130f),
            rightLegOffset = (ikRLeg ?: basePose.rightLegOffset).coerceIn(-120f, 120f),
            rightKneeOffset = (ikRKnee ?: basePose.rightKneeOffset).coerceIn(-130f, 130f)
        )

        val swayEnabled = snapshot.getFirst(targets, listOf("propSway.enabled"))?.boolValue ?: clip.propSwayEnabled
        val swayStiff = (snapshot.getFirst(targets, listOf("propSway.stiffness"))?.floatValue ?: clip.propSwayStiffness).coerceIn(10f, 120f)
        val swayDamp = (snapshot.getFirst(targets, listOf("propSway.damping"))?.floatValue ?: clip.propSwayDamping).coerceIn(1f, 20f)
        val swayMax = (snapshot.getFirst(targets, listOf("propSway.maxAngleDeg"))?.floatValue ?: clip.propSwayMaxAngleDeg).coerceIn(5f, 80f)
        val swayStrength = (snapshot.getFirst(targets, listOf("propSway.strength"))?.floatValue ?: clip.propSwayStrength).coerceIn(0f, 2.0f)

        val tintArgb = snapshot.getFirst(targets, listOf("character.tint", "tint"))?.colorArgb

        return EvaluatedUniversalCharacterState(
            transform = evalTransform,
            flipHorizontal = flipX,
            flipVertical = flipY,
            depthOrder = depthOrder,
            orientation = orientation,
            animationPreset = preset,
            expression = effectiveExpr,
            facialPresetId = effectiveFacialPresetId,
            mouthOverride = mouth,
            eyeOverride = eye,
            eyebrowOverride = brow,
            handPose = handPose,
            blinkOverride = blink,
            gazeTargetX = gazeX,
            gazeTargetY = gazeY,
            poseOffsets = mergedPose,
            propSwayEnabled = swayEnabled,
            propSwayConfig = PropSwayConfig(stiffness = swayStiff, damping = swayDamp, maxAngleDeg = swayMax),
            propSwayStrength = swayStrength,
            tintArgb = tintArgb
        )
    }

    /**
     * Resolves clip mask keyframes (`mask.x`, `mask.y`, `mask.w`, `mask.h`, `mask.feather`, `mask.radius`).
     */
    fun resolveClipMask(
        snapshot: UniversalFrameSnapshot,
        clip: TimelineClip
    ): EvaluatedClipMask {
        if (snapshot.isEmpty) return EvaluatedClipMask()
        val targets = buildList {
            add(clip.id)
            clip.characterId?.let { add(it) }
            clip.inlineSvgAssetId?.let { add(it) }
            add("mask")
        }
        val mx = snapshot.getFirst(targets, listOf("mask.x"))?.floatValue
        val my = snapshot.getFirst(targets, listOf("mask.y"))?.floatValue
        val mw = snapshot.getFirst(targets, listOf("mask.w", "mask.width"))?.floatValue
        val mh = snapshot.getFirst(targets, listOf("mask.h", "mask.height"))?.floatValue
        val feather = snapshot.getFirst(targets, listOf("mask.feather"))?.floatValue ?: 0f
        val radius = snapshot.getFirst(targets, listOf("mask.radius"))?.floatValue?.coerceIn(0.08f, 0.95f)
        val hasRect = mx != null || my != null || mw != null || mh != null
        return EvaluatedClipMask(
            hasCustomRectMask = hasRect,
            maskX = (mx ?: 0f).coerceIn(-1f, 1f),
            maskY = (my ?: 0f).coerceIn(-1f, 1f),
            maskW = (mw ?: 1f).coerceIn(0.05f, 2f),
            maskH = (mh ?: 1f).coerceIn(0.05f, 2f),
            feather = feather.coerceIn(0f, 1f),
            radiusOverride = radius
        )
    }

    /**
     * Resolves keyframed transition overrides (`transition.progress`, `transition.wipeAngle`, `transition.flashAlpha`)
     * targeting a transition's ID, `"transition_in"`, `"transition_out"`, or `"transition"`.
     */
    fun resolveTransitionOverrides(
        snapshot: UniversalFrameSnapshot,
        transitionId: String?,
        isEntering: Boolean
    ): EvaluatedTransitionKeyOverrides {
        if (snapshot.isEmpty) return EvaluatedTransitionKeyOverrides()
        val targets = buildList {
            if (!transitionId.isNullOrBlank()) add(transitionId)
            add(if (isEntering) "transition_in" else "transition_out")
            add("transition")
            add("scene")
        }
        val prog = snapshot.getFirst(targets, listOf("transition.progress", "progress"))?.floatValue?.coerceIn(0f, 1f)
        val angle = snapshot.getFirst(targets, listOf("transition.wipeAngle", "transition.angle", "wipeAngle"))?.floatValue
        val flash = snapshot.getFirst(targets, listOf("transition.flashAlpha", "flashAlpha"))?.floatValue?.coerceIn(0f, 1f)
        return EvaluatedTransitionKeyOverrides(
            progressOverride = prog,
            wipeAngleOverrideDeg = angle,
            flashAlphaOverride = flash
        )
    }

    /**
     * Resolves Inline SVG `<g id="...">` sub-part tracks dynamically—supporting BOTH:
     * 1. `targetId = clip.id` / `inlineSvgAssetId` with `propertyPath = "svg.<nodeId>.(dx|dy|rotation|scaleX|scaleY|opacity)"`
     * 2. `targetId = <nodeId>` directly with `propertyPath = "rotation" | "dx" | "dy" | "scaleX" | "scaleY" | "opacity"`
     *
     * Merges seamlessly on top of [baseNodeTransforms] evaluated by [UniversalCustomAnimationEngine].
     */
    fun resolveInlineSvgNodeTransforms(
        snapshot: UniversalFrameSnapshot,
        clipId: String,
        inlineSvgAssetId: String?,
        knownNodeIds: List<String>,
        baseNodeTransforms: Map<String, EvaluatedNodeTransform>
    ): Map<String, EvaluatedNodeTransform> {
        if (snapshot.isEmpty) return baseNodeTransforms

        val merged = LinkedHashMap<String, EvaluatedNodeTransform>(baseNodeTransforms)
        val clipTargets = buildList {
            add(clipId.lowercase())
            if (!inlineSvgAssetId.isNullOrBlank()) add(inlineSvgAssetId.lowercase())
        }

        fun applyProp(nodeId: String, prop: String, value: Float) {
            val existingKey = merged.keys.firstOrNull { it.equals(nodeId, ignoreCase = true) } ?: nodeId
            val cur = merged[existingKey] ?: EvaluatedNodeTransform()
            merged[existingKey] = when (prop.lowercase()) {
                "dx", "x" -> cur.copy(dx = cur.dx + value)
                "dy", "y" -> cur.copy(dy = cur.dy + value)
                "rotation", "rot" -> cur.copy(rotation = cur.rotation + value)
                "scalex", "sx" -> cur.copy(scaleX = (cur.scaleX * value).coerceIn(0.05f, 8f))
                "scaley", "sy" -> cur.copy(scaleY = (cur.scaleY * value).coerceIn(0.05f, 8f))
                "opacity", "alpha" -> cur.copy(opacity = (cur.opacity * value).coerceIn(0f, 1f))
                else -> cur
            }
        }

        // Pattern A: targetId matches clip/asset and propertyPath is "svg.<nodeId>.<prop>"
        for (tKey in clipTargets) {
            for ((path, eval) in snapshot.entriesForTarget(tKey)) {
                val fv = eval.floatValue ?: continue
                if (path.startsWith("svg.")) {
                    val parts = path.split('.')
                    if (parts.size >= 3) {
                        val nodeId = parts[1]
                        val prop = parts[2]
                        applyProp(nodeId, prop, fv)
                    }
                }
            }
        }

        // Pattern B: targetId directly matches an SVG <g id="..."> nodeId
        for (nodeId in knownNodeIds) {
            val entries = snapshot.entriesForTarget(nodeId)
            if (entries.isEmpty()) continue
            for ((path, eval) in entries) {
                val fv = eval.floatValue ?: continue
                val cleanProp = path.substringAfterLast('.')
                applyProp(nodeId, cleanProp, fv)
            }
        }

        return merged
    }

    // ────────────────────────── 6. Pure-Kotlin Utilities ──────────────────────────

    /**
     * Pure-Kotlin hex color parser supporting `#RGB`, `#RRGGBB`, and `#AARRGGBB`
     * without calling Android's `Color.parseColor` (100% JVM unit-test safe).
     */
    fun parseHexColorPure(hex: String?, fallbackArgb: Int): Int {
        if (hex.isNullOrBlank()) return fallbackArgb
        val clean = hex.trim().removePrefix("#")
        return try {
            when (clean.length) {
                3 -> {
                    val r = clean[0].digitToInt(16) * 17
                    val g = clean[1].digitToInt(16) * 17
                    val b = clean[2].digitToInt(16) * 17
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                6 -> {
                    val rgb = clean.toLong(16).toInt()
                    (0xFF shl 24) or (rgb and 0x00FFFFFF)
                }
                8 -> clean.toLong(16).toInt()
                else -> fallbackArgb
            }
        } catch (_: Throwable) {
            fallbackArgb
        }
    }

    /** Pure-Kotlin linear ARGB color interpolation (JVM unit-test safe). */
    fun lerpColorArgb(c1: Int, c2: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val a1 = (c1 ushr 24) and 0xFF
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF

        val a2 = (c2 ushr 24) and 0xFF
        val r2 = (c2 ushr 16) and 0xFF
        val g2 = (c2 ushr 8) and 0xFF
        val b2 = c2 and 0xFF

        val a = (a1 + (a2 - a1) * u).toInt().coerceIn(0, 255)
        val r = (r1 + (r2 - r1) * u).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * u).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * u).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun isSortedByFrame(kfs: List<UniversalKeyframe>): Boolean {
        for (i in 1 until kfs.size) {
            if (kfs[i].frame < kfs[i - 1].frame) return false
        }
        return true
    }

    private fun findSegment(
        sorted: List<UniversalKeyframe>,
        effectiveFrame: Float
    ): Pair<UniversalKeyframe, UniversalKeyframe> {
        if (sorted.size == 1 || effectiveFrame <= sorted.first().frame) {
            return sorted.first() to sorted.first()
        }
        if (effectiveFrame >= sorted.last().frame) {
            return sorted.last() to sorted.last()
        }
        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (effectiveFrame >= a.frame && effectiveFrame <= b.frame) {
                return a to b
            }
        }
        return sorted.last() to sorted.last()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private inline fun <reified T : Enum<T>> parseEnumSafe(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        val norm = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name.equals(norm, ignoreCase = true) }
    }
}
