package com.example.model

import java.util.UUID

/**
 * Categories for organizing saved custom IK rig poses in the offline Pose Library.
 */
enum class PoseCategory(val displayName: String) {
    STANDING("Standing"),
    SITTING("Sitting"),
    TALKING("Talking"),
    ACTION("Action"),
    CUSTOM("Custom")
}

/**
 * Normalized eye-look / gaze target offset (`-1f..1f` horizontally & vertically).
 */
data class GazeTarget(
    val x: Float = 0f,
    val y: Float = 0f
)

/**
 * A reusable custom IK character rig pose persisted locally in `filesDir/pose_library.json`.
 * Captures all bone offsets, eye gaze target, head tilt, facial expression, hand pose,
 * and an optional cached offline rig silhouette thumbnail path.
 */
data class SavedPose(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: PoseCategory = PoseCategory.CUSTOM,
    val ikJointOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
    val gazeTarget: GazeTarget = GazeTarget(),
    val headTilt: Float = 0f,
    val expression: FacialExpression = FacialExpression.AUTO,
    val handPose: HandPoseType = HandPoseType.OPEN,
    val thumbnailPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Root local JSON document persisted to `filesDir/pose_library.json`.
 */
data class PoseLibraryDocument(
    val schemaVersion: Int = 1,
    val poses: List<SavedPose> = emptyList()
)
