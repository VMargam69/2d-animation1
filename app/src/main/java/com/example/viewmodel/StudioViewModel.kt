package com.example.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BuiltInCatalog
import com.example.data.LocalAssetStorage
import com.example.data.PoseLibraryStorage
import com.example.data.StudioRepository
import com.example.data.db.FrameCraftDatabase
import com.example.data.db.ProjectSummary
import com.example.engine.AnimationExporter
import com.example.engine.ExportOutput
import com.example.engine.ExportRequest
import com.example.engine.ModularActionDef
import com.example.engine.ModularActionEngine
import com.example.engine.SceneRenderer
import com.example.model.*
import com.example.navigation.HashRoute
import com.example.navigation.HashRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class StudioDestination {
    PROJECTS_HOME,
    EDITOR,
    CHARACTER_LIBRARY,
    ANIMATION_LIBRARY,
    MEDIA_LIBRARY,
    CHARACTER_SETUP,
    CHARACTER_MAKER
}

enum class ActiveEditorSheet {
    NONE,
    PROJECT_SETTINGS,
    TRANSFORM_INSPECTOR,
    CHARACTER_ACTING_PROPS,
    CAMERA_CHROMA_STUDIO,
    SCENE_VFX_STUDIO,
    EASING_CURVE_EDITOR,
    SPEED_SETTINGS,
    TRANSITIONS,
    LAYER_MANAGER,
    ADD_SHAPE,
    ADD_TEXT,
    BACKGROUND_PICKER,
    TEMPLATES,
    EXPORT_DIALOG,
    WAVEFORM_EXPORT,
    DIRECTOR_SCRIPT_STUDIO,
    IK_STUDIO,
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
    val playbackSpeed: Float = 1.0f,
    val canvasZoom: Float = 1f,
    val canvasPanX: Float = 0f,
    val canvasPanY: Float = 0f,
    val timelineZoom: Float = 1f,
    val waveformSensitivity: Float = 1.0f,
    val isDrawingMode: Boolean = false,
    val isIkPosingMode: Boolean = false,
    val ikFlipBend: Boolean = false,
    val isCameraKeyMode: Boolean = false,
    val brushColorHex: String = "#38BDF8",
    val brushWidthRatio: Float = 0.009f,
    val brushStyle: BrushStyle = BrushStyle.SOLID,
    val activeSheet: ActiveEditorSheet = ActiveEditorSheet.NONE,
    val ikStudioInitialTab: Int = 1,
    val savedPoses: List<SavedPose> = emptyList(),
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
    val poseLibraryStorage = PoseLibraryStorage(application)
    val repository = StudioRepository(
        projectDao = db.projectDao(),
        characterDao = db.characterDao(),
        animationPresetDao = db.animationPresetDao(),
        mediaAssetDao = db.mediaAssetDao(),
        assetStorage = assetStorage
    )

    val sceneRenderer = SceneRenderer(
        bitmapProvider = { path, bundledKey ->
            assetStorage.getCachedOrRequestBitmap(path, bundledKey)
        },
        blockingBitmapProvider = { path, bundledKey ->
            when {
                !path.isNullOrBlank() -> assetStorage.loadBitmapFromFile(path)
                !bundledKey.isNullOrBlank() -> assetStorage.loadBundledBackgroundBitmap(bundledKey)
                else -> null
            }
        },
        atlasSlicesProvider = { path ->
            assetStorage.getCachedOrSliceAtlas(path)
        },
        blockingAtlasSlicesProvider = { path ->
            assetStorage.loadOrSliceAtlasBlocking(path)
        }
    )

    val bitmapVersion: StateFlow<Int> get() = assetStorage.bitmapVersion

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
    private var autoSaveJob: Job? = null
    private var lastCoalescedUndoMs: Long = 0L
    private var lastCoalescedUndoTag: String? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isAudioPrepared: Boolean = false

