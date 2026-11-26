package com.example.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

enum class AtlasPoseTemplate(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val badgeText: String,
    val defaultOrientation: CharacterOrientation
) {
    THREE_QUARTER_FRONT(
        id = "three_quarter_front",
        displayName = "3-4 Front",
        subtitle = "Dynamic cartoon storytelling angle with natural depth layering & expressive face rig.",
        badgeText = "Recommended",
        defaultOrientation = CharacterOrientation.THREE_QUARTER_FRONT
    ),
    FRONT(
        id = "front",
        displayName = "Front",
        subtitle = "Symmetrical straight-on pose ideal for dialogue, lip-sync closeups, and presenter scenes.",
        badgeText = "Dialogue Rig",
        defaultOrientation = CharacterOrientation.FRONT
    ),
    BACK_SIDE(
        id = "back_side",
        displayName = "Back / Side",
        subtitle = "Profile & over-the-shoulder action rig built for walk cycles, running, and turns.",
        badgeText = "Action Walk",
        defaultOrientation = CharacterOrientation.SIDE
    );

    companion object {
        fun fromId(raw: String?): AtlasPoseTemplate =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: THREE_QUARTER_FRONT
    }
}

enum class AtlasResolution(val px: Int, val label: String) {
    RES_2048(2048, "2048×2048"),
    RES_4096(4096, "4096×4096");

    companion object {
        fun fromPx(px: Int): AtlasResolution = if (px >= 4096) RES_4096 else RES_2048
    }
}

enum class HandPoseType(val displayName: String, val shortLabel: String) {
    OPEN("Open Hand", "Open"),
    FIST("Closed Fist", "Fist"),
    POINT("Pointing", "Point"),
    PROP_GRIP("Prop Grip", "Prop")
}

enum class MakerTestAnimation(
    val displayName: String,
    val groupLabel: String,
    val preset: AnimationPresetType,
    val expression: FacialExpression,
    val handPose: HandPoseType,
    val mouthSlotOverride: AtlasPartSlot? = null,
    val blinkOverride: Boolean? = null
) {
    IDLE("Idle", "Motion", AnimationPresetType.IDLE, FacialExpression.NEUTRAL, HandPoseType.OPEN),
    WALK("Walk", "Motion", AnimationPresetType.WALK, FacialExpression.HAPPY, HandPoseType.OPEN),
    RUN("Run", "Motion", AnimationPresetType.RUNNING, FacialExpression.HAPPY, HandPoseType.FIST),
    EYE_CLOSE("Eye Close / Blink", "Face", AnimationPresetType.IDLE, FacialExpression.SLEEPING, HandPoseType.OPEN, blinkOverride = true),
    TALK_1("Talk 1 (Calm)", "Talk", AnimationPresetType.TALKING, FacialExpression.TALKING, HandPoseType.OPEN),
    TALK_2("Talk 2 (Happy)", "Talk", AnimationPresetType.TALKING, FacialExpression.HAPPY, HandPoseType.OPEN),
    TALK_3("Talk 3 (Pointing)", "Talk", AnimationPresetType.TALKING, FacialExpression.TALKING, HandPoseType.POINT),
    TALK_4("Talk 4 (Excited)", "Talk", AnimationPresetType.IDLE_HAPPY, FacialExpression.LAUGHING, HandPoseType.OPEN),
    TALK_5("Talk 5 (Angry)", "Talk", AnimationPresetType.TALKING, FacialExpression.ANGRY, HandPoseType.FIST),
    TALK_6("Talk 6 (Sad)", "Talk", AnimationPresetType.TALKING, FacialExpression.SAD, HandPoseType.OPEN),
    TALK_7("Talk 7 (Shocked)", "Talk", AnimationPresetType.TALKING, FacialExpression.SHOCKED, HandPoseType.OPEN),
    TALK_8("Talk 8 (Waving)", "Talk", AnimationPresetType.WAVING, FacialExpression.TALKING, HandPoseType.OPEN),
    TALK_9("Talk 9 (Prop Speech)", "Talk", AnimationPresetType.TALKING, FacialExpression.HAPPY, HandPoseType.PROP_GRIP),
    TALK_10("Talk 10 (Walk & Talk)", "Talk", AnimationPresetType.WALK, FacialExpression.TALKING, HandPoseType.OPEN),
    HAND_OPEN("Hand: Open", "Hands", AnimationPresetType.WAVING, FacialExpression.HAPPY, HandPoseType.OPEN),
    HAND_FIST("Hand: Fist", "Hands", AnimationPresetType.IDLE, FacialExpression.ANGRY, HandPoseType.FIST),
    HAND_POINT("Hand: Point", "Hands", AnimationPresetType.TALKING, FacialExpression.NEUTRAL, HandPoseType.POINT),
    HAND_PROP("Hand: Prop", "Hands", AnimationPresetType.WAVING, FacialExpression.HAPPY, HandPoseType.PROP_GRIP),
    MTH1("Mouth: MTH1 (Idle)", "Mouth", AnimationPresetType.IDLE, FacialExpression.NEUTRAL, HandPoseType.OPEN, AtlasPartSlot.LIP_IDLE),
    MTH2("Mouth: MTH2 (Happy)", "Mouth", AnimationPresetType.IDLE_HAPPY, FacialExpression.HAPPY, HandPoseType.OPEN, AtlasPartSlot.LIP_HAPPY),
    MTH3("Mouth: MTH3 (Scared)", "Mouth", AnimationPresetType.TALKING, FacialExpression.SAD, HandPoseType.OPEN, AtlasPartSlot.LIP_SCARED),
    MTH4("Mouth: MTH4 (Angry)", "Mouth", AnimationPresetType.TALKING, FacialExpression.ANGRY, HandPoseType.FIST, AtlasPartSlot.LIP_ANGRY)
}

