package com.example.engine

import android.media.MediaMetadataRetriever
import com.example.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * Exports a compact, 100% offline, LLM-ready `.fcblueprint.json` (schema v3) describing:
 * 1. Project metadata & normalized stage coordinate system (0.0..1.0, ground y = 0.82).
 * 2. Live project assets (characters, backgrounds, props/images, and audio clips with real durations).
 * 3. Dynamically introspected studio capabilities from [StudioCapabilityRegistry].
 * 4. Universal extension schemas (`inlineSvgAssets`, `customAnimations`, `customTransitions`) so
 *    external AI tools or human script writers can invent brand-new assets, motions, and transitions.
 */
object AIBlueprintExporter {

    const val SCHEMA_VERSION = 3
    const val GROUND_Y_NORMALIZED = 0.82f

    data class CoordinateSystemSpec(
        val origin: String = "top-left",
        val xRange: List<Float> = listOf(0.0f, 1.0f),
        val yRange: List<Float> = listOf(0.0f, 1.0f),
        val groundY: Float = GROUND_Y_NORMALIZED,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f
    )

    data class ProjectMetaPayload(
        val projectId: String,
        val projectName: String,
        val fps: Int,
        val aspectRatio: String,
        val width: Int,
        val height: Int,
        val sceneCount: Int,
        val coordinateSystem: CoordinateSystemSpec = CoordinateSystemSpec()
    )

    data class BlueprintCharacterAsset(
        val id: String,
        val name: String,
        val category: String,
        val bodyType: String,
        val isCustom: Boolean,
        val rigType: String,
        val supportedOrientations: List<String>
    )

    data class BlueprintVisualAsset(
        val id: String,
        val name: String,
        val category: String,
        val mediaType: String,
        val isBundled: Boolean
    )

    data class BlueprintAudioAsset(
        val id: String,
        val name: String,
        val durationSec: Double,
        val durationMs: Long,
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = 0L,
        val hasWaveformLipSync: Boolean = false
    )

    data class ProjectAssetsPayload(
        val characters: List<BlueprintCharacterAsset>,
        val backgrounds: List<BlueprintVisualAsset>,
        val propsAndImages: List<BlueprintVisualAsset>,
        val audioClips: List<BlueprintAudioAsset>,
        val existingInlineSvgIds: List<String>,
        val existingCustomAnimationIds: List<String>,
        val existingCustomTransitionIds: List<String>
    )

    data class BlueprintCapabilitiesPayload(
        val animationPresets: List<String>,
        val facialExpressions: List<String>,
        val facialExpressionPresets: List<String>,
        val mouthShapeOverrides: List<String>,
        val eyeShapeOverrides: List<String>,
        val eyebrowShapeOverrides: List<String>,
        val handPoses: List<String>,
        val orientations: List<String>,
        val ikJointIds: List<String>,
        val cameraShotTypes: List<String>,
        val cameraPresets: List<String>,
        val vfxStyles: List<String>,
        val colourGradingPresets: List<String>,
        val maskTypes: List<String>,
        val curveAndEasingTypes: List<String>,
        val transitions: List<String>,
        val customTransitionClipTypes: List<String>,
        val customAnimationLoopModes: List<String>,
        val inlineSvgAssetTypes: List<String>,
        val layerBlendModes: List<String>,
        val aspectRatios: List<String>,
        val builtInProps: List<String>,
        val textBubbleStyles: List<String>,
        val clipOperations: List<String>
    )

    data class UniversalExtensionSchemasPayload(
        val scriptFormatVersion: Int = SCHEMA_VERSION,
        val notes: List<String>,
        val inlineSvgAssetExample: Map<String, Any>,
        val customAnimationExample: Map<String, Any>,
        val customTransitionExample: Map<String, Any>,
        val directorScriptSkeleton: Map<String, Any>
    )

