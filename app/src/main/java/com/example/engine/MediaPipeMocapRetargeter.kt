package com.example.engine

import com.example.model.*
import com.example.viewmodel.ProjectMutator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Capture mode for 100% Offline On-Device Motion Capture.
 */
enum class MocapCaptureMode(val displayName: String, val requiredModelsSummary: String) {
    FACE_ONLY("Face Mocap", "face_landmarker.task"),
    BODY_AND_HANDS("Body + Hands", "pose_landmarker_lite.task + hand_landmarker.task"),
    FULL_FACE_BODY_HANDS("Full Studio", "face + pose + hand .task models")
}

/**
 * Palm gesture classes inferred from MediaPipe 21-point HandLandmarker finger curl ratios.
 */
enum class MocapPalmGesture(val displayName: String, val mappedHandPose: HandPoseType) {
    OPEN("Open", HandPoseType.OPEN),
    FIST("Fist", HandPoseType.FIST),
    POINT("Point", HandPoseType.POINT),
    PEACE("Peace", HandPoseType.POINT),
    HOLD("Hold", HandPoseType.PROP_GRIP),
    NAMASKAR("Namaskar", HandPoseType.OPEN)
}

/**
 * Lightweight normalized 3D landmark coordinate (MediaPipe `0..1` image space).
 * Pure Kotlin/JVM model so all retargeting & smoothing math is 100% JVM unit-testable.
 */
data class MocapLandmark3D(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f
)

/**
 * Evaluated face-only retargeted rig state from 478 landmarks + 52 ARKit blendshapes.
 */
data class MocapFaceSample(
    val blinkLeft: Boolean = false,
    val blinkRight: Boolean = false,
    val isBlinking: Boolean = false,
    val gazeTarget: GazeTarget = GazeTarget(),
    val headTiltDeg: Float = 0f,
    val headNodDeg: Float = 0f,
    val expression: FacialExpression = FacialExpression.NEUTRAL,
    val mouthOverride: MouthShapeOverride = MouthShapeOverride.NEUTRAL,
    val eyeOverride: EyeShapeOverride = EyeShapeOverride.NORMAL,
    val eyebrowOverride: EyebrowShapeOverride = EyebrowShapeOverride.NEUTRAL,
    val visemeCode: String = "Lips_M",
    val mthCode: String = "mth1"
)

/**
 * Unified retargeted 2D FrameCraft rig frame produced by KalidoKit-style MediaPipe retargeting.
 */
data class MocapRetargetedFrame(
    val timestampMs: Long = 0L,
    val poseOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
    val expression: FacialExpression = FacialExpression.NEUTRAL,
    val mouthOverride: MouthShapeOverride = MouthShapeOverride.NEUTRAL,
    val eyeOverride: EyeShapeOverride = EyeShapeOverride.NORMAL,
    val eyebrowOverride: EyebrowShapeOverride = EyebrowShapeOverride.NEUTRAL,
    val blink: Boolean = false,
    val gazeTarget: GazeTarget = GazeTarget(),
    val headTiltDeg: Float = 0f,
    val handPose: HandPoseType = HandPoseType.OPEN,
    val palmGesture: MocapPalmGesture = MocapPalmGesture.OPEN,
    val visemeCode: String = "Lips_M",
    val mthCode: String = "mth1",
    val hasFaceData: Boolean = false,
    val hasPoseData: Boolean = false,
    val hasHandData: Boolean = false
)

/**
 * Track selection toggles when baking recorded mocap frames onto a character timeline clip.
 */
data class MocapBakeConfig(
    val sampleEveryNFrames: Int = 2,
    val bakeIkPose: Boolean = true,
    val bakeExpression: Boolean = true,
    val bakeHands: Boolean = true,
    val bakeLegs: Boolean = true,
    val enforceJointLimits: Boolean = true
)

/**
 * Exponential Moving Average (EMA) smoother eliminating webcam/video landmark jitter.
 *
 * `alpha` in `0.10..0.95`: lower = silkier/heavier smoothing, higher = snappier response.
 */
class MocapEmaSmoother(initialAlpha: Float = 0.38f) {
    var alpha: Float = initialAlpha.coerceIn(0.10f, 0.95f)
        set(value) {
            field = value.coerceIn(0.10f, 0.95f)
        }

    private var previous: MocapRetargetedFrame? = null
    private var blinkHoldCounter: Int = 0

