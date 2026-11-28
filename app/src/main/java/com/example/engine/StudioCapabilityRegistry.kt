package com.example.engine

import com.example.model.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dynamic, zero-hardcoding runtime capability introspection registry.
 *
 * Reads every live FrameCraft enum, preset catalog, VFX engine, colour grading list,
 * mask shape, easing curve, transition, IK handle, camera shot type, blend mode,
 * saved IK poses, modular actions, and aspect ratio directly at runtime.
 */
object StudioCapabilityRegistry {

    private val registeredSavedPoses = CopyOnWriteArrayList<SavedPose>()

    /** Updates the live registered custom IK poses so `.fcblueprint.json` exports include them automatically. */
    fun registerSavedPoses(poses: List<SavedPose>) {
        registeredSavedPoses.clear()
        registeredSavedPoses.addAll(poses)
    }

    /** Returns the currently registered custom saved IK poses. */
    fun currentSavedPoses(): List<SavedPose> = registeredSavedPoses.toList()

    /** Finds a registered saved pose by exact ID or case-insensitive name. */
    fun findSavedPose(idOrName: String?): SavedPose? {
        if (idOrName.isNullOrBlank()) return null
        val q = idOrName.trim()
        return registeredSavedPoses.firstOrNull { it.id == q }
            ?: registeredSavedPoses.firstOrNull { it.name.equals(q, ignoreCase = true) }
    }

    data class CapabilityItem(
        val id: String,
        val label: String,
        val meta: Map<String, Any>? = null
    )

    data class ClipOperationCapability(
        val id: String,
        val description: String,
        val params: List<String>
    )

    data class StudioCapabilitiesSnapshot(
        val animationPresets: List<CapabilityItem>,
        val facialExpressions: List<CapabilityItem>,
        val facialExpressionPresets: List<CapabilityItem>,
        val mouthShapeOverrides: List<CapabilityItem>,
        val eyeShapeOverrides: List<CapabilityItem>,
        val eyebrowShapeOverrides: List<CapabilityItem>,
        val handPoses: List<CapabilityItem>,
        val orientations: List<CapabilityItem>,
        val ikJointIds: List<CapabilityItem>,
        val cameraShotTypes: List<CapabilityItem>,
        val cameraPresets: List<CapabilityItem>,
        val vfxStyles: List<CapabilityItem>,
        val colourGradingPresets: List<CapabilityItem>,
        val maskTypes: List<CapabilityItem>,
        val tearsFxStyles: List<CapabilityItem>,
        val shadowModes: List<CapabilityItem>,
        val curveAndEasingTypes: List<CapabilityItem>,
        val transitions: List<CapabilityItem>,
        val customTransitionClipTypes: List<CapabilityItem>,
        val customAnimationLoopModes: List<CapabilityItem>,
        val inlineSvgAssetTypes: List<CapabilityItem>,
        val layerBlendModes: List<CapabilityItem>,
        val layerTypes: List<CapabilityItem>,
        val aspectRatios: List<CapabilityItem>,
        val builtInProps: List<CapabilityItem>,
        val textBubbleStyles: List<CapabilityItem>,
        val brushStyles: List<CapabilityItem>,
        val shapeKinds: List<CapabilityItem>,
        val modularActions: List<CapabilityItem>,
        val speakingModes: List<CapabilityItem>,
        val eyeblinkModes: List<CapabilityItem>,
        val savedPoses: List<CapabilityItem>,
        val clipOperations: List<ClipOperationCapability>,
        val armGesturePresets: List<CapabilityItem>,
        val simultaneousTrackTypes: List<CapabilityItem>,
        val ikJointLimits: Map<String, List<Float>>,
        val propSwayDefaults: PropSwayConfig,
        val parallaxDepthPresets: List<CapabilityItem>,
        val weatherParticleStyles: List<CapabilityItem>,
        val dialogueOverlayLanguages: List<CapabilityItem>,
        val audioTrackRoles: List<CapabilityItem>,
        val audioDuckingDefaults: AudioDuckingConfig,
        val universalCurveTypes: List<CapabilityItem>,
        val universalLoopModes: List<CapabilityItem>,
        val universalPropertyDomains: List<CapabilityItem>,
        val universalKeyframeChannels: List<CapabilityItem>
    )

