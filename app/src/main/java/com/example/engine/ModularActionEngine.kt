package com.example.engine

import com.example.model.*
import com.example.viewmodel.ProjectMutator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Independent modular body tracks that can be previewed and inserted cleanly
 * without disturbing the character's other body tracks.
 */
enum class ModularActionTrack(val displayName: String, val timelineTrackLabel: String, val accentHex: String) {
    FACIAL("Facial", "Expression", "#F59E0B"),
    HANDS("Hands", "Hands", "#38BDF8"),
    LEGS("Legs", "Legs", "#34D399")
}

/**
 * Declarative modular action card selectable inside the IK Studio's Actions tab.
 */
data class ModularActionDef(
    val id: String,
    val name: String,
    val track: ModularActionTrack,
    val durationFrames: Int,
    val subtitle: String,
    val accentHex: String = track.accentHex
)

/**
 * Track-isolated evaluated sample of a [ModularActionDef] at normalized progress `0f..1f`.
 * Only populates fields belonging to its own [ModularActionTrack] so unrelated body tracks stay untouched!
 */
data class ModularActionSample(
    val track: ModularActionTrack,
    // FACIAL track channels
    val expressionOverride: FacialExpression? = null,
    val facialPresetId: String? = null,
    val mouthShapeOverride: MouthShapeOverride? = null,
    val eyeShapeOverride: EyeShapeOverride? = null,
    val eyebrowShapeOverride: EyebrowShapeOverride? = null,
    val blinkOverride: Boolean? = null,
    val headNodDelta: Float = 0f,
    // HANDS track channels
    val handPoseOverride: HandPoseType? = null,
    val leftArmDelta: Float = 0f,
    val leftForearmDelta: Float = 0f,
    val rightArmDelta: Float = 0f,
    val rightForearmDelta: Float = 0f,
    // LEGS track channels
    val torsoTiltDelta: Float = 0f,
    val leftLegDelta: Float = 0f,
    val leftKneeDelta: Float = 0f,
    val rightLegDelta: Float = 0f,
    val rightKneeDelta: Float = 0f
)

/**
 * Non-destructive blended preview state returned by [ModularActionEngine.blendPreviewOntoBase].
 */
data class BlendedPreviewPose(
    val poseOffsets: PoseKeyframeOffsets,
    val expression: FacialExpression,
    val facialPresetId: String?,
    val mouthOverride: MouthShapeOverride,
    val eyeOverride: EyeShapeOverride,
    val eyebrowOverride: EyebrowShapeOverride,
    val handPose: HandPoseType,
    val blinkOverride: Boolean?,
    val gazeTarget: GazeTarget,
    val headTilt: Float
)

/**
 * Offline Modular Action Catalog, Non-Destructive Preview Blender, and Track-Isolated Timeline Inserter.
 *
 * Powers Modular Action Preview Mode:
 * - Animators tap any `Facial`, `Hands`, or `Legs` action card to scrub `0%..100%` or loop-play
 *   live on the character WITHOUT modifying the timeline or disturbing other body tracks.
 * - Tapping **Insert at Playhead** stamps track-isolated keyframes onto the active clip cleanly.
 * - Tapping **Cancel** or switching tabs discards the preview instantly with zero leftover state.
 */
object ModularActionEngine {

