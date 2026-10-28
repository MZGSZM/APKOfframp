package com.mzgszm.apkofframp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppEntity::class, DestinationEntity::class, VersionEntity::class, UploadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OfframpDb : RoomDatabase() {
    abstract fun dao(): OfframpDao

    companion object {
        fun open(context: Context): OfframpDb =
            Room.databaseBuilder(context, OfframpDb::class.java, "offramp.db").build()
    }
}
