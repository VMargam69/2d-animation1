package com.example.engine

import com.example.model.AtlasPartSlot
import com.example.model.FacialExpression
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Target facial rig parameters defining the emotional "pose" of a preset before
 * organic anticipation, overshoot, staggered overlap, micro-saccades, and natural
 * blink dynamics are layered on top.
 */
data class FacialExpressionTarget(
    val leftEyeOpenness: Float = 1.0f,       // 0 = closed, 1 = normal, 1.35 = wide shock
    val rightEyeOpenness: Float = 1.0f,
    val leftEyelidClose: Float = 0.0f,       // 0 = open, 1 = fully closed lid
    val rightEyelidClose: Float = 0.0f,
    val happyEyeArc: Float = 0.0f,           // 0..1 smiling crescent eye / Duchenne lift
    val pupilScale: Float = 1.0f,            // 0.72 = pinned shock/anger, 1.15 = warm happy
    val gazeBiasX: Float = 0.0f,             // -1..1 horizontal gaze bias
    val gazeBiasY: Float = 0.0f,             // -1..1 vertical gaze bias (+ down, - up)
    val leftBrowLift: Float = 0.0f,          // -1 = furrowed down, +1 = raised high
    val rightBrowLift: Float = 0.0f,
    val leftBrowTiltDeg: Float = 0.0f,       // + = angry inward dip, - = sad inner raise
    val rightBrowTiltDeg: Float = 0.0f,
    val mouthOpen: Float = 0.0f,             // 0..1 jaw openness
    val smileCurve: Float = 0.1f,            // -1 = frown, 0 = neutral, +1 = wide smile
    val mouthWidth: Float = 1.0f,            // 0.65..1.25 horizontal stretch
    val cheekSquint: Float = 0.0f,           // 0..1 Duchenne cheek warmth & lift
    val showTeeth: Boolean = false,
    val showGrittedTeeth: Boolean = false,
    val showTear: Boolean = false,
    val headNodBiasDeg: Float = 0.0f,        // subtle expressive head pitch/tilt
    val browSlot: AtlasPartSlot = AtlasPartSlot.BROW_IDLE,
    val mouthSlot: AtlasPartSlot = AtlasPartSlot.LIP_IDLE
)

/**
 * Pre-defined algorithmic facial expression preset that can be dragged and dropped
 * onto any character layer/clip in the TimelinePanel.
 */
data class FacialExpressionPreset(
    val id: String,
    val name: String,
    val shortLabel: String,
    val category: String,
    val accentHex: String,
    val baseExpression: FacialExpression,
    val overshootStrength: Float,            // Elastic spring overshoot when entering expression
    val transitionFrames: Int,               // Natural organic transition duration in frames (at 24fps)
    val anticipationHeadDeg: Float,          // Subtle anticipation head nod stamped on keyframe
    val blinkOnTransition: Boolean,          // Natural involuntary blink when shifting into this emotion
    val saccadeIntensity: Float,             // Micro-saccade liveliness (0 = sleepy, 1.3 = alert/talk)
    val dynamicTalkCadence: Boolean = false, // Procedural syllable lip-sync cadence
    val dynamicLaughBounce: Boolean = false, // Rhythmic breath/laugh chest-jaw bounce
    val target: FacialExpressionTarget
)

/**
 * Per-frame evaluated organic facial state consumed by both [SpriteAtlasRigRenderer]
 * and [HumanRigDrawer] so facial animation feels fluid, staggered, and alive.
 */
data class OrganicFacialState(
    val presetId: String,
    val resolvedExpression: FacialExpression,
    val leftEyeOpenness: Float,
    val rightEyeOpenness: Float,
    val leftEyelidClose: Float,
    val rightEyelidClose: Float,
    val happyEyeArc: Float,
    val eyeLookX: Float,          // -1..1 organic micro-saccade + gaze bias
    val eyeLookY: Float,          // -1..1 organic vertical gaze drift
    val pupilScale: Float,
    val leftBrowLift: Float,      // -1..1.4
    val rightBrowLift: Float,
    val leftBrowTiltDeg: Float,
    val rightBrowTiltDeg: Float,
    val mouthOpen: Float,         // 0..1
    val mouthScaleX: Float,       // Volume-preserving horizontal stretch
    val mouthScaleY: Float,       // Volume-preserving vertical stretch
    val smileCurve: Float,        // -1..1
    val cheekSquint: Float,       // 0..1
    val showTeeth: Boolean,
    val showGrittedTeeth: Boolean,
    val showTear: Boolean,
    val headMicroNodDeg: Float,
    val isBlinkingLeft: Boolean,
    val isBlinkingRight: Boolean,
    val resolvedBrowSlot: AtlasPartSlot,
    val resolvedMouthSlot: AtlasPartSlot
)

