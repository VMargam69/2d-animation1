package com.example.data.db

import android.annotation.SuppressLint
import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@SuppressLint("RestrictedApi")
@Suppress("UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL")
class ProjectDao_Impl(
    private val __db: RoomDatabase,
) : ProjectDao {
    private val __insertAdapterOfProjectEntity: EntityInsertAdapter<ProjectEntity> =
        object : EntityInsertAdapter<ProjectEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR REPLACE INTO `projects` (`id`,`name`,`width`,`height`,`aspectRatioLabel`,`fps`,`sceneCount`,`durationFrames`,`previewBgKey`,`previewBgColorHex`,`documentJson`,`isLastOpened`,`createdAt`,`updatedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: ProjectEntity) {
                statement.bindText(1, entity.id)
                statement.bindText(2, entity.name)
                statement.bindLong(3, entity.width.toLong())
                statement.bindLong(4, entity.height.toLong())
                statement.bindText(5, entity.aspectRatioLabel)
                statement.bindLong(6, entity.fps.toLong())
                statement.bindLong(7, entity.sceneCount.toLong())
                statement.bindLong(8, entity.durationFrames.toLong())
                val _tmpPreviewBgKey = entity.previewBgKey
                if (_tmpPreviewBgKey == null) {
                    statement.bindNull(9)
                } else {
                    statement.bindText(9, _tmpPreviewBgKey)
                }
                statement.bindText(10, entity.previewBgColorHex)
                statement.bindText(11, entity.documentJson)
                statement.bindLong(12, if (entity.isLastOpened) 1L else 0L)
                statement.bindLong(13, entity.createdAt)
                statement.bindLong(14, entity.updatedAt)
            }
        }

    private val __deleteAdapterOfProjectEntity: EntityDeleteOrUpdateAdapter<ProjectEntity> =
        object : EntityDeleteOrUpdateAdapter<ProjectEntity>() {
            protected override fun createQuery(): String = "DELETE FROM `projects` WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: ProjectEntity) {
                statement.bindText(1, entity.id)
            }
        }

    private val __updateAdapterOfProjectEntity: EntityDeleteOrUpdateAdapter<ProjectEntity> =
        object : EntityDeleteOrUpdateAdapter<ProjectEntity>() {
            protected override fun createQuery(): String =
                "UPDATE OR ABORT `projects` SET `id` = ?,`name` = ?,`width` = ?,`height` = ?,`aspectRatioLabel` = ?,`fps` = ?,`sceneCount` = ?,`durationFrames` = ?,`previewBgKey` = ?,`previewBgColorHex` = ?,`documentJson` = ?,`isLastOpened` = ?,`createdAt` = ?,`updatedAt` = ? WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: ProjectEntity) {
                statement.bindText(1, entity.id)
                statement.bindText(2, entity.name)
                statement.bindLong(3, entity.width.toLong())
                statement.bindLong(4, entity.height.toLong())
                statement.bindText(5, entity.aspectRatioLabel)
                statement.bindLong(6, entity.fps.toLong())
                statement.bindLong(7, entity.sceneCount.toLong())
                statement.bindLong(8, entity.durationFrames.toLong())
                val _tmpPreviewBgKey = entity.previewBgKey
                if (_tmpPreviewBgKey == null) {
                    statement.bindNull(9)
                } else {
                    statement.bindText(9, _tmpPreviewBgKey)
                }
                statement.bindText(10, entity.previewBgColorHex)
                statement.bindText(11, entity.documentJson)
                statement.bindLong(12, if (entity.isLastOpened) 1L else 0L)
                statement.bindLong(13, entity.createdAt)
                statement.bindLong(14, entity.updatedAt)
                statement.bindText(15, entity.id)
            }
        }

    override suspend fun upsertProject(project: ProjectEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfProjectEntity.insert(_connection, project)
    }

    override suspend fun deleteProjectEntity(project: ProjectEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __deleteAdapterOfProjectEntity.handle(_connection, project)
    }

    override suspend fun updateProject(project: ProjectEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __updateAdapterOfProjectEntity.handle(_connection, project)
    }

    override fun observeProjectSummaries(): Flow<List<ProjectSummary>> {
        val _sql = """
            SELECT id, name, width, height, aspectRatioLabel, fps, sceneCount,
                   durationFrames, previewBgKey, previewBgColorHex, isLastOpened, updatedAt
            FROM projects
            ORDER BY updatedAt DESC
        """.trimIndent()
        return createFlow(__db, false, arrayOf("projects")) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val _result = mutableListOf<ProjectSummary>()
                while (_stmt.step()) {
                    val _tmpPreviewBgKey = if (_stmt.isNull(8)) null else _stmt.getText(8)
                    _result.add(
                        ProjectSummary(
                            id = _stmt.getText(0),
                            name = _stmt.getText(1),
                            width = _stmt.getLong(2).toInt(),
                            height = _stmt.getLong(3).toInt(),
                            aspectRatioLabel = _stmt.getText(4),
                            fps = _stmt.getLong(5).toInt(),
                            sceneCount = _stmt.getLong(6).toInt(),
                            durationFrames = _stmt.getLong(7).toInt(),
                            previewBgKey = _tmpPreviewBgKey,
                            previewBgColorHex = _stmt.getText(9),
                            isLastOpened = _stmt.getLong(10).toInt() != 0,
                            updatedAt = _stmt.getLong(11)
                        )
                    )
                }
                _result
            } finally {
                _stmt.close()
            }
        }
    }

    override fun observeAllProjects(): Flow<List<ProjectEntity>> {
        val _sql = "SELECT * FROM projects ORDER BY updatedAt DESC"
        return createFlow(__db, false, arrayOf("projects")) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val _result = mutableListOf<ProjectEntity>()
                while (_stmt.step()) {
                    _result.add(readProjectEntity(_stmt))
                }
                _result
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun getProjectById(id: String): ProjectEntity? {
        val _sql = "SELECT * FROM projects WHERE id = ? LIMIT 1"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, id)
                if (_stmt.step()) readProjectEntity(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun getLastOpenedProject(): ProjectEntity? {
        val _sql = "SELECT * FROM projects WHERE isLastOpened = 1 ORDER BY updatedAt DESC LIMIT 1"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (_stmt.step()) readProjectEntity(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    private fun readProjectEntity(_stmt: SQLiteStatement): ProjectEntity {
        val _colId = getColumnIndexOrThrow(_stmt, "id")
        val _colName = getColumnIndexOrThrow(_stmt, "name")
        val _colWidth = getColumnIndexOrThrow(_stmt, "width")
        val _colHeight = getColumnIndexOrThrow(_stmt, "height")
        val _colAspect = getColumnIndexOrThrow(_stmt, "aspectRatioLabel")
        val _colFps = getColumnIndexOrThrow(_stmt, "fps")
        val _colScenes = getColumnIndexOrThrow(_stmt, "sceneCount")
        val _colDuration = getColumnIndexOrThrow(_stmt, "durationFrames")
        val _colBgKey = getColumnIndexOrThrow(_stmt, "previewBgKey")
        val _colBgHex = getColumnIndexOrThrow(_stmt, "previewBgColorHex")
        val _colDocJson = getColumnIndexOrThrow(_stmt, "documentJson")
        val _colLastOpened = getColumnIndexOrThrow(_stmt, "isLastOpened")
        val _colCreated = getColumnIndexOrThrow(_stmt, "createdAt")
        val _colUpdated = getColumnIndexOrThrow(_stmt, "updatedAt")
        return ProjectEntity(
            id = _stmt.getText(_colId),
            name = _stmt.getText(_colName),
            width = _stmt.getLong(_colWidth).toInt(),
            height = _stmt.getLong(_colHeight).toInt(),
            aspectRatioLabel = _stmt.getText(_colAspect),
            fps = _stmt.getLong(_colFps).toInt(),
            sceneCount = _stmt.getLong(_colScenes).toInt(),
            durationFrames = _stmt.getLong(_colDuration).toInt(),
            previewBgKey = if (_stmt.isNull(_colBgKey)) null else _stmt.getText(_colBgKey),
            previewBgColorHex = _stmt.getText(_colBgHex),
            documentJson = _stmt.getText(_colDocJson),
            isLastOpened = _stmt.getLong(_colLastOpened).toInt() != 0,
            createdAt = _stmt.getLong(_colCreated),
            updatedAt = _stmt.getLong(_colUpdated)
        )
    }

    override suspend fun countProjects(): Int {
        val _sql = "SELECT COUNT(*) FROM projects"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (_stmt.step()) _stmt.getLong(0).toInt() else 0
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun clearLastOpenedFlags() {
        val _sql = "UPDATE projects SET isLastOpened = 0"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun renameProject(id: String, newName: String, updatedAt: Long) {
        val _sql = "UPDATE projects SET name = ?, updatedAt = ? WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, newName)
                _stmt.bindLong(2, updatedAt)
                _stmt.bindText(3, id)
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun deleteProject(id: String) {
        val _sql = "DELETE FROM projects WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, id)
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    companion object {
        fun getRequiredConverters(): List<KClass<*>> = emptyList()
    }
}
