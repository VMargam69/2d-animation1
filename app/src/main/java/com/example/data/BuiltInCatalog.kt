package com.example.data

import com.example.R
import com.example.model.*
import java.util.UUID

object BuiltInCatalog {

    const val BG_VILLAGE_SUNSET = "bg_village_sunset"
    const val BG_CITY_TWILIGHT = "bg_city_twilight"
    const val BG_FOREST_MEADOW = "bg_forest_meadow"

    fun bundledBackgroundDrawableRes(key: String?): Int? = when (key) {
        BG_VILLAGE_SUNSET -> R.drawable.img_bg_village_sunset_1795538340845
        BG_CITY_TWILIGHT -> R.drawable.img_bg_city_twilight_1795538340690
        BG_FOREST_MEADOW -> R.drawable.img_bg_forest_meadow_1795538340989
        else -> null
    }

    val bundledMediaAssets: List<LocalMediaAsset> = listOf(
        LocalMediaAsset(
            id = "bundled_bg_village",
            name = "Village Sunset Lane",
            category = "Village",
            type = MediaAssetType.BACKGROUND,
            bundledResKey = BG_VILLAGE_SUNSET,
            mimeType = "image/jpeg",
            isBundled = true
        ),
        LocalMediaAsset(
            id = "bundled_bg_city",
            name = "City Twilight Street",
            category = "Western",
            type = MediaAssetType.BACKGROUND,
            bundledResKey = BG_CITY_TWILIGHT,
            mimeType = "image/jpeg",
            isBundled = true
        ),
        LocalMediaAsset(
            id = "bundled_bg_forest",
            name = "Emerald Forest Clearing",
            category = "Nature",
            type = MediaAssetType.BACKGROUND,
            bundledResKey = BG_FOREST_MEADOW,
            mimeType = "image/jpeg",
            isBundled = true
        )
    )