    companion object {
        private const val MAX_UNDO_HISTORY = 20
        private const val AUTO_SAVE_DEBOUNCE_MS = 450L
        private const val UNDO_COALESCE_WINDOW_MS = 650L
    }

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
                prefetchActiveScene(last)
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
                val byId = chars.associateBy { c -> c.id }
                _uiState.update { it.copy(characters = chars, charactersById = byId) }
                assetStorage.prefetchSceneAssets(_uiState.value.project.activeScene, byId)
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
        viewModelScope.launch {
            val loadedPoses = poseLibraryStorage.loadPoses()
            _uiState.update { it.copy(savedPoses = loadedPoses) }
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
            StudioDestination.CHARACTER_SETUP -> HashRoute.CharacterSetup
            StudioDestination.CHARACTER_MAKER -> HashRoute.CharacterMaker()
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

    fun openCharacterSetup() {
        stopPlayback()
        router.navigate(HashRoute.CharacterSetup)
        _uiState.update {
            it.copy(
                destination = StudioDestination.CHARACTER_SETUP,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
    }

    fun openCharacterMaker(pose: AtlasPoseTemplate, editCharacterId: String? = null) {
        stopPlayback()
        router.navigate(HashRoute.CharacterMaker(poseId = pose.id, editCharacterId = editCharacterId))
        _uiState.update {
            it.copy(
                destination = StudioDestination.CHARACTER_MAKER,
                activeSheet = ActiveEditorSheet.NONE
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
                is HashRoute.CharacterSetup -> StudioDestination.CHARACTER_SETUP
                is HashRoute.CharacterMaker -> StudioDestination.CHARACTER_MAKER
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
            prefetchActiveScene(saved)
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
            prefetchActiveScene(loaded)
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
            prefetchActiveScene(last)
            showStatus("Recovered \"${last.name}\"")
        }
    }

    fun saveCurrentProject(silent: Boolean = false) {
        autoSaveJob?.cancel()
        autoSaveJob = null
        val current = _uiState.value.project
        viewModelScope.launch {
            val saved = repository.saveProject(current, markAsLastOpened = true)
            _uiState.update { state ->
                if (state.project.id == saved.id && state.project.updatedAt == current.updatedAt) {
                    state.copy(project = saved)
                } else state
            }
            if (!silent) showStatus("Saved locally offline")
        }
    }

    private fun scheduleDebouncedSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MS)
            val current = _uiState.value.project
            repository.saveProject(current, markAsLastOpened = true)
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
            if (!character.customImagePath.isNullOrBlank()) {
                assetStorage.invalidateAtlasSlices(character.customImagePath)
            }
            repository.saveCharacter(character)
            showStatus("Saved character \"${character.name}\"")
        }
    }

    fun saveAtlasCharacterAndReturn(character: CharacterRigDefinition) {
        viewModelScope.launch {
            repository.saveCharacter(character)
            router.popUpTo(HashRoute.HASH_CHARACTERS, inclusive = false)
            _uiState.update { it.copy(destination = StudioDestination.CHARACTER_LIBRARY) }
            showStatus("Saved \"${character.name}\" to Character Library")
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

    fun importCharacterPackUri(uri: Uri, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.importCharacterPack(uri)
            showStatus(result.message)
            onResult(result.message)
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
            startFrame = _playbackFrame.value,
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
                state.project, asset.name, asset.localFilePath, _playbackFrame.value
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
            startFrame = _playbackFrame.value,
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

    /** Commits a completed clip drag gesture once on finger lift (zero mid-drag ProjectDocument copies). */
    fun commitClipTranslation(clipId: String, totalDx: Float, totalDy: Float) {
        if (kotlin.math.abs(totalDx) < 0.0001f && kotlin.math.abs(totalDy) < 0.0001f) return
        val state = _uiState.value
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame) { kf ->
            kf.copy(
                x = (kf.x + totalDx).coerceIn(0.02f, 0.98f),
                y = (kf.y + totalDy).coerceIn(0.02f, 0.98f)
            )
        }
        commitProjectChange(updated)
    }

    /** Commits a completed Direct Stage IK limb/head drag once on finger lift. */
    fun commitIkPoseOffsets(clipId: String, finalOffsets: PoseKeyframeOffsets) {
        val state = _uiState.value
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame) { kf ->
            kf.copy(poseOffsets = finalOffsets)
        }
        commitProjectChange(updated)
    }

    /** Opens the IK Studio modal (Tab 0 = Timeline, Tab 1 = Actions Preview, Tab 2 = Pose Library). */
    fun openIkStudio(initialTab: Int = 1) {
        stopPlayback()
        val state = _uiState.value
        val targetClip = state.selectedClip?.takeIf { it.characterId != null }
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }
        if (targetClip == null) {
            showStatus("Add or select a character first to open IK Studio")
            return
        }
        _uiState.update {
            it.copy(
                selectedClipId = targetClip.id,
                selectedLayerId = targetClip.layerId,
                ikStudioInitialTab = initialTab.coerceIn(0, 2),
                activeSheet = ActiveEditorSheet.IK_STUDIO,
                isDrawingMode = false
            )
        }
    }

    /** Commits an IK Studio pose (with expression & hand pose) at the current playhead frame with Undo. */
    fun commitIkStudioPose(
        clipId: String,
        offsets: PoseKeyframeOffsets,
        expression: FacialExpression,
        handPose: HandPoseType
    ) {
        val state = _uiState.value
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame) { kf ->
            kf.copy(
                poseOffsets = offsets,
                expressionOverride = expression,
                handPoseOverride = handPose
            )
        }
        commitProjectChange(updated)
        showStatus("IK Pose saved at frame $frame")
    }