    fun reset() {
        previous = null
        blinkHoldCounter = 0
    }

    private fun ema(prev: Float, next: Float, a: Float): Float = prev + (next - prev) * a

    fun smooth(raw: MocapRetargetedFrame): MocapRetargetedFrame {
        val prev = previous
        if (prev == null) {
            previous = raw
            return raw
        }
        val a = alpha
        val smoothedPose = PoseKeyframeOffsets(
            headNodOffset = ema(prev.poseOffsets.headNodOffset, raw.poseOffsets.headNodOffset, a),
            torsoTiltOffset = ema(prev.poseOffsets.torsoTiltOffset, raw.poseOffsets.torsoTiltOffset, a),
            leftArmOffset = ema(prev.poseOffsets.leftArmOffset, raw.poseOffsets.leftArmOffset, a),
            leftForearmOffset = ema(prev.poseOffsets.leftForearmOffset, raw.poseOffsets.leftForearmOffset, a),
            rightArmOffset = ema(prev.poseOffsets.rightArmOffset, raw.poseOffsets.rightArmOffset, a),
            rightForearmOffset = ema(prev.poseOffsets.rightForearmOffset, raw.poseOffsets.rightForearmOffset, a),
            leftLegOffset = ema(prev.poseOffsets.leftLegOffset, raw.poseOffsets.leftLegOffset, a),
            leftKneeOffset = ema(prev.poseOffsets.leftKneeOffset, raw.poseOffsets.leftKneeOffset, a),
            rightLegOffset = ema(prev.poseOffsets.rightLegOffset, raw.poseOffsets.rightLegOffset, a),
            rightKneeOffset = ema(prev.poseOffsets.rightKneeOffset, raw.poseOffsets.rightKneeOffset, a)
        )
        val smoothedGaze = GazeTarget(
            x = ema(prev.gazeTarget.x, raw.gazeTarget.x, a).coerceIn(-1f, 1f),
            y = ema(prev.gazeTarget.y, raw.gazeTarget.y, a).coerceIn(-1f, 1f)
        )
        val smoothedHeadTilt = ema(prev.headTiltDeg, raw.headTiltDeg, a).coerceIn(-45f, 45f)

        // Small hysteresis on blink to prevent 1-frame flicker
        val stableBlink = if (raw.blink) {
            blinkHoldCounter = 2
            true
        } else if (blinkHoldCounter > 0) {
            blinkHoldCounter--
            true
        } else {
            false
        }

        val out = raw.copy(
            poseOffsets = smoothedPose,
            gazeTarget = smoothedGaze,
            headTiltDeg = smoothedHeadTilt,
            blink = stableBlink
        )
        previous = out
        return out
    }
}

/**
 * KalidoKit-Style 2D Rig Retargeting Engine for 100% Offline MediaPipe Face + Pose + Hand Mocap.
 *
 * - Face (`FaceLandmarker` — 478 landmarks + 52 ARKit blendshapes + optional 4x4 head matrix)
 * - Body + Legs (`PoseLandmarker` — 33 landmarks -> clamped FrameCraft `ikJointOffsets`)
 * - Hand/Palm (`HandLandmarker` — 21 landmarks -> finger curl ratios -> 6 Palm Gestures)
 * - Deterministic Timeline Keyframe Baker with Undo-friendly `ProjectDocument` return.
 */
object MediaPipeMocapRetargeter {

    // MediaPipe Pose landmark indices
    private const val POSE_NOSE = 0
    private const val POSE_LEFT_SHOULDER = 11
    private const val POSE_RIGHT_SHOULDER = 12
    private const val POSE_LEFT_ELBOW = 13
    private const val POSE_RIGHT_ELBOW = 14
    private const val POSE_LEFT_WRIST = 15
    private const val POSE_RIGHT_WRIST = 16
    private const val POSE_LEFT_HIP = 23
    private const val POSE_RIGHT_HIP = 24
    private const val POSE_LEFT_KNEE = 25
    private const val POSE_RIGHT_KNEE = 26
    private const val POSE_LEFT_ANKLE = 27
    private const val POSE_RIGHT_ANKLE = 28

    // MediaPipe Face iris & eye corner indices (478-landmark model)
    private const val FACE_LEFT_EYE_OUTER = 33
    private const val FACE_LEFT_EYE_INNER = 133
    private const val FACE_RIGHT_EYE_INNER = 362
    private const val FACE_RIGHT_EYE_OUTER = 263
    private const val FACE_LEFT_IRIS = 468
    private const val FACE_RIGHT_IRIS = 473
    private const val FACE_FOREHEAD = 10
    private const val FACE_CHIN = 152

