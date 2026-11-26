package com.example.ui.screens.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.ActiveEditorSheet
import com.example.viewmodel.StudioUiState

@Composable
fun EditorScreen(
    state: StudioUiState,
    playbackFrameFlow: kotlinx.coroutines.flow.StateFlow<Int>,
    bitmapVersionFlow: kotlinx.coroutines.flow.StateFlow<Int>,
    sceneRenderer: SceneRenderer,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onToggleMotionPaths: () -> Unit,
    onSave: () -> Unit,
    onResetProject: () -> Unit,
    onOpenSheet: (ActiveEditorSheet) -> Unit,
    onCloseSheet: () -> Unit,
    onOpenCharacterLibrary: () -> Unit,
    onOpenPresetsForSelectedChar: (characterId: String, clipId: String) -> Unit,
    onOpenMediaLibrary: () -> Unit,
    onQuickImportImageUri: (Uri) -> Unit,
    onQuickImportAudioUri: (Uri) -> Unit,
    onSelectClip: (String?) -> Unit,
    onCommitClipTranslation: (clipId: String, totalDx: Float, totalDy: Float) -> Unit,
    onCommitIkPose: (clipId: String, finalOffsets: PoseKeyframeOffsets) -> Unit = { _, _ -> },
    onCommitCameraPan: (totalDx: Float, totalDy: Float) -> Unit = { _, _ -> },
    onFinishStroke: (List<NormalizedPoint>) -> Unit,
    onBrushStyleChange: (String, Float) -> Unit,
    onToggleDrawingMode: () -> Unit,
    onZoomCanvas: (Float) -> Unit,
    onResetViewport: () -> Unit,
    onSelectScene: (Int) -> Unit,
    onAddScene: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onSeekFrame: (Int) -> Unit,
    onToggleLoop: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onSelectKeyframeNode: (clipId: String, globalFrame: Int) -> Unit,
    onCycleSelectedKeyframeEasing: () -> Unit,
    onAdjustTimelineZoom: (Float) -> Unit,
    onToggleLayerVisibility: (String) -> Unit,
    onToggleLayerLock: (String) -> Unit,
    onAddLayer: (LayerType, String) -> Unit,
    onRenameLayer: (String, String) -> Unit,
    onReorderLayer: (String, Boolean) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onUpdateTransform: ((TransformKeyframe) -> TransformKeyframe) -> Unit,
    onUpdateSelectedClip: ((TimelineClip) -> TimelineClip) -> Unit,
    onSplitClip: () -> Unit,
    onDuplicateClip: () -> Unit,
    onFlipClip: (Boolean) -> Unit,
    onDeleteClip: () -> Unit,
    onAddShape: (ShapeKind, String, String) -> Unit,
    onAddText: (String, String, Float) -> Unit,
    onSetBackgroundBundled: (String) -> Unit,
    onSetBackgroundColor: (String) -> Unit,
    onApplyTemplate: (String) -> Unit,
    onUpdateProjectSettings: (String, AspectRatioPreset, Int, Int, Int, Int, PreviewQuality) -> Unit,
    onStartExport: (ExportFormat, ExportResolution, Int, Int) -> Unit,
    onCancelExport: () -> Unit,
    onToggleIkPosingMode: () -> Unit = {},
    onToggleCameraKeyMode: () -> Unit = {},
    onStepAdjacentKeyframe: (Boolean) -> Unit = {},
    onStampPoseKey: () -> Unit = {},
    onResetPoseAtCurrentFrame: () -> Unit = {},
    onSetChromaKeyMode: (ChromaKeyMode) -> Unit = {},
    onSetCameraFocusClip: (String?) -> Unit = {},
    onUpdateCamera: ((CameraKeyframe) -> CameraKeyframe) -> Unit = {},
    onDeleteCameraKeyframeAtFrame: () -> Unit = {},
    onResetAllCameraKeyframes: () -> Unit = {},
    onImportCustomPropUri: (Uri, (String) -> Unit) -> Unit = { _, _ -> },
    onTrimAudioClip: (
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ) -> Unit = { _, _, _, _, _ -> },
    onResetAudioTrim: () -> Unit = {},
    onWaveformSensitivityChange: (Float) -> Unit = {},
    onSaveWaveformJsonToProject: (clipName: String, json: String, onResult: (String) -> Unit) -> Unit = { _, _, _ -> },
    onWriteWaveformJsonToSafUri: (uri: Uri, json: String, onResult: (String) -> Unit) -> Unit = { _, _, _ -> }
) {
    // Non-exported stage composition grid toggle & resizable canvas/timeline split
    var showGridOverlay by remember { mutableStateOf(false) }
    var canvasSplitFraction by remember { mutableFloatStateOf(0.52f) }
    var isDraggingSplitHandle by remember { mutableStateOf(false) }

    // Smart back handling: exit active editing modes or deselect clip before exiting editor
    BackHandler {
        when {
            state.isDrawingMode -> onToggleDrawingMode()
            state.isIkPosingMode -> onToggleIkPosingMode()
            state.isCameraKeyMode -> onToggleCameraKeyMode()
            state.selectedClipId != null -> onSelectClip(null)
            else -> onBack()
        }
    }

    val quickImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onQuickImportImageUri(uri)
    }

    val quickAudioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onQuickImportAudioUri(uri)
    }

    val customPropPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onImportCustomPropUri(uri) { savedPath ->
                onUpdateSelectedClip { clip ->
                    clip.copy(
                        propType = CharacterPropType.CUSTOM_PNG,
                        customPropImagePath = savedPath
                    )
                }
            }
        }
    }

    val activeScene = state.project.activeScene
    val audioLayer = remember(activeScene.layers) {
        activeScene.layers.firstOrNull { it.type == LayerType.AUDIO }
    }
    val audioClip = remember(activeScene.clips, audioLayer) {
        activeScene.clips.firstOrNull { clip ->
            !clip.audioPath.isNullOrBlank() || (audioLayer != null && clip.layerId == audioLayer.id)
        }
    }
    val isAudioMuted = audioLayer?.isVisible == false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioObsidian)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // A. Compact Single-Row Top Toolbar (~50dp)
            EditorTopToolbar(
                project = state.project,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onBack = onBack,
                onOpenSettings = { onOpenSheet(ActiveEditorSheet.PROJECT_SETTINGS) },
                onToggleOnionSkin = onToggleOnionSkin,
                onUndo = onUndo,
                onRedo = onRedo,
                onSave = onSave,
                onOpenExport = { onOpenSheet(ActiveEditorSheet.EXPORT_DIALOG) },
                onResetProject = onResetProject,
                onOpenGuide = { onOpenSheet(ActiveEditorSheet.TUTORIAL_GUIDE) }
            )

            // B. Adaptive Portrait (Resizable Split) vs Landscape Workspace
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isLandscapeWide = maxWidth > maxHeight && maxWidth >= 600.dp
                val workspaceHeightPx = with(LocalDensity.current) { maxHeight.toPx().coerceAtLeast(240f) }

                if (isLandscapeWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        EditorCanvasPane(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            playbackFrameFlow = playbackFrameFlow,
                            bitmapVersionFlow = bitmapVersionFlow,
                            isPlaying = state.isPlaying,
                            charactersById = state.charactersById,
                            selectedClipId = state.selectedClipId,
                            canvasZoom = state.canvasZoom,
                            canvasPanX = state.canvasPanX,
                            canvasPanY = state.canvasPanY,
                            isDrawingMode = state.isDrawingMode,
                            isIkPosingMode = state.isIkPosingMode,
                            isCameraKeyMode = state.isCameraKeyMode,
                            showGridOverlay = showGridOverlay,
                            brushColorHex = state.brushColorHex,
                            brushWidthRatio = state.brushWidthRatio,
                            sceneRenderer = sceneRenderer,
                            onSelectClip = onSelectClip,
                            onCommitClipTranslation = onCommitClipTranslation,
                            onCommitIkPose = onCommitIkPose,
                            onCommitCameraPan = onCommitCameraPan,
                            onFinishStroke = onFinishStroke,
                            onBrushStyleChange = onBrushStyleChange,
                            onExitDrawingMode = onToggleDrawingMode,
                            onZoomBy = onZoomCanvas,
                            onResetViewport = onResetViewport,
                            onQuickAddCharacter = onOpenCharacterLibrary,
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                        )
                        VerticalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                        TimelinePanel(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            playbackFrameFlow = playbackFrameFlow,
                            isPlaying = state.isPlaying,
                            isLooping = state.isLooping,
                            selectedClipId = state.selectedClipId,
                            selectedLayerId = state.selectedLayerId,
                            timelineZoom = state.timelineZoom,
                            onSelectScene = onSelectScene,
                            onAddScene = onAddScene,
                            onTogglePlayback = onTogglePlayback,
                            onSeekFrame = onSeekFrame,
                            onToggleLoop = onToggleLoop,
                            onToggleKeyframe = onToggleKeyframe,
                            onSelectKeyframeNode = onSelectKeyframeNode,
                            onCycleSelectedKeyframeEasing = onCycleSelectedKeyframeEasing,
                            onAdjustTimelineZoom = onAdjustTimelineZoom,
                            onSelectClip = { onSelectClip(it) },
                            onToggleLayerVisibility = onToggleLayerVisibility,
                            onToggleLayerLock = onToggleLayerLock,
                            onOpenLayerManager = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                            onTrimAudioClip = onTrimAudioClip,
                            waveformSensitivity = state.waveformSensitivity,
                            onWaveformSensitivityChange = onWaveformSensitivityChange,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait Resizable Split: Default ~45% Canvas / ~42% Timeline of viewport height
                    Column(modifier = Modifier.fillMaxSize()) {
                        EditorCanvasPane(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            playbackFrameFlow = playbackFrameFlow,
                            bitmapVersionFlow = bitmapVersionFlow,
                            isPlaying = state.isPlaying,
                            charactersById = state.charactersById,
                            selectedClipId = state.selectedClipId,
                            canvasZoom = state.canvasZoom,
                            canvasPanX = state.canvasPanX,
                            canvasPanY = state.canvasPanY,
                            isDrawingMode = state.isDrawingMode,
                            isIkPosingMode = state.isIkPosingMode,
                            isCameraKeyMode = state.isCameraKeyMode,
                            showGridOverlay = showGridOverlay,
                            brushColorHex = state.brushColorHex,
                            brushWidthRatio = state.brushWidthRatio,
                            sceneRenderer = sceneRenderer,
                            onSelectClip = onSelectClip,
                            onCommitClipTranslation = onCommitClipTranslation,
                            onCommitIkPose = onCommitIkPose,
                            onCommitCameraPan = onCommitCameraPan,
                            onFinishStroke = onFinishStroke,
                            onBrushStyleChange = onBrushStyleChange,
                            onExitDrawingMode = onToggleDrawingMode,
                            onZoomBy = onZoomCanvas,
                            onResetViewport = onResetViewport,
                            onQuickAddCharacter = onOpenCharacterLibrary,
                            modifier = Modifier
                                .weight(canvasSplitFraction)
                                .fillMaxWidth()
                        )

                        // Slim Horizontal Drag Handle to Dynamically Resize Canvas vs. Timeline
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(StudioCharcoal)
                                .pointerInput(workspaceHeightPx) {
                                    detectVerticalDragGestures(
                                        onDragStart = { isDraggingSplitHandle = true },
                                        onDragEnd = { isDraggingSplitHandle = false },
                                        onDragCancel = { isDraggingSplitHandle = false }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val deltaFraction = dragAmount / workspaceHeightPx
                                        canvasSplitFraction = (canvasSplitFraction + deltaFraction).coerceIn(0.28f, 0.72f)
                                    }
                                }
                                .testTag("canvas_timeline_drag_handle")
                        ) {
                            HorizontalDivider(
                                color = StudioBorderSubtle,
                                thickness = 1.dp,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                            Box(
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isDraggingSplitHandle) ElectricCyan else StudioBorder)
                            )
                        }

                        TimelinePanel(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            playbackFrameFlow = playbackFrameFlow,
                            isPlaying = state.isPlaying,
                            isLooping = state.isLooping,
                            selectedClipId = state.selectedClipId,
                            selectedLayerId = state.selectedLayerId,
                            timelineZoom = state.timelineZoom,
                            onSelectScene = onSelectScene,
                            onAddScene = onAddScene,
                            onTogglePlayback = onTogglePlayback,
                            onSeekFrame = onSeekFrame,
                            onToggleLoop = onToggleLoop,
                            onToggleKeyframe = onToggleKeyframe,
                            onSelectKeyframeNode = onSelectKeyframeNode,
                            onCycleSelectedKeyframeEasing = onCycleSelectedKeyframeEasing,
                            onAdjustTimelineZoom = onAdjustTimelineZoom,
                            onSelectClip = { onSelectClip(it) },
                            onToggleLayerVisibility = onToggleLayerVisibility,
                            onToggleLayerLock = onToggleLayerLock,
                            onOpenLayerManager = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                            onTrimAudioClip = onTrimAudioClip,
                            waveformSensitivity = state.waveformSensitivity,
                            onWaveformSensitivityChange = onWaveformSensitivityChange,
                            modifier = Modifier
                                .weight(1f - canvasSplitFraction)
                                .fillMaxWidth()
                        )
                    }
                }
            }

            // C. CapCut Pro-Style Flat Hierarchical Bottom Toolbar (Zero AI tools)
            BottomEditingToolbar(
                selectedClip = state.selectedClip,
                isDrawingMode = state.isDrawingMode,
                isIkPosingMode = state.isIkPosingMode,
                isCameraKeyMode = state.isCameraKeyMode,
                onionSkinEnabled = state.project.onionSkinEnabled,
                showMotionPaths = state.project.showMotionPaths,
                showGridOverlay = showGridOverlay,
                isAudioMuted = isAudioMuted,
                onDeselectClip = { onSelectClip(null) },
                onOpenCharacters = onOpenCharacterLibrary,
                onOpenPresetsForSelectedChar = {
                    val clip = state.selectedClip
                    val charId = clip?.characterId
                    if (clip != null && charId != null) {
                        onOpenPresetsForSelectedChar(charId, clip.id)
                    }
                },
                onToggleIkPosing = onToggleIkPosingMode,
                onStampPoseKey = onStampPoseKey,
                onStepAdjacentKeyframe = onStepAdjacentKeyframe,
                onResetPoseAtCurrentFrame = onResetPoseAtCurrentFrame,
                onOpenCharacterActingProps = { onOpenSheet(ActiveEditorSheet.CHARACTER_ACTING_PROPS) },
                onToggleCameraKeyMode = onToggleCameraKeyMode,
                onOpenCameraChroma = { onOpenSheet(ActiveEditorSheet.CAMERA_CHROMA_STUDIO) },
                onOpenMedia = onOpenMediaLibrary,
                onOpenTemplates = { onOpenSheet(ActiveEditorSheet.TEMPLATES) },
                onOpenSettings = { onOpenSheet(ActiveEditorSheet.PROJECT_SETTINGS) },
                onAddScene = onAddScene,
                onQuickAddImage = {
                    quickImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onQuickAddAudio = {
                    quickAudioPicker.launch(arrayOf("audio/*"))
                },
                onToggleAudioMute = {
                    if (audioLayer != null) {
                        onToggleLayerVisibility(audioLayer.id)
                    } else {
                        quickAudioPicker.launch(arrayOf("audio/*"))
                    }
                },
                onOpenAudioSpeed = {
                    if (audioClip != null) {
                        onSelectClip(audioClip.id)
                        onOpenSheet(ActiveEditorSheet.SPEED_SETTINGS)
                    } else {
                        quickAudioPicker.launch(arrayOf("audio/*"))
                    }
                },
                onExportWaveformJson = {
                    val targetAudio = state.selectedClip?.takeIf { !it.audioPath.isNullOrBlank() } ?: audioClip
                    if (targetAudio != null) {
                        onSelectClip(targetAudio.id)
                        onOpenSheet(ActiveEditorSheet.WAVEFORM_EXPORT)
                    } else {
                        quickAudioPicker.launch(arrayOf("audio/*"))
                    }
                },
                onResetAudioTrim = onResetAudioTrim,
                onOpenShapes = { onOpenSheet(ActiveEditorSheet.ADD_SHAPE) },
                onOpenText = { onOpenSheet(ActiveEditorSheet.ADD_TEXT) },
                onToggleDrawing = onToggleDrawingMode,
                onOpenBackground = { onOpenSheet(ActiveEditorSheet.BACKGROUND_PICKER) },
                onOpenLayers = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                onToggleOnionSkin = onToggleOnionSkin,
                onToggleGridOverlay = { showGridOverlay = !showGridOverlay },
                onOpenTransform = { onOpenSheet(ActiveEditorSheet.TRANSFORM_INSPECTOR) },
                onOpenSpeed = { onOpenSheet(ActiveEditorSheet.SPEED_SETTINGS) },
                onToggleMotionPath = onToggleMotionPaths,
                onOpenTransitions = { onOpenSheet(ActiveEditorSheet.TRANSITIONS) },
                onOpenSceneTransitions = {
                    val targetClip = state.selectedClip ?: activeScene.clips.firstOrNull()
                    if (targetClip != null) {
                        onSelectClip(targetClip.id)
                        onOpenSheet(ActiveEditorSheet.TRANSITIONS)
                    } else {
                        onOpenSheet(ActiveEditorSheet.CAMERA_CHROMA_STUDIO)
                    }
                },
                onSplitClip = onSplitClip,
                onDuplicateClip = onDuplicateClip,
                onFlipHorizontal = { onFlipClip(true) },
                onDeleteClip = onDeleteClip
            )
        }

        // Floating Status Pill Feedback
        AnimatedVisibility(
            visible = state.statusBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 52.dp)
        ) {
            state.statusBanner?.let { msg ->
                Surface(
                    color = StudioObsidian.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, ElectricCyan)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }

    // Active Editor Modals & Inspectors (100% preserved)
    when (state.activeSheet) {
        ActiveEditorSheet.NONE -> Unit
        ActiveEditorSheet.PROJECT_SETTINGS -> {
            ProjectSettingsDialog(
                project = state.project,
                onDismiss = onCloseSheet,
                onApply = onUpdateProjectSettings
            )
        }
        ActiveEditorSheet.TRANSFORM_INSPECTOR -> {
            state.selectedClip?.let { clip ->
                TransformInspectorDialog(
                    clip = clip,
                    currentFrame = state.currentFrame,
                    onUpdateTransform = onUpdateTransform,
                    onFlipHorizontal = { onFlipClip(true) },
                    onFlipVertical = { onFlipClip(false) },
                    onUpdateClip = onUpdateSelectedClip,
                    isIkPosingMode = state.isIkPosingMode,
                    onToggleIkPosingMode = onToggleIkPosingMode,
                    onDismiss = onCloseSheet
                )
            }
        }
        ActiveEditorSheet.CHARACTER_ACTING_PROPS -> {
            state.selectedClip?.let { clip ->
                CharacterActingAndPropsDialog(
                    clip = clip,
                    currentFrame = state.currentFrame,
                    isIkPosingMode = state.isIkPosingMode,
                    onToggleIkPosingMode = onToggleIkPosingMode,
                    onUpdateClip = onUpdateSelectedClip,
                    onUpdateTransform = onUpdateTransform,
                    onResetPoseAtFrame = onResetPoseAtCurrentFrame,
                    onImportCustomPropPng = { _ ->
                        customPropPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onFlipHorizontal = { onFlipClip(true) },
                    onFlipVertical = { onFlipClip(false) },
                    onDismiss = onCloseSheet
                )
            }
        }
        ActiveEditorSheet.CAMERA_CHROMA_STUDIO -> {
            CameraAndChromaKeyDialog(
                scene = state.project.activeScene,
                currentFrame = state.currentFrame,
                isCameraKeyMode = state.isCameraKeyMode,
                onToggleCameraKeyMode = onToggleCameraKeyMode,
                onSetChromaKeyMode = onSetChromaKeyMode,
                onSetCameraFocusClip = onSetCameraFocusClip,
                onUpdateCamera = onUpdateCamera,
                onDeleteCameraKeyframeAtFrame = onDeleteCameraKeyframeAtFrame,
                onResetAllCameraKeyframes = onResetAllCameraKeyframes,
                onDismiss = onCloseSheet
            )
        }
        ActiveEditorSheet.SPEED_SETTINGS -> {
            state.selectedClip?.let { clip ->
                SpeedAndDurationDialog(
                    clip = clip,
                    onUpdateClip = onUpdateSelectedClip,
                    onDismiss = onCloseSheet
                )
            }
        }
        ActiveEditorSheet.TRANSITIONS -> {
            state.selectedClip?.let { clip ->
                TransitionsDialog(
                    clip = clip,
                    onUpdateClip = onUpdateSelectedClip,
                    onDismiss = onCloseSheet
                )
            }
        }
        ActiveEditorSheet.LAYER_MANAGER -> {
            LayerManagerDialog(
                layers = state.project.activeScene.layers,
                onAddLayer = onAddLayer,
                onRenameLayer = onRenameLayer,
                onReorderLayer = onReorderLayer,
                onToggleVisibility = onToggleLayerVisibility,
                onToggleLock = onToggleLayerLock,
                onDeleteLayer = onDeleteLayer,
                onDismiss = onCloseSheet
            )
        }
        ActiveEditorSheet.ADD_SHAPE -> {
            AddShapeDialog(onAddShape = onAddShape, onDismiss = onCloseSheet)
        }
        ActiveEditorSheet.ADD_TEXT -> {
            AddTextDialog(onAddText = onAddText, onDismiss = onCloseSheet)
        }
        ActiveEditorSheet.BACKGROUND_PICKER -> {
            BackgroundPickerDialog(
                onSelectBundled = onSetBackgroundBundled,
                onSelectSolidColor = onSetBackgroundColor,
                onOpenMediaLibrary = onOpenMediaLibrary,
                onDismiss = onCloseSheet
            )
        }
        ActiveEditorSheet.TEMPLATES -> {
            TemplatesPickerDialog(onApplyTemplate = onApplyTemplate, onDismiss = onCloseSheet)
        }
        ActiveEditorSheet.EXPORT_DIALOG -> {
            OfflineExportDialog(
                project = state.project,
                exportState = state.exportState,
                onStartExport = onStartExport,
                onCancelExport = onCancelExport,
                onDismiss = onCloseSheet
            )
        }
        ActiveEditorSheet.WAVEFORM_EXPORT -> {
            val targetAudio = state.selectedClip?.takeIf { !it.audioPath.isNullOrBlank() } ?: audioClip
            if (targetAudio != null) {
                WaveformExportDialog(
                    project = state.project,
                    audioClip = targetAudio,
                    waveformSensitivity = state.waveformSensitivity,
                    onSaveToProject = onSaveWaveformJsonToProject,
                    onSaveToSafUri = onWriteWaveformJsonToSafUri,
                    onDismiss = onCloseSheet
                )
            } else {
                onCloseSheet()
            }
        }
        ActiveEditorSheet.TUTORIAL_GUIDE -> {
            StudioGuideDialog(onDismiss = onCloseSheet)
        }
    }
}