    data class BlueprintDocumentV3(
        val format: String = "framecraft.blueprint",
        val schemaVersion: Int = SCHEMA_VERSION,
        val projectMeta: ProjectMetaPayload,
        val projectAssets: ProjectAssetsPayload,
        val capabilities: BlueprintCapabilitiesPayload,
        val universalExtensionSchemas: UniversalExtensionSchemasPayload
    )

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Builds the complete `.fcblueprint.json` v3 document by dynamically introspecting
     * [StudioCapabilityRegistry] and reading live uploaded/bundled project assets.
     */
    fun buildBlueprint(
        project: ProjectDocument,
        characters: List<CharacterRigDefinition>,
        mediaAssets: List<LocalMediaAsset>,
        audioDurationResolver: (String?) -> Long? = { path -> probeAudioDurationMs(path) }
    ): BlueprintDocumentV3 {
        val caps = StudioCapabilityRegistry.introspect()

        val meta = ProjectMetaPayload(
            projectId = project.id,
            projectName = project.name,
            fps = project.fps.coerceIn(12, 60),
            aspectRatio = project.aspectRatioPreset.label,
            width = project.width,
            height = project.height,
            sceneCount = project.scenes.size
        )

        val charAssets = characters.map { rig ->
            val rigKind = when {
                rig.spriteAtlasRig != null || rig.skeletonRig?.rigMode == CustomRigMode.ATLAS_RIG_PRO -> "ATLAS_RIG_PRO"
                rig.skeletonRig?.rigMode == CustomRigMode.MODULAR_CHART_2048 -> "MODULAR_CHART_2048"
                rig.isCustom -> "CUSTOM_SINGLE_PNG_OR_SVG"
                else -> "PROCEDURAL_VECTOR"
            }
            BlueprintCharacterAsset(
                id = rig.id,
                name = rig.name,
                category = rig.category.name,
                bodyType = rig.bodyType.name,
                isCustom = rig.isCustom,
                rigType = rigKind,
                supportedOrientations = rig.supportedOrientations.map { it.name }
            )
        }

        val bgAssets = mediaAssets
            .filter { it.type == MediaAssetType.BACKGROUND }
            .map { asset ->
                BlueprintVisualAsset(
                    id = asset.id,
                    name = asset.name,
                    category = asset.category,
                    mediaType = asset.type.name,
                    isBundled = asset.isBundled
                )
            }

        val propAssets = mediaAssets
            .filter { it.type == MediaAssetType.IMAGE || it.type == MediaAssetType.SVG || it.type == MediaAssetType.GIF }
            .map { asset ->
                BlueprintVisualAsset(
                    id = asset.id,
                    name = asset.name,
                    category = asset.category,
                    mediaType = asset.type.name,
                    isBundled = asset.isBundled
                )
            }

        // Collect audio from both LocalMediaAsset library AND timeline audio clips in the project
        val audioById = LinkedHashMap<String, BlueprintAudioAsset>()
        val syncByClipId = project.audioSyncTracks.associateBy { it.clipId }

        for (scene in project.scenes) {
            for (clip in scene.clips) {
                val path = clip.audioPath
                if (path.isNullOrBlank()) continue
                val syncMeta = syncByClipId[clip.id]
                val probedMs = syncMeta?.durationMs?.takeIf { it > 0L }
                    ?: runCatching { AudioWaveformExtractor.getCached(path)?.durationMs }.getOrNull()?.takeIf { it > 0L }
                    ?: audioDurationResolver(path)?.takeIf { it > 0L }
                    ?: ((clip.durationFrames.toDouble() / project.fps.coerceAtLeast(1).toDouble()) * 1000.0).toLong()
                val effectiveEndMs = if (clip.audioTrimEndMs > clip.audioTrimStartMs) clip.audioTrimEndMs else probedMs
                val trimmedMs = (effectiveEndMs - clip.audioTrimStartMs).coerceAtLeast(0L)
                val durationSec = ((trimmedMs / 1000.0) * 1000.0).roundToInt() / 1000.0
                audioById[clip.id] = BlueprintAudioAsset(
                    id = clip.id,
                    name = clip.audioLabel ?: clip.name,
                    durationSec = durationSec,
                    durationMs = trimmedMs,
                    trimStartMs = clip.audioTrimStartMs,
                    trimEndMs = effectiveEndMs,
                    hasWaveformLipSync = (syncMeta?.phonemeMappings?.isNotEmpty() == true)
                )
            }
        }

        for (asset in mediaAssets.filter { it.type == MediaAssetType.AUDIO }) {
            if (audioById.containsKey(asset.id)) continue
            val path = asset.localFilePath
            val probedMs = runCatching { AudioWaveformExtractor.getCached(path)?.durationMs }.getOrNull()?.takeIf { it > 0L }
                ?: audioDurationResolver(path)?.takeIf { it > 0L }
                ?: 0L
            val durationSec = ((probedMs / 1000.0) * 1000.0).roundToInt() / 1000.0
            audioById[asset.id] = BlueprintAudioAsset(
                id = asset.id,
                name = asset.name,
                durationSec = durationSec,
                durationMs = probedMs,
                hasWaveformLipSync = false
            )
        }

        val assetsPayload = ProjectAssetsPayload(
            characters = charAssets,
            backgrounds = bgAssets,
            propsAndImages = propAssets,
            audioClips = audioById.values.toList(),
            existingInlineSvgIds = project.inlineSvgAssets.keys.toList(),
            existingCustomAnimationIds = project.customAnimations.keys.toList(),
            existingCustomTransitionIds = project.customTransitions.keys.toList()
        )

        val capabilitiesPayload = BlueprintCapabilitiesPayload(
            animationPresets = caps.animationPresets.map { it.id },
            facialExpressions = caps.facialExpressions.map { it.id },
            facialExpressionPresets = caps.facialExpressionPresets.map { it.id },
            mouthShapeOverrides = caps.mouthShapeOverrides.map { it.id },
            eyeShapeOverrides = caps.eyeShapeOverrides.map { it.id },
            eyebrowShapeOverrides = caps.eyebrowShapeOverrides.map { it.id },
            handPoses = caps.handPoses.map { it.id },
            orientations = caps.orientations.map { it.id },
            ikJointIds = caps.ikJointIds.map { it.id },
            cameraShotTypes = caps.cameraShotTypes.map { it.id },
            cameraPresets = caps.cameraPresets.map { it.id },
            vfxStyles = caps.vfxStyles.map { it.id },
            colourGradingPresets = caps.colourGradingPresets.map { it.id },
            maskTypes = caps.maskTypes.map { it.id },
            curveAndEasingTypes = caps.curveAndEasingTypes.map { it.id },
            transitions = caps.transitions.map { it.id },
            customTransitionClipTypes = caps.customTransitionClipTypes.map { it.id },
            customAnimationLoopModes = caps.customAnimationLoopModes.map { it.id },
            inlineSvgAssetTypes = caps.inlineSvgAssetTypes.map { it.id },
            layerBlendModes = caps.layerBlendModes.map { it.id },
            aspectRatios = caps.aspectRatios.map { it.id },
            builtInProps = caps.builtInProps.map { it.id },
            textBubbleStyles = caps.textBubbleStyles.map { it.id },
            clipOperations = caps.clipOperations.map { it.id }
        )

        val extensionSchemas = UniversalExtensionSchemasPayload(
            notes = listOf(
                "All coordinates are normalized 0.0..1.0 (x: left->right, y: top->bottom, ground y=0.82).",
                "Invent brand-new trees, animals, props, backgrounds, or 2048x2048 characters via inlineSvgAssets[] using clean AndroidSVG markup (<path>, <circle>, <rect>, <ellipse>, <polygon>, <g id='...' data-pivot='x,y'>). Never use <script>, <foreignObject>, or <filter>.",
                "Invent any custom motion via customAnimations{} targeting character bone IDs (HEAD, TORSO, LEFT_ARM, LEFT_FOREARM, RIGHT_ARM, RIGHT_FOREARM, LEFT_LEG, LEFT_KNEE, RIGHT_LEG, RIGHT_KNEE) OR any SVG sub-group id.",
                "Invent any custom scene transition via customTransitions{} with clipPathType, outgoing/incoming transforms, flashOverlay, and cubicBezier.",
                "Use cameraShots[] with focusTargetCharacterId + shotType for automatic cinematic speaker tracking and reaction cuts."
            ),
            inlineSvgAssetExample = mapOf(
                "id" to "svg_windy_tree",
                "type" to "TREE",
                "viewBox" to "0 0 256 256",
                "svgXml" to "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'><g id='trunk' data-pivot='128,230'><rect x='112' y='130' width='32' height='110' rx='8' fill='#7C4A27'/></g><g id='tree_canopy' data-pivot='128,136'><circle cx='128' cy='92' r='64' fill='#2E8B57'/><circle cx='88' cy='112' r='44' fill='#3CB371'/><circle cx='168' cy='112' r='44' fill='#228B22'/></g></svg>"
            ),
            customAnimationExample = mapOf(
                "tree_wind_sway" to mapOf(
                    "durationSec" to 2.4,
                    "loopMode" to "PING_PONG",
                    "tracks" to listOf(
                        mapOf(
                            "targetNodeId" to "tree_canopy",
                            "keyframes" to listOf(
                                mapOf("t" to 0.0, "rotation" to -4.5, "scaleX" to 1.0, "scaleY" to 1.0, "cubicBezier" to listOf(0.42, 0.0, 0.58, 1.0)),
                                mapOf("t" to 1.0, "rotation" to 5.5, "scaleX" to 1.03, "scaleY" to 0.98, "cubicBezier" to listOf(0.42, 0.0, 0.58, 1.0))
                            )
                        )
                    )
                )
            ),
            customTransitionExample = mapOf(
                "golden_whip_iris" to mapOf(
                    "durationSec" to 0.65,
                    "clipPathType" to "IRIS_CIRCLE",
                    "angleDeg" to 25.0,
                    "outgoing" to mapOf("dx" to -0.15, "dy" to 0.0, "scale" to 1.08, "rotation" to -2.0, "alpha" to 0.25),
                    "incoming" to mapOf("dx" to 0.12, "dy" to 0.0, "scale" to 1.0, "rotation" to 0.0, "alpha" to 1.0),
                    "flashOverlay" to mapOf("colorHex" to "#FDE68A", "peakAlpha" to 0.45),
                    "cubicBezier" to listOf(0.22, 1.0, 0.36, 1.0)
                )
            ),
            directorScriptSkeleton = mapOf(
                "format" to "framecraft.director_script",
                "schemaVersion" to SCHEMA_VERSION,
                "ratio" to project.aspectRatioPreset.label,
                "fps" to project.fps,
                "inlineSvgAssets" to listOf("<InlineSvgAssetDef>"),
                "customAnimations" to mapOf("<animId>" to "<CustomAnimationDef>"),
                "customTransitions" to mapOf("<transId>" to "<CustomTransitionDef>"),
                "scenes" to listOf(
                    mapOf(
                        "name" to "Scene 1",
                        "durationSec" to 6.0,
                        "backgroundColorHex" to "#162033",
                        "backgroundAssetId" to null,
                        "colourGrading" to "GOLDEN_HOUR",
                        "vfx" to mapOf("particleType" to "FIREFLIES", "intensity" to 0.65, "speed" to 1.0, "vignette" to 0.25, "cameraShake" to 0.0),
                        "customTransitionInId" to null,
                        "customTransitionOutId" to "golden_whip_iris",
                        "layers" to listOf(
                            mapOf("id" to "layer_bg", "name" to "Forest", "type" to "IMAGE", "zIndex" to 0, "blendMode" to "NORMAL", "visible" to true, "locked" to false)
                        ),
                        "clips" to listOf(
                            mapOf(
                                "id" to "clip_hero",
                                "layerId" to "layer_chars",
                                "name" to "Hero",
                                "characterId" to (charAssets.firstOrNull()?.id ?: "char_id"),
                                "startSec" to 0.0,
                                "durationSec" to 6.0,
                                "animationPreset" to "TALKING",
                                "facialExpression" to "HAPPY",
                                "lipSyncAudioId" to null,
                                "mask" to mapOf("type" to "NONE", "radius" to 0.46),
                                "motionPath" to listOf(mapOf("x" to 0.32, "y" to 0.82), mapOf("x" to 0.48, "y" to 0.82)),
                                "keyframes" to listOf(
                                    mapOf("tSec" to 0.0, "x" to 0.32, "y" to 0.82, "scaleX" to 1.0, "scaleY" to 1.0, "easing" to "EASE_IN_OUT")
                                )
                            )
                        ),
                        "cameraShots" to listOf(
                            mapOf("tSec" to 0.0, "shotType" to "WIDE_ESTABLISHING"),
                            mapOf("tSec" to 1.5, "shotType" to "CLOSE_UP_SPEAKER", "focusTargetCharacterId" to (charAssets.firstOrNull()?.id ?: "char_id"))
                        )
                    )
                )
            )
        )

        return BlueprintDocumentV3(
            projectMeta = meta,
            projectAssets = assetsPayload,
            capabilities = capabilitiesPayload,
            universalExtensionSchemas = extensionSchemas
        )
    }

