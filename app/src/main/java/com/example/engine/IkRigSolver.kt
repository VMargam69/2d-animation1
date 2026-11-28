package com.example.engine

import com.example.model.*
import kotlin.math.*

/**
 * Analytical 2-Bone Inverse Kinematics (Law of Cosines) solver, rig-aware stage handle
 * projector, pose mirroring utility, and camera screen-to-world coordinate transforms.
 */
object IkRigSolver {

    data class JointAngleLimit(
        val minDeg: Float,
        val maxDeg: Float
    ) {
        fun clamp(angle: Float): Float = angle.coerceIn(minDeg, maxDeg)
    }

    /**
     * Realistic anatomical joint rotation limits (in degrees) preventing backward elbow/knee
     * hyperextension or unnatural neck/hip inversion when `enforceJointLimits` is enabled.
     */
    object AnatomicalJointLimits {
        val NECK = JointAngleLimit(-42f, 42f)
        val TORSO = JointAngleLimit(-38f, 38f)
        val LEFT_SHOULDER = JointAngleLimit(-145f, 135f)
        val RIGHT_SHOULDER = JointAngleLimit(-135f, 145f)
        val LEFT_ELBOW_HINGE = JointAngleLimit(-142f, 0f)
        val RIGHT_ELBOW_HINGE = JointAngleLimit(0f, 142f)
        val ELBOW_SIDE_HINGE = JointAngleLimit(-142f, 0f)
        val HIP = JointAngleLimit(-105f, 95f)
        val KNEE_HINGE = JointAngleLimit(0f, 132f)
        val RIGHT_KNEE_FRONT_HINGE = JointAngleLimit(-132f, 0f)

        fun asMap(): Map<String, Pair<Float, Float>> = mapOf(
            "neck" to (NECK.minDeg to NECK.maxDeg),
            "torso" to (TORSO.minDeg to TORSO.maxDeg),
            "leftShoulder" to (LEFT_SHOULDER.minDeg to LEFT_SHOULDER.maxDeg),
            "rightShoulder" to (RIGHT_SHOULDER.minDeg to RIGHT_SHOULDER.maxDeg),
            "leftElbow" to (LEFT_ELBOW_HINGE.minDeg to LEFT_ELBOW_HINGE.maxDeg),
            "rightElbow" to (RIGHT_ELBOW_HINGE.minDeg to RIGHT_ELBOW_HINGE.maxDeg),
            "hip" to (HIP.minDeg to HIP.maxDeg),
            "knee" to (KNEE_HINGE.minDeg to KNEE_HINGE.maxDeg)
        )
    }

    /**
     * Applies anatomical joint rotation limits to [offsets] when [enforceJointLimits] is true,
     * or relaxed cartoon-bending limits when false. Used by both on-stage IK dragging and
     * `.fcscript.json` IK pose compilation.
     */
    fun clampPoseOffsets(
        offsets: PoseKeyframeOffsets,
        enforceJointLimits: Boolean = true,
        orientation: CharacterOrientation = CharacterOrientation.FRONT
    ): PoseKeyframeOffsets {
        if (!enforceJointLimits) {
            return PoseKeyframeOffsets(
                headNodOffset = offsets.headNodOffset.coerceIn(-75f, 75f),
                torsoTiltOffset = offsets.torsoTiltOffset.coerceIn(-68f, 68f),
                leftArmOffset = offsets.leftArmOffset.coerceIn(-175f, 175f),
                leftForearmOffset = offsets.leftForearmOffset.coerceIn(-170f, 170f),
                rightArmOffset = offsets.rightArmOffset.coerceIn(-175f, 175f),
                rightForearmOffset = offsets.rightForearmOffset.coerceIn(-170f, 170f),
                leftLegOffset = offsets.leftLegOffset.coerceIn(-155f, 155f),
                leftKneeOffset = offsets.leftKneeOffset.coerceIn(-165f, 165f),
                rightLegOffset = offsets.rightLegOffset.coerceIn(-155f, 155f),
                rightKneeOffset = offsets.rightKneeOffset.coerceIn(-165f, 165f)
            )
        }
        val isSideOr3Q = orientation == CharacterOrientation.SIDE ||
            orientation == CharacterOrientation.THREE_QUARTER_FRONT
        val rightElbowLimit = if (isSideOr3Q) AnatomicalJointLimits.ELBOW_SIDE_HINGE else AnatomicalJointLimits.RIGHT_ELBOW_HINGE
        val rightKneeLimit = if (isSideOr3Q) AnatomicalJointLimits.KNEE_HINGE else AnatomicalJointLimits.RIGHT_KNEE_FRONT_HINGE
        return PoseKeyframeOffsets(
            headNodOffset = AnatomicalJointLimits.NECK.clamp(offsets.headNodOffset),
            torsoTiltOffset = AnatomicalJointLimits.TORSO.clamp(offsets.torsoTiltOffset),
            leftArmOffset = AnatomicalJointLimits.LEFT_SHOULDER.clamp(offsets.leftArmOffset),
            leftForearmOffset = AnatomicalJointLimits.LEFT_ELBOW_HINGE.clamp(offsets.leftForearmOffset),
            rightArmOffset = AnatomicalJointLimits.RIGHT_SHOULDER.clamp(offsets.rightArmOffset),
            rightForearmOffset = rightElbowLimit.clamp(offsets.rightForearmOffset),
            leftLegOffset = AnatomicalJointLimits.HIP.clamp(offsets.leftLegOffset),
            leftKneeOffset = AnatomicalJointLimits.KNEE_HINGE.clamp(offsets.leftKneeOffset),
            rightLegOffset = AnatomicalJointLimits.HIP.clamp(offsets.rightLegOffset),
            rightKneeOffset = rightKneeLimit.clamp(offsets.rightKneeOffset)
        )
    }