enum class AtlasSlotCategory(val displayName: String) {
    CORE("Core & Hair"),
    FACE("Eyes, Brows & Lips"),
    ARMS("Arms"),
    LEGS("Legs"),
    HANDS("Hands & Props")
}

/**
 * Fixed, strictly non-overlapping normalized (0..1) sprite-atlas slot layout supporting both
 * 2048x2048 and 4096x4096 transparent PNG and crisp vector SVG sprite sheets.
 */
enum class AtlasPartSlot(
    val id: String,
    val label: String,
    val shortCode: String,
    val category: AtlasSlotCategory,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
    val pivotU: Float,
    val pivotV: Float,
    val guideColorHex: String
) {
    // Band 1: Core Head, Hair & Chest (v: 0.01..0.29)
    HEAD("head", "Head", "HEAD", AtlasSlotCategory.CORE, 0.01f, 0.01f, 0.24f, 0.29f, 0.50f, 0.86f, "#38BDF8"),
    HAIR_FRONT("hair_front", "Hair Front", "HAIR_F", AtlasSlotCategory.CORE, 0.25f, 0.01f, 0.48f, 0.29f, 0.50f, 0.50f, "#A78BFA"),
    HAIR_BACK("hair_back", "Hair Back", "HAIR_B", AtlasSlotCategory.CORE, 0.49f, 0.01f, 0.72f, 0.29f, 0.50f, 0.45f, "#818CF8"),
    CHEST_BODY("chest_body", "Chest / Body", "BODY", AtlasSlotCategory.CORE, 0.73f, 0.01f, 0.99f, 0.29f, 0.50f, 0.55f, "#10B981"),

    // Band 2A: Eyes & Eyebrows (v: 0.31..0.40)
    EYE_WHITE("eye_white", "Eye White", "EYE_W", AtlasSlotCategory.FACE, 0.01f, 0.31f, 0.15f, 0.40f, 0.50f, 0.50f, "#E2E8F0"),
    EYEBALL("eyeball", "Eyeballs", "PUPIL", AtlasSlotCategory.FACE, 0.16f, 0.31f, 0.30f, 0.40f, 0.50f, 0.50f, "#22D3EE"),
    EYELID("eyelid", "Eyelids / Lashes", "LID", AtlasSlotCategory.FACE, 0.31f, 0.31f, 0.45f, 0.40f, 0.50f, 0.50f, "#F472B6"),
    BROW_IDLE("brow_idle", "Eyebrow Idle", "BRW_ID", AtlasSlotCategory.FACE, 0.46f, 0.31f, 0.59f, 0.40f, 0.50f, 0.50f, "#FBBF24"),
    BROW_HAPPY("brow_happy", "Eyebrow Happy", "BRW_HP", AtlasSlotCategory.FACE, 0.60f, 0.31f, 0.73f, 0.40f, 0.50f, 0.50f, "#34D399"),
    BROW_ANGRY("brow_angry", "Eyebrow Angry", "BRW_AN", AtlasSlotCategory.FACE, 0.74f, 0.31f, 0.86f, 0.40f, 0.50f, 0.50f, "#F87171"),
    BROW_SCARED("brow_scared", "Eyebrow Scared", "BRW_SC", AtlasSlotCategory.FACE, 0.87f, 0.31f, 0.99f, 0.40f, 0.50f, 0.50f, "#C084FC"),

    // Band 2B: Mouth / Lips (MTH1-MTH4 + Shocked) & Accessories (v: 0.41..0.50)
    LIP_IDLE("lip_idle", "Mouth / Lip Idle (MTH1)", "MTH1", AtlasSlotCategory.FACE, 0.01f, 0.41f, 0.16f, 0.50f, 0.50f, 0.50f, "#FB7185"),
    LIP_HAPPY("lip_happy", "Mouth / Lip Happy (MTH2)", "MTH2", AtlasSlotCategory.FACE, 0.17f, 0.41f, 0.32f, 0.50f, 0.50f, 0.50f, "#F43F5E"),
    LIP_SCARED("lip_scared", "Mouth / Lip Scared (MTH3)", "MTH3", AtlasSlotCategory.FACE, 0.33f, 0.41f, 0.48f, 0.50f, 0.50f, 0.50f, "#EC4899"),
    LIP_ANGRY("lip_angry", "Mouth / Lip Angry (MTH4)", "MTH4", AtlasSlotCategory.FACE, 0.49f, 0.41f, 0.64f, 0.50f, 0.50f, 0.50f, "#EF4444"),
    LIP_SHOCKED("lip_shocked", "Mouth / Lip Shocked", "MTH_O", AtlasSlotCategory.FACE, 0.65f, 0.41f, 0.80f, 0.50f, 0.50f, 0.50f, "#F97316"),
    ACCESSORY("accessory", "Accessories (Hat/Glasses)", "ACC", AtlasSlotCategory.CORE, 0.81f, 0.41f, 0.99f, 0.50f, 0.50f, 0.72f, "#FACC15"),

    // Band 3: Upper & Lower Arms and Legs (v: 0.51..0.79)
    L_UPPER_ARM("l_upper_arm", "Left Upper Arm", "L_UARM", AtlasSlotCategory.ARMS, 0.010f, 0.51f, 0.125f, 0.79f, 0.50f, 0.14f, "#F59E0B"),
    L_LOWER_ARM("l_lower_arm", "Left Lower Arm", "L_LARM", AtlasSlotCategory.ARMS, 0.135f, 0.51f, 0.250f, 0.79f, 0.50f, 0.14f, "#FBBF24"),
    R_UPPER_ARM("r_upper_arm", "Right Upper Arm", "R_UARM", AtlasSlotCategory.ARMS, 0.260f, 0.51f, 0.375f, 0.79f, 0.50f, 0.14f, "#EC4899"),
    R_LOWER_ARM("r_lower_arm", "Right Lower Arm", "R_LARM", AtlasSlotCategory.ARMS, 0.385f, 0.51f, 0.500f, 0.79f, 0.50f, 0.14f, "#F472B6"),
    L_UPPER_LEG("l_upper_leg", "Left Upper Leg", "L_ULEG", AtlasSlotCategory.LEGS, 0.510f, 0.51f, 0.625f, 0.79f, 0.50f, 0.14f, "#8B5CF6"),
    L_LOWER_LEG("l_lower_leg", "Left Lower Leg", "L_LLEG", AtlasSlotCategory.LEGS, 0.635f, 0.51f, 0.750f, 0.79f, 0.50f, 0.14f, "#A78BFA"),
    R_UPPER_LEG("r_upper_leg", "Right Upper Leg", "R_ULEG", AtlasSlotCategory.LEGS, 0.760f, 0.51f, 0.875f, 0.79f, 0.50f, 0.14f, "#FB923C"),
    R_LOWER_LEG("r_lower_leg", "Right Lower Leg", "R_LLEG", AtlasSlotCategory.LEGS, 0.885f, 0.51f, 0.995f, 0.79f, 0.50f, 0.14f, "#FDBA74"),

    // Band 4: Hand Poses & Prop Hands (v: 0.805..0.990)
    HAND_OPEN("hand_open", "Hand Pose: Open", "H_OPEN", AtlasSlotCategory.HANDS, 0.010f, 0.805f, 0.195f, 0.990f, 0.50f, 0.18f, "#2DD4BF"),
    HAND_FIST("hand_fist", "Hand Pose: Fist", "H_FIST", AtlasSlotCategory.HANDS, 0.205f, 0.805f, 0.390f, 0.990f, 0.50f, 0.18f, "#14B8A6"),
    HAND_POINT("hand_point", "Hand Pose: Point", "H_PNT", AtlasSlotCategory.HANDS, 0.400f, 0.805f, 0.585f, 0.990f, 0.50f, 0.18f, "#06B6D4"),
    PROP_HAND_L("prop_hand_l", "Prop Hand (Left)", "PROP_L", AtlasSlotCategory.HANDS, 0.595f, 0.805f, 0.790f, 0.990f, 0.50f, 0.18f, "#38BDF8"),
    PROP_HAND_R("prop_hand_r", "Prop Hand (Right)", "PROP_R", AtlasSlotCategory.HANDS, 0.800f, 0.805f, 0.990f, 0.990f, 0.50f, 0.18f, "#60A5FA");

    val widthU: Float get() = u1 - u0
    val heightV: Float get() = v1 - v0

    fun containsNormalized(u: Float, v: Float): Boolean = u in u0..u1 && v in v0..v1

    companion object {
        fun fromId(id: String?): AtlasPartSlot? = entries.firstOrNull { it.id == id }
        fun hitTest(u: Float, v: Float): AtlasPartSlot? = entries.firstOrNull { it.containsNormalized(u, v) }
    }
}