    /**
     * Retargets MediaPipe FaceLandmarker 52 ARKit blendshapes + 478 landmarks + optional 4x4 head matrix
     * into FrameCraft facial rig channels (`blink`, `gazeTarget`, `headTilt`, `eyebrow`, `mouth` mth1-mth4 / Lips_A..X).
     */
    fun retargetFace(
        blendshapes: Map<String, Float>,
        faceLandmarks: List<MocapLandmark3D> = emptyList(),
        headMatrix4x4: FloatArray? = null
    ): MocapFaceSample {
        fun bs(name: String): Float = (blendshapes[name] ?: 0f).coerceIn(0f, 1f)

        // 1. Eye Blink detection
        val blinkLScore = bs("eyeBlinkLeft")
        val blinkRScore = bs("eyeBlinkRight")
        val blinkL = blinkLScore >= 0.48f
        val blinkR = blinkRScore >= 0.48f
        val isBlinking = blinkL && blinkR

        // 2. Eye Gaze Target (prefer 478 iris landmarks; fallback to ARKit eyeLook blendshapes)
        var gazeX = (bs("eyeLookOutRight") + bs("eyeLookInLeft") - bs("eyeLookOutLeft") - bs("eyeLookInRight")) * 0.85f
        var gazeY = (bs("eyeLookDownLeft") + bs("eyeLookDownRight") - bs("eyeLookUpLeft") - bs("eyeLookUpRight")) * 0.85f
        if (faceLandmarks.size >= 474) {
            val lOuter = faceLandmarks[FACE_LEFT_EYE_OUTER]
            val lInner = faceLandmarks[FACE_LEFT_EYE_INNER]
            val lIris = faceLandmarks[FACE_LEFT_IRIS]
            val rInner = faceLandmarks[FACE_RIGHT_EYE_INNER]
            val rOuter = faceLandmarks[FACE_RIGHT_EYE_OUTER]
            val rIris = faceLandmarks[FACE_RIGHT_IRIS]

            val lSpan = (lInner.x - lOuter.x).takeIf { abs(it) > 0.002f } ?: 0.04f
            val rSpan = (rOuter.x - rInner.x).takeIf { abs(it) > 0.002f } ?: 0.04f
            val lNorm = ((lIris.x - (lOuter.x + lInner.x) * 0.5f) / (lSpan * 0.5f)).coerceIn(-1f, 1f)
            val rNorm = ((rIris.x - (rInner.x + rOuter.x) * 0.5f) / (rSpan * 0.5f)).coerceIn(-1f, 1f)
            gazeX = ((lNorm + rNorm) * 0.5f).coerceIn(-1f, 1f)

            val eyeMidY = ((lOuter.y + lInner.y + rInner.y + rOuter.y) * 0.25f)
            val irisMidY = ((lIris.y + rIris.y) * 0.5f)
            val eyeH = (abs(lSpan) * 0.35f).coerceAtLeast(0.006f)
            gazeY = ((irisMidY - eyeMidY) / eyeH).coerceIn(-1f, 1f)
        }
        val gaze = GazeTarget(gazeX.coerceIn(-1f, 1f), gazeY.coerceIn(-1f, 1f))

        // 3. Head Tilt (Roll) & Head Nod (Pitch) from 4x4 transformation matrix or landmark fallback
        var headTiltDeg = 0f
        var headNodDeg = 0f
        if (headMatrix4x4 != null && headMatrix4x4.size >= 16) {
            // Column-major 4x4 rotation matrix extraction (KalidoKit convention)
            val m00 = headMatrix4x4[0]
            val m10 = headMatrix4x4[1]
            val m20 = headMatrix4x4[2]
            val m21 = headMatrix4x4[6]
            val m22 = headMatrix4x4[10]
            val rollRad = atan2(m10.toDouble(), m00.toDouble())
            val pitchRad = atan2(m21.toDouble(), m22.toDouble())
            headTiltDeg = (rollRad * 180.0 / PI).toFloat().coerceIn(-42f, 42f)
            headNodDeg = (pitchRad * 180.0 / PI).toFloat().coerceIn(-38f, 38f)
            // Keep yaw safely unused unless needed for extreme tilt
            val _yawUnused = asin((-m20).coerceIn(-1f, 1f).toDouble())
            if (_yawUnused.isNaN()) headTiltDeg = 0f
        } else if (faceLandmarks.size > FACE_RIGHT_EYE_OUTER) {
            val lEye = faceLandmarks[FACE_LEFT_EYE_OUTER]
            val rEye = faceLandmarks[FACE_RIGHT_EYE_OUTER]
            headTiltDeg = (atan2((rEye.y - lEye.y).toDouble(), (rEye.x - lEye.x).toDouble()) * 180.0 / PI)
                .toFloat()
                .coerceIn(-42f, 42f)
            val forehead = faceLandmarks[FACE_FOREHEAD]
            val chin = faceLandmarks[FACE_CHIN]
            val dz = chin.z - forehead.z
            headNodDeg = (dz * 120f).coerceIn(-38f, 38f)
        }

        // 4. Eyebrow expression from browDown / browInnerUp / browOuterUp
        val browDown = (bs("browDownLeft") + bs("browDownRight")) * 0.5f
        val browInnerUp = bs("browInnerUp")
        val browOuterUp = (bs("browOuterUpLeft") + bs("browOuterUpRight")) * 0.5f
        val eyebrowOverride = when {
            browDown >= 0.42f -> EyebrowShapeOverride.FURROWED_ANGRY
            browInnerUp >= 0.48f && browOuterUp < 0.30f -> EyebrowShapeOverride.WORRIED_SAD
            browInnerUp >= 0.42f || browOuterUp >= 0.48f -> EyebrowShapeOverride.RAISED_SURPRISE
            else -> EyebrowShapeOverride.NEUTRAL
        }

        // 5. Mouth -> mth1-mth4 / Lips_A..X + MouthShapeOverride
        val jawOpen = bs("jawOpen")
        val mouthSmile = (bs("mouthSmileLeft") + bs("mouthSmileRight")) * 0.5f
        val mouthFunnel = maxOf(bs("mouthFunnel"), bs("mouthPucker"))
        val mouthFrown = (bs("mouthFrownLeft") + bs("mouthFrownRight")) * 0.5f

        val (mouthOverride, visemeCode, mthCode) = when {
            jawOpen >= 0.45f && mouthFunnel >= 0.35f -> Triple(MouthShapeOverride.SHOCK_O, "Lips_O", "mth4")
            jawOpen >= 0.52f && mouthSmile >= 0.35f -> Triple(MouthShapeOverride.OPEN_LAUGH, "Lips_A", "mth4")
            jawOpen >= 0.32f -> Triple(MouthShapeOverride.TALK_AA, "Lips_A", "mth3")
            mouthFunnel >= 0.42f -> Triple(MouthShapeOverride.SHOCK_O, "Lips_U", "mth2")
            jawOpen >= 0.16f && mouthSmile >= 0.22f -> Triple(MouthShapeOverride.TALK_EE, "Lips_E", "mth2")
            mouthSmile >= 0.42f -> Triple(MouthShapeOverride.SMILE, "Lips_E", "mth2")
            mouthFrown >= 0.38f -> Triple(MouthShapeOverride.FROWN, "Lips_X", "mth1")
            browDown >= 0.50f && jawOpen >= 0.12f -> Triple(MouthShapeOverride.GRIT_ANGRY, "Lips_E", "mth2")
            else -> Triple(MouthShapeOverride.NEUTRAL, "Lips_M", "mth1")
        }

        // 6. Eye shape + holistic FacialExpression classification
        val eyeWide = (bs("eyeWideLeft") + bs("eyeWideRight")) * 0.5f
        val eyeSquint = (bs("eyeSquintLeft") + bs("eyeSquintRight")) * 0.5f
        val eyeOverride = when {
            isBlinking -> EyeShapeOverride.CLOSED_SLEEP
            blinkR && !blinkL -> EyeShapeOverride.WINK_RIGHT
            eyeWide >= 0.45f -> EyeShapeOverride.WIDE_SHOCK
            browDown >= 0.45f || eyeSquint >= 0.55f -> EyeShapeOverride.SQUINT_ANGRY
            mouthSmile >= 0.55f && eyeSquint >= 0.25f -> EyeShapeOverride.HAPPY_ARC
            eyebrowOverride == EyebrowShapeOverride.WORRIED_SAD -> EyeShapeOverride.SAD_DROOP
            else -> EyeShapeOverride.NORMAL
        }

        val expression = when {
            browDown >= 0.48f -> FacialExpression.ANGRY
            jawOpen >= 0.45f && mouthSmile >= 0.45f -> FacialExpression.LAUGHING
            eyeWide >= 0.45f && jawOpen >= 0.35f -> FacialExpression.SHOCKED
            mouthSmile >= 0.42f -> FacialExpression.HAPPY
            mouthFrown >= 0.40f || eyebrowOverride == EyebrowShapeOverride.WORRIED_SAD -> FacialExpression.SAD
            jawOpen >= 0.22f -> FacialExpression.TALKING
            else -> FacialExpression.NEUTRAL
        }

        return MocapFaceSample(
            blinkLeft = blinkL,
            blinkRight = blinkR,
            isBlinking = isBlinking,
            gazeTarget = gaze,
            headTiltDeg = headTiltDeg,
            headNodDeg = headNodDeg,
            expression = expression,
            mouthOverride = mouthOverride,
            eyeOverride = eyeOverride,
            eyebrowOverride = eyebrowOverride,
            visemeCode = visemeCode,
            mthCode = mthCode
        )
    }

