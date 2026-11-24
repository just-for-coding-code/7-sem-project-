package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PeerEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class BlueWaveDatabase : RoomDatabase() {
    abstract fun blueWaveDao(): BlueWaveDao

    companion object {
        @Volatile
        private var INSTANCE: BlueWaveDatabase? = null

        fun getInstance(context: Context): BlueWaveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlueWaveDatabase::class.java,
                    "bluewave_p2p.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