/**
 * Zero-AI, 100% deterministic algorithmic facial animation engine.
 *
 * Implements classical 12-principles animation math so character faces feel genuinely alive:
 *  1. Anticipation -> Overshoot -> Damped Settle spring curves on transitions.
 *  2. Staggered overlapping channels: Eyebrows lead (+2f), Eyes/Lids follow with
 *     natural involuntary transition blinks, Mouth trails (-1.5f) with squash-and-stretch.
 *  3. Asymmetric blink envelope: rapid lid drop (~2f) + softer relaxed lid lift (~4-5f)
 *     on an irregular human cadence (~2.4s..4.1s) with occasional double-blinks.
 *  4. Stepped micro-saccades & living breath drift so held expressions never look frozen.
 */
object FacialExpressionEngine {

    const val PRESET_HAPPY = "happy_warm"
    const val PRESET_LAUGH = "joy_laugh"
    const val PRESET_SAD = "sad_teary"
    const val PRESET_ANGRY = "angry_fierce"
    const val PRESET_SHOCKED = "shock_gasp"
    const val PRESET_WINK = "wink_playful"
    const val PRESET_TALK = "talk_expressive"
    const val PRESET_SUSPICIOUS = "suspicious_squint"
    const val PRESET_SLEEPY = "sleepy_calm"
    const val PRESET_NEUTRAL = "neutral_alive"