    val builtInCharacters: List<CharacterRigDefinition> = listOf(
        // DESI
        CharacterRigDefinition(
            id = "char_aarav_desi",
            name = "Aarav Kurta",
            category = CharacterCategory.DESI,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#D99B6C",
            primaryColorHex = "#E67E22",
            secondaryColorHex = "#F8FAFC",
            accentColorHex = "#F59E0B",
            hairColorHex = "#1B1715",
            eyeColorHex = "#2A1B12",
            costumeStyle = HumanCostumeStyle.KURTA_TRADITIONAL,
            hairStyle = HumanHairStyle.SIDE_PART,
            buildType = HumanBuildType.ATHLETIC,
            accessory = CharacterAccessory.SCARF,
            isLatest = true
        ),
        CharacterRigDefinition(
            id = "char_meera_desi",
            name = "Meera Festive",
            category = CharacterCategory.DESI,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#DDA172",
            primaryColorHex = "#E11D48",
            secondaryColorHex = "#F59E0B",
            accentColorHex = "#FDE047",
            hairColorHex = "#1A1412",
            eyeColorHex = "#291711",
            costumeStyle = HumanCostumeStyle.SAREE_DRAPE,
            hairStyle = HumanHairStyle.TRADITIONAL_BUN,
            buildType = HumanBuildType.SLIM,
            accessory = CharacterAccessory.NONE,
            isLatest = true
        ),
        // VILLAGE
        CharacterRigDefinition(
            id = "char_kisan_village",
            name = "Kisan Babu",
            category = CharacterCategory.VILLAGE,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#C68652",
            primaryColorHex = "#F8FAFC",
            secondaryColorHex = "#E2E8F0",
            accentColorHex = "#EA580C",
            hairColorHex = "#292524",
            eyeColorHex = "#231914",
            costumeStyle = HumanCostumeStyle.VILLAGE_DHOTI,
            hairStyle = HumanHairStyle.SHORT_FADE,
            buildType = HumanBuildType.STANDARD,
            accessory = CharacterAccessory.TURBAN
        ),
        CharacterRigDefinition(
            id = "char_savitri_village",
            name = "Savitri Didi",
            category = CharacterCategory.VILLAGE,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#CF8F5B",
            primaryColorHex = "#0D9488",
            secondaryColorHex = "#FBBF24",
            accentColorHex = "#F97316",
            hairColorHex = "#1C1917",
            eyeColorHex = "#241914",
            costumeStyle = HumanCostumeStyle.SAREE_DRAPE,
            hairStyle = HumanHairStyle.WAVY_LONG,
            buildType = HumanBuildType.STANDARD,
            accessory = CharacterAccessory.NONE
        ),
        // WESTERN
        CharacterRigDefinition(
            id = "char_alex_western",
            name = "Alex Studio",
            category = CharacterCategory.WESTERN,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#F1C4A2",
            primaryColorHex = "#2563EB",
            secondaryColorHex = "#1E293B",
            accentColorHex = "#38BDF8",
            hairColorHex = "#4A3122",
            eyeColorHex = "#1D4ED8",
            costumeStyle = HumanCostumeStyle.HOODIE_STREET,
            hairStyle = HumanHairStyle.SPIKY,
            buildType = HumanBuildType.ATHLETIC,
            accessory = CharacterAccessory.HEADPHONES,
            isLatest = true
        ),
        CharacterRigDefinition(
            id = "char_cole_western",
            name = "Sheriff Cole",
            category = CharacterCategory.WESTERN,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#EAB892",
            primaryColorHex = "#9A3412",
            secondaryColorHex = "#3F2E21",
            accentColorHex = "#FACC15",
            hairColorHex = "#3D2B1F",
            eyeColorHex = "#365314",
            costumeStyle = HumanCostumeStyle.CASUAL_JACKET,
            hairStyle = HumanHairStyle.SHORT_FADE,
            buildType = HumanBuildType.BROAD,
            accessory = CharacterAccessory.HAT
        ),
        // INDONESIAN
        CharacterRigDefinition(
            id = "char_budi_indo",
            name = "Budi Batik",
            category = CharacterCategory.INDONESIAN,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#D2986B",
            primaryColorHex = "#7C2D12",
            secondaryColorHex = "#1E293B",
            accentColorHex = "#F59E0B",
            hairColorHex = "#18181B",
            eyeColorHex = "#1F1714",
            costumeStyle = HumanCostumeStyle.FORMAL_SUIT,
            hairStyle = HumanHairStyle.SIDE_PART,
            buildType = HumanBuildType.STANDARD,
            accessory = CharacterAccessory.HAT
        ),
        CharacterRigDefinition(
            id = "char_sari_indo",
            name = "Sari Kebaya",
            category = CharacterCategory.INDONESIAN,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#D8A074",
            primaryColorHex = "#059669",
            secondaryColorHex = "#78350F",
            accentColorHex = "#FDE047",
            hairColorHex = "#171717",
            eyeColorHex = "#241914",
            costumeStyle = HumanCostumeStyle.SAREE_DRAPE,
            hairStyle = HumanHairStyle.TRADITIONAL_BUN,
            buildType = HumanBuildType.SLIM,
            accessory = CharacterAccessory.CROWN
        ),
        // BIRDS
        CharacterRigDefinition(
            id = "char_mittu_bird",
            name = "Mittu Parrot",
            category = CharacterCategory.BIRDS,
            bodyType = CharacterBodyType.BIRD,
            skinColorHex = "#FACC15",
            primaryColorHex = "#22C55E",
            secondaryColorHex = "#15803D",
            accentColorHex = "#EF4444",
            hairColorHex = "#16A34A",
            accessory = CharacterAccessory.NONE,
            isLatest = true
        ),
        CharacterRigDefinition(
            id = "char_neel_peacock",
            name = "Neel Peacock",
            category = CharacterCategory.BIRDS,
            bodyType = CharacterBodyType.BIRD,
            skinColorHex = "#F59E0B",
            primaryColorHex = "#0284C7",
            secondaryColorHex = "#0F766E",
            accentColorHex = "#38BDF8",
            hairColorHex = "#0369A1",
            accessory = CharacterAccessory.CROWN
        ),
        // ANIMALS
        CharacterRigDefinition(
            id = "char_sheru_animal",
            name = "Sheru Cub",
            category = CharacterCategory.ANIMALS,
            bodyType = CharacterBodyType.QUADRUPED,
            skinColorHex = "#FDE68A",
            primaryColorHex = "#F97316",
            secondaryColorHex = "#7C2D12",
            accentColorHex = "#1E1B18",
            hairColorHex = "#EA580C",
            accessory = CharacterAccessory.NONE
        ),
        CharacterRigDefinition(
            id = "char_moti_animal",
            name = "Moti Fox",
            category = CharacterCategory.ANIMALS,
            bodyType = CharacterBodyType.QUADRUPED,
            skinColorHex = "#FEF3C7",
            primaryColorHex = "#DC2626",
            secondaryColorHex = "#F8FAFC",
            accentColorHex = "#1F2937",
            hairColorHex = "#B91C1C",
            accessory = CharacterAccessory.SCARF
        ),
        // VEHICLES
        CharacterRigDefinition(
            id = "char_auto_vehicle",
            name = "City Auto",
            category = CharacterCategory.VEHICLES,
            bodyType = CharacterBodyType.VEHICLE,
            skinColorHex = "#BAE6FD",
            primaryColorHex = "#16A34A",
            secondaryColorHex = "#FACC15",
            accentColorHex = "#1E293B",
            hairColorHex = "#0F172A",
            accessory = CharacterAccessory.NONE,
            isLatest = true
        ),
        CharacterRigDefinition(
            id = "char_cruiser_vehicle",
            name = "Metro Cruiser",
            category = CharacterCategory.VEHICLES,
            bodyType = CharacterBodyType.VEHICLE,
            skinColorHex = "#E0F2FE",
            primaryColorHex = "#EF4444",
            secondaryColorHex = "#F8FAFC",
            accentColorHex = "#38BDF8",
            hairColorHex = "#0F172A",
            accessory = CharacterAccessory.NONE
        ),
        // RELIGIOUS
        CharacterRigDefinition(
            id = "char_rishi_religious",
            name = "Temple Sage",
            category = CharacterCategory.RELIGIOUS,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#DDA172",
            primaryColorHex = "#F97316",
            secondaryColorHex = "#FED7AA",
            accentColorHex = "#FACC15",
            hairColorHex = "#E5E7EB",
            accessory = CharacterAccessory.HALO
        ),
        CharacterRigDefinition(
            id = "char_deva_religious",
            name = "Lotus Guardian",
            category = CharacterCategory.RELIGIOUS,
            bodyType = CharacterBodyType.HUMANOID,
            skinColorHex = "#93C5FD",
            primaryColorHex = "#F59E0B",
            secondaryColorHex = "#7E22CE",
            accentColorHex = "#FDE047",
            hairColorHex = "#1E1B4B",
            accessory = CharacterAccessory.CROWN
        ),
        // HORROR
        CharacterRigDefinition(
            id = "char_phantom_horror",
            name = "Shadow Wisp",
            category = CharacterCategory.HORROR,
            bodyType = CharacterBodyType.SPIRIT,
            skinColorHex = "#CBD5E1",
            primaryColorHex = "#4C1D95",
            secondaryColorHex = "#1E1B4B",
            accentColorHex = "#34D399",
            hairColorHex = "#0F172A",
            accessory = CharacterAccessory.CAPE,
            isLatest = true
        ),
        CharacterRigDefinition(
            id = "char_specter_horror",
            name = "Crimson Imp",
            category = CharacterCategory.HORROR,
            bodyType = CharacterBodyType.SPIRIT,
            skinColorHex = "#991B1B",
            primaryColorHex = "#1F2937",
            secondaryColorHex = "#450A0A",
            accentColorHex = "#F43F5E",
            hairColorHex = "#111827",
            accessory = CharacterAccessory.HORNS
        )
    )