    data class RigBoneGeometry(
        val shoulderHalf: Float,
        val shoulderY: Float,
        val upperArmLen: Float,
        val forearmLen: Float,
        val hipHalf: Float,
        val hipY: Float,
        val thighLen: Float,
        val calfLen: Float,
        val neckY: Float,
        val headHandleOffset: Float,
        val torsoHandleY: Float,
        val pelvisPivotY: Float
    )

    data class StageSkeletonAnchors(
        val neckNx: Float,
        val neckNy: Float,
        val leftShoulderNx: Float,
        val leftShoulderNy: Float,
        val rightShoulderNx: Float,
        val rightShoulderNy: Float,
        val leftHipNx: Float,
        val leftHipNy: Float,
        val rightHipNx: Float,
        val rightHipNy: Float
    )

    /**
     * Converts a screen-normalized stage tap/drag coordinate ([screenNx], [screenNy]) into
     * camera-world normalized stage coordinates so hit-testing, clip dragging, and IK handle
     * posing stay 100% accurate under any camera zoom, pan, or rotation.
     */
    fun screenNormToWorldNorm(
        screenNx: Float,
        screenNy: Float,
        camera: EvaluatedCamera,
        stageWidth: Float,
        stageHeight: Float
    ): Pair<Float, Float> {
        if (camera.isIdentity) return screenNx to screenNy
        val w = stageWidth.coerceAtLeast(1f)
        val h = stageHeight.coerceAtLeast(1f)
        val safeZoom = camera.zoom.coerceAtLeast(0.1f)

        // Forward transform in SceneRenderer:
        //   screenPx = center + zoom * Rot(-camRot) * (worldPx - camPosPx)
        // Inverse:
        //   worldPx = camPosPx + (1/zoom) * Rot(+camRot) * (screenPx - center)
        val dxPx = (screenNx - 0.5f) * w / safeZoom
        val dyPx = (screenNy - 0.5f) * h / safeZoom
        val rad = (camera.rotation * PI / 180.0).toFloat()
        val cosR = cos(rad)
        val sinR = sin(rad)
        val rx = dxPx * cosR - dyPx * sinR
        val ry = dxPx * sinR + dyPx * cosR
        val worldNx = camera.x + rx / w
        val worldNy = camera.y + ry / h
        return worldNx to worldNy
    }