data class AtlasPartPlacement(
    val slotId: String,
    val partImagePath: String? = null,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val svgInline: String? = null
)

data class SpriteAtlasRigConfig(
    val poseTemplate: AtlasPoseTemplate = AtlasPoseTemplate.THREE_QUARTER_FRONT,
    val resolutionPx: Int = 2048,
    val textureName: String = "CustomHero",
    val renderMode: String = "PNG", // "PNG" or "SVG"
    val importedFullSheetPath: String? = null,
    val inlineAtlasSvg: String? = null,
    val placements: Map<String, AtlasPartPlacement> = emptyMap(),
    val metadataJsonPath: String? = null
) {
    fun placementFor(slot: AtlasPartSlot): AtlasPartPlacement =
        placements[slot.id] ?: AtlasPartPlacement(slotId = slot.id)

    fun isSlotVisible(slot: AtlasPartSlot): Boolean = placements[slot.id]?.visible ?: true
    fun isSlotLocked(slot: AtlasPartSlot): Boolean = placements[slot.id]?.locked ?: false
    val isSvgMode: Boolean
        get() = renderMode.equals("SVG", ignoreCase = true) ||
            importedFullSheetPath?.endsWith(".svg", ignoreCase = true) == true ||
            !inlineAtlasSvg.isNullOrBlank()
}

