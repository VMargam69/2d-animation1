package com.example.engine

import com.example.model.*

/**
 * Dynamic, zero-hardcoding runtime capability introspection registry.
 *
 * Reads every live FrameCraft enum, preset catalog, VFX engine, colour grading list,
 * mask shape, easing curve, transition, IK handle, camera shot type, blend mode, and
 * aspect ratio directly at runtime. Any enum entry or preset added in future releases
 * automatically appears in exported `.fcblueprint.json` files without touching this registry.
 */
object StudioCapabilityRegistry {

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
        val clipOperations: List<ClipOperationCapability>
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

        val clipOperations = listOf(
            ClipOperationCapability("split", "Split a clip cleanly at a frame or timestamp into two clips", listOf("atSec", "atFrame")),
            ClipOperationCapability("flipX", "Mirror clip horizontally", listOf("enabled")),
            ClipOperationCapability("flipY", "Mirror clip vertically", listOf("enabled")),
            ClipOperationCapability("duplicate", "Clone clip onto next layer or shifted time", listOf("offsetSec", "newClipId")),
            ClipOperationCapability("delete", "Remove a referenced clip from the scene", listOf("targetClipId")),
            ClipOperationCapability("speed", "Adjust playback speed multiplier (0.25..4.0)", listOf("multiplier")),
            ClipOperationCapability("trim", "Adjust start/end timing or audio trim window", listOf("startSec", "durationSec", "audioTrimStartMs", "audioTrimEndMs"))
        )

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
            clipOperations = clipOperations
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
            snapshot.curveAndEasingTypes.size +
            snapshot.transitions.size +
            snapshot.customTransitionClipTypes.size +
            snapshot.layerBlendModes.size +
            snapshot.aspectRatios.size +
            snapshot.builtInProps.size +
            snapshot.clipOperations.size
}