    val presets: List<FacialExpressionPreset> = listOf(
        FacialExpressionPreset(
            id = PRESET_HAPPY,
            name = "Warm Happy",
            shortLabel = "Happy",
            category = "Joy",
            accentHex = "#FBBF24",
            baseExpression = FacialExpression.HAPPY,
            overshootStrength = 0.20f,
            transitionFrames = 9,
            anticipationHeadDeg = -2.4f,
            blinkOnTransition = true,
            saccadeIntensity = 0.95f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.94f,
                rightEyeOpenness = 0.94f,
                leftEyelidClose = 0.14f,
                rightEyelidClose = 0.14f,
                happyEyeArc = 0.35f,
                pupilScale = 1.12f,
                gazeBiasY = -0.06f,
                leftBrowLift = 0.42f,
                rightBrowLift = 0.48f,
                leftBrowTiltDeg = -4f,
                rightBrowTiltDeg = -3f,
                mouthOpen = 0.24f,
                smileCurve = 0.88f,
                mouthWidth = 1.14f,
                cheekSquint = 0.72f,
                showTeeth = true,
                headNodBiasDeg = -1.8f,
                browSlot = AtlasPartSlot.BROW_HAPPY,
                mouthSlot = AtlasPartSlot.LIP_HAPPY
            )
        ),
        FacialExpressionPreset(
            id = PRESET_LAUGH,
            name = "Joyful Laugh",
            shortLabel = "Laugh",
            category = "Joy",
            accentHex = "#F59E0B",
            baseExpression = FacialExpression.LAUGHING,
            overshootStrength = 0.28f,
            transitionFrames = 8,
            anticipationHeadDeg = -4.2f,
            blinkOnTransition = false,
            saccadeIntensity = 0.45f,
            dynamicLaughBounce = true,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.25f,
                rightEyeOpenness = 0.25f,
                leftEyelidClose = 0.85f,
                rightEyelidClose = 0.85f,
                happyEyeArc = 1.0f,
                pupilScale = 1.10f,
                leftBrowLift = 0.65f,
                rightBrowLift = 0.70f,
                leftBrowTiltDeg = -6f,
                rightBrowTiltDeg = -6f,
                mouthOpen = 0.78f,
                smileCurve = 1.0f,
                mouthWidth = 1.22f,
                cheekSquint = 1.0f,
                showTeeth = true,
                headNodBiasDeg = -3.8f,
                browSlot = AtlasPartSlot.BROW_HAPPY,
                mouthSlot = AtlasPartSlot.LIP_HAPPY
            )
        ),
        FacialExpressionPreset(
            id = PRESET_SAD,
            name = "Heartfelt Sad",
            shortLabel = "Sad",
            category = "Drama",
            accentHex = "#60A5FA",
            baseExpression = FacialExpression.SAD,
            overshootStrength = 0.08f,
            transitionFrames = 13,
            anticipationHeadDeg = 3.8f,
            blinkOnTransition = true,
            saccadeIntensity = 0.42f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.82f,
                rightEyeOpenness = 0.82f,
                leftEyelidClose = 0.34f,
                rightEyelidClose = 0.34f,
                pupilScale = 1.08f,
                gazeBiasY = 0.38f,
                leftBrowLift = 0.26f,
                rightBrowLift = 0.22f,
                leftBrowTiltDeg = -18f,
                rightBrowTiltDeg = -18f,
                mouthOpen = 0.06f,
                smileCurve = -0.82f,
                mouthWidth = 0.86f,
                cheekSquint = 0.12f,
                showTear = true,
                headNodBiasDeg = 4.2f,
                browSlot = AtlasPartSlot.BROW_SCARED,
                mouthSlot = AtlasPartSlot.LIP_SCARED
            )
        ),
        FacialExpressionPreset(
            id = PRESET_ANGRY,
            name = "Fierce Angry",
            shortLabel = "Angry",
            category = "Drama",
            accentHex = "#F87171",
            baseExpression = FacialExpression.ANGRY,
            overshootStrength = 0.30f,
            transitionFrames = 6,
            anticipationHeadDeg = 2.8f,
            blinkOnTransition = false,
            saccadeIntensity = 0.65f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.78f,
                rightEyeOpenness = 0.78f,
                leftEyelidClose = 0.38f,
                rightEyelidClose = 0.38f,
                pupilScale = 0.80f,
                gazeBiasY = -0.05f,
                leftBrowLift = -0.68f,
                rightBrowLift = -0.60f,
                leftBrowTiltDeg = 22f,
                rightBrowTiltDeg = 20f,
                mouthOpen = 0.26f,
                smileCurve = -0.68f,
                mouthWidth = 1.06f,
                cheekSquint = 0.25f,
                showGrittedTeeth = true,
                headNodBiasDeg = 3.2f,
                browSlot = AtlasPartSlot.BROW_ANGRY,
                mouthSlot = AtlasPartSlot.LIP_ANGRY
            )
        ),
        FacialExpressionPreset(
            id = PRESET_SHOCKED,
            name = "Gasp Shock",
            shortLabel = "Shock",
            category = "React",
            accentHex = "#C084FC",
            baseExpression = FacialExpression.SHOCKED,
            overshootStrength = 0.38f,
            transitionFrames = 5,
            anticipationHeadDeg = -4.5f,
            blinkOnTransition = false,
            saccadeIntensity = 0.30f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 1.32f,
                rightEyeOpenness = 1.32f,
                leftEyelidClose = 0.0f,
                rightEyelidClose = 0.0f,
                pupilScale = 0.70f,
                leftBrowLift = 1.05f,
                rightBrowLift = 1.08f,
                leftBrowTiltDeg = -8f,
                rightBrowTiltDeg = -8f,
                mouthOpen = 0.92f,
                smileCurve = -0.18f,
                mouthWidth = 0.74f,
                cheekSquint = 0.0f,
                headNodBiasDeg = -3.4f,
                browSlot = AtlasPartSlot.BROW_SCARED,
                mouthSlot = AtlasPartSlot.LIP_SHOCKED
            )
        ),
        FacialExpressionPreset(
            id = PRESET_WINK,
            name = "Playful Wink",
            shortLabel = "Wink",
            category = "Playful",
            accentHex = "#F472B6",
            baseExpression = FacialExpression.HAPPY,
            overshootStrength = 0.24f,
            transitionFrames = 7,
            anticipationHeadDeg = -2.2f,
            blinkOnTransition = false,
            saccadeIntensity = 0.85f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 1.05f,
                rightEyeOpenness = 0.10f,
                leftEyelidClose = 0.05f,
                rightEyelidClose = 0.96f,
                happyEyeArc = 0.60f,
                pupilScale = 1.12f,
                gazeBiasX = 0.16f,
                leftBrowLift = 0.52f,
                rightBrowLift = -0.10f,
                leftBrowTiltDeg = -5f,
                rightBrowTiltDeg = 6f,
                mouthOpen = 0.16f,
                smileCurve = 0.82f,
                mouthWidth = 1.08f,
                cheekSquint = 0.65f,
                showTeeth = false,
                headNodBiasDeg = -2.4f,
                browSlot = AtlasPartSlot.BROW_HAPPY,
                mouthSlot = AtlasPartSlot.LIP_HAPPY
            )
        ),
        FacialExpressionPreset(
            id = PRESET_TALK,
            name = "Expressive Talk",
            shortLabel = "Talk",
            category = "Speech",
            accentHex = "#38BDF8",
            baseExpression = FacialExpression.TALKING,
            overshootStrength = 0.18f,
            transitionFrames = 7,
            anticipationHeadDeg = -1.4f,
            blinkOnTransition = true,
            saccadeIntensity = 1.20f,
            dynamicTalkCadence = true,
            target = FacialExpressionTarget(
                leftEyeOpenness = 1.02f,
                rightEyeOpenness = 1.02f,
                leftEyelidClose = 0.06f,
                rightEyelidClose = 0.06f,
                pupilScale = 1.04f,
                leftBrowLift = 0.28f,
                rightBrowLift = 0.34f,
                leftBrowTiltDeg = -2f,
                rightBrowTiltDeg = -2f,
                mouthOpen = 0.52f,
                smileCurve = 0.28f,
                mouthWidth = 1.02f,
                cheekSquint = 0.30f,
                showTeeth = true,
                headNodBiasDeg = -0.8f,
                browSlot = AtlasPartSlot.BROW_IDLE,
                mouthSlot = AtlasPartSlot.LIP_HAPPY
            )
        ),
        FacialExpressionPreset(
            id = PRESET_SUSPICIOUS,
            name = "Suspicious Squint",
            shortLabel = "Suspect",
            category = "React",
            accentHex = "#34D399",
            baseExpression = FacialExpression.NEUTRAL,
            overshootStrength = 0.16f,
            transitionFrames = 9,
            anticipationHeadDeg = 1.8f,
            blinkOnTransition = true,
            saccadeIntensity = 0.55f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.62f,
                rightEyeOpenness = 0.72f,
                leftEyelidClose = 0.48f,
                rightEyelidClose = 0.36f,
                pupilScale = 0.90f,
                gazeBiasX = 0.48f,
                gazeBiasY = -0.04f,
                leftBrowLift = -0.45f,
                rightBrowLift = 0.58f,
                leftBrowTiltDeg = 14f,
                rightBrowTiltDeg = -10f,
                mouthOpen = 0.02f,
                smileCurve = -0.22f,
                mouthWidth = 0.82f,
                cheekSquint = 0.10f,
                headNodBiasDeg = 2.0f,
                browSlot = AtlasPartSlot.BROW_ANGRY,
                mouthSlot = AtlasPartSlot.LIP_IDLE
            )
        ),
        FacialExpressionPreset(
            id = PRESET_SLEEPY,
            name = "Sleepy Calm",
            shortLabel = "Sleepy",
            category = "Calm",
            accentHex = "#818CF8",
            baseExpression = FacialExpression.SLEEPING,
            overshootStrength = 0.05f,
            transitionFrames = 15,
            anticipationHeadDeg = 4.6f,
            blinkOnTransition = true,
            saccadeIntensity = 0.12f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 0.22f,
                rightEyeOpenness = 0.22f,
                leftEyelidClose = 0.92f,
                rightEyelidClose = 0.92f,
                pupilScale = 0.96f,
                gazeBiasY = 0.25f,
                leftBrowLift = -0.18f,
                rightBrowLift = -0.18f,
                leftBrowTiltDeg = -4f,
                rightBrowTiltDeg = -4f,
                mouthOpen = 0.12f,
                smileCurve = 0.10f,
                mouthWidth = 0.84f,
                cheekSquint = 0.08f,
                headNodBiasDeg = 4.8f,
                browSlot = AtlasPartSlot.BROW_IDLE,
                mouthSlot = AtlasPartSlot.LIP_IDLE
            )
        ),
        FacialExpressionPreset(
            id = PRESET_NEUTRAL,
            name = "Natural Idle",
            shortLabel = "Neutral",
            category = "Calm",
            accentHex = "#94A3B8",
            baseExpression = FacialExpression.NEUTRAL,
            overshootStrength = 0.10f,
            transitionFrames = 10,
            anticipationHeadDeg = 0f,
            blinkOnTransition = true,
            saccadeIntensity = 0.85f,
            target = FacialExpressionTarget(
                leftEyeOpenness = 1.0f,
                rightEyeOpenness = 1.0f,
                leftEyelidClose = 0.06f,
                rightEyelidClose = 0.06f,
                pupilScale = 1.0f,
                leftBrowLift = 0.0f,
                rightBrowLift = 0.04f,
                mouthOpen = 0.0f,
                smileCurve = 0.18f,
                mouthWidth = 0.98f,
                cheekSquint = 0.15f,
                headNodBiasDeg = 0f,
                browSlot = AtlasPartSlot.BROW_IDLE,
                mouthSlot = AtlasPartSlot.LIP_IDLE
            )
        )
    )

    private val presetsById: Map<String, FacialExpressionPreset> = presets.associateBy { it.id }

    fun presetById(id: String?): FacialExpressionPreset? = id?.let { presetsById[it] }

    fun presetForExpression(expression: FacialExpression): FacialExpressionPreset = when (expression) {
        FacialExpression.HAPPY -> presetsById.getValue(PRESET_HAPPY)
        FacialExpression.LAUGHING -> presetsById.getValue(PRESET_LAUGH)
        FacialExpression.SAD -> presetsById.getValue(PRESET_SAD)
        FacialExpression.ANGRY -> presetsById.getValue(PRESET_ANGRY)
        FacialExpression.SHOCKED -> presetsById.getValue(PRESET_SHOCKED)
        FacialExpression.TALKING -> presetsById.getValue(PRESET_TALK)
        FacialExpression.SLEEPING -> presetsById.getValue(PRESET_SLEEPY)
        FacialExpression.NEUTRAL, FacialExpression.AUTO -> presetsById.getValue(PRESET_NEUTRAL)
    }

    /**
     * Evaluates a smooth, organic, non-robotic facial state at [globalFrame].
     *
     * Handles:
     *  - Multi-channel staggered transitions (brows lead, eyes follow, mouth trails)
     *  - Anticipation + spring overshoot + damped settle when entering a new expression
     *  - Involuntary transition blink between contrasting expressions
     *  - Irregular organic blink envelope (fast close, slow relaxed open)
     *  - Stepped micro-saccades + subtle breathing micro-motion during held expressions
     */
    fun evaluateOrganicFace(
        activePresetId: String?,
        previousPresetId: String?,
        fallbackExpression: FacialExpression,
        framesSinceKey: Int,
        globalFrame: Int,
        fps: Int,
        baseMouthOpen: Float = 0f,
        blinkOverride: Boolean? = null,
        characterSeed: Int = 17
    ): OrganicFacialState {
        val safeFps = fps.coerceAtLeast(12).toFloat()
        val targetPreset = presetById(activePresetId) ?: presetForExpression(fallbackExpression)
        val fromPreset = presetById(previousPresetId) ?: targetPreset

        val isTransitioning = fromPreset.id != targetPreset.id
        val transFrames = (targetPreset.transitionFrames * (safeFps / 24f)).coerceAtLeast(4f)
        val elapsed = framesSinceKey.coerceAtLeast(0).toFloat()

        // Staggered channel timing:
        // Eyebrows lead by +1.8 frames, eyes follow at base time, mouth trails by -1.4 frames
        val browRaw = if (!isTransitioning) 1f else ((elapsed + 1.8f) / transFrames).coerceIn(0f, 1f)
        val eyeRaw = if (!isTransitioning) 1f else (elapsed / transFrames).coerceIn(0f, 1f)
        val mouthRaw = if (!isTransitioning) 1f else ((elapsed - 1.4f) / transFrames).coerceIn(0f, 1f)

        val overshoot = targetPreset.overshootStrength
        val browT = if (!isTransitioning) 1f else organicOvershootCurve(browRaw, overshoot * 1.15f)
        val eyeT = if (!isTransitioning) 1f else organicOvershootCurve(eyeRaw, overshoot * 0.55f)
        val mouthT = if (!isTransitioning) 1f else organicOvershootCurve(mouthRaw, overshoot)

        val from = fromPreset.target
        val to = targetPreset.target

        val timeSec = globalFrame.toFloat() / safeFps
        val seedPhase = (abs(characterSeed) % 97) * 0.13f

        // 1. Natural breathing micro-oscillation (golden-ratio dual sine so it never looks mechanical)
        val breathWave = (sin((timeSec * 1.65 + seedPhase).toDouble()) * 0.65 +
            sin((timeSec * 0.73 + seedPhase * 1.9).toDouble()) * 0.35).toFloat()

        // 2. Stepped organic micro-saccades (hold ~0.95s -> swift 3-frame gaze dart -> settle)
        val saccade = computeMicroSaccade(timeSec, seedPhase, targetPreset.saccadeIntensity)

        // 3. Asymmetric natural blink envelope (fast drop ~2f, softer open ~4-5f) + transition blink
        val autoBlinkAmount = computeNaturalBlinkEnvelope(timeSec, seedPhase, targetPreset.id)
        val transitionBlinkAmount = if (isTransitioning && targetPreset.blinkOnTransition && elapsed in 1f..5.5f) {
            val u = ((elapsed - 1f) / 4.5f).coerceIn(0f, 1f)
            // Fast snap down (0..0.32), smooth release (0.32..1.0)
            if (u < 0.32f) (u / 0.32f) else (1f - ((u - 0.32f) / 0.68f))
        } else {
            0f
        }
        val combinedBlink = when (blinkOverride) {
            true -> 1f
            false -> 0f
            null -> maxOf(autoBlinkAmount, transitionBlinkAmount)
        }

        // 4. Blend eye channels with blink and subtle asymmetry
        var leftLid = lerp(from.leftEyelidClose, to.leftEyelidClose, eyeT).coerceIn(0f, 1f)
        var rightLid = lerp(from.rightEyelidClose, to.rightEyelidClose, eyeT).coerceIn(0f, 1f)
        // Only apply auto-blink over non-winking open eyes (preserve intentional wink asymmetry!)
        val blinkMix = if (targetPreset.id == PRESET_WINK && blinkOverride == null) combinedBlink * 0.35f else combinedBlink
        leftLid = maxOf(leftLid, blinkMix).coerceIn(0f, 1f)
        rightLid = maxOf(rightLid, blinkMix).coerceIn(0f, 1f)

        val leftEyeOpen = (lerp(from.leftEyeOpenness, to.leftEyeOpenness, eyeT) * (1f - leftLid * 0.88f)).coerceIn(0.05f, 1.45f)
        val rightEyeOpen = (lerp(from.rightEyeOpenness, to.rightEyeOpenness, eyeT) * (1f - rightLid * 0.88f)).coerceIn(0.05f, 1.45f)
        val happyEyeArc = lerp(from.happyEyeArc, to.happyEyeArc, eyeT).coerceIn(0f, 1f)
        val pupilScale = (lerp(from.pupilScale, to.pupilScale, eyeT) + breathWave * 0.025f).coerceIn(0.65f, 1.28f)

        val lookX = (lerp(from.gazeBiasX, to.gazeBiasX, eyeT) + saccade.first).coerceIn(-1f, 1f)
        val lookY = (lerp(from.gazeBiasY, to.gazeBiasY, eyeT) + saccade.second + breathWave * 0.03f).coerceIn(-1f, 1f)

        // 5. Eyebrow channels (lead expression + subtle micro-accent during talk/laugh)
        val talkSyllablePulse = if (targetPreset.dynamicTalkCadence) {
            computeTalkSyllableEnergy(timeSec, seedPhase)
        } else 0f
        val laughBounce = if (targetPreset.dynamicLaughBounce) {
            (0.5f + 0.5f * sin((timeSec * 16.5 + seedPhase).toDouble()).toFloat())
        } else 0f

        val browAccent = talkSyllablePulse * 0.22f + laughBounce * 0.16f + breathWave * 0.045f
        val leftBrowLift = (lerp(from.leftBrowLift, to.leftBrowLift, browT) + browAccent).coerceIn(-1.1f, 1.35f)
        val rightBrowLift = (lerp(from.rightBrowLift, to.rightBrowLift, browT) + browAccent * 1.08f).coerceIn(-1.1f, 1.35f)
        val leftBrowTilt = lerp(from.leftBrowTiltDeg, to.leftBrowTiltDeg, browT)
        val rightBrowTilt = lerp(from.rightBrowTiltDeg, to.rightBrowTiltDeg, browT)

        // 6. Mouth channels (trails brows + dynamic speech/laugh modulation + volume-preserving squash & stretch)
        val targetMouthBase = lerp(from.mouthOpen, to.mouthOpen, mouthT).coerceIn(0f, 1f)
        val dynamicMouth = when {
            targetPreset.dynamicTalkCadence -> {
                // Multi-harmonic speech cadence that produces natural pauses & vowel peaks
                (0.08f + talkSyllablePulse * 0.84f).coerceIn(0f, 1f)
            }
            targetPreset.dynamicLaughBounce -> {
                (targetMouthBase * (0.68f + 0.36f * laughBounce)).coerceIn(0f, 1f)
            }
            else -> maxOf(targetMouthBase, baseMouthOpen)
        }
        val smileCurve = (lerp(from.smileCurve, to.smileCurve, mouthT) + breathWave * 0.03f).coerceIn(-1f, 1f)
        val cheekSquint = (lerp(from.cheekSquint, to.cheekSquint, eyeT) + laughBounce * 0.12f).coerceIn(0f, 1f)

        // Volume-preserving squash-and-stretch on mouth: opening tall slightly narrows width; smiling widens it
        val baseMouthWidth = lerp(from.mouthWidth, to.mouthWidth, mouthT)
        val stretchY = (0.86f + dynamicMouth * 0.54f).coerceIn(0.75f, 1.48f)
        val volumeCompX = (1f / sqrt(stretchY.coerceAtLeast(0.5f))).coerceIn(0.80f, 1.18f)
        val mouthScaleX = (baseMouthWidth * volumeCompX).coerceIn(0.68f, 1.35f)
        val mouthScaleY = stretchY

        // 7. Subtle head micro-nod coupled to emotion + breathing + laugh/speech cadence
        val transitionAnticipation = if (isTransitioning && elapsed < transFrames) {
            val u = (elapsed / transFrames).coerceIn(0f, 1f)
            sin(u * PI).toFloat() * targetPreset.anticipationHeadDeg * 0.45f
        } else 0f
        val headMicroNodDeg = lerp(from.headNodBiasDeg, to.headNodBiasDeg, eyeT) +
            transitionAnticipation +
            breathWave * 0.9f +
            talkSyllablePulse * 1.6f -
            laughBounce * 2.4f

        // 8. Automatic AtlasPartSlot selection (mouth & brows)
        val resolvedBrowSlot = if (browRaw >= 0.35f) to.browSlot else from.browSlot
        val resolvedMouthSlot = when {
            targetPreset.dynamicTalkCadence -> when {
                dynamicMouth > 0.74f -> AtlasPartSlot.LIP_SHOCKED
                dynamicMouth > 0.44f -> AtlasPartSlot.LIP_HAPPY
                dynamicMouth > 0.22f -> AtlasPartSlot.LIP_SCARED
                else -> AtlasPartSlot.LIP_IDLE
            }
            mouthRaw >= 0.42f -> to.mouthSlot
            else -> from.mouthSlot
        }

        val resolvedExpression = if (eyeRaw >= 0.45f) targetPreset.baseExpression else fromPreset.baseExpression

        return OrganicFacialState(
            presetId = targetPreset.id,
            resolvedExpression = resolvedExpression,
            leftEyeOpenness = leftEyeOpen,
            rightEyeOpenness = rightEyeOpen,
            leftEyelidClose = leftLid,
            rightEyelidClose = rightLid,
            happyEyeArc = happyEyeArc,
            eyeLookX = lookX,
            eyeLookY = lookY,
            pupilScale = pupilScale,
            leftBrowLift = leftBrowLift,
            rightBrowLift = rightBrowLift,
            leftBrowTiltDeg = leftBrowTilt,
            rightBrowTiltDeg = rightBrowTilt,
            mouthOpen = dynamicMouth,
            mouthScaleX = mouthScaleX,
            mouthScaleY = mouthScaleY,
            smileCurve = smileCurve,
            cheekSquint = cheekSquint,
            showTeeth = if (mouthRaw >= 0.45f) to.showTeeth else from.showTeeth,
            showGrittedTeeth = if (mouthRaw >= 0.45f) to.showGrittedTeeth else from.showGrittedTeeth,
            showTear = if (eyeRaw >= 0.55f) to.showTear else from.showTear,
            headMicroNodDeg = headMicroNodDeg,
            isBlinkingLeft = leftLid >= 0.78f,
            isBlinkingRight = rightLid >= 0.78f,
            resolvedBrowSlot = resolvedBrowSlot,
            resolvedMouthSlot = resolvedMouthSlot
        )
    }

    /**
     * Anticipation -> elastic overshoot -> damped spring settle curve.
     * For t in 0..1:
     *  - 0.00..0.16: subtle anticipation counter-dip (-0.18 * overshoot)
     *  - 0.16..0.68: swift expressive acceleration up to peak (1.0 + 0.72 * overshoot)
     *  - 0.68..1.00: smooth damped settle into exact 1.0
     */
    internal fun organicOvershootCurve(t: Float, overshoot: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val safeOver = overshoot.coerceIn(0f, 0.55f)
        val dip = -safeOver * 0.18f
        val peak = 1f + safeOver * 0.72f
        return when {
            x < 0.16f -> {
                val u = x / 0.16f
                // Smooth anticipation dip down to `dip`
                dip * sin(u * PI * 0.5).toFloat()
            }
            x < 0.68f -> {
                val u = (x - 0.16f) / 0.52f
                // Expressive S-curve launching from `dip` up to `peak`
                val ease = (0.5f - 0.5f * cos(u * PI)).toFloat()
                lerp(dip, peak, ease)
            }
            else -> {
                val u = (x - 0.68f) / 0.32f
                // Smooth damped settle from `peak` cleanly back to 1.0
                val ease = (0.5f - 0.5f * cos(u * PI)).toFloat()
                lerp(peak, 1f, ease)
            }
        }
    }

    /**
     * Irregular organic human blink cadence (~2.6s to 3.9s intervals with an occasional
     * natural double-blink). Fast lid close (~0.08s), smooth slower lid open (~0.18s).
     */
    private fun computeNaturalBlinkEnvelope(timeSec: Float, seedPhase: Float, presetId: String): Float {
        if (presetId == PRESET_SLEEPY || presetId == PRESET_LAUGH) return 0f
        // Period varies pseudo-randomly per blink cycle between 2.6s and 4.1s
        val cycleLen = 3.25f
        val shifted = timeSec + seedPhase * 2.1f
        val cycleIndex = (shifted / cycleLen).toInt()
        val cycleFrac = ((shifted % cycleLen) + cycleLen) % cycleLen
        // Jitter blink onset deterministically per cycle
        val onset = 1.65f + (sin((cycleIndex * 2.37 + seedPhase).toDouble()).toFloat() * 0.65f)
        val blinkDur = 0.26f
        val primary = singleAsymmetricBlink(cycleFrac - onset, blinkDur)
        // Occasional natural double-blink every 4th cycle
        val secondary = if (cycleIndex % 4 == 2) {
            singleAsymmetricBlink(cycleFrac - (onset + 0.34f), 0.22f) * 0.92f
        } else 0f
        return maxOf(primary, secondary)
    }

    private fun singleAsymmetricBlink(localT: Float, duration: Float): Float {
        if (localT < 0f || localT > duration) return 0f
        val u = (localT / duration).coerceIn(0f, 1f)
        // Fast close (0..0.30), gentle relaxed open (0.30..1.0)
        return if (u < 0.30f) {
            val c = u / 0.30f
            c * c
        } else {
            val o = (u - 0.30f) / 0.70f
            val easeOut = 1f - (1f - o) * (1f - o)
            (1f - easeOut).coerceIn(0f, 1f)
        }
    }

    /**
     * Stepped smooth-damped eye micro-saccades: eyes fixate briefly (~0.85s),
     * then make a crisp subtle gaze shift rather than swimming sinusoidally.
     */
    private fun computeMicroSaccade(timeSec: Float, seedPhase: Float, intensity: Float): Pair<Float, Float> {
        if (intensity <= 0.01f) return 0f to 0f
        val stepSec = 1.05f
        val t = (timeSec + seedPhase) / stepSec
        val idx = t.toInt()
        val frac = t - idx
        // Saccade dart happens quickly in first 16% of fixation window, then holds steady
        val blend = (frac / 0.16f).coerceIn(0f, 1f)
        val smoothStep = blend * blend * (3f - 2f * blend)

        val prevX = pseudoGazeX(idx, seedPhase)
        val nextX = pseudoGazeX(idx + 1, seedPhase)
        val prevY = pseudoGazeY(idx, seedPhase)
        val nextY = pseudoGazeY(idx + 1, seedPhase)

        val sx = lerp(prevX, nextX, smoothStep) * 0.28f * intensity
        val sy = lerp(prevY, nextY, smoothStep) * 0.16f * intensity
        return sx to sy
    }

    private fun pseudoGazeX(step: Int, seed: Float): Float =
        sin((step * 3.17 + seed * 4.3).toDouble()).toFloat()

    private fun pseudoGazeY(step: Int, seed: Float): Float =
        cos((step * 2.53 + seed * 2.9).toDouble()).toFloat() * 0.75f

    /**
     * Expressive procedural speech syllable energy with natural micro-pauses between words.
     */
    private fun computeTalkSyllableEnergy(timeSec: Float, seedPhase: Float): Float {
        val phraseGate = sin((timeSec * 2.1 + seedPhase).toDouble()).toFloat()
        if (phraseGate < -0.58f) return 0.05f // Brief natural breath pause between words
        val syl1 = abs(sin((timeSec * 12.8 + seedPhase).toDouble())).toFloat()
        val syl2 = abs(sin((timeSec * 7.4 + seedPhase * 1.7).toDouble())).toFloat()
        return (syl1 * 0.65f + syl2 * 0.35f).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
