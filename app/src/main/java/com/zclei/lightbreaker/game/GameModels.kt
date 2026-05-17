package com.zclei.lightbreaker.game

import com.zclei.lightbreaker.ble.GloveHand

enum class TileKind {
    Edge,
    Normal,
    Core,
    Bonus,
    Locked,
}

enum class GameDifficulty(
    val id: String,
    val title: String,
    val columns: Int,
    val rows: Int,
    val defaultSeconds: Int,
    val bonusChance: Float,
    val lockedChance: Float,
) {
    Easy("easy", "简单", 15, 10, 60, 0.03f, 0f),
    Standard("standard", "标准", 20, 15, 60, 0.04f, 0f),
    Challenge("challenge", "挑战", 25, 20, 90, 0.05f, 0.06f);

    companion object {
        fun fromId(id: String?): GameDifficulty =
            entries.firstOrNull { it.id == id?.lowercase() } ?: Standard
    }
}

enum class TreasureReward(
    val label: String,
) {
    Splash("范围震落"),
    DoubleXp("双倍经验"),
    FastBreak("快速破壁"),
}

data class TileSnapshot(
    val index: Int,
    val row: Int,
    val col: Int,
    val maxHp: Int,
    val hp: Int,
    val kind: TileKind,
    val opened: Boolean,
    val owner: GloveHand?,
    val ownerId: String? = null,
)

data class GameSnapshot(
    val totalHits: Int,
    val leftHits: Int,
    val rightHits: Int,
    val openedTiles: Int,
    val totalTiles: Int,
    val combo: Int,
    val maxCombo: Int,
    val calories: Float,
    val completed: Boolean,
    val progressPercent: Float,
    val tiles: List<TileSnapshot>,
    val lastOpenedIndexes: Set<Int>,
    val columns: Int = 16,
    val rows: Int = 10,
    val difficulty: GameDifficulty = GameDifficulty.Standard,
    val lastReward: TreasureReward? = null,
    val xpMultiplier: Int = 1,
    val playerHits: Map<String, Int> = emptyMap(),
)