    /**
     * Converts MediaPipe PoseLandmarker 33 landmarks into FrameCraft `PoseKeyframeOffsets`,
     * clamped safely via [IkRigSolver.clampPoseOffsets] (`AnatomicalJointLimits`).
     */
    fun retargetPose(
        poseLandmarks: List<MocapLandmark3D>,
        enforceJointLimits: Boolean = true,
        orientation: CharacterOrientation = CharacterOrientation.FRONT,
        mirroredCamera: Boolean = true
    ): PoseKeyframeOffsets {
        if (poseLandmarks.size < 29) return PoseKeyframeOffsets()

        fun lm(idx: Int): MocapLandmark3D = poseLandmarks[idx]
        fun angleFromDownDeg(from: MocapLandmark3D, to: MocapLandmark3D): Float {
            val dx = (to.x - from.x) * if (mirroredCamera) -1f else 1f
            val dy = to.y - from.y
            return (atan2(dx.toDouble(), dy.toDouble()) * 180.0 / PI).toFloat()
        }

        val lSh = lm(POSE_LEFT_SHOULDER)
        val rSh = lm(POSE_RIGHT_SHOULDER)
        val lEl = lm(POSE_LEFT_ELBOW)
        val rEl = lm(POSE_RIGHT_ELBOW)
        val lWr = lm(POSE_LEFT_WRIST)
        val rWr = lm(POSE_RIGHT_WRIST)
        val lHp = lm(POSE_LEFT_HIP)
        val rHp = lm(POSE_RIGHT_HIP)
        val lKn = lm(POSE_LEFT_KNEE)
        val rKn = lm(POSE_RIGHT_KNEE)
        val lAn = lm(POSE_LEFT_ANKLE)
        val rAn = lm(POSE_RIGHT_ANKLE)
        val nose = lm(POSE_NOSE)

        // Torso tilt from hip midpoint -> shoulder midpoint
        val shMid = MocapLandmark3D((lSh.x + rSh.x) * 0.5f, (lSh.y + rSh.y) * 0.5f)
        val hpMid = MocapLandmark3D((lHp.x + rHp.x) * 0.5f, (lHp.y + rHp.y) * 0.5f)
        val torsoDx = (shMid.x - hpMid.x) * if (mirroredCamera) -1f else 1f
        val torsoDy = (hpMid.y - shMid.y).coerceAtLeast(0.02f)
        val torsoTilt = (atan2(torsoDx.toDouble(), torsoDy.toDouble()) * 180.0 / PI).toFloat()

        // Head nod estimate from nose relative to shoulders
        val headDx = (nose.x - shMid.x) * if (mirroredCamera) -1f else 1f
        val headDy = (shMid.y - nose.y).coerceAtLeast(0.02f)
        val headAngle = (atan2(headDx.toDouble(), headDy.toDouble()) * 180.0 / PI).toFloat() - torsoTilt

        // Arms: shoulder-to-elbow & elbow-to-wrist relative angles
        val lUpperDeg = angleFromDownDeg(lSh, lEl)
        val lLowerDeg = angleFromDownDeg(lEl, lWr)
        val rUpperDeg = angleFromDownDeg(rSh, rEl)
        val rLowerDeg = angleFromDownDeg(rEl, rWr)

        // Map to FrameCraft rig sign convention (leftArmOffset positive raises left arm outward/up, rightArmOffset negative raises right arm outward/up)
        val leftArmOffset = (-lUpperDeg - 14f)
        val leftForearmOffset = (-(lLowerDeg - lUpperDeg))
        val rightArmOffset = (-rUpperDeg + 14f)
        val rightForearmOffset = (-(rLowerDeg - rUpperDeg))

        // Legs: hip-to-knee & knee-to-ankle (only when legs have reasonable visibility)
        val legVis = minOf(lHp.visibility, rHp.visibility, lKn.visibility, rKn.visibility)
        val leftLegOffset: Float
        val leftKneeOffset: Float
        val rightLegOffset: Float
        val rightKneeOffset: Float
        if (legVis >= 0.35f) {
            val lThigh = angleFromDownDeg(lHp, lKn)
            val lShin = angleFromDownDeg(lKn, lAn)
            val rThigh = angleFromDownDeg(rHp, rKn)
            val rShin = angleFromDownDeg(rKn, rAn)
            leftLegOffset = -lThigh
            leftKneeOffset = (lShin - lThigh)
            rightLegOffset = -rThigh
            rightKneeOffset = (rShin - rThigh)
        } else {
            leftLegOffset = 0f
            leftKneeOffset = 0f
            rightLegOffset = 0f
            rightKneeOffset = 0f
        }

        val raw = PoseKeyframeOffsets(
            headNodOffset = headAngle,
            torsoTiltOffset = torsoTilt,
            leftArmOffset = leftArmOffset,
            leftForearmOffset = leftForearmOffset,
            rightArmOffset = rightArmOffset,
            rightForearmOffset = rightForearmOffset,
            leftLegOffset = leftLegOffset,
            leftKneeOffset = leftKneeOffset,
            rightLegOffset = rightLegOffset,
            rightKneeOffset = rightKneeOffset
        )
        return IkRigSolver.clampPoseOffsets(raw, enforceJointLimits = enforceJointLimits, orientation = orientation)
    }