    /** Saves the current IK rig pose into local JSON (`filesDir/pose_library.json`) with offline thumbnail. */
    fun saveCurrentPoseToLibrary(
        name: String,
        category: PoseCategory,
        offsets: PoseKeyframeOffsets,
        gaze: GazeTarget,
        headTilt: Float,
        expression: FacialExpression,
        handPose: HandPoseType
    ) {
        val state = _uiState.value
        val clip = state.selectedClip?.takeIf { it.characterId != null }
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }
        val rig = clip?.characterId?.let { state.charactersById[it] } ?: state.characters.firstOrNull()
        val orientation = clip?.orientation ?: CharacterOrientation.FRONT
        val newPose = SavedPose(
            id = "pose_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom Pose" },
            category = category,
            ikJointOffsets = offsets,
            gazeTarget = gaze,
            headTilt = headTilt,
            expression = expression,
            handPose = handPose,
            createdAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            val updatedList = poseLibraryStorage.savePose(newPose, rig = rig, orientation = orientation)
            _uiState.update { it.copy(savedPoses = updatedList) }
            showStatus("Saved pose \"${newPose.name}\" to library")
        }
    }

    fun renameSavedPose(poseId: String, newName: String, newCategory: PoseCategory) {
        viewModelScope.launch {
            val updatedList = poseLibraryStorage.renamePose(poseId, newName, newCategory)
            _uiState.update { it.copy(savedPoses = updatedList) }
            showStatus("Updated pose \"$newName\"")
        }
    }

    fun deleteSavedPose(poseId: String) {
        viewModelScope.launch {
            val updatedList = poseLibraryStorage.deletePose(poseId)
            _uiState.update { it.copy(savedPoses = updatedList) }
            showStatus("Deleted pose from library")
        }
    }

    /** 1-Tap Apply Saved Pose with automatic Undo snapshot at the current playhead frame. */
    fun applySavedPoseToSelectedClip(pose: SavedPose) {
        val state = _uiState.value
        val clip = state.selectedClip?.takeIf { it.characterId != null }
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }
            ?: return
        val frame = _playbackFrame.value
        val mergedOffsets = pose.ikJointOffsets.copy(
            headNodOffset = (pose.ikJointOffsets.headNodOffset + pose.headTilt).coerceIn(-65f, 65f)
        )
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clip.id, frame) { kf ->
            kf.copy(
                easing = EasingType.EASE_IN_OUT,
                poseOffsets = mergedOffsets,
                expressionOverride = pose.expression,
                handPoseOverride = pose.handPose
            )
        }
        commitProjectChange(updated)
        showStatus("Applied pose \"${pose.name}\" at frame $frame")
    }

    /** Commits a previewed modular action onto its isolated track (`Expression`, `Hands`, or `Legs`) with Undo. */
    fun insertModularActionAtPlayhead(clipId: String, action: ModularActionDef) {
        val state = _uiState.value
        val frame = _playbackFrame.value
        val updated = ModularActionEngine.insertModularActionIntoProject(
            project = state.project,
            clipId = clipId,
            startGlobalFrame = frame,
            action = action
        )
        commitProjectChange(updated)
        showStatus("Inserted ${action.name} on ${action.track.timelineTrackLabel} track (F$frame)")
    }

    /** Commits a completed stage camera pan drag once on finger lift. */
    fun commitCameraPan(totalDx: Float, totalDy: Float) {
        if (kotlin.math.abs(totalDx) < 0.0001f && kotlin.math.abs(totalDy) < 0.0001f) return
        val state = _uiState.value
        if (state.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertCameraKeyframeAtFrame(state.project, frame) { cam ->
            cam.copy(
                x = (cam.x - totalDx).coerceIn(0.05f, 0.95f),
                y = (cam.y - totalDy).coerceIn(0.05f, 0.95f)
            )
        }
        commitProjectChange(updated)
    }

    fun updateSelectedClipTransform(updater: (TransformKeyframe) -> TransformKeyframe) {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame, updater)
        commitProjectChange(updated, coalesceUndoTag = "transform_$clipId")
    }

    fun toggleKeyframeAtCurrentFrame() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: run {
            showStatus("Select an object on canvas or timeline first")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.toggleKeyframeAtFrame(state.project, clipId, frame)
        commitProjectChange(updated)
        showStatus("Keyframe updated at F${frame + 1}")
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
        val localFrame = (_playbackFrame.value - clip.startFrame).coerceAtLeast(0)
        val existing = clip.keyframes.firstOrNull { it.frame == localFrame } ?: return
        val entries = EasingType.entries
        val nextEasing = entries[(entries.indexOf(existing.easing) + 1) % entries.size]
        updateSelectedClipTransform { it.copy(easing = nextEasing) }
        showStatus("Easing: ${nextEasing.displayName}")
    }

    fun updateSelectedKeyframeEasing(
        easing: EasingType,
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float
    ) {
        val state = _uiState.value
        val clip = state.selectedClip ?: return
        val localFrame = (_playbackFrame.value - clip.startFrame).coerceAtLeast(0)
        val targetFrame = clip.keyframes.firstOrNull { it.frame == localFrame }?.frame
            ?: clip.keyframes.filter { it.frame <= localFrame }.maxByOrNull { it.frame }?.frame
            ?: clip.keyframes.firstOrNull()?.frame
            ?: 0
        val globalTarget = (clip.startFrame + targetFrame).coerceIn(0, (state.project.activeScene.durationFrames - 1).coerceAtLeast(0))
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clip.id, globalTarget) { kf ->
            kf.copy(
                easing = easing,
                easingP1X = p1x.coerceIn(0.01f, 0.99f),
                easingP1Y = p1y.coerceIn(-0.6f, 1.6f),
                easingP2X = p2x.coerceIn(0.01f, 0.99f),
                easingP2Y = p2y.coerceIn(-0.6f, 1.6f)
            )
        }
        commitProjectChange(updated, coalesceUndoTag = "easing_${clip.id}")
        showStatus("Curve set: ${easing.displayName}")
    }

    fun applyQuickMotionPreset(presetId: String, label: String) {
        val state = _uiState.value
        val clipId = state.selectedClipId
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }?.id
            ?: state.project.activeScene.clips.firstOrNull()?.id
            ?: run {
                showStatus("Select a clip on stage or timeline first")
                return
            }
        val updated = ProjectMutator.applyQuickMotionPreset(
            project = state.project,
            clipId = clipId,
            startGlobalFrame = _playbackFrame.value,
            presetId = presetId
        )
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId) }
        showStatus("Applied $label motion")
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
        commitProjectChange(updated, coalesceUndoTag = "clip_props_$clipId")
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
        val frame = _playbackFrame.value
        val updated = ProjectMutator.splitClipAtFrame(state.project, clipId, frame)
        if (updated != state.project) {
            commitProjectChange(updated)
            showStatus("Clip split at F${frame + 1}")
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
        val (updated, clipId) = ProjectMutator.addShapeClip(state.project, kind, fillHex, strokeHex, _playbackFrame.value)
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId, activeSheet = ActiveEditorSheet.NONE) }
        showStatus("Added ${kind.displayName}")
    }

    fun addText(
        text: String,
        colorHex: String,
        sizeSp: Float,
        bubbleStyle: TextBubbleStyle = TextBubbleStyle.NONE,
        bubbleFillHex: String = "#FFFFFF"
    ) {
        val state = _uiState.value
        val (updated, clipId) = ProjectMutator.addTextClip(
            project = state.project,
            text = text,
            colorHex = colorHex,
            sizeSp = sizeSp,
            startFrame = _playbackFrame.value,
            bubbleStyle = bubbleStyle,
            bubbleFillHex = bubbleFillHex
        )
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId, activeSheet = ActiveEditorSheet.NONE) }
        showStatus(if (bubbleStyle == TextBubbleStyle.NONE) "Added text layer" else "Added ${bubbleStyle.displayName} bubble")
    }

    fun toggleDrawingMode() {
        stopPlayback()
        _uiState.update {
            it.copy(
                isDrawingMode = !it.isDrawingMode,
                isIkPosingMode = false,
                isCameraKeyMode = false,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
    }

    fun toggleIkPosingMode() {
        stopPlayback()
        val state = _uiState.value
        val next = !state.isIkPosingMode
        val charClipId = state.selectedClip?.takeIf { it.characterId != null }?.id
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }?.id
        _uiState.update {
            it.copy(
                isIkPosingMode = next,
                isDrawingMode = false,
                isCameraKeyMode = false,
                selectedClipId = charClipId ?: it.selectedClipId,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
        if (next) {
            showStatus("IK Pose Mode ON: Drag limb or head handles on stage")
        } else {
            showStatus("IK Pose Mode OFF")
        }
    }

    fun toggleCameraKeyMode() {
        stopPlayback()
        val next = !_uiState.value.isCameraKeyMode
        _uiState.update {
            it.copy(
                isCameraKeyMode = next,
                isIkPosingMode = false,
                isDrawingMode = false,
                activeSheet = ActiveEditorSheet.NONE
            )
        }
        if (next) {
            showStatus("Camera Key Mode ON: Drag stage to pan camera")
        } else {
            showStatus("Camera Key Mode OFF")
        }
    }

    fun dragIkHandleOnStage(
        handleId: IkHandleId,
        targetNx: Float,
        targetNy: Float,
        stageWidth: Float,
        stageHeight: Float
    ) {
        val state = _uiState.value
        val clip = state.selectedClip ?: return
        val rig = clip.characterId?.let { state.charactersById[it] } ?: return
        val frame = _playbackFrame.value
        val eval = com.example.engine.KeyframeEvaluator.evaluate(clip, frame)
        val localFrame = (frame - clip.startFrame).coerceAtLeast(0)
        val cycleFrames = clip.animationPresetType.defaultDurationFrames.coerceAtLeast(8).toFloat()
        val rawPhase = (localFrame * clip.speedMultiplier) / cycleFrames
        val phase = if (clip.loopAnimation) rawPhase % 1f else rawPhase.coerceIn(0f, 0.999f)
        val basePose = com.example.engine.ProceduralRigRenderer.computePose(clip.animationPresetType, phase)

        val newOffsets = com.example.engine.PropAndModularChartRenderer.solveIkDragToOffsets(
            handleId = handleId,
            targetNx = targetNx,
            targetNy = targetNy,
            clip = clip,
            rig = rig,
            transform = eval,
            basePose = basePose,
            currentOffsets = eval.poseOffsets,
            stageWidth = stageWidth,
            stageHeight = stageHeight
        )
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clip.id, frame) { kf ->
            kf.copy(poseOffsets = newOffsets)
        }
        _uiState.update { it.copy(project = updated) }
    }

    fun toggleIkFlipBend() {
        val next = !_uiState.value.ikFlipBend
        _uiState.update { it.copy(ikFlipBend = next) }
        showStatus(if (next) "IK Bend Direction: Reversed" else "IK Bend Direction: Natural")
    }

    fun mirrorSelectedClipPoseAtCurrentFrame() {
        val state = _uiState.value
        val clipId = state.selectedClipId
            ?: state.project.activeScene.clips.firstOrNull { it.characterId != null }?.id
            ?: return
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame) { kf ->
            kf.copy(poseOffsets = com.example.engine.IkRigSolver.mirrorPoseOffsets(kf.poseOffsets))
        }
        commitProjectChange(updated)
        showStatus("Mirrored L↔R limb pose at F${frame + 1}")
    }

    fun resetSelectedClipPoseAtCurrentFrame() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertKeyframeAtFrame(state.project, clipId, frame) { kf ->
            kf.copy(poseOffsets = PoseKeyframeOffsets())
        }
        commitProjectChange(updated)
        showStatus("Reset IK pose offsets at F${frame + 1}")
    }

    fun stampPoseAndCameraKeyframe() {
        stopPlayback()
        val state = _uiState.value
        val frame = _playbackFrame.value
        var proj = state.project
        if (state.isCameraKeyMode) {
            proj = ProjectMutator.upsertCameraKeyframeAtFrame(proj, frame) { it }
            commitProjectChange(proj)
            showStatus("Recorded Camera Keyframe at F${frame + 1}")
            return
        }
        val targetClip = state.selectedClip
            ?: proj.activeScene.clips.firstOrNull { it.characterId != null && frame in it.startFrame until it.endFrame }
            ?: proj.activeScene.clips.firstOrNull()
        if (targetClip != null) {
            proj = ProjectMutator.upsertKeyframeAtFrame(proj, targetClip.id, frame) { it }
            commitProjectChange(proj)
            _uiState.update { it.copy(selectedClipId = targetClip.id) }
            val updatedCount = proj.activeScene.clips.firstOrNull { it.id == targetClip.id }?.keyframes?.size ?: targetClip.keyframes.size
            showStatus("Stamped Pose Keyframe #$updatedCount at F${frame + 1}")
        } else {
            showStatus("Add or select a character first")
        }
    }

    fun dropFacialExpressionOnTimeline(
        layerId: String,
        clipId: String?,
        globalFrame: Int,
        presetId: String
    ) {
        stopPlayback()
        val state = _uiState.value
        val preset = com.example.engine.FacialExpressionEngine.presetById(presetId) ?: return
        val scene = state.project.activeScene
        val safeFrame = globalFrame.coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0))
        val (updated, affectedClipId) = ProjectMutator.dropFacialExpressionPreset(
            project = state.project,
            layerId = layerId,
            clipId = clipId,
            globalFrame = safeFrame,
            presetId = presetId
        )
        if (affectedClipId == null) {
            showStatus("Drop onto an unlocked Character layer")
            return
        }
        commitProjectChange(updated)
        _playbackFrame.value = safeFrame
        _uiState.update {
            it.copy(
                currentFrame = safeFrame,
                selectedClipId = affectedClipId,
                selectedLayerId = layerId
            )
        }
        showStatus("Applied ${preset.name} at F${safeFrame + 1}")
    }

    fun setFacialShapeOverride(
        mouthOverride: MouthShapeOverride? = null,
        eyeOverride: EyeShapeOverride? = null,
        eyebrowOverride: EyebrowShapeOverride? = null
    ) {
        stopPlayback()
        val state = _uiState.value
        val scene = state.project.activeScene
        val targetClip = state.selectedClip?.takeIf { it.characterId != null }
            ?: scene.clips.firstOrNull { clip ->
                clip.characterId != null &&
                    scene.layers.any { l -> l.id == clip.layerId && l.type == LayerType.CHARACTER && !l.isLocked }
            }
        if (targetClip == null) {
            showStatus("Add or select an unlocked Character first")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.setFacialShapeOverrides(
            project = state.project,
            clipId = targetClip.id,
            globalFrame = frame,
            mouthOverride = mouthOverride,
            eyeOverride = eyeOverride,
            eyebrowOverride = eyebrowOverride
        )
        commitProjectChange(updated, coalesceUndoTag = "face_override_${targetClip.id}_$frame")
        _uiState.update { it.copy(selectedClipId = targetClip.id, selectedLayerId = targetClip.layerId) }
        val summary = buildList {
            if (mouthOverride != null) add("Mouth: ${mouthOverride.shortLabel}")
            if (eyeOverride != null) add("Eyes: ${eyeOverride.shortLabel}")
            if (eyebrowOverride != null) add("Brows: ${eyebrowOverride.shortLabel}")
        }.joinToString(" • ")
        if (summary.isNotBlank()) {
            showStatus("$summary (F${frame + 1})")
        }
    }

    fun clearFacialShapeOverrides() {
        stopPlayback()
        val state = _uiState.value
        val scene = state.project.activeScene
        val targetClip = state.selectedClip?.takeIf { it.characterId != null }
            ?: scene.clips.firstOrNull { it.characterId != null }
            ?: return
        val frame = _playbackFrame.value
        val updated = ProjectMutator.clearFacialShapeOverrides(state.project, targetClip.id, frame)
        commitProjectChange(updated)
        showStatus("Facial shape overrides reset to Auto")
    }

    fun stepToAdjacentKeyframe(forward: Boolean) {
        stopPlayback()
        val state = _uiState.value
        val curFrame = _playbackFrame.value
        val scene = state.project.activeScene
        val candidateFrames = sortedSetOf<Int>()
        candidateFrames.add(0)
        for (camKf in scene.cameraKeyframes) {
            candidateFrames.add(camKf.frame)
        }
        val clip = state.selectedClip ?: scene.clips.firstOrNull { it.characterId != null }
        if (clip != null) {
            for (kf in clip.keyframes) {
                candidateFrames.add((clip.startFrame + kf.frame).coerceIn(0, (scene.durationFrames - 1).coerceAtLeast(0)))
            }
        }
        val target = if (forward) {
            candidateFrames.firstOrNull { it > curFrame } ?: candidateFrames.lastOrNull() ?: curFrame
        } else {
            candidateFrames.lastOrNull { it < curFrame } ?: candidateFrames.firstOrNull() ?: curFrame
        }
        seekToFrame(target)
    }

    fun toggleCameraLayerEnabled() {
        val updated = ProjectMutator.toggleCameraLayerEnabled(_uiState.value.project)
        commitProjectChange(updated)
        val enabled = updated.activeScene.cameraLayerEnabled
        showStatus(if (enabled) "Camera Control Layer ON" else "Camera Control Layer Bypassed")
    }

    fun toggleCameraLayerLocked() {
        val updated = ProjectMutator.toggleCameraLayerLocked(_uiState.value.project)
        commitProjectChange(updated)
        val locked = updated.activeScene.cameraLayerLocked
        showStatus(if (locked) "Camera Control Layer Locked" else "Camera Control Layer Unlocked")
    }

    fun selectCameraKeyframe(frame: Int) {
        stopPlayback()
        seekToFrame(frame)
        showStatus("Camera Keyframe at F${frame + 1}")
    }

    fun updateCameraKeyframeEasing(easing: EasingType) {
        val state = _uiState.value
        if (state.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.updateCameraKeyframeEasing(state.project, frame, easing)
        commitProjectChange(updated)
        showStatus("Camera easing: ${easing.displayName}")
    }

    fun updateCameraAtCurrentFrame(updater: (CameraKeyframe) -> CameraKeyframe) {
        val state = _uiState.value
        if (state.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.upsertCameraKeyframeAtFrame(state.project, frame, updater)
        commitProjectChange(updated, coalesceUndoTag = "camera_$frame")
    }

    fun setCameraFocusClip(clipId: String?) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(cameraFocusClipId = clipId)
        }
        commitProjectChange(updated)
        if (clipId == null) {
            showStatus("Camera Focus Tracking OFF")
        } else {
            val clipName = updated.activeScene.clips.firstOrNull { it.id == clipId }?.name ?: "Character"
            showStatus("Camera Tracking: $clipName")
        }
    }

    fun deleteCameraKeyframeAtCurrentFrame() {
        val state = _uiState.value
        if (state.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val frame = _playbackFrame.value
        val updated = ProjectMutator.deleteCameraKeyframeAtFrame(state.project, frame)
        commitProjectChange(updated)
        showStatus("Removed camera keyframe at F${frame + 1}")
    }

    fun resetAllCameraKeyframes() {
        if (_uiState.value.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val updated = ProjectMutator.updateActiveScene(ProjectMutator.resetCameraKeyframes(_uiState.value.project)) { scene ->
            scene.copy(cameraFocusClipId = null)
        }
        commitProjectChange(updated)
        showStatus("Camera reset to default 100% stage view")
    }

    fun setSceneChromaKeyMode(mode: ChromaKeyMode) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(chromaKeyMode = mode)
        }
        commitProjectChange(updated)
        showStatus("Stage Background: ${mode.displayName}")
    }

    fun applyCinematicCameraPreset(presetId: String, label: String, intensity: Float = 1.0f) {
        if (_uiState.value.project.activeScene.cameraLayerLocked) {
            showStatus("Camera Control layer is locked")
            return
        }
        val updated = ProjectMutator.applyCinematicCameraPreset(
            project = _uiState.value.project,
            startFrame = _playbackFrame.value,
            presetId = presetId,
            intensity = intensity
        )
        commitProjectChange(updated)
        showStatus("Camera preset: $label")
    }

    fun updateSceneVfxConfig(updater: (SceneVfxConfig) -> SceneVfxConfig) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(vfxConfig = updater(scene.vfxConfig))
        }
        commitProjectChange(updated, coalesceUndoTag = "scene_vfx")
    }

    fun exportBlank2048ModularChartTemplate(onSavedPath: (String) -> Unit = {}) {
        viewModelScope.launch {
            val savedPath = assetStorage.saveBlank2048ModularChartTemplate()
            if (savedPath != null) {
                onSavedPath(savedPath)
                showStatus("Saved 2048×2048 Modular Chart Template PNG")
            } else {
                showStatus("Failed to generate 2048×2048 template")
            }
        }
    }

    fun setBrushStyle(colorHex: String, widthRatio: Float, style: BrushStyle = _uiState.value.brushStyle) {
        _uiState.update {
            it.copy(
                brushColorHex = colorHex,
                brushWidthRatio = widthRatio,
                brushStyle = style
            )
        }
    }

    fun finishDrawingStroke(points: List<NormalizedPoint>) {
        if (points.size < 2) return
        val state = _uiState.value
        val stroke = DrawingStroke(
            points = points,
            colorHex = state.brushColorHex,
            strokeWidthRatio = state.brushWidthRatio,
            brushStyle = state.brushStyle
        )
        val (updated, clipId) = ProjectMutator.addDrawingStroke(state.project, stroke, _playbackFrame.value)
        commitProjectChange(updated)
        _uiState.update { it.copy(selectedClipId = clipId) }
    }

    fun undoLastDrawingStroke() {
        val state = _uiState.value
        val (updated, removed) = ProjectMutator.undoLastDrawingStroke(state.project, _playbackFrame.value)
        if (removed) {
            commitProjectChange(updated)
            showStatus("Undid last brush stroke")
        } else {
            showStatus("No brush strokes to undo")
        }
    }

    fun clearDrawingStrokes() {
        val state = _uiState.value
        val (updated, cleared) = ProjectMutator.clearDrawingStrokes(state.project, _playbackFrame.value)
        if (cleared) {
            commitProjectChange(updated)
            showStatus("Cleared sketch strokes")
        } else {
            showStatus("Sketch layer is already empty")
        }
    }

    fun setSceneBackgroundColor(hex: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(
                backgroundColorHex = hex,
                backgroundBundledKey = null,
                backgroundImagePath = null,
                chromaKeyMode = ChromaKeyMode.NONE
            )
        }
        commitProjectChange(updated)
    }

    fun setSceneBackgroundBundled(key: String) {
        val updated = ProjectMutator.updateActiveScene(_uiState.value.project) { scene ->
            scene.copy(
                backgroundBundledKey = key,
                backgroundImagePath = null,
                chromaKeyMode = ChromaKeyMode.NONE
            )
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
            val nextProj = proj.copy(activeSceneIndex = index)
            _playbackFrame.value = 0
            _uiState.update {
                it.copy(
                    project = nextProj,
                    currentFrame = 0,
                    selectedClipId = null
                )
            }
            prefetchActiveScene(nextProj)
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

    fun duplicateActiveScene() {
        stopPlayback()
        val updated = ProjectMutator.duplicateActiveScene(_uiState.value.project)
        commitProjectChange(updated)
        _playbackFrame.value = 0
        _uiState.update { it.copy(currentFrame = 0, selectedClipId = null) }
        prefetchActiveScene(updated)
        showStatus("Duplicated scene: ${updated.activeScene.name}")
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
        if (_uiState.value.isPlaying) {
            stopPlayback()
        }
        val maxFrame = (_uiState.value.project.activeScene.durationFrames - 1).coerceAtLeast(0)
        val clamped = frame.coerceIn(0, maxFrame)
        if (_playbackFrame.value != clamped) {
            _playbackFrame.value = clamped
        }
        if (_uiState.value.currentFrame != clamped) {
            _uiState.update { it.copy(currentFrame = clamped) }
        }
    }

    fun stepFrame(delta: Int) {
        stopPlayback()
        seekToFrame(_playbackFrame.value + delta)
    }

    fun toggleLooping() {
        _uiState.update { it.copy(isLooping = !it.isLooping) }
    }

    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)
        val current = _uiState.value.playbackSpeed
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.05f }
        val next = speeds[(idx + 1) % speeds.size]
        _uiState.update { it.copy(playbackSpeed = next) }
        applyMediaPlayerSpeed()
        val label = if (next == next.toInt().toFloat()) "${next.toInt()}x" else "${next}x"
        showStatus("Playback Speed: $label")
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
            var anchorFrame = startFrame
            var anchorTimeNs = System.nanoTime()
            var lastSpeed = _uiState.value.playbackSpeed.coerceIn(0.25f, 2.0f)

            while (isActive && _uiState.value.isPlaying) {
                val nowNs = System.nanoTime()
                val speed = _uiState.value.playbackSpeed.coerceIn(0.25f, 2.0f)
                if (kotlin.math.abs(speed - lastSpeed) > 0.01f) {
                    anchorFrame = _playbackFrame.value
                    anchorTimeNs = nowNs
                    lastSpeed = speed
                }
                val fps = _uiState.value.project.fps.coerceIn(12, 60)
                val frameDurationNs = (1_000_000_000.0 / (fps * speed)).toLong().coerceAtLeast(4_000_000L)
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
                    syncAudioPreviewForFrame(targetRaw)
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

    fun setWaveformSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(
            com.example.engine.AudioWaveformJsonCodec.MIN_SENSITIVITY,
            com.example.engine.AudioWaveformJsonCodec.MAX_SENSITIVITY
        )
        _uiState.update { it.copy(waveformSensitivity = clamped) }
    }

    private fun refreshAudioSyncTracks(project: ProjectDocument, sensitivity: Float): ProjectDocument {
        val audioClips = project.activeScene.clips.filter { !it.audioPath.isNullOrBlank() }
        if (audioClips.isEmpty()) return project
        val updatedById = project.audioSyncTracks.associateBy { it.clipId }.toMutableMap()
        for (clip in audioClips) {
            val cachedWaveform = com.example.engine.AudioWaveformExtractor.getCached(clip.audioPath)
            updatedById[clip.id] = com.example.engine.AudioWaveformJsonCodec.buildAudioSyncMetadata(
                project = project,
                audioClip = clip,
                waveform = cachedWaveform,
                sensitivity = sensitivity
            )
        }
        return project.copy(audioSyncTracks = updatedById.values.toList())
    }

    fun trimAudioClip(
        clipId: String,
        newStartFrame: Int,
        newDurationFrames: Int,
        newTrimStartMs: Long,
        newTrimEndMs: Long
    ) {
        stopPlayback()
        val trimmed = ProjectMutator.trimAudioClip(
            project = _uiState.value.project,
            clipId = clipId,
            newStartFrame = newStartFrame,
            newDurationFrames = newDurationFrames,
            newTrimStartMs = newTrimStartMs,
            newTrimEndMs = newTrimEndMs
        )
        val updated = refreshAudioSyncTracks(trimmed, _uiState.value.waveformSensitivity)
        commitProjectChange(updated)
        val startSec = newTrimStartMs / 1000f
        val endSec = if (newTrimEndMs > 0L) newTrimEndMs / 1000f else (newTrimStartMs + (newDurationFrames * 1000L / updated.fps.coerceAtLeast(1))) / 1000f
        showStatus(String.format(java.util.Locale.US, "Trimmed audio: %.1fs – %.1fs", startSec, endSec))
    }

    fun resetSelectedAudioTrim() {
        stopPlayback()
        val scene = _uiState.value.project.activeScene
        val targetClip = _uiState.value.selectedClip?.takeIf { !it.audioPath.isNullOrBlank() }
            ?: scene.clips.firstOrNull { !it.audioPath.isNullOrBlank() }
            ?: return
        val fullDurationMs = com.example.engine.AudioWaveformExtractor.getCached(targetClip.audioPath)?.durationMs ?: 0L
        val reset = ProjectMutator.resetAudioTrim(
            project = _uiState.value.project,
            clipId = targetClip.id,
            fullAudioDurationMs = fullDurationMs
        )
        val updated = refreshAudioSyncTracks(reset, _uiState.value.waveformSensitivity)
        commitProjectChange(updated)
        showStatus("Reset audio trim to full clip")
    }

    fun saveWaveformJsonToProject(clipName: String, jsonContent: String, onResult: (String) -> Unit) {
        val currentProject = _uiState.value.project
        val syncedProject = refreshAudioSyncTracks(currentProject, _uiState.value.waveformSensitivity)
        if (syncedProject != currentProject) {
            commitProjectChange(syncedProject)
        }
        viewModelScope.launch {
            val savedFile = assetStorage.saveProjectWaveformSidecar(syncedProject.id, clipName, jsonContent)
            if (savedFile != null) {
                showStatus("Saved ${savedFile.name} & project audio-sync metadata")
                onResult("Saved to project: ${savedFile.name}")
            } else {
                onResult("Could not save waveform JSON to project")
            }
        }
    }

    fun writeWaveformJsonToSafUri(uri: Uri, jsonContent: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val ok = assetStorage.writeTextToSafUri(uri, jsonContent)
            if (ok) {
                showStatus("Exported waveform lip-sync JSON")
                onResult("Exported .waveform.json successfully")
            } else {
                onResult("Could not write selected file")
            }
        }
    }

    fun exportBlueprintToSafUri(uri: Uri, blueprintJson: String) {
        viewModelScope.launch {
            val ok = assetStorage.writeTextToSafUri(uri, blueprintJson)
            if (ok) {
                showStatus("Exported .fcblueprint.json (v3)")
            } else {
                showStatus("Could not write .fcblueprint.json file")
            }
        }
    }

    fun applyDirectorScript(scriptJson: String, mode: com.example.engine.DirectorScriptImportMode) {
        stopPlayback()
        val stateSnapshot = _uiState.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.example.engine.DirectorScriptReceiver.compileIntoProject(
                    context = getApplication(),
                    scriptJson = scriptJson,
                    currentProject = stateSnapshot.project,
                    existingCharacters = stateSnapshot.characters,
                    existingMediaAssets = stateSnapshot.mediaAssets,
                    importMode = mode
                )
            }
            result.onSuccess { compiled ->
                // Persist newly spawned inline SVG characters & media assets to local offline repository
                for (charRig in compiled.spawnedCharacters) {
                    repository.saveCharacter(charRig)
                }
                for (asset in compiled.spawnedMediaAssets) {
                    repository.saveMediaAsset(asset)
                }

                val mergedCharacters = if (compiled.spawnedCharacters.isNotEmpty()) {
                    val existingIds = stateSnapshot.characters.map { it.id }.toSet()
                    stateSnapshot.characters + compiled.spawnedCharacters.filter { it.id !in existingIds }
                } else {
                    stateSnapshot.characters
                }
                val mergedMedia = if (compiled.spawnedMediaAssets.isNotEmpty()) {
                    val existingIds = stateSnapshot.mediaAssets.map { it.id }.toSet()
                    stateSnapshot.mediaAssets + compiled.spawnedMediaAssets.filter { it.id !in existingIds }
                } else {
                    stateSnapshot.mediaAssets
                }

                // Automatic Undo snapshot before committing compiled director script
                commitProjectChange(compiled.project)
                _playbackFrame.value = 0
                _uiState.update { cur ->
                    cur.copy(
                        characters = mergedCharacters,
                        mediaAssets = mergedMedia,
                        currentFrame = 0,
                        selectedClipId = compiled.project.activeScene.clips.firstOrNull()?.id,
                        activeSheet = ActiveEditorSheet.NONE
                    )
                }
                assetStorage.prefetchSceneAssets(
                    compiled.project.activeScene,
                    mergedCharacters.associateBy { it.id }
                )
                saveCurrentProject(silent = true)
                val r = compiled.report
                showStatus(
                    "Script applied: ${r.sceneCount} scene(s), ${r.clipCount} clip(s), ${r.inlineSvgCount} SVG(s), ${r.cameraShotCount} cam shot(s)"
                )
            }.onFailure { err ->
                showStatus("Director script error: ${err.message ?: "invalid JSON"}")
            }
        }
    }

    private fun activePlayableAudioClip(): TimelineClip? {
        val scene = _uiState.value.project.activeScene
        return scene.clips.firstOrNull { clip ->
            if (clip.audioPath.isNullOrBlank()) return@firstOrNull false
            val layer = scene.layers.firstOrNull { it.id == clip.layerId }
            layer?.isVisible != false
        }
    }

    private fun applyMediaPlayerSpeed() {
        val mp = mediaPlayer ?: return
        if (!isAudioPrepared) return
        val audioClip = activePlayableAudioClip() ?: return
        val combinedSpeed = (_uiState.value.playbackSpeed * audioClip.speedMultiplier).coerceIn(0.25f, 4.0f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            runCatching {
                val params = mp.playbackParams
                if (kotlin.math.abs(params.speed - combinedSpeed) > 0.02f) {
                    mp.playbackParams = params.setSpeed(combinedSpeed)
                }
            }
        }
    }

    private fun startAudioPreviewIfPresent() {
        val state = _uiState.value
        val audioClip = activePlayableAudioClip() ?: return
        val file = File(audioClip.audioPath!!)
        if (!file.exists()) return
        val startFrameAtLaunch = _playbackFrame.value
        val fps = state.project.fps.coerceAtLeast(1)
        val safeSpeed = audioClip.speedMultiplier.coerceIn(0.1f, 4f)
        runCatching {
            stopAudioPreview()
            val player = MediaPlayer()
            mediaPlayer = player
            isAudioPrepared = false
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { mp ->
                if (mp === mediaPlayer && _uiState.value.isPlaying) {
                    isAudioPrepared = true
                    val currentF = _playbackFrame.value.coerceAtLeast(startFrameAtLaunch)
                    val endLimitMs = if (audioClip.audioTrimEndMs > audioClip.audioTrimStartMs) {
                        audioClip.audioTrimEndMs.toInt().coerceAtMost(mp.duration)
                    } else {
                        mp.duration
                    }
                    if (currentF in audioClip.startFrame until audioClip.endFrame) {
                        val localOffsetMs = (((currentF - audioClip.startFrame) * 1000f * safeSpeed) / fps).toInt()
                        val seekMs = (audioClip.audioTrimStartMs.toInt() + localOffsetMs).coerceIn(0, endLimitMs)
                        if (seekMs < endLimitMs) {
                            mp.seekTo(seekMs)
                            mp.start()
                            applyMediaPlayerSpeed()
                        }
                    } else if (currentF < audioClip.startFrame) {
                        val seekMs = audioClip.audioTrimStartMs.toInt().coerceIn(0, endLimitMs)
                        mp.seekTo(seekMs)
                    }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                isAudioPrepared = false
                true
            }
            // Non-blocking async preparation so pressing Play never freezes the UI thread
            player.prepareAsync()
        }
    }

    private fun syncAudioPreviewForFrame(frame: Int) {
        val mp = mediaPlayer ?: return
        if (!isAudioPrepared) return
        val audioClip = activePlayableAudioClip() ?: run {
            runCatching { if (mp.isPlaying) mp.pause() }
            return
        }
        val fps = _uiState.value.project.fps.coerceAtLeast(1)
        val safeSpeed = audioClip.speedMultiplier.coerceIn(0.1f, 4f)
        runCatching {
            val endLimitMs = if (audioClip.audioTrimEndMs > audioClip.audioTrimStartMs) {
                audioClip.audioTrimEndMs.toInt().coerceAtMost(mp.duration)
            } else {
                mp.duration
            }
            val insideClip = frame in audioClip.startFrame until audioClip.endFrame
            if (!insideClip) {
                if (mp.isPlaying) mp.pause()
                return
            }
            val expectedMs = audioClip.audioTrimStartMs.toInt() +
                (((frame - audioClip.startFrame) * 1000f * safeSpeed) / fps).toInt()
            if (expectedMs >= endLimitMs) {
                if (mp.isPlaying) mp.pause()
            } else if (!mp.isPlaying) {
                mp.seekTo(expectedMs.coerceIn(0, endLimitMs))
                mp.start()
                applyMediaPlayerSpeed()
            }
        }
    }

    private fun restartAudioPreview() {
        val mp = mediaPlayer ?: return
        if (!isAudioPrepared) return
        val audioClip = activePlayableAudioClip()
        runCatching {
            val startSeekMs = audioClip?.audioTrimStartMs?.toInt()?.coerceIn(0, mp.duration) ?: 0
            mp.seekTo(startSeekMs)
            if (audioClip == null || audioClip.startFrame == 0) {
                mp.start()
            } else if (mp.isPlaying) {
                mp.pause()
            }
        }
    }

    private fun stopAudioPreview() {
        isAudioPrepared = false
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
    }

    // --- Export ---
    fun startExport(format: ExportFormat, resolution: ExportResolution, fps: Int, durationFrames: Int) {
        stopPlayback()
        saveCurrentProject(silent = true)
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
        lastCoalescedUndoTag = null
        val prev = undoStack.removeLast()
        redoStack.addLast(_uiState.value.project)
        _uiState.update {
            it.copy(project = prev, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        saveCurrentProject(silent = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        lastCoalescedUndoTag = null
        val next = redoStack.removeLast()
        undoStack.addLast(_uiState.value.project)
        _uiState.update {
            it.copy(project = next, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        saveCurrentProject(silent = true)
    }

    private fun commitProjectChange(newProject: ProjectDocument, coalesceUndoTag: String? = null) {
        val current = _uiState.value.project
        if (newProject == current) return
        val nowMs = System.currentTimeMillis()
        val shouldCoalesce = coalesceUndoTag != null &&
            coalesceUndoTag == lastCoalescedUndoTag &&
            (nowMs - lastCoalescedUndoMs) < UNDO_COALESCE_WINDOW_MS
        if (!shouldCoalesce) {
            pushUndoSnapshot(current)
        }
        lastCoalescedUndoTag = coalesceUndoTag
        lastCoalescedUndoMs = nowMs
        _uiState.update {
            it.copy(project = newProject, canUndo = undoStack.isNotEmpty(), canRedo = false)
        }
        prefetchActiveScene(newProject)
        if (coalesceUndoTag != null) {
            scheduleDebouncedSave()
        } else {
            saveCurrentProject(silent = true)
        }
    }

    private fun pushUndoSnapshot(snapshot: ProjectDocument) {
        if (undoStack.lastOrNull() == snapshot) return
        undoStack.addLast(snapshot)
        while (undoStack.size > MAX_UNDO_HISTORY) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun prefetchActiveScene(project: ProjectDocument) {
        assetStorage.prefetchSceneAssets(project.activeScene, _uiState.value.charactersById)
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        lastCoalescedUndoTag = null
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
        sceneRenderer.clearScaledCache()
        assetStorage.shutdown()
    }
}
