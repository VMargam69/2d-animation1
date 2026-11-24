package com.example.model

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class AspectRatioPreset(val label: String, val defaultWidth: Int, val defaultHeight: Int) {
    RATIO_16_9("16:9", 1280, 720),
    RATIO_9_16("9:16", 720, 1280),
    RATIO_1_1("1:1", 960, 960),
    RATIO_4_3("4:3", 1024, 768),
    CUSTOM("Custom", 1280, 720)
}

enum class PreviewQuality(val label: String, val scaleFactor: Float) {
    DRAFT("Draft", 0.6f),
    STANDARD("Standard", 0.85f),
    HIGH("High", 1.0f)
}

enum class CharacterCategory(val displayName: String) {
    ALL("All"),
    DESI("Desi"),
    WESTERN("Western"),
    INDONESIAN("Indonesian"),
    VILLAGE("Village"),
    BIRDS("Birds"),
    ANIMALS("Animals"),
    VEHICLES("Vehicles"),
    RELIGIOUS("Religious"),
    HORROR("Horror"),
    LATEST("Latest"),
    PINNED("Pinned")
}

enum class CharacterOrientation(val displayName: String, val shortLabel: String) {
    FRONT("Front", "Front"),
    THREE_QUARTER_FRONT("3/4 Front", "3/4 F"),
    THREE_QUARTER_BACK("3/4 Back", "3/4 B"),
    BACK("Back", "Back"),
    SIDE("Side", "Side")
}

enum class CharacterBodyType(val displayName: String) {
    HUMANOID("Humanoid"),
    BIRD("Bird"),
    QUADRUPED("Animal"),
    VEHICLE("Vehicle"),
    SPIRIT("Spirit / Ghost")
}

enum class CharacterAccessory(val displayName: String) {
    NONE("None"),
    TURBAN("Turban"),
    HAT("Hat"),
    CROWN("Crown"),
    SCARF("Scarf"),
    CAPE("Cape"),
    HALO("Halo"),
    HORNS("Horns"),
    HEADPHONES("Headphones")
}

enum class HumanCostumeStyle(val displayName: String) {
    CASUAL_JACKET("Casual Jacket"),
    KURTA_TRADITIONAL("Kurta"),
    SAREE_DRAPE("Saree Drape"),
    FORMAL_SUIT("Formal Suit"),
    SPORT_ATHLETIC("Athletic"),
    HOODIE_STREET("Street Hoodie"),
    VILLAGE_DHOTI("Dhoti & Kurta"),
    FANTASY_ARMOR("Armor Tunic")
}

enum class HumanHairStyle(val displayName: String) {
    SHORT_FADE("Short Fade"),
    SIDE_PART("Side Part"),
    WAVY_LONG("Wavy Long"),
    TRADITIONAL_BUN("Classic Bun"),
    CURLY_AFRO("Curly"),
    SPIKY("Modern Spiky"),
    NONE("Bald / Covered")
}

enum class HumanBuildType(val displayName: String, val shoulderScale: Float, val torsoScale: Float) {
    SLIM("Slim", 0.90f, 0.88f),
    STANDARD("Standard", 1.0f, 1.0f),
    ATHLETIC("Athletic", 1.12f, 1.04f),
    BROAD("Broad", 1.20f, 1.16f)
}

