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
class MediaAssetDao_Impl(
    private val __db: RoomDatabase,
) : MediaAssetDao {
    private val __insertAdapterOfMediaAssetEntity: EntityInsertAdapter<MediaAssetEntity> =
        object : EntityInsertAdapter<MediaAssetEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR REPLACE INTO `media_assets` (`id`,`name`,`category`,`typeName`,`localFilePath`,`mimeType`,`createdAt`) VALUES (?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: MediaAssetEntity) {
                statement.bindText(1, entity.id)
                statement.bindText(2, entity.name)
                statement.bindText(3, entity.category)
                statement.bindText(4, entity.typeName)
                statement.bindText(5, entity.localFilePath)
                statement.bindText(6, entity.mimeType)
                statement.bindLong(7, entity.createdAt)
            }
        }

    private val __deleteAdapterOfMediaAssetEntity: EntityDeleteOrUpdateAdapter<MediaAssetEntity> =
        object : EntityDeleteOrUpdateAdapter<MediaAssetEntity>() {
            protected override fun createQuery(): String = "DELETE FROM `media_assets` WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: MediaAssetEntity) {
                statement.bindText(1, entity.id)
            }
        }

    private val __updateAdapterOfMediaAssetEntity: EntityDeleteOrUpdateAdapter<MediaAssetEntity> =
        object : EntityDeleteOrUpdateAdapter<MediaAssetEntity>() {
            protected override fun createQuery(): String =
                "UPDATE OR ABORT `media_assets` SET `id` = ?,`name` = ?,`category` = ?,`typeName` = ?,`localFilePath` = ?,`mimeType` = ?,`createdAt` = ? WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: MediaAssetEntity) {
                statement.bindText(1, entity.id)
                statement.bindText(2, entity.name)
                statement.bindText(3, entity.category)
                statement.bindText(4, entity.typeName)
                statement.bindText(5, entity.localFilePath)
                statement.bindText(6, entity.mimeType)
                statement.bindLong(7, entity.createdAt)
                statement.bindText(8, entity.id)
            }
        }

    override suspend fun upsertMediaAsset(asset: MediaAssetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfMediaAssetEntity.insert(_connection, asset)
    }

    override suspend fun deleteMediaAssetEntity(asset: MediaAssetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __deleteAdapterOfMediaAssetEntity.handle(_connection, asset)
    }

    override suspend fun updateMediaAsset(asset: MediaAssetEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __updateAdapterOfMediaAssetEntity.handle(_connection, asset)
    }

    override fun observeAllMediaAssets(): Flow<List<MediaAssetEntity>> {
        val _sql = "SELECT * FROM media_assets ORDER BY createdAt DESC"
        return createFlow(__db, false, arrayOf("media_assets")) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val _result = mutableListOf<MediaAssetEntity>()
                while (_stmt.step()) {
                    _result.add(readMediaAsset(_stmt))
                }
                _result
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun getMediaAssetById(id: String): MediaAssetEntity? {
        val _sql = "SELECT * FROM media_assets WHERE id = ? LIMIT 1"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, id)
                if (_stmt.step()) readMediaAsset(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    private fun readMediaAsset(_stmt: SQLiteStatement): MediaAssetEntity {
        val _cId = getColumnIndexOrThrow(_stmt, "id")
        val _cName = getColumnIndexOrThrow(_stmt, "name")
        val _cCat = getColumnIndexOrThrow(_stmt, "category")
        val _cType = getColumnIndexOrThrow(_stmt, "typeName")
        val _cPath = getColumnIndexOrThrow(_stmt, "localFilePath")
        val _cMime = getColumnIndexOrThrow(_stmt, "mimeType")
        val _cCreated = getColumnIndexOrThrow(_stmt, "createdAt")
        return MediaAssetEntity(
            id = _stmt.getText(_cId),
            name = _stmt.getText(_cName),
            category = _stmt.getText(_cCat),
            typeName = _stmt.getText(_cType),
            localFilePath = _stmt.getText(_cPath),
            mimeType = _stmt.getText(_cMime),
            createdAt = _stmt.getLong(_cCreated)
        )
    }

    override suspend fun deleteMediaAsset(id: String) {
        val _sql = "DELETE FROM media_assets WHERE id = ?"
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
