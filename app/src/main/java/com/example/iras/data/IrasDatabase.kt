package com.example.iras.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, RoundEntity::class, ArrowEntity::class],
    version = 1,
    exportSchema = false
)
abstract class IrasDatabase : RoomDatabase() {

    abstract fun dao(): IrasDao

    companion object {
        @Volatile
        private var instance: IrasDatabase? = null

        fun get(context: Context): IrasDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IrasDatabase::class.java,
                    "iras.db"
                ).build().also { instance = it }
            }
    }
}