    /**
     * Maps a screen-space pixel drag delta ([dxPx], [dyPx]) into world-normalized delta
     * taking the active scene camera zoom and rotation into account.
     */
    fun screenDeltaToWorldNormDelta(
        dxPx: Float,
        dyPx: Float,
        camera: EvaluatedCamera,
        stageWidth: Float,
        stageHeight: Float
    ): Pair<Float, Float> {
        val w = stageWidth.coerceAtLeast(1f)
        val h = stageHeight.coerceAtLeast(1f)
        if (camera.isIdentity) return (dxPx / w) to (dyPx / h)
        val safeZoom = camera.zoom.coerceAtLeast(0.1f)
        val sx = dxPx / safeZoom
        val sy = dyPx / safeZoom
        val rad = (camera.rotation * PI / 180.0).toFloat()
        val cosR = cos(rad)
        val sinR = sin(rad)
        val rx = sx * cosR - sy * sinR
        val ry = sx * sinR + sy * cosR
        return (rx / w) to (ry / h)
    }

    /**
     * Resolves bone lengths & joint offsets matching the exact renderer active for [rig].
     */
    fun resolveRigGeometry(
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        baseScale: Float
    ): RigBoneGeometry {
        val isAtlasPro = rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO
        if (isAtlasPro) {
            val poseTemplate = rig.spriteAtlasRig?.poseTemplate ?: when (clip.orientation) {
                CharacterOrientation.FRONT -> AtlasPoseTemplate.FRONT
                CharacterOrientation.SIDE, CharacterOrientation.BACK, CharacterOrientation.THREE_QUARTER_BACK -> AtlasPoseTemplate.BACK_SIDE
                CharacterOrientation.THREE_QUARTER_FRONT -> AtlasPoseTemplate.THREE_QUARTER_FRONT
            }
            val shoulderHalf = when (poseTemplate) {
                AtlasPoseTemplate.FRONT -> 29f * baseScale
                AtlasPoseTemplate.THREE_QUARTER_FRONT -> 24f * baseScale
                AtlasPoseTemplate.BACK_SIDE -> 11f * baseScale
            }
            val hipHalf = when (poseTemplate) {
                AtlasPoseTemplate.FRONT -> 15f * baseScale
                AtlasPoseTemplate.THREE_QUARTER_FRONT -> 13f * baseScale
                AtlasPoseTemplate.BACK_SIDE -> 7f * baseScale
            }
            return RigBoneGeometry(
                shoulderHalf = shoulderHalf,
                shoulderY = -28f * baseScale,
                upperArmLen = 28f * baseScale,
                forearmLen = 25f * baseScale,
                hipHalf = hipHalf,
                hipY = 18f * baseScale,
                thighLen = 33f * baseScale,
                calfLen = 34f * baseScale,
                neckY = -36f * baseScale,
                headHandleOffset = 46f * baseScale,
                torsoHandleY = -8f * baseScale,
                pelvisPivotY = 18f * baseScale
            )
        }

        val isModularChart = rig.skeletonRig?.enabled == true && rig.skeletonRig.rigMode == CustomRigMode.MODULAR_CHART_2048
        if (isModularChart) {
            return RigBoneGeometry(
                shoulderHalf = 24f * baseScale,
                shoulderY = -30f * baseScale,
                upperArmLen = 25f * baseScale,
                forearmLen = 25f * baseScale,
                hipHalf = 12f * baseScale,
                hipY = 20f * baseScale,
                thighLen = 29f * baseScale,
                calfLen = 32f * baseScale,
                neckY = -68f * baseScale,
                headHandleOffset = 20f * baseScale,
                torsoHandleY = -8f * baseScale,
                pelvisPivotY = 18f * baseScale
            )
        }

        // Standard Real-Human & PNG Skeleton Rig geometry (matches HumanRigDrawer exact measurements)
        val isSide = clip.orientation == CharacterOrientation.SIDE
        val isThreeQuarter = clip.orientation == CharacterOrientation.THREE_QUARTER_FRONT ||
            clip.orientation == CharacterOrientation.THREE_QUARTER_BACK
        val build = rig.buildType
        val shoulderHalf = (if (isSide) 7f else if (isThreeQuarter) 22f else 26f) * build.shoulderScale * baseScale
        val hipHalf = (if (isSide) 5f else 11.5f) * build.torsoScale * baseScale

        return RigBoneGeometry(
            shoulderHalf = shoulderHalf,
            shoulderY = -30f * baseScale,
            upperArmLen = 25.5f * baseScale,
            forearmLen = 25.5f * baseScale,
            hipHalf = hipHalf,
            hipY = 20f * baseScale,
            thighLen = 29.5f * baseScale,
            calfLen = 31f * baseScale,
            neckY = -68f * baseScale,
            headHandleOffset = 20f * baseScale,
            torsoHandleY = -8f * baseScale,
            pelvisPivotY = 18f * baseScale
        )
    }

