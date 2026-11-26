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
class CharacterDao_Impl(
    private val __db: RoomDatabase,
) : CharacterDao {
    private val __insertAdapterOfCharacterEntity: EntityInsertAdapter<CharacterEntity> =
        object : EntityInsertAdapter<CharacterEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR REPLACE INTO `characters` (`id`,`name`,`categoryName`,`bodyTypeName`,`skinColorHex`,`primaryColorHex`,`secondaryColorHex`,`accentColorHex`,`hairColorHex`,`eyeColorHex`,`costumeStyleName`,`hairStyleName`,`buildTypeName`,`accessoryName`,`isPinned`,`isCustom`,`isLatest`,`customImagePath`,`skeletonRigJson`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: CharacterEntity) {
                bindCharacter(statement, entity)
            }
        }

    private val __insertAdapterOfCharacterEntity_1: EntityInsertAdapter<CharacterEntity> =
        object : EntityInsertAdapter<CharacterEntity>() {
            protected override fun createQuery(): String =
                "INSERT OR IGNORE INTO `characters` (`id`,`name`,`categoryName`,`bodyTypeName`,`skinColorHex`,`primaryColorHex`,`secondaryColorHex`,`accentColorHex`,`hairColorHex`,`eyeColorHex`,`costumeStyleName`,`hairStyleName`,`buildTypeName`,`accessoryName`,`isPinned`,`isCustom`,`isLatest`,`customImagePath`,`skeletonRigJson`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

            protected override fun bind(statement: SQLiteStatement, entity: CharacterEntity) {
                bindCharacter(statement, entity)
            }
        }

    private val __deleteAdapterOfCharacterEntity: EntityDeleteOrUpdateAdapter<CharacterEntity> =
        object : EntityDeleteOrUpdateAdapter<CharacterEntity>() {
            protected override fun createQuery(): String = "DELETE FROM `characters` WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: CharacterEntity) {
                statement.bindText(1, entity.id)
            }
        }

    private val __updateAdapterOfCharacterEntity: EntityDeleteOrUpdateAdapter<CharacterEntity> =
        object : EntityDeleteOrUpdateAdapter<CharacterEntity>() {
            protected override fun createQuery(): String =
                "UPDATE OR ABORT `characters` SET `id` = ?,`name` = ?,`categoryName` = ?,`bodyTypeName` = ?,`skinColorHex` = ?,`primaryColorHex` = ?,`secondaryColorHex` = ?,`accentColorHex` = ?,`hairColorHex` = ?,`eyeColorHex` = ?,`costumeStyleName` = ?,`hairStyleName` = ?,`buildTypeName` = ?,`accessoryName` = ?,`isPinned` = ?,`isCustom` = ?,`isLatest` = ?,`customImagePath` = ?,`skeletonRigJson` = ?,`createdAt` = ? WHERE `id` = ?"

            protected override fun bind(statement: SQLiteStatement, entity: CharacterEntity) {
                bindCharacter(statement, entity)
                statement.bindText(21, entity.id)
            }
        }

    private fun bindCharacter(statement: SQLiteStatement, entity: CharacterEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.categoryName)
        statement.bindText(4, entity.bodyTypeName)
        statement.bindText(5, entity.skinColorHex)
        statement.bindText(6, entity.primaryColorHex)
        statement.bindText(7, entity.secondaryColorHex)
        statement.bindText(8, entity.accentColorHex)
        statement.bindText(9, entity.hairColorHex)
        statement.bindText(10, entity.eyeColorHex)
        statement.bindText(11, entity.costumeStyleName)
        statement.bindText(12, entity.hairStyleName)
        statement.bindText(13, entity.buildTypeName)
        statement.bindText(14, entity.accessoryName)
        statement.bindLong(15, if (entity.isPinned) 1L else 0L)
        statement.bindLong(16, if (entity.isCustom) 1L else 0L)
        statement.bindLong(17, if (entity.isLatest) 1L else 0L)
        val _tmpImg = entity.customImagePath
        if (_tmpImg == null) statement.bindNull(18) else statement.bindText(18, _tmpImg)
        val _tmpSkel = entity.skeletonRigJson
        if (_tmpSkel == null) statement.bindNull(19) else statement.bindText(19, _tmpSkel)
        statement.bindLong(20, entity.createdAt)
    }

    override suspend fun upsertCharacter(character: CharacterEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfCharacterEntity.insert(_connection, character)
    }

    override suspend fun insertAllIgnore(characters: List<CharacterEntity>): Unit = performSuspending(__db, false, true) { _connection ->
        __insertAdapterOfCharacterEntity_1.insert(_connection, characters)
    }

    override suspend fun deleteCharacterEntity(character: CharacterEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __deleteAdapterOfCharacterEntity.handle(_connection, character)
    }

    override suspend fun updateCharacter(character: CharacterEntity): Unit = performSuspending(__db, false, true) { _connection ->
        __updateAdapterOfCharacterEntity.handle(_connection, character)
    }

    override fun observeAllCharacters(): Flow<List<CharacterEntity>> {
        val _sql = "SELECT * FROM characters ORDER BY isPinned DESC, isCustom DESC, createdAt DESC"
        return createFlow(__db, false, arrayOf("characters")) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                val _result = mutableListOf<CharacterEntity>()
                while (_stmt.step()) {
                    _result.add(readCharacterEntity(_stmt))
                }
                _result
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun getCharacterById(id: String): CharacterEntity? {
        val _sql = "SELECT * FROM characters WHERE id = ? LIMIT 1"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindText(1, id)
                if (_stmt.step()) readCharacterEntity(_stmt) else null
            } finally {
                _stmt.close()
            }
        }
    }

    private fun readCharacterEntity(_stmt: SQLiteStatement): CharacterEntity {
        val _cId = getColumnIndexOrThrow(_stmt, "id")
        val _cName = getColumnIndexOrThrow(_stmt, "name")
        val _cCat = getColumnIndexOrThrow(_stmt, "categoryName")
        val _cBody = getColumnIndexOrThrow(_stmt, "bodyTypeName")
        val _cSkin = getColumnIndexOrThrow(_stmt, "skinColorHex")
        val _cPrim = getColumnIndexOrThrow(_stmt, "primaryColorHex")
        val _cSec = getColumnIndexOrThrow(_stmt, "secondaryColorHex")
        val _cAcc = getColumnIndexOrThrow(_stmt, "accentColorHex")
        val _cHair = getColumnIndexOrThrow(_stmt, "hairColorHex")
        val _cEye = getColumnIndexOrThrow(_stmt, "eyeColorHex")
        val _cCostume = getColumnIndexOrThrow(_stmt, "costumeStyleName")
        val _cHairStyle = getColumnIndexOrThrow(_stmt, "hairStyleName")
        val _cBuild = getColumnIndexOrThrow(_stmt, "buildTypeName")
        val _cAccessory = getColumnIndexOrThrow(_stmt, "accessoryName")
        val _cPinned = getColumnIndexOrThrow(_stmt, "isPinned")
        val _cCustom = getColumnIndexOrThrow(_stmt, "isCustom")
        val _cLatest = getColumnIndexOrThrow(_stmt, "isLatest")
        val _cImg = getColumnIndexOrThrow(_stmt, "customImagePath")
        val _cSkel = getColumnIndexOrThrow(_stmt, "skeletonRigJson")
        val _cCreated = getColumnIndexOrThrow(_stmt, "createdAt")
        return CharacterEntity(
            id = _stmt.getText(_cId),
            name = _stmt.getText(_cName),
            categoryName = _stmt.getText(_cCat),
            bodyTypeName = _stmt.getText(_cBody),
            skinColorHex = _stmt.getText(_cSkin),
            primaryColorHex = _stmt.getText(_cPrim),
            secondaryColorHex = _stmt.getText(_cSec),
            accentColorHex = _stmt.getText(_cAcc),
            hairColorHex = _stmt.getText(_cHair),
            eyeColorHex = _stmt.getText(_cEye),
            costumeStyleName = _stmt.getText(_cCostume),
            hairStyleName = _stmt.getText(_cHairStyle),
            buildTypeName = _stmt.getText(_cBuild),
            accessoryName = _stmt.getText(_cAccessory),
            isPinned = _stmt.getLong(_cPinned).toInt() != 0,
            isCustom = _stmt.getLong(_cCustom).toInt() != 0,
            isLatest = _stmt.getLong(_cLatest).toInt() != 0,
            customImagePath = if (_stmt.isNull(_cImg)) null else _stmt.getText(_cImg),
            skeletonRigJson = if (_stmt.isNull(_cSkel)) null else _stmt.getText(_cSkel),
            createdAt = _stmt.getLong(_cCreated)
        )
    }

    override suspend fun countCharacters(): Int {
        val _sql = "SELECT COUNT(*) FROM characters"
        return performSuspending(__db, true, false) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                if (_stmt.step()) _stmt.getLong(0).toInt() else 0
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        val _sql = "UPDATE characters SET isPinned = ? WHERE id = ?"
        return performSuspending(__db, false, true) { _connection ->
            val _stmt = _connection.prepare(_sql)
            try {
                _stmt.bindLong(1, if (pinned) 1L else 0L)
                _stmt.bindText(2, id)
                _stmt.step()
            } finally {
                _stmt.close()
            }
        }
    }

    override suspend fun deleteCustomCharacter(id: String) {
        val _sql = "DELETE FROM characters WHERE id = ? AND isCustom = 1"
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
