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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.ActiveEditorSheet
import com.example.viewmodel.StudioUiState

@Composable
fun EditorScreen(
    state: StudioUiState,
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
    onDragSelectedClip: (Float, Float) -> Unit,
    onCommitDrag: () -> Unit,
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
    onStartExport: (ExportFormat, ExportResolution, Int, Int) -> Unit
) {
    BackHandler { onBack() }

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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // A. Top Toolbar
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

            // Adaptive Portrait vs Landscape / Tablet Workspace
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isLandscapeWide = maxWidth > maxHeight && maxWidth >= 600.dp

                if (isLandscapeWide) {
                    // Landscape Spacious Split: Larger Canvas on Top/Left + Side-by-Side Timeline
                    Row(modifier = Modifier.fillMaxSize()) {
                        EditorCanvasPane(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            charactersById = state.charactersById,
                            selectedClipId = state.selectedClipId,
                            canvasZoom = state.canvasZoom,
                            canvasPanX = state.canvasPanX,
                            canvasPanY = state.canvasPanY,
                            isDrawingMode = state.isDrawingMode,
                            brushColorHex = state.brushColorHex,
                            brushWidthRatio = state.brushWidthRatio,
                            sceneRenderer = sceneRenderer,
                            onSelectClip = onSelectClip,
                            onDragSelectedClip = onDragSelectedClip,
                            onCommitDrag = onCommitDrag,
                            onFinishStroke = onFinishStroke,
                            onBrushStyleChange = onBrushStyleChange,
                            onExitDrawingMode = onToggleDrawingMode,
                            onZoomIn = { onZoomCanvas(1.2f) },
                            onZoomOut = { onZoomCanvas(0.84f) },
                            onResetViewport = onResetViewport,
                            onQuickAddCharacter = onOpenCharacterLibrary,
                            modifier = Modifier.weight(0.58f).fillMaxHeight()
                        )
                        TimelinePanel(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            isPlaying = state.isPlaying,
                            isLooping = state.isLooping,
                            selectedClipId = state.selectedClipId,
                            selectedLayerId = state.selectedLayerId,
                            timelineZoom = state.timelineZoom,
                            onSelectScene = onSelectScene,
                            onAddScene = onAddScene,
                            onTogglePlayback = onTogglePlayback,
                            onStepFrame = onStepFrame,
                            onSeekFrame = onSeekFrame,
                            onToggleLoop = onToggleLoop,
                            onToggleKeyframe = onToggleKeyframe,
                            onSelectKeyframeNode = onSelectKeyframeNode,
                            onCycleSelectedKeyframeEasing = onCycleSelectedKeyframeEasing,
                            onOpenTransformForKeyframe = { onOpenSheet(ActiveEditorSheet.TRANSFORM_INSPECTOR) },
                            onAdjustTimelineZoom = onAdjustTimelineZoom,
                            onSelectClip = { onSelectClip(it) },
                            onToggleLayerVisibility = onToggleLayerVisibility,
                            onToggleLayerLock = onToggleLayerLock,
                            onOpenLayerManager = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                            modifier = Modifier.weight(0.42f).fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait Stacked Studio Layout (Canvas Upper 56%, Timeline Lower 44%)
                    Column(modifier = Modifier.fillMaxSize()) {
                        EditorCanvasPane(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            charactersById = state.charactersById,
                            selectedClipId = state.selectedClipId,
                            canvasZoom = state.canvasZoom,
                            canvasPanX = state.canvasPanX,
                            canvasPanY = state.canvasPanY,
                            isDrawingMode = state.isDrawingMode,
                            brushColorHex = state.brushColorHex,
                            brushWidthRatio = state.brushWidthRatio,
                            sceneRenderer = sceneRenderer,
                            onSelectClip = onSelectClip,
                            onDragSelectedClip = onDragSelectedClip,
                            onCommitDrag = onCommitDrag,
                            onFinishStroke = onFinishStroke,
                            onBrushStyleChange = onBrushStyleChange,
                            onExitDrawingMode = onToggleDrawingMode,
                            onZoomIn = { onZoomCanvas(1.2f) },
                            onZoomOut = { onZoomCanvas(0.84f) },
                            onResetViewport = onResetViewport,
                            onQuickAddCharacter = onOpenCharacterLibrary,
                            modifier = Modifier.weight(0.55f).fillMaxWidth()
                        )
                        TimelinePanel(
                            project = state.project,
                            currentFrame = state.currentFrame,
                            isPlaying = state.isPlaying,
                            isLooping = state.isLooping,
                            selectedClipId = state.selectedClipId,
                            selectedLayerId = state.selectedLayerId,
                            timelineZoom = state.timelineZoom,
                            onSelectScene = onSelectScene,
                            onAddScene = onAddScene,
                            onTogglePlayback = onTogglePlayback,
                            onStepFrame = onStepFrame,
                            onSeekFrame = onSeekFrame,
                            onToggleLoop = onToggleLoop,
                            onToggleKeyframe = onToggleKeyframe,
                            onSelectKeyframeNode = onSelectKeyframeNode,
                            onCycleSelectedKeyframeEasing = onCycleSelectedKeyframeEasing,
                            onOpenTransformForKeyframe = { onOpenSheet(ActiveEditorSheet.TRANSFORM_INSPECTOR) },
                            onAdjustTimelineZoom = onAdjustTimelineZoom,
                            onSelectClip = { onSelectClip(it) },
                            onToggleLayerVisibility = onToggleLayerVisibility,
                            onToggleLayerLock = onToggleLayerLock,
                            onOpenLayerManager = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                            modifier = Modifier.weight(0.45f).fillMaxWidth()
                        )
                    }
                }
            }

            // Bottom Context-Aware Editing Toolbar (Zero AI tools!)
            BottomEditingToolbar(
                selectedClip = state.selectedClip,
                isDrawingMode = state.isDrawingMode,
                showMotionPaths = state.project.showMotionPaths,
                onOpenGuide = { onOpenSheet(ActiveEditorSheet.TUTORIAL_GUIDE) },
                onOpenCharacters = onOpenCharacterLibrary,
                onOpenPresetsForSelectedChar = {
                    val clip = state.selectedClip
                    val charId = clip?.characterId
                    if (clip != null && charId != null) {
                        onOpenPresetsForSelectedChar(charId, clip.id)
                    }
                },
                onOpenMedia = onOpenMediaLibrary,
                onOpenTemplates = { onOpenSheet(ActiveEditorSheet.TEMPLATES) },
                onQuickAddImage = {
                    quickImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onQuickAddAudio = {
                    quickAudioPicker.launch(arrayOf("audio/*"))
                },
                onOpenShapes = { onOpenSheet(ActiveEditorSheet.ADD_SHAPE) },
                onOpenText = { onOpenSheet(ActiveEditorSheet.ADD_TEXT) },
                onToggleDrawing = onToggleDrawingMode,
                onOpenBackground = { onOpenSheet(ActiveEditorSheet.BACKGROUND_PICKER) },
                onOpenLayers = { onOpenSheet(ActiveEditorSheet.LAYER_MANAGER) },
                onOpenTransform = { onOpenSheet(ActiveEditorSheet.TRANSFORM_INSPECTOR) },
                onOpenSpeed = { onOpenSheet(ActiveEditorSheet.SPEED_SETTINGS) },
                onToggleMotionPath = onToggleMotionPaths,
                onOpenTransitions = { onOpenSheet(ActiveEditorSheet.TRANSITIONS) },
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
                .padding(top = 56.dp)
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

    // Active Editor Modals & Inspectors
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
                    onDismiss = onCloseSheet
                )
            }
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
                onDismiss = onCloseSheet
            )
        }
        ActiveEditorSheet.TUTORIAL_GUIDE -> {
            StudioGuideDialog(onDismiss = onCloseSheet)
        }
    }
}
