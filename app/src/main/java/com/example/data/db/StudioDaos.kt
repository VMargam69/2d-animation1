package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query(
        """
        SELECT id, name, width, height, aspectRatioLabel, fps, sceneCount,
               durationFrames, previewBgKey, previewBgColorHex, isLastOpened, updatedAt
        FROM projects
        ORDER BY updatedAt DESC
        """
    )
    fun observeProjectSummaries(): Flow<List<ProjectSummary>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE isLastOpened = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLastOpenedProject(): ProjectEntity?

    @Query("UPDATE projects SET isLastOpened = 0")
    suspend fun clearLastOpenedFlags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET name = :newName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameProject(id: String, newName: String, updatedAt: Long)

    @Delete
    suspend fun deleteProjectEntity(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun countProjects(): Int
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY isPinned DESC, isCustom DESC, createdAt DESC")
    fun observeAllCharacters(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    suspend fun getCharacterById(id: String): CharacterEntity?

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun countCharacters(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacter(character: CharacterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(characters: List<CharacterEntity>)

    @Update
    suspend fun updateCharacter(character: CharacterEntity)

    @Query("UPDATE characters SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Delete
    suspend fun deleteCharacterEntity(character: CharacterEntity)

    @Query("DELETE FROM characters WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomCharacter(id: String)
}

@Dao
interface AnimationPresetDao {
    @Query("SELECT * FROM animation_presets ORDER BY isCustom DESC, createdAt ASC")
    fun observeAllPresets(): Flow<List<AnimationPresetEntity>>

    @Query("SELECT * FROM animation_presets WHERE id = :id LIMIT 1")
    suspend fun getPresetById(id: String): AnimationPresetEntity?

    @Query("SELECT COUNT(*) FROM animation_presets")
    suspend fun countPresets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: AnimationPresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(presets: List<AnimationPresetEntity>)

    @Update
    suspend fun updatePreset(preset: AnimationPresetEntity)

    @Delete
    suspend fun deletePresetEntity(preset: AnimationPresetEntity)

    @Query("DELETE FROM animation_presets WHERE id = :id")
    suspend fun deletePresetById(id: String)
}

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets ORDER BY createdAt DESC")
    fun observeAllMediaAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE id = :id LIMIT 1")
    suspend fun getMediaAssetById(id: String): MediaAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaAsset(asset: MediaAssetEntity)

    @Update
    suspend fun updateMediaAsset(asset: MediaAssetEntity)

    @Delete
    suspend fun deleteMediaAssetEntity(asset: MediaAssetEntity)

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteMediaAsset(id: String)
}
