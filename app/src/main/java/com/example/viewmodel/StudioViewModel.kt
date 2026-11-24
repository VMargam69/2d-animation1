package com.example.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BuiltInCatalog
import com.example.data.LocalAssetStorage
import com.example.data.StudioRepository
import com.example.data.db.FrameCraftDatabase
import com.example.data.db.ProjectSummary
import com.example.engine.AnimationExporter
import com.example.engine.ExportOutput
import com.example.engine.ExportRequest
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.navigation.HashRoute
import com.example.navigation.HashRouter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class StudioDestination {
    PROJECTS_HOME,
    EDITOR,
    CHARACTER_LIBRARY,
    ANIMATION_LIBRARY,
    MEDIA_LIBRARY
}

enum class ActiveEditorSheet {
    NONE,
    PROJECT_SETTINGS,
    TRANSFORM_INSPECTOR,
    SPEED_SETTINGS,
    TRANSITIONS,
    LAYER_MANAGER,
    ADD_SHAPE,
    ADD_TEXT,
    BACKGROUND_PICKER,
    TEMPLATES,
    EXPORT_DIALOG,
    TUTORIAL_GUIDE
}

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Exporting(val currentFrame: Int, val totalFrames: Int, val format: ExportFormat) : ExportUiState
    data class Success(val output: ExportOutput) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

