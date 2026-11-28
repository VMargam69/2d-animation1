package com.example.model

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class AspectRatioPreset(val label: String, val defaultWidth: Int, val defaultHeight: Int) {
    RATIO_16_9("16:9", 1280, 720),
    RATIO_9_16("9:16", 720, 1280),
    RATIO_1_1("1:1", 960, 960),
    RATIO_4_5("4:5", 1080, 1350),
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
    FRONT("Front", "F"),
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

enum class CustomRigMode(val displayName: String, val shortLabel: String) {
    SINGLE_PNG_MESH("Single PNG Mesh Rig", "Single PNG Mesh"),
    MODULAR_CHART_2048("2048×2048 Modular Chart", "2048×2048 Chart"),
    ATLAS_RIG_PRO("Custom Character Maker Atlas", "Atlas Pro")
}

enum class FacialExpression(val displayName: String, val badgeText: String) {
    AUTO("Auto (Preset)", "Auto"),
    NEUTRAL("Neutral", "Neutral"),
    HAPPY("Happy", "Happy"),
    ANGRY("Angry", "Angry"),
    SAD("Sad", "Sad"),
    SHOCKED("Shocked", "Shock"),
    LAUGHING("Laughing", "Laugh"),
    TALKING("Talking", "Talk"),
    SLEEPING("Sleeping", "Sleep")
}

enum class MouthShapeOverride(val displayName: String, val shortLabel: String) {
    AUTO("Auto", "Auto"),
    NEUTRAL("Neutral", "Idle"),
    SMILE("Warm Smile", "Smile"),
    OPEN_LAUGH("Open Laugh", "Laugh"),
    FROWN("Sad Frown", "Frown"),
    SHOCK_O("Shock O", "Shock"),
    GRIT_ANGRY("Gritted Teeth", "Grit"),
    TALK_AA("Open AA", "AA"),
    TALK_EE("Wide EE", "EE"),
    SMIRK("Smirk", "Smirk")
}

enum class EyeShapeOverride(val displayName: String, val shortLabel: String) {
    AUTO("Auto", "Auto"),
    NORMAL("Open Normal", "Open"),
    HAPPY_ARC("Happy Arc", "Happy"),
    WIDE_SHOCK("Wide Shock", "Wide"),
    SQUINT_ANGRY("Fierce Squint", "Squint"),
    SAD_DROOP("Sad Teary", "Sad"),
    WINK_RIGHT("Wink Right", "Wink R"),
    WINK_LEFT("Wink Left", "Wink L"),
    CLOSED_SLEEP("Closed", "Closed")
}

enum class EyebrowShapeOverride(val displayName: String, val shortLabel: String) {
    AUTO("Auto", "Auto"),
    NEUTRAL("Flat Neutral", "Flat"),
    RAISED_SURPRISE("Raised High", "Raised"),
    FURROWED_ANGRY("Furrowed V", "Angry"),
    WORRIED_SAD("Worried Up", "Sad"),
    ARCHED_HAPPY("Arched Happy", "Arched"),
    SKEPTICAL_ONE_UP("Skeptical", "Skeptic")
}

enum class CharacterPropType(val displayName: String) {
    NONE("No Prop"),
    MICROPHONE("Microphone"),
    SWORD("Hero Sword"),
    MAGIC_WAND("Magic Wand"),
    SMARTPHONE("Smartphone"),
    COFFEE_MUG("Coffee Mug"),
    LANTERN("Village Lantern"),
    SHIELD("Round Shield"),
    UMBRELLA("Umbrella"),
    BOOK("Story Book"),
    FLOWER("Lotus Flower"),
    CUSTOM_PNG("Custom PNG Prop")
}

enum class PropAttachmentPoint(val displayName: String, val shortLabel: String) {
    RIGHT_HAND("Right Hand", "R. Hand"),
    LEFT_HAND("Left Hand", "L. Hand"),
    HEAD("Head Top", "Head")
}

enum class IkHandleId(val label: String) {
    HEAD("Head"),
    TORSO("Torso"),
    LEFT_ELBOW("L.Elbow"),
    LEFT_HAND("L.Hand"),
    RIGHT_ELBOW("R.Elbow"),
    RIGHT_HAND("R.Hand"),
    LEFT_KNEE("L.Knee"),
    LEFT_FOOT("L.Foot"),
    RIGHT_KNEE("R.Knee"),
    RIGHT_FOOT("R.Foot")
}

enum class ChromaKeyMode(val displayName: String, val colorHex: String?) {
    NONE("Normal Stage", null),
    GREEN_SCREEN("Green Screen (#00FF00)", "#00FF00"),
    BLUE_SCREEN("Blue Screen (#0000FF)", "#0000FF")
}

data class PoseKeyframeOffsets(
    val headNodOffset: Float = 0f,
    val torsoTiltOffset: Float = 0f,
    val leftArmOffset: Float = 0f,
    val leftForearmOffset: Float = 0f,
    val rightArmOffset: Float = 0f,
    val rightForearmOffset: Float = 0f,
    val leftLegOffset: Float = 0f,
    val leftKneeOffset: Float = 0f,
    val rightLegOffset: Float = 0f,
    val rightKneeOffset: Float = 0f
) {
    val hasCustomOffsets: Boolean
        get() = headNodOffset != 0f || torsoTiltOffset != 0f ||
            leftArmOffset != 0f || leftForearmOffset != 0f ||
            rightArmOffset != 0f || rightForearmOffset != 0f ||
            leftLegOffset != 0f || leftKneeOffset != 0f ||
            rightLegOffset != 0f || rightKneeOffset != 0f
}

data class CameraKeyframe(
    val frame: Int,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val zoom: Float = 1.0f,
    val rotation: Float = 0f,
    val easing: EasingType = EasingType.EASE_IN_OUT,
    val easingP1X: Float = 0.42f,
    val easingP1Y: Float = 0f,
    val easingP2X: Float = 0.58f,
    val easingP2Y: Float = 1f
)

/**
 * Visual On-Canvas Camera Box ("A", "B", "C"...) rendered as an aspect-ratio-locked frame
 * over the wide Director stage and represented as a trimmable orange clip on the Camera Track.
 */
data class CameraBoxClip(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "A",
    val startFrame: Int = 0,
    val endFrame: Int = 48,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1.0f,
    val rotation: Float = 0f,
    val easing: EasingType = EasingType.EASE_IN_OUT,
    val easingP1X: Float = 0.42f,
    val easingP1Y: Float = 0f,
    val easingP2X: Float = 0.58f,
    val easingP2Y: Float = 1f
) {
    val durationFrames: Int
        get() = (endFrame - startFrame).coerceAtLeast(1)
}

data class SkeletonRigConfig(
    val enabled: Boolean = false,
    val rigMode: CustomRigMode = CustomRigMode.SINGLE_PNG_MESH,
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
    val spriteAtlasRig: SpriteAtlasRigConfig? = null,
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
    DANCING("Dancing", 44, "Action"),
    FIGHTING("Fighting", 30, "Action"),
    FLYING("Flying", 36, "Action"),
    SLEEPING("Sleeping", 60, "Pose"),
    CLAPPING("Clapping", 28, "Gesture"),
    THINKING("Thinking", 48, "Emotion")
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

enum class EasingType(val displayName: String, val shortLabel: String) {
    LINEAR("Linear", "Linear"),
    HOLD("Hold (Step)", "Hold"),
    EASE_IN("Ease In", "Ease In"),
    EASE_OUT("Ease Out", "Ease Out"),
    EASE_IN_OUT("Ease In-Out", "Smooth"),
    BOUNCE("Bounce", "Bounce"),
    ELASTIC("Elastic", "Elastic"),
    CUSTOM_CUBIC("Custom Curve", "Cubic")
}

enum class BrushStyle(val displayName: String) {
    SOLID("Solid"),
    NEON_GLOW("Neon"),
    SKETCH_DASHED("Sketch")
}

enum class TextBubbleStyle(val displayName: String) {
    NONE("Plain"),
    SPEECH("Speech"),
    THOUGHT("Thought"),
    SHOUT("Shout"),
    SUBTITLE("Subtitle")
}

enum class SceneParticleType(val displayName: String, val accentHex: String) {
    NONE("None", "#64748B"),
    RAIN("Rain", "#38BDF8"),
    SNOW("Snow", "#E2E8F0"),
    FIREFLIES("Fireflies", "#FACC15"),
    PETALS("Petals", "#F472B6"),
    SPARKLES("Sparkles", "#A78BFA"),
    CONFETTI("Confetti", "#34D399"),
    EMBERS("Embers", "#FB923C")
}

enum class SceneLightingMood(val displayName: String, val swatchHex: String) {
    NONE("Natural", "#475569"),
    GOLDEN_HOUR("Golden Hour", "#F59E0B"),
    MIDNIGHT_BLUE("Midnight", "#1D4ED8"),
    NOIR_DRAMATIC("Noir", "#334155"),
    NEON_CYBER("Neon Cyber", "#C026D3"),
    WARM_CINEMA("Warm Cinema", "#EA580C")
}

enum class WeatherParticleStyle(val displayName: String, val accentHex: String) {
    NONE("Off", "#64748B"),
    RAIN("Rain", "#38BDF8"),
    FOG("Fog", "#CBD5E1"),
    FALLING_LEAVES("Falling Leaves", "#F97316"),
    FIRE_SPARKS("Fire Sparks", "#FB923C"),
    SMOKE("Smoke", "#94A3B8"),
    DUST_MOTES("Dust Motes", "#FDE047"),
    SNOW("Snow", "#E0F2FE")
}

data class WeatherParticleConfig(
    val enabled: Boolean = false,
    val style: WeatherParticleStyle = WeatherParticleStyle.NONE,
    val density: Float = 0.6f,
    val windSpeed: Float = 0.5f,
    val windAngleDeg: Float = 12f,
    val sizeScale: Float = 1.0f,
    val opacity: Float = 0.78f,
    val startSec: Float = 0f,
    val endSec: Float = -1f, // < 0 means active through end of scene
    val seed: Int = 104729
) {
    val isActive: Boolean
        get() = enabled && style != WeatherParticleStyle.NONE && density > 0.01f && opacity > 0.01f
}

enum class DialogueLanguage(val code: String, val displayName: String, val badge: String) {
    ENGLISH("en", "English", "EN"),
    HINDI("hi", "हिन्दी (Hindi)", "हिन्दी"),
    ODIA("or", "ଓଡ଼ିଆ (Odia)", "ଓଡ଼ିଆ")
}

/**
 * Optional, opt-in animated comic Speech Bubble & Cinema Subtitle beat.
 * Both [showSpeechBubble] and [showSubtitle] default to `false` so nothing renders unless
 * the animator or Director Script explicitly enables them. Supports full Unicode Odia, Hindi, and English.
 */
data class DialogueOverlayBeat(
    val id: String = UUID.randomUUID().toString(),
    val speakerCharacterId: String? = null,
    val speakerClipId: String? = null,
    val speakerName: String = "",
    val text: String = "",
    val language: String = "en",
    val startFrame: Int = 0,
    val endFrame: Int = 72,
    val showSpeechBubble: Boolean = false,
    val showSubtitle: Boolean = false,
    val bubbleStyle: TextBubbleStyle = TextBubbleStyle.SPEECH,
    val bubbleFillHex: String = "#FFFFFF",
    val textColorHex: String = "#0F172A",
    val subtitleBgHex: String = "#0F172A",
    val subtitleTextHex: String = "#F8FAFC"
) {
    val isOptedIn: Boolean
        get() = (showSpeechBubble || showSubtitle) && text.isNotBlank()
}

data class SceneVfxConfig(
    val particleType: SceneParticleType = SceneParticleType.NONE,
    val particleIntensity: Float = 0.65f,
    val particleSpeed: Float = 1.0f,
    val lightingMood: SceneLightingMood = SceneLightingMood.NONE,
    val vignetteIntensity: Float = 0f,
    val cameraShakeIntensity: Float = 0f,
    val weatherConfig: WeatherParticleConfig = WeatherParticleConfig()
) {
    val hasActiveVfx: Boolean
        get() = particleType != SceneParticleType.NONE ||
            lightingMood != SceneLightingMood.NONE ||
            vignetteIntensity > 0.01f ||
            cameraShakeIntensity > 0.01f ||
            weatherConfig.isActive
}

enum class TransitionType(val displayName: String) {
    NONE("None"),
    FADE("Fade"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_UP("Slide Up"),
    ZOOM_POP("Zoom Pop")
}

enum class ClipMaskType(val displayName: String) {
    NONE("None"),
    CIRCLE("Circle"),
    RECTANGLE("Rectangle"),
    ROUNDED_RECT("Rounded Rect"),
    OVAL("Oval"),
    STAR("Star"),
    ARCH("Arch"),
    LINEAR_SPLIT("Linear Split"),
    DIAGONAL_SPLIT("Diagonal Split")
}

enum class TearsFxStyle(val displayName: String) {
    OFF("Off"),
    GLISTENING("Glistening"),
    FLOWING("Flowing"),
    HEAVY_STREAM("Heavy Stream")
}

data class TearsFxConfig(
    val style: TearsFxStyle = TearsFxStyle.OFF,
    val intensity: Float = 0.75f,
    val speed: Float = 1.0f
) {
    val isActive: Boolean get() = style != TearsFxStyle.OFF && intensity > 0.01f
}

enum class ShadowMode(val displayName: String) {
    GROUND_ELLIPSE("Ground Shadow"),
    DROP_SHADOW("Drop Shadow")
}

data class ShadowConfig(
    val enabled: Boolean = false,
    val mode: ShadowMode = ShadowMode.GROUND_ELLIPSE,
    val opacity: Float = 0.42f,
    val softness: Float = 0.45f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 0.32f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val colorHex: String = "#0B101B"
) {
    val isActive: Boolean get() = enabled && opacity > 0.01f
}

data class ClipColorAdjust(
    val brightness: Float = 0f,   // -1f..1f
    val contrast: Float = 1f,     // 0.2f..2.5f
    val saturation: Float = 1f,   // 0f..2.5f
    val hueDeg: Float = 0f,       // -180f..180f
    val warmth: Float = 0f        // -1f..1f
) {
    val isActive: Boolean
        get() = kotlin.math.abs(brightness) > 0.005f ||
            kotlin.math.abs(contrast - 1f) > 0.005f ||
            kotlin.math.abs(saturation - 1f) > 0.005f ||
            kotlin.math.abs(hueDeg) > 0.5f ||
            kotlin.math.abs(warmth) > 0.005f
}

data class ClipMaskConfig(
    val type: ClipMaskType = ClipMaskType.NONE,
    val centerX: Float = 0f,      // Normalized offset -0.5..0.5 from clip center
    val centerY: Float = 0f,      // Normalized offset -0.5..0.5 from clip center
    val width: Float = 0.72f,     // Normalized 0.1..1.4
    val height: Float = 0.72f,    // Normalized 0.1..1.4
    val feather: Float = 0f,      // 0f..1f soft edge feather
    val invert: Boolean = false
) {
    fun resolvedType(legacyMaskType: ClipMaskType): ClipMaskType =
        if (type != ClipMaskType.NONE) type else legacyMaskType
}

enum class LayerBlendMode(val displayName: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    ADD("Add"),
    DARKEN("Darken"),
    LIGHTEN("Lighten")
}

enum class CameraShotType(
    val displayName: String,
    val defaultZoom: Float,
    val defaultRollDeg: Float = 0f,
    val focusHeadBiasY: Float = 0f
) {
    WIDE_ESTABLISHING("Wide Establishing", 1.0f, 0f, 0f),
    MEDIUM_TWO_SHOT("Medium Two-Shot", 1.28f, 0f, -0.04f),
    CLOSE_UP_SPEAKER("Close-Up Speaker", 1.85f, 0f, -0.14f),
    EXTREME_CLOSE_UP_REACTION("Extreme Close-Up Reaction", 2.45f, 0f, -0.18f),
    OVER_THE_SHOULDER("Over The Shoulder", 1.65f, 0f, -0.10f),
    SLOW_PUSH_IN("Slow Push-In", 1.15f, 0f, -0.06f),
    WHIP_PAN("Whip Pan", 1.35f, 0f, -0.04f),
    DUTCH_TILT("Dutch Tilt", 1.30f, -10f, -0.04f),
    CAMERA_SHAKE("Camera Shake", 1.25f, 0f, -0.05f)
}

enum class InlineSvgAssetType(val displayName: String) {
    CHARACTER("Character"),
    TREE("Tree"),
    ANIMAL("Animal"),
    PROP("Prop"),
    BG("Background")
}

data class SvgSubPartPivot(
    val nodeId: String,
    val pivotX: Float,
    val pivotY: Float
)

data class InlineSvgAssetDef(
    val id: String,
    val type: InlineSvgAssetType = InlineSvgAssetType.PROP,
    val viewBox: String = "0 0 256 256",
    val svgXml: String,
    val localSvgPath: String? = null,
    val subPartPivots: List<SvgSubPartPivot> = emptyList()
)

enum class CustomAnimLoopMode(val displayName: String) {
    ONCE("Once"),
    LOOP("Loop"),
    PING_PONG("Ping-Pong")
}

data class CustomAnimKeyframe(
    val t: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Float = 1f,
    val cubicBezier: List<Float> = listOf(0.42f, 0f, 0.58f, 1f)
)

data class CustomAnimTrack(
    val targetNodeId: String,
    val keyframes: List<CustomAnimKeyframe> = emptyList()
)

data class CustomAnimationDef(
    val id: String,
    val durationSec: Float = 1.0f,
    val loopMode: CustomAnimLoopMode = CustomAnimLoopMode.LOOP,
    val tracks: List<CustomAnimTrack> = emptyList()
)

enum class CustomTransitionClipType(val displayName: String) {
    NONE("None"),
    WIPE_ANGLE("Wipe Angle"),
    IRIS_CIRCLE("Iris Circle"),
    SPLIT_CURTAIN("Split Curtain"),
    CUSTOM_SVG_PATH("Custom SVG Path")
}

data class CustomTransitionTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f
)

data class CustomTransitionFlash(
    val colorHex: String = "#FFFFFF",
    val peakAlpha: Float = 0f
)

data class CustomTransitionDef(
    val id: String,
    val durationSec: Float = 0.6f,
    val clipPathType: CustomTransitionClipType = CustomTransitionClipType.WIPE_ANGLE,
    val angleDeg: Float = 0f,
    val customSvgPath: String? = null,
    val outgoing: CustomTransitionTransform = CustomTransitionTransform(),
    val incoming: CustomTransitionTransform = CustomTransitionTransform(),
    val flashOverlay: CustomTransitionFlash? = null,
    val cubicBezier: List<Float> = listOf(0.42f, 0f, 0.58f, 1f)
)

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

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class DrawingStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<NormalizedPoint>,
    val colorHex: String = "#38BDF8",
    val strokeWidthRatio: Float = 0.008f,
    val brushStyle: BrushStyle = BrushStyle.SOLID
)

enum class ArmGestureTrackPreset(
    val displayName: String,
    val defaultHandPose: HandPoseType
) {
    NONE("None (Body Default)", HandPoseType.OPEN),
    WAVE("Wave", HandPoseType.OPEN),
    POINT("Point Forward", HandPoseType.POINT),
    HOLD_PROP("Hold / Carry", HandPoseType.PROP_GRIP),
    OPEN_WELCOME("Open Welcome", HandPoseType.OPEN),
    FIST_PUMP("Fist Pump", HandPoseType.FIST),
    THINK_CHIN("Think (Chin)", HandPoseType.POINT),
    CLAP("Clap Beat", HandPoseType.OPEN)
}

enum class SimultaneousTrackType(val displayName: String) {
    HAND_GESTURE("Hand/Arm Gesture"),
    FACIAL_OVERRIDE("Facial Override"),
    LIP_SYNC("Auto Audio Lip-Sync")
}

/**
 * Non-destructive simultaneous character sub-track layered over a single character clip's
 * base body/legs preset without overwriting unrelated channels.
 */
data class SimultaneousTrackClip(
    val id: String = UUID.randomUUID().toString(),
    val trackType: SimultaneousTrackType,
    val startLocalFrame: Int = 0,
    val durationFrames: Int = 24,
    val armGesturePreset: ArmGestureTrackPreset = ArmGestureTrackPreset.NONE,
    val handPoseOverride: HandPoseType? = null,
    val expressionOverride: FacialExpression? = null,
    val facialPresetId: String? = null,
    val mouthOverride: MouthShapeOverride? = null,
    val eyeOverride: EyeShapeOverride? = null,
    val eyebrowOverride: EyebrowShapeOverride? = null,
    val weight: Float = 1.0f
)

data class TransformKeyframe(
    val frame: Int,
    val x: Float = 0.5f,          // Normalized 0..1 of canvas width
    val y: Float = 0.55f,         // Normalized 0..1 of canvas height
    val rotation: Float = 0f,     // Degrees
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Float = 1f,
    val easing: EasingType = EasingType.LINEAR,
    val easingP1X: Float = 0.42f,
    val easingP1Y: Float = 0f,
    val easingP2X: Float = 0.58f,
    val easingP2Y: Float = 1f,
    val poseOffsets: PoseKeyframeOffsets = PoseKeyframeOffsets(),
    val expressionOverride: FacialExpression? = null,
    val facialPresetId: String? = null,
    val mouthShapeOverride: MouthShapeOverride? = null,
    val eyeShapeOverride: EyeShapeOverride? = null,
    val eyebrowShapeOverride: EyebrowShapeOverride? = null,
    val handPoseOverride: HandPoseType? = null,
    val armGestureOverride: ArmGestureTrackPreset? = null,
    val blinkOverride: Boolean? = null
)

data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val name: String,
    val startFrame: Int = 0,
    val durationFrames: Int = 96,
    val characterId: String? = null,
    val orientation: CharacterOrientation = CharacterOrientation.FRONT,
    val animationPresetType: AnimationPresetType = AnimationPresetType.IDLE,
    val facialExpression: FacialExpression = FacialExpression.AUTO,
    val facialOverridesEnabled: Boolean = true,
    val mouthShapeOverride: MouthShapeOverride = MouthShapeOverride.AUTO,
    val eyeShapeOverride: EyeShapeOverride = EyeShapeOverride.AUTO,
    val eyebrowShapeOverride: EyebrowShapeOverride = EyebrowShapeOverride.AUTO,
    val handPose: HandPoseType = HandPoseType.OPEN,
    val armGesturePreset: ArmGestureTrackPreset = ArmGestureTrackPreset.NONE,
    val simultaneousTracks: List<SimultaneousTrackClip> = emptyList(),
    val forceBlink: Boolean = false,
    val propType: CharacterPropType = CharacterPropType.NONE,
    val propAttachment: PropAttachmentPoint = PropAttachmentPoint.RIGHT_HAND,
    val customPropImagePath: String? = null,
    val propSwayEnabled: Boolean = true,
    val propSwayStrength: Float = 1.0f,
    val propSwayStiffness: Float = 45f,
    val propSwayDamping: Float = 5.5f,
    val propSwayMaxAngleDeg: Float = 42f,
    val propScale: Float = 1.0f,
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
    val textBubbleStyle: TextBubbleStyle = TextBubbleStyle.NONE,
    val textBubbleFillHex: String = "#FFFFFF",
    val shapeKind: ShapeKind? = null,
    val shapeFillHex: String = "#38BDF8",
    val shapeStrokeHex: String = "#F8FAFC",
    val audioPath: String? = null,
    val audioLabel: String? = null,
    val audioTrackRole: AudioTrackRole = AudioTrackRole.BGM,
    val audioVolume: Float = 1.0f,
    val audioTrimStartMs: Long = 0L,
    val audioTrimEndMs: Long = 0L,
    val drawingStrokes: List<DrawingStroke> = emptyList(),
    val keyframes: List<TransformKeyframe> = listOf(TransformKeyframe(frame = 0)),
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val blackShade: Float = 0f,
    val depthOrder: Int = 0,
    val inlineSvgAssetId: String? = null,
    val customAnimationId: String? = null,
    val maskType: ClipMaskType = ClipMaskType.NONE,
    val maskRadius: Float = 0.46f,
    val maskConfig: ClipMaskConfig = ClipMaskConfig(),
    val tearsConfig: TearsFxConfig = TearsFxConfig(),
    val shadowConfig: ShadowConfig = ShadowConfig(),
    val colorAdjust: ClipColorAdjust = ClipColorAdjust(),
    val motionPathControlPoints: List<NormalizedPoint> = emptyList(),
    val lipSyncAudioClipId: String? = null
) {
    val endFrame: Int get() = startFrame + durationFrames
    val effectiveMaskType: ClipMaskType get() = maskConfig.resolvedType(maskType)
}

