package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        CharacterEntity::class,
        AnimationPresetEntity::class,
        MediaAssetEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FrameCraftDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun characterDao(): CharacterDao
    abstract fun animationPresetDao(): AnimationPresetDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        @Volatile
        private var INSTANCE: FrameCraftDatabase? = null

        fun getInstance(context: Context): FrameCraftDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrameCraftDatabase::class.java,
                    "framecraft_offline_studio.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
