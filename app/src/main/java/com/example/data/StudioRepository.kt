package com.example.data

import android.net.Uri
import com.example.data.db.*
import com.example.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class StudioRepository(
    private val projectDao: ProjectDao,
    private val characterDao: CharacterDao,
    private val animationPresetDao: AnimationPresetDao,
    private val mediaAssetDao: MediaAssetDao,
    val assetStorage: LocalAssetStorage
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val projectAdapter = moshi.adapter(ProjectDocument::class.java)
    private val skeletonAdapter = moshi.adapter(SkeletonRigConfig::class.java)

    val projectSummaries: Flow<List<ProjectSummary>> = projectDao.observeProjectSummaries()

    val characters: Flow<List<CharacterRigDefinition>> = characterDao.observeAllCharacters().map { entities ->
        entities.map { it.toModel() }
    }

    val animationPresets: Flow<List<AnimationPreset>> = animationPresetDao.observeAllPresets().map { entities ->
        if (entities.isEmpty()) BuiltInCatalog.animationPresets else entities.map { it.toModel() }
    }

    val mediaAssets: Flow<List<LocalMediaAsset>> = mediaAssetDao.observeAllMediaAssets().map { userEntities ->
        val imported = userEntities.map { it.toModel() }
        imported + BuiltInCatalog.bundledMediaAssets
    }

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (characterDao.countCharacters() == 0) {
            characterDao.insertAllIgnore(BuiltInCatalog.builtInCharacters.map { it.toEntity() })
        }
        // Always ensure bundled offline HD SVG/PNG packs from assets/characters/ are registered
        val bundledPacks = assetStorage.loadBundledAssetPacks()
        if (bundledPacks.isNotEmpty()) {
            bundledPacks.forEach { pack ->
                pack.spriteAtlasRig?.let { cfg ->
                    if (assetStorage.loadAtlasSidecarConfig(pack.id) == null) {
                        assetStorage.saveAtlasSidecarConfig(pack.id, cfg)
                    }
                }
            }
            characterDao.insertAllIgnore(bundledPacks.map { it.toEntity() })
        }
        if (animationPresetDao.countPresets() == 0) {
            animationPresetDao.insertAllIgnore(BuiltInCatalog.animationPresets.map { it.toEntity() })
        }
        if (projectDao.countProjects() == 0) {
            val starterProject = BuiltInCatalog.sceneTemplates.first().buildProject("Village Sunset Walk")
            saveProject(starterProject, markAsLastOpened = true)
        }
    }

    suspend fun saveAnimationPreset(preset: AnimationPreset, isCustom: Boolean = true) = withContext(Dispatchers.IO) {
        animationPresetDao.upsertPreset(preset.toEntity(isCustom = isCustom))
    }

    suspend fun deleteAnimationPreset(id: String) = withContext(Dispatchers.IO) {
        animationPresetDao.deletePresetById(id)
    }

    suspend fun saveProject(document: ProjectDocument, markAsLastOpened: Boolean = true): ProjectDocument =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = document.copy(updatedAt = now)
            val json = projectAdapter.toJson(updated)
            if (markAsLastOpened) {
                projectDao.clearLastOpenedFlags()
            }
            val firstScene = updated.activeScene
            val entity = ProjectEntity(
                id = updated.id,
                name = updated.name,
                width = updated.width,
                height = updated.height,
                aspectRatioLabel = updated.aspectRatioPreset.label,
                fps = updated.fps,
                sceneCount = updated.scenes.size,
                durationFrames = firstScene.durationFrames,
                previewBgKey = firstScene.backgroundBundledKey,
                previewBgColorHex = firstScene.backgroundColorHex,
                documentJson = json,
                isLastOpened = markAsLastOpened,
                createdAt = updated.createdAt,
                updatedAt = now
            )
            projectDao.upsertProject(entity)
            updated
        }

    suspend fun loadProject(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val entity = projectDao.getProjectById(id) ?: return@withContext null
        val doc = runCatching { projectAdapter.fromJson(entity.documentJson) }.getOrNull()
        if (doc != null) {
            projectDao.clearLastOpenedFlags()
            projectDao.upsertProject(entity.copy(isLastOpened = true))
        }
        doc
    }

    suspend fun recoverLastOpenedProject(): ProjectDocument? = withContext(Dispatchers.IO) {
        val entity = projectDao.getLastOpenedProject() ?: return@withContext null
        runCatching { projectAdapter.fromJson(entity.documentJson) }.getOrNull()
    }

    suspend fun renameProject(id: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim().ifBlank { "Untitled Animation" }
        val existing = projectDao.getProjectById(id) ?: return@withContext
        val doc = runCatching { projectAdapter.fromJson(existing.documentJson) }.getOrNull()
        if (doc != null) {
            saveProject(doc.copy(name = trimmed), markAsLastOpened = existing.isLastOpened)
        } else {
            projectDao.renameProject(id, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun duplicateProject(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val existing = loadProject(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = existing.copy(
            id = UUID.randomUUID().toString(),
            name = "${existing.name} Copy",
            createdAt = now,
            updatedAt = now
        )
        saveProject(copy, markAsLastOpened = false)
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        projectDao.deleteProject(id)
    }

    suspend fun saveCharacter(character: CharacterRigDefinition) = withContext(Dispatchers.IO) {
        character.spriteAtlasRig?.let { cfg ->
            assetStorage.saveAtlasSidecarConfig(character.id, cfg)
        }
        characterDao.upsertCharacter(character.toEntity())
    }

    suspend fun importCharacterPack(uri: Uri): com.example.engine.CharacterPackImportResult = withContext(Dispatchers.IO) {
        val result = assetStorage.importCharacterPackUri(uri)
        val rawCharDef = result.character ?: return@withContext result

        val existingChars = characterDao.observeAllCharacters().first()
        val existingIds = existingChars.map { it.id }.toSet()
        val existingNames = existingChars.map { it.name }

        val uniqueId = if (rawCharDef.id in existingIds || rawCharDef.id.startsWith("bundled_")) {
            "pack_${UUID.randomUUID().toString().take(12)}"
        } else {
            rawCharDef.id
        }
        val uniqueName = com.example.engine.CharacterPackImporter.ensureUniqueCharacterName(
            baseName = rawCharDef.name,
            existingNames = existingNames
        )
        val finalRig = rawCharDef.spriteAtlasRig?.copy(textureName = uniqueName)
        val charDef = rawCharDef.copy(
            id = uniqueId,
            name = uniqueName,
            spriteAtlasRig = finalRig
        )

        finalRig?.let { cfg ->
            assetStorage.saveAtlasSidecarConfig(charDef.id, cfg)
        }
        characterDao.upsertCharacter(charDef.toEntity())
        // Warm up slice cache immediately in background
        assetStorage.loadOrSliceAtlasBlocking(charDef.customImagePath)
        result.copy(
            character = charDef,
            message = result.message.replace(rawCharDef.name, uniqueName)
        )
    }

    suspend fun togglePinCharacter(id: String, currentlyPinned: Boolean) = withContext(Dispatchers.IO) {
        characterDao.setPinned(id, !currentlyPinned)
    }

    suspend fun deleteCustomCharacter(character: CharacterRigDefinition) = withContext(Dispatchers.IO) {
        if (character.isCustom) {
            assetStorage.deleteOfflineFile(character.customImagePath)
            assetStorage.deleteAtlasSidecarConfig(character.id)
            characterDao.deleteCustomCharacter(character.id)
        }
    }

    suspend fun importMediaAsset(
        uri: Uri,
        category: String = "Imported",
        preferredType: MediaAssetType? = null
    ): LocalMediaAsset? = withContext(Dispatchers.IO) {
        val result = assetStorage.importUriToOfflineStorage(uri, preferredType) ?: return@withContext null
        val entity = MediaAssetEntity(
            id = UUID.randomUUID().toString(),
            name = result.displayName,
            category = category,
            typeName = result.mediaType.name,
            localFilePath = result.filePath,
            mimeType = result.mimeType,
            createdAt = System.currentTimeMillis()
        )
        mediaAssetDao.upsertMediaAsset(entity)
        entity.toModel()
    }

    suspend fun deleteMediaAsset(asset: LocalMediaAsset) = withContext(Dispatchers.IO) {
        if (!asset.isBundled) {
            assetStorage.deleteOfflineFile(asset.localFilePath)
            mediaAssetDao.deleteMediaAsset(asset.id)
        }
    }

    fun serializeProjectJson(document: ProjectDocument): String = projectAdapter.toJson(document)

    fun deserializeProjectJson(json: String): ProjectDocument? =
        runCatching { projectAdapter.fromJson(json) }.getOrNull()

    private fun CharacterEntity.toModel(): CharacterRigDefinition {
        val cat = runCatching { CharacterCategory.valueOf(categoryName) }.getOrDefault(CharacterCategory.DESI)
        val body = runCatching { CharacterBodyType.valueOf(bodyTypeName) }.getOrDefault(CharacterBodyType.HUMANOID)
        val acc = runCatching { CharacterAccessory.valueOf(accessoryName) }.getOrDefault(CharacterAccessory.NONE)
        val costume = runCatching { HumanCostumeStyle.valueOf(costumeStyleName) }.getOrDefault(HumanCostumeStyle.CASUAL_JACKET)
        val hair = runCatching { HumanHairStyle.valueOf(hairStyleName) }.getOrDefault(HumanHairStyle.SIDE_PART)
        val build = runCatching { HumanBuildType.valueOf(buildTypeName) }.getOrDefault(HumanBuildType.STANDARD)
        val skel = skeletonRigJson?.let { runCatching { skeletonAdapter.fromJson(it) }.getOrNull() }
        val atlasSidecar = assetStorage.loadAtlasSidecarConfig(id) ?: if (skel?.rigMode == CustomRigMode.ATLAS_RIG_PRO) {
            SpriteAtlasRigConfig(
                poseTemplate = AtlasPoseTemplate.THREE_QUARTER_FRONT,
                resolutionPx = 2048,
                textureName = name,
                renderMode = if (customImagePath?.endsWith(".svg", true) == true) "SVG" else "PNG",
                importedFullSheetPath = customImagePath
            )
        } else null
        return CharacterRigDefinition(
            id = id,
            name = name,
            category = cat,
            bodyType = body,
            skinColorHex = skinColorHex,
            primaryColorHex = primaryColorHex,
            secondaryColorHex = secondaryColorHex,
            accentColorHex = accentColorHex,
            hairColorHex = hairColorHex,
            eyeColorHex = eyeColorHex,
            costumeStyle = costume,
            hairStyle = hair,
            buildType = build,
            accessory = acc,
            supportedOrientations = CharacterOrientation.entries,
            isPinned = isPinned,
            isCustom = isCustom,
            isLatest = isLatest,
            customImagePath = customImagePath,
            skeletonRig = skel,
            spriteAtlasRig = atlasSidecar,
            createdAt = createdAt
        )
    }

    private fun CharacterRigDefinition.toEntity(): CharacterEntity = CharacterEntity(
        id = id,
        name = name,
        categoryName = category.name,
        bodyTypeName = bodyType.name,
        skinColorHex = skinColorHex,
        primaryColorHex = primaryColorHex,
        secondaryColorHex = secondaryColorHex,
        accentColorHex = accentColorHex,
        hairColorHex = hairColorHex,
        eyeColorHex = eyeColorHex,
        costumeStyleName = costumeStyle.name,
        hairStyleName = hairStyle.name,
        buildTypeName = buildType.name,
        accessoryName = accessory.name,
        isPinned = isPinned,
        isCustom = isCustom,
        isLatest = isLatest,
        customImagePath = customImagePath,
        skeletonRigJson = skeletonRig?.let { skeletonAdapter.toJson(it) },
        createdAt = createdAt
    )

    private fun MediaAssetEntity.toModel(): LocalMediaAsset {
        val mediaType = runCatching { MediaAssetType.valueOf(typeName) }.getOrDefault(MediaAssetType.IMAGE)
        return LocalMediaAsset(
            id = id,
            name = name,
            category = category,
            type = mediaType,
            localFilePath = localFilePath,
            bundledResKey = null,
            mimeType = mimeType,
            isBundled = false,
            createdAt = createdAt
        )
    }

    private fun AnimationPresetEntity.toModel(): AnimationPreset {
        val type = runCatching { AnimationPresetType.valueOf(typeName) }.getOrDefault(AnimationPresetType.IDLE)
        val orientations = supportedOrientationsCsv
            .split(',')
            .mapNotNull { runCatching { CharacterOrientation.valueOf(it.trim()) }.getOrNull() }
            .ifEmpty { CharacterOrientation.entries }
        return AnimationPreset(
            id = id,
            type = type,
            name = name,
            tag = tag,
            defaultDurationFrames = defaultDurationFrames,
            supportedOrientations = orientations,
            description = description
        )
    }

    private fun AnimationPreset.toEntity(isCustom: Boolean = false): AnimationPresetEntity = AnimationPresetEntity(
        id = id,
        typeName = type.name,
        name = name,
        tag = tag,
        defaultDurationFrames = defaultDurationFrames,
        description = description,
        supportedOrientationsCsv = supportedOrientations.joinToString(",") { it.name },
        isCustom = isCustom
    )
}
