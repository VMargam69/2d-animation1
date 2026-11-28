package com.example.model

import java.util.UUID

/**
 * Interpolation curves supported by [com.example.engine.UniversalKeyframeEngine].
 * Covers standard easing, instant HOLD steps, bounce, elastic, analytical damped SPRING,
 * and arbitrary cubic Bezier curves (`cubicBezier(x1, y1, x2, y2)`).
 */
enum class UniversalCurveType(val displayName: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
    HOLD("Hold (Step)"),
    BOUNCE("Bounce"),
    ELASTIC("Elastic"),
    SPRING("Spring Physics"),
    CUSTOM_CUBIC("Custom Cubic Bezier")
}

/**
 * Per-track loop / extrapolation modes after a track's final keyframe (or before its first).
 * - [ONCE]: Clamps to first/last keyframe value outside the track span.
 * - [LOOP]: Repeats from the first keyframe cleanly on every cycle.
 * - [PING_PONG]: Alternates forward and backward smoothly across cycles.
 * - [OFFSET_REPEAT]: Repeats the curve while accumulating `(lastValue - firstValue)` per completed cycle
 *   for infinite scrolling backgrounds, continuous walking cycles, or endless rotation.
 */
enum class UniversalLoopMode(val displayName: String) {
    ONCE("Once"),
    LOOP("Loop"),
    PING_PONG("Ping-Pong"),
    OFFSET_REPEAT("Offset Repeat")
}

/**
 * High-level studio domain targeted by a [UniversalKeyframeTrack].
 * Purely organizational/introspective; the engine evaluates any custom `targetId` + `propertyPath`
 * dynamically regardless of domain.
 */
enum class UniversalPropertyDomain(val displayName: String) {
    CHARACTER("Character & Rig"),
    BACKGROUND_PARALLAX("Background & 2.5D Parallax"),
    CAMERA("Camera"),
    TRANSITION("Transitions"),
    VFX_WEATHER("VFX & Weather"),
    MASK("Masking"),
    INLINE_SVG("Inline SVG Sub-Parts"),
    CUSTOM("Custom / Dynamic")
}

/**
 * Value payload type stored in each [UniversalKeyframe].
 */
enum class UniversalValueType(val displayName: String) {
    FLOAT("Float"),
    VEC2("Vec2 (x, y)"),
    COLOR("Color (#AARRGGBB)"),
    BOOLEAN("Boolean"),
    STRING("String / Enum")
}

/**
 * Optional curved Bezier motion path attached to a 2D position track (or `transform.position` / `camera.position`).
 * When [autoOrientToPath] is true, the engine computes the tangent angle along the Bezier path
 * so flying birds, vehicles, or moving characters automatically orient along their trajectory.
 */
data class UniversalMotionPathSpec(
    val controlPoints: List<NormalizedPoint> = emptyList(),
    val autoOrientToPath: Boolean = false,
    val orientOffsetDeg: Float = 0f
)

/**
 * A single typed keyframe inside a [UniversalKeyframeTrack].
 * Uses Moshi-friendly nullable typed fields so local JSON persistence and `.fcscript.json`
 * serialization work offline without custom polymorphic adapters.
 */
data class UniversalKeyframe(
    val frame: Int = 0,
    val floatValue: Float? = null,
    val vec2X: Float? = null,
    val vec2Y: Float? = null,
    val colorHex: String? = null,
    val boolValue: Boolean? = null,
    val stringValue: String? = null,
    val curve: UniversalCurveType = UniversalCurveType.EASE_IN_OUT,
    val bezierP1X: Float = 0.42f,
    val bezierP1Y: Float = 0f,
    val bezierP2X: Float = 0.58f,
    val bezierP2Y: Float = 1f,
    val springStiffness: Float = 180f,
    val springDamping: Float = 14f
)