    val animationPresets: List<AnimationPreset> = listOf(
        AnimationPreset(
            id = "preset_idle",
            type = AnimationPresetType.IDLE,
            name = "Idle",
            tag = "Base",
            defaultDurationFrames = 48,
            description = "Subtle breathing motion and natural blinking stance."
        ),
        AnimationPreset(
            id = "preset_idle_happy",
            type = AnimationPresetType.IDLE_HAPPY,
            name = "Idle Happy",
            tag = "Emotion",
            defaultDurationFrames = 48,
            description = "Upbeat cheerful bounce with open smile and relaxed sway."
        ),
        AnimationPreset(
            id = "preset_walk",
            type = AnimationPresetType.WALK,
            name = "Walk",
            tag = "Locomotion",
            defaultDurationFrames = 36,
            description = "Smooth alternating stride cycle with counter-swinging arms."
        ),
        AnimationPreset(
            id = "preset_jogging",
            type = AnimationPresetType.JOGGING,
            name = "Jogging",
            tag = "Locomotion",
            defaultDurationFrames = 28,
            description = "Energetic mid-tempo jog with bent elbows and higher lift."
        ),
        AnimationPreset(
            id = "preset_running",
            type = AnimationPresetType.RUNNING,
            name = "Running",
            tag = "Locomotion",
            defaultDurationFrames = 22,
            description = "Fast forward-leaning sprint with wide limb swing."
        ),
        AnimationPreset(
            id = "preset_sitting",
            type = AnimationPresetType.SITTING,
            name = "Sitting",
            tag = "Pose",
            defaultDurationFrames = 48,
            description = "Seated posture with folded knees and gentle idle sway."
        ),
        AnimationPreset(
            id = "preset_standing",
            type = AnimationPresetType.STANDING,
            name = "Standing",
            tag = "Pose",
            defaultDurationFrames = 36,
            description = "Steady upright presentation pose with calm balance."
        ),
        AnimationPreset(
            id = "preset_talking",
            type = AnimationPresetType.TALKING,
            name = "Talking",
            tag = "Dialogue",
            defaultDurationFrames = 40,
            description = "Animated lip-sync jaw motion with expressive hand gestures."
        ),
        AnimationPreset(
            id = "preset_waving",
            type = AnimationPresetType.WAVING,
            name = "Waving",
            tag = "Gesture",
            defaultDurationFrames = 36,
            description = "Friendly raised-arm wave with slight head tilt."
        ),
        AnimationPreset(
            id = "preset_jumping",
            type = AnimationPresetType.JUMPING,
            name = "Jumping",
            tag = "Action",
            defaultDurationFrames = 30,
            description = "Anticipation crouch, airborne apex stretch, and soft landing."
        ),
        AnimationPreset(
            id = "preset_dancing",
            type = AnimationPresetType.DANCING,
            name = "Dancing",
            tag = "Action",
            defaultDurationFrames = 44,
            description = "Rhythmic groove with alternating arm lifts and hip bounce."
        )
    )