/**
 * Clean, human-readable FrameCraft Character Code Format (.fcchar.json) schema v1.
 * Enables artists and developers to author, copy/paste, share, and import/export crisp
 * 30-slot SVG or PNG character rigs completely offline.
 */
data class CharacterCodeSlot(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val assetPath: String? = null,
    val svgPathData: String? = null
)

data class FrameCraftCharacterCode(
    val schemaVersion: Int = 1,
    val id: String? = null,
    val name: String = "CustomHero",
    val category: String = "DESI",
    val orientation: String = "THREE_QUARTER_FRONT",
    val poseTemplate: String = "three_quarter_front",
    val atlasSize: Int = 2048,
    val renderMode: String = "SVG",
    val atlasFile: String? = null,
    val atlasSvg: String? = null,
    val slots: Map<String, CharacterCodeSlot> = emptyMap(),
    val animations: List<String> = listOf("idle", "walk", "run", "talk1", "mth1", "mth2", "mth3", "mth4")
)

object CharacterCodeCodec {
    private val moshi: Moshi by lazy {
        Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }
    private val codeAdapter by lazy {
        moshi.adapter(FrameCraftCharacterCode::class.java).indent("  ")
    }
    private val atlasConfigAdapter by lazy {
        moshi.adapter(SpriteAtlasRigConfig::class.java)
    }