    /**
     * Classifies MediaPipe HandLandmarker 21-point landmarks into FrameCraft Palm Gestures
     * (`Open`, `Fist`, `Point`, `Peace`, `Hold`, `Namaskar`) using finger curl ratios.
     */
    fun classifyPalmGesture(
        primaryHandLandmarks: List<MocapLandmark3D>,
        secondaryHandLandmarks: List<MocapLandmark3D>? = null
    ): MocapPalmGesture {
        if (primaryHandLandmarks.size < 21) return MocapPalmGesture.OPEN

        fun dist(a: MocapLandmark3D, b: MocapLandmark3D): Float =
            hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

        // Check two-hand Namaskar (palms pressed close together vertically)
        if (secondaryHandLandmarks != null && secondaryHandLandmarks.size >= 21) {
            val wristDist = dist(primaryHandLandmarks[0], secondaryHandLandmarks[0])
            val tipDist = dist(primaryHandLandmarks[12], secondaryHandLandmarks[12])
            if (wristDist < 0.14f && tipDist < 0.14f) {
                return MocapPalmGesture.NAMASKAR
            }
        }

        val wrist = primaryHandLandmarks[0]
        // Finger extension ratio = dist(tip, wrist) / dist(pip, wrist). > 1.25 => extended, < 1.05 => curled
        fun extensionRatio(pipIdx: Int, tipIdx: Int): Float {
            val pipD = dist(primaryHandLandmarks[pipIdx], wrist).coerceAtLeast(0.005f)
            val tipD = dist(primaryHandLandmarks[tipIdx], wrist)
            return tipD / pipD
        }

        val indexRatio = extensionRatio(6, 8)
        val middleRatio = extensionRatio(10, 12)
        val ringRatio = extensionRatio(14, 16)
        val pinkyRatio = extensionRatio(18, 20)

        val indexExt = indexRatio > 1.22f
        val middleExt = middleRatio > 1.22f
        val ringExt = ringRatio > 1.20f
        val pinkyExt = pinkyRatio > 1.18f
        val extendedCount = listOf(indexExt, middleExt, ringExt, pinkyExt).count { it }

        // Pinch / Hold check (thumb tip near index tip with other fingers partially curled)
        val thumbIndexPinch = dist(primaryHandLandmarks[4], primaryHandLandmarks[8])
        val palmSpan = dist(wrist, primaryHandLandmarks[9]).coerceAtLeast(0.02f)
        val isPinchHold = (thumbIndexPinch / palmSpan) < 0.42f

        return when {
            extendedCount == 4 -> MocapPalmGesture.OPEN
            indexExt && middleExt && !ringExt && !pinkyExt -> MocapPalmGesture.PEACE
            indexExt && !middleExt && !ringExt && !pinkyExt -> MocapPalmGesture.POINT
            isPinchHold -> MocapPalmGesture.HOLD
            extendedCount == 0 -> {
                val avgRatio = (indexRatio + middleRatio + ringRatio + pinkyRatio) * 0.25f
                if (avgRatio > 0.96f) MocapPalmGesture.HOLD else MocapPalmGesture.FIST
            }
            else -> MocapPalmGesture.HOLD
        }
    }