    /**
     * Dynamically collects all active studio capabilities directly from live enums and catalogs.
     */
    fun introspect(): StudioCapabilitiesSnapshot {
        val animationPresets = AnimationPresetType.entries.map { preset ->
            CapabilityItem(
                id = preset.name,
                label = preset.displayName,
                meta = mapOf(
                    "tag" to preset.tag,
                    "defaultDurationFrames" to preset.defaultDurationFrames
                )
            )
        }

        val facialExpressions = FacialExpression.entries.map { expr ->
            CapabilityItem(id = expr.name, label = expr.displayName)
        }

        val facialExpressionPresets = FacialExpressionEngine.presets.map { preset ->
            CapabilityItem(
                id = preset.id,
                label = preset.name,
                meta = mapOf("category" to preset.category)
            )
        }

        val mouthShapeOverrides = MouthShapeOverride.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val eyeShapeOverrides = EyeShapeOverride.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val eyebrowShapeOverrides = EyebrowShapeOverride.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val handPoses = HandPoseType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val orientations = CharacterOrientation.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val ikJointIds = IkHandleId.entries.map {
            CapabilityItem(id = it.name, label = it.label)
        }

        val cameraShotTypes = CameraShotType.entries.map { shot ->
            CapabilityItem(
                id = shot.name,
                label = shot.displayName,
                meta = mapOf(
                    "defaultZoom" to shot.defaultZoom,
                    "defaultRollDeg" to shot.defaultRollDeg
                )
            )
        }

        val cameraPresets = CameraPresetEngine.presets.map { preset ->
            CapabilityItem(
                id = preset.id,
                label = preset.name,
                meta = mapOf("category" to preset.category.displayName)
            )
        }

        val vfxStyles = SceneParticleType.entries.map { particle ->
            CapabilityItem(
                id = particle.name,
                label = particle.displayName,
                meta = mapOf("accentHex" to particle.accentHex)
            )
        }

        val colourGradingPresets = SceneLightingMood.entries.map { mood ->
            CapabilityItem(
                id = mood.name,
                label = mood.displayName,
                meta = mapOf("swatchHex" to mood.swatchHex)
            )
        }

        val maskTypes = ClipMaskType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val tearsFxStyles = TearsFxStyle.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val shadowModes = ShadowMode.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val curveAndEasingTypes = EasingType.entries.map { easing ->
            val handles = KeyframeEvaluator.defaultBezierHandles(easing)
            CapabilityItem(
                id = easing.name,
                label = easing.displayName,
                meta = mapOf(
                    "defaultBezier" to listOf(handles[0], handles[1], handles[2], handles[3])
                )
            )
        }

        val transitions = TransitionType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val customTransitionClipTypes = CustomTransitionClipType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val customAnimationLoopModes = CustomAnimLoopMode.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val inlineSvgAssetTypes = InlineSvgAssetType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val layerBlendModes = LayerBlendMode.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val layerTypes = LayerType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val aspectRatios = AspectRatioPreset.entries.map { ratio ->
            CapabilityItem(
                id = ratio.label,
                label = ratio.label,
                meta = mapOf(
                    "enumName" to ratio.name,
                    "width" to ratio.defaultWidth,
                    "height" to ratio.defaultHeight
                )
            )
        }

        val builtInProps = CharacterPropType.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val textBubbleStyles = TextBubbleStyle.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val brushStyles = BrushStyle.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val shapeKinds = ShapeKind.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val modularActions = ModularActionEngine.catalog.map { act ->
            CapabilityItem(
                id = act.id,
                label = act.name,
                meta = mapOf(
                    "track" to act.track.name,
                    "durationFrames" to act.durationFrames
                )
            )
        }

        val speakingModes = CharacterSpeakingMode.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val eyeblinkModes = CharacterEyeblinkMode.entries.map {
            CapabilityItem(id = it.name, label = it.displayName)
        }

        val savedPoses = registeredSavedPoses.map { pose ->
            CapabilityItem(
                id = pose.id,
                label = pose.name,
                meta = mapOf(
                    "category" to pose.category.name,
                    "expression" to pose.expression.name,
                    "handPose" to pose.handPose.name
                )
            )
        }

        val clipOperations = listOf(
            ClipOperationCapability("split", "Split a clip cleanly at a frame or timestamp into two clips", listOf("atSec", "atFrame")),
            ClipOperationCapability("flipX", "Mirror clip horizontally", listOf("enabled")),
            ClipOperationCapability("flipY", "Mirror clip vertically", listOf("enabled")),
            ClipOperationCapability("duplicate", "Clone clip onto next layer or shifted time", listOf("offsetSec", "newClipId")),
            ClipOperationCapability("delete", "Remove a referenced clip from the scene", listOf("targetClipId")),
            ClipOperationCapability("speed", "Adjust playback speed multiplier (0.25..4.0)", listOf("multiplier")),
            ClipOperationCapability("trim", "Adjust start/end timing or audio trim window", listOf("startSec", "durationSec", "audioTrimStartMs", "audioTrimEndMs")),
            ClipOperationCapability("replace", "Swap character rig or image/background asset while preserving all keyframes, IK poses, masks, shadows, tears, and sway physics", listOf("newCharacterId", "newImageAssetId")),
            ClipOperationCapability("tears", "Configure deterministic animated Tears FX anchored beneath character eyes", listOf("style", "intensity", "speed")),
            ClipOperationCapability("shadow", "Configure dynamic ground ellipse or drop shadow (auto-scales on jumps)", listOf("enabled", "mode", "opacity", "softness", "scaleX", "scaleY", "offsetX", "offsetY", "colorHex")),
            ClipOperationCapability("adjust", "Configure per-clip ColorMatrix colour grading (brightness, contrast, saturation, hueDeg, warmth)", listOf("brightness", "contrast", "saturation", "hueDeg", "warmth")),
            ClipOperationCapability("mask", "Configure interactive clip mask shape with position, width/height, feather, and invert", listOf("type", "centerX", "centerY", "width", "height", "feather", "invert")),
            ClipOperationCapability("duplicateLayer", "Deep-copy an entire layer and all of its clips directly above the original layer", listOf("targetLayerId", "newLayerId")),
            ClipOperationCapability("sort", "Change layer Z-order (BRING_TO_FRONT, BRING_FORWARD, SEND_BACKWARD, SEND_TO_BACK)", listOf("targetLayerId", "zOrderAction"))
        )

        val armGesturePresets = ArmGestureTrackPreset.entries.map { gesture ->
            CapabilityItem(
                id = gesture.name,
                label = gesture.displayName,
                meta = mapOf("defaultHandPose" to gesture.defaultHandPose.name)
            )
        }

        val simultaneousTrackTypes = SimultaneousTrackType.entries.map { track ->
            CapabilityItem(id = track.name, label = track.displayName)
        }

        val ikJointLimits = IkRigSolver.AnatomicalJointLimits.asMap().mapValues { (_, range) ->
            listOf(range.first, range.second)
        }

        val propSwayDefaults = PropSwayPhysics.DEFAULT_CONFIG

        val parallaxDepthPresets = ParallaxDepthPreset.entries.map { preset ->
            CapabilityItem(
                id = preset.name,
                label = preset.displayName,
                meta = mapOf("depth" to preset.depth)
            )
        }

        val weatherParticleStyles = WeatherParticleStyle.entries.map { style ->
            CapabilityItem(
                id = style.name,
                label = style.displayName,
                meta = mapOf("accentHex" to style.accentHex)
            )
        }

        val dialogueOverlayLanguages = DialogueLanguage.entries.map { lang ->
            CapabilityItem(
                id = lang.code,
                label = lang.displayName,
                meta = mapOf("enumName" to lang.name, "badge" to lang.badge)
            )
        }

        val audioTrackRoles = AudioTrackRole.entries.map { role ->
            CapabilityItem(id = role.name, label = role.displayName)
        }

        val audioDuckingDefaults = AudioDuckingConfig()

        val universalCurveTypes = UniversalCurveType.entries.map { curve ->
            CapabilityItem(id = curve.name, label = curve.displayName)
        }

        val universalLoopModes = UniversalLoopMode.entries.map { mode ->
            CapabilityItem(id = mode.name, label = mode.displayName)
        }

        val universalPropertyDomains = UniversalPropertyDomain.entries.map { domain ->
            CapabilityItem(id = domain.name, label = domain.displayName)
        }

        val universalKeyframeChannels = UniversalKeyframeEngine.canonicalPropertyChannels.map { ch ->
            CapabilityItem(
                id = ch.propertyPath,
                label = ch.description,
                meta = buildMap {
                    put("domain", ch.domain.name)
                    put("valueType", ch.valueType.name)
                    if (!ch.rangeHint.isNullOrBlank()) put("range", ch.rangeHint)
                }
            )
        }

        return StudioCapabilitiesSnapshot(
            animationPresets = animationPresets,
            facialExpressions = facialExpressions,
            facialExpressionPresets = facialExpressionPresets,
            mouthShapeOverrides = mouthShapeOverrides,
            eyeShapeOverrides = eyeShapeOverrides,
            eyebrowShapeOverrides = eyebrowShapeOverrides,
            handPoses = handPoses,
            orientations = orientations,
            ikJointIds = ikJointIds,
            cameraShotTypes = cameraShotTypes,
            cameraPresets = cameraPresets,
            vfxStyles = vfxStyles,
            colourGradingPresets = colourGradingPresets,
            maskTypes = maskTypes,
            tearsFxStyles = tearsFxStyles,
            shadowModes = shadowModes,
            curveAndEasingTypes = curveAndEasingTypes,
            transitions = transitions,
            customTransitionClipTypes = customTransitionClipTypes,
            customAnimationLoopModes = customAnimationLoopModes,
            inlineSvgAssetTypes = inlineSvgAssetTypes,
            layerBlendModes = layerBlendModes,
            layerTypes = layerTypes,
            aspectRatios = aspectRatios,
            builtInProps = builtInProps,
            textBubbleStyles = textBubbleStyles,
            brushStyles = brushStyles,
            shapeKinds = shapeKinds,
            modularActions = modularActions,
            speakingModes = speakingModes,
            eyeblinkModes = eyeblinkModes,
            savedPoses = savedPoses,
            clipOperations = clipOperations,
            armGesturePresets = armGesturePresets,
            simultaneousTrackTypes = simultaneousTrackTypes,
            ikJointLimits = ikJointLimits,
            propSwayDefaults = propSwayDefaults,
            parallaxDepthPresets = parallaxDepthPresets,
            weatherParticleStyles = weatherParticleStyles,
            dialogueOverlayLanguages = dialogueOverlayLanguages,
            audioTrackRoles = audioTrackRoles,
            audioDuckingDefaults = audioDuckingDefaults,
            universalCurveTypes = universalCurveTypes,
            universalLoopModes = universalLoopModes,
            universalPropertyDomains = universalPropertyDomains,
            universalKeyframeChannels = universalKeyframeChannels
        )
    }

