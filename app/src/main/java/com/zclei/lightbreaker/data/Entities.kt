package com.zclei.lightbreaker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gallery_items")
data class GalleryItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val theme: String,
    val prompt: String,
    val seed: Int,
    val finishedAtMs: Long,
    val totalHits: Int,
    val openedTiles: Int,
    val totalTiles: Int,
    val maxCombo: Int,
    val calories: Float,
)

@Entity(tableName = "session_records")
data class SessionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val galleryItemId: String,
    val title: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSeconds: Int,
    val totalHits: Int,
    val leftHits: Int,
    val rightHits: Int,
    val openedTiles: Int,
    val totalTiles: Int,
    val maxCombo: Int,
    val calories: Float,
)

@Entity(tableName = "achievement_states")
data class AchievementStateEntity(
    @PrimaryKey val key: String,
    val title: String,
    val description: String,
    val unlocked: Boolean,
    val progress: Int,
    val target: Int,
    val unlockedAtMs: Long?,
)

data class ProgressStats(
    val xp: Int,
    val level: Int,
    val lastLeftDevice: String?,
    val lastRightDevice: String?,
)
