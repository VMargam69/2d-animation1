package com.example.data.db

import android.annotation.SuppressLint
import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.reflect.KClass

@SuppressLint("RestrictedApi")
@Suppress("UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL")
class FrameCraftDatabase_Impl : FrameCraftDatabase() {
    private val _projectDao: Lazy<ProjectDao> = lazy {
        ProjectDao_Impl(this)
    }

    private val _characterDao: Lazy<CharacterDao> = lazy {
        CharacterDao_Impl(this)
    }

    private val _animationPresetDao: Lazy<AnimationPresetDao> = lazy {
        AnimationPresetDao_Impl(this)
    }

    private val _mediaAssetDao: Lazy<MediaAssetDao> = lazy {
        MediaAssetDao_Impl(this)
    }

    protected override fun createOpenDelegate(): RoomOpenDelegate {
        val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(
            3,
            "25f3f263289361eb868f339ac65d9354",
            "3c921261f6b92e79d5c3fc67e76c6c48"
        ) {
            override fun createAllTables(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `aspectRatioLabel` TEXT NOT NULL, `fps` INTEGER NOT NULL, `sceneCount` INTEGER NOT NULL, `durationFrames` INTEGER NOT NULL, `previewBgKey` TEXT, `previewBgColorHex` TEXT NOT NULL, `documentJson` TEXT NOT NULL, `isLastOpened` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `characters` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `bodyTypeName` TEXT NOT NULL, `skinColorHex` TEXT NOT NULL, `primaryColorHex` TEXT NOT NULL, `secondaryColorHex` TEXT NOT NULL, `accentColorHex` TEXT NOT NULL, `hairColorHex` TEXT NOT NULL, `eyeColorHex` TEXT NOT NULL, `costumeStyleName` TEXT NOT NULL, `hairStyleName` TEXT NOT NULL, `buildTypeName` TEXT NOT NULL, `accessoryName` TEXT NOT NULL, `isPinned` INTEGER NOT NULL, `isCustom` INTEGER NOT NULL, `isLatest` INTEGER NOT NULL, `customImagePath` TEXT, `skeletonRigJson` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `animation_presets` (`id` TEXT NOT NULL, `typeName` TEXT NOT NULL, `name` TEXT NOT NULL, `tag` TEXT NOT NULL, `defaultDurationFrames` INTEGER NOT NULL, `description` TEXT NOT NULL, `supportedOrientationsCsv` TEXT NOT NULL, `isCustom` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `media_assets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `typeName` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '25f3f263289361eb868f339ac65d9354')")
            }

            override fun dropAllTables(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `projects`")
                connection.execSQL("DROP TABLE IF EXISTS `characters`")
                connection.execSQL("DROP TABLE IF EXISTS `animation_presets`")
                connection.execSQL("DROP TABLE IF EXISTS `media_assets`")
            }

            override fun onCreate(connection: SQLiteConnection) {}

            override fun onOpen(connection: SQLiteConnection) {
                internalInitInvalidationTracker(connection)
            }

            override fun onPreMigrate(connection: SQLiteConnection) {
                dropFtsSyncTriggers(connection)
            }

            override fun onPostMigrate(connection: SQLiteConnection) {}

            override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
                val _columnsProjects: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsProjects["id"] = TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["name"] = TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["width"] = TableInfo.Column("width", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["height"] = TableInfo.Column("height", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["aspectRatioLabel"] = TableInfo.Column("aspectRatioLabel", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["fps"] = TableInfo.Column("fps", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["sceneCount"] = TableInfo.Column("sceneCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["durationFrames"] = TableInfo.Column("durationFrames", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["previewBgKey"] = TableInfo.Column("previewBgKey", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["previewBgColorHex"] = TableInfo.Column("previewBgColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["documentJson"] = TableInfo.Column("documentJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["isLastOpened"] = TableInfo.Column("isLastOpened", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["createdAt"] = TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsProjects["updatedAt"] = TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                val _infoProjects = TableInfo("projects", _columnsProjects, mutableSetOf(), mutableSetOf())
                val _existingProjects = read(connection, "projects")
                if (_infoProjects != _existingProjects) {
                    return RoomOpenDelegate.ValidationResult(false, "projects(com.example.data.db.ProjectEntity).\n Expected:\n$_infoProjects\n Found:\n$_existingProjects")
                }

                val _columnsCharacters: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsCharacters["id"] = TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["name"] = TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["categoryName"] = TableInfo.Column("categoryName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["bodyTypeName"] = TableInfo.Column("bodyTypeName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["skinColorHex"] = TableInfo.Column("skinColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["primaryColorHex"] = TableInfo.Column("primaryColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["secondaryColorHex"] = TableInfo.Column("secondaryColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["accentColorHex"] = TableInfo.Column("accentColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["hairColorHex"] = TableInfo.Column("hairColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["eyeColorHex"] = TableInfo.Column("eyeColorHex", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["costumeStyleName"] = TableInfo.Column("costumeStyleName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["hairStyleName"] = TableInfo.Column("hairStyleName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["buildTypeName"] = TableInfo.Column("buildTypeName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["accessoryName"] = TableInfo.Column("accessoryName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["isPinned"] = TableInfo.Column("isPinned", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["isCustom"] = TableInfo.Column("isCustom", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["isLatest"] = TableInfo.Column("isLatest", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["customImagePath"] = TableInfo.Column("customImagePath", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["skeletonRigJson"] = TableInfo.Column("skeletonRigJson", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsCharacters["createdAt"] = TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                val _infoCharacters = TableInfo("characters", _columnsCharacters, mutableSetOf(), mutableSetOf())
                val _existingCharacters = read(connection, "characters")
                if (_infoCharacters != _existingCharacters) {
                    return RoomOpenDelegate.ValidationResult(false, "characters(com.example.data.db.CharacterEntity).\n Expected:\n$_infoCharacters\n Found:\n$_existingCharacters")
                }

                val _columnsAnimationPresets: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsAnimationPresets["id"] = TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["typeName"] = TableInfo.Column("typeName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["name"] = TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["tag"] = TableInfo.Column("tag", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["defaultDurationFrames"] = TableInfo.Column("defaultDurationFrames", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["description"] = TableInfo.Column("description", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["supportedOrientationsCsv"] = TableInfo.Column("supportedOrientationsCsv", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["isCustom"] = TableInfo.Column("isCustom", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsAnimationPresets["createdAt"] = TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                val _infoAnimationPresets = TableInfo("animation_presets", _columnsAnimationPresets, mutableSetOf(), mutableSetOf())
                val _existingAnimationPresets = read(connection, "animation_presets")
                if (_infoAnimationPresets != _existingAnimationPresets) {
                    return RoomOpenDelegate.ValidationResult(false, "animation_presets(com.example.data.db.AnimationPresetEntity).\n Expected:\n$_infoAnimationPresets\n Found:\n$_existingAnimationPresets")
                }

                val _columnsMediaAssets: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsMediaAssets["id"] = TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["name"] = TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["category"] = TableInfo.Column("category", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["typeName"] = TableInfo.Column("typeName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["localFilePath"] = TableInfo.Column("localFilePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["mimeType"] = TableInfo.Column("mimeType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                _columnsMediaAssets["createdAt"] = TableInfo.Column("createdAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                val _infoMediaAssets = TableInfo("media_assets", _columnsMediaAssets, mutableSetOf(), mutableSetOf())
                val _existingMediaAssets = read(connection, "media_assets")
                if (_infoMediaAssets != _existingMediaAssets) {
                    return RoomOpenDelegate.ValidationResult(false, "media_assets(com.example.data.db.MediaAssetEntity).\n Expected:\n$_infoMediaAssets\n Found:\n$_existingMediaAssets")
                }
                return RoomOpenDelegate.ValidationResult(true, null)
            }
        }
        return _openDelegate
    }

    protected override fun createInvalidationTracker(): InvalidationTracker {
        return InvalidationTracker(
            this,
            mutableMapOf(),
            mutableMapOf(),
            "projects",
            "characters",
            "animation_presets",
            "media_assets"
        )
    }

    override fun clearAllTables() {
        super.performClear(false, "projects", "characters", "animation_presets", "media_assets")
    }

    protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
        val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
        _typeConvertersMap[ProjectDao::class] = ProjectDao_Impl.getRequiredConverters()
        _typeConvertersMap[CharacterDao::class] = CharacterDao_Impl.getRequiredConverters()
        _typeConvertersMap[AnimationPresetDao::class] = AnimationPresetDao_Impl.getRequiredConverters()
        _typeConvertersMap[MediaAssetDao::class] = MediaAssetDao_Impl.getRequiredConverters()
        return _typeConvertersMap
    }

    override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> = mutableSetOf()

    override fun createAutoMigrations(
        autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>
    ): List<Migration> = mutableListOf()

    override fun projectDao(): ProjectDao = _projectDao.value

    override fun characterDao(): CharacterDao = _characterDao.value

    override fun animationPresetDao(): AnimationPresetDao = _animationPresetDao.value

    override fun mediaAssetDao(): MediaAssetDao = _mediaAssetDao.value
}