    fun toJson(code: FrameCraftCharacterCode): String = codeAdapter.toJson(code)

    fun fromJson(rawJson: String): FrameCraftCharacterCode? {
        if (rawJson.length > 4 * 1024 * 1024) return null
        return runCatching {
            val parsed = codeAdapter.fromJson(rawJson) ?: return null
            if (parsed.schemaVersion !in 1..2) return null
            parsed
        }.getOrNull()
    }

    fun encodeAtlasConfig(config: SpriteAtlasRigConfig): String = atlasConfigAdapter.toJson(config)
    fun decodeAtlasConfig(rawJson: String): SpriteAtlasRigConfig? =
        runCatching { atlasConfigAdapter.fromJson(rawJson) }.getOrNull()

    fun fromRigConfig(
        config: SpriteAtlasRigConfig,
        category: CharacterCategory = CharacterCategory.LATEST,
        atlasFileName: String? = null,
        inlineSvgAtlas: String? = null
    ): FrameCraftCharacterCode {
        val slotsMap = LinkedHashMap<String, CharacterCodeSlot>()
        for (slot in AtlasPartSlot.entries) {
            val p = config.placementFor(slot)
            if (!p.partImagePath.isNullOrBlank() || !p.svgInline.isNullOrBlank() ||
                !p.visible || p.locked || p.offsetX != 0f || p.offsetY != 0f ||
                p.scale != 1f || p.rotationDeg != 0f
            ) {
                slotsMap[slot.id] = CharacterCodeSlot(
                    x = p.offsetX,
                    y = p.offsetY,
                    scale = p.scale,
                    rotation = p.rotationDeg,
                    pivotX = slot.pivotU,
                    pivotY = slot.pivotV,
                    visible = p.visible,
                    locked = p.locked,
                    assetPath = p.partImagePath?.substringAfterLast('/'),
                    svgPathData = p.svgInline
                )
            }
        }
        return FrameCraftCharacterCode(
            schemaVersion = 1,
            name = config.textureName.trim().ifBlank { "CustomHero" },
            category = category.name,
            orientation = config.poseTemplate.defaultOrientation.name,
            poseTemplate = config.poseTemplate.id,
            atlasSize = if (config.resolutionPx >= 4096) 4096 else 2048,
            renderMode = if (config.isSvgMode || !inlineSvgAtlas.isNullOrBlank()) "SVG" else "PNG",
            atlasFile = atlasFileName,
            atlasSvg = inlineSvgAtlas ?: config.inlineAtlasSvg,
            slots = slotsMap,
            animations = listOf("idle", "walk", "run", "eyeclose", "talk1", "talk2", "mth1", "mth2", "mth3", "mth4")
        )
    }

    fun toRigConfig(
        code: FrameCraftCharacterCode,
        resolvedAtlasPath: String? = null,
        resolvedSlotPaths: Map<String, String> = emptyMap()
    ): SpriteAtlasRigConfig {
        val placements = LinkedHashMap<String, AtlasPartPlacement>()
        for ((slotId, slotData) in code.slots) {
            val validSlot = AtlasPartSlot.fromId(slotId) ?: continue
            placements[validSlot.id] = AtlasPartPlacement(
                slotId = validSlot.id,
                partImagePath = resolvedSlotPaths[validSlot.id],
                visible = slotData.visible,
                locked = slotData.locked,
                offsetX = slotData.x.coerceIn(-0.6f, 0.6f),
                offsetY = slotData.y.coerceIn(-0.6f, 0.6f),
                scale = slotData.scale.coerceIn(0.25f, 2.8f),
                rotationDeg = slotData.rotation,
                svgInline = slotData.svgPathData
            )
        }
        val pose = AtlasPoseTemplate.fromId(code.poseTemplate)
        return SpriteAtlasRigConfig(
            poseTemplate = pose,
            resolutionPx = if (code.atlasSize >= 4096) 4096 else 2048,
            textureName = code.name.trim().ifBlank { "CustomHero" },
            renderMode = if (code.renderMode.equals("SVG", ignoreCase = true)) "SVG" else "PNG",
            importedFullSheetPath = resolvedAtlasPath,
            inlineAtlasSvg = code.atlasSvg,
            placements = placements
        )
    }
}