data class StudioUiState(
    val destination: StudioDestination = StudioDestination.PROJECTS_HOME,
    val returnDestinationAfterLibrary: StudioDestination = StudioDestination.EDITOR,
    val project: ProjectDocument = BuiltInCatalog.sceneTemplates.first().buildProject("Village Sunset Walk"),
    val projects: List<ProjectSummary> = emptyList(),
    val characters: List<CharacterRigDefinition> = BuiltInCatalog.builtInCharacters,
    val animationPresets: List<AnimationPreset> = BuiltInCatalog.animationPresets,
    val mediaAssets: List<LocalMediaAsset> = BuiltInCatalog.bundledMediaAssets,
    val selectedCharacterIdForLibrary: String? = null,
    val editingClipForAnimationSwapId: String? = null,
    val selectedClipId: String? = null,
    val selectedLayerId: String? = null,
    val currentFrame: Int = 0,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = true,
    val canvasZoom: Float = 1f,
    val canvasPanX: Float = 0f,
    val canvasPanY: Float = 0f,
    val timelineZoom: Float = 1f,
    val isDrawingMode: Boolean = false,
    val brushColorHex: String = "#38BDF8",
    val brushWidthRatio: Float = 0.009f,
    val activeSheet: ActiveEditorSheet = ActiveEditorSheet.NONE,
    val exportState: ExportUiState = ExportUiState.Idle,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val statusBanner: String? = null,
    val hasLastRecoverableProject: Boolean = false,
    val charactersById: Map<String, CharacterRigDefinition> = emptyMap()
) {
    val selectedClip: TimelineClip?
        get() = project.activeScene.clips.firstOrNull { it.id == selectedClipId }
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    val router = HashRouter(HashRoute.HASH_PROJECTS)

    private val db = FrameCraftDatabase.getInstance(application)
    val assetStorage = LocalAssetStorage(application)
    val repository = StudioRepository(
        projectDao = db.projectDao(),
        characterDao = db.characterDao(),
        animationPresetDao = db.animationPresetDao(),
        mediaAssetDao = db.mediaAssetDao(),
        assetStorage = assetStorage
    )

    val sceneRenderer = SceneRenderer { path, bundledKey ->
        when {
            !path.isNullOrBlank() -> assetStorage.loadBitmapFromFile(path)
            !bundledKey.isNullOrBlank() -> assetStorage.loadBundledBackgroundBitmap(bundledKey)
            else -> null
        }
    }

    private val exporter = AnimationExporter(application, sceneRenderer)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    /**
     * Dedicated high-frequency frame stream updated during video playback without triggering
     * full-screen Compose recompositions of toolbars, layer panels, or clip lists.
     */
    private val _playbackFrame = MutableStateFlow(0)
    val playbackFrame: StateFlow<Int> = _playbackFrame.asStateFlow()

    private val undoStack = ArrayDeque<ProjectDocument>()
    private val redoStack = ArrayDeque<ProjectDocument>()
    private var playbackJob: Job? = null
    private var exportJob: Job? = null
    private var statusClearJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            val last = repository.recoverLastOpenedProject()
            if (last != null) {
                _uiState.update {
                    it.copy(
                        project = last,
                        selectedClipId = last.activeScene.clips.firstOrNull { c -> c.characterId != null }?.id,
                        hasLastRecoverableProject = true
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.projectSummaries.collect { list ->
                _uiState.update {
                    it.copy(
                        projects = list,
                        hasLastRecoverableProject = list.any { p -> p.isLastOpened }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.characters.collect { chars ->
                _uiState.update { it.copy(characters = chars, charactersById = chars.associateBy { c -> c.id }) }
            }
        }
        viewModelScope.launch {
            repository.animationPresets.collect { presets ->
                _uiState.update { it.copy(animationPresets = presets) }
            }
        }
        viewModelScope.launch {
            repository.mediaAssets.collect { assets ->
                _uiState.update { it.copy(mediaAssets = assets) }
            }
        }
    }

    // --- HashRouter Navigation ---
    fun navigateTo(destination: StudioDestination) {
        stopPlayback()
        val route = when (destination) {
            StudioDestination.PROJECTS_HOME -> HashRoute.ProjectDashboard
            StudioDestination.EDITOR -> HashRoute.AnimationEditor(_uiState.value.project.id)
            StudioDestination.CHARACTER_LIBRARY -> HashRoute.CharacterLibrary
            StudioDestination.ANIMATION_LIBRARY -> HashRoute.AnimationLibrary(
                characterId = _uiState.value.selectedCharacterIdForLibrary,
                swapClipId = _uiState.value.editingClipForAnimationSwapId
            )
            StudioDestination.MEDIA_LIBRARY -> HashRoute.MediaLibrary
        }
        router.navigate(route)
        _uiState.update {
            it.copy(
                destination = destination,
                activeSheet = ActiveEditorSheet.NONE,
                isDrawingMode = false
            )
        }
    }

    fun openCharacterLibrary(from: StudioDestination = StudioDestination.EDITOR) {
        stopPlayback()
        router.navigate(HashRoute.CharacterLibrary)
        _uiState.update {
            it.copy(
                returnDestinationAfterLibrary = from,
                editingClipForAnimationSwapId = null,
                destination = StudioDestination.CHARACTER_LIBRARY,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
    }

    fun openAnimationLibraryForCharacter(characterId: String, swapClipId: String? = null) {
        stopPlayback()
        router.navigate(HashRoute.AnimationLibrary(characterId = characterId, swapClipId = swapClipId))
        _uiState.update {
            it.copy(
                selectedCharacterIdForLibrary = characterId,
                editingClipForAnimationSwapId = swapClipId,
                destination = StudioDestination.ANIMATION_LIBRARY
            )
        }
    }

    fun openMediaLibrary(from: StudioDestination = StudioDestination.EDITOR) {
        stopPlayback()
        router.navigate(HashRoute.MediaLibrary)
        _uiState.update {
            it.copy(
                returnDestinationAfterLibrary = from,
                destination = StudioDestination.MEDIA_LIBRARY,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value
        if (current.activeSheet != ActiveEditorSheet.NONE) {
            closeSheet()
            return true
        }
        stopPlayback()
        if (router.currentRoute is HashRoute.AnimationEditor) {
            saveCurrentProject(silent = true)
        }
        val popped = router.popBackStack()
        if (popped) {
            val dest = when (router.currentRoute) {
                is HashRoute.ProjectDashboard -> StudioDestination.PROJECTS_HOME
                is HashRoute.CharacterLibrary -> StudioDestination.CHARACTER_LIBRARY
                is HashRoute.AnimationLibrary -> StudioDestination.ANIMATION_LIBRARY
                is HashRoute.AnimationEditor -> StudioDestination.EDITOR
                is HashRoute.MediaLibrary -> StudioDestination.MEDIA_LIBRARY
            }
            _uiState.update { it.copy(destination = dest, isDrawingMode = false) }
        }
        return popped
    }

    // --- Project Management ---
    fun createNewProject(name: String, templateId: String = "tpl_blank_stage") {
        val template = BuiltInCatalog.sceneTemplates.firstOrNull { it.id == templateId }
            ?: BuiltInCatalog.sceneTemplates.last()
        val doc = template.buildProject(name.trim().ifBlank { "Untitled Animation" })
        viewModelScope.launch {
            val saved = repository.saveProject(doc, markAsLastOpened = true)
            clearHistory()
            router.navigate(HashRoute.AnimationEditor(saved.id))
            _playbackFrame.value = 0
            _uiState.update {
                it.copy(
                    project = saved,
                    currentFrame = 0,
                    selectedClipId = saved.activeScene.clips.firstOrNull { c -> c.characterId != null }?.id,
                    destination = StudioDestination.EDITOR,
                    activeSheet = ActiveEditorSheet.NONE,
                    canvasZoom = 1f,
                    canvasPanX = 0f,
                    canvasPanY = 0f
                )
            }
            showStatus("Created \"${saved.name}\"")
        }
    }

    fun openProject(projectId: String) {
        viewModelScope.launch {
            val loaded = repository.loadProject(projectId) ?: return@launch
            clearHistory()
            router.navigate(HashRoute.AnimationEditor(loaded.id))
            _playbackFrame.value = 0
            _uiState.update {
                it.copy(
                    project = loaded,
                    currentFrame = 0,
                    selectedClipId = loaded.activeScene.clips.firstOrNull { c -> c.characterId != null }?.id,
                    destination = StudioDestination.EDITOR,
                    activeSheet = ActiveEditorSheet.NONE,
                    canvasZoom = 1f,
                    canvasPanX = 0f,
                    canvasPanY = 0f
                )
            }
        }
    }

    fun recoverLastProject() {
        viewModelScope.launch {
            val last = repository.recoverLastOpenedProject() ?: return@launch
            clearHistory()
            router.navigate(HashRoute.AnimationEditor(last.id))
            _playbackFrame.value = 0
            _uiState.update {
                it.copy(
                    project = last,
                    currentFrame = 0,
                    destination = StudioDestination.EDITOR
                )
            }
            showStatus("Recovered \"${last.name}\"")
        }
    }

    fun saveCurrentProject(silent: Boolean = false) {
        val current = _uiState.value.project
        viewModelScope.launch {
            val saved = repository.saveProject(current, markAsLastOpened = true)
            _uiState.update { it.copy(project = saved) }
            if (!silent) showStatus("Saved locally offline")
        }
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch {
            repository.renameProject(projectId, newName)
            if (_uiState.value.project.id == projectId) {
                _uiState.update { it.copy(project = it.project.copy(name = newName.trim().ifBlank { it.project.name })) }
            }
            showStatus("Project renamed")
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            repository.duplicateProject(projectId)
            showStatus("Project duplicated")
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            showStatus("Project deleted")
        }
    }

    fun resetCurrentProject() {
        val cur = _uiState.value.project
        val fresh = BuiltInCatalog.sceneTemplates.last().buildProject(cur.name).copy(
            id = cur.id,
            width = cur.width,
            height = cur.height,
            aspectRatioPreset = cur.aspectRatioPreset,
            fps = cur.fps
        )
        commitProjectChange(fresh)
        _playbackFrame.value = 0
        _uiState.update { it.copy(currentFrame = 0, selectedClipId = null) }
        showStatus("Stage cleared")
    }

    fun applyTemplateToCurrentProject(templateId: String) {
        val template = BuiltInCatalog.sceneTemplates.firstOrNull { it.id == templateId } ?: return
        val cur = _uiState.value.project
        val built = template.buildProject(cur.name).copy(id = cur.id, createdAt = cur.createdAt)
        commitProjectChange(built)
        _playbackFrame.value = 0
        _uiState.update {
            it.copy(
                currentFrame = 0,
                selectedClipId = built.activeScene.clips.firstOrNull { c -> c.characterId != null }?.id,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
        showStatus("Applied template: ${template.title}")
    }

    // --- Character & Animation Library ---
    fun togglePinCharacter(character: CharacterRigDefinition) {
        viewModelScope.launch {
            repository.togglePinCharacter(character.id, character.isPinned)
        }
    }

    fun saveCharacter(character: CharacterRigDefinition) {
        viewModelScope.launch {
            repository.saveCharacter(character)
            showStatus("Saved character \"${character.name}\"")
        }
    }

    fun deleteCustomCharacter(character: CharacterRigDefinition) {
        viewModelScope.launch {
            repository.deleteCustomCharacter(character)
            showStatus("Deleted custom character")
        }
    }

    fun importCharacterSpriteUri(uri: Uri, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val res = assetStorage.importUriToOfflineStorage(uri, MediaAssetType.IMAGE)
            if (res != null) {
                onLoaded(res.filePath)
            }
        }
    }

    fun applyAnimationPresetToCanvas(
        characterId: String,
        orientation: CharacterOrientation,
        presetType: AnimationPresetType,
        durationFrames: Int,
        speedMultiplier: Float,
        loopAnimation: Boolean
    ) {
        val state = _uiState.value
        val character = state.charactersById[characterId] ?: return
        val swapClipId = state.editingClipForAnimationSwapId

        if (swapClipId != null) {
            val updated = ProjectMutator.updateActiveScene(state.project) { scene ->
                scene.copy(
                    clips = scene.clips.map { c ->
                        if (c.id == swapClipId) {
                            c.copy(
                                characterId = characterId,
                                name = "${character.name} • ${presetType.displayName}",
                                orientation = orientation,
                                animationPresetType = presetType,
                                durationFrames = durationFrames.coerceAtLeast(12),
                                speedMultiplier = speedMultiplier,
                                loopAnimation = loopAnimation
                            )
                        } else c
                    }
                )
            }
            commitProjectChange(updated)
            router.popUpTo(HashRoute.HASH_EDITOR)
            if (router.currentRoute !is HashRoute.AnimationEditor) {
                router.navigate(HashRoute.AnimationEditor(updated.id))
            }
            _uiState.update {
                it.copy(
                    destination = StudioDestination.EDITOR,
                    editingClipForAnimationSwapId = null,
                    selectedClipId = swapClipId
                )
            }
            showStatus("Updated animation: ${presetType.displayName}")
            return
        }

        val (updated, clipId) = ProjectMutator.addCharacterClip(
            project = state.project,
            character = character,
            orientation = orientation,
            preset = presetType,
            startFrame = state.currentFrame,
            durationFrames = durationFrames,
            speedMultiplier = speedMultiplier,
            loopAnimation = loopAnimation
        )
        commitProjectChange(updated)
        router.popUpTo(HashRoute.HASH_EDITOR)
        if (router.currentRoute !is HashRoute.AnimationEditor) {
            router.navigate(HashRoute.AnimationEditor(updated.id))
        }
        _uiState.update {
            it.copy(
                destination = StudioDestination.EDITOR,
                selectedClipId = clipId,
                selectedLayerId = updated.activeScene.clips.firstOrNull { c -> c.id == clipId }?.layerId
            )
        }
        showStatus("Added ${character.name} (${presetType.displayName})")
    }

    // --- Media Library ---
    fun importMediaUri(uri: Uri, preferredType: MediaAssetType? = null, addImmediatelyToCanvas: Boolean = false) {
        viewModelScope.launch {
            val asset = repository.importMediaAsset(uri, category = "Imported", preferredType = preferredType)
            if (asset != null) {
                showStatus("Imported ${asset.name}")
                if (addImmediatelyToCanvas) {
                    placeMediaAssetOnCanvas(asset, asBackground = false)
                }
            } else {
                showStatus("Could not import selected file")
            }
        }
    }

    fun deleteMediaAsset(asset: LocalMediaAsset) {
        viewModelScope.launch {
            repository.deleteMediaAsset(asset)
            showStatus("Removed ${asset.name}")
        }
    }

    fun placeMediaAssetOnCanvas(asset: LocalMediaAsset, asBackground: Boolean) {
        val state = _uiState.value
        if (asset.type == MediaAssetType.AUDIO && !asset.localFilePath.isNullOrBlank()) {
            val (updated, clipId) = ProjectMutator.addAudioClip(
                state.project, asset.name, asset.localFilePath, state.currentFrame
            )
            commitProjectChange(updated)
            router.popUpTo(HashRoute.HASH_EDITOR)
            if (router.currentRoute !is HashRoute.AnimationEditor) {
                router.navigate(HashRoute.AnimationEditor(updated.id))
            }
            _uiState.update { it.copy(selectedClipId = clipId, destination = StudioDestination.EDITOR) }
            showStatus("Added audio track: ${asset.name}")
            return
        }
        val (updated, clipId) = ProjectMutator.addImageOrMediaClip(
            project = state.project,
            name = asset.name,
            imagePath = asset.localFilePath,
            bundledKey = asset.bundledResKey,
            startFrame = state.currentFrame,
            asBackground = asBackground
        )
        commitProjectChange(updated)
        router.popUpTo(HashRoute.HASH_EDITOR)
        if (router.currentRoute !is HashRoute.AnimationEditor) {
            router.navigate(HashRoute.AnimationEditor(updated.id))
        }
        _uiState.update {
            it.copy(
                selectedClipId = clipId.ifBlank { it.selectedClipId },
                destination = StudioDestination.EDITOR,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
        showStatus(if (asBackground) "Background updated" else "Added ${asset.name} to canvas")
    }

    // --- Editor Canvas, Objects, Keyframes, Layers & Scenes ---
    fun openSheet(sheet: ActiveEditorSheet) {
        stopPlayback()
        _uiState.update { it.copy(activeSheet = sheet, isDrawingMode = false) }
    }

    fun closeSheet() {
        _uiState.update { it.copy(activeSheet = ActiveEditorSheet.NONE, exportState = ExportUiState.Idle) }
    }

    fun selectClip(clipId: String?) {
        val layerId = clipId?.let { id ->
            _uiState.value.project.activeScene.clips.firstOrNull { it.id == id }?.layerId
        }
        _uiState.update { it.copy(selectedClipId = clipId, selectedLayerId = layerId ?: it.selectedLayerId) }
    }

    fun dragSelectedClipByNormalizedDelta(dx: Float, dy: Float) {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, state.currentFrame) { kf ->
            kf.copy(
                x = (kf.x + dx).coerceIn(0.02f, 0.98f),
                y = (kf.y + dy).coerceIn(0.02f, 0.98f)
            )
        }
        _uiState.update { it.copy(project = updated) }
    }

    fun commitGestureSnapshot() {
        pushUndoSnapshot(_uiState.value.project)
        saveCurrentProject(silent = true)
    }

    fun updateSelectedClipTransform(updater: (TransformKeyframe) -> TransformKeyframe) {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, state.currentFrame, updater)
        commitProjectChange(updated)
    }

    fun toggleKeyframeAtCurrentFrame() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: run {
            showStatus("Select an object on canvas or timeline first")
            return
        }
        val updated = ProjectMutator.toggleKeyframeAtFrame(state.project, clipId, state.currentFrame)
        commitProjectChange(updated)
        showStatus("Keyframe updated at F${state.currentFrame + 1}")
    }

    fun selectKeyframeNode(clipId: String, globalFrame: Int) {
        stopPlayback()
        val maxFrame = (_uiState.value.project.activeScene.durationFrames - 1).coerceAtLeast(0)
        val clamped = globalFrame.coerceIn(0, maxFrame)
        val layerId = _uiState.value.project.activeScene.clips.firstOrNull { it.id == clipId }?.layerId
        _playbackFrame.value = clamped
        _uiState.update {
            it.copy(
                selectedClipId = clipId,
                selectedLayerId = layerId ?: it.selectedLayerId,
                currentFrame = clamped
            )
        }
        showStatus("Selected keyframe node at F${clamped + 1}")
    }

    fun cycleSelectedKeyframeEasing() {
        val state = _uiState.value
        val clip = state.selectedClip ?: return
        val localFrame = (state.currentFrame - clip.startFrame).coerceAtLeast(0)
        val existing = clip.keyframes.firstOrNull { it.frame == localFrame } ?: return
        val entries = EasingType.entries
        val nextEasing = entries[(entries.indexOf(existing.easing) + 1) % entries.size]
        updateSelectedClipTransform { it.copy(easing = nextEasing) }
        showStatus("Easing: ${nextEasing.displayName}")
    }

    fun updateSelectedClipProps(updater: (TimelineClip) -> TimelineClip) {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val updated = ProjectMutator.updateActiveScene(state.project) { scene ->
            val target = scene.clips.firstOrNull { it.id == clipId }
            val locked = scene.layers.firstOrNull { it.id == target?.layerId }?.isLocked == true
            if (locked) scene
            else scene.copy(clips = scene.clips.map { if (it.id == clipId) updater(it) else it })
        }
        commitProjectChange(updated)
    }

    fun flipSelectedClip(horizontal: Boolean) {
        updateSelectedClipProps { clip ->
            if (horizontal) clip.copy(flipHorizontal = !clip.flipHorizontal)
            else clip.copy(flipVertical = !clip.flipVertical)
        }
    }

    fun splitSelectedClipAtPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val updated = ProjectMutator.splitClipAtFrame(state.project, clipId, state.currentFrame)
        if (updated != state.project) {
            commitProjectChange(updated)
            showStatus("Clip split at F${state.currentFrame + 1}")
        } else {
            showStatus("Move playhead inside the clip to split")
        }
    }

    fun duplicateSelectedClip() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val (updated, newId) = ProjectMutator.duplicateClip(state.project, clipId)
        if (newId != null) {
            commitProjectChange(updated)
            _uiState.update { it.copy(selectedClipId = newId) }
            showStatus("Clip duplicated")
        }
    }

    fun deleteSelectedClip() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val updated = ProjectMutator.updateActiveScene(state.project) { scene ->
            val clip = scene.clips.firstOrNull { it.id == clipId }
            val locked = scene.layers.firstOrNull { it.id == clip?.layerId }?.isLocked == true
            if (locked) scene else scene.copy(clips = scene.clips.filterNot { it.id == clipId })
        }
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = null) }
        showStatus("Clip deleted")
    }

    fun addShape(kind: ShapeKind, fillHex: String, strokeHex: String) {
        val state = _uiState.value
        val (updated, clipId) = ProjectMutator.addShapeClip(state.project, kind, fillHex, strokeHex, state.currentFrame)
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId, activeSheet = ActiveEditorSheet.NONE) }
        showStatus("Added ${kind.displayName}")
    }

    fun addText(text: String, colorHex: String, sizeSp: Float) {
        val state = _uiState.value
        val (updated, clipId) = ProjectMutator.addTextClip(state.project, text, colorHex, sizeSp, state.currentFrame)
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId, activeSheet = ActiveEditorSheet.NONE) }
        showStatus("Added text layer")
    }

    fun toggleDrawingMode() {
        stopPlayback()
        _uiState.update { it.copy(isDrawingMode = !it.isDrawingMode, activeSheet = ActiveEditorSheet.NONE) }
    }

    fun setBrushStyle(colorHex: String, widthRatio: Float) {
        _uiState.update { it.copy(brushColorHex = colorHex, brushWidthRatio = widthRatio) }
    }

    fun finishDrawingStroke(points: List<NormalizedPoint>) {
        if (points.size < 2) return
        val state = _uiState.value
        val stroke = DrawingStroke(
            points = points,
            colorHex = state.brushColorHex,
            strokeWidthRatio = state.brushWidthRatio
        )
        val (updated, clipId) = ProjectMutator.addDrawingStroke(state.project, stroke, state.currentFrame)
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId) }
    }

    fun setSceneBackgroundColor(hex: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(backgroundColorHex = hex, backgroundBundledKey = null, backgroundImagePath = null)
        }
        commitProjectChange(updated)
    }

    fun setSceneBackgroundBundled(key: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(backgroundBundledKey = key, backgroundImagePath = null)
        }
        commitProjectChange(updated)
    }

    // --- Layer Management ---
    fun addEmptyLayer(type: LayerType, name: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            val nextIdx = (scene.layers.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val layer = AnimationLayer(name = name.ifBlank { type.displayName }, type = type, orderIndex = nextIdx)
            scene.copy(layers = scene.layers + layer)
        }
        commitProjectChange(updated)
    }

    fun toggleLayerVisibility(layerId: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(layers = scene.layers.map { if (it.id == layerId) it.copy(isVisible = !it.isVisible) else it })
        }
        commitProjectChange(updated)
    }

    fun toggleLayerLock(layerId: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(layers = scene.layers.map { if (it.id == layerId) it.copy(isLocked = !it.isLocked) else it })
        }
        commitProjectChange(updated)
    }

    fun renameLayer(layerId: String, newName: String) {
        val clean = newName.trim().ifBlank { return }
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(layers = scene.layers.map { if (it.id == layerId) it.copy(name = clean) else it })
        }
        commitProjectChange(updated)
    }

    fun reorderLayer(layerId: String, moveUp: Boolean) {
        val updated = ProjectMutator.reorderLayer(_uiState.value.project, layerId, moveUp)
        commitProjectChange(updated)
    }

    fun deleteLayer(layerId: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            if (scene.layers.size <= 1) scene
            else scene.copy(
                layers = scene.layers.filterNot { it.id == layerId },
                clips = scene.clips.filterNot { it.layerId == layerId }
            )
        }
        commitProjectChange(updated)
        if (_uiState.value.selectedLayerId == layerId) {
            _uiState.update { it.copy(selectedLayerId = null, selectedClipId = null) }
        }
    }

    // --- Scenes ---
    fun selectScene(index: Int) {
        stopPlayback()
        val proj = _uiState.value.project
        if (index in proj.scenes.indices) {
            _playbackFrame.value = 0
            _uiState.update {
                it.copy(
                    project = proj.copy(activeSceneIndex = index),
                    currentFrame = 0,
                    selectedClipId = null
                )
            }
        }
    }

    fun addScene() {
        stopPlayback()
        val proj = _uiState.value.project
        val nextNum = proj.scenes.size + 1
        val newScene = AnimationScene(
            name = "Scene $nextNum",
            backgroundColorHex = "#162033",
            durationFrames = 120,
            layers = listOf(AnimationLayer(name = "Background", type = LayerType.BACKGROUND, orderIndex = 0))
        )
        val updated = proj.copy(scenes = proj.scenes + newScene, activeSceneIndex = proj.scenes.size)
        commitProjectChange(updated)
        _playbackFrame.value = 0
        _uiState.update { it.copy(currentFrame = 0, selectedClipId = null) }
        showStatus("Added Scene $nextNum")
    }

    fun deleteActiveScene() {
        val proj = _uiState.value.project
        if (proj.scenes.size <= 1) return
        val idx = proj.activeSceneIndex.coerceIn(0, proj.scenes.lastIndex)
        val remaining = proj.scenes.toMutableList().apply { removeAt(idx) }
        val updated = proj.copy(scenes = remaining, activeSceneIndex = (idx - 1).coerceAtLeast(0))
        commitProjectChange(updated)
        _playbackFrame.value = 0
        _uiState.update { it.copy(currentFrame = 0, selectedClipId = null) }
    }

    // --- Project & Canvas Settings ---
    fun updateProjectSettings(
        name: String,
        aspectRatio: AspectRatioPreset,
        width: Int,
        height: Int,
        fps: Int,
        durationFrames: Int,
        previewQuality: PreviewQuality
    ) {
        val cur = _uiState.value.project
        val updated = ProjectMutator.updateActiveScene(
            cur.copy(
                name = name.trim().ifBlank { cur.name },
                aspectRatioPreset = aspectRatio,
                width = width.coerceIn(240, 3840),
                height = height.coerceIn(240, 3840),
                fps = fps.coerceIn(12, 60),
                previewQuality = previewQuality
            )
        ) { scene ->
            scene.copy(durationFrames = durationFrames.coerceIn(24, 1800))
        }
        commitProjectChange(updated)
        val clamped = _playbackFrame.value.coerceAtMost(updated.activeScene.durationFrames - 1)
        _playbackFrame.value = clamped
        _uiState.update {
            it.copy(
                currentFrame = clamped,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
        showStatus("Updated project settings")
    }

    fun toggleOnionSkin() {
        val cur = _uiState.value.project
        commitProjectChange(cur.copy(onionSkinEnabled = !cur.onionSkinEnabled))
    }

    fun toggleMotionPaths() {
        val cur = _uiState.value.project
        commitProjectChange(cur.copy(showMotionPaths = !cur.showMotionPaths))
    }

    // --- Viewport & Timeline Zoom ---
    fun zoomCanvasBy(factor: Float) {
        _uiState.update { it.copy(canvasZoom = (it.canvasZoom * factor).coerceIn(0.45f, 3.5f)) }
    }

    fun panCanvasBy(dx: Float, dy: Float) {
        _uiState.update { it.copy(canvasPanX = it.canvasPanX + dx, canvasPanY = it.canvasPanY + dy) }
    }

    fun resetCanvasViewport() {
        _uiState.update { it.copy(canvasZoom = 1f, canvasPanX = 0f, canvasPanY = 0f) }
    }

    fun adjustTimelineZoom(delta: Float) {
        _uiState.update { it.copy(timelineZoom = (it.timelineZoom + delta).coerceIn(0.5f, 2.5f)) }
    }

    // --- Playback Engine (Drift-Free Monotonic Clock + Zero Full-Screen Recomposition) ---
    fun seekToFrame(frame: Int) {
        val maxFrame = (_uiState.value.project.activeScene.durationFrames - 1).coerceAtLeast(0)
        val clamped = frame.coerceIn(0, maxFrame)
        _playbackFrame.value = clamped
        _uiState.update { it.copy(currentFrame = clamped) }
    }

    fun stepFrame(delta: Int) {
        stopPlayback()
        seekToFrame(_playbackFrame.value + delta)
    }

    fun toggleLooping() {
        _uiState.update { it.copy(isLooping = !it.isLooping) }
    }

    fun togglePlayback() {
        if (_uiState.value.isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        val initState = _uiState.value
        val totalFrames = initState.project.activeScene.durationFrames.coerceAtLeast(1)
        val startFrame = if (_playbackFrame.value >= totalFrames - 1) 0 else _playbackFrame.value
        _playbackFrame.value = startFrame
        _uiState.update { it.copy(isPlaying = true, currentFrame = startFrame) }
        startAudioPreviewIfPresent()

        playbackJob = viewModelScope.launch {
            val fps = _uiState.value.project.fps.coerceIn(12, 60)
            val frameDurationNs = 1_000_000_000L / fps
            var anchorFrame = startFrame
            var anchorTimeNs = System.nanoTime()

            while (isActive && _uiState.value.isPlaying) {
                val nowNs = System.nanoTime()
                val elapsedFrames = ((nowNs - anchorTimeNs) / frameDurationNs).toInt()
                val targetRaw = anchorFrame + elapsedFrames
                val total = _uiState.value.project.activeScene.durationFrames.coerceAtLeast(1)

                if (targetRaw >= total) {
                    if (_uiState.value.isLooping) {
                        anchorFrame = 0
                        anchorTimeNs = nowNs
                        _playbackFrame.value = 0
                        restartAudioPreview()
                    } else {
                        _playbackFrame.value = total - 1
                        stopPlayback()
                        break
                    }
                } else if (targetRaw != _playbackFrame.value) {
                    _playbackFrame.value = targetRaw
                }

                // Sleep cleanly to the exact next frame boundary (no drift, no busy-spin)
                val nextBoundaryNs = anchorTimeNs + (elapsedFrames + 1L) * frameDurationNs
                val sleepMs = ((nextBoundaryNs - System.nanoTime()) / 1_000_000L).coerceIn(1L, 42L)
                delay(sleepMs)
            }
        }
    }

    fun stopPlayback() {
        val wasPlaying = _uiState.value.isPlaying || playbackJob != null
        playbackJob?.cancel()
        playbackJob = null
        stopAudioPreview()
        if (wasPlaying) {
            val stoppedFrame = _playbackFrame.value
            _uiState.update { it.copy(isPlaying = false, currentFrame = stoppedFrame) }
        }
    }

    private fun startAudioPreviewIfPresent() {
        val state = _uiState.value
        val audioClip = state.project.activeScene.clips.firstOrNull { !it.audioPath.isNullOrBlank() } ?: return
        val file = File(audioClip.audioPath!!)
        if (!file.exists()) return
        runCatching {
            stopAudioPreview()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                val seekMs = ((_playbackFrame.value - audioClip.startFrame).coerceAtLeast(0) * 1000) / state.project.fps.coerceAtLeast(1)
                if (seekMs in 0 until duration) seekTo(seekMs)
                start()
            }
        }
    }

    private fun restartAudioPreview() {
        runCatching {
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
        }
    }

    private fun stopAudioPreview() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    // --- Export ---
    fun startExport(format: ExportFormat, resolution: ExportResolution, fps: Int, durationFrames: Int) {
        stopPlayback()
        exportJob?.cancel()
        val state = _uiState.value
        val total = durationFrames.coerceIn(1, state.project.activeScene.durationFrames.coerceAtLeast(1))
        _uiState.update { it.copy(exportState = ExportUiState.Exporting(0, total, format)) }

        exportJob = viewModelScope.launch {
            try {
                val output = exporter.export(
                    project = state.project,
                    charactersById = state.charactersById,
                    request = ExportRequest(format, resolution, fps, total)
                ) { curFrame, tot ->
                    _uiState.update {
                        it.copy(exportState = ExportUiState.Exporting(curFrame, tot, format))
                    }
                }
                _uiState.update { it.copy(exportState = ExportUiState.Success(output)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(exportState = ExportUiState.Idle) }
                throw e
            } catch (err: Throwable) {
                _uiState.update {
                    it.copy(exportState = ExportUiState.Error(err.localizedMessage ?: "Export failed"))
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update { it.copy(exportState = ExportUiState.Idle) }
        showStatus("Export cancelled")
    }

    // --- Undo / Redo ---
    fun undo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.addLast(_uiState.value.project)
        _uiState.update {
            it.copy(project = prev, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        saveCurrentProject(silent = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(_uiState.value.project)
        _uiState.update {
            it.copy(project = next, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        saveCurrentProject(silent = true)
    }

    private fun commitProjectChange(newProject: ProjectDocument) {
        pushUndoSnapshot(_uiState.value.project)
        _uiState.update {
            it.copy(project = newProject, canUndo = undoStack.isNotEmpty(), canRedo = false)
        }
        saveCurrentProject(silent = true)
    }

    private fun pushUndoSnapshot(snapshot: ProjectDocument) {
        undoStack.addLast(snapshot)
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        _uiState.update { it.copy(canUndo = false, canRedo = false) }
    }

    private fun showStatus(message: String) {
        statusClearJob?.cancel()
        _uiState.update { it.copy(statusBanner = message) }
        statusClearJob = viewModelScope.launch {
            delay(2400)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }
}
