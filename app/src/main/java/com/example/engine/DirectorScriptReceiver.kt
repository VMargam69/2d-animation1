package com.example.engine

import android.content.Context
import com.example.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

enum class DirectorScriptImportMode(val label: String) {
    REPLACE_TIMELINE("Replace Timeline"),
    APPEND_SCENES("Append Scenes")
}

data class DirectorScriptDryRunReport(
    val isValid: Boolean,
    val formatVersion: Int = 3,
    val sceneCount: Int = 0,
    val inlineSvgCount: Int = 0,
    val customAnimationCount: Int = 0,
    val customTransitionCount: Int = 0,
    val layerCount: Int = 0,
    val clipCount: Int = 0,
    val keyframeCount: Int = 0,
    val cameraShotCount: Int = 0,
    val lipSyncTrackCount: Int = 0,
    val clipOperationCount: Int = 0,
    val requestedAspectRatio: String? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

data class DirectorScriptCompileResult(
    val project: ProjectDocument,
    val spawnedCharacters: List<CharacterRigDefinition>,
    val spawnedMediaAssets: List<LocalMediaAsset>,
    val report: DirectorScriptDryRunReport
)

/**
 * 100% Offline Director Script Receiver & Compiler (`.fcscript.json`).
 *
 * Parses, validates (dry-run), and compiles structured director scripts into fully editable
 * FrameCraft [ProjectDocument] timelines with:
 * - Inline SVG Asset Spawning (`CHARACTER`, `TREE`, `ANIMAL`, `PROP`, `BG`)
 * - Universal Custom Animations & Custom Transitions
 * - Dialogue-Driven Cinematic Auto-Focus Camera (9 [CameraShotType]s with speaker tracking & reaction cuts)
 * - Full Studio Tool execution: masking, `split`, `flipX`/`flipY`, `duplicate`, `delete`, `speed`,
 *   `trim`, curved Bezier `motionPath`, custom `cubicBezier`, `IK Pose`, auto audio-driven lip-sync
 *   (`lipSyncAudioId` via [AudioWaveformExtractor]), VFX, colour grading, aspect ratio, and
 *   layer properties (`zIndex`, `visible`, `locked`, `blendMode`, `parentLayerId`).
 */
object DirectorScriptReceiver {

    // ────────────────────────── Script JSON Schema DTOs ──────────────────────────

    data class ScriptDocumentDto(
        val format: String? = null,
        val schemaVersion: Int? = 3,
        val projectName: String? = null,
        val ratio: String? = null,
        val aspectRatio: String? = null,
        val fps: Int? = null,
        val inlineSvgAssets: List<ScriptInlineSvgDto>? = null,
        val customAnimations: Map<String, ScriptCustomAnimationDto>? = null,
        val customTransitions: Map<String, ScriptCustomTransitionDto>? = null,
        val scenes: List<ScriptSceneDto>? = null
    )

    data class ScriptInlineSvgDto(
        val id: String? = null,
        val name: String? = null,
        val type: String? = "PROP",
        val viewBox: String? = "0 0 256 256",
        val svgXml: String? = null
    )

    data class ScriptCustomAnimKeyframeDto(
        val t: Float = 0f,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val rotation: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val opacity: Float = 1f,
        val cubicBezier: List<Float>? = null
    )

    data class ScriptCustomAnimTrackDto(
        val targetNodeId: String? = null,
        val keyframes: List<ScriptCustomAnimKeyframeDto>? = null
    )

    data class ScriptCustomAnimationDto(
        val durationSec: Float? = 1.0f,
        val loopMode: String? = "LOOP",
        val tracks: List<ScriptCustomAnimTrackDto>? = null
    )

    data class ScriptCustomTransitionTransformDto(
        val dx: Float = 0f,
        val dy: Float = 0f,
        val scale: Float = 1f,
        val rotation: Float = 0f,
        val alpha: Float = 1f
    )

    data class ScriptCustomTransitionFlashDto(
        val colorHex: String? = "#FFFFFF",
        val peakAlpha: Float = 0f
    )

    data class ScriptCustomTransitionDto(
        val durationSec: Float? = 0.6f,
        val clipPathType: String? = "WIPE_ANGLE",
        val angleDeg: Float = 0f,
        val customSvgPath: String? = null,
        val outgoing: ScriptCustomTransitionTransformDto? = null,
        val incoming: ScriptCustomTransitionTransformDto? = null,
        val flashOverlay: ScriptCustomTransitionFlashDto? = null,
        val cubicBezier: List<Float>? = null
    )

    data class ScriptVfxDto(
        val particleType: String? = null,
        val style: String? = null,
        val intensity: Float? = 0.65f,
        val speed: Float? = 1.0f,
        val lightingMood: String? = null,
        val vignette: Float? = 0f,
        val cameraShake: Float? = 0f
    )

    data class ScriptLayerDto(
        val id: String? = null,
        val name: String? = null,
        val type: String? = null,
        val zIndex: Int? = null,
        val visible: Boolean? = true,
        val locked: Boolean? = false,
        val blendMode: String? = "NORMAL",
        val parentLayerId: String? = null,
        val colorHex: String? = null
    )

    data class ScriptMaskDto(
        val type: String? = "NONE",
        val radius: Float? = 0.46f
    )

    data class ScriptPointDto(
        val x: Float = 0.5f,
        val y: Float = 0.5f
    )

    data class ScriptIkPoseDto(
        val headNod: Float = 0f,
        val torsoTilt: Float = 0f,
        val leftArm: Float = 0f,
        val leftForearm: Float = 0f,
        val rightArm: Float = 0f,
        val rightForearm: Float = 0f,
        val leftLeg: Float = 0f,
        val leftKnee: Float = 0f,
        val rightLeg: Float = 0f,
        val rightKnee: Float = 0f
    )

    data class ScriptKeyframeDto(
        val frame: Int? = null,
        val tSec: Float? = null,
        val x: Float? = null,
        val y: Float? = null,
        val rotation: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val opacity: Float? = null,
        val easing: String? = null,
        val cubicBezier: List<Float>? = null,
        val expression: String? = null,
        val facialPresetId: String? = null,
        val mouthShape: String? = null,
        val eyeShape: String? = null,
        val eyebrowShape: String? = null,
        val handPose: String? = null,
        val blink: Boolean? = null,
        val ikPose: ScriptIkPoseDto? = null
    )

    data class ScriptClipOperationDto(
        val op: String? = null,
        val targetClipId: String? = null,
        val atSec: Float? = null,
        val atFrame: Int? = null,
        val enabled: Boolean? = null,
        val offsetSec: Float? = null,
        val newClipId: String? = null,
        val multiplier: Float? = null,
        val startSec: Float? = null,
        val durationSec: Float? = null,
        val audioTrimStartMs: Long? = null,
        val audioTrimEndMs: Long? = null
    )

    data class ScriptClipDto(
        val id: String? = null,
        val layerId: String? = null,
        val name: String? = null,
        val startSec: Float? = null,
        val startFrame: Int? = null,
        val durationSec: Float? = null,
        val durationFrames: Int? = null,
        val characterId: String? = null,
        val inlineSvgAssetId: String? = null,
        val imageAssetId: String? = null,
        val audioAssetId: String? = null,
        val lipSyncAudioId: String? = null,
        val customAnimationId: String? = null,
        val orientation: String? = null,
        val animationPreset: String? = null,
        val facialExpression: String? = null,
        val mouthShapeOverride: String? = null,
        val eyeShapeOverride: String? = null,
        val eyebrowShapeOverride: String? = null,
        val handPose: String? = null,
        val propType: String? = null,
        val propAttachment: String? = null,
        val speed: Float? = null,
        val loopAnimation: Boolean? = true,
        val transitionIn: String? = null,
        val transitionOut: String? = null,
        val flipX: Boolean? = null,
        val flipY: Boolean? = null,
        val mask: ScriptMaskDto? = null,
        val motionPath: List<ScriptPointDto>? = null,
        val textContent: String? = null,
        val textColorHex: String? = null,
        val textBubbleStyle: String? = null,
        val shapeKind: String? = null,
        val shapeFillHex: String? = null,
        val audioTrimStartMs: Long? = null,
        val audioTrimEndMs: Long? = null,
        val depthOrder: Int? = 0,
        val keyframes: List<ScriptKeyframeDto>? = null
    )

    data class ScriptCameraShotDto(
        val tSec: Float? = null,
        val frame: Int? = null,
        val durationSec: Float? = null,
        val shotType: String? = null,
        val focusTargetCharacterId: String? = null,
        val focusTargetClipId: String? = null,
        val secondaryTargetCharacterId: String? = null,
        val x: Float? = null,
        val y: Float? = null,
        val zoom: Float? = null,
        val rotation: Float? = null,
        val easing: String? = null,
        val cubicBezier: List<Float>? = null,
        val shakeIntensity: Float? = null
    )

    data class ScriptSceneDto(
        val name: String? = null,
        val durationSec: Float? = null,
        val durationFrames: Int? = null,
        val backgroundColorHex: String? = null,
        val backgroundAssetId: String? = null,
        val colourGrading: String? = null,
        val colorGrading: String? = null,
        val vfx: ScriptVfxDto? = null,
        val customTransitionInId: String? = null,
        val customTransitionOutId: String? = null,
        val layers: List<ScriptLayerDto>? = null,
        val clips: List<ScriptClipDto>? = null,
        val clipOperations: List<ScriptClipOperationDto>? = null,
        val cameraShots: List<ScriptCameraShotDto>? = null
    )

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Parses and dry-run validates raw `.fcscript.json` text without mutating the current project.
     */
    fun validateAndDryRun(
        scriptJson: String,
        existingCharacters: List<CharacterRigDefinition>,
        existingMediaAssets: List<LocalMediaAsset>
    ): Pair<ScriptDocumentDto?, DirectorScriptDryRunReport> {
        val trimmed = scriptJson.trim()
        if (trimmed.isEmpty()) {
            return null to DirectorScriptDryRunReport(
                isValid = false,
                errors = listOf("Director script JSON is empty.")
            )
        }

        val parsed = runCatching {
            moshi.adapter(ScriptDocumentDto::class.java).fromJson(trimmed)
        }.getOrElse { err ->
            return null to DirectorScriptDryRunReport(
                isValid = false,
                errors = listOf("Invalid JSON syntax: ${err.message ?: "unable to parse"}")
            )
        } ?: return null to DirectorScriptDryRunReport(
            isValid = false,
            errors = listOf("JSON parsed to null document.")
        )

        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val inlineSvgs = parsed.inlineSvgAssets.orEmpty()
        val inlineIds = HashSet<String>()
        var validInlineCount = 0
        for ((idx, svgDto) in inlineSvgs.withIndex()) {
            val id = svgDto.id?.takeIf { it.isNotBlank() }
            if (id == null) {
                warnings.add("inlineSvgAssets[$idx] missing 'id' — will be skipped.")
                continue
            }
            val xml = svgDto.svgXml.orEmpty()
            val check = InlineSvgAssetSpawner.sanitizeSvgXml(xml, svgDto.viewBox ?: "0 0 256 256")
            if (!check.isValid) {
                warnings.add("inlineSvgAssets['$id'] rejected: ${check.errorMessage}")
            } else {
                inlineIds.add(id)
                validInlineCount++
            }
        }

        val customAnims = parsed.customAnimations.orEmpty()
        val customTrans = parsed.customTransitions.orEmpty()
        val scenes = parsed.scenes.orEmpty()
        if (scenes.isEmpty() && validInlineCount == 0 && customAnims.isEmpty() && customTrans.isEmpty()) {
            errors.add("Script contains no scenes, inline SVGs, custom animations, or custom transitions.")
        }

        val knownCharIds = existingCharacters.map { it.id }.toSet() + inlineIds
        val knownAssetIds = existingMediaAssets.map { it.id }.toSet() + inlineIds

        var totalLayers = 0
        var totalClips = 0
        var totalKeyframes = 0
        var totalCameraShots = 0
        var totalLipSync = 0
        var totalOps = 0

        for ((sIdx, scene) in scenes.withIndex()) {
            totalLayers += scene.layers.orEmpty().size
            val clips = scene.clips.orEmpty()
            totalClips += clips.size
            totalOps += scene.clipOperations.orEmpty().size
            totalCameraShots += scene.cameraShots.orEmpty().size
            for (clip in clips) {
                totalKeyframes += max(1, clip.keyframes.orEmpty().size)
                if (!clip.lipSyncAudioId.isNullOrBlank()) totalLipSync++
                val cid = clip.characterId
                if (!cid.isNullOrBlank() && cid !in knownCharIds) {
                    warnings.add("Scene ${sIdx + 1}: characterId '$cid' not found — will fall back to first available character.")
                }
                val bgId = clip.imageAssetId
                if (!bgId.isNullOrBlank() && bgId !in knownAssetIds) {
                    warnings.add("Scene ${sIdx + 1}: imageAssetId '$bgId' not found in library.")
                }
                val animId = clip.customAnimationId
                if (!animId.isNullOrBlank() && animId !in customAnims.keys) {
                    warnings.add("Scene ${sIdx + 1}: customAnimationId '$animId' not defined in customAnimations{}.")
                }
            }
            val transIn = scene.customTransitionInId
            if (!transIn.isNullOrBlank() && transIn !in customTrans.keys) {
                warnings.add("Scene ${sIdx + 1}: customTransitionInId '$transIn' not defined in customTransitions{}.")
            }
            val transOut = scene.customTransitionOutId
            if (!transOut.isNullOrBlank() && transOut !in customTrans.keys) {
                warnings.add("Scene ${sIdx + 1}: customTransitionOutId '$transOut' not defined in customTransitions{}.")
            }
        }

        val reqRatio = parsed.ratio ?: parsed.aspectRatio
        if (!reqRatio.isNullOrBlank() && StudioCapabilityRegistry.resolveAspectRatio(reqRatio) == null) {
            warnings.add("Unknown aspect ratio '$reqRatio' — keeping current project aspect ratio.")
        }

        val report = DirectorScriptDryRunReport(
            isValid = errors.isEmpty(),
            formatVersion = parsed.schemaVersion ?: 3,
            sceneCount = scenes.size,
            inlineSvgCount = validInlineCount,
            customAnimationCount = customAnims.size,
            customTransitionCount = customTrans.size,
            layerCount = totalLayers,
            clipCount = totalClips,
            keyframeCount = totalKeyframes,
            cameraShotCount = totalCameraShots,
            lipSyncTrackCount = totalLipSync,
            clipOperationCount = totalOps,
            requestedAspectRatio = reqRatio,
            warnings = warnings,
            errors = errors
        )
        return parsed to report
    }

    /**
     * Compiles a validated `.fcscript.json` document into [currentProject], spawning any inline
     * SVG assets, storing custom animations & transitions, executing all studio clip operations,
     * auto-generating lip-sync mouth keyframes from audio waveforms, and compiling dialogue-driven
     * cinematic camera shots.
     */
    fun compileIntoProject(
        context: Context?,
        scriptJson: String,
        currentProject: ProjectDocument,
        existingCharacters: List<CharacterRigDefinition>,
        existingMediaAssets: List<LocalMediaAsset>,
        importMode: DirectorScriptImportMode,
        waveformProvider: (audioPath: String) -> AudioWaveformData? = { path ->
            runCatching { AudioWaveformExtractor.getCached(path) }.getOrNull()
                ?: java.io.File(path).takeIf { it.exists() }?.let { AudioWaveformExtractor.parseWavWaveform(it, 240) }
        }
    ): Result<DirectorScriptCompileResult> {
        val (dto, report) = validateAndDryRun(scriptJson, existingCharacters, existingMediaAssets)
        if (dto == null || !report.isValid) {
            return Result.failure(IllegalArgumentException(report.errors.firstOrNull() ?: "Invalid director script"))
        }

        val fps = (dto.fps ?: currentProject.fps).coerceIn(12, 60)
        val requestedRatio = StudioCapabilityRegistry.resolveAspectRatio(dto.ratio ?: dto.aspectRatio)
        val newRatio = requestedRatio ?: currentProject.aspectRatioPreset
        val newWidth = if (requestedRatio != null) requestedRatio.defaultWidth else currentProject.width
        val newHeight = if (requestedRatio != null) requestedRatio.defaultHeight else currentProject.height

        // 1. Spawn & persist Inline SVG Assets
        val mergedInlineSvgs = LinkedHashMap(currentProject.inlineSvgAssets)
        val spawnedCharacters = mutableListOf<CharacterRigDefinition>()
        val spawnedMediaAssets = mutableListOf<LocalMediaAsset>()

        for (svgDto in dto.inlineSvgAssets.orEmpty()) {
            val id = svgDto.id?.takeIf { it.isNotBlank() } ?: continue
            val xml = svgDto.svgXml ?: continue
            val assetType = parseEnumIgnoreCase<InlineSvgAssetType>(svgDto.type) ?: InlineSvgAssetType.PROP
            val rawDef = InlineSvgAssetDef(
                id = id,
                type = assetType,
                viewBox = svgDto.viewBox ?: "0 0 256 256",
                svgXml = xml
            )
            val persisted = InlineSvgAssetSpawner.spawnAndPersist(context, rawDef) ?: continue
            mergedInlineSvgs[id] = persisted

            if (assetType == InlineSvgAssetType.CHARACTER) {
                spawnedCharacters.add(InlineSvgAssetSpawner.buildCharacterRigFromInlineSvg(persisted, svgDto.name))
            } else {
                val mediaType = if (assetType == InlineSvgAssetType.BG) MediaAssetType.BACKGROUND else MediaAssetType.SVG
                spawnedMediaAssets.add(
                    LocalMediaAsset(
                        id = id,
                        name = svgDto.name?.takeIf { it.isNotBlank() } ?: id,
                        category = assetType.displayName,
                        type = mediaType,
                        localFilePath = persisted.localSvgPath,
                        mimeType = "image/svg+xml",
                        isBundled = false
                    )
                )
            }
        }

        // 2. Compile Custom Animations
        val mergedCustomAnims = LinkedHashMap(currentProject.customAnimations)
        for ((animId, animDto) in dto.customAnimations.orEmpty()) {
            if (animId.isBlank()) continue
            val loopMode = parseEnumIgnoreCase<CustomAnimLoopMode>(animDto.loopMode) ?: CustomAnimLoopMode.LOOP
            val tracks = animDto.tracks.orEmpty().mapNotNull { tDto ->
                val targetId = tDto.targetNodeId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kfs = tDto.keyframes.orEmpty().map { k ->
                    CustomAnimKeyframe(
                        t = k.t.coerceIn(0f, 1f),
                        dx = k.dx,
                        dy = k.dy,
                        rotation = k.rotation,
                        scaleX = k.scaleX.coerceIn(0.05f, 8f),
                        scaleY = k.scaleY.coerceIn(0.05f, 8f),
                        opacity = k.opacity.coerceIn(0f, 1f),
                        cubicBezier = k.cubicBezier?.takeIf { it.size >= 4 } ?: listOf(0.42f, 0f, 0.58f, 1f)
                    )
                }.sortedBy { it.t }
                if (kfs.isEmpty()) null else CustomAnimTrack(targetNodeId = targetId, keyframes = kfs)
            }
            if (tracks.isNotEmpty()) {
                mergedCustomAnims[animId] = CustomAnimationDef(
                    id = animId,
                    durationSec = (animDto.durationSec ?: 1.0f).coerceIn(0.08f, 120f),
                    loopMode = loopMode,
                    tracks = tracks
                )
            }
        }

        // 3. Compile Custom Transitions
        val mergedCustomTrans = LinkedHashMap(currentProject.customTransitions)
        for ((transId, tDto) in dto.customTransitions.orEmpty()) {
            if (transId.isBlank()) continue
            val clipType = parseEnumIgnoreCase<CustomTransitionClipType>(tDto.clipPathType)
                ?: CustomTransitionClipType.WIPE_ANGLE
            mergedCustomTrans[transId] = CustomTransitionDef(
                id = transId,
                durationSec = (tDto.durationSec ?: 0.6f).coerceIn(0.1f, 8f),
                clipPathType = clipType,
                angleDeg = tDto.angleDeg,
                customSvgPath = tDto.customSvgPath,
                outgoing = tDto.outgoing?.let {
                    CustomTransitionTransform(it.dx, it.dy, it.scale, it.rotation, it.alpha)
                } ?: CustomTransitionTransform(),
                incoming = tDto.incoming?.let {
                    CustomTransitionTransform(it.dx, it.dy, it.scale, it.rotation, it.alpha)
                } ?: CustomTransitionTransform(),
                flashOverlay = tDto.flashOverlay?.let {
                    CustomTransitionFlash(it.colorHex ?: "#FFFFFF", it.peakAlpha.coerceIn(0f, 1f))
                },
                cubicBezier = tDto.cubicBezier?.takeIf { it.size >= 4 } ?: listOf(0.42f, 0f, 0.58f, 1f)
            )
        }

        // Build lookup tables across existing + newly spawned assets
        val allCharacters = existingCharacters + spawnedCharacters
        val charactersById = allCharacters.associateBy { it.id }
        val allMediaAssets = existingMediaAssets + spawnedMediaAssets
        val mediaById = allMediaAssets.associateBy { it.id }

        // Map existing timeline audio clips by id so lipSyncAudioId can reference them too
        val existingAudioPathById = HashMap<String, String>()
        for (scene in currentProject.scenes) {
            for (clip in scene.clips) {
                if (!clip.audioPath.isNullOrBlank()) {
                    existingAudioPathById[clip.id] = clip.audioPath
                }
            }
        }
        for (asset in allMediaAssets) {
            if (asset.type == MediaAssetType.AUDIO && !asset.localFilePath.isNullOrBlank()) {
                existingAudioPathById[asset.id] = asset.localFilePath
            }
        }

        // 4. Compile Scenes
        val compiledScenes = mutableListOf<AnimationScene>()
        val baseSceneIndex = if (importMode == DirectorScriptImportMode.APPEND_SCENES) currentProject.scenes.size else 0

        for ((idx, sceneDto) in dto.scenes.orEmpty().withIndex()) {
            val sceneDurationFrames = (sceneDto.durationFrames
                ?: sceneDto.durationSec?.let { (it * fps).roundToInt() }
                ?: 144).coerceIn(12, fps * 300)

            // Resolve background asset
            val bgAsset = sceneDto.backgroundAssetId?.let { mediaById[it] }
            val bgInline = sceneDto.backgroundAssetId?.let { mergedInlineSvgs[it] }

            // Resolve VFX & colour grading
            val vfxDto = sceneDto.vfx
            val mood = parseEnumIgnoreCase<SceneLightingMood>(
                sceneDto.colourGrading ?: sceneDto.colorGrading ?: vfxDto?.lightingMood
            ) ?: SceneLightingMood.NONE
            val particle = parseEnumIgnoreCase<SceneParticleType>(
                vfxDto?.particleType ?: vfxDto?.style
            ) ?: SceneParticleType.NONE
            var sceneVfx = SceneVfxConfig(
                particleType = particle,
                particleIntensity = (vfxDto?.intensity ?: 0.65f).coerceIn(0f, 1f),
                particleSpeed = (vfxDto?.speed ?: 1.0f).coerceIn(0.2f, 3f),
                lightingMood = mood,
                vignetteIntensity = (vfxDto?.vignette ?: 0f).coerceIn(0f, 1f),
                cameraShakeIntensity = (vfxDto?.cameraShake ?: 0f).coerceIn(0f, 1f)
            )

            // Build layers (preserving zIndex, visible, locked, blendMode, parentLayerId)
            val layersById = LinkedHashMap<String, AnimationLayer>()
            sceneDto.layers.orEmpty()
                .sortedBy { it.zIndex ?: 0 }
                .forEachIndexed { lIdx, lDto ->
                    val layerType = parseEnumIgnoreCase<LayerType>(lDto.type) ?: LayerType.IMAGE
                    val layerId = lDto.id?.takeIf { it.isNotBlank() } ?: "layer_${idx}_$lIdx"
                    layersById[layerId] = AnimationLayer(
                        id = layerId,
                        name = lDto.name?.takeIf { it.isNotBlank() } ?: layerType.displayName,
                        type = layerType,
                        isVisible = lDto.visible ?: true,
                        isLocked = lDto.locked ?: false,
                        colorHex = lDto.colorHex ?: layerType.defaultColorHex,
                        orderIndex = lDto.zIndex ?: lIdx,
                        blendMode = parseEnumIgnoreCase<LayerBlendMode>(lDto.blendMode) ?: LayerBlendMode.NORMAL,
                        parentLayerId = lDto.parentLayerId
                    )
                }

            // Ensure helper to auto-create layer if a clip references an undeclared layerId
            fun ensureLayer(requestedLayerId: String?, inferredType: LayerType, clipName: String): String {
                if (!requestedLayerId.isNullOrBlank() && layersById.containsKey(requestedLayerId)) {
                    return requestedLayerId
                }
                val newId = requestedLayerId?.takeIf { it.isNotBlank() } ?: "layer_${UUID.randomUUID().toString().take(8)}"
                layersById[newId] = AnimationLayer(
                    id = newId,
                    name = clipName.ifBlank { inferredType.displayName },
                    type = inferredType,
                    orderIndex = layersById.size
                )
                return newId
            }

            // Compile clips
            val clips = mutableListOf<TimelineClip>()
            for ((cIdx, cDto) in sceneDto.clips.orEmpty().withIndex()) {
                val clipId = cDto.id?.takeIf { it.isNotBlank() } ?: "clip_${idx}_$cIdx"
                val inferredType = inferClipLayerType(cDto, mergedInlineSvgs)
                val clipName = cDto.name?.takeIf { it.isNotBlank() }
                    ?: cDto.characterId?.let { charactersById[it]?.name }
                    ?: cDto.inlineSvgAssetId
                    ?: "Clip ${cIdx + 1}"
                val layerId = ensureLayer(cDto.layerId, inferredType, clipName)

                val startFrame = (cDto.startFrame
                    ?: cDto.startSec?.let { (it * fps).roundToInt() }
                    ?: 0).coerceIn(0, sceneDurationFrames - 1)
                val durationFrames = (cDto.durationFrames
                    ?: cDto.durationSec?.let { (it * fps).roundToInt() }
                    ?: (sceneDurationFrames - startFrame)).coerceIn(4, sceneDurationFrames)

                val resolvedCharId = when {
                    !cDto.characterId.isNullOrBlank() && charactersById.containsKey(cDto.characterId) -> cDto.characterId
                    inferredType == LayerType.CHARACTER -> allCharacters.firstOrNull()?.id
                    else -> null
                }

                val inlineId = cDto.inlineSvgAssetId?.takeIf { mergedInlineSvgs.containsKey(it) }
                val imgAsset = cDto.imageAssetId?.let { mediaById[it] }
                val audioAsset = cDto.audioAssetId?.let { mediaById[it] }
                val resolvedAudioPath = audioAsset?.localFilePath
                    ?: cDto.audioAssetId?.let { existingAudioPathById[it] }
                if (!resolvedAudioPath.isNullOrBlank()) {
                    existingAudioPathById[clipId] = resolvedAudioPath
                }

                // Compile keyframes (including custom cubicBezier, expressions, overrides, and IK pose!)
                val compiledKfs = compileKeyframes(cDto.keyframes, fps, durationFrames)

                // Compile curved Bezier motionPath control points
                val motionPoints = cDto.motionPath.orEmpty().map {
                    NormalizedPoint(it.x.coerceIn(-0.5f, 1.5f), it.y.coerceIn(-0.5f, 1.5f))
                }

                // Auto Audio-Driven Lip-Sync via AudioWaveformExtractor
                val lipSyncAudioPath = cDto.lipSyncAudioId?.let { existingAudioPathById[it] }
                val finalKeyframes = if (!cDto.lipSyncAudioId.isNullOrBlank() && inferredType == LayerType.CHARACTER) {
                    applyAutoAudioLipSync(
                        baseKeyframes = compiledKfs,
                        audioPath = lipSyncAudioPath,
                        durationFrames = durationFrames,
                        fps = fps,
                        waveformProvider = waveformProvider
                    )
                } else {
                    compiledKfs
                }

                val clip = TimelineClip(
                    id = clipId,
                    layerId = layerId,
                    name = clipName,
                    startFrame = startFrame,
                    durationFrames = durationFrames,
                    characterId = resolvedCharId,
                    orientation = parseEnumIgnoreCase<CharacterOrientation>(cDto.orientation) ?: CharacterOrientation.FRONT,
                    animationPresetType = parseEnumIgnoreCase<AnimationPresetType>(cDto.animationPreset)
                        ?: if (!cDto.lipSyncAudioId.isNullOrBlank()) AnimationPresetType.TALKING else AnimationPresetType.IDLE,
                    facialExpression = parseEnumIgnoreCase<FacialExpression>(cDto.facialExpression) ?: FacialExpression.AUTO,
                    mouthShapeOverride = parseEnumIgnoreCase<MouthShapeOverride>(cDto.mouthShapeOverride) ?: MouthShapeOverride.AUTO,
                    eyeShapeOverride = parseEnumIgnoreCase<EyeShapeOverride>(cDto.eyeShapeOverride) ?: EyeShapeOverride.AUTO,
                    eyebrowShapeOverride = parseEnumIgnoreCase<EyebrowShapeOverride>(cDto.eyebrowShapeOverride) ?: EyebrowShapeOverride.AUTO,
                    handPose = parseEnumIgnoreCase<HandPoseType>(cDto.handPose) ?: HandPoseType.OPEN,
                    propType = parseEnumIgnoreCase<CharacterPropType>(cDto.propType) ?: CharacterPropType.NONE,
                    propAttachment = parseEnumIgnoreCase<PropAttachmentPoint>(cDto.propAttachment) ?: PropAttachmentPoint.RIGHT_HAND,
                    speedMultiplier = (cDto.speed ?: 1.0f).coerceIn(0.25f, 4.0f),
                    loopAnimation = cDto.loopAnimation ?: true,
                    transitionIn = parseEnumIgnoreCase<TransitionType>(cDto.transitionIn) ?: TransitionType.NONE,
                    transitionOut = parseEnumIgnoreCase<TransitionType>(cDto.transitionOut) ?: TransitionType.NONE,
                    imagePath = imgAsset?.localFilePath ?: inlineId?.let { mergedInlineSvgs[it]?.localSvgPath },
                    bundledBgKey = imgAsset?.bundledResKey,
                    textContent = cDto.textContent,
                    textColorHex = cDto.textColorHex ?: "#FFFFFF",
                    textBubbleStyle = parseEnumIgnoreCase<TextBubbleStyle>(cDto.textBubbleStyle) ?: TextBubbleStyle.NONE,
                    shapeKind = parseEnumIgnoreCase<ShapeKind>(cDto.shapeKind),
                    shapeFillHex = cDto.shapeFillHex ?: "#38BDF8",
                    audioPath = resolvedAudioPath,
                    audioLabel = audioAsset?.name ?: clipName,
                    audioTrimStartMs = (cDto.audioTrimStartMs ?: 0L).coerceAtLeast(0L),
                    audioTrimEndMs = (cDto.audioTrimEndMs ?: 0L).coerceAtLeast(0L),
                    keyframes = finalKeyframes,
                    flipHorizontal = cDto.flipX ?: false,
                    flipVertical = cDto.flipY ?: false,
                    depthOrder = cDto.depthOrder ?: 0,
                    inlineSvgAssetId = inlineId,
                    customAnimationId = cDto.customAnimationId?.takeIf { mergedCustomAnims.containsKey(it) },
                    maskType = parseEnumIgnoreCase<ClipMaskType>(cDto.mask?.type) ?: ClipMaskType.NONE,
                    maskRadius = (cDto.mask?.radius ?: 0.46f).coerceIn(0.08f, 0.95f),
                    motionPathControlPoints = motionPoints,
                    lipSyncAudioClipId = cDto.lipSyncAudioId
                )
                clips.add(clip)
            }

            // Execute Clip Operations: split, flipX, flipY, duplicate, delete, speed, trim
            executeClipOperations(
                clips = clips,
                operations = sceneDto.clipOperations.orEmpty(),
                fps = fps,
                sceneDurationFrames = sceneDurationFrames
            )

            // Compile Dialogue-Driven Cinematic Auto-Focus Camera Shots
            val (cameraKeyframes, requestedShake) = compileCinematicCameraShots(
                shots = sceneDto.cameraShots.orEmpty(),
                clips = clips,
                fps = fps,
                sceneDurationFrames = sceneDurationFrames
            )
            if (requestedShake > sceneVfx.cameraShakeIntensity) {
                sceneVfx = sceneVfx.copy(cameraShakeIntensity = requestedShake.coerceIn(0f, 1f))
            }

            val orderedLayers = layersById.values.sortedBy { it.orderIndex }
                .mapIndexed { i, l -> l.copy(orderIndex = i) }

            compiledScenes.add(
                AnimationScene(
                    id = UUID.randomUUID().toString(),
                    name = sceneDto.name?.takeIf { it.isNotBlank() } ?: "Scene ${baseSceneIndex + idx + 1}",
                    backgroundColorHex = sceneDto.backgroundColorHex ?: "#162033",
                    backgroundBundledKey = bgAsset?.bundledResKey,
                    backgroundImagePath = bgAsset?.localFilePath ?: bgInline?.localSvgPath,
                    cameraKeyframes = cameraKeyframes,
                    cameraLayerEnabled = true,
                    vfxConfig = sceneVfx,
                    durationFrames = sceneDurationFrames,
                    layers = orderedLayers,
                    clips = clips,
                    customTransitionInId = sceneDto.customTransitionInId?.takeIf { mergedCustomTrans.containsKey(it) },
                    customTransitionOutId = sceneDto.customTransitionOutId?.takeIf { mergedCustomTrans.containsKey(it) }
                )
            )
        }

        val finalScenes = when {
            compiledScenes.isEmpty() -> currentProject.scenes
            importMode == DirectorScriptImportMode.REPLACE_TIMELINE -> compiledScenes
            else -> currentProject.scenes + compiledScenes
        }
        val targetActiveIdx = when {
            compiledScenes.isEmpty() -> currentProject.activeSceneIndex
            importMode == DirectorScriptImportMode.REPLACE_TIMELINE -> 0
            else -> currentProject.scenes.size
        }.coerceIn(0, (finalScenes.size - 1).coerceAtLeast(0))

        val updatedProject = currentProject.copy(
            name = dto.projectName?.takeIf { it.isNotBlank() } ?: currentProject.name,
            fps = fps,
            aspectRatioPreset = newRatio,
            width = newWidth,
            height = newHeight,
            activeSceneIndex = targetActiveIdx,
            scenes = finalScenes,
            inlineSvgAssets = mergedInlineSvgs,
            customAnimations = mergedCustomAnims,
            customTransitions = mergedCustomTrans,
            updatedAt = System.currentTimeMillis()
        )

        return Result.success(
            DirectorScriptCompileResult(
                project = updatedProject,
                spawnedCharacters = spawnedCharacters,
                spawnedMediaAssets = spawnedMediaAssets,
                report = report
            )
        )
    }

    // ────────────────────────── Sub-Compilers ──────────────────────────

    private fun inferClipLayerType(
        dto: ScriptClipDto,
        inlineSvgs: Map<String, InlineSvgAssetDef>
    ): LayerType {
        if (!dto.characterId.isNullOrBlank()) return LayerType.CHARACTER
        if (!dto.audioAssetId.isNullOrBlank()) return LayerType.AUDIO
        if (!dto.textContent.isNullOrBlank()) return LayerType.TEXT
        if (!dto.shapeKind.isNullOrBlank()) return LayerType.SHAPE
        val svgDef = dto.inlineSvgAssetId?.let { inlineSvgs[it] }
        if (svgDef != null) {
            return when (svgDef.type) {
                InlineSvgAssetType.CHARACTER -> LayerType.CHARACTER
                InlineSvgAssetType.BG -> LayerType.BACKGROUND
                else -> LayerType.IMAGE
            }
        }
        return LayerType.IMAGE
    }

    private fun compileKeyframes(
        rawKfs: List<ScriptKeyframeDto>?,
        fps: Int,
        durationFrames: Int
    ): List<TransformKeyframe> {
        if (rawKfs.isNullOrEmpty()) {
            return listOf(TransformKeyframe(frame = 0, x = 0.5f, y = AIBlueprintExporter.GROUND_Y_NORMALIZED))
        }
        var curX = 0.5f
        var curY = AIBlueprintExporter.GROUND_Y_NORMALIZED
        var curRot = 0f
        var curSx = 1f
        var curSy = 1f
        var curOpacity = 1f

        return rawKfs.map { dto ->
            val f = (dto.frame ?: dto.tSec?.let { (it * fps).roundToInt() } ?: 0)
                .coerceIn(0, (durationFrames - 1).coerceAtLeast(0))
            if (dto.x != null) curX = dto.x
            if (dto.y != null) curY = dto.y
            if (dto.rotation != null) curRot = dto.rotation
            if (dto.scaleX != null) curSx = dto.scaleX.coerceIn(0.05f, 6f)
            if (dto.scaleY != null) curSy = dto.scaleY.coerceIn(0.05f, 6f)
            if (dto.opacity != null) curOpacity = dto.opacity.coerceIn(0f, 1f)

            val bez = dto.cubicBezier
            val easing = when {
                bez != null && bez.size >= 4 -> EasingType.CUSTOM_CUBIC
                else -> parseEnumIgnoreCase<EasingType>(dto.easing) ?: EasingType.EASE_IN_OUT
            }
            val poseOffsets = dto.ikPose?.let { ik ->
                PoseKeyframeOffsets(
                    headNodOffset = ik.headNod,
                    torsoTiltOffset = ik.torsoTilt,
                    leftArmOffset = ik.leftArm,
                    leftForearmOffset = ik.leftForearm,
                    rightArmOffset = ik.rightArm,
                    rightForearmOffset = ik.rightForearm,
                    leftLegOffset = ik.leftLeg,
                    leftKneeOffset = ik.leftKnee,
                    rightLegOffset = ik.rightLeg,
                    rightKneeOffset = ik.rightKnee
                )
            } ?: PoseKeyframeOffsets()

            TransformKeyframe(
                frame = f,
                x = curX,
                y = curY,
                rotation = curRot,
                scaleX = curSx,
                scaleY = curSy,
                opacity = curOpacity,
                easing = easing,
                easingP1X = bez?.getOrNull(0) ?: 0.42f,
                easingP1Y = bez?.getOrNull(1) ?: 0f,
                easingP2X = bez?.getOrNull(2) ?: 0.58f,
                easingP2Y = bez?.getOrNull(3) ?: 1f,
                poseOffsets = poseOffsets,
                expressionOverride = parseEnumIgnoreCase<FacialExpression>(dto.expression),
                facialPresetId = dto.facialPresetId,
                mouthShapeOverride = parseEnumIgnoreCase<MouthShapeOverride>(dto.mouthShape),
                eyeShapeOverride = parseEnumIgnoreCase<EyeShapeOverride>(dto.eyeShape),
                eyebrowShapeOverride = parseEnumIgnoreCase<EyebrowShapeOverride>(dto.eyebrowShape),
                handPoseOverride = parseEnumIgnoreCase<HandPoseType>(dto.handPose),
                blinkOverride = dto.blink
            )
        }.sortedBy { it.frame }
            .distinctBy { it.frame }
    }

    /**
     * Generates organic, audio-amplitude-synced [MouthShapeOverride] keyframes when `lipSyncAudioId`
     * is provided, using real waveform peaks from [AudioWaveformExtractor] (or rhythmic syllable
     * cadence if the audio file isn't present in a unit test).
     */
    private fun applyAutoAudioLipSync(
        baseKeyframes: List<TransformKeyframe>,
        audioPath: String?,
        durationFrames: Int,
        fps: Int,
        waveformProvider: (String) -> AudioWaveformData?
    ): List<TransformKeyframe> {
        val waveform = audioPath?.let { waveformProvider(it) }
        val stepFrames = max(2, fps / 8) // ~8 mouth changes per second for crisp speech sync
        val sortedBase = baseKeyframes.sortedBy { it.frame }
        val byFrame = LinkedHashMap<Int, TransformKeyframe>()
        for (kf in sortedBase) byFrame[kf.frame] = kf

        var lastMouth: MouthShapeOverride? = null
        var frame = 0
        while (frame < durationFrames) {
            val amp = if (waveform != null && waveform.amplitudes.isNotEmpty()) {
                val frac = (frame.toFloat() / durationFrames.coerceAtLeast(1).toFloat()).coerceIn(0f, 0.999f)
                val idx = (frac * waveform.amplitudes.size).toInt().coerceIn(0, waveform.amplitudes.lastIndex)
                waveform.amplitudes[idx]
            } else {
                // Deterministic speech envelope fallback when audio file is offline/unloaded
                val t = frame.toFloat() / fps.coerceAtLeast(1).toFloat()
                (abs(sin(t * 7.3f)) * (0.45f + 0.55f * abs(sin(t * 2.1f)))).coerceIn(0f, 1f)
            }

            val mouth = when {
                amp < 0.14f -> MouthShapeOverride.NEUTRAL
                amp < 0.36f -> MouthShapeOverride.SMILE
                amp < 0.62f -> MouthShapeOverride.TALK_EE
                amp < 0.84f -> MouthShapeOverride.TALK_AA
                else -> MouthShapeOverride.OPEN_LAUGH
            }

            if (mouth != lastMouth) {
                lastMouth = mouth
                val existing = byFrame[frame]
                if (existing != null) {
                    byFrame[frame] = existing.copy(mouthShapeOverride = existing.mouthShapeOverride ?: mouth)
                } else {
                    val eval = KeyframeEvaluator.evaluateKeyframes(sortedBase, frame)
                    byFrame[frame] = TransformKeyframe(
                        frame = frame,
                        x = eval.x,
                        y = eval.y,
                        rotation = eval.rotation,
                        scaleX = eval.scaleX,
                        scaleY = eval.scaleY,
                        opacity = eval.opacity,
                        easing = EasingType.LINEAR,
                        poseOffsets = eval.poseOffsets,
                        mouthShapeOverride = mouth
                    )
                }
            }
            frame += stepFrames
        }
        return byFrame.values.sortedBy { it.frame }
    }

    /**
     * Executes full Studio clip operations declared in `clipOperations[]`:
     * `split`, `flipX`, `flipY`, `duplicate`, `delete`, `speed`, and `trim`.
     */
    private fun executeClipOperations(
        clips: MutableList<TimelineClip>,
        operations: List<ScriptClipOperationDto>,
        fps: Int,
        sceneDurationFrames: Int
    ) {
        for (opDto in operations) {
            val opName = opDto.op?.trim()?.lowercase() ?: continue
            val targetId = opDto.targetClipId ?: continue
            val idx = clips.indexOfFirst { it.id == targetId }
            if (idx < 0) continue
            val target = clips[idx]

            when (opName) {
                "split" -> {
                    val splitGlobalFrame = opDto.atFrame
                        ?: opDto.atSec?.let { (it * fps).roundToInt() }
                        ?: (target.startFrame + target.durationFrames / 2)
                    val localSplit = splitGlobalFrame - target.startFrame
                    if (localSplit in 2 until (target.durationFrames - 2)) {
                        val evalAtSplit = KeyframeEvaluator.evaluate(target, splitGlobalFrame)
                        val leftKfs = target.keyframes.filter { it.frame < localSplit } +
                            TransformKeyframe(
                                frame = localSplit,
                                x = evalAtSplit.x, y = evalAtSplit.y,
                                rotation = evalAtSplit.rotation,
                                scaleX = evalAtSplit.scaleX, scaleY = evalAtSplit.scaleY,
                                opacity = evalAtSplit.opacity,
                                poseOffsets = evalAtSplit.poseOffsets
                            )
                        val rightKfs = listOf(
                            TransformKeyframe(
                                frame = 0,
                                x = evalAtSplit.x, y = evalAtSplit.y,
                                rotation = evalAtSplit.rotation,
                                scaleX = evalAtSplit.scaleX, scaleY = evalAtSplit.scaleY,
                                opacity = evalAtSplit.opacity,
                                poseOffsets = evalAtSplit.poseOffsets
                            )
                        ) + target.keyframes.filter { it.frame > localSplit }
                            .map { it.copy(frame = it.frame - localSplit) }

                        val firstHalf = target.copy(
                            durationFrames = localSplit,
                            keyframes = leftKfs.distinctBy { it.frame }.sortedBy { it.frame }
                        )
                        val secondHalf = target.copy(
                            id = opDto.newClipId?.takeIf { it.isNotBlank() } ?: "${target.id}_part2",
                            name = "${target.name} (2)",
                            startFrame = splitGlobalFrame,
                            durationFrames = target.durationFrames - localSplit,
                            keyframes = rightKfs.distinctBy { it.frame }.sortedBy { it.frame }
                        )
                        clips[idx] = firstHalf
                        clips.add(idx + 1, secondHalf)
                    }
                }
                "flipx" -> {
                    val enabled = opDto.enabled ?: !target.flipHorizontal
                    clips[idx] = target.copy(flipHorizontal = enabled)
                }
                "flipy" -> {
                    val enabled = opDto.enabled ?: !target.flipVertical
                    clips[idx] = target.copy(flipVertical = enabled)
                }
                "duplicate" -> {
                    val offsetFrames = ((opDto.offsetSec ?: 0f) * fps).roundToInt()
                    val clone = target.copy(
                        id = opDto.newClipId?.takeIf { it.isNotBlank() } ?: "${target.id}_copy",
                        name = "${target.name} Copy",
                        startFrame = (target.startFrame + offsetFrames).coerceIn(0, sceneDurationFrames - 1)
                    )
                    clips.add(idx + 1, clone)
                }
                "delete" -> {
                    clips.removeAt(idx)
                }
                "speed" -> {
                    val mult = (opDto.multiplier ?: 1.0f).coerceIn(0.25f, 4.0f)
                    clips[idx] = target.copy(speedMultiplier = mult)
                }
                "trim" -> {
                    val newStart = opDto.startSec?.let { (it * fps).roundToInt() }
                        ?.coerceIn(0, sceneDurationFrames - 1) ?: target.startFrame
                    val newDur = opDto.durationSec?.let { (it * fps).roundToInt() }
                        ?.coerceIn(4, sceneDurationFrames) ?: target.durationFrames
                    clips[idx] = target.copy(
                        startFrame = newStart,
                        durationFrames = newDur,
                        audioTrimStartMs = opDto.audioTrimStartMs ?: target.audioTrimStartMs,
                        audioTrimEndMs = opDto.audioTrimEndMs ?: target.audioTrimEndMs
                    )
                }
            }
        }
    }

    /**
     * Compiles dialogue-driven cinematic camera shots (`focusTargetCharacterId` + [CameraShotType])
     * into smooth [CameraKeyframe]s that automatically frame & track speaking/walking characters
     * and cut to reaction shots.
     */
    private fun compileCinematicCameraShots(
        shots: List<ScriptCameraShotDto>,
        clips: List<TimelineClip>,
        fps: Int,
        sceneDurationFrames: Int
    ): Pair<List<CameraKeyframe>, Float> {
        if (shots.isEmpty()) return emptyList<CameraKeyframe>() to 0f

        val byFrame = LinkedHashMap<Int, CameraKeyframe>()
        var peakCameraShake = 0f

        fun findClipForTarget(charId: String?, clipId: String?): TimelineClip? {
            if (!clipId.isNullOrBlank()) {
                clips.firstOrNull { it.id == clipId }?.let { return it }
            }
            if (!charId.isNullOrBlank()) {
                clips.firstOrNull { it.characterId == charId || it.inlineSvgAssetId == charId }?.let { return it }
            }
            return null
        }

        val sortedShots = shots.sortedBy { shot ->
            shot.frame ?: shot.tSec?.let { (it * fps).roundToInt() } ?: 0
        }

        for (i in sortedShots.indices) {
            val shot = sortedShots[i]
            val startFrame = (shot.frame ?: shot.tSec?.let { (it * fps).roundToInt() } ?: 0)
                .coerceIn(0, (sceneDurationFrames - 1).coerceAtLeast(0))
            val shotType = parseEnumIgnoreCase<CameraShotType>(shot.shotType) ?: CameraShotType.WIDE_ESTABLISHING
            val primaryClip = findClipForTarget(shot.focusTargetCharacterId, shot.focusTargetClipId)
            val secondaryClip = findClipForTarget(shot.secondaryTargetCharacterId, null)

            val bez = shot.cubicBezier
            val easing = when {
                bez != null && bez.size >= 4 -> EasingType.CUSTOM_CUBIC
                shotType == CameraShotType.WHIP_PAN -> EasingType.EASE_OUT
                shotType == CameraShotType.SLOW_PUSH_IN -> EasingType.EASE_IN_OUT
                else -> parseEnumIgnoreCase<EasingType>(shot.easing) ?: EasingType.EASE_IN_OUT
            }

            fun resolveFrameXY(atFrame: Int): Pair<Float, Float> {
                if (shot.x != null && shot.y != null) return shot.x to shot.y
                if (shotType == CameraShotType.WIDE_ESTABLISHING && primaryClip == null) {
                    return 0.5f to 0.5f
                }
                val pEval = primaryClip?.let { KeyframeEvaluator.evaluate(it, atFrame) }
                val sEval = secondaryClip?.let { KeyframeEvaluator.evaluate(it, atFrame) }
                return when {
                    shotType == CameraShotType.MEDIUM_TWO_SHOT && pEval != null && sEval != null -> {
                        val mx = (pEval.x + sEval.x) * 0.5f
                        val my = ((pEval.y + sEval.y) * 0.5f + shotType.focusHeadBiasY).coerceIn(0.15f, 0.85f)
                        mx to my
                    }
                    shotType == CameraShotType.OVER_THE_SHOULDER && pEval != null -> {
                        val sideBias = if (sEval != null && sEval.x < pEval.x) -0.06f else 0.06f
                        (pEval.x + sideBias).coerceIn(0.12f, 0.88f) to
                            (pEval.y + shotType.focusHeadBiasY).coerceIn(0.15f, 0.85f)
                    }
                    pEval != null -> {
                        pEval.x.coerceIn(0.10f, 0.90f) to
                            (pEval.y + shotType.focusHeadBiasY).coerceIn(0.14f, 0.86f)
                    }
                    else -> (shot.x ?: 0.5f) to (shot.y ?: 0.5f)
                }
            }

            val (fx, fy) = resolveFrameXY(startFrame)
            val baseZoom = (shot.zoom ?: shotType.defaultZoom).coerceIn(0.4f, 3.2f)
            val baseRot = shot.rotation ?: shotType.defaultRollDeg

            if (shotType == CameraShotType.CAMERA_SHAKE) {
                peakCameraShake = max(peakCameraShake, shot.shakeIntensity ?: 0.45f)
            }

            byFrame[startFrame] = CameraKeyframe(
                frame = startFrame,
                x = fx,
                y = fy,
                zoom = baseZoom,
                rotation = baseRot,
                easing = easing,
                easingP1X = bez?.getOrNull(0) ?: 0.42f,
                easingP1Y = bez?.getOrNull(1) ?: 0f,
                easingP2X = bez?.getOrNull(2) ?: 0.58f,
                easingP2Y = bez?.getOrNull(3) ?: 1f
            )

            // Determine end of this shot (next shot start or explicit durationSec)
            val nextShotFrame = sortedShots.getOrNull(i + 1)?.let {
                it.frame ?: it.tSec?.let { s -> (s * fps).roundToInt() }
            }
            val endFrame = (shot.durationSec?.let { startFrame + (it * fps).roundToInt() }
                ?: nextShotFrame?.let { (it - 2).coerceAtLeast(startFrame) }
                ?: (sceneDurationFrames - 1)).coerceIn(startFrame, (sceneDurationFrames - 1).coerceAtLeast(0))

            // Add tracking / slow push-in end keyframe if target moves or shot pushes in
            if (endFrame > startFrame + 3) {
                val (endX, endY) = resolveFrameXY(endFrame)
                val endZoom = when (shotType) {
                    CameraShotType.SLOW_PUSH_IN -> (baseZoom * 1.28f).coerceAtMost(2.8f)
                    else -> baseZoom
                }
                val movedSignificantly = abs(endX - fx) > 0.015f || abs(endY - fy) > 0.015f || abs(endZoom - baseZoom) > 0.03f
                if (movedSignificantly) {
                    byFrame[endFrame] = CameraKeyframe(
                        frame = endFrame,
                        x = endX,
                        y = endY,
                        zoom = endZoom,
                        rotation = baseRot,
                        easing = EasingType.EASE_IN_OUT
                    )
                }
            }
        }

        return byFrame.values.sortedBy { it.frame } to peakCameraShake
    }

    private inline fun <reified T : Enum<T>> parseEnumIgnoreCase(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        val norm = raw.trim().replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name.equals(norm, ignoreCase = true) }
    }

    /**
     * Built-in 100% offline demo `.fcscript.json` showcasing inline SVG spawning (swaying tree),
     * a custom animation, a custom iris+flash transition, dialogue auto-focus camera shots,
     * curved motion path, VFX, and colour grading.
     */
    fun sampleDemoDirectorScriptJson(
        project: ProjectDocument,
        characters: List<CharacterRigDefinition>
    ): String {
        val heroId = characters.firstOrNull()?.id ?: "hero_1"
        val buddyId = characters.getOrNull(1)?.id ?: heroId
        return """
{
  "format": "framecraft.director_script",
  "schemaVersion": 3,
  "ratio": "${project.aspectRatioPreset.label}",
  "fps": ${project.fps},
  "inlineSvgAssets": [
    {
      "id": "svg_breezy_tree",
      "name": "Breezy Pine Tree",
      "type": "TREE",
      "viewBox": "0 0 256 256",
      "svgXml": "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 256'><g id='trunk' data-pivot='128,236'><rect x='114' y='140' width='28' height='104' rx='8' fill='#7C4A27'/></g><g id='tree_canopy' data-pivot='128,144'><circle cx='128' cy='94' r='62' fill='#1F7A52'/><circle cx='86' cy='116' r='42' fill='#2DA36B'/><circle cx='170' cy='116' r='42' fill='#186342'/></g></svg>"
    }
  ],
  "customAnimations": {
    "gentle_tree_breeze": {
      "durationSec": 2.4,
      "loopMode": "PING_PONG",
      "tracks": [
        {
          "targetNodeId": "tree_canopy",
          "keyframes": [
            { "t": 0.0, "rotation": -5.0, "scaleX": 1.0, "scaleY": 1.0, "cubicBezier": [0.42, 0.0, 0.58, 1.0] },
            { "t": 1.0, "rotation": 6.0, "scaleX": 1.03, "scaleY": 0.98, "cubicBezier": [0.42, 0.0, 0.58, 1.0] }
          ]
        }
      ]
    },
    "excited_wave_bounce": {
      "durationSec": 1.2,
      "loopMode": "PING_PONG",
      "tracks": [
        {
          "targetNodeId": "RIGHT_ARM",
          "keyframes": [
            { "t": 0.0, "rotation": -18.0, "cubicBezier": [0.34, 1.4, 0.64, 1.0] },
            { "t": 1.0, "rotation": 26.0, "cubicBezier": [0.34, 1.4, 0.64, 1.0] }
          ]
        },
        {
          "targetNodeId": "HEAD",
          "keyframes": [
            { "t": 0.0, "rotation": -6.0 },
            { "t": 1.0, "rotation": 8.0 }
          ]
        }
      ]
    }
  },
  "customTransitions": {
    "golden_iris_reveal": {
      "durationSec": 0.65,
      "clipPathType": "IRIS_CIRCLE",
      "angleDeg": 0.0,
      "outgoing": { "dx": 0.0, "dy": 0.0, "scale": 1.06, "rotation": 0.0, "alpha": 0.2 },
      "incoming": { "dx": 0.0, "dy": 0.0, "scale": 1.0, "rotation": 0.0, "alpha": 1.0 },
      "flashOverlay": { "colorHex": "#FDE68A", "peakAlpha": 0.35 },
      "cubicBezier": [0.22, 1.0, 0.36, 1.0]
    }
  },
  "scenes": [
    {
      "name": "Golden Grove Dialogue",
      "durationSec": 5.0,
      "backgroundColorHex": "#14263B",
      "colourGrading": "GOLDEN_HOUR",
      "vfx": { "particleType": "FIREFLIES", "intensity": 0.7, "speed": 1.0, "vignette": 0.25 },
      "customTransitionInId": "golden_iris_reveal",
      "layers": [
        { "id": "layer_tree", "name": "Breezy Tree", "type": "IMAGE", "zIndex": 0, "blendMode": "NORMAL" },
        { "id": "layer_hero", "name": "Speaker", "type": "CHARACTER", "zIndex": 1, "blendMode": "NORMAL" },
        { "id": "layer_buddy", "name": "Listener", "type": "CHARACTER", "zIndex": 2, "blendMode": "NORMAL" },
        { "id": "layer_caption", "name": "Speech Bubble", "type": "TEXT", "zIndex": 3, "blendMode": "NORMAL" }
      ],
      "clips": [
        {
          "id": "clip_tree",
          "layerId": "layer_tree",
          "name": "Breezy Pine Tree",
          "inlineSvgAssetId": "svg_breezy_tree",
          "customAnimationId": "gentle_tree_breeze",
          "startSec": 0.0,
          "durationSec": 5.0,
          "keyframes": [
            { "tSec": 0.0, "x": 0.18, "y": 0.62, "scaleX": 1.35, "scaleY": 1.35 }
          ]
        },
        {
          "id": "clip_hero",
          "layerId": "layer_hero",
          "name": "Speaker",
          "characterId": "$heroId",
          "startSec": 0.0,
          "durationSec": 5.0,
          "animationPreset": "TALKING",
          "customAnimationId": "excited_wave_bounce",
          "facialExpression": "HAPPY",
          "motionPath": [
            { "x": 0.28, "y": 0.82 },
            { "x": 0.36, "y": 0.78 },
            { "x": 0.40, "y": 0.82 }
          ],
          "keyframes": [
            { "tSec": 0.0, "x": 0.28, "y": 0.82, "scaleX": 0.95, "scaleY": 0.95, "easing": "EASE_IN_OUT" },
            { "tSec": 2.2, "x": 0.40, "y": 0.82, "scaleX": 0.98, "scaleY": 0.98, "easing": "EASE_OUT" }
          ]
        },
        {
          "id": "clip_buddy",
          "layerId": "layer_buddy",
          "name": "Listener",
          "characterId": "$buddyId",
          "startSec": 0.0,
          "durationSec": 5.0,
          "animationPreset": "IDLE_HAPPY",
          "facialExpression": "LAUGHING",
          "flipX": true,
          "keyframes": [
            { "tSec": 0.0, "x": 0.72, "y": 0.82, "scaleX": 0.95, "scaleY": 0.95 }
          ]
        },
        {
          "id": "clip_speech",
          "layerId": "layer_caption",
          "name": "Dialogue",
          "startSec": 1.0,
          "durationSec": 3.0,
          "textContent": "The forest is alive tonight!",
          "textColorHex": "#0F172A",
          "textBubbleStyle": "SPEECH",
          "keyframes": [
            { "tSec": 0.0, "x": 0.42, "y": 0.34, "scaleX": 0.92, "scaleY": 0.92 }
          ]
        }
      ],
      "cameraShots": [
        { "tSec": 0.0, "shotType": "WIDE_ESTABLISHING" },
        { "tSec": 1.2, "shotType": "CLOSE_UP_SPEAKER", "focusTargetClipId": "clip_hero" },
        { "tSec": 3.4, "shotType": "EXTREME_CLOSE_UP_REACTION", "focusTargetClipId": "clip_buddy" }
      ]
    }
  ]
}
        """.trimIndent()
    }
}
