package com.example.engine

import com.example.model.*
import com.example.viewmodel.ProjectMutator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Independent modular body tracks that can be previewed and layered cleanly
 * without disturbing the character's other body tracks.
 */
enum class ModularActionTrack(val displayName: String, val timelineTrackLabel: String, val accentHex: String) {
    FACIAL("Expressions", "Expression", "#F59E0B"),
    HANDS("Hand Parts", "Hands", "#38BDF8"),
    LEGS("Leg Parts", "Legs", "#34D399")
}

/**
 * Declarative modular action card selectable inside the Pro Control Modular Action Library.
 */
data class ModularActionDef(
    val id: String,
    val name: String,
    val track: ModularActionTrack,
    val durationFrames: Int,
    val subtitle: String,
    val accentHex: String = track.accentHex,
    val propHint: String? = null
)

/**
 * Combined non-destructive modular control selection applied via the sticky `Apply Changes` button.
 */
data class ModularControlSelection(
    val expressionId: String? = null,
    val handId: String? = null,
    val legId: String? = null,
    val speakingMode: CharacterSpeakingMode = CharacterSpeakingMode.AUTO_LIP_SYNC,
    val eyeblinkMode: CharacterEyeblinkMode = CharacterEyeblinkMode.ENABLE
) {
    val activeCount: Int
        get() = listOfNotNull(expressionId, handId, legId).size

    companion object {
        fun fromClip(clip: TimelineClip): ModularControlSelection = ModularControlSelection(
            expressionId = clip.modularExpressionId,
            handId = clip.modularHandId,
            legId = clip.modularLegId,
            speakingMode = clip.speakingMode,
            eyeblinkMode = clip.eyeblinkMode
        )
    }
}

/**
 * Track-isolated evaluated sample of a [ModularActionDef] at normalized progress `0f..1f`.
 * Follows Android Canvas anatomical rotation convention (`ProceduralRigRenderer` / `SpriteAtlasRigRenderer`):
 * - Left arm: `+arm` swings outward-left/up; `-forearm` folds elbow inward across torso/face.
 * - Right arm: `-arm` swings outward-right/up; `+forearm` folds elbow inward across torso/face.
 * - Legs: `-leg` lifts thigh forward; `+knee` bends shin downward/back.
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
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
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
    val rightKneeDelta: Float = 0f,
    // Whole-body vertical bob + volume-preserving squash & stretch
    val bodyBobDelta: Float = 0f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
    // Silhouette framing/posture hints (`single_right_arm`, `cross_legged`, `figure_four`, `seated_profile`, `head_turn_left/right/behind`)
    val silhouetteStyle: String? = null,
    val propSilhouetteHint: String? = null
)

/**
 * Non-destructive blended preview state returned by [ModularActionEngine.blendPreviewOntoBase]
 * and [ModularActionEngine.blendControlSelectionOntoBase].
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
    val headTilt: Float,
    val bodyBobDelta: Float = 0f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
    val overrideHandTrack: Boolean = false,
    val overrideLegTrack: Boolean = false
)

/**
 * Offline Pro "Control" Modular Action Engine, Non-Destructive Multi-Track Blender,
 * Speaking/Eyeblink Evaluator, and Track-Isolated Timeline Committer.
 */
object ModularActionEngine {

    val catalog: List<ModularActionDef>
        get() = ModularActionCatalog.allActions

    fun actionsForTrack(track: ModularActionTrack): List<ModularActionDef> =
        catalog.filter { it.track == track }

    fun search(query: String, trackFilter: ModularActionTrack? = null): List<ModularActionDef> {
        val q = query.trim().lowercase()
        return catalog.filter { act ->
            (trackFilter == null || act.track == trackFilter) &&
                (q.isEmpty() ||
                    act.name.lowercase().contains(q) ||
                    act.subtitle.lowercase().contains(q) ||
                    act.id.lowercase().contains(q))
        }
    }