    /**
     * Serializes the `.fcblueprint.json` v3 document to compact or pretty-printed JSON.
     */
    fun exportToJson(
        project: ProjectDocument,
        characters: List<CharacterRigDefinition>,
        mediaAssets: List<LocalMediaAsset>,
        prettyPrint: Boolean = true,
        audioDurationResolver: (String?) -> Long? = { path -> probeAudioDurationMs(path) }
    ): String {
        val doc = buildBlueprint(project, characters, mediaAssets, audioDurationResolver)
        val adapter = moshi.adapter(BlueprintDocumentV3::class.java)
        return if (prettyPrint) adapter.indent("  ").toJson(doc) else adapter.toJson(doc)
    }

    /**
     * Probes real audio duration in milliseconds offline via [MediaMetadataRetriever]
     * with a fallback to [AudioWaveformExtractor.parseWavWaveform] for PCM WAV files.
     */
    fun probeAudioDurationMs(audioPath: String?): Long? {
        if (audioPath.isNullOrBlank()) return null
        val file = File(audioPath)
        if (!file.exists() || !file.isFile) return null
        runCatching { AudioWaveformExtractor.getCached(audioPath)?.durationMs }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        AudioWaveformExtractor.parseWavWaveform(file, 48)?.durationMs?.takeIf { it > 0L }?.let { return it }
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever()
            retriever?.setDataSource(file.absolutePath)
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull().also {
            runCatching { retriever?.release() }
        }
    }
}