/**
 * A generic, property-channel keyframe track that can animate ANY studio element or future
 * dynamic property identified by `(targetId, propertyPath)`.
 *
 * Examples of `targetId`:
 * - Character ID or Clip ID (e.g., `"clip_hero"`, `"char_boy"`)
 * - Layer ID (e.g., `"layer_far_mountains"`)
 * - Global studio channels: `"camera"`, `"background"` / `"bg"`, `"scene"`, `"vfx"`, `"weather"`
 * - Transition ID (e.g., `"golden_whip_iris"`, `"transition_in"`, `"transition_out"`)
 * - Inline SVG asset ID or `<g id="...">` sub-part ID (e.g., `"svg_windy_tree"`, `"tree_canopy"`)
 *
 * Examples of `propertyPath`:
 * - `"transform.x"`, `"transform.y"`, `"transform.position"`, `"transform.scaleX"`, `"transform.rotation"`, `"transform.opacity"`, `"transform.flipX"`, `"transform.zIndex"`
 * - `"character.orientation"`, `"character.preset"`, `"character.handPose"`, `"character.blink"`, `"character.headTilt"`, `"character.tint"`, `"character.gazeTarget"`
 * - `"expression.preset"`, `"expression.mouth"`, `"expression.eye"`, `"expression.eyebrow"`
 * - `"ik.head"`, `"ik.torso"`, `"ik.leftArm"`, `"ik.leftForearm"`, `"ik.leftElbow"`, `"ik.rightArm"`, `"ik.rightElbow"`, `"ik.leftLeg"`, `"ik.leftKnee"`, `"ik.rightLeg"`, `"ik.rightKnee"`
 * - `"propSway.stiffness"`, `"propSway.damping"`, `"propSway.strength"`, `"propSway.maxAngleDeg"`, `"propSway.enabled"`
 * - `"bg.parallaxDepth"`, `"bg.scrollX"`, `"bg.scrollY"`, `"bg.scale"`, `"bg.rotation"`, `"bg.blur"`, `"bg.ambientLightColor"`, `"bg.dayNightBlend"`
 * - `"colourGrading.exposure"`, `"colourGrading.contrast"`, `"colourGrading.saturation"`, `"colourGrading.temperature"`, `"colourGrading.vignette"`
 * - `"camera.x"`, `"camera.y"`, `"camera.position"`, `"camera.zoom"`, `"camera.rotation"`
 * - `"transition.progress"`, `"transition.wipeAngle"`, `"transition.flashAlpha"`
 * - `"mask.x"`, `"mask.y"`, `"mask.w"`, `"mask.h"`, `"mask.feather"`, `"mask.radius"`
 * - `"vfx.intensity"`, `"vfx.speed"`, `"vfx.vignette"`, `"vfx.cameraShake"`
 * - `"weather.density"`, `"weather.windSpeed"`, `"weather.windAngle"`, `"weather.opacity"`, `"weather.sizeScale"`
 * - `"svg.<nodeId>.rotation"`, `"svg.<nodeId>.dx"`, `"svg.<nodeId>.dy"`, `"svg.<nodeId>.scaleX"`, `"svg.<nodeId>.scaleY"`, `"svg.<nodeId>.opacity"`
 */
data class UniversalKeyframeTrack(
    val id: String = UUID.randomUUID().toString(),
    val targetId: String,
    val propertyPath: String,
    val domain: UniversalPropertyDomain = UniversalPropertyDomain.CUSTOM,
    val valueType: UniversalValueType = UniversalValueType.FLOAT,
    val loopMode: UniversalLoopMode = UniversalLoopMode.ONCE,
    val enabled: Boolean = true,
    val motionPath: UniversalMotionPathSpec? = null,
    val keyframes: List<UniversalKeyframe> = emptyList()
)

/**
 * Structured metadata descriptor for a canonical property channel exposed in
 * [com.example.engine.StudioCapabilityRegistry] and `.fcblueprint.json`.
 */
data class UniversalPropertyChannelSpec(
    val propertyPath: String,
    val domain: UniversalPropertyDomain,
    val valueType: UniversalValueType,
    val description: String,
    val rangeHint: String? = null
)