@JsonClass(generateAdapter = true)
data class SkeletonRigConfig(
    val enabled: Boolean = false,
    val removeBackground: Boolean = false,
    val bgTolerance: Int = 36,
    val showBoneOverlay: Boolean = false,
    val headX: Float = 0.50f,
    val headY: Float = 0.16f,
    val neckX: Float = 0.50f,
    val neckY: Float = 0.27f,
    val torsoX: Float = 0.50f,
    val torsoY: Float = 0.42f,
    val pelvisX: Float = 0.50f,
    val pelvisY: Float = 0.56f,
    val leftShoulderX: Float = 0.34f,
    val leftShoulderY: Float = 0.31f,
    val leftHandX: Float = 0.22f,
    val leftHandY: Float = 0.54f,
    val rightShoulderX: Float = 0.66f,
    val rightShoulderY: Float = 0.31f,
    val rightHandX: Float = 0.78f,
    val rightHandY: Float = 0.54f,
    val leftHipX: Float = 0.42f,
    val leftHipY: Float = 0.58f,
    val leftFootX: Float = 0.39f,
    val leftFootY: Float = 0.91f,
    val rightHipX: Float = 0.58f,
    val rightHipY: Float = 0.58f,
    val rightFootX: Float = 0.61f,
    val rightFootY: Float = 0.91f
)

@JsonClass(generateAdapter = true)
data class CharacterRigDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: CharacterCategory,
    val bodyType: CharacterBodyType = CharacterBodyType.HUMANOID,
    val skinColorHex: String = "#E5B48A",
    val primaryColorHex: String = "#38BDF8",
    val secondaryColorHex: String = "#1E293B",
    val accentColorHex: String = "#F59E0B",
    val hairColorHex: String = "#1F1B18",
    val eyeColorHex: String = "#2B1E16",
    val costumeStyle: HumanCostumeStyle = HumanCostumeStyle.CASUAL_JACKET,
    val hairStyle: HumanHairStyle = HumanHairStyle.SIDE_PART,
    val buildType: HumanBuildType = HumanBuildType.STANDARD,
    val accessory: CharacterAccessory = CharacterAccessory.NONE,
    val supportedOrientations: List<CharacterOrientation> = CharacterOrientation.entries,
    val isPinned: Boolean = false,
    val isCustom: Boolean = false,
    val isLatest: Boolean = false,
    val customImagePath: String? = null,
    val skeletonRig: SkeletonRigConfig? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AnimationPresetType(
    val displayName: String,
    val defaultDurationFrames: Int,
    val tag: String
) {
    IDLE("Idle", 48, "Base"),
    IDLE_HAPPY("Idle Happy", 48, "Emotion"),
    WALK("Walk", 36, "Locomotion"),
    JOGGING("Jogging", 28, "Locomotion"),
    RUNNING("Running", 22, "Locomotion"),
    SITTING("Sitting", 48, "Pose"),
    STANDING("Standing", 36, "Pose"),
    TALKING("Talking", 40, "Dialogue"),
    WAVING("Waving", 36, "Gesture"),
    JUMPING("Jumping", 30, "Action"),
    DANCING("Dancing", 44, "Action")
}

data class AnimationPreset(
    val id: String,
    val type: AnimationPresetType,
    val name: String,
    val tag: String,
    val defaultDurationFrames: Int,
    val supportedOrientations: List<CharacterOrientation> = CharacterOrientation.entries,
    val description: String
)

enum class EasingType(val displayName: String) {
    LINEAR("Linear"),
    HOLD("Hold (Step)"),
    EASE_IN_OUT("Ease In-Out")
}

enum class TransitionType(val displayName: String) {
    NONE("None"),
    FADE("Fade"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_UP("Slide Up"),
    ZOOM_POP("Zoom Pop")
}

enum class LayerType(val displayName: String, val defaultColorHex: String) {
    BACKGROUND("Background", "#10B981"),
    CHARACTER("Character", "#38BDF8"),
    IMAGE("Image", "#A78BFA"),
    SHAPE("Shape", "#FB923C"),
    TEXT("Text", "#F59E0B"),
    DRAWING("Drawing", "#F472B6"),
    AUDIO("Audio", "#34D399")
}

enum class ShapeKind(val displayName: String) {
    RECTANGLE("Rectangle"),
    CIRCLE("Circle"),
    STAR("Star"),
    SPEECH_BUBBLE("Speech Bubble"),
    ARROW("Arrow")
}

@JsonClass(generateAdapter = true)
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

@JsonClass(generateAdapter = true)
data class DrawingStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<NormalizedPoint>,
    val colorHex: String = "#38BDF8",
    val strokeWidthRatio: Float = 0.008f
)

