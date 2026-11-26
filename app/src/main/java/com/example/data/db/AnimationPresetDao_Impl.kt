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
class AnimationPresetDao_Impl(
    private val __db: RoomDatabase,
) : AnimationPresetDao {
    private val __insertAdapterOfAnimationPresetEntity: EntityInsertAdapter<AnimationPresetEntity> =
        object : EntityInsertAdapter<AnimationPresetEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR REPLACE INTO `animation_presets` (`id`,`typeName`,`name`,`tag`,`defaultDurationFrames`,`description`,`supportedOrientationsCsv`,`isCustom`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: AnimationPresetEntity) {
                bindPreset(statement, entity)
            }
        }

    private val __insertAdapterOfAnimationPresetEntity_1: EntityInsertAdapter<AnimationPresetEntity> =
        object : EntityInsertAdapter<AnimationPresetEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR IGNORE INTO `animation_presets` (`id`,`typeName`,`name`,`tag`,`defaultDurationFrames`,`description`,`supportedOrientationsCsv`,`isCustom`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: AnimationPresetEntity) {
                bindPreset(statement, entity)
            }
        }

    private val __deleteAdapterOfAnimationPresetEntity: EntityDeleteOrUpdateAdapter<AnimationPresetEntity> =
        object : EntityDeleteOrUpdateAdapter<AnimationPresetEntity>() {
            protected override fun createQuery(): String = "DELETE FROM `animation_presets` WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: AnimationPresetEntity) {
                statement.bindText(1, entity.id)
            }
        }

    private val __updateAdapterOfAnimationPresetEntity: EntityDeleteOrUpdateAdapter<AnimationPresetEntity> =
        object : EntityDeleteOrUpdateAdapter<AnimationPresetEntity>() {
            protected override fun createQuery(): String =
                "UPDATE OR ABORT `animation_presets` SET `id` = ?,`typeName` = ?,`name` = ?,`tag` = ?,`defaultDurationFrames` = ?,`description` = ?,`supportedOrientationsCsv` = ?,`isCustom` = ?,`createdAt` = ? WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: AnimationPresetEntity) {
                bindPreset(statement, entity)
                statement.bindText(10, entity.id)
            }
        }

    private fun bindPreset(statement: SQLiteStatement, entity: AnimationPresetEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.typeName)
        statement.bindText(3, entity.name)
        statement.bindText(4, entity.tag)
        statement.bindLong(5, entity.defaultDurationFrames.toLong())
        statement.bindText(6, entity.description)
        statement.bindText(7, entity.supportedOrientationsCsv)
        statement.bindLong(8, if (entity.isCustom) 1L else 0L)
        statement.bindLong(9, entity.createdAt)
    }

    override suspend fun upsertPreset(preset: AnimationPresetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfAnimationPresetEntity.insert(_connection, preset)
    }

    override suspend fun insertAllIgnore(presets: List<AnimationPresetEntity>): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfAnimationPresetEntity_1.insert(_connection, presets)
    }

    override suspend fun deletePresetEntity(preset: AnimationPresetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __deleteAdapterOfAnimationPresetEntity.handle(_connection, preset)
    }

    override suspend fun updatePreset(preset: AnimationPresetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __updateAdapterOfAnimationPresetEntity.handle(_connection, preset)
    }

    override fun observeAllPresets(): Flow<List<AnimationPresetEntity>> {
        val _sql = "SELECT * FROM animation_presets ORDER BY isCustom DESC, createdAt ASC"
        return createFlow(__db, false, arrayOf("animation_presets")) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val _result = mutableListOf<AnimationPresetEntity>()
                while (_stmt.step()) {
                    _result.add(readPreset(_stmt))
                }
                _result
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun getPresetById(id: String): AnimationPresetEntity? {
        val _sql = "SELECT * FROM animation_presets WHERE id = ? LIMIT 1"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, id)
                if (_stmt.step()) readPreset(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    private fun readPreset(_stmt: SQLiteStatement): AnimationPresetEntity {
        val _cId = getColumnIndexOrThrow(_stmt, "id")
        val _cType = getColumnIndexOrThrow(_stmt, "typeName")
        val _cName = getColumnIndexOrThrow(_stmt, "name")
        val _cTag = getColumnIndexOrThrow(_stmt, "tag")
        val _cDur = getColumnIndexOrThrow(_stmt, "defaultDurationFrames")
        val _cDesc = getColumnIndexOrThrow(_stmt, "description")
        val _cCsv = getColumnIndexOrThrow(_stmt, "supportedOrientationsCsv")
        val _cCustom = getColumnIndexOrThrow(_stmt, "isCustom")
        val _cCreated = getColumnIndexOrThrow(_stmt, "createdAt")
        return AnimationPresetEntity(
            id = _stmt.getText(_cId),
            typeName = _stmt.getText(_cType),
            name = _stmt.getText(_cName),
            tag = _stmt.getText(_cTag),
            defaultDurationFrames = _stmt.getLong(_cDur).toInt(),
            description = _stmt.getText(_cDesc),
            supportedOrientationsCsv = _stmt.getText(_cCsv),
            isCustom = _stmt.getLong(_cCustom).toInt() != 0,
            createdAt = _stmt.getLong(_cCreated)
        )
    }

    override suspend fun countPresets(): Int {
        val _sql = "SELECT COUNT(*) FROM animation_presets"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (_stmt.step()) _stmt.getLong(0).toInt() else 0
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun deletePresetById(id: String) {
        val _sql = "DELETE FROM animation_presets WHERE id = ?"
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
