package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AtlasPoseTemplate
import com.example.model.MediaAssetType
import com.example.navigation.HashRoute
import com.example.ui.screens.AnimationLibraryScreen
import com.example.ui.screens.CharacterLibraryScreen
import com.example.ui.screens.MediaLibraryScreen
import com.example.ui.screens.ProjectsHomeScreen
import com.example.ui.screens.editor.EditorScreen
import com.example.ui.screens.maker.CharacterMakerScreen
import com.example.ui.screens.maker.CharacterSetupScreen
import com.example.ui.theme.FrameCraftTheme
import com.example.ui.theme.StudioObsidian
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            FrameCraftTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StudioObsidian
                ) {
                    FrameCraftApp()
                }
            }
        }
    }
}

@Composable
fun FrameCraftApp(viewModel: StudioViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack by viewModel.router.backStack.collectAsStateWithLifecycle()
    val currentHash = backStack.lastOrNull() ?: HashRoute.HASH_PROJECTS
    val currentRoute = HashRoute.parse(currentHash)

    when (currentRoute) {
        is HashRoute.ProjectDashboard -> {
            ProjectsHomeScreen(
                projects = state.projects,
                hasRecoverableProject = state.hasLastRecoverableProject,
                onCreateProject = { name, tplId -> viewModel.createNewProject(name, tplId) },
                onOpenProject = { id -> viewModel.openProject(id) },
                onRecoverLastProject = { viewModel.recoverLastProject() },
                onRenameProject = { id, newName -> viewModel.renameProject(id, newName) },
                onDuplicateProject = { id -> viewModel.duplicateProject(id) },
                onDeleteProject = { id -> viewModel.deleteProject(id) },
                onOpenCharacterLibrary = { viewModel.openCharacterLibrary(StudioDestination.PROJECTS_HOME) },
                onOpenMediaLibrary = { viewModel.openMediaLibrary(StudioDestination.PROJECTS_HOME) }
            )
        }

        is HashRoute.CharacterLibrary -> {
            val isReplacingChar = state.replacingClipId != null
            CharacterLibraryScreen(
                characters = state.characters,
                assetStorage = viewModel.assetStorage,
                onSelectCharacter = { char ->
                    if (isReplacingChar) {
                        viewModel.replaceSelectedClipCharacter(char)
                    } else {
                        viewModel.openAnimationLibraryForCharacter(char.id)
                    }
                },
                onTogglePin = { char -> viewModel.togglePinCharacter(char) },
                onSaveCharacter = { char -> viewModel.saveCharacter(char) },
                onDeleteCharacter = { char -> viewModel.deleteCustomCharacter(char) },
                onImportSpriteUri = { uri, onPath -> viewModel.importCharacterSpriteUri(uri, onPath) },
                onOpenCharacterMakerSetup = { viewModel.openCharacterSetup() },
                onEditAtlasCharacter = { char ->
                    val pose = char.spriteAtlasRig?.poseTemplate ?: AtlasPoseTemplate.THREE_QUARTER_FRONT
                    viewModel.openCharacterMaker(pose = pose, editCharacterId = char.id)
                },
                onImportCharacterPack = { uri, onMsg -> viewModel.importCharacterPackUri(uri, onMsg) },
                isReplaceMode = isReplacingChar,
                onClose = { viewModel.navigateBack() }
            )
        }

        is HashRoute.CharacterSetup -> {
            CharacterSetupScreen(
                onCreateWithPose = { pose -> viewModel.openCharacterMaker(pose) },
                onImportCharacterPack = { uri, onMsg -> viewModel.importCharacterPackUri(uri, onMsg) },
                onBack = { viewModel.navigateBack() }
            )
        }

        is HashRoute.CharacterMaker -> {
            val pose = AtlasPoseTemplate.fromId(currentRoute.poseId)
            val editChar = currentRoute.editCharacterId?.let { state.charactersById[it] }
            CharacterMakerScreen(
                initialPose = pose,
                existingCharacter = editChar,
                assetStorage = viewModel.assetStorage,
                onSaveCharacter = { saved -> viewModel.saveAtlasCharacterAndReturn(saved) },
                onBack = { viewModel.navigateBack() }
            )
        }

        is HashRoute.AnimationLibrary -> {
            val charId = currentRoute.characterId ?: state.selectedCharacterIdForLibrary
            val selectedChar = charId?.let { state.charactersById[it] } ?: state.characters.first()
            AnimationLibraryScreen(
                selectedCharacter = selectedChar,
                presets = state.animationPresets,
                assetStorage = viewModel.assetStorage,
                onBack = { viewModel.navigateBack() },
                onApplyAnimation = { orient, preset, dur, speed, loop ->
                    viewModel.applyAnimationPresetToCanvas(
                        characterId = selectedChar.id,
                        orientation = orient,
                        presetType = preset,
                        durationFrames = dur,
                        speedMultiplier = speed,
                        loopAnimation = loop
                    )
                }
            )
        }

        is HashRoute.MediaLibrary -> {
            val isReplacingMedia = state.replacingClipId != null
            MediaLibraryScreen(
                assets = state.mediaAssets,
                onImportUri = { uri, type -> viewModel.importMediaUri(uri, preferredType = type) },
                onPlaceOnCanvas = { asset, asBg ->
                    if (isReplacingMedia) {
                        viewModel.replaceSelectedClipMedia(asset)
                    } else {
                        viewModel.placeMediaAssetOnCanvas(asset, asBg)
                    }
                },
                onDeleteAsset = { asset -> viewModel.deleteMediaAsset(asset) },
                isReplaceMode = isReplacingMedia,
                onClose = { viewModel.navigateBack() }
            )
        }

        is HashRoute.AnimationEditor -> {
            EditorScreen(
                state = state,
                playbackFrameFlow = viewModel.playbackFrame,
                bitmapVersionFlow = viewModel.bitmapVersion,
                sceneRenderer = viewModel.sceneRenderer,
                onBack = { viewModel.navigateBack() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onToggleOnionSkin = { viewModel.toggleOnionSkin() },
                onToggleMotionPaths = { viewModel.toggleMotionPaths() },
                onSave = { viewModel.saveCurrentProject(silent = false) },
                onResetProject = { viewModel.resetCurrentProject() },
                onOpenSheet = { viewModel.openSheet(it) },
                onCloseSheet = { viewModel.closeSheet() },
                onOpenCharacterLibrary = { viewModel.openCharacterLibrary(StudioDestination.EDITOR) },
                onOpenPresetsForSelectedChar = { charId, clipId ->
                    viewModel.openAnimationLibraryForCharacter(charId, swapClipId = clipId)
                },
                onOpenMediaLibrary = { viewModel.openMediaLibrary(StudioDestination.EDITOR) },
                onQuickImportImageUri = { uri ->
                    viewModel.importMediaUri(uri, preferredType = MediaAssetType.IMAGE, addImmediatelyToCanvas = true)
                },
                onQuickImportAudioUri = { uri ->
                    viewModel.importMediaUri(uri, preferredType = MediaAssetType.AUDIO, addImmediatelyToCanvas = true)
                },
                onSelectClip = { viewModel.selectClip(it) },
                onCommitClipTranslation = { clipId, dx, dy -> viewModel.commitClipTranslation(clipId, dx, dy) },
                onCommitIkPose = { clipId, offsets -> viewModel.commitIkPoseOffsets(clipId, offsets) },
                onCommitCameraPan = { dx, dy -> viewModel.commitCameraPan(dx, dy) },
                onFinishStroke = { pts -> viewModel.finishDrawingStroke(pts) },
                onBrushStyleChange = { hex, w, style -> viewModel.setBrushStyle(hex, w, style) },
                onUndoStroke = { viewModel.undoLastDrawingStroke() },
                onClearStrokes = { viewModel.clearDrawingStrokes() },
                onToggleDrawingMode = { viewModel.toggleDrawingMode() },
                onZoomCanvas = { viewModel.zoomCanvasBy(it) },
                onResetViewport = { viewModel.resetCanvasViewport() },
                onSelectScene = { viewModel.selectScene(it) },
                onAddScene = { viewModel.addScene() },
                onDuplicateScene = { viewModel.duplicateActiveScene() },
                onTogglePlayback = { viewModel.togglePlayback() },
                onStepFrame = { viewModel.stepFrame(it) },
                onSeekFrame = { viewModel.seekToFrame(it) },
                onToggleLoop = { viewModel.toggleLooping() },
                onCyclePlaybackSpeed = { viewModel.cyclePlaybackSpeed() },
                onToggleKeyframe = { viewModel.toggleKeyframeAtCurrentFrame() },
                onSelectKeyframeNode = { clipId, frame -> viewModel.selectKeyframeNode(clipId, frame) },
                onCycleSelectedKeyframeEasing = { viewModel.cycleSelectedKeyframeEasing() },
                onApplyEasingCurve = { easing, p1x, p1y, p2x, p2y ->
                    viewModel.updateSelectedKeyframeEasing(easing, p1x, p1y, p2x, p2y)
                },
                onApplyQuickMotion = { presetId, label -> viewModel.applyQuickMotionPreset(presetId, label) },
                onAdjustTimelineZoom = { viewModel.adjustTimelineZoom(it) },
                onSetTimelineZoom = { viewModel.setTimelineZoom(it) },
                onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                onAddLayer = { type, name -> viewModel.addEmptyLayer(type, name) },
                onRenameLayer = { id, name -> viewModel.renameLayer(id, name) },
                onReorderLayer = { id, up -> viewModel.reorderLayer(id, up) },
                onBringLayerToFront = { id -> viewModel.bringLayerToFront(id) },
                onSendLayerToBack = { id -> viewModel.sendLayerToBack(id) },
                onBringLayerForward = { id -> viewModel.bringLayerForward(id) },
                onSendLayerBackward = { id -> viewModel.sendLayerBackward(id) },
                onDuplicateLayer = { id -> viewModel.duplicateLayer(id) },
                onUpdateLayerParallaxDepth = { id, d -> viewModel.updateLayerParallaxDepth(id, d) },
                onDeleteLayer = { id -> viewModel.deleteLayer(id) },
                onReplaceCharacterClip = { clipId -> viewModel.openCharacterLibraryForReplace(clipId) },
                onReplaceMediaClip = { clipId -> viewModel.openMediaLibraryForReplace(clipId) },
                onUpdateTransform = { viewModel.updateSelectedClipTransform(it) },
                onUpdateSelectedClip = { viewModel.updateSelectedClipProps(it) },
                onSplitClip = { viewModel.splitSelectedClipAtPlayhead() },
                onSplitClipById = { clipId -> viewModel.splitClipAtPlayhead(clipId) },
                onDuplicateClip = { viewModel.duplicateSelectedClip() },
                onDuplicateClipById = { clipId -> viewModel.duplicateClipById(clipId) },
                onFlipClip = { viewModel.flipSelectedClip(it) },
                onDeleteClip = { viewModel.deleteSelectedClip() },
                onDeleteClipById = { clipId, ripple -> viewModel.deleteClipById(clipId, ripple) },
                onToggleClipHoldCut = { clipId -> viewModel.toggleClipHoldCut(clipId) },
                onTrimClipToPlayhead = { clipId -> viewModel.trimClipToPlayhead(clipId) },
                onTrimOrMoveClip = { clipId, startF, durF, isMoveOnly ->
                    viewModel.trimOrMoveTimelineClip(clipId, startF, durF, isMoveOnly)
                },
                onMoveClipAcrossTracks = { clipId, targetLayerId, targetStartF, createNewTrack, insertAtTop ->
                    viewModel.moveClipAcrossTracks(clipId, targetLayerId, targetStartF, createNewTrack, insertAtTop)
                },
                onToggleTimelineMultiSelectMode = { viewModel.toggleTimelineMultiSelectMode() },
                onToggleTimelineClipMultiSelect = { viewModel.toggleTimelineClipSelection(it) },
                onSelectAllTimelineClips = { viewModel.selectAllTimelineClips() },
                onDeleteSelectedTimelineClips = { viewModel.deleteSelectedTimelineClips() },
                onAddShape = { kind, fill, stroke -> viewModel.addShape(kind, fill, stroke) },
                onAddText = { text, col, sp, bubbleStyle, bubbleFill ->
                    viewModel.addText(text, col, sp, bubbleStyle, bubbleFill)
                },
                onSetBackgroundBundled = { viewModel.setSceneBackgroundBundled(it) },
                onSetBackgroundColor = { viewModel.setSceneBackgroundColor(it) },
                onApplyTemplate = { viewModel.applyTemplateToCurrentProject(it) },
                onUpdateProjectSettings = { name, ratio, w, h, fps, dur, q ->
                    viewModel.updateProjectSettings(name, ratio, w, h, fps, dur, q)
                },
                onStartExport = { fmt, res, fps, dur ->
                    viewModel.startExport(fmt, res, fps, dur)
                },
                onCancelExport = { viewModel.cancelExport() },
                onToggleIkPosingMode = { viewModel.toggleIkPosingMode() },
                onToggleIkFlipBend = { viewModel.toggleIkFlipBend() },
                onToggleIkJointLimits = { viewModel.toggleIkJointLimits() },
                onMirrorIkPose = { viewModel.mirrorSelectedClipPoseAtCurrentFrame() },
                onToggleCameraKeyMode = { viewModel.toggleCameraKeyMode() },
                onToggleCameraViewportMode = { viewModel.toggleCameraViewportMode() },
                onAddCameraBox = { viewModel.addCameraBox() },
                onSelectCameraBox = { boxId -> viewModel.selectCameraBox(boxId) },
                onCommitCameraBoxTransform = { id, cx, cy, z, r ->
                    viewModel.commitCameraBoxTransform(id, cx, cy, z, r)
                },
                onTrimOrMoveCameraBox = { id, startF, endF ->
                    viewModel.trimOrMoveCameraBox(id, startF, endF)
                },
                onDuplicateCameraBox = { id -> viewModel.duplicateCameraBox(id) },
                onSplitCameraBox = { id -> viewModel.splitCameraBox(id) },
                onDeleteCameraBox = { id -> viewModel.deleteCameraBox(id, ripple = false) },
                onDeleteCameraBoxRipple = { id, ripple -> viewModel.deleteCameraBox(id, ripple = ripple) },
                onTrimCameraBoxToPlayhead = { id -> viewModel.trimCameraBoxToPlayhead(id) },
                onCycleCameraBoxEasing = { id -> viewModel.cycleCameraBoxEasing(id) },
                onStepAdjacentKeyframe = { forward -> viewModel.stepToAdjacentKeyframe(forward) },
                onStampPoseKey = { viewModel.stampPoseAndCameraKeyframe() },
                onResetPoseAtCurrentFrame = { viewModel.resetSelectedClipPoseAtCurrentFrame() },
                onSetChromaKeyMode = { mode -> viewModel.setSceneChromaKeyMode(mode) },
                onSetCameraFocusClip = { clipId -> viewModel.setCameraFocusClip(clipId) },
                onToggleCameraLayerEnabled = { viewModel.toggleCameraLayerEnabled() },
                onToggleCameraLayerLocked = { viewModel.toggleCameraLayerLocked() },
                onSelectCameraKeyframe = { frame -> viewModel.selectCameraKeyframe(frame) },
                onUpdateCameraEasing = { easing -> viewModel.updateCameraKeyframeEasing(easing) },
                onUpdateCamera = { mutator -> viewModel.updateCameraAtCurrentFrame(mutator) },
                onDeleteCameraKeyframeAtFrame = { viewModel.deleteCameraKeyframeAtCurrentFrame() },
                onResetAllCameraKeyframes = { viewModel.resetAllCameraKeyframes() },
                onApplyCinematicCameraPreset = { presetId, label, intensity ->
                    viewModel.applyCinematicCameraPreset(presetId, label, intensity)
                },
                onUpdateSceneVfx = { updater -> viewModel.updateSceneVfxConfig(updater) },
                onUpsertDialogueOverlay = { beat -> viewModel.upsertDialogueOverlay(beat) },
                onRemoveDialogueOverlay = { beatId -> viewModel.removeDialogueOverlay(beatId) },
                onUpdateAudioDucking = { updater -> viewModel.updateAudioDuckingConfig(updater) },
                onImportCustomPropUri = { uri, onPath -> viewModel.importCharacterSpriteUri(uri, onPath) },
                onTrimAudioClip = { clipId, startF, durF, trimStartMs, trimEndMs ->
                    viewModel.trimAudioClip(clipId, startF, durF, trimStartMs, trimEndMs)
                },
                onResetAudioTrim = { viewModel.resetSelectedAudioTrim() },
                onWaveformSensitivityChange = { viewModel.setWaveformSensitivity(it) },
                onSaveWaveformJsonToProject = { clipName, json, onRes ->
                    viewModel.saveWaveformJsonToProject(clipName, json, onRes)
                },
                onWriteWaveformJsonToSafUri = { uri, json, onRes ->
                    viewModel.writeWaveformJsonToSafUri(uri, json, onRes)
                },
                onDropFacialExpression = { layerId, clipId, globalFrame, presetId ->
                    viewModel.dropFacialExpressionOnTimeline(layerId, clipId, globalFrame, presetId)
                },
                onSetFacialShapeOverride = { mouth, eye, eyebrow ->
                    viewModel.setFacialShapeOverride(mouth, eye, eyebrow)
                },
                onClearFacialShapeOverrides = { viewModel.clearFacialShapeOverrides() },
                onApplyDirectorScript = { scriptJson, mode, trackSelection ->
                    viewModel.applyDirectorScript(scriptJson, mode, trackSelection)
                },
                onDeleteScriptHistoryEntry = { entryId ->
                    viewModel.deleteDirectorScriptHistoryEntry(entryId)
                },
                onClearScriptHistory = {
                    viewModel.clearDirectorScriptHistory()
                },
                onExportBlueprintToSaf = { uri, json ->
                    viewModel.exportBlueprintToSafUri(uri, json)
                },
                onOpenIkStudio = { tab -> viewModel.openIkStudio(tab) },
                onCommitIkStudioPose = { clipId, offsets, expr, hand ->
                    viewModel.commitIkStudioPose(clipId, offsets, expr, hand)
                },
                onSavePoseToLibrary = { name, cat, offsets, gaze, headTilt, expr, hand ->
                    viewModel.saveCurrentPoseToLibrary(name, cat, offsets, gaze, headTilt, expr, hand)
                },
                onRenameSavedPose = { id, newName, newCat ->
                    viewModel.renameSavedPose(id, newName, newCat)
                },
                onDeleteSavedPose = { id -> viewModel.deleteSavedPose(id) },
                onDeleteSavedPoses = { ids -> viewModel.deleteSavedPoses(ids) },
                onApplySavedPose = { pose -> viewModel.applySavedPoseToSelectedClip(pose) },
                onInsertModularAction = { clipId, action ->
                    viewModel.insertModularActionAtPlayhead(clipId, action)
                },
                loadPoseThumbnail = { path -> viewModel.poseLibraryStorage.loadCachedThumbnail(path) },
                loadCharacterBitmap = { path ->
                    if (!path.isNullOrBlank()) viewModel.assetStorage.getCachedOrRequestBitmap(path, null) else null
                },
                loadAtlasSlices = { path ->
                    if (!path.isNullOrBlank()) viewModel.assetStorage.getCachedOrSliceAtlas(path) else null
                }
            )
        }
    }
}