    /**
     * Combines Face + Pose + Hand landmarks into a single unified [MocapRetargetedFrame].
     */
    fun retargetCombinedFrame(
        timestampMs: Long,
        blendshapes: Map<String, Float> = emptyMap(),
        faceLandmarks: List<MocapLandmark3D> = emptyList(),
        headMatrix4x4: FloatArray? = null,
        poseLandmarks: List<MocapLandmark3D> = emptyList(),
        primaryHandLandmarks: List<MocapLandmark3D> = emptyList(),
        secondaryHandLandmarks: List<MocapLandmark3D>? = null,
        enforceJointLimits: Boolean = true,
        orientation: CharacterOrientation = CharacterOrientation.FRONT
    ): MocapRetargetedFrame {
        val hasFace = blendshapes.isNotEmpty() || faceLandmarks.size >= 264
        val hasPose = poseLandmarks.size >= 29
        val hasHand = primaryHandLandmarks.size >= 21

        val face = if (hasFace) retargetFace(blendshapes, faceLandmarks, headMatrix4x4) else MocapFaceSample()
        val poseBase = if (hasPose) {
            retargetPose(poseLandmarks, enforceJointLimits = enforceJointLimits, orientation = orientation)
        } else {
            PoseKeyframeOffsets()
        }
        val mergedPose = if (hasFace) {
            IkRigSolver.clampPoseOffsets(
                poseBase.copy(
                    headNodOffset = if (abs(face.headNodDeg) > 0.5f) face.headNodDeg else poseBase.headNodOffset
                ),
                enforceJointLimits = enforceJointLimits,
                orientation = orientation
            )
        } else {
            poseBase
        }
        val gesture = if (hasHand) classifyPalmGesture(primaryHandLandmarks, secondaryHandLandmarks) else MocapPalmGesture.OPEN

        // If Namaskar detected via hands, also bring forearms politely inward when pose isn't driving arms
        // Android Canvas FK: Left +arm = outward-left, -forearm = fold inward; Right -arm = outward-right, +forearm = fold inward
        val finalPose = if (gesture == MocapPalmGesture.NAMASKAR && !hasPose) {
            mergedPose.copy(
                leftArmOffset = 44f,
                leftForearmOffset = -110f,
                rightArmOffset = -44f,
                rightForearmOffset = 110f
            )
        } else {
            mergedPose
        }

        return MocapRetargetedFrame(
            timestampMs = timestampMs,
            poseOffsets = finalPose,
            expression = face.expression,
            mouthOverride = face.mouthOverride,
            eyeOverride = face.eyeOverride,
            eyebrowOverride = face.eyebrowOverride,
            blink = face.isBlinking,
            gazeTarget = face.gazeTarget,
            headTiltDeg = face.headTiltDeg,
            handPose = gesture.mappedHandPose,
            palmGesture = gesture,
            visemeCode = face.visemeCode,
            mthCode = face.mthCode,
            hasFaceData = hasFace,
            hasPoseData = hasPose,
            hasHandData = hasHand
        )
    }

