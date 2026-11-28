package com.example.viewmodel

import com.example.engine.CameraPresetEngine
import com.example.engine.FacialExpressionEngine
import com.example.engine.KeyframeEvaluator
import com.example.model.*
import java.util.UUID

/**
 * Pure immutable mutations on ProjectDocument so every editor action is deterministic,
 * undoable, and directly unit-testable.
 */
object ProjectMutator {

    fun updateActiveScene(
        project: ProjectDocument,
        transform: (AnimationScene) -> AnimationScene
    ): ProjectDocument {
        val idx = project.activeSceneIndex.coerceIn(0, (project.scenes.size - 1).coerceAtLeast(0))
        val updatedScenes = project.scenes.toMutableList()
        if (updatedScenes.isEmpty()) {
            updatedScenes.add(transform(AnimationScene()))
        } else {
            val currentScene = updatedScenes[idx]
            val nextScene = transform(currentScene)
            if (nextScene == currentScene) return project
            updatedScenes[idx] = nextScene
        }
        return project.copy(scenes = updatedScenes, updatedAt = System.currentTimeMillis())
    }

    fun addCharacterClip(
        project: ProjectDocument,
        character: CharacterRigDefinition,
        orientation: CharacterOrientation,
        preset: AnimationPresetType,
        startFrame: Int,
        durationFrames: Int,
        speedMultiplier: Float,
        loopAnimation: Boolean
    ): Pair<ProjectDocument, String> {
        val clipId = UUID.randomUUID().toString()
        val updated = updateActiveScene(project) { scene ->
            val existingLayer = scene.layers.firstOrNull {
                it.type == LayerType.CHARACTER && !it.isLocked &&
                    scene.clips.none { c -> c.layerId == it.id }
            }
            val targetLayer = existingLayer ?: AnimationLayer(
                name = character.name,
                type = LayerType.CHARACTER,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val newLayers = if (existingLayer == null) scene.layers + targetLayer else scene.layers
            val safeDuration = durationFrames.coerceAtLeast(12)
            val neededSceneLen = maxOf(scene.durationFrames, startFrame + safeDuration)

            // Offset X slightly if multiple characters already exist so they don't stack invisibly
            val charCount = scene.clips.count { it.characterId != null }
            val startX = (0.5f + ((charCount % 3) - 1) * 0.18f).coerceIn(0.2f, 0.8f)

            val clip = TimelineClip(
                id = clipId,
                layerId = targetLayer.id,
                name = "${character.name} • ${preset.displayName}",
                startFrame = startFrame,
                durationFrames = safeDuration,
                characterId = character.id,
                orientation = orientation,
                animationPresetType = preset,
                speedMultiplier = speedMultiplier,
                loopAnimation = loopAnimation,
                keyframes = listOf(
                    TransformKeyframe(frame = 0, x = startX, y = 0.65f, scaleX = 1f, scaleY = 1f, opacity = 1f)
                )
            )
            scene.copy(
                durationFrames = neededSceneLen,
                layers = newLayers,
                clips = scene.clips + clip
            )
        }
        return updated to clipId
    }

    fun addImageOrMediaClip(
        project: ProjectDocument,
        name: String,
        imagePath: String?,
        bundledKey: String?,
        startFrame: Int,
        asBackground: Boolean
    ): Pair<ProjectDocument, String> {
        if (asBackground) {
            val updated = updateActiveScene(project) { scene ->
                scene.copy(
                    backgroundBundledKey = bundledKey,
                    backgroundImagePath = imagePath
                )
            }
            return updated to ""
        }
        val clipId = UUID.randomUUID().toString()
        val updated = updateActiveScene(project) { scene ->
            val layer = AnimationLayer(
                name = name.take(18),
                type = LayerType.IMAGE,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val dur = (scene.durationFrames - startFrame).coerceAtLeast(48)
            val clip = TimelineClip(
                id = clipId,
                layerId = layer.id,
                name = name,
                startFrame = startFrame,
                durationFrames = dur,
                imagePath = imagePath,
                bundledBgKey = bundledKey,
                keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f))
            )
            scene.copy(
                durationFrames = maxOf(scene.durationFrames, startFrame + dur),
                layers = scene.layers + layer,
                clips = scene.clips + clip
            )
        }
        return updated to clipId
    }

    fun addAudioClip(
        project: ProjectDocument,
        name: String,
        audioPath: String,
        startFrame: Int
    ): Pair<ProjectDocument, String> {
        val clipId = UUID.randomUUID().toString()
        val updated = updateActiveScene(project) { scene ->
            val layer = scene.layers.firstOrNull { it.type == LayerType.AUDIO && !it.isLocked }
                ?: AnimationLayer(
                    name = "Audio Track",
                    type = LayerType.AUDIO,
                    orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
                )
            val newLayers = if (scene.layers.any { it.id == layer.id }) scene.layers else scene.layers + layer
            val dur = (scene.durationFrames - startFrame).coerceAtLeast(48)
            val clip = TimelineClip(
                id = clipId,
                layerId = layer.id,
                name = name,
                startFrame = startFrame,
                durationFrames = dur,
                audioPath = audioPath,
                audioLabel = name,
                keyframes = listOf(TransformKeyframe(frame = 0))
            )
            scene.copy(layers = newLayers, clips = scene.clips + clip)
        }
        return updated to clipId
    }