    /** Resolves an aspect ratio label ("16:9", "9:16", "1:1", "4:5", "4:3") or enum name dynamically. */
    fun resolveAspectRatio(value: String?): AspectRatioPreset? {
        if (value.isNullOrBlank()) return null
        val norm = value.trim()
        return AspectRatioPreset.entries.firstOrNull {
            it.label.equals(norm, ignoreCase = true) ||
                it.name.equals(norm, ignoreCase = true) ||
                it.name.equals("RATIO_${norm.replace(':', '_')}", ignoreCase = true)
        }
    }

    /** Total count of dynamically discovered capability entries across all categories. */
    fun totalCapabilityCount(snapshot: StudioCapabilitiesSnapshot = introspect()): Int =
        snapshot.animationPresets.size +
            snapshot.facialExpressions.size +
            snapshot.facialExpressionPresets.size +
            snapshot.mouthShapeOverrides.size +
            snapshot.eyeShapeOverrides.size +
            snapshot.eyebrowShapeOverrides.size +
            snapshot.handPoses.size +
            snapshot.orientations.size +
            snapshot.ikJointIds.size +
            snapshot.cameraShotTypes.size +
            snapshot.cameraPresets.size +
            snapshot.vfxStyles.size +
            snapshot.colourGradingPresets.size +
            snapshot.maskTypes.size +
            snapshot.tearsFxStyles.size +
            snapshot.shadowModes.size +
            snapshot.curveAndEasingTypes.size +
            snapshot.transitions.size +
            snapshot.customTransitionClipTypes.size +
            snapshot.layerBlendModes.size +
            snapshot.aspectRatios.size +
            snapshot.builtInProps.size +
            snapshot.modularActions.size +
            snapshot.speakingModes.size +
            snapshot.eyeblinkModes.size +
            snapshot.savedPoses.size +
            snapshot.clipOperations.size +
            snapshot.armGesturePresets.size +
            snapshot.simultaneousTrackTypes.size +
            snapshot.ikJointLimits.size +
            snapshot.parallaxDepthPresets.size +
            snapshot.weatherParticleStyles.size +
            snapshot.dialogueOverlayLanguages.size +
            snapshot.audioTrackRoles.size +
            snapshot.universalCurveTypes.size +
            snapshot.universalLoopModes.size +
            snapshot.universalKeyframeChannels.size
}