    /**
     * Bakes a recorded sequence of smoothed [MocapRetargetedFrame]s onto [clipId] starting at
     * [startGlobalFrame] at the configured [MocapBakeConfig.sampleEveryNFrames] cadence.
     * Preserves unrelated tracks and returns an immutable updated [ProjectDocument] ready for Undo.
     */
    fun bakeMocapRecordingToProject(
        project: ProjectDocument,
        clipId: String,
        startGlobalFrame: Int,
        frames: List<MocapRetargetedFrame>,
        config: MocapBakeConfig = MocapBakeConfig(),
        fps: Int = project.fps
    ): Pair<ProjectDocument, Int> {
        if (frames.isEmpty()) return project to 0
        val clip = project.activeScene.clips.firstOrNull { it.id == clipId } ?: return project to 0
        val safeStep = config.sampleEveryNFrames.coerceIn(1, 6)
        val safeFps = fps.coerceAtLeast(12)
        val baseTs = frames.first().timestampMs

        var updatedProject = project
        var stampedCount = 0
        var lastStampedFrame = Int.MIN_VALUE

        for ((index, sample) in frames.withIndex()) {
            if (index % safeStep != 0 && index != frames.lastIndex) continue
            val deltaFrames = if (sample.timestampMs > baseTs) {
                (((sample.timestampMs - baseTs) / 1000.0) * safeFps).roundToInt()
            } else {
                index
            }
            val targetGlobalFrame = (startGlobalFrame + deltaFrames)
                .coerceIn(clip.startFrame, (clip.endFrame - 1).coerceAtLeast(clip.startFrame))
            if (targetGlobalFrame == lastStampedFrame) continue
            lastStampedFrame = targetGlobalFrame

            val baseline = KeyframeEvaluator.evaluate(clip, targetGlobalFrame)
            val basePose = baseline.poseOffsets
            val rawMergedPose = basePose.copy(
                headNodOffset = if (config.bakeIkPose && (sample.hasFaceData || sample.hasPoseData)) sample.poseOffsets.headNodOffset else basePose.headNodOffset,
                torsoTiltOffset = if (config.bakeIkPose && sample.hasPoseData) sample.poseOffsets.torsoTiltOffset else basePose.torsoTiltOffset,
                leftArmOffset = if (config.bakeHands && (sample.hasPoseData || sample.hasHandData)) sample.poseOffsets.leftArmOffset else basePose.leftArmOffset,
                leftForearmOffset = if (config.bakeHands && (sample.hasPoseData || sample.hasHandData)) sample.poseOffsets.leftForearmOffset else basePose.leftForearmOffset,
                rightArmOffset = if (config.bakeHands && (sample.hasPoseData || sample.hasHandData)) sample.poseOffsets.rightArmOffset else basePose.rightArmOffset,
                rightForearmOffset = if (config.bakeHands && (sample.hasPoseData || sample.hasHandData)) sample.poseOffsets.rightForearmOffset else basePose.rightForearmOffset,
                leftLegOffset = if (config.bakeLegs && sample.hasPoseData) sample.poseOffsets.leftLegOffset else basePose.leftLegOffset,
                leftKneeOffset = if (config.bakeLegs && sample.hasPoseData) sample.poseOffsets.leftKneeOffset else basePose.leftKneeOffset,
                rightLegOffset = if (config.bakeLegs && sample.hasPoseData) sample.poseOffsets.rightLegOffset else basePose.rightLegOffset,
                rightKneeOffset = if (config.bakeLegs && sample.hasPoseData) sample.poseOffsets.rightKneeOffset else basePose.rightKneeOffset
            )
            val finalPose = IkRigSolver.clampPoseOffsets(
                rawMergedPose,
                enforceJointLimits = config.enforceJointLimits,
                orientation = clip.orientation
            )

            updatedProject = ProjectMutator.upsertKeyframeAtFrame(updatedProject, clipId, targetGlobalFrame) { kf ->
                kf.copy(
                    easing = EasingType.LINEAR,
                    poseOffsets = finalPose,
                    expressionOverride = if (config.bakeExpression && sample.hasFaceData) sample.expression else baseline.expressionOverride,
                    mouthShapeOverride = if (config.bakeExpression && sample.hasFaceData) sample.mouthOverride else baseline.mouthShapeOverride,
                    eyeShapeOverride = if (config.bakeExpression && sample.hasFaceData) sample.eyeOverride else baseline.eyeShapeOverride,
                    eyebrowShapeOverride = if (config.bakeExpression && sample.hasFaceData) sample.eyebrowOverride else baseline.eyebrowShapeOverride,
                    blinkOverride = if (config.bakeExpression && sample.hasFaceData) sample.blink else baseline.blinkOverride,
                    handPoseOverride = if (config.bakeHands && sample.hasHandData) sample.handPose else baseline.handPoseOverride
                )
            }
            stampedCount++
        }

        return updatedProject to stampedCount
    }
}