    /**
     * Computes all 10 stage-normalized IK posing handles (Head, Torso, L/R Elbow, L/R Hand, L/R Knee, L/R Foot).
     */
    internal fun computeStageIkHandles(
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        transform: EvaluatedTransform,
        pose: ProceduralRigRenderer.PoseAngles,
        stageWidth: Float,
        stageHeight: Float
    ): List<PropAndModularChartRenderer.StageIkHandle> {
        val (handles, _) = computeStageIkHandlesWithAnchors(clip, rig, transform, pose, stageWidth, stageHeight)
        return handles
    }

    /**
     * Computes both the 10 interactive IK handles and structural anchor joints (shoulders, hips, neck)
     * so the viewport bone overlay connects anatomically through real shoulders and hips.
     */
    internal fun computeStageIkHandlesWithAnchors(
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        transform: EvaluatedTransform,
        pose: ProceduralRigRenderer.PoseAngles,
        stageWidth: Float,
        stageHeight: Float
    ): Pair<List<PropAndModularChartRenderer.StageIkHandle>, StageSkeletonAnchors> {
        val w = stageWidth.coerceAtLeast(1f)
        val h = stageHeight.coerceAtLeast(1f)
        val baseScale = min(w, h) / 420f
        val geom = resolveRigGeometry(clip, rig, baseScale)

        val sx = (if (clip.flipHorizontal) -1f else 1f) * transform.scaleX * pose.squashX
        val sy = (if (clip.flipVertical) -1f else 1f) * transform.scaleY * pose.squashY

        val totalRotRad = ((transform.rotation + pose.bodyTiltDeg) * PI / 180.0).toFloat()
        val cosR = cos(totalRotRad)
        val sinR = sin(totalRotRad)
        val bobY = pose.bodyBobY * baseScale
        val cx = transform.x * w
        val cy = transform.y * h

        fun localToStageNorm(lx: Float, ly: Float): Pair<Float, Float> {
            val yWithBob = ly + bobY
            val scaledX = lx * sx
            val scaledY = yWithBob * sy
            val wx = cx + (scaledX * cosR - scaledY * sinR)
            val wy = cy + (scaledX * sinR + scaledY * cosR)
            return ((wx / w).coerceIn(0.01f, 0.99f)) to ((wy / h).coerceIn(0.01f, 0.99f))
        }

        fun fkChain(
            originX: Float,
            originY: Float,
            upperDeg: Float,
            lowerDeg: Float,
            upperLen: Float,
            lowerLen: Float
        ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            val uRad = (upperDeg * PI / 180.0).toFloat()
            val midX = originX - sin(uRad) * upperLen
            val midY = originY + cos(uRad) * upperLen
            val lRad = ((upperDeg + lowerDeg) * PI / 180.0).toFloat()
            val endX = midX - sin(lRad) * lowerLen
            val endY = midY + cos(lRad) * lowerLen
            return (midX to midY) to (endX to endY)
        }

        val headRad = (pose.headNodDeg * PI / 180.0).toFloat()
        val headX = sin(headRad) * geom.headHandleOffset
        val headY = geom.neckY - cos(headRad) * geom.headHandleOffset

        val (lElbowLocal, lHandLocal) = fkChain(
            -geom.shoulderHalf, geom.shoulderY,
            pose.leftArmDeg, pose.leftForearmDeg,
            geom.upperArmLen, geom.forearmLen
        )
        val (rElbowLocal, rHandLocal) = fkChain(
            geom.shoulderHalf, geom.shoulderY,
            pose.rightArmDeg, pose.rightForearmDeg,
            geom.upperArmLen, geom.forearmLen
        )
        val (lKneeLocal, lFootLocal) = fkChain(
            -geom.hipHalf, geom.hipY,
            pose.leftLegDeg, pose.leftKneeDeg,
            geom.thighLen, geom.calfLen
        )
        val (rKneeLocal, rFootLocal) = fkChain(
            geom.hipHalf, geom.hipY,
            pose.rightLegDeg, pose.rightKneeDeg,
            geom.thighLen, geom.calfLen
        )

        val (headNx, headNy) = localToStageNorm(headX, headY)
        val (torsoNx, torsoNy) = localToStageNorm(0f, geom.torsoHandleY)
        val (lElNx, lElNy) = localToStageNorm(lElbowLocal.first, lElbowLocal.second)
        val (lHdNx, lHdNy) = localToStageNorm(lHandLocal.first, lHandLocal.second)
        val (rElNx, rElNy) = localToStageNorm(rElbowLocal.first, rElbowLocal.second)
        val (rHdNx, rHdNy) = localToStageNorm(rHandLocal.first, rHandLocal.second)
        val (lKnNx, lKnNy) = localToStageNorm(lKneeLocal.first, lKneeLocal.second)
        val (lFtNx, lFtNy) = localToStageNorm(lFootLocal.first, lFootLocal.second)
        val (rKnNx, rKnNy) = localToStageNorm(rKneeLocal.first, rKneeLocal.second)
        val (rFtNx, rFtNy) = localToStageNorm(rFootLocal.first, rFootLocal.second)

        val (neckNx, neckNy) = localToStageNorm(0f, geom.neckY)
        val (lShNx, lShNy) = localToStageNorm(-geom.shoulderHalf, geom.shoulderY)
        val (rShNx, rShNy) = localToStageNorm(geom.shoulderHalf, geom.shoulderY)
        val (lHpNx, lHpNy) = localToStageNorm(-geom.hipHalf, geom.hipY)
        val (rHpNx, rHpNy) = localToStageNorm(geom.hipHalf, geom.hipY)

        val handles = listOf(
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.HEAD, headNx, headNy, 0xFF38BDF8.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.TORSO, torsoNx, torsoNy, 0xFF10B981.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.LEFT_ELBOW, lElNx, lElNy, 0xFFF59E0B.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.LEFT_HAND, lHdNx, lHdNy, 0xFFFB923C.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.RIGHT_ELBOW, rElNx, rElNy, 0xFFEC4899.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.RIGHT_HAND, rHdNx, rHdNy, 0xFFF472B6.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.LEFT_KNEE, lKnNx, lKnNy, 0xFF8B5CF6.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.LEFT_FOOT, lFtNx, lFtNy, 0xFFA78BFA.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.RIGHT_KNEE, rKnNx, rKnNy, 0xFF14B8A6.toInt()),
            PropAndModularChartRenderer.StageIkHandle(IkHandleId.RIGHT_FOOT, rFtNx, rFtNy, 0xFF34D399.toInt())
        )

