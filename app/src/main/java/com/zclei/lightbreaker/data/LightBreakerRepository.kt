package com.zclei.lightbreaker.data

import com.zclei.lightbreaker.game.GameSnapshot
import com.zclei.lightbreaker.mural.GeneratedMural
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.roundToInt

class LightBreakerRepository(
    private val database: LightBreakerDatabase,
    private val progressStore: ProgressStore,
) {
    val progressStats: Flow<ProgressStats> = progressStore.stats

    suspend fun gallery(): List<GalleryItemEntity> = database.galleryDao().allGalleryItems()

    suspend fun achievements(): List<AchievementStateEntity> = database.achievementDao().allAchievements()

    suspend fun saveSession(
        mural: GeneratedMural,
        snapshot: GameSnapshot,
        startedAtMs: Long,
        endedAtMs: Long,
    ): SaveSessionResult {
        val galleryId = "${mural.id}-${UUID.randomUUID()}"
        val durationSeconds = ((endedAtMs - startedAtMs) / 1000L).coerceAtLeast(1L).toInt()
        if (snapshot.completed) {
            database.galleryDao().insertGalleryItem(
                GalleryItemEntity(
                    id = galleryId,
                    title = mural.title,
                    theme = mural.theme,
                    prompt = mural.prompt,
                    seed = mural.seed,
                    finishedAtMs = endedAtMs,
                    totalHits = snapshot.totalHits,
                    openedTiles = snapshot.openedTiles,
                    totalTiles = snapshot.totalTiles,
                    maxCombo = snapshot.maxCombo,
                    calories = snapshot.calories,
                ),
            )
        }
        database.sessionDao().insertSession(
            SessionRecordEntity(
                galleryItemId = galleryId,
                title = mural.title,
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                durationSeconds = durationSeconds,
                totalHits = snapshot.totalHits,
                leftHits = snapshot.leftHits,
                rightHits = snapshot.rightHits,
                openedTiles = snapshot.openedTiles,
                totalTiles = snapshot.totalTiles,
                maxCombo = snapshot.maxCombo,
                calories = snapshot.calories,
            ),
        )
        val xpGain = calculateXp(snapshot)
        progressStore.addXp(xpGain)
        updateAchievements(snapshot, endedAtMs)
        return SaveSessionResult(xpGain = xpGain, gallerySaved = snapshot.completed)
    }

    suspend fun rememberDevice(
        leftName: String?,
        rightName: String?,
    ) {
        progressStore.rememberDevice(leftName, rightName)
    }

    private suspend fun updateAchievements(
        snapshot: GameSnapshot,
        nowMs: Long,
    ) {
        val definitions =
            listOf(
                AchievementDefinition("first_break", "初次破壁", "完成第一幅光影作品", 1, 1),
                AchievementDefinition("hundred_hits", "百拳破壁", "单局出拳达到 100 次", snapshot.totalHits, 100),
                AchievementDefinition("combo_master", "连击掌控者", "单局最高连击达到 x8", snapshot.maxCombo, 8),
                AchievementDefinition("full_reveal", "完整揭晓", "完成率达到 100%", snapshot.progressPercent.roundToInt(), 100),
            )
        definitions.forEach { definition ->
            database.achievementDao().upsertAchievement(
                AchievementStateEntity(
                    key = definition.key,
                    title = definition.title,
                    description = definition.description,
                    unlocked = definition.progress >= definition.target,
                    progress = definition.progress.coerceAtMost(definition.target),
                    target = definition.target,
                    unlockedAtMs = nowMs.takeIf { definition.progress >= definition.target },
                ),
            )
        }
    }

    private fun calculateXp(snapshot: GameSnapshot): Int =
        20 + snapshot.openedTiles / 3 + snapshot.maxCombo * 2 + if (snapshot.completed) 50 else 0

    private data class AchievementDefinition(
        val key: String,
        val title: String,
        val description: String,
        val progress: Int,
        val target: Int,
    )

    data class SaveSessionResult(
        val xpGain: Int,
        val gallerySaved: Boolean,
    )
}