    fun trimAudioClip(
        project: ProjectDocument,
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val updatedClips = scene.clips.map { clip ->
            if (clip.id != clipId) return@map clip
            val clampedStart = newStartFrame.coerceAtLeast(0)
            val clampedDuration = newDurationFrames.coerceAtLeast(6)
            val startDelta = clampedStart - clip.startFrame
            val safeTrimStart = newTrimStartMs.coerceAtLeast(0L)
            val safeTrimEnd = if (newTrimEndMs > 0L) newTrimEndMs.coerceAtLeast(safeTrimStart + 50L) else 0L

            val shiftedKeyframes = clip.keyframes
                .map { kf -> kf.copy(frame = (kf.frame - startDelta).coerceIn(0, clampedDuration - 1)) }
                .distinctBy { it.frame }
                .sortedBy { it.frame }
                .ifEmpty { listOf(TransformKeyframe(frame = 0)) }

            clip.copy(
                startFrame = clampedStart,
                durationFrames = clampedDuration,
                audioTrimStartMs = safeTrimStart,
                audioTrimEndMs = safeTrimEnd,
                keyframes = shiftedKeyframes
            )
        }
        val maxClipEnd = updatedClips.maxOfOrNull { it.endFrame } ?: scene.durationFrames
        scene.copy(
            durationFrames = maxOf(scene.durationFrames, maxClipEnd),
            clips = updatedClips
        )
    }

    fun resetAudioTrim(
        project: ProjectDocument,
        clipId: String,
        fullAudioDurationMs: Long = 0L
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val fps = project.fps.coerceAtLeast(1)
        val updatedClips = scene.clips.map { clip ->
            if (clip.id != clipId) return@map clip
            val safeSpeed = clip.speedMultiplier.coerceIn(0.1f, 4f)
            val trimStartFrames = ((clip.audioTrimStartMs * fps) / (1000f * safeSpeed)).toInt()
            val restoredStart = (clip.startFrame - trimStartFrames).coerceAtLeast(0)
            val targetDuration = if (fullAudioDurationMs > 0L) {
                ((fullAudioDurationMs * fps) / (1000f * safeSpeed)).toInt().coerceAtLeast(12)
            } else {
                (scene.durationFrames - restoredStart).coerceAtLeast(clip.durationFrames)
            }
            clip.copy(
                startFrame = restoredStart,
                durationFrames = targetDuration,
                audioTrimStartMs = 0L,
                audioTrimEndMs = 0L
            )
        }
        val maxClipEnd = updatedClips.maxOfOrNull { it.endFrame } ?: scene.durationFrames
        scene.copy(
            durationFrames = maxOf(scene.durationFrames, maxClipEnd),
            clips = updatedClips
        )
    }

