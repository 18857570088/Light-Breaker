package com.zclei.lightbreaker.game

import com.zclei.lightbreaker.ble.GloveHand

enum class TileKind {
    Edge,
    Normal,
    Core,
    Bonus,
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
)