    data class SceneTemplate(
        val id: String,
        val title: String,
        val subtitle: String,
        val aspectRatio: AspectRatioPreset,
        val buildProject: (String) -> ProjectDocument
    )

    val sceneTemplates: List<SceneTemplate> = listOf(
        SceneTemplate(
            id = "tpl_village_walk",
            title = "Village Sunset Walk",
            subtitle = "16:9 • Aarav walking along golden-hour village lane",
            aspectRatio = AspectRatioPreset.RATIO_16_9,
            buildProject = { projectName ->
                val bgLayerId = UUID.randomUUID().toString()
                val charLayerId = UUID.randomUUID().toString()
                val titleLayerId = UUID.randomUUID().toString()
                ProjectDocument(
                    name = projectName,
                    width = 1280,
                    height = 720,
                    aspectRatioPreset = AspectRatioPreset.RATIO_16_9,
                    fps = 24,
                    scenes = listOf(
                        AnimationScene(
                            name = "Scene 1",
                            backgroundColorHex = "#1E1610",
                            backgroundBundledKey = BG_VILLAGE_SUNSET,
                            durationFrames = 144,
                            layers = listOf(
                                AnimationLayer(id = bgLayerId, name = "Background", type = LayerType.BACKGROUND, orderIndex = 0),
                                AnimationLayer(id = charLayerId, name = "Aarav Kurta", type = LayerType.CHARACTER, orderIndex = 1),
                                AnimationLayer(id = titleLayerId, name = "Title Card", type = LayerType.TEXT, orderIndex = 2)
                            ),
                            clips = listOf(
                                TimelineClip(
                                    layerId = bgLayerId,
                                    name = "Village Sunset",
                                    startFrame = 0,
                                    durationFrames = 144,
                                    bundledBgKey = BG_VILLAGE_SUNSET,
                                    keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f))
                                ),
                                TimelineClip(
                                    layerId = charLayerId,
                                    name = "Aarav • Walk",
                                    startFrame = 0,
                                    durationFrames = 144,
                                    characterId = "char_aarav_desi",
                                    orientation = CharacterOrientation.THREE_QUARTER_FRONT,
                                    animationPresetType = AnimationPresetType.WALK,
                                    speedMultiplier = 1.0f,
                                    loopAnimation = true,
                                    keyframes = listOf(
                                        TransformKeyframe(frame = 0, x = 0.22f, y = 0.68f, scaleX = 0.95f, scaleY = 0.95f, easing = EasingType.LINEAR),
                                        TransformKeyframe(frame = 120, x = 0.78f, y = 0.72f, scaleX = 1.08f, scaleY = 1.08f, easing = EasingType.EASE_IN_OUT)
                                    )
                                ),
                                TimelineClip(
                                    layerId = titleLayerId,
                                    name = "Evening Story",
                                    startFrame = 8,
                                    durationFrames = 72,
                                    textContent = "Golden Hour Walk",
                                    textColorHex = "#FEF3C7",
                                    textSizeSp = 34f,
                                    transitionIn = TransitionType.FADE,
                                    transitionOut = TransitionType.FADE,
                                    keyframes = listOf(
                                        TransformKeyframe(frame = 0, x = 0.5f, y = 0.16f, opacity = 1f)
                                    )
                                )
                            )
                        )
                    )
                )
            }
        ),
        SceneTemplate(
            id = "tpl_city_chat",
            title = "Twilight Café Dialogue",
            subtitle = "16:9 • Two characters talking & waving on city street",
            aspectRatio = AspectRatioPreset.RATIO_16_9,
            buildProject = { projectName ->
                val bgLayerId = UUID.randomUUID().toString()
                val char1LayerId = UUID.randomUUID().toString()
                val char2LayerId = UUID.randomUUID().toString()
                ProjectDocument(
                    name = projectName,
                    width = 1280,
                    height = 720,
                    aspectRatioPreset = AspectRatioPreset.RATIO_16_9,
                    fps = 24,
                    scenes = listOf(
                        AnimationScene(
                            name = "Scene 1",
                            backgroundColorHex = "#0E172A",
                            backgroundBundledKey = BG_CITY_TWILIGHT,
                            durationFrames = 120,
                            layers = listOf(
                                AnimationLayer(id = bgLayerId, name = "Background", type = LayerType.BACKGROUND, orderIndex = 0),
                                AnimationLayer(id = char1LayerId, name = "Alex Studio", type = LayerType.CHARACTER, orderIndex = 1),
                                AnimationLayer(id = char2LayerId, name = "Meera Festive", type = LayerType.CHARACTER, orderIndex = 2)
                            ),
                            clips = listOf(
                                TimelineClip(
                                    layerId = bgLayerId,
                                    name = "City Twilight",
                                    startFrame = 0,
                                    durationFrames = 120,
                                    bundledBgKey = BG_CITY_TWILIGHT,
                                    keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f))
                                ),
                                TimelineClip(
                                    layerId = char1LayerId,
                                    name = "Alex • Talking",
                                    startFrame = 0,
                                    durationFrames = 120,
                                    characterId = "char_alex_western",
                                    orientation = CharacterOrientation.THREE_QUARTER_FRONT,
                                    animationPresetType = AnimationPresetType.TALKING,
                                    keyframes = listOf(
                                        TransformKeyframe(frame = 0, x = 0.34f, y = 0.70f, scaleX = 1.0f, scaleY = 1.0f)
                                    )
                                ),
                                TimelineClip(
                                    layerId = char2LayerId,
                                    name = "Meera • Waving",
                                    startFrame = 0,
                                    durationFrames = 120,
                                    characterId = "char_meera_desi",
                                    orientation = CharacterOrientation.THREE_QUARTER_FRONT,
                                    animationPresetType = AnimationPresetType.WAVING,
                                    flipHorizontal = true,
                                    keyframes = listOf(
                                        TransformKeyframe(frame = 0, x = 0.66f, y = 0.70f, scaleX = 1.0f, scaleY = 1.0f)
                                    )
                                )
                            )
                        )
                    )
                )
            }
        ),
        SceneTemplate(
            id = "tpl_forest_dance",
            title = "Forest Dance Reel",
            subtitle = "9:16 Vertical • Peacock & Cub dancing in the meadow",
            aspectRatio = AspectRatioPreset.RATIO_9_16,
            buildProject = { projectName ->
                val bgLayerId = UUID.randomUUID().toString()
                val charLayerId = UUID.randomUUID().toString()
                ProjectDocument(
                    name = projectName,
                    width = 720,
                    height = 1280,
                    aspectRatioPreset = AspectRatioPreset.RATIO_9_16,
                    fps = 24,
                    scenes = listOf(
                        AnimationScene(
                            name = "Scene 1",
                            backgroundColorHex = "#10281B",
                            backgroundBundledKey = BG_FOREST_MEADOW,
                            durationFrames = 120,
                            layers = listOf(
                                AnimationLayer(id = bgLayerId, name = "Background", type = LayerType.BACKGROUND, orderIndex = 0),
                                AnimationLayer(id = charLayerId, name = "Mittu Parrot", type = LayerType.CHARACTER, orderIndex = 1)
                            ),
                            clips = listOf(
                                TimelineClip(
                                    layerId = bgLayerId,
                                    name = "Forest Meadow",
                                    startFrame = 0,
                                    durationFrames = 120,
                                    bundledBgKey = BG_FOREST_MEADOW,
                                    keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f))
                                ),
                                TimelineClip(
                                    layerId = charLayerId,
                                    name = "Mittu • Dancing",
                                    startFrame = 0,
                                    durationFrames = 120,
                                    characterId = "char_mittu_bird",
                                    orientation = CharacterOrientation.FRONT,
                                    animationPresetType = AnimationPresetType.DANCING,
                                    keyframes = listOf(
                                        TransformKeyframe(frame = 0, x = 0.5f, y = 0.64f, scaleX = 1.15f, scaleY = 1.15f),
                                        TransformKeyframe(frame = 60, x = 0.5f, y = 0.56f, scaleX = 1.25f, scaleY = 1.25f, easing = EasingType.EASE_IN_OUT),
                                        TransformKeyframe(frame = 119, x = 0.5f, y = 0.64f, scaleX = 1.15f, scaleY = 1.15f, easing = EasingType.EASE_IN_OUT)
                                    )
                                )
                            )
                        )
                    )
                )
            }
        ),
        SceneTemplate(
            id = "tpl_blank_stage",
            title = "Empty Studio Stage",
            subtitle = "16:9 • Clean blank canvas ready for custom layers",
            aspectRatio = AspectRatioPreset.RATIO_16_9,
            buildProject = { projectName ->
                val bgLayerId = UUID.randomUUID().toString()
                ProjectDocument(
                    name = projectName,
                    width = 1280,
                    height = 720,
                    aspectRatioPreset = AspectRatioPreset.RATIO_16_9,
                    fps = 24,
                    scenes = listOf(
                        AnimationScene(
                            name = "Scene 1",
                            backgroundColorHex = "#151B28",
                            durationFrames = 120,
                            layers = listOf(
                                AnimationLayer(id = bgLayerId, name = "Background", type = LayerType.BACKGROUND, orderIndex = 0)
                            ),
                            clips = emptyList()
                        )
                    )
                )
            }
        )
    )
}