    fun addShapeClip(
        project: ProjectDocument,
        kind: ShapeKind,
        fillHex: String,
        strokeHex: String,
        startFrame: Int
    ): Pair<ProjectDocument, String> {
        val clipId = UUID.randomUUID().toString()
        val updated = updateActiveScene(project) { scene ->
            val layer = AnimationLayer(
                name = kind.displayName,
                type = LayerType.SHAPE,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val dur = (scene.durationFrames - startFrame).coerceAtLeast(48)
            val clip = TimelineClip(
                id = clipId,
                layerId = layer.id,
                name = kind.displayName,
                startFrame = startFrame,
                durationFrames = dur,
                shapeKind = kind,
                shapeFillHex = fillHex,
                shapeStrokeHex = strokeHex,
                keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.48f))
            )
            scene.copy(layers = scene.layers + layer, clips = scene.clips + clip)
        }
        return updated to clipId
    }

    fun addTextClip(
        project: ProjectDocument,
        text: String,
        colorHex: String,
        sizeSp: Float,
        startFrame: Int,
        bubbleStyle: TextBubbleStyle = TextBubbleStyle.NONE,
        bubbleFillHex: String = "#FFFFFF"
    ): Pair<ProjectDocument, String> {
        val clipId = UUID.randomUUID().toString()
        val cleanText = text.trim().ifBlank { "Title" }
        val updated = updateActiveScene(project) { scene ->
            val layer = AnimationLayer(
                name = if (bubbleStyle == TextBubbleStyle.NONE) "Text: ${cleanText.take(12)}" else "${bubbleStyle.displayName}: ${cleanText.take(10)}",
                type = LayerType.TEXT,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val dur = (scene.durationFrames - startFrame).coerceAtLeast(48)
            val defaultY = if (bubbleStyle == TextBubbleStyle.SUBTITLE) 0.86f else 0.24f
            val clip = TimelineClip(
                id = clipId,
                layerId = layer.id,
                name = cleanText.take(20),
                startFrame = startFrame,
                durationFrames = dur,
                textContent = cleanText,
                textColorHex = colorHex,
                textSizeSp = sizeSp,
                textBubbleStyle = bubbleStyle,
                textBubbleFillHex = bubbleFillHex,
                keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = defaultY))
            )
            scene.copy(layers = scene.layers + layer, clips = scene.clips + clip)
        }
        return updated to clipId
    }

    fun addDrawingStroke(
        project: ProjectDocument,
        stroke: DrawingStroke,
        currentFrame: Int
    ): Pair<ProjectDocument, String> {
        var targetClipId = ""
        val updated = updateActiveScene(project) { scene ->
            val existingLayer = scene.layers.firstOrNull { it.type == LayerType.DRAWING && !it.isLocked }
            val layer = existingLayer ?: AnimationLayer(
                name = "Freehand Sketch",
                type = LayerType.DRAWING,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val newLayers = if (existingLayer == null) scene.layers + layer else scene.layers
            val existingClip = scene.clips.firstOrNull {
                it.layerId == layer.id && currentFrame in it.startFrame until it.endFrame
            }
            val newClips = if (existingClip != null) {
                targetClipId = existingClip.id
                scene.clips.map { c ->
                    if (c.id == existingClip.id) c.copy(drawingStrokes = c.drawingStrokes + stroke) else c
                }
            } else {
                val clip = TimelineClip(
                    layerId = layer.id,
                    name = "Sketch Layer",
                    startFrame = 0,
                    durationFrames = scene.durationFrames,
                    drawingStrokes = listOf(stroke),
                    keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.5f))
                )
                targetClipId = clip.id
                scene.clips + clip
            }
            scene.copy(layers = newLayers, clips = newClips)
        }
        return updated to targetClipId
    }

    fun undoLastDrawingStroke(
        project: ProjectDocument,
        currentFrame: Int
    ): Pair<ProjectDocument, Boolean> {
        var removed = false
        val updated = updateActiveScene(project) { scene ->
            val layer = scene.layers.firstOrNull { it.type == LayerType.DRAWING && !it.isLocked }
                ?: return@updateActiveScene scene
            val targetClip = scene.clips.firstOrNull {
                it.layerId == layer.id && currentFrame in it.startFrame until it.endFrame && it.drawingStrokes.isNotEmpty()
            } ?: scene.clips.lastOrNull { it.layerId == layer.id && it.drawingStrokes.isNotEmpty() }
                ?: return@updateActiveScene scene
            removed = true
            scene.copy(
                clips = scene.clips.map { c ->
                    if (c.id == targetClip.id) c.copy(drawingStrokes = c.drawingStrokes.dropLast(1)) else c
                }
            )
        }
        return updated to removed
    }

    fun clearDrawingStrokes(
        project: ProjectDocument,
        currentFrame: Int
    ): Pair<ProjectDocument, Boolean> {
        var cleared = false
        val updated = updateActiveScene(project) { scene ->
            val layer = scene.layers.firstOrNull { it.type == LayerType.DRAWING && !it.isLocked }
                ?: return@updateActiveScene scene
            val targetClip = scene.clips.firstOrNull {
                it.layerId == layer.id && currentFrame in it.startFrame until it.endFrame && it.drawingStrokes.isNotEmpty()
            } ?: scene.clips.lastOrNull { it.layerId == layer.id && it.drawingStrokes.isNotEmpty() }
                ?: return@updateActiveScene scene
            cleared = true
            scene.copy(
                clips = scene.clips.map { c ->
                    if (c.id == targetClip.id) c.copy(drawingStrokes = emptyList()) else c
                }
            )
        }
        return updated to cleared
    }

    fun upsertKeyframeAtFrame(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int,
        transformUpdater: (TransformKeyframe) -> TransformKeyframe
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return@updateActiveScene scene
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) return@updateActiveScene scene

        val localFrame = (globalFrame - clip.startFrame).coerceIn(0, (clip.durationFrames - 1).coerceAtLeast(0))
        val existing = clip.keyframes.firstOrNull { it.frame == localFrame }
        val base = existing ?: run {
            val eval = KeyframeEvaluator.evaluate(clip, globalFrame)
            TransformKeyframe(
                frame = localFrame,
                x = eval.x,
                y = eval.y,
                rotation = eval.rotation,
                scaleX = eval.scaleX,
                scaleY = eval.scaleY,
                opacity = eval.opacity,
                poseOffsets = eval.poseOffsets,
                expressionOverride = eval.expressionOverride,
                facialPresetId = eval.facialPresetId,
                mouthShapeOverride = eval.mouthShapeOverride,
                eyeShapeOverride = eval.eyeShapeOverride,
                eyebrowShapeOverride = eval.eyebrowShapeOverride,
                handPoseOverride = eval.handPoseOverride,
                blinkOverride = eval.blinkOverride
            )
        }
        val nextKf = transformUpdater(base)
        val merged = (clip.keyframes.filterNot { it.frame == localFrame } + nextKf).sortedBy { it.frame }
        scene.copy(
            clips = scene.clips.map { if (it.id == clipId) it.copy(keyframes = merged) else it }
        )
    }

    /**
     * Drops an algorithmic facial expression preset onto a Character layer/clip at [globalFrame],
     * automatically inserting an organic expression keyframe with EASE_IN_OUT timing and
     * returning the updated project paired with the affected clip ID (or null if layer is invalid/locked).
     */
    fun dropFacialExpressionPreset(
        project: ProjectDocument,
        layerId: String,
        clipId: String?,
        globalFrame: Int,
        presetId: String
    ): Pair<ProjectDocument, String?> {
        val preset = FacialExpressionEngine.presetById(presetId) ?: return project to null
        var affectedClipId: String? = null
        val updated = updateActiveScene(project) { scene ->
            val layer = scene.layers.firstOrNull { it.id == layerId }
            if (layer == null || layer.type != LayerType.CHARACTER || layer.isLocked) {
                return@updateActiveScene scene
            }
            val layerClips = scene.clips.filter { it.layerId == layer.id && it.characterId != null }
            val targetClip = (clipId?.let { id -> layerClips.firstOrNull { it.id == id } })
                ?: layerClips.firstOrNull { globalFrame in it.startFrame until it.endFrame }
                ?: layerClips.minByOrNull { kotlin.math.abs(it.startFrame - globalFrame) }
                ?: return@updateActiveScene scene

            affectedClipId = targetClip.id
            val clampedGlobal = globalFrame.coerceIn(
                targetClip.startFrame,
                (targetClip.endFrame - 1).coerceAtLeast(targetClip.startFrame)
            )
            val localFrame = (clampedGlobal - targetClip.startFrame)
                .coerceIn(0, (targetClip.durationFrames - 1).coerceAtLeast(0))

            val existing = targetClip.keyframes.firstOrNull { it.frame == localFrame }
            val eval = KeyframeEvaluator.evaluate(targetClip, clampedGlobal)
            val baseKf = existing ?: TransformKeyframe(
                frame = localFrame,
                x = eval.x,
                y = eval.y,
                rotation = eval.rotation,
                scaleX = eval.scaleX,
                scaleY = eval.scaleY,
                opacity = eval.opacity,
                easing = EasingType.EASE_IN_OUT,
                poseOffsets = eval.poseOffsets,
                mouthShapeOverride = eval.mouthShapeOverride,
                eyeShapeOverride = eval.eyeShapeOverride,
                eyebrowShapeOverride = eval.eyebrowShapeOverride,
                handPoseOverride = eval.handPoseOverride
            )
            val updatedKf = baseKf.copy(
                easing = EasingType.EASE_IN_OUT,
                expressionOverride = preset.baseExpression,
                facialPresetId = preset.id,
                blinkOverride = null
            )
            val merged = (targetClip.keyframes.filterNot { it.frame == localFrame } + updatedKf)
                .sortedBy { it.frame }

            scene.copy(
                clips = scene.clips.map { c ->
                    if (c.id == targetClip.id) c.copy(keyframes = merged) else c
                }
            )
        }
        return updated to affectedClipId
    }

    /**
     * Stamps independent Mouth, Eye, and/or Eyebrow preset shape overrides at [globalFrame]
     * on top of the character clip's running animation (persisted on both keyframe and clip fallback).
     */
    fun setFacialShapeOverrides(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int,
        mouthOverride: MouthShapeOverride? = null,
        eyeOverride: EyeShapeOverride? = null,
        eyebrowOverride: EyebrowShapeOverride? = null
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return@updateActiveScene scene
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) return@updateActiveScene scene

        val localFrame = (globalFrame - clip.startFrame).coerceIn(0, (clip.durationFrames - 1).coerceAtLeast(0))
        val existing = clip.keyframes.firstOrNull { it.frame == localFrame }
        val eval = KeyframeEvaluator.evaluate(clip, globalFrame)
        val base = existing ?: TransformKeyframe(
            frame = localFrame,
            x = eval.x,
            y = eval.y,
            rotation = eval.rotation,
            scaleX = eval.scaleX,
            scaleY = eval.scaleY,
            opacity = eval.opacity,
            poseOffsets = eval.poseOffsets,
            expressionOverride = eval.expressionOverride,
            facialPresetId = eval.facialPresetId,
            mouthShapeOverride = eval.mouthShapeOverride,
            eyeShapeOverride = eval.eyeShapeOverride,
            eyebrowShapeOverride = eval.eyebrowShapeOverride,
            handPoseOverride = eval.handPoseOverride,
            blinkOverride = eval.blinkOverride
        )
        val nextKf = base.copy(
            mouthShapeOverride = mouthOverride ?: base.mouthShapeOverride,
            eyeShapeOverride = eyeOverride ?: base.eyeShapeOverride,
            eyebrowShapeOverride = eyebrowOverride ?: base.eyebrowShapeOverride
        )
        val merged = (clip.keyframes.filterNot { it.frame == localFrame } + nextKf).sortedBy { it.frame }
        scene.copy(
            clips = scene.clips.map { c ->
                if (c.id == clipId) {
                    c.copy(
                        mouthShapeOverride = mouthOverride ?: c.mouthShapeOverride,
                        eyeShapeOverride = eyeOverride ?: c.eyeShapeOverride,
                        eyebrowShapeOverride = eyebrowOverride ?: c.eyebrowShapeOverride,
                        keyframes = merged
                    )
                } else c
            }
        )
    }

    fun clearFacialShapeOverrides(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int
    ): ProjectDocument = setFacialShapeOverrides(
        project = project,
        clipId = clipId,
        globalFrame = globalFrame,
        mouthOverride = MouthShapeOverride.AUTO,
        eyeOverride = EyeShapeOverride.AUTO,
        eyebrowOverride = EyebrowShapeOverride.AUTO
    )

    fun toggleKeyframeAtFrame(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return@updateActiveScene scene
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) return@updateActiveScene scene

        val localFrame = (globalFrame - clip.startFrame).coerceIn(0, (clip.durationFrames - 1).coerceAtLeast(0))
        val hasKf = clip.keyframes.any { it.frame == localFrame }
        val newKfs = if (hasKf && clip.keyframes.size > 1) {
            clip.keyframes.filterNot { it.frame == localFrame }
        } else if (!hasKf) {
            val eval = KeyframeEvaluator.evaluate(clip, globalFrame)
            (clip.keyframes + TransformKeyframe(
                frame = localFrame,
                x = eval.x,
                y = eval.y,
                rotation = eval.rotation,
                scaleX = eval.scaleX,
                scaleY = eval.scaleY,
                opacity = eval.opacity,
                poseOffsets = eval.poseOffsets,
                expressionOverride = eval.expressionOverride,
                facialPresetId = eval.facialPresetId,
                mouthShapeOverride = eval.mouthShapeOverride,
                eyeShapeOverride = eval.eyeShapeOverride,
                eyebrowShapeOverride = eval.eyebrowShapeOverride,
                handPoseOverride = eval.handPoseOverride,
                blinkOverride = eval.blinkOverride
            )).sortedBy { it.frame }
        } else {
            clip.keyframes
        }
        scene.copy(clips = scene.clips.map { if (it.id == clipId) it.copy(keyframes = newKfs) else it })
    }

    fun toggleCameraLayerEnabled(project: ProjectDocument): ProjectDocument = updateActiveScene(project) { scene ->
        scene.copy(cameraLayerEnabled = !scene.cameraLayerEnabled)
    }

    fun toggleCameraLayerLocked(project: ProjectDocument): ProjectDocument = updateActiveScene(project) { scene ->
        scene.copy(cameraLayerLocked = !scene.cameraLayerLocked)
    }

    fun upsertCameraKeyframeAtFrame(
        project: ProjectDocument,
        globalFrame: Int,
        updater: (CameraKeyframe) -> CameraKeyframe
    ): ProjectDocument = updateActiveScene(project) { scene ->
        if (scene.cameraLayerLocked) return@updateActiveScene scene
        val clampedFrame = globalFrame.coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
        val existing = scene.cameraKeyframes.firstOrNull { it.frame == clampedFrame }
        val base = existing ?: run {
            val evalCam = KeyframeEvaluator.evaluateCamera(scene.cameraKeyframes, clampedFrame)
            CameraKeyframe(
                frame = clampedFrame,
                x = evalCam.x,
                y = evalCam.y,
                zoom = evalCam.zoom,
                rotation = evalCam.rotation
            )
        }
        val nextCamKf = updater(base).let { kf ->
            kf.copy(
                x = kf.x.coerceIn(0.05f, 0.95f),
                y = kf.y.coerceIn(0.05f, 0.95f),
                zoom = kf.zoom.coerceIn(0.35f, 3.5f),
                rotation = kf.rotation.coerceIn(-180f, 180f)
            )
        }
        val merged = (scene.cameraKeyframes.filterNot { it.frame == clampedFrame } + nextCamKf).sortedBy { it.frame }
        scene.copy(cameraLayerEnabled = true, cameraKeyframes = merged)
    }

    fun updateCameraKeyframeEasing(
        project: ProjectDocument,
        globalFrame: Int,
        easing: EasingType
    ): ProjectDocument = upsertCameraKeyframeAtFrame(project, globalFrame) { kf ->
        val handles = KeyframeEvaluator.defaultBezierHandles(easing)
        kf.copy(
            easing = easing,
            easingP1X = handles[0],
            easingP1Y = handles[1],
            easingP2X = handles[2],
            easingP2Y = handles[3]
        )
    }

    fun deleteCameraKeyframeAtFrame(
        project: ProjectDocument,
        globalFrame: Int
    ): ProjectDocument = updateActiveScene(project) { scene ->
        if (scene.cameraLayerLocked) return@updateActiveScene scene
        scene.copy(cameraKeyframes = scene.cameraKeyframes.filterNot { it.frame == globalFrame })
    }

    fun resetCameraKeyframes(project: ProjectDocument): ProjectDocument = updateActiveScene(project) { scene ->
        if (scene.cameraLayerLocked) return@updateActiveScene scene
        scene.copy(cameraKeyframes = emptyList())
    }

    fun splitClipAtFrame(
        project: ProjectDocument,
        clipId: String,
        globalFrame: Int
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return@updateActiveScene scene
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) return@updateActiveScene scene
        if (globalFrame <= clip.startFrame + 2 || globalFrame >= clip.endFrame - 2) {
            return@updateActiveScene scene
        }

        val splitLocal = globalFrame - clip.startFrame
        val evalAtSplit = KeyframeEvaluator.evaluate(clip, globalFrame)
        val firstKfs = clip.keyframes.filter { it.frame < splitLocal }.ifEmpty {
            listOf(
                TransformKeyframe(
                    frame = 0,
                    x = evalAtSplit.x,
                    y = evalAtSplit.y,
                    rotation = evalAtSplit.rotation,
                    scaleX = evalAtSplit.scaleX,
                    scaleY = evalAtSplit.scaleY,
                    opacity = evalAtSplit.opacity,
                    poseOffsets = evalAtSplit.poseOffsets,
                    expressionOverride = evalAtSplit.expressionOverride,
                    facialPresetId = evalAtSplit.facialPresetId,
                    mouthShapeOverride = evalAtSplit.mouthShapeOverride,
                    eyeShapeOverride = evalAtSplit.eyeShapeOverride,
                    eyebrowShapeOverride = evalAtSplit.eyebrowShapeOverride,
                    handPoseOverride = evalAtSplit.handPoseOverride,
                    blinkOverride = evalAtSplit.blinkOverride
                )
            )
        }
        val secondKfs = (listOf(
            TransformKeyframe(
                frame = 0,
                x = evalAtSplit.x,
                y = evalAtSplit.y,
                rotation = evalAtSplit.rotation,
                scaleX = evalAtSplit.scaleX,
                scaleY = evalAtSplit.scaleY,
                opacity = evalAtSplit.opacity,
                poseOffsets = evalAtSplit.poseOffsets,
                expressionOverride = evalAtSplit.expressionOverride,
                facialPresetId = evalAtSplit.facialPresetId,
                mouthShapeOverride = evalAtSplit.mouthShapeOverride,
                eyeShapeOverride = evalAtSplit.eyeShapeOverride,
                eyebrowShapeOverride = evalAtSplit.eyebrowShapeOverride,
                handPoseOverride = evalAtSplit.handPoseOverride,
                blinkOverride = evalAtSplit.blinkOverride
            )
        ) + clip.keyframes.filter { it.frame > splitLocal }.map { it.copy(frame = it.frame - splitLocal) })
            .distinctBy { it.frame }
            .sortedBy { it.frame }

        val firstPart = clip.copy(durationFrames = splitLocal, keyframes = firstKfs)
        val secondPart = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (2)",
            startFrame = globalFrame,
            durationFrames = clip.endFrame - globalFrame,
            keyframes = secondKfs
        )
        scene.copy(
            clips = scene.clips.flatMap { if (it.id == clipId) listOf(firstPart, secondPart) else listOf(it) }
        )
    }

    fun duplicateClip(project: ProjectDocument, clipId: String): Pair<ProjectDocument, String?> {
        val original = project.activeScene.clips.firstOrNull { it.id == clipId } ?: return project to null
        val newClipId = UUID.randomUUID().toString()
        val updated = updateActiveScene(project) { scene ->
            val srcLayer = scene.layers.firstOrNull { it.id == original.layerId }
            val newLayer = AnimationLayer(
                name = "${srcLayer?.name ?: original.name} Copy",
                type = srcLayer?.type ?: LayerType.CHARACTER,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val shiftedKfs = original.keyframes.map { kf ->
                kf.copy(x = (kf.x + 0.08f).coerceIn(0.08f, 0.92f))
            }
            val copy = original.copy(
                id = newClipId,
                layerId = newLayer.id,
                name = "${original.name} Copy",
                keyframes = shiftedKfs
            )
            scene.copy(layers = scene.layers + newLayer, clips = scene.clips + copy)
        }
        return updated to newClipId
    }

    fun reorderLayer(project: ProjectDocument, layerId: String, moveUp: Boolean): ProjectDocument =
        updateActiveScene(project) { scene ->
            val sorted = scene.layers.sortedBy { it.orderIndex }.toMutableList()
            val idx = sorted.indexOfFirst { it.id == layerId }
            if (idx == -1) return@updateActiveScene scene
            val targetIdx = if (moveUp) idx + 1 else idx - 1
            if (targetIdx !in sorted.indices) return@updateActiveScene scene
            val tmp = sorted[idx]
            sorted[idx] = sorted[targetIdx]
            sorted[targetIdx] = tmp
            val reindexed = sorted.mapIndexed { i, l -> l.copy(orderIndex = i) }
            scene.copy(layers = reindexed)
        }

    fun bringLayerToFront(project: ProjectDocument, layerId: String): ProjectDocument =
        updateActiveScene(project) { scene ->
            val sorted = scene.layers.sortedBy { it.orderIndex }.toMutableList()
            val idx = sorted.indexOfFirst { it.id == layerId }
            if (idx == -1 || idx == sorted.lastIndex) return@updateActiveScene scene
            val target = sorted.removeAt(idx)
            sorted.add(target)
            val reindexed = sorted.mapIndexed { i, l -> l.copy(orderIndex = i) }
            scene.copy(layers = reindexed)
        }

    fun sendLayerToBack(project: ProjectDocument, layerId: String): ProjectDocument =
        updateActiveScene(project) { scene ->
            val sorted = scene.layers.sortedBy { it.orderIndex }.toMutableList()
            val idx = sorted.indexOfFirst { it.id == layerId }
            if (idx == -1 || idx == 0) return@updateActiveScene scene
            val target = sorted.removeAt(idx)
            sorted.add(0, target)
            val reindexed = sorted.mapIndexed { i, l -> l.copy(orderIndex = i) }
            scene.copy(layers = reindexed)
        }

    fun bringLayerForward(project: ProjectDocument, layerId: String): ProjectDocument =
        reorderLayer(project, layerId, moveUp = true)

    fun sendLayerBackward(project: ProjectDocument, layerId: String): ProjectDocument =
        reorderLayer(project, layerId, moveUp = false)

    fun deleteLayer(project: ProjectDocument, layerId: String): ProjectDocument =
        updateActiveScene(project) { scene ->
            val target = scene.layers.firstOrNull { it.id == layerId } ?: return@updateActiveScene scene
            if (target.isLocked || scene.layers.size <= 1) return@updateActiveScene scene
            val removedClipIds = scene.clips.asSequence()
                .filter { it.layerId == layerId }
                .map { it.id }
                .toSet()
            val remainingLayers = scene.layers
                .filterNot { it.id == layerId }
                .sortedBy { it.orderIndex }
                .mapIndexed { i, l -> l.copy(orderIndex = i) }
            val remainingClips = scene.clips.filterNot { it.layerId == layerId }
            val nextFocusClipId = if (scene.cameraFocusClipId in removedClipIds) null else scene.cameraFocusClipId
            val remainingUniversalTracks = scene.universalKeyframeTracks.filterNot { tr ->
                tr.targetId == layerId || tr.targetId in removedClipIds
            }
            scene.copy(
                layers = remainingLayers,
                clips = remainingClips,
                cameraFocusClipId = nextFocusClipId,
                universalKeyframeTracks = remainingUniversalTracks
            )
        }

    fun updateLayerParallaxDepth(
        project: ProjectDocument,
        layerId: String,
        parallaxDepth: Float
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clamped = com.example.engine.ParallaxCameraMath.clampDepth(parallaxDepth)
        scene.copy(
            layers = scene.layers.map { l ->
                if (l.id == layerId) l.copy(parallaxDepth = clamped) else l
            }
        )
    }

    fun upsertDialogueOverlayBeat(
        project: ProjectDocument,
        beat: DialogueOverlayBeat
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val exists = scene.dialogueOverlays.any { it.id == beat.id }
        val next = if (exists) {
            scene.dialogueOverlays.map { if (it.id == beat.id) beat else it }
        } else {
            scene.dialogueOverlays + beat
        }
        scene.copy(dialogueOverlays = next)
    }

    fun removeDialogueOverlayBeat(
        project: ProjectDocument,
        beatId: String
    ): ProjectDocument = updateActiveScene(project) { scene ->
        scene.copy(dialogueOverlays = scene.dialogueOverlays.filterNot { it.id == beatId })
    }

    /**
     * Deep-clones the active scene with fresh UUIDs for scene, layers, and clips,
     * remapping layerId and cameraFocusClipId references, and switches to the copy.
     */
    fun duplicateActiveScene(project: ProjectDocument): ProjectDocument {
        if (project.scenes.isEmpty()) return project
        val idx = project.activeSceneIndex.coerceIn(0, project.scenes.lastIndex)
        val source = project.scenes[idx]

        val layerIdMap = HashMap<String, String>(source.layers.size)
        val clonedLayers = source.layers.map { layer ->
            val newId = UUID.randomUUID().toString()
            layerIdMap[layer.id] = newId
            layer.copy(id = newId)
        }
        val clipIdMap = HashMap<String, String>(source.clips.size)
        val clonedClips = source.clips.map { clip ->
            val newId = UUID.randomUUID().toString()
            clipIdMap[clip.id] = newId
            clip.copy(
                id = newId,
                layerId = layerIdMap[clip.layerId] ?: clip.layerId,
                drawingStrokes = clip.drawingStrokes.map { st -> st.copy(id = UUID.randomUUID().toString()) }
            )
        }
        val baseTitle = source.name.removeSuffix(" Copy").trim().ifBlank { "Scene ${idx + 1}" }
        val clonedScene = source.copy(
            id = UUID.randomUUID().toString(),
            name = "$baseTitle Copy",
            cameraFocusClipId = source.cameraFocusClipId?.let { clipIdMap[it] },
            layers = clonedLayers,
            clips = clonedClips
        )
        val nextScenes = project.scenes.toMutableList().apply { add(idx + 1, clonedScene) }
        return project.copy(
            scenes = nextScenes,
            activeSceneIndex = idx + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Stamps a 1-tap expressive multi-keyframe motion preset onto [clipId] starting at [startGlobalFrame].
     */
    fun applyQuickMotionPreset(
        project: ProjectDocument,
        clipId: String,
        startGlobalFrame: Int,
        presetId: String
    ): ProjectDocument = updateActiveScene(project) { scene ->
        val clip = scene.clips.firstOrNull { it.id == clipId } ?: return@updateActiveScene scene
        val layer = scene.layers.firstOrNull { it.id == clip.layerId }
        if (layer?.isLocked == true) return@updateActiveScene scene

        val maxLocal = (clip.durationFrames - 1).coerceAtLeast(1)
        val f0 = (startGlobalFrame - clip.startFrame).coerceIn(0, (maxLocal - 12).coerceAtLeast(0))
        val span = (maxLocal - f0).coerceIn(12, 48)
        val fMid = (f0 + span / 2).coerceAtMost(maxLocal)
        val fEnd = (f0 + span).coerceAtMost(maxLocal)
        val base = KeyframeEvaluator.evaluate(clip, clip.startFrame + f0)

        val generated: List<TransformKeyframe> = when (presetId) {
            "bounce_in" -> listOf(
                TransformKeyframe(frame = f0, x = base.x, y = 0.14f, scaleX = base.scaleX * 0.85f, scaleY = base.scaleY * 1.15f, opacity = 0.25f, easing = EasingType.BOUNCE),
                TransformKeyframe(frame = fEnd, x = base.x, y = base.y, scaleX = base.scaleX, scaleY = base.scaleY, opacity = 1f, easing = EasingType.EASE_OUT)
            )
            "slide_overshoot" -> listOf(
                TransformKeyframe(frame = f0, x = 0.12f, y = base.y, scaleX = base.scaleX, scaleY = base.scaleY, opacity = 0.4f, easing = EasingType.ELASTIC),
                TransformKeyframe(frame = fEnd, x = base.x.coerceIn(0.32f, 0.82f), y = base.y, scaleX = base.scaleX, scaleY = base.scaleY, opacity = 1f, easing = EasingType.EASE_OUT)
            )
            "float_hover" -> listOf(
                TransformKeyframe(frame = f0, x = base.x, y = base.y, rotation = -2f, scaleX = base.scaleX, scaleY = base.scaleY, easing = EasingType.EASE_IN_OUT),
                TransformKeyframe(frame = fMid, x = base.x, y = (base.y - 0.10f).coerceAtLeast(0.12f), rotation = 2.5f, scaleX = base.scaleX, scaleY = base.scaleY, easing = EasingType.EASE_IN_OUT),
                TransformKeyframe(frame = fEnd, x = base.x, y = base.y, rotation = 0f, scaleX = base.scaleX, scaleY = base.scaleY, easing = EasingType.EASE_IN_OUT)
            )
            "dash_across" -> listOf(
                TransformKeyframe(frame = f0, x = 0.14f, y = base.y, rotation = -6f, scaleX = base.scaleX * 1.08f, scaleY = base.scaleY * 0.94f, easing = EasingType.EASE_IN),
                TransformKeyframe(frame = fMid, x = 0.56f, y = (base.y - 0.04f).coerceAtLeast(0.15f), rotation = 4f, scaleX = base.scaleX * 1.04f, scaleY = base.scaleY * 0.96f, easing = EasingType.EASE_OUT),
                TransformKeyframe(frame = fEnd, x = 0.84f, y = base.y, rotation = 0f, scaleX = base.scaleX, scaleY = base.scaleY, easing = EasingType.EASE_OUT)
            )
            "pop_scale" -> listOf(
                TransformKeyframe(frame = f0, x = base.x, y = base.y, scaleX = base.scaleX * 0.22f, scaleY = base.scaleY * 0.22f, opacity = 0f, easing = EasingType.ELASTIC),
                TransformKeyframe(frame = fEnd, x = base.x, y = base.y, scaleX = base.scaleX, scaleY = base.scaleY, opacity = 1f, easing = EasingType.EASE_OUT)
            )
            "hero_landing" -> listOf(
                TransformKeyframe(frame = f0, x = base.x, y = 0.12f, rotation = -8f, scaleX = base.scaleX * 0.9f, scaleY = base.scaleY * 1.14f, easing = EasingType.EASE_IN),
                TransformKeyframe(frame = fMid, x = base.x, y = (base.y + 0.03f).coerceAtMost(0.92f), rotation = 0f, scaleX = base.scaleX * 1.16f, scaleY = base.scaleY * 0.84f, easing = EasingType.BOUNCE),
                TransformKeyframe(frame = fEnd, x = base.x, y = base.y, rotation = 0f, scaleX = base.scaleX, scaleY = base.scaleY, easing = EasingType.EASE_OUT)
            )
            else -> return@updateActiveScene scene
        }

        val generatedFrames = generated.map { it.frame }.toSet()
        val merged = (clip.keyframes.filterNot { it.frame in generatedFrames } + generated).sortedBy { it.frame }
        scene.copy(clips = scene.clips.map { if (it.id == clipId) it.copy(keyframes = merged) else it })
    }

    /**
     * Stamps an advanced multi-keyframe camera choreography preset from [CameraPresetEngine]
     * onto the active scene's Camera Control Layer.
     */
    fun applyCinematicCameraPreset(
        project: ProjectDocument,
        startFrame: Int,
        presetId: String,
        intensity: Float = 1.0f,
        spanFrames: Int? = null
    ): ProjectDocument = updateActiveScene(project) { scene ->
        if (scene.cameraLayerLocked) return@updateActiveScene scene
        val keys = CameraPresetEngine.generateKeyframes(
            presetId = presetId,
            startFrame = startFrame,
            sceneDurationFrames = scene.durationFrames,
            intensity = intensity,
            customSpanFrames = spanFrames
        )
        if (keys.isEmpty()) return@updateActiveScene scene

        val replacedFrames = keys.map { it.frame }.toSet()
        val merged = (scene.cameraKeyframes.filterNot { it.frame in replacedFrames } + keys).sortedBy { it.frame }
        scene.copy(cameraLayerEnabled = true, cameraKeyframes = merged)
    }

    /**
     * Bulk-deletes multiple timeline clips across all unlocked layers in the active scene.
     * Preserves clips on locked layers and clears [AnimationScene.cameraFocusClipId] if deleted.
     */
    fun deleteClips(
        project: ProjectDocument,
        clipIds: Set<String>
    ): ProjectDocument {
        if (clipIds.isEmpty()) return project
        return updateActiveScene(project) { scene ->
            val lockedLayerIds = scene.layers.filter { it.isLocked }.map { it.id }.toSet()
            val remaining = scene.clips.filterNot { clip ->
                clip.id in clipIds && clip.layerId !in lockedLayerIds
            }
            val remainingIds = remaining.map { it.id }.toSet()
            val nextFocusId = scene.cameraFocusClipId?.takeIf { it in remainingIds }
            scene.copy(
                clips = remaining,
                cameraFocusClipId = nextFocusId
            )
        }
    }
}
