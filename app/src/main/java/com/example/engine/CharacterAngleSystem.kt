package com.example.engine

import com.example.model.CharacterOrientation
import com.example.model.HumanBuildType

/**
 * Directional shoe rendering mode per character yaw angle so feet never all point sideways
 * in Front or Back views.
 */
enum class ShoeAngleView {
    FRONT_OUTWARD_LEFT,
    FRONT_OUTWARD_RIGHT,
    PROFILE_RIGHT,
    REAR_HEEL
}

/**
 * Head contour geometry mode for the 5-angle turnaround system.
 */
enum class HeadAngleProfileMode {
    FRONT,
    THREE_QUARTER_FRONT,
    SIDE_PROFILE,
    THREE_QUARTER_BACK,
    BACK
}

/**
 * Unified single-source-of-truth biomechanical & depth-sorting profile for the 5-Angle Character System
 * (`FRONT` 0°, `THREE_QUARTER_FRONT` 45°, `SIDE` 90°, `THREE_QUARTER_BACK` 135°, `BACK` 180°).
 *
 * Consumed by [HumanRigDrawer], [IkRigSolver], and [SpriteAtlasRigRenderer] so character rendering,
 * Z-order depth shading, facial foreshortening, and 2-bone IK handles stay 100% geometrically locked
 * across all 5 turnaround angles.
 */
data class CharacterAngleProfile(
    val orientation: CharacterOrientation,
    val yawDeg: Float,
    val leftShoulderX: Float,
    val rightShoulderX: Float,
    val leftHipX: Float,
    val rightHipX: Float,
    val waistHalfWidth: Float,
    val chestForwardShift: Float,
    val faceShiftX: Float,
    val headHalfWidth: Float,
    val headProfileMode: HeadAngleProfileMode,
    val showsFace: Boolean,
    val showsBackHairFull: Boolean,
    val leftArmBehindTorso: Boolean,
    val rightArmBehindTorso: Boolean,
    val leftArmDepthShade: Float,
    val rightArmDepthShade: Float,
    val leftLegDepthShade: Float,
    val rightLegDepthShade: Float,
    val showLeftEar: Boolean,
    val showRightEar: Boolean,
    val leftEarX: Float,
    val rightEarX: Float,
    val leftEyeScaleX: Float,
    val rightEyeScaleX: Float,
    val noseProtrusionX: Float,
    val leftShoeView: ShoeAngleView,
    val rightShoeView: ShoeAngleView,
    val ikRightElbowSign: Float,
    val ikRightKneeSign: Float
)

object CharacterAngleSystem {

    /**
     * Ordered 5-angle turnaround sequence (0° -> 45° -> 90° -> 135° -> 180°).
     */
    val turnaroundAngles: List<CharacterOrientation> = listOf(
        CharacterOrientation.FRONT,
        CharacterOrientation.THREE_QUARTER_FRONT,
        CharacterOrientation.SIDE,
        CharacterOrientation.THREE_QUARTER_BACK,
        CharacterOrientation.BACK
    )

    fun shortLabel(orientation: CharacterOrientation): String = when (orientation) {
        CharacterOrientation.FRONT -> "Front"
        CharacterOrientation.THREE_QUARTER_FRONT -> "3/4 F"
        CharacterOrientation.SIDE -> "Side"
        CharacterOrientation.THREE_QUARTER_BACK -> "3/4 B"
        CharacterOrientation.BACK -> "Back"
    }