    val catalog: List<ModularActionDef> = listOf(
        // ─── FACIAL (Expression Track) ───
        ModularActionDef("act_face_smile", "Warm Smile", ModularActionTrack.FACIAL, 24, "Happy smile + gentle nod"),
        ModularActionDef("act_face_gasp", "Shock Gasp", ModularActionTrack.FACIAL, 20, "Wide eyes + shock O mouth"),
        ModularActionDef("act_face_angry", "Fierce Glare", ModularActionTrack.FACIAL, 24, "Furrowed brows + gritted teeth"),
        ModularActionDef("act_face_laugh", "Hearty Laugh", ModularActionTrack.FACIAL, 28, "Bouncing laugh rhythm"),
        ModularActionDef("act_face_wink", "Playful Wink", ModularActionTrack.FACIAL, 18, "Asymmetric right wink + smirk"),
        ModularActionDef("act_face_talk", "Talk Cadence", ModularActionTrack.FACIAL, 30, "Natural AA / EE syllable cycle"),
        ModularActionDef("act_face_sad", "Sad Sigh", ModularActionTrack.FACIAL, 26, "Drooping gaze + soft head drop"),
        ModularActionDef("act_face_blink", "Double Blink", ModularActionTrack.FACIAL, 18, "Crisp double eye blink"),

        // ─── HANDS (Hands Track) ───
        ModularActionDef("act_hand_wave", "Friendly Wave", ModularActionTrack.HANDS, 28, "High right-hand wave arc"),
        ModularActionDef("act_hand_point", "Point Forward", ModularActionTrack.HANDS, 20, "Confident extended point"),
        ModularActionDef("act_hand_peace", "Victory Peace", ModularActionTrack.HANDS, 22, "Raised peace sign pose"),
        ModularActionDef("act_hand_thumbs", "Thumbs Up", ModularActionTrack.HANDS, 22, "Heroic thumbs-up lift"),
        ModularActionDef("act_hand_fist", "Fist Pump", ModularActionTrack.HANDS, 24, "Energetic double fist pump"),
        ModularActionDef("act_hand_present", "Open Present", ModularActionTrack.HANDS, 26, "Wide welcoming arms sweep"),
        ModularActionDef("act_hand_think", "Chin Think", ModularActionTrack.HANDS, 28, "Right hand raised to chin"),
        ModularActionDef("act_hand_clap", "Clap Beat", ModularActionTrack.HANDS, 24, "Rhythmic two-hand clap"),

        // ─── LEGS (Legs Track) ───
        ModularActionDef("act_leg_walk", "Walk Stride", ModularActionTrack.LEGS, 28, "Smooth alternating stride"),
        ModularActionDef("act_leg_sprint", "Sprint Cycle", ModularActionTrack.LEGS, 20, "Fast high-knee run swing"),
        ModularActionDef("act_leg_jump", "Hero Jump", ModularActionTrack.LEGS, 26, "Crouch -> launch -> tuck -> land"),
        ModularActionDef("act_leg_sit", "Sit Relaxed", ModularActionTrack.LEGS, 24, "Bent thighs + grounded calves"),
        ModularActionDef("act_leg_kick", "Kick Strike", ModularActionTrack.LEGS, 22, "Chamber -> snap kick -> recover"),
        ModularActionDef("act_leg_sneak", "Tip-Toe Sneak", ModularActionTrack.LEGS, 30, "Cautious bent-knee step"),
        ModularActionDef("act_leg_squat", "Squat Bounce", ModularActionTrack.LEGS, 24, "Deep knee bend & spring"),
        ModularActionDef("act_leg_stance", "Wide Stance", ModularActionTrack.LEGS, 20, "Braced hero power stance")
    )

    fun actionsForTrack(track: ModularActionTrack): List<ModularActionDef> =
        catalog.filter { it.track == track }

    fun findById(id: String?): ModularActionDef? =
        if (id.isNullOrBlank()) null else catalog.firstOrNull { it.id == id }