enum class ParallaxDepthPreset(val displayName: String, val depth: Float) {
    FAR("Far", 0.2f),
    MID("Mid", 0.6f),
    STAGE("Stage", 1.0f),
    NEAR("Near", 1.35f)
}

data class AnimationLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LayerType,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val colorHex: String = type.defaultColorHex,
    val orderIndex: Int = 0,
    val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val parentLayerId: String? = null,
    val parallaxDepth: Float = 1.0f
)

data class AnimationScene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scene 1",
    val backgroundColorHex: String = "#162033",
    val backgroundBundledKey: String? = null,
    val backgroundImagePath: String? = null,
    val chromaKeyMode: ChromaKeyMode = ChromaKeyMode.NONE,
    val cameraKeyframes: List<CameraKeyframe> = emptyList(),
    val cameraBoxes: List<CameraBoxClip> = emptyList(),
    val cameraFocusClipId: String? = null,
    val cameraLayerEnabled: Boolean = true,
    val cameraLayerLocked: Boolean = false,
    val cameraLayerName: String = "Camera Control",
    val vfxConfig: SceneVfxConfig = SceneVfxConfig(),
    val weatherLayers: List<WeatherParticleConfig> = emptyList(),
    val dialogueOverlays: List<DialogueOverlayBeat> = emptyList(),
    val durationFrames: Int = 144,
    val layers: List<AnimationLayer> = emptyList(),
    val clips: List<TimelineClip> = emptyList(),
    val customTransitionInId: String? = null,
    val customTransitionOutId: String? = null,
    val universalKeyframeTracks: List<UniversalKeyframeTrack> = emptyList()
)