    /**
     * Computes exact unscaled (unit `s = 1f`) biomechanical coordinates for [orientation] and [buildType].
     * Multiply horizontal coordinates by `rigScale` when drawing or computing stage IK anchors.
     */
    fun profileFor(
        orientation: CharacterOrientation,
        buildType: HumanBuildType = HumanBuildType.STANDARD
    ): CharacterAngleProfile {
        val sh = buildType.shoulderScale
        val to = buildType.torsoScale
        return when (orientation) {
            CharacterOrientation.FRONT -> CharacterAngleProfile(
                orientation = orientation,
                yawDeg = 0f,
                leftShoulderX = -25.5f * sh,
                rightShoulderX = 25.5f * sh,
                leftHipX = -11.5f * to,
                rightHipX = 11.5f * to,
                waistHalfWidth = 17.5f * to,
                chestForwardShift = 0f,
                faceShiftX = 0f,
                headHalfWidth = 20.5f,
                headProfileMode = HeadAngleProfileMode.FRONT,
                showsFace = true,
                showsBackHairFull = false,
                leftArmBehindTorso = true,
                rightArmBehindTorso = false,
                leftArmDepthShade = 1.0f,
                rightArmDepthShade = 1.0f,
                leftLegDepthShade = 1.0f,
                rightLegDepthShade = 1.0f,
                showLeftEar = true,
                showRightEar = true,
                leftEarX = -19.5f,
                rightEarX = 19.5f,
                leftEyeScaleX = 1.0f,
                rightEyeScaleX = 1.0f,
                noseProtrusionX = 1.2f,
                leftShoeView = ShoeAngleView.FRONT_OUTWARD_LEFT,
                rightShoeView = ShoeAngleView.FRONT_OUTWARD_RIGHT,
                ikRightElbowSign = 1f,
                ikRightKneeSign = -1f
            )

            CharacterOrientation.THREE_QUARTER_FRONT -> CharacterAngleProfile(
                orientation = orientation,
                yawDeg = 45f,
                leftShoulderX = -17.5f * sh,
                rightShoulderX = 23.5f * sh,
                leftHipX = -8.5f * to,
                rightHipX = 11.5f * to,
                waistHalfWidth = 16.0f * to,
                chestForwardShift = 3.2f * to,
                faceShiftX = 7.2f,
                headHalfWidth = 19.8f,
                headProfileMode = HeadAngleProfileMode.THREE_QUARTER_FRONT,
                showsFace = true,
                showsBackHairFull = false,
                leftArmBehindTorso = true,
                rightArmBehindTorso = false,
                leftArmDepthShade = 0.87f,
                rightArmDepthShade = 1.0f,
                leftLegDepthShade = 0.88f,
                rightLegDepthShade = 1.0f,
                showLeftEar = true,
                showRightEar = false,
                leftEarX = -18.8f,
                rightEarX = 18.0f,
                leftEyeScaleX = 1.04f,
                rightEyeScaleX = 0.82f,
                noseProtrusionX = 3.2f,
                leftShoeView = ShoeAngleView.PROFILE_RIGHT,
                rightShoeView = ShoeAngleView.PROFILE_RIGHT,
                ikRightElbowSign = -1f,
                ikRightKneeSign = 1f
            )

            CharacterOrientation.SIDE -> CharacterAngleProfile(
                orientation = orientation,
                yawDeg = 90f,
                leftShoulderX = -3.8f * sh,
                rightShoulderX = 4.2f * sh,
                leftHipX = -3.2f * to,
                rightHipX = 3.5f * to,
                waistHalfWidth = 12.6f * to,
                chestForwardShift = 4.6f * to,
                faceShiftX = 11.5f,
                headHalfWidth = 18.6f,
                headProfileMode = HeadAngleProfileMode.SIDE_PROFILE,
                showsFace = true,
                showsBackHairFull = false,
                leftArmBehindTorso = true,
                rightArmBehindTorso = false,
                leftArmDepthShade = 0.80f,
                rightArmDepthShade = 1.0f,
                leftLegDepthShade = 0.82f,
                rightLegDepthShade = 1.0f,
                showLeftEar = true,
                showRightEar = false,
                leftEarX = -4.2f,
                rightEarX = 0f,
                leftEyeScaleX = 0.92f,
                rightEyeScaleX = 0.92f,
                noseProtrusionX = 5.4f,
                leftShoeView = ShoeAngleView.PROFILE_RIGHT,
                rightShoeView = ShoeAngleView.PROFILE_RIGHT,
                ikRightElbowSign = -1f,
                ikRightKneeSign = 1f
            )

            CharacterOrientation.THREE_QUARTER_BACK -> CharacterAngleProfile(
                orientation = orientation,
                yawDeg = 135f,
                leftShoulderX = -22.5f * sh,
                rightShoulderX = 17.0f * sh,
                leftHipX = -11.0f * to,
                rightHipX = 8.5f * to,
                waistHalfWidth = 16.0f * to,
                chestForwardShift = -2.8f * to,
                faceShiftX = -6.8f,
                headHalfWidth = 19.8f,
                headProfileMode = HeadAngleProfileMode.THREE_QUARTER_BACK,
                showsFace = false,
                showsBackHairFull = true,
                leftArmBehindTorso = false,
                rightArmBehindTorso = true,
                leftArmDepthShade = 1.0f,
                rightArmDepthShade = 0.86f,
                leftLegDepthShade = 1.0f,
                rightLegDepthShade = 0.88f,
                showLeftEar = true,
                showRightEar = false,
                leftEarX = -17.5f,
                rightEarX = 17.5f,
                leftEyeScaleX = 0f,
                rightEyeScaleX = 0f,
                noseProtrusionX = 0f,
                leftShoeView = ShoeAngleView.REAR_HEEL,
                rightShoeView = ShoeAngleView.REAR_HEEL,
                ikRightElbowSign = 1f,
                ikRightKneeSign = -1f
            )

            CharacterOrientation.BACK -> CharacterAngleProfile(
                orientation = orientation,
                yawDeg = 180f,
                leftShoulderX = -25.5f * sh,
                rightShoulderX = 25.5f * sh,
                leftHipX = -11.5f * to,
                rightHipX = 11.5f * to,
                waistHalfWidth = 17.5f * to,
                chestForwardShift = 0f,
                faceShiftX = 0f,
                headHalfWidth = 20.5f,
                headProfileMode = HeadAngleProfileMode.BACK,
                showsFace = false,
                showsBackHairFull = true,
                leftArmBehindTorso = false,
                rightArmBehindTorso = true,
                leftArmDepthShade = 1.0f,
                rightArmDepthShade = 1.0f,
                leftLegDepthShade = 1.0f,
                rightLegDepthShade = 1.0f,
                showLeftEar = true,
                showRightEar = true,
                leftEarX = -19.5f,
                rightEarX = 19.5f,
                leftEyeScaleX = 0f,
                rightEyeScaleX = 0f,
                noseProtrusionX = 0f,
                leftShoeView = ShoeAngleView.REAR_HEEL,
                rightShoeView = ShoeAngleView.REAR_HEEL,
                ikRightElbowSign = 1f,
                ikRightKneeSign = -1f
            )
        }
    }
}
