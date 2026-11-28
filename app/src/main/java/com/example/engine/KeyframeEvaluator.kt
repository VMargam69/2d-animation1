package com.example.engine

import com.example.model.AnimationScene
import com.example.model.ArmGestureTrackPreset
import com.example.model.AudioSyncMetadata
import com.example.model.CameraKeyframe
import com.example.model.CharacterEyeblinkMode
import com.example.model.CharacterSpeakingMode
import com.example.model.EasingType
import com.example.model.EyeShapeOverride
import com.example.model.EyebrowShapeOverride
import com.example.model.FacialExpression
import com.example.model.GazeTarget
import com.example.model.HandPoseType
import com.example.model.MouthShapeOverride
import com.example.model.NormalizedPoint
import com.example.model.PoseKeyframeOffsets
import com.example.model.SimultaneousTrackType
import com.example.model.TimelineClip
import com.example.model.TransformKeyframe
import com.example.model.TransitionType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class EvaluatedTransform(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
    val opacity: Float,
    val poseOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
    val expressionOverride: FacialExpression? = null,
    val facialPresetId: String? = null,
    val previousFacialPresetId: String? = null,
    val framesSinceFacialKey: Int = 999,
    val mouthShapeOverride: MouthShapeOverride? = null,
    val eyeShapeOverride: EyeShapeOverride? = null,
    val eyebrowShapeOverride: EyebrowShapeOverride? = null,
    val handPoseOverride: HandPoseType? = null,
    val armGestureOverride: ArmGestureTrackPreset? = null,
    val blinkOverride: Boolean? = null
)

/**
 * Combined non-destructive 5-track evaluation for a single character at a timestamp:
 * 1. Base Body/Legs Preset (`clip.animationPresetType`)
 * 2. Independent Hand/Arm Gesture Track (arm/forearm deltas + handPose without touching legs/torso)
 * 3. Custom IK Pose Overrides (`poseOffsets` layered additively)
 * 4. Facial Expression Override Track (independent Mouth/Eyes/Eyebrows when `facialOverridesEnabled`)
 * 5. Auto Audio Lip-Sync (phoneme-synced mouth shape layered cleanly on top)
 */
data class SimultaneousCharacterBlend(
    val blendedPoseOffsets: PoseKeyframeOffsets,
    val effectiveExpression: FacialExpression,
    val effectiveFacialPresetId: String?,
    val previousFacialPresetId: String?,
    val framesSinceFacialKey: Int,
    val effectiveMouthOverride: MouthShapeOverride,
    val effectiveEyeOverride: EyeShapeOverride,
    val effectiveEyebrowOverride: EyebrowShapeOverride,
    val effectiveHandPose: HandPoseType,
    val activeArmGesture: ArmGestureTrackPreset,
    val effectiveBlink: Boolean?,
    val lipSyncActive: Boolean,
    val effectiveGazeX: Float? = null,
    val effectiveGazeY: Float? = null,
    val bodyBobDelta: Float = 0f,
    val squashX: Float = 1f,
    val squashY: Float = 1f,
    val overrideHandTrack: Boolean = false,
    val overrideLegTrack: Boolean = false
)

data class EvaluatedCamera(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val zoom: Float = 1.0f,
    val rotation: Float = 0f,
    val trackedClipName: String? = null
) {
    val isIdentity: Boolean
        get() = kotlin.math.abs(x - 0.5f) < 0.0005f &&
            kotlin.math.abs(y - 0.5f) < 0.0005f &&
            kotlin.math.abs(zoom - 1.0f) < 0.0005f &&
            kotlin.math.abs(rotation) < 0.05f
}

object KeyframeEvaluator {

    fun evaluate(clip: TimelineClip, globalFrame: Int): EvaluatedTransform {
        val localFrame = (globalFrame - clip.startFrame).coerceAtLeast(0)
        val sorted = ensureSortedTransformKeyframes(clip.keyframes)
        var base = evaluateKeyframes(sorted, localFrame)
        if (clip.motionPathControlPoints.size >= 3) {
            val pathSpan = (sorted.lastOrNull()?.frame?.takeIf { it > 0 } ?: clip.durationFrames).coerceAtLeast(1)
            val rawU = (localFrame.toFloat() / pathSpan.toFloat()).coerceIn(0f, 1f)
            val firstEasing = sorted.firstOrNull()?.easing ?: EasingType.EASE_IN_OUT
            val easedU = evaluateEasing(firstEasing, rawU)
            val pt = evaluateBezierPath(clip.motionPathControlPoints, easedU)
            base = base.copy(x = pt.x, y = pt.y)
        }
        return applyTransitions(base, clip, localFrame)
    }