/**
 * Frame-aligned phoneme & mouth-shape cue derived from an audio track's amplitude envelope.
 * Stored inside [ProjectDocument.audioSyncTracks] for offline lip-sync persistence & sharing.
 */
data class PhonemeFrameMapping(
    val frame: Int,
    val timeMs: Long,
    val phoneme: String,      // "REST", "MBP", "EE", "AA", "OH"
    val mouthShape: String,   // "MTH1".."MTH4"
    val amplitude: Float
)

/**
 * Duration-based volume curve control point describing audio gain/envelope over time.
 */
data class VolumeCurvePoint(
    val timeMs: Long,
    val frame: Int,
    val gain: Float           // Normalized volume envelope / gain (0..1)
)

/**
 * Structured audio-sync metadata persisted in the project document per audio clip,
 * tracking trim window, waveform display sensitivity, phoneme frame mappings, and volume curve.
 */
data class AudioSyncMetadata(
    val clipId: String,
    val clipName: String,
    val fps: Int = 24,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val waveformSensitivity: Float = 1.0f,
    val phonemeMappings: List<PhonemeFrameMapping> = emptyList(),
    val volumeCurve: List<VolumeCurvePoint> = emptyList()
)

enum class AudioTrackRole(val displayName: String) {
    DIALOGUE("Dialogue"),
    BGM("BGM"),
    SFX("SFX")
}

/**
 * Smart Auto Audio Ducking configuration for offline playback and MP4 audio mixing.
 * Automatically ducks BGM tracks smoothly whenever any DIALOGUE track exceeds [voiceThreshold].
 */
data class AudioDuckingConfig(
    val duckingEnabled: Boolean = true,
    val duckTargetVolume: Float = 0.25f, // 0.05f..0.85f
    val attackMs: Int = 180,             // 20..1000 ms
    val releaseMs: Int = 420,            // 50..2000 ms
    val voiceThreshold: Float = 0.12f    // 0.02f..0.60f
)

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
    val audioSyncTracks: List<AudioSyncMetadata> = emptyList(),
    val audioDucking: AudioDuckingConfig = AudioDuckingConfig(),
    val inlineSvgAssets: Map<String, InlineSvgAssetDef> = emptyMap(),
    val customAnimations: Map<String, CustomAnimationDef> = emptyMap(),
    val customTransitions: Map<String, CustomTransitionDef> = emptyMap(),
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
