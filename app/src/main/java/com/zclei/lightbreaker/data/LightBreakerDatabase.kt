package com.zclei.lightbreaker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GalleryItemEntity::class,
        SessionRecordEntity::class,
        AchievementStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LightBreakerDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao

    abstract fun sessionDao(): SessionDao

    abstract fun achievementDao(): AchievementDao

    companion object {
        @Volatile private var instance: LightBreakerDatabase? = null

        fun get(context: Context): LightBreakerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LightBreakerDatabase::class.java,
                    "light_breaker.db",
                ).build().also { instance = it }
            }
    }
}