    /**
     * Evaluates a curved Bezier motion path of arbitrary degree using de Casteljau's algorithm.
     */
    fun evaluateBezierPath(controlPoints: List<NormalizedPoint>, t: Float): NormalizedPoint {
        if (controlPoints.isEmpty()) return NormalizedPoint(0.5f, 0.5f)
        if (controlPoints.size == 1) return controlPoints[0]
        val u = t.coerceIn(0f, 1f)
        val inv = 1f - u
        var pts = controlPoints
        while (pts.size > 1) {
            val next = ArrayList<NormalizedPoint>(pts.size - 1)
            for (i in 0 until pts.size - 1) {
                next.add(
                    NormalizedPoint(
                        x = inv * pts[i].x + u * pts[i + 1].x,
                        y = inv * pts[i].y + u * pts[i + 1].y
                    )
                )
            }
            pts = next
        }
        return pts[0]
    }

    fun evaluateKeyframes(sortedKeyframes: List<TransformKeyframe>, localFrame: Int): EvaluatedTransform {
        if (sortedKeyframes.isEmpty()) {
            return EvaluatedTransform(0.5f, 0.55f, 0f, 1f, 1f, 1f)
        }
        val exprState = resolveExpressionTrackState(sortedKeyframes, localFrame)
        val first = sortedKeyframes.first()
        if (sortedKeyframes.size == 1 || localFrame <= first.frame) {
            return first.toEvaluated().copy(
                expressionOverride = exprState.expressionOverride ?: first.expressionOverride,
                facialPresetId = exprState.activePresetId,
                previousFacialPresetId = exprState.previousPresetId,
                framesSinceFacialKey = exprState.framesSinceKey,
                mouthShapeOverride = exprState.mouthShapeOverride ?: first.mouthShapeOverride,
                eyeShapeOverride = exprState.eyeShapeOverride ?: first.eyeShapeOverride,
                eyebrowShapeOverride = exprState.eyebrowShapeOverride ?: first.eyebrowShapeOverride
            )
        }
        val last = sortedKeyframes.last()
        if (localFrame >= last.frame) {
            return last.toEvaluated().copy(
                expressionOverride = exprState.expressionOverride ?: last.expressionOverride,
                facialPresetId = exprState.activePresetId,
                previousFacialPresetId = exprState.previousPresetId,
                framesSinceFacialKey = exprState.framesSinceKey,
                mouthShapeOverride = exprState.mouthShapeOverride ?: last.mouthShapeOverride,
                eyeShapeOverride = exprState.eyeShapeOverride ?: last.eyeShapeOverride,
                eyebrowShapeOverride = exprState.eyebrowShapeOverride ?: last.eyebrowShapeOverride
            )
        }

        var prev = first
        var next = last
        for (i in 0 until sortedKeyframes.size - 1) {
            val a = sortedKeyframes[i]
            val b = sortedKeyframes[i + 1]
            if (localFrame in a.frame..b.frame) {
                prev = a
                next = b
                break
            }
        }

        val span = (next.frame - prev.frame).coerceAtLeast(1)
        val rawT = ((localFrame - prev.frame).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val t = evaluateEasing(
            easing = prev.easing,
            rawT = rawT,
            p1x = prev.easingP1X,
            p1y = prev.easingP1Y,
            p2x = prev.easingP2X,
            p2y = prev.easingP2Y,
            isAtEnd = localFrame >= next.frame
        )

        val pPose = prev.poseOffsets
        val nPose = next.poseOffsets
        val interpPose = if (!pPose.hasCustomOffsets && !nPose.hasCustomOffsets) {
            pPose
        } else {
            PoseKeyframeOffsets(
                headNodOffset = lerp(pPose.headNodOffset, nPose.headNodOffset, t),
                torsoTiltOffset = lerp(pPose.torsoTiltOffset, nPose.torsoTiltOffset, t),
                leftArmOffset = lerp(pPose.leftArmOffset, nPose.leftArmOffset, t),
                leftForearmOffset = lerp(pPose.leftForearmOffset, nPose.leftForearmOffset, t),
                rightArmOffset = lerp(pPose.rightArmOffset, nPose.rightArmOffset, t),
                rightForearmOffset = lerp(pPose.rightForearmOffset, nPose.rightForearmOffset, t),
                leftLegOffset = lerp(pPose.leftLegOffset, nPose.leftLegOffset, t),
                leftKneeOffset = lerp(pPose.leftKneeOffset, nPose.leftKneeOffset, t),
                rightLegOffset = lerp(pPose.rightLegOffset, nPose.rightLegOffset, t),
                rightKneeOffset = lerp(pPose.rightKneeOffset, nPose.rightKneeOffset, t)
            )
        }

        return EvaluatedTransform(
            x = lerp(prev.x, next.x, t),
            y = lerp(prev.y, next.y, t),
            rotation = lerp(prev.rotation, next.rotation, t),
            scaleX = lerp(prev.scaleX, next.scaleX, t),
            scaleY = lerp(prev.scaleY, next.scaleY, t),
            opacity = lerp(prev.opacity, next.opacity, t).coerceIn(0f, 1f),
            poseOffsets = interpPose,
            expressionOverride = exprState.expressionOverride
                ?: if (t >= 1f) (next.expressionOverride ?: prev.expressionOverride) else prev.expressionOverride,
            facialPresetId = exprState.activePresetId,
            previousFacialPresetId = exprState.previousPresetId,
            framesSinceFacialKey = exprState.framesSinceKey,
            mouthShapeOverride = exprState.mouthShapeOverride
                ?: if (t >= 1f) (next.mouthShapeOverride ?: prev.mouthShapeOverride) else prev.mouthShapeOverride,
            eyeShapeOverride = exprState.eyeShapeOverride
                ?: if (t >= 1f) (next.eyeShapeOverride ?: prev.eyeShapeOverride) else prev.eyeShapeOverride,
            eyebrowShapeOverride = exprState.eyebrowShapeOverride
                ?: if (t >= 1f) (next.eyebrowShapeOverride ?: prev.eyebrowShapeOverride) else prev.eyebrowShapeOverride,
            handPoseOverride = if (t >= 1f) (next.handPoseOverride ?: prev.handPoseOverride) else prev.handPoseOverride,
            armGestureOverride = if (t >= 1f) (next.armGestureOverride ?: prev.armGestureOverride) else prev.armGestureOverride,
            blinkOverride = if (t >= 1f) (next.blinkOverride ?: prev.blinkOverride) else prev.blinkOverride
        )
    }

    private data class ExpressionTrackState(
        val activePresetId: String? = null,
        val previousPresetId: String? = null,
        val expressionOverride: FacialExpression? = null,
        val framesSinceKey: Int = 999,
        val mouthShapeOverride: MouthShapeOverride? = null,
        val eyeShapeOverride: EyeShapeOverride? = null,
        val eyebrowShapeOverride: EyebrowShapeOverride? = null
    )

    /**
     * Scans expression-bearing keyframes and independent Mouth / Eye / Eyebrow shape
     * override keyframes separately from pure transform keyframes so overrides hold
     * cleanly across movement keyframes and transition organically.
     */
    private fun resolveExpressionTrackState(
        sortedKeyframes: List<TransformKeyframe>,
        localFrame: Int
    ): ExpressionTrackState {
        var activeKf: TransformKeyframe? = null
        var previousKf: TransformKeyframe? = null
        var activeMouth: MouthShapeOverride? = null
        var activeEye: EyeShapeOverride? = null
        var activeEyebrow: EyebrowShapeOverride? = null
        for (i in sortedKeyframes.indices) {
            val kf = sortedKeyframes[i]
            if (kf.frame > localFrame) break
            if (kf.facialPresetId != null || kf.expressionOverride != null) {
                previousKf = activeKf
                activeKf = kf
            }
            if (kf.mouthShapeOverride != null) activeMouth = kf.mouthShapeOverride
            if (kf.eyeShapeOverride != null) activeEye = kf.eyeShapeOverride
            if (kf.eyebrowShapeOverride != null) activeEyebrow = kf.eyebrowShapeOverride
        }
        val cur = activeKf
        val activePreset = cur?.let {
            it.facialPresetId ?: it.expressionOverride?.let { expr ->
                FacialExpressionEngine.presetForExpression(expr).id
            }
        }
        val prevPreset = previousKf?.let { prev ->
            prev.facialPresetId ?: prev.expressionOverride?.let { expr ->
                FacialExpressionEngine.presetForExpression(expr).id
            }
        }
        return ExpressionTrackState(
            activePresetId = activePreset,
            previousPresetId = prevPreset,
            expressionOverride = cur?.expressionOverride,
            framesSinceKey = cur?.let { (localFrame - it.frame).coerceAtLeast(0) } ?: 999,
            mouthShapeOverride = activeMouth,
            eyeShapeOverride = activeEye,
            eyebrowShapeOverride = activeEyebrow
        )
    }

    /**
     * Evaluates all 5 simultaneous non-destructive character tracks at [globalFrame]:
     *   Base Body/Legs Preset -> Independent Hand/Arm Gesture Track -> Custom IK Pose Overrides
     *   -> Facial Expression Override Track -> Auto Audio Lip-Sync.
     */
    fun evaluateSimultaneousCharacterTracks(
        clip: TimelineClip,
        eval: EvaluatedTransform,
        globalFrame: Int,
        fps: Int = 24,
        audioSyncTracks: List<AudioSyncMetadata> = emptyList()
    ): SimultaneousCharacterBlend {
        val localFrame = (globalFrame - clip.startFrame).coerceAtLeast(0)
        val safeFps = fps.coerceAtLeast(12)

        // Track 2: Independent Hand/Arm Gesture Track (touches ONLY arms/forearms/handPose)
        val activeGestureClip = clip.simultaneousTracks.lastOrNull { tr ->
            tr.trackType == SimultaneousTrackType.HAND_GESTURE &&
                localFrame in tr.startLocalFrame until (tr.startLocalFrame + tr.durationFrames.coerceAtLeast(1))
        }
        val activeGesture = activeGestureClip?.armGesturePreset?.takeIf { it != ArmGestureTrackPreset.NONE }
            ?: eval.armGestureOverride?.takeIf { it != ArmGestureTrackPreset.NONE }
            ?: clip.armGesturePreset

        val gestureProgress = if (activeGestureClip != null) {
            ((localFrame - activeGestureClip.startLocalFrame).toFloat() /
                activeGestureClip.durationFrames.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        } else {
            val cycle = (safeFps * 1.1f).coerceAtLeast(12f)
            ((localFrame % cycle) / cycle).coerceIn(0f, 1f)
        }
        val gestureWeight = (activeGestureClip?.weight ?: 1f).coerceIn(0f, 1f)
        val gestureArmDeltas = evaluateArmGestureDeltas(activeGesture, gestureProgress, gestureWeight)

        // Track 3: Custom IK Pose Overrides blended cleanly on top of Hand/Arm Gesture Track
        val blendedOffsets = eval.poseOffsets.copy(
            leftArmOffset = (eval.poseOffsets.leftArmOffset + gestureArmDeltas.leftArmOffset).coerceIn(-165f, 165f),
            leftForearmOffset = (eval.poseOffsets.leftForearmOffset + gestureArmDeltas.leftForearmOffset).coerceIn(-148f, 148f),
            rightArmOffset = (eval.poseOffsets.rightArmOffset + gestureArmDeltas.rightArmOffset).coerceIn(-165f, 165f),
            rightForearmOffset = (eval.poseOffsets.rightForearmOffset + gestureArmDeltas.rightForearmOffset).coerceIn(-148f, 148f)
        )

        val resolvedHandPose = activeGestureClip?.handPoseOverride
            ?: eval.handPoseOverride
            ?: if (activeGesture != ArmGestureTrackPreset.NONE) activeGesture.defaultHandPose else clip.handPose

        // Track 4: Independent Facial Expression Override Track (Mouth, Eyes, Eyebrows)
        val activeFacialTrack = clip.simultaneousTracks.lastOrNull { tr ->
            tr.trackType == SimultaneousTrackType.FACIAL_OVERRIDE &&
                localFrame in tr.startLocalFrame until (tr.startLocalFrame + tr.durationFrames.coerceAtLeast(1))
        }
        val effectiveExpression = if (clip.facialOverridesEnabled) {
            activeFacialTrack?.expressionOverride ?: eval.expressionOverride ?: clip.facialExpression
        } else {
            clip.facialExpression
        }
        val effectiveFacialPresetId = if (clip.facialOverridesEnabled) {
            activeFacialTrack?.facialPresetId ?: eval.facialPresetId
        } else {
            null
        }
        var effectiveMouth = if (clip.facialOverridesEnabled) {
            activeFacialTrack?.mouthOverride ?: eval.mouthShapeOverride ?: clip.mouthShapeOverride
        } else {
            MouthShapeOverride.AUTO
        }
        val effectiveEye = if (clip.facialOverridesEnabled) {
            activeFacialTrack?.eyeOverride ?: eval.eyeShapeOverride ?: clip.eyeShapeOverride
        } else {
            EyeShapeOverride.AUTO
        }
        val effectiveEyebrow = if (clip.facialOverridesEnabled) {
            activeFacialTrack?.eyebrowOverride ?: eval.eyebrowShapeOverride ?: clip.eyebrowShapeOverride
        } else {
            EyebrowShapeOverride.AUTO
        }

        // Track 5: Auto Audio Lip-Sync + Speaking Mode (`Auto Lip Sync` | `Yes` | `No`)
        var lipSyncActive = false
        if (clip.speakingMode != CharacterSpeakingMode.NO) {
            val syncMeta = clip.lipSyncAudioClipId?.let { id ->
                audioSyncTracks.firstOrNull { it.clipId == id }
            }
            if (syncMeta != null && syncMeta.phonemeMappings.isNotEmpty()) {
                val phonemeCue = syncMeta.phonemeMappings.firstOrNull { it.frame == globalFrame }
                    ?: syncMeta.phonemeMappings.minByOrNull { abs(it.frame - globalFrame) }
                        ?.takeIf { abs(it.frame - globalFrame) <= 2 }
                val mappedMouth = phonemeCue?.let { mapPhonemeToMouthOverride(it.phoneme, it.amplitude) }
                if (mappedMouth != null) {
                    effectiveMouth = mappedMouth
                    lipSyncActive = true
                }
            }
            val lipSyncTrack = clip.simultaneousTracks.lastOrNull { tr ->
                tr.trackType == SimultaneousTrackType.LIP_SYNC &&
                    localFrame in tr.startLocalFrame until (tr.startLocalFrame + tr.durationFrames.coerceAtLeast(1))
            }
            if (lipSyncTrack?.mouthOverride != null) {
                effectiveMouth = lipSyncTrack.mouthOverride
                lipSyncActive = true
            }
        }

        val baseBlink = eval.blinkOverride ?: if (clip.forceBlink) true else null

        // Track 6: Pro "Control" Modular Action Library (non-destructive Expression + Hands + Legs + Speaking + Eyeblink)
        val hasModularControl = clip.modularExpressionId != null ||
            clip.modularHandId != null ||
            clip.modularLegId != null ||
            clip.speakingMode != CharacterSpeakingMode.AUTO_LIP_SYNC ||
            clip.eyeblinkMode != CharacterEyeblinkMode.ENABLE

        val finalBlend = if (hasModularControl) {
            val controlSel = ModularControlSelection.fromClip(clip)
            val modular = ModularActionEngine.blendControlSelectionOntoBase(
                basePose = blendedOffsets,
                baseExpression = effectiveExpression,
                baseFacialPresetId = effectiveFacialPresetId,
                baseMouthOverride = effectiveMouth,
                baseEyeOverride = effectiveEye,
                baseEyebrowOverride = effectiveEyebrow,
                baseHandPose = resolvedHandPose,
                baseBlinkOverride = baseBlink,
                baseGaze = GazeTarget(0f, 0f),
                baseHeadTilt = 0f,
                selection = controlSel,
                localFrame = localFrame,
                fps = safeFps,
                speedMultiplier = clip.speedMultiplier
            )
            // Preserve audio lip-sync mouth when Speaking = AUTO_LIP_SYNC and audio is actively speaking
            val resolvedMouth = if (clip.speakingMode == CharacterSpeakingMode.AUTO_LIP_SYNC && lipSyncActive) {
                effectiveMouth
            } else {
                modular.mouthOverride
            }
            val isSpeakingYes = clip.speakingMode == CharacterSpeakingMode.YES
            SimultaneousCharacterBlend(
                blendedPoseOffsets = modular.poseOffsets,
                effectiveExpression = modular.expression,
                effectiveFacialPresetId = modular.facialPresetId,
                previousFacialPresetId = eval.previousFacialPresetId,
                framesSinceFacialKey = eval.framesSinceFacialKey,
                effectiveMouthOverride = resolvedMouth,
                effectiveEyeOverride = modular.eyeOverride,
                effectiveEyebrowOverride = modular.eyebrowOverride,
                effectiveHandPose = modular.handPose,
                activeArmGesture = activeGesture,
                effectiveBlink = modular.blinkOverride,
                lipSyncActive = lipSyncActive || isSpeakingYes,
                effectiveGazeX = modular.gazeTarget.x.takeIf { kotlin.math.abs(it) > 0.01f },
                effectiveGazeY = modular.gazeTarget.y.takeIf { kotlin.math.abs(it) > 0.01f },
                bodyBobDelta = modular.bodyBobDelta,
                squashX = modular.squashX,
                squashY = modular.squashY,
                overrideHandTrack = modular.overrideHandTrack,
                overrideLegTrack = modular.overrideLegTrack
            )
        } else {
            SimultaneousCharacterBlend(
                blendedPoseOffsets = blendedOffsets,
                effectiveExpression = effectiveExpression,
                effectiveFacialPresetId = effectiveFacialPresetId,
                previousFacialPresetId = eval.previousFacialPresetId,
                framesSinceFacialKey = eval.framesSinceFacialKey,
                effectiveMouthOverride = effectiveMouth,
                effectiveEyeOverride = effectiveEye,
                effectiveEyebrowOverride = effectiveEyebrow,
                effectiveHandPose = resolvedHandPose,
                activeArmGesture = activeGesture,
                effectiveBlink = baseBlink,
                lipSyncActive = lipSyncActive
            )
        }

        return finalBlend
    }

    private fun evaluateArmGestureDeltas(
        preset: ArmGestureTrackPreset,
        progress: Float,
        weight: Float
    ): PoseKeyframeOffsets {
        if (preset == ArmGestureTrackPreset.NONE || weight <= 0.001f) return PoseKeyframeOffsets()
        val p = progress.coerceIn(0f, 1f)
        val env = sin(p * PI).toFloat().coerceIn(0f, 1f)
        val wave2 = sin(p * 4.0 * PI).toFloat()
        val raw = when (preset) {
            ArmGestureTrackPreset.NONE -> PoseKeyframeOffsets()
            ArmGestureTrackPreset.WAVE -> PoseKeyframeOffsets(
                rightArmOffset = -112f * (0.75f + 0.25f * env),
                rightForearmOffset = 28f + 24f * wave2
            )
            ArmGestureTrackPreset.POINT -> PoseKeyframeOffsets(
                rightArmOffset = -80f * (0.78f + 0.22f * env),
                rightForearmOffset = 10f
            )
            ArmGestureTrackPreset.HOLD_PROP -> PoseKeyframeOffsets(
                rightArmOffset = -38f,
                rightForearmOffset = 58f
            )
            ArmGestureTrackPreset.OPEN_WELCOME -> PoseKeyframeOffsets(
                leftArmOffset = 68f * (0.72f + 0.28f * env),
                leftForearmOffset = -18f,
                rightArmOffset = -68f * (0.72f + 0.28f * env),
                rightForearmOffset = 18f
            )
            ArmGestureTrackPreset.FIST_PUMP -> {
                val pump = 0.5f + 0.5f * sin(p * 4.0 * PI).toFloat()
                PoseKeyframeOffsets(
                    leftArmOffset = 54f + 22f * pump,
                    leftForearmOffset = -62f - 16f * pump,
                    rightArmOffset = -54f - 22f * pump,
                    rightForearmOffset = 62f + 16f * pump
                )
            }
            ArmGestureTrackPreset.THINK_CHIN -> PoseKeyframeOffsets(
                rightArmOffset = -56f,
                rightForearmOffset = 112f,
                leftArmOffset = 26f,
                leftForearmOffset = -64f
            )
            ArmGestureTrackPreset.CLAP -> {
                val clap = abs(sin(p * 2.0 * PI).toFloat())
                PoseKeyframeOffsets(
                    leftArmOffset = 34f + 20f * clap,
                    leftForearmOffset = -52f - 36f * clap,
                    rightArmOffset = -34f - 20f * clap,
                    rightForearmOffset = 52f + 36f * clap
                )
            }
        }
        return PoseKeyframeOffsets(
            leftArmOffset = raw.leftArmOffset * weight,
            leftForearmOffset = raw.leftForearmOffset * weight,
            rightArmOffset = raw.rightArmOffset * weight,
            rightForearmOffset = raw.rightForearmOffset * weight
        )
    }

    private fun mapPhonemeToMouthOverride(phoneme: String, amplitude: Float): MouthShapeOverride? {
        if (amplitude < 0.06f) return null
        return when (phoneme.uppercase()) {
            "AA" -> MouthShapeOverride.TALK_AA
            "OH" -> MouthShapeOverride.SHOCK_O
            "EE" -> MouthShapeOverride.TALK_EE
            "MBP" -> MouthShapeOverride.NEUTRAL
            else -> null
        }
    }

    /**
     * Evaluates scene camera including optional Camera Control Layer visibility toggle
     * and Focus Tracking on [AnimationScene.cameraFocusClipId].
     * When [AnimationScene.cameraLayerEnabled] is false, returns identity framing without
     * discarding saved camera keyframes.
     */
    fun evaluateCamera(
        scene: AnimationScene,
        globalFrame: Int,
        clipDeltaOverrideId: String? = null,
        clipDeltaX: Float = 0f,
        clipDeltaY: Float = 0f
    ): EvaluatedCamera {
        if (!scene.cameraLayerEnabled) return EvaluatedCamera()
        val base = if (scene.cameraBoxes.isNotEmpty()) {
            CameraBoxEngine.evaluateCameraBoxes(scene.cameraBoxes, globalFrame)
        } else {
            evaluateCamera(scene.cameraKeyframes, globalFrame)
        }
        val focusId = scene.cameraFocusClipId ?: return base
        val targetClip = scene.clips.firstOrNull { it.id == focusId } ?: return base
        val eval = evaluate(targetClip, globalFrame)
        val tx = (eval.x + if (targetClip.id == clipDeltaOverrideId) clipDeltaX else 0f).coerceIn(0.02f, 0.98f)
        val ty = (eval.y + if (targetClip.id == clipDeltaOverrideId) clipDeltaY else 0f).coerceIn(0.02f, 0.98f)
        val offsetX = base.x - 0.5f
        val offsetY = base.y - 0.5f
        return base.copy(
            x = (tx + offsetX).coerceIn(0.05f, 0.95f),
            y = (ty + offsetY).coerceIn(0.05f, 0.95f),
            trackedClipName = targetClip.name
        )
    }

    fun evaluateCamera(cameraKeyframes: List<CameraKeyframe>, globalFrame: Int): EvaluatedCamera {
        if (cameraKeyframes.isEmpty()) return EvaluatedCamera()
        val sorted = ensureSortedCameraKeyframes(cameraKeyframes)
        val first = sorted.first()
        if (sorted.size == 1 || globalFrame <= first.frame) {
            return EvaluatedCamera(first.x, first.y, first.zoom.coerceIn(0.35f, 3.5f), first.rotation)
        }
        val last = sorted.last()
        if (globalFrame >= last.frame) {
            return EvaluatedCamera(last.x, last.y, last.zoom.coerceIn(0.35f, 3.5f), last.rotation)
        }
        var prev = first
        var next = last
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (globalFrame in a.frame..b.frame) {
                prev = a
                next = b
                break
            }
        }
        val span = (next.frame - prev.frame).coerceAtLeast(1)
        val rawT = ((globalFrame - prev.frame).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        val t = evaluateEasing(
            easing = prev.easing,
            rawT = rawT,
            p1x = prev.easingP1X,
            p1y = prev.easingP1Y,
            p2x = prev.easingP2X,
            p2y = prev.easingP2Y,
            isAtEnd = globalFrame >= next.frame
        )
        return EvaluatedCamera(
            x = lerp(prev.x, next.x, t),
            y = lerp(prev.y, next.y, t),
            zoom = lerp(prev.zoom, next.zoom, t).coerceIn(0.35f, 3.5f),
            rotation = lerp(prev.rotation, next.rotation, t)
        )
    }

    fun sampleMotionPath(clip: TimelineClip, steps: Int = 24): List<NormalizedPoint> {
        if (clip.motionPathControlPoints.size >= 3) {
            val result = ArrayList<NormalizedPoint>(steps + 1)
            for (i in 0..steps) {
                val u = i.toFloat() / steps.coerceAtLeast(1).toFloat()
                result.add(evaluateBezierPath(clip.motionPathControlPoints, u))
            }
            return result
        }
        val sorted = ensureSortedTransformKeyframes(clip.keyframes)
        if (sorted.size < 2) return sorted.map { NormalizedPoint(it.x, it.y) }
        val startF = sorted.first().frame
        val endF = sorted.last().frame
        val span = (endF - startF).coerceAtLeast(1)
        val result = ArrayList<NormalizedPoint>(steps + 1)
        for (i in 0..steps) {
            val frame = startF + ((span * i) / steps)
            val eval = evaluateKeyframes(sorted, frame)
            result.add(NormalizedPoint(eval.x, eval.y))
        }
        return result
    }

    private fun ensureSortedTransformKeyframes(list: List<TransformKeyframe>): List<TransformKeyframe> {
        if (list.size <= 1) return list
        for (i in 0 until list.size - 1) {
            if (list[i].frame > list[i + 1].frame) {
                return list.sortedBy { it.frame }
            }
        }
        return list
    }

    private fun ensureSortedCameraKeyframes(list: List<CameraKeyframe>): List<CameraKeyframe> {
        if (list.size <= 1) return list
        for (i in 0 until list.size - 1) {
            if (list[i].frame > list[i + 1].frame) {
                return list.sortedBy { it.frame }
            }
        }
        return list
    }

    private fun applyTransitions(
        base: EvaluatedTransform,
        clip: TimelineClip,
        localFrame: Int
    ): EvaluatedTransform {
        var result = base
        val transLen = clip.transitionFrames.coerceIn(1, (clip.durationFrames / 2).coerceAtLeast(1))

        if (clip.transitionIn != TransitionType.NONE && localFrame < transLen) {
            val p = (localFrame.toFloat() / transLen.toFloat()).coerceIn(0f, 1f)
            result = applyTransitionFactor(result, clip.transitionIn, p)
        }
        val framesFromEnd = (clip.durationFrames - 1 - localFrame).coerceAtLeast(0)
        if (clip.transitionOut != TransitionType.NONE && framesFromEnd < transLen) {
            val p = (framesFromEnd.toFloat() / transLen.toFloat()).coerceIn(0f, 1f)
            result = applyTransitionFactor(result, clip.transitionOut, p)
        }
        return result
    }

    private fun applyTransitionFactor(
        t: EvaluatedTransform,
        type: TransitionType,
        progress: Float
    ): EvaluatedTransform {
        val smooth = (0.5f - 0.5f * cos(progress * PI)).toFloat()
        return when (type) {
            TransitionType.NONE -> t
            TransitionType.FADE -> t.copy(opacity = (t.opacity * smooth).coerceIn(0f, 1f))
            TransitionType.SLIDE_LEFT -> t.copy(
                x = t.x - (1f - smooth) * 0.35f,
                opacity = (t.opacity * smooth).coerceIn(0f, 1f)
            )
            TransitionType.SLIDE_UP -> t.copy(
                y = t.y + (1f - smooth) * 0.28f,
                opacity = (t.opacity * smooth).coerceIn(0f, 1f)
            )
            TransitionType.ZOOM_POP -> {
                val scaleMult = 0.35f + 0.65f * smooth
                t.copy(
                    scaleX = t.scaleX * scaleMult,
                    scaleY = t.scaleY * scaleMult,
                    opacity = (t.opacity * smooth).coerceIn(0f, 1f)
                )
            }
        }
    }

    private fun TransformKeyframe.toEvaluated() = EvaluatedTransform(
        x = x,
        y = y,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        opacity = opacity.coerceIn(0f, 1f),
        poseOffsets = poseOffsets,
        expressionOverride = expressionOverride,
        facialPresetId = facialPresetId,
        mouthShapeOverride = mouthShapeOverride,
        eyeShapeOverride = eyeShapeOverride,
        eyebrowShapeOverride = eyebrowShapeOverride,
        handPoseOverride = handPoseOverride,
        armGestureOverride = armGestureOverride,
        blinkOverride = blinkOverride
    )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Evaluates natural, non-robotic interpolation curves for any [EasingType],
     * including physics-inspired Bounce and Elastic curves and an arbitrary cubic Bézier
     * defined by (0,0) -> (p1x,p1y) -> (p2x,p2y) -> (1,1).
     */
    fun evaluateEasing(
        easing: EasingType,
        rawT: Float,
        p1x: Float = 0.42f,
        p1y: Float = 0f,
        p2x: Float = 0.58f,
        p2y: Float = 1f,
        isAtEnd: Boolean = rawT >= 1f
    ): Float {
        val x = rawT.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f && easing != EasingType.HOLD) return 1f
        return when (easing) {
            EasingType.HOLD -> if (isAtEnd) 1f else 0f
            EasingType.LINEAR -> x
            EasingType.EASE_IN -> x * x * x
            EasingType.EASE_OUT -> {
                val inv = 1f - x
                1f - inv * inv * inv
            }
            EasingType.EASE_IN_OUT -> (0.5f - 0.5f * cos(x * PI)).toFloat()
            EasingType.BOUNCE -> easeOutBounce(x)
            EasingType.ELASTIC -> easeOutElastic(x)
            EasingType.CUSTOM_CUBIC -> evaluateCubicBezier(
                x = x,
                p1x = p1x.coerceIn(0.01f, 0.99f),
                p1y = p1y.coerceIn(-0.6f, 1.6f),
                p2x = p2x.coerceIn(0.01f, 0.99f),
                p2y = p2y.coerceIn(-0.6f, 1.6f)
            )
        }
    }

    /**
     * Default cubic Bézier control handles representing each preset curve for visual editing.
     */
    fun defaultBezierHandles(easing: EasingType): FloatArray = when (easing) {
        EasingType.LINEAR -> floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f)
        EasingType.HOLD -> floatArrayOf(0.95f, 0.0f, 0.99f, 0.0f)
        EasingType.EASE_IN -> floatArrayOf(0.55f, 0.0f, 0.90f, 0.25f)
        EasingType.EASE_OUT -> floatArrayOf(0.10f, 0.75f, 0.45f, 1.0f)
        EasingType.EASE_IN_OUT -> floatArrayOf(0.42f, 0.0f, 0.58f, 1.0f)
        EasingType.BOUNCE -> floatArrayOf(0.30f, 1.25f, 0.65f, 0.92f)
        EasingType.ELASTIC -> floatArrayOf(0.22f, 1.45f, 0.48f, 0.88f)
        EasingType.CUSTOM_CUBIC -> floatArrayOf(0.34f, 1.32f, 0.64f, 1.0f)
    }

