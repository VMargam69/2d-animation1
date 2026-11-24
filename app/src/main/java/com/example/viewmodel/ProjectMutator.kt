package com.example.viewmodel

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
            updatedScenes[idx] = transform(updatedScenes[idx])
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
        startFrame: Int
    ): Pair<ProjectDocument, String> {
        val clipId = UUID.randomUUID().toString()
        val cleanText = text.trim().ifBlank { "Title" }
        val updated = updateActiveScene(project) { scene ->
            val layer = AnimationLayer(
                name = "Text: ${cleanText.take(12)}",
                type = LayerType.TEXT,
                orderIndex = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            )
            val dur = (scene.durationFrames - startFrame).coerceAtLeast(48)
            val clip = TimelineClip(
                id = clipId,
                layerId = layer.id,
                name = cleanText.take(20),
                startFrame = startFrame,
                durationFrames = dur,
                textContent = cleanText,
                textColorHex = colorHex,
                textSizeSp = sizeSp,
                keyframes = listOf(TransformKeyframe(frame = 0, x = 0.5f, y = 0.22f))
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
                opacity = eval.opacity
            )
        }
        val nextKf = transformUpdater(base)
        val merged = (clip.keyframes.filterNot { it.frame == localFrame } + nextKf).sortedBy { it.frame }
        scene.copy(
            clips = scene.clips.map { if (it.id == clipId) it.copy(keyframes = merged) else it }
        )
    }

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
                opacity = eval.opacity
            )).sortedBy { it.frame }
        } else {
            clip.keyframes
        }
        scene.copy(clips = scene.clips.map { if (it.id == clipId) it.copy(keyframes = newKfs) else it })
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
            listOf(TransformKeyframe(frame = 0, x = evalAtSplit.x, y = evalAtSplit.y, rotation = evalAtSplit.rotation, scaleX = evalAtSplit.scaleX, scaleY = evalAtSplit.scaleY, opacity = evalAtSplit.opacity))
        }
        val secondKfs = (listOf(
            TransformKeyframe(
                frame = 0,
                x = evalAtSplit.x,
                y = evalAtSplit.y,
                rotation = evalAtSplit.rotation,
                scaleX = evalAtSplit.scaleX,
                scaleY = evalAtSplit.scaleY,
                opacity = evalAtSplit.opacity
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
}