@JsonClass(generateAdapter = true)
data class TransformKeyframe(
    val frame: Int,
    val x: Float = 0.5f,          // Normalized 0..1 of canvas width
    val y: Float = 0.55f,         // Normalized 0..1 of canvas height
    val rotation: Float = 0f,     // Degrees
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Float = 1f,
    val easing: EasingType = EasingType.LINEAR
)

@JsonClass(generateAdapter = true)
data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val name: String,
    val startFrame: Int = 0,
    val durationFrames: Int = 96,
    val characterId: String? = null,
    val orientation: CharacterOrientation = CharacterOrientation.FRONT,
    val animationPresetType: AnimationPresetType = AnimationPresetType.IDLE,
    val speedMultiplier: Float = 1.0f,
    val loopAnimation: Boolean = true,
    val transitionIn: TransitionType = TransitionType.NONE,
    val transitionOut: TransitionType = TransitionType.NONE,
    val transitionFrames: Int = 10,
    val imagePath: String? = null,
    val bundledBgKey: String? = null,
    val textContent: String? = null,
    val textColorHex: String = "#FFFFFF",
    val textSizeSp: Float = 36f,
    val shapeKind: ShapeKind? = null,
    val shapeFillHex: String = "#38BDF8",
    val shapeStrokeHex: String = "#F8FAFC",
    val audioPath: String? = null,
    val audioLabel: String? = null,
    val drawingStrokes: List<DrawingStroke> = emptyList(),
    val keyframes: List<TransformKeyframe> = listOf(TransformKeyframe(frame = 0)),
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
) {
    val endFrame: Int get() = startFrame + durationFrames
}

@JsonClass(generateAdapter = true)
data class AnimationLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LayerType,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val colorHex: String = type.defaultColorHex,
    val orderIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class AnimationScene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scene 1",
    val backgroundColorHex: String = "#162033",
    val backgroundBundledKey: String? = null,
    val backgroundImagePath: String? = null,
    val durationFrames: Int = 144,
    val layers: List<AnimationLayer> = emptyList(),
    val clips: List<TimelineClip> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProjectDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Animation",
    val width: Int = 1280,
    val height: Int = 720,
    val aspectRatioPreset: AspectRatioPreset = AspectRatioPreset.RATIO_16_9,
    val fps: Int = 24,
    val previewQuality: PreviewQuality = PreviewQuality.STANDARD,
    val onionSkinEnabled: Boolean = false,
    val showMotionPaths: Boolean = false,
    val activeSceneIndex: Int = 0,
    val scenes: List<AnimationScene> = listOf(AnimationScene()),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val activeScene: AnimationScene
        get() = scenes.getOrElse(activeSceneIndex.coerceIn(0, (scenes.size - 1).coerceAtLeast(0))) {
            AnimationScene()
        }
}

enum class MediaAssetType(val displayName: String) {
    BACKGROUND("Background"),
    IMAGE("Image"),
    GIF("GIF"),
    SVG("SVG"),
    VIDEO("Video"),
    AUDIO("Audio")
}

data class LocalMediaAsset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val type: MediaAssetType,
    val localFilePath: String? = null,
    val bundledResKey: String? = null,
    val mimeType: String = "image/png",
    val isBundled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ExportFormat(val displayName: String, val extension: String, val description: String) {
    MP4("MP4 Video (H.264)", "mp4", "Hardware-accelerated AVC video file"),
    GIF("Animated GIF", "gif", "Looping 256-color GIF89a animation"),
    PNG_SEQUENCE("PNG Frame Sequence", "png", "Lossless frame-by-frame PNG folder")
}

enum class ExportResolution(val label: String, val longEdge: Int) {
    SD_480("480p Fast", 854),
    HD_720("720p HD", 1280),
    FHD_1080("1080p Full HD", 1920)
}
