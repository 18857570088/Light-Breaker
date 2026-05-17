package com.zclei.lightbreaker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GalleryDao {
    @Query("SELECT * FROM gallery_items ORDER BY finishedAtMs DESC")
    suspend fun allGalleryItems(): List<GalleryItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGalleryItem(item: GalleryItemEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session_records ORDER BY endedAtMs DESC")
    suspend fun allSessions(): List<SessionRecordEntity>

    @Insert
    suspend fun insertSession(record: SessionRecordEntity)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievement_states ORDER BY unlocked DESC, key ASC")
    suspend fun allAchievements(): List<AchievementStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAchievement(achievement: AchievementStateEntity)
}
