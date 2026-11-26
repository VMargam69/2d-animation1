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
            CharacterLibraryScreen(
                characters = state.characters,
                assetStorage = viewModel.assetStorage,
                onSelectCharacter = { char -> viewModel.openAnimationLibraryForCharacter(char.id) },
                onTogglePin = { char -> viewModel.togglePinCharacter(char) },
                onSaveCharacter = { char -> viewModel.saveCharacter(char) },
                onDeleteCharacter = { char -> viewModel.deleteCustomCharacter(char) },
                onImportSpriteUri = { uri, onPath -> viewModel.importCharacterSpriteUri(uri, onPath) },
                onOpenCharacterMakerSetup = { viewModel.openCharacterSetup() },
                onEditAtlasCharacter = { char ->
                    val pose = char.spriteAtlasRig?.poseTemplate ?: AtlasPoseTemplate.THREE_QUARTER_FRONT
                    viewModel.openCharacterMaker(pose = pose, editCharacterId = char.id)
                },
                onClose = { viewModel.navigateBack() }
            )
        }

        is HashRoute.CharacterSetup -> {
            CharacterSetupScreen(
                onCreateWithPose = { pose -> viewModel.openCharacterMaker(pose) },
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
            MediaLibraryScreen(
                assets = state.mediaAssets,
                onImportUri = { uri, type -> viewModel.importMediaUri(uri, preferredType = type) },
                onPlaceOnCanvas = { asset, asBg -> viewModel.placeMediaAssetOnCanvas(asset, asBg) },
                onDeleteAsset = { asset -> viewModel.deleteMediaAsset(asset) },
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
                onBrushStyleChange = { hex, w -> viewModel.setBrushStyle(hex, w) },
                onToggleDrawingMode = { viewModel.toggleDrawingMode() },
                onZoomCanvas = { viewModel.zoomCanvasBy(it) },
                onResetViewport = { viewModel.resetCanvasViewport() },
                onSelectScene = { viewModel.selectScene(it) },
                onAddScene = { viewModel.addScene() },
                onTogglePlayback = { viewModel.togglePlayback() },
                onStepFrame = { viewModel.stepFrame(it) },
                onSeekFrame = { viewModel.seekToFrame(it) },
                onToggleLoop = { viewModel.toggleLooping() },
                onToggleKeyframe = { viewModel.toggleKeyframeAtCurrentFrame() },
                onSelectKeyframeNode = { clipId, frame -> viewModel.selectKeyframeNode(clipId, frame) },
                onCycleSelectedKeyframeEasing = { viewModel.cycleSelectedKeyframeEasing() },
                onAdjustTimelineZoom = { viewModel.adjustTimelineZoom(it) },
                onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                onAddLayer = { type, name -> viewModel.addEmptyLayer(type, name) },
                onRenameLayer = { id, name -> viewModel.renameLayer(id, name) },
                onReorderLayer = { id, up -> viewModel.reorderLayer(id, up) },
                onDeleteLayer = { id -> viewModel.deleteLayer(id) },
                onUpdateTransform = { viewModel.updateSelectedClipTransform(it) },
                onUpdateSelectedClip = { viewModel.updateSelectedClipProps(it) },
                onSplitClip = { viewModel.splitSelectedClipAtPlayhead() },
                onDuplicateClip = { viewModel.duplicateSelectedClip() },
                onFlipClip = { viewModel.flipSelectedClip(it) },
                onDeleteClip = { viewModel.deleteSelectedClip() },
                onAddShape = { kind, fill, stroke -> viewModel.addShape(kind, fill, stroke) },
                onAddText = { text, col, sp -> viewModel.addText(text, col, sp) },
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
                onToggleCameraKeyMode = { viewModel.toggleCameraKeyMode() },
                onStepAdjacentKeyframe = { forward -> viewModel.stepToAdjacentKeyframe(forward) },
                onStampPoseKey = { viewModel.stampPoseAndCameraKeyframe() },
                onResetPoseAtCurrentFrame = { viewModel.resetSelectedClipPoseAtCurrentFrame() },
                onSetChromaKeyMode = { mode -> viewModel.setSceneChromaKeyMode(mode) },
                onSetCameraFocusClip = { clipId -> viewModel.setCameraFocusClip(clipId) },
                onUpdateCamera = { mutator -> viewModel.updateCameraAtCurrentFrame(mutator) },
                onDeleteCameraKeyframeAtFrame = { viewModel.deleteCameraKeyframeAtCurrentFrame() },
                onResetAllCameraKeyframes = { viewModel.resetAllCameraKeyframes() },
                onImportCustomPropUri = { uri, onPath -> viewModel.importCharacterSpriteUri(uri, onPath) }
            )
        }
    }
}