    private fun easeOutBounce(x: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> {
                val u = x - 1.5f / d1
                n1 * u * u + 0.75f
            }
            x < 2.5f / d1 -> {
                val u = x - 2.25f / d1
                n1 * u * u + 0.9375f
            }
            else -> {
                val u = x - 2.625f / d1
                n1 * u * u + 0.984375f
            }
        }
    }

    private fun easeOutElastic(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val c4 = (2.0 * PI) / 3.0
        val value = 2.0.pow(-10.0 * x) * sin((x * 10.0 - 0.75) * c4) + 1.0
        return value.toFloat()
    }

    private fun evaluateCubicBezier(
        x: Float,
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float
    ): Float {
        // Solve B_x(u) = x via Newton-Raphson with binary-search fallback for guaranteed convergence
        var u = x
        val ax = 3f * p1x - 3f * p2x + 1f
        val bx = -6f * p1x + 3f * p2x
        val cx = 3f * p1x

        for (i in 0 until 6) {
            val xu = ((ax * u + bx) * u + cx) * u
            val err = xu - x
            if (abs(err) < 1e-4f) break
            val dxu = (3f * ax * u + 2f * bx) * u + cx
            if (abs(dxu) < 1e-5f) break
            u = (u - err / dxu).coerceIn(0f, 1f)
        }
        // Refine if needed via bisection
        val checkX = ((ax * u + bx) * u + cx) * u
        if (abs(checkX - x) > 1e-3f) {
            var lo = 0f
            var hi = 1f
            for (i in 0 until 12) {
                val mid = (lo + hi) * 0.5f
                val xm = ((ax * mid + bx) * mid + cx) * mid
                if (xm < x) lo = mid else hi = mid
            }
            u = (lo + hi) * 0.5f
        }
        val ay = 3f * p1y - 3f * p2y + 1f
        val by = -6f * p1y + 3f * p2y
        val cy = 3f * p1y
        return ((ay * u + by) * u + cy) * u
    }
}