        val anchors = StageSkeletonAnchors(
            neckNx = neckNx,
            neckNy = neckNy,
            leftShoulderNx = lShNx,
            leftShoulderNy = lShNy,
            rightShoulderNx = rShNx,
            rightShoulderNy = rShNy,
            leftHipNx = lHpNx,
            leftHipNy = lHpNy,
            rightHipNx = rHpNx,
            rightHipNy = rHpNy
        )
        return handles to anchors
    }

    /**
     * True Analytical 2-Bone IK Solver (Law of Cosines) in local +Y-down Canvas space
     * where angle A has tip (-sin(A)*L, +cos(A)*L).
     *
     * Returns `(upperTotalDeg, lowerRelDeg)` that places the end effector accurately on
     * `(targetX, targetY)` with smooth soft-clamping near full extension and controllable
     * elbow/knee bend direction ([bendSign] = +1f or -1f).
     */
    fun solveTwoBoneIk(
        originX: Float,
        originY: Float,
        targetX: Float,
        targetY: Float,
        upperLen: Float,
        lowerLen: Float,
        bendSign: Float,
        minLowerDeg: Float = -148f,
        maxLowerDeg: Float = 148f
    ): Pair<Float, Float> {
        val vx = targetX - originX
        val vy = targetY - originY
        val rawDist = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        val maxReach = (upperLen + lowerLen) * 0.996f
        val minReach = (abs(upperLen - lowerLen) + 1.5f).coerceAtMost(maxReach * 0.6f)
        val d = rawDist.coerceIn(minReach, maxReach)

        // Direct aim angle from origin to target in +Y-down canvas convention
        val aimRad = atan2(-vx.toDouble(), vy.toDouble())

        // Law of cosines for upper-bone offset angle (alpha) and interior elbow/knee angle (beta)
        val cosAlpha = ((upperLen * upperLen + d * d - lowerLen * lowerLen) / (2.0 * upperLen * d))
            .coerceIn(-1.0, 1.0)
        val cosBeta = ((upperLen * upperLen + lowerLen * lowerLen - d * d) / (2.0 * upperLen * lowerLen))
            .coerceIn(-1.0, 1.0)

        val alpha = acos(cosAlpha)
        val bendAngleRad = PI - acos(cosBeta) // 0 = straight, PI = fully folded

        val sign = if (bendSign >= 0f) 1.0 else -1.0
        val upperRad = aimRad - sign * alpha
        val lowerRelRad = sign * bendAngleRad

        val upperDeg = normalizeAngleDeg((upperRad * 180.0 / PI).toFloat())
        val lowerRelDeg = ((lowerRelRad * 180.0 / PI).toFloat()).coerceIn(minLowerDeg, maxLowerDeg)
        return upperDeg to lowerRelDeg
    }

    /**
     * Solves a stage drag of [handleId] to world-normalized ([targetNx], [targetNy]) into
     * updated [PoseKeyframeOffsets] relative to [basePose].
     *
     * Uses analytical 2-bone Law-of-Cosines IK for hands & feet, direct joint aiming for
     * elbows & knees, and feedback-free un-rotation for torso tilting.
     */
    internal fun solveIkDragToOffsets(
        handleId: IkHandleId,
        targetNx: Float,
        targetNy: Float,
        clip: TimelineClip,
        rig: CharacterRigDefinition,
        transform: EvaluatedTransform,
        basePose: ProceduralRigRenderer.PoseAngles,
        currentOffsets: PoseKeyframeOffsets,
        stageWidth: Float,
        stageHeight: Float,
        bendFlip: Boolean = false,
        enforceJointLimits: Boolean = true
    ): PoseKeyframeOffsets {
        val w = stageWidth.coerceAtLeast(1f)
        val h = stageHeight.coerceAtLeast(1f)
        val baseScale = min(w, h) / 420f
        val geom = resolveRigGeometry(clip, rig, baseScale)
        val posed = basePose.withOffsets(currentOffsets, clip.facialExpression)

        val sx = ((if (clip.flipHorizontal) -1f else 1f) * transform.scaleX * posed.squashX).let {
            if (abs(it) < 0.05f) (if (it < 0f) -0.05f else 0.05f) else it
        }
        val sy = ((if (clip.flipVertical) -1f else 1f) * transform.scaleY * posed.squashY).let {
            if (abs(it) < 0.05f) (if (it < 0f) -0.05f else 0.05f) else it
        }

        // CRITICAL BUG FIX: When dragging TORSO, un-rotate ONLY by transform.rotation (not posed.bodyTiltDeg)
        // to avoid feedback oscillation where torsoTiltOffset rotates its own coordinate frame!
        val unrotDeg = if (handleId == IkHandleId.TORSO) {
            transform.rotation
        } else {
            transform.rotation + posed.bodyTiltDeg
        }
        val totalRotRad = (unrotDeg * PI / 180.0).toFloat()
        val cosR = cos(-totalRotRad)
        val sinR = sin(-totalRotRad)
        val bobY = posed.bodyBobY * baseScale

        val dx = targetNx * w - transform.x * w
        val dy = targetNy * h - transform.y * h
        val unrotX = dx * cosR - dy * sinR
        val unrotY = dx * sinR + dy * cosR
        val lx = unrotX / sx
        val ly = (unrotY / sy) - bobY

        fun angleFromDownDeg(vx: Float, vy: Float): Float {
            return (atan2(-vx.toDouble(), vy.toDouble()) * 180.0 / PI).toFloat()
        }

        val flipMult = if (bendFlip) -1f else 1f
        val isSideOr3Q = clip.orientation == CharacterOrientation.SIDE ||
            clip.orientation == CharacterOrientation.THREE_QUARTER_FRONT

        val rawSolved = when (handleId) {
            IkHandleId.HEAD -> {
                val vx = lx
                val vy = -(ly - geom.neckY)
                val maxNeck = if (enforceJointLimits) AnatomicalJointLimits.NECK.maxDeg else 75f
                val worldDeg = (atan2(vx.toDouble(), vy.toDouble()) * 180.0 / PI).toFloat().coerceIn(-maxNeck, maxNeck)
                currentOffsets.copy(headNodOffset = (worldDeg - basePose.headNodDeg).coerceIn(-maxNeck, maxNeck))
            }
            IkHandleId.TORSO -> {
                val vx = lx
                val vy = -(ly - geom.pelvisPivotY)
                val maxTorso = if (enforceJointLimits) AnatomicalJointLimits.TORSO.maxDeg else 68f
                val tiltDeg = (atan2(vx.toDouble(), vy.toDouble()) * 180.0 / PI).toFloat().coerceIn(-maxTorso, maxTorso)
                currentOffsets.copy(torsoTiltOffset = (tiltDeg - basePose.bodyTiltDeg).coerceIn(-maxTorso, maxTorso))
            }
            IkHandleId.LEFT_ELBOW -> {
                val worldUpper = angleFromDownDeg(lx - (-geom.shoulderHalf), ly - geom.shoulderY)
                val clampedUpper = if (enforceJointLimits) AnatomicalJointLimits.LEFT_SHOULDER.clamp(worldUpper) else worldUpper
                currentOffsets.copy(leftArmOffset = normalizeAngleDeg(clampedUpper - basePose.leftArmDeg))
            }
            IkHandleId.LEFT_HAND -> {
                val defaultBend = -1f
                val minLower = if (enforceJointLimits && !bendFlip) -142f else -165f
                val maxLower = if (enforceJointLimits && !bendFlip) 0f else 165f
                val (upperDeg, lowerRelDeg) = solveTwoBoneIk(
                    originX = -geom.shoulderHalf,
                    originY = geom.shoulderY,
                    targetX = lx,
                    targetY = ly,
                    upperLen = geom.upperArmLen,
                    lowerLen = geom.forearmLen,
                    bendSign = defaultBend * flipMult,
                    minLowerDeg = minLower,
                    maxLowerDeg = maxLower
                )
                val safeUpper = if (enforceJointLimits) AnatomicalJointLimits.LEFT_SHOULDER.clamp(upperDeg) else upperDeg
                currentOffsets.copy(
                    leftArmOffset = normalizeAngleDeg(safeUpper - basePose.leftArmDeg),
                    leftForearmOffset = normalizeAngleDeg(lowerRelDeg - basePose.leftForearmDeg)
                )
            }
            IkHandleId.RIGHT_ELBOW -> {
                val worldUpper = angleFromDownDeg(lx - geom.shoulderHalf, ly - geom.shoulderY)
                val clampedUpper = if (enforceJointLimits) AnatomicalJointLimits.RIGHT_SHOULDER.clamp(worldUpper) else worldUpper
                currentOffsets.copy(rightArmOffset = normalizeAngleDeg(clampedUpper - basePose.rightArmDeg))
            }
            IkHandleId.RIGHT_HAND -> {
                val defaultBend = if (isSideOr3Q) -1f else 1f
                val minLower = if (enforceJointLimits && !bendFlip) (if (isSideOr3Q) -142f else 0f) else -165f
                val maxLower = if (enforceJointLimits && !bendFlip) (if (isSideOr3Q) 0f else 142f) else 165f
                val (upperDeg, lowerRelDeg) = solveTwoBoneIk(
                    originX = geom.shoulderHalf,
                    originY = geom.shoulderY,
                    targetX = lx,
                    targetY = ly,
                    upperLen = geom.upperArmLen,
                    lowerLen = geom.forearmLen,
                    bendSign = defaultBend * flipMult,
                    minLowerDeg = minLower,
                    maxLowerDeg = maxLower
                )
                val safeUpper = if (enforceJointLimits) AnatomicalJointLimits.RIGHT_SHOULDER.clamp(upperDeg) else upperDeg
                currentOffsets.copy(
                    rightArmOffset = normalizeAngleDeg(safeUpper - basePose.rightArmDeg),
                    rightForearmOffset = normalizeAngleDeg(lowerRelDeg - basePose.rightForearmDeg)
                )
            }
            IkHandleId.LEFT_KNEE -> {
                val rawThigh = angleFromDownDeg(lx - (-geom.hipHalf), ly - geom.hipY)
                val worldThigh = if (enforceJointLimits) AnatomicalJointLimits.HIP.clamp(rawThigh) else rawThigh.coerceIn(-155f, 155f)
                currentOffsets.copy(leftLegOffset = normalizeAngleDeg(worldThigh - basePose.leftLegDeg))
            }
            IkHandleId.LEFT_FOOT -> {
                val defaultBend = 1f
                val minKnee = if (enforceJointLimits && !bendFlip) 0f else -155f
                val maxKnee = if (enforceJointLimits && !bendFlip) 132f else 155f
                val (thighDeg, kneeRelDeg) = solveTwoBoneIk(
                    originX = -geom.hipHalf,
                    originY = geom.hipY,
                    targetX = lx,
                    targetY = ly,
                    upperLen = geom.thighLen,
                    lowerLen = geom.calfLen,
                    bendSign = defaultBend * flipMult,
                    minLowerDeg = minKnee,
                    maxLowerDeg = maxKnee
                )
                val safeThigh = if (enforceJointLimits) AnatomicalJointLimits.HIP.clamp(thighDeg) else thighDeg.coerceIn(-155f, 155f)
                currentOffsets.copy(
                    leftLegOffset = normalizeAngleDeg(safeThigh - basePose.leftLegDeg),
                    leftKneeOffset = normalizeAngleDeg(kneeRelDeg - basePose.leftKneeDeg)
                )
            }
            IkHandleId.RIGHT_KNEE -> {
                val rawThigh = angleFromDownDeg(lx - geom.hipHalf, ly - geom.hipY)
                val worldThigh = if (enforceJointLimits) AnatomicalJointLimits.HIP.clamp(rawThigh) else rawThigh.coerceIn(-155f, 155f)
                currentOffsets.copy(rightLegOffset = normalizeAngleDeg(worldThigh - basePose.rightLegDeg))
            }
            IkHandleId.RIGHT_FOOT -> {
                val defaultBend = if (isSideOr3Q) 1f else -1f
                val minKnee = if (enforceJointLimits && !bendFlip) (if (isSideOr3Q) 0f else -132f) else -155f
                val maxKnee = if (enforceJointLimits && !bendFlip) (if (isSideOr3Q) 132f else 0f) else 155f
                val (thighDeg, kneeRelDeg) = solveTwoBoneIk(
                    originX = geom.hipHalf,
                    originY = geom.hipY,
                    targetX = lx,
                    targetY = ly,
                    upperLen = geom.thighLen,
                    lowerLen = geom.calfLen,
                    bendSign = defaultBend * flipMult,
                    minLowerDeg = minKnee,
                    maxLowerDeg = maxKnee
                )
                val safeThigh = if (enforceJointLimits) AnatomicalJointLimits.HIP.clamp(thighDeg) else thighDeg.coerceIn(-155f, 155f)
                currentOffsets.copy(
                    rightLegOffset = normalizeAngleDeg(safeThigh - basePose.rightLegDeg),
                    rightKneeOffset = normalizeAngleDeg(kneeRelDeg - basePose.rightKneeDeg)
                )
            }
        }
        return if (enforceJointLimits && !bendFlip) {
            clampPoseOffsets(rawSolved, enforceJointLimits = true, orientation = clip.orientation)
        } else {
            clampPoseOffsets(rawSolved, enforceJointLimits = false, orientation = clip.orientation)
        }
    }

    /**
     * Mirrors left and right limb offsets symmetrically (great for walk/run contact poses).
     */
    fun mirrorPoseOffsets(offsets: PoseKeyframeOffsets): PoseKeyframeOffsets = PoseKeyframeOffsets(
        headNodOffset = -offsets.headNodOffset,
        torsoTiltOffset = -offsets.torsoTiltOffset,
        leftArmOffset = -offsets.rightArmOffset,
        leftForearmOffset = -offsets.rightForearmOffset,
        rightArmOffset = -offsets.leftArmOffset,
        rightForearmOffset = -offsets.leftForearmOffset,
        leftLegOffset = -offsets.rightLegOffset,
        leftKneeOffset = -offsets.rightKneeOffset,
        rightLegOffset = -offsets.leftLegOffset,
        rightKneeOffset = -offsets.leftKneeOffset
    )

    private fun normalizeAngleDeg(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a.coerceIn(-165f, 165f)
    }
}