    /**
     * Evaluates a modular action deterministically at normalized progress [progress] in `0f..1f`.
     */
    fun evaluateAtProgress(action: ModularActionDef, progress: Float): ModularActionSample {
        val p = progress.coerceIn(0f, 1f)
        val env = sin(p * PI).toFloat().coerceIn(0f, 1f) // smooth 0 -> 1 -> 0 envelope
        val wave = sin(p * 2.0 * PI).toFloat()
        val wave2 = sin(p * 4.0 * PI).toFloat()

        return when (action.id) {
            // ─── FACIAL ───
            "act_face_smile" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_HAPPY,
                mouthShapeOverride = MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -6f * env
            )
            "act_face_gasp" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SHOCKED,
                facialPresetId = FacialExpressionEngine.PRESET_SHOCKED,
                mouthShapeOverride = if (p > 0.15f) MouthShapeOverride.SHOCK_O else MouthShapeOverride.NEUTRAL,
                eyeShapeOverride = EyeShapeOverride.WIDE_SHOCK,
                eyebrowShapeOverride = EyebrowShapeOverride.RAISED_SURPRISE,
                headNodDelta = -10f * env
            )
            "act_face_angry" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.ANGRY,
                facialPresetId = FacialExpressionEngine.PRESET_ANGRY,
                mouthShapeOverride = MouthShapeOverride.GRIT_ANGRY,
                eyeShapeOverride = EyeShapeOverride.SQUINT_ANGRY,
                eyebrowShapeOverride = EyebrowShapeOverride.FURROWED_ANGRY,
                headNodDelta = 7f * env
            )
            "act_face_laugh" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.LAUGHING,
                facialPresetId = FacialExpressionEngine.PRESET_LAUGH,
                mouthShapeOverride = if (abs(wave2) > 0.35f) MouthShapeOverride.OPEN_LAUGH else MouthShapeOverride.SMILE,
                eyeShapeOverride = EyeShapeOverride.HAPPY_ARC,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = -8f * abs(wave2)
            )
            "act_face_wink" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.HAPPY,
                facialPresetId = FacialExpressionEngine.PRESET_WINK,
                mouthShapeOverride = MouthShapeOverride.SMIRK,
                eyeShapeOverride = if (p in 0.15f..0.85f) EyeShapeOverride.WINK_RIGHT else EyeShapeOverride.NORMAL,
                eyebrowShapeOverride = EyebrowShapeOverride.ARCHED_HAPPY,
                headNodDelta = 5f * env
            )
            "act_face_talk" -> {
                val mouth = when {
                    p < 0.18f || p > 0.88f -> MouthShapeOverride.NEUTRAL
                    abs(wave2) > 0.65f -> MouthShapeOverride.TALK_AA
                    abs(wave2) > 0.25f -> MouthShapeOverride.TALK_EE
                    else -> MouthShapeOverride.SMILE
                }
                ModularActionSample(
                    track = ModularActionTrack.FACIAL,
                    expressionOverride = FacialExpression.TALKING,
                    mouthShapeOverride = mouth,
                    headNodDelta = 4f * wave
                )
            }
            "act_face_sad" -> ModularActionSample(
                track = ModularActionTrack.FACIAL,
                expressionOverride = FacialExpression.SAD,
                facialPresetId = FacialExpressionEngine.PRESET_SAD,
                mouthShapeOverride = MouthShapeOverride.FROWN,
                eyeShapeOverride = EyeShapeOverride.SAD_DROOP,
                eyebrowShapeOverride = EyebrowShapeOverride.WORRIED_SAD,
                headNodDelta = 14f * env
            )
            "act_face_blink" -> {
                val closed = p in 0.18f..0.34f || p in 0.56f..0.72f
                ModularActionSample(
                    track = ModularActionTrack.FACIAL,
                    blinkOverride = closed,
                    eyeShapeOverride = if (closed) EyeShapeOverride.CLOSED_SLEEP else EyeShapeOverride.NORMAL,
                    headNodDelta = if (closed) 2.5f else 0f
                )
            }

            // ─── HANDS ───
            "act_hand_wave" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                rightArmDelta = -115f * env,
                rightForearmDelta = (-32f + 26f * wave2) * env
            )
            "act_hand_point" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                rightArmDelta = -82f * env,
                rightForearmDelta = -12f * env
            )
            "act_hand_peace" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                rightArmDelta = -102f * env,
                rightForearmDelta = -48f * env,
                leftArmDelta = 12f * env
            )
            "act_hand_thumbs" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.PROP_GRIP,
                rightArmDelta = -76f * env,
                rightForearmDelta = -54f * env
            )
            "act_hand_fist" -> {
                val pump = 0.5f + 0.5f * sin(p * 4.0 * PI).toFloat()
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = HandPoseType.FIST,
                    leftArmDelta = (58f + 28f * pump) * env,
                    leftForearmDelta = (68f + 16f * pump) * env,
                    rightArmDelta = (-58f - 28f * pump) * env,
                    rightForearmDelta = (-68f - 16f * pump) * env
                )
            }
            "act_hand_present" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.OPEN,
                leftArmDelta = 72f * env,
                leftForearmDelta = 24f * env,
                rightArmDelta = -72f * env,
                rightForearmDelta = -24f * env
            )
            "act_hand_think" -> ModularActionSample(
                track = ModularActionTrack.HANDS,
                handPoseOverride = HandPoseType.POINT,
                rightArmDelta = -56f * env,
                rightForearmDelta = -112f * env,
                leftArmDelta = 28f * env,
                leftForearmDelta = 70f * env
            )
            "act_hand_clap" -> {
                val clap = abs(sin(p * 3.0 * PI).toFloat())
                ModularActionSample(
                    track = ModularActionTrack.HANDS,
                    handPoseOverride = HandPoseType.OPEN,
                    leftArmDelta = (32f + 26f * clap) * env,
                    leftForearmDelta = (54f + 36f * clap) * env,
                    rightArmDelta = (-32f - 26f * clap) * env,
                    rightForearmDelta = (-54f - 36f * clap) * env
                )
            }

            // ─── LEGS ───
            "act_leg_walk" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = 28f * wave,
                leftKneeDelta = (26f * (-wave).coerceAtLeast(0f)),
                rightLegDelta = -28f * wave,
                rightKneeDelta = (26f * wave.coerceAtLeast(0f)),
                torsoTiltDelta = 3f * abs(wave)
            )
            "act_leg_sprint" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = 52f * wave,
                leftKneeDelta = 58f * (-wave).coerceAtLeast(0f),
                rightLegDelta = -52f * wave,
                rightKneeDelta = 58f * wave.coerceAtLeast(0f),
                torsoTiltDelta = 12f * env
            )
            "act_leg_jump" -> {
                val crouch = if (p < 0.25f) sin((p / 0.25f) * PI).toFloat() else if (p > 0.78f) sin(((p - 0.78f) / 0.22f) * PI).toFloat() else 0f
                val air = if (p in 0.25f..0.78f) sin(((p - 0.25f) / 0.53f) * PI).toFloat() else 0f
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    leftLegDelta = -32f * crouch - 24f * air,
                    leftKneeDelta = 62f * crouch + 52f * air,
                    rightLegDelta = -28f * crouch - 18f * air,
                    rightKneeDelta = 58f * crouch + 44f * air,
                    torsoTiltDelta = 10f * crouch - 6f * air
                )
            }
            "act_leg_sit" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -68f * env,
                leftKneeDelta = 78f * env,
                rightLegDelta = -64f * env,
                rightKneeDelta = 74f * env,
                torsoTiltDelta = -5f * env
            )
            "act_leg_kick" -> {
                val snap = sin(p * PI).toFloat().coerceIn(0f, 1f)
                ModularActionSample(
                    track = ModularActionTrack.LEGS,
                    rightLegDelta = -88f * snap,
                    rightKneeDelta = 18f * (1f - snap) * env,
                    leftLegDelta = 14f * snap,
                    leftKneeDelta = 10f * snap,
                    torsoTiltDelta = -14f * snap
                )
            }
            "act_leg_sneak" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -20f * env + 22f * wave,
                leftKneeDelta = 46f * env + 18f * (-wave).coerceAtLeast(0f),
                rightLegDelta = -20f * env - 22f * wave,
                rightKneeDelta = 46f * env + 18f * wave.coerceAtLeast(0f),
                torsoTiltDelta = 14f * env
            )
            "act_leg_squat" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = -48f * env,
                leftKneeDelta = 82f * env,
                rightLegDelta = -48f * env,
                rightKneeDelta = 82f * env,
                torsoTiltDelta = 12f * env
            )
            "act_leg_stance" -> ModularActionSample(
                track = ModularActionTrack.LEGS,
                leftLegDelta = 26f * env,
                leftKneeDelta = 14f * env,
                rightLegDelta = -26f * env,
                rightKneeDelta = 14f * env,
                torsoTiltDelta = 4f * env
            )
            else -> ModularActionSample(track = action.track)
        }
    }

    /**
     * Non-destructively blends a previewed [ModularActionDef] at [progress] onto the character's
     * existing base pose, touching ONLY the previewed action's own track!
     */
    fun blendPreviewOntoBase(
        basePose: PoseKeyframeOffsets,
        baseExpression: FacialExpression,
        baseMouthOverride: MouthShapeOverride,
        baseEyeOverride: EyeShapeOverride,
        baseEyebrowOverride: EyebrowShapeOverride,
        baseHandPose: HandPoseType,
        baseGaze: GazeTarget,
        baseHeadTilt: Float,
        action: ModularActionDef,
        progress: Float
    ): BlendedPreviewPose {
        val sample = evaluateAtProgress(action, progress)
        return when (action.track) {
            ModularActionTrack.FACIAL -> BlendedPreviewPose(
                poseOffsets = basePose.copy(
                    headNodOffset = (basePose.headNodOffset + sample.headNodDelta).coerceIn(-65f, 65f)
                ),
                expression = sample.expressionOverride ?: baseExpression,
                facialPresetId = sample.facialPresetId,
                mouthOverride = sample.mouthShapeOverride ?: baseMouthOverride,
                eyeOverride = sample.eyeShapeOverride ?: baseEyeOverride,
                eyebrowOverride = sample.eyebrowShapeOverride ?: baseEyebrowOverride,
                handPose = baseHandPose,
                blinkOverride = sample.blinkOverride,
                gazeTarget = baseGaze,
                headTilt = baseHeadTilt
            )
            ModularActionTrack.HANDS -> BlendedPreviewPose(
                poseOffsets = basePose.copy(
                    leftArmOffset = (basePose.leftArmOffset + sample.leftArmDelta).coerceIn(-160f, 160f),
                    leftForearmOffset = (basePose.leftForearmOffset + sample.leftForearmDelta).coerceIn(-145f, 145f),
                    rightArmOffset = (basePose.rightArmOffset + sample.rightArmDelta).coerceIn(-160f, 160f),
                    rightForearmOffset = (basePose.rightForearmOffset + sample.rightForearmDelta).coerceIn(-145f, 145f)
                ),
                expression = baseExpression,
                facialPresetId = null,
                mouthOverride = baseMouthOverride,
                eyeOverride = baseEyeOverride,
                eyebrowOverride = baseEyebrowOverride,
                handPose = sample.handPoseOverride ?: baseHandPose,
                blinkOverride = null,
                gazeTarget = baseGaze,
                headTilt = baseHeadTilt
            )
            ModularActionTrack.LEGS -> BlendedPreviewPose(
                poseOffsets = basePose.copy(
                    torsoTiltOffset = (basePose.torsoTiltOffset + sample.torsoTiltDelta).coerceIn(-55f, 55f),
                    leftLegOffset = (basePose.leftLegOffset + sample.leftLegDelta).coerceIn(-120f, 120f),
                    leftKneeOffset = (basePose.leftKneeOffset + sample.leftKneeDelta).coerceIn(-135f, 135f),
                    rightLegOffset = (basePose.rightLegOffset + sample.rightLegDelta).coerceIn(-120f, 120f),
                    rightKneeOffset = (basePose.rightKneeOffset + sample.rightKneeDelta).coerceIn(-135f, 135f)
                ),
                expression = baseExpression,
                facialPresetId = null,
                mouthOverride = baseMouthOverride,
                eyeOverride = baseEyeOverride,
                eyebrowOverride = baseEyebrowOverride,
                handPose = baseHandPose,
                blinkOverride = null,
                gazeTarget = baseGaze,
                headTilt = baseHeadTilt
            )
        }
    }

    /**
     * Commits a previewed [ModularActionDef] onto [clipId] starting at [startGlobalFrame] by
     * stamping 5 clean track-isolated keyframes (`0%`, `25%`, `50%`, `75%`, `100%`) that ONLY
     * alter the target track's channels while keeping all other body tracks intact!
     */
    fun insertModularActionIntoProject(
        project: ProjectDocument,
        clipId: String,
        startGlobalFrame: Int,
        action: ModularActionDef
    ): ProjectDocument {
        val scene = project.activeScene
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return project
        val safeStart = startGlobalFrame.coerceIn(clip.startFrame, (clip.endFrame - 1).coerceAtLeast(clip.startFrame))
        val span = action.durationFrames.coerceIn(8, (clip.endFrame - safeStart).coerceAtLeast(8))
        val sampleSteps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        val baseProject = project
        var updatedProject = project
        for (u in sampleSteps) {
            val targetGlobalFrame = (safeStart + (span * u).roundToInt())
                .coerceIn(clip.startFrame, (clip.endFrame - 1).coerceAtLeast(clip.startFrame))
            val sample = evaluateAtProgress(action, u)
            val baselineEval = KeyframeEvaluator.evaluate(clip, targetGlobalFrame)
            val basePose = baselineEval.poseOffsets

            updatedProject = ProjectMutator.upsertKeyframeAtFrame(updatedProject, clipId, targetGlobalFrame) { kf ->
                when (action.track) {
                    ModularActionTrack.FACIAL -> kf.copy(
                        easing = EasingType.EASE_IN_OUT,
                        poseOffsets = basePose.copy(
                            headNodOffset = (basePose.headNodOffset + sample.headNodDelta).coerceIn(-65f, 65f)
                        ),
                        expressionOverride = sample.expressionOverride ?: baselineEval.expressionOverride,
                        facialPresetId = sample.facialPresetId ?: baselineEval.facialPresetId,
                        mouthShapeOverride = sample.mouthShapeOverride ?: baselineEval.mouthShapeOverride,
                        eyeShapeOverride = sample.eyeShapeOverride ?: baselineEval.eyeShapeOverride,
                        eyebrowShapeOverride = sample.eyebrowShapeOverride ?: baselineEval.eyebrowShapeOverride,
                        blinkOverride = sample.blinkOverride ?: baselineEval.blinkOverride,
                        handPoseOverride = baselineEval.handPoseOverride
                    )
                    ModularActionTrack.HANDS -> kf.copy(
                        easing = EasingType.EASE_IN_OUT,
                        poseOffsets = basePose.copy(
                            leftArmOffset = (basePose.leftArmOffset + sample.leftArmDelta).coerceIn(-160f, 160f),
                            leftForearmOffset = (basePose.leftForearmOffset + sample.leftForearmDelta).coerceIn(-145f, 145f),
                            rightArmOffset = (basePose.rightArmOffset + sample.rightArmDelta).coerceIn(-160f, 160f),
                            rightForearmOffset = (basePose.rightForearmOffset + sample.rightForearmDelta).coerceIn(-145f, 145f)
                        ),
                        expressionOverride = baselineEval.expressionOverride,
                        facialPresetId = baselineEval.facialPresetId,
                        mouthShapeOverride = baselineEval.mouthShapeOverride,
                        eyeShapeOverride = baselineEval.eyeShapeOverride,
                        eyebrowShapeOverride = baselineEval.eyebrowShapeOverride,
                        blinkOverride = baselineEval.blinkOverride,
                        handPoseOverride = sample.handPoseOverride ?: baselineEval.handPoseOverride
                    )
                    ModularActionTrack.LEGS -> kf.copy(
                        easing = EasingType.EASE_IN_OUT,
                        poseOffsets = basePose.copy(
                            torsoTiltOffset = (basePose.torsoTiltOffset + sample.torsoTiltDelta).coerceIn(-55f, 55f),
                            leftLegOffset = (basePose.leftLegOffset + sample.leftLegDelta).coerceIn(-120f, 120f),
                            leftKneeOffset = (basePose.leftKneeOffset + sample.leftKneeDelta).coerceIn(-135f, 135f),
                            rightLegOffset = (basePose.rightLegOffset + sample.rightLegDelta).coerceIn(-120f, 120f),
                            rightKneeOffset = (basePose.rightKneeOffset + sample.rightKneeDelta).coerceIn(-135f, 135f)
                        ),
                        expressionOverride = baselineEval.expressionOverride,
                        facialPresetId = baselineEval.facialPresetId,
                        mouthShapeOverride = baselineEval.mouthShapeOverride,
                        eyeShapeOverride = baselineEval.eyeShapeOverride,
                        eyebrowShapeOverride = baselineEval.eyebrowShapeOverride,
                        blinkOverride = baselineEval.blinkOverride,
                        handPoseOverride = baselineEval.handPoseOverride
                    )
                }
            }
        }
        return updatedProject
    }
}