    fun findById(id: String?): ModularActionDef? {
        if (id.isNullOrBlank()) return null
        val q = id.trim()
        return catalog.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: catalog.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    /**
     * Evaluates a modular action deterministically at normalized progress [progress] in `0f..1f`.
     */
    fun evaluateAtProgress(action: ModularActionDef, progress: Float): ModularActionSample =
        ModularActionCatalog.evaluate(action, progress)

    /**
     * Deterministic multi-harmonic syllable mouth cadence for `Speaking = Yes`.
     */
    fun evaluateSpeakingMouth(localFrame: Int, fps: Int, isAngry: Boolean = false): MouthShapeOverride {
        val safeFps = fps.coerceAtLeast(12)
        val tSec = localFrame.coerceAtLeast(0).toFloat() / safeFps.toFloat()
        val syllableWave = sin(tSec * 2.0 * PI * 4.1).toFloat() * 0.68f +
            sin(tSec * 2.0 * PI * 6.9 + 0.6).toFloat() * 0.34f +
            sin(tSec * 2.0 * PI * 11.3 + 1.4).toFloat() * 0.16f
        val phraseBreath = sin(tSec * 2.0 * PI * 0.52).toFloat()
        if (phraseBreath < -0.78f) {
            return if (isAngry) MouthShapeOverride.GRIT_ANGRY else MouthShapeOverride.NEUTRAL
        }
        return when {
            syllableWave > 0.58f -> MouthShapeOverride.TALK_AA
            syllableWave > 0.24f -> MouthShapeOverride.SHOCK_O
            syllableWave > -0.08f -> MouthShapeOverride.TALK_EE
            syllableWave > -0.42f -> if (isAngry) MouthShapeOverride.GRIT_ANGRY else MouthShapeOverride.SMILE
            else -> if (isAngry) MouthShapeOverride.FROWN else MouthShapeOverride.NEUTRAL
        }
    }

    /**
     * Non-destructively blends a single previewed [ModularActionDef] at [progress] onto the character's
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
                gazeTarget = if (abs(sample.gazeX) > 0.01f || abs(sample.gazeY) > 0.01f) {
                    GazeTarget(sample.gazeX.coerceIn(-1f, 1f), sample.gazeY.coerceIn(-1f, 1f))
                } else baseGaze,
                headTilt = baseHeadTilt,
                bodyBobDelta = sample.bodyBobDelta,
                squashX = sample.squashX,
                squashY = sample.squashY
            )
            ModularActionTrack.HANDS -> BlendedPreviewPose(
                poseOffsets = basePose.copy(
                    leftArmOffset = (basePose.leftArmOffset + sample.leftArmDelta).coerceIn(-165f, 165f),
                    leftForearmOffset = (basePose.leftForearmOffset + sample.leftForearmDelta).coerceIn(-155f, 155f),
                    rightArmOffset = (basePose.rightArmOffset + sample.rightArmDelta).coerceIn(-165f, 165f),
                    rightForearmOffset = (basePose.rightForearmOffset + sample.rightForearmDelta).coerceIn(-155f, 155f)
                ),
                expression = baseExpression,
                facialPresetId = null,
                mouthOverride = baseMouthOverride,
                eyeOverride = baseEyeOverride,
                eyebrowOverride = baseEyebrowOverride,
                handPose = sample.handPoseOverride ?: baseHandPose,
                blinkOverride = null,
                gazeTarget = baseGaze,
                headTilt = baseHeadTilt,
                overrideHandTrack = true
            )
            ModularActionTrack.LEGS -> BlendedPreviewPose(
                poseOffsets = basePose.copy(
                    torsoTiltOffset = (basePose.torsoTiltOffset + sample.torsoTiltDelta).coerceIn(-55f, 55f),
                    leftLegOffset = (basePose.leftLegOffset + sample.leftLegDelta).coerceIn(-125f, 125f),
                    leftKneeOffset = (basePose.leftKneeOffset + sample.leftKneeDelta).coerceIn(-140f, 140f),
                    rightLegOffset = (basePose.rightLegOffset + sample.rightLegDelta).coerceIn(-125f, 125f),
                    rightKneeOffset = (basePose.rightKneeOffset + sample.rightKneeDelta).coerceIn(-140f, 140f)
                ),
                expression = baseExpression,
                facialPresetId = null,
                mouthOverride = baseMouthOverride,
                eyeOverride = baseEyeOverride,
                eyebrowOverride = baseEyebrowOverride,
                handPose = baseHandPose,
                blinkOverride = null,
                gazeTarget = baseGaze,
                headTilt = baseHeadTilt,
                bodyBobDelta = sample.bodyBobDelta,
                squashX = sample.squashX,
                squashY = sample.squashY,
                overrideLegTrack = true
            )
        }
    }

    /**
     * Simultaneously layers an active [ModularControlSelection] (Expression + Hand Part + Leg Part
     * + Speaking mode + Eyeblink mode) non-destructively onto a character's base pose at [localFrame].
     *
     * Sets [BlendedPreviewPose.overrideHandTrack] when a Hand Part is active and
     * [BlendedPreviewPose.overrideLegTrack] when a Leg Part is active so base AnimationPreset
     * limbs never fight or double-add against the chosen modular tracks!
     */
    fun blendControlSelectionOntoBase(
        basePose: PoseKeyframeOffsets,
        baseExpression: FacialExpression,
        baseFacialPresetId: String?,
        baseMouthOverride: MouthShapeOverride,
        baseEyeOverride: EyeShapeOverride,
        baseEyebrowOverride: EyebrowShapeOverride,
        baseHandPose: HandPoseType,
        baseBlinkOverride: Boolean?,
        baseGaze: GazeTarget,
        baseHeadTilt: Float,
        selection: ModularControlSelection,
        localFrame: Int,
        fps: Int,
        speedMultiplier: Float = 1f
    ): BlendedPreviewPose {
        val safeSpeed = speedMultiplier.coerceIn(0.25f, 4f)
        fun progressFor(act: ModularActionDef): Float {
            val cycle = act.durationFrames.coerceAtLeast(8).toFloat() / safeSpeed
            return ((localFrame.coerceAtLeast(0) % cycle) / cycle).coerceIn(0f, 1f)
        }

        val exprSample = findById(selection.expressionId)?.let { evaluateAtProgress(it, progressFor(it)) }
        val handSample = findById(selection.handId)?.let { evaluateAtProgress(it, progressFor(it)) }
        val legSample = findById(selection.legId)?.let { evaluateAtProgress(it, progressFor(it)) }

        val combinedPose = basePose.copy(
            headNodOffset = (basePose.headNodOffset + (exprSample?.headNodDelta ?: 0f)).coerceIn(-65f, 65f),
            leftArmOffset = (basePose.leftArmOffset + (handSample?.leftArmDelta ?: 0f)).coerceIn(-165f, 165f),
            leftForearmOffset = (basePose.leftForearmOffset + (handSample?.leftForearmDelta ?: 0f)).coerceIn(-155f, 155f),
            rightArmOffset = (basePose.rightArmOffset + (handSample?.rightArmDelta ?: 0f)).coerceIn(-165f, 165f),
            rightForearmOffset = (basePose.rightForearmOffset + (handSample?.rightForearmDelta ?: 0f)).coerceIn(-155f, 155f),
            torsoTiltOffset = (basePose.torsoTiltOffset + (legSample?.torsoTiltDelta ?: 0f)).coerceIn(-55f, 55f),
            leftLegOffset = (basePose.leftLegOffset + (legSample?.leftLegDelta ?: 0f)).coerceIn(-125f, 125f),
            leftKneeOffset = (basePose.leftKneeOffset + (legSample?.leftKneeDelta ?: 0f)).coerceIn(-140f, 140f),
            rightLegOffset = (basePose.rightLegOffset + (legSample?.rightLegDelta ?: 0f)).coerceIn(-125f, 125f),
            rightKneeOffset = (basePose.rightKneeOffset + (legSample?.rightKneeDelta ?: 0f)).coerceIn(-140f, 140f)
        )

        val effExpression = exprSample?.expressionOverride ?: baseExpression
        val effFacialPresetId = exprSample?.facialPresetId ?: baseFacialPresetId
        var effMouth = exprSample?.mouthShapeOverride ?: baseMouthOverride
        var effEye = exprSample?.eyeShapeOverride ?: baseEyeOverride
        val effBrow = exprSample?.eyebrowShapeOverride ?: baseEyebrowOverride
        val effHandPose = handSample?.handPoseOverride ?: baseHandPose

        // Speaking mode radio control
        when (selection.speakingMode) {
            CharacterSpeakingMode.YES -> {
                effMouth = evaluateSpeakingMouth(
                    localFrame = localFrame,
                    fps = fps,
                    isAngry = effExpression == FacialExpression.ANGRY
                )
            }
            CharacterSpeakingMode.NO -> {
                if (effMouth == MouthShapeOverride.TALK_AA ||
                    effMouth == MouthShapeOverride.TALK_EE ||
                    effMouth == MouthShapeOverride.OPEN_LAUGH
                ) {
                    effMouth = MouthShapeOverride.NEUTRAL
                }
            }
            CharacterSpeakingMode.AUTO_LIP_SYNC -> Unit
        }

        // Eyeblink mode radio control
        val effBlink: Boolean? = when (selection.eyeblinkMode) {
            CharacterEyeblinkMode.ENABLE -> exprSample?.blinkOverride ?: baseBlinkOverride
            CharacterEyeblinkMode.DISABLE -> false
            CharacterEyeblinkMode.EYE_CLOSED -> {
                effEye = EyeShapeOverride.CLOSED_SLEEP
                true
            }
        }

        val effGaze = if (exprSample != null && (abs(exprSample.gazeX) > 0.01f || abs(exprSample.gazeY) > 0.01f)) {
            GazeTarget(exprSample.gazeX.coerceIn(-1f, 1f), exprSample.gazeY.coerceIn(-1f, 1f))
        } else {
            baseGaze
        }

        val combinedBob = ((exprSample?.bodyBobDelta ?: 0f) + (legSample?.bodyBobDelta ?: 0f)).coerceIn(-52f, 36f)
        val combinedSqX = ((exprSample?.squashX ?: 1f) * (legSample?.squashX ?: 1f)).coerceIn(0.82f, 1.20f)
        val combinedSqY = ((exprSample?.squashY ?: 1f) * (legSample?.squashY ?: 1f)).coerceIn(0.82f, 1.20f)

        return BlendedPreviewPose(
            poseOffsets = combinedPose,
            expression = effExpression,
            facialPresetId = effFacialPresetId,
            mouthOverride = effMouth,
            eyeOverride = effEye,
            eyebrowOverride = effBrow,
            handPose = effHandPose,
            blinkOverride = effBlink,
            gazeTarget = effGaze,
            headTilt = baseHeadTilt,
            bodyBobDelta = combinedBob,
            squashX = combinedSqX,
            squashY = combinedSqY,
            overrideHandTrack = handSample != null,
            overrideLegTrack = legSample != null
        )
    }

    /**
     * Commits the selected Expression + Hand Part + Leg Part + Speaking + Eyeblink non-destructively
     * onto [clipId], preserving all existing keyframes, transform, shadow, tears, and props!
     */
    fun applyModularControlConfigToProject(
        project: ProjectDocument,
        clipId: String,
        selection: ModularControlSelection
    ): ProjectDocument {
        return ProjectMutator.updateActiveScene(project) { scene ->
            scene.copy(
                clips = scene.clips.map { clip ->
                    if (clip.id == clipId) {
                        clip.copy(
                            modularExpressionId = selection.expressionId,
                            modularHandId = selection.handId,
                            modularLegId = selection.legId,
                            speakingMode = selection.speakingMode,
                            eyeblinkMode = selection.eyeblinkMode,
                            forceBlink = selection.eyeblinkMode == CharacterEyeblinkMode.EYE_CLOSED
                        )
                    } else clip
                }
            )
        }
    }

    /**
     * Commits a single previewed [ModularActionDef] onto [clipId] starting at [startGlobalFrame] by
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

        var updatedProject = project
        for (u in sampleSteps) {
            val targetGlobalFrame = (safeStart + (span * u).roundToInt())
                .coerceIn(clip.startFrame, (clip.endFrame - 1).coerceAtLeast(clip.startFrame))
            val sample = evaluateAtProgress(action, u)
            // Smooth entry/exit weight on one-shot timeline keyframe stamps so transitions ease cleanly
            val rampWeight = when {
                u <= 0.01f || u >= 0.99f -> 0.15f
                else -> 1f
            }
            val baselineEval = KeyframeEvaluator.evaluate(clip, targetGlobalFrame)
            val basePose = baselineEval.poseOffsets

            updatedProject = ProjectMutator.upsertKeyframeAtFrame(updatedProject, clipId, targetGlobalFrame) { kf ->
                when (action.track) {
                    ModularActionTrack.FACIAL -> kf.copy(
                        easing = EasingType.EASE_IN_OUT,
                        poseOffsets = basePose.copy(
                            headNodOffset = (basePose.headNodOffset + sample.headNodDelta * rampWeight).coerceIn(-65f, 65f)
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
                            leftArmOffset = (basePose.leftArmOffset + sample.leftArmDelta * rampWeight).coerceIn(-165f, 165f),
                            leftForearmOffset = (basePose.leftForearmOffset + sample.leftForearmDelta * rampWeight).coerceIn(-155f, 155f),
                            rightArmOffset = (basePose.rightArmOffset + sample.rightArmDelta * rampWeight).coerceIn(-165f, 165f),
                            rightForearmOffset = (basePose.rightForearmOffset + sample.rightForearmDelta * rampWeight).coerceIn(-155f, 155f)
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
                            torsoTiltOffset = (basePose.torsoTiltOffset + sample.torsoTiltDelta * rampWeight).coerceIn(-55f, 55f),
                            leftLegOffset = (basePose.leftLegOffset + sample.leftLegDelta * rampWeight).coerceIn(-125f, 125f),
                            leftKneeOffset = (basePose.leftKneeOffset + sample.leftKneeDelta * rampWeight).coerceIn(-140f, 140f),
                            rightLegOffset = (basePose.rightLegOffset + sample.rightLegDelta * rampWeight).coerceIn(-125f, 125f),
                            rightKneeOffset = (basePose.rightKneeOffset + sample.rightKneeDelta * rampWeight).coerceIn(-140f, 140f)
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
