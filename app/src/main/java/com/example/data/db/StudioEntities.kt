package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val aspectRatioLabel: String,
    val fps: Int,
    val sceneCount: Int,
    val durationFrames: Int,
    val previewBgKey: String?,
    val previewBgColorHex: String,
    val documentJson: String,
    val isLastOpened: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryName: String,
    val bodyTypeName: String,
    val skinColorHex: String,
    val primaryColorHex: String,
    val secondaryColorHex: String,
    val accentColorHex: String,
    val hairColorHex: String,
    val eyeColorHex: String = "#2B1E16",
    val costumeStyleName: String = "CASUAL_JACKET",
    val hairStyleName: String = "SIDE_PART",
    val buildTypeName: String = "STANDARD",
    val accessoryName: String,
    val isPinned: Boolean,
    val isCustom: Boolean,
    val isLatest: Boolean,
    val customImagePath: String?,
    val skeletonRigJson: String? = null,
    val createdAt: Long
)

@Entity(tableName = "animation_presets")
data class AnimationPresetEntity(
    @PrimaryKey val id: String,
    val typeName: String,
    val name: String,
    val tag: String,
    val defaultDurationFrames: Int,
    val description: String,
    val supportedOrientationsCsv: String = "FRONT,THREE_QUARTER_FRONT,THREE_QUARTER_BACK,BACK,SIDE",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "media_assets")
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val typeName: String,
    val localFilePath: String,
    val mimeType: String,
    val createdAt: Long
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val aspectRatioLabel: String,
    val fps: Int,
    val sceneCount: Int,
    val durationFrames: Int,
    val previewBgKey: String?,
    val previewBgColorHex: String,
    val isLastOpened: Boolean,
    val updatedAt: Long
)
