package com.zclei.lightbreaker.game

import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.hit.HitEvent
import com.zclei.lightbreaker.mural.GeneratedMural
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class LightBreakerGameEngine {
    private var columns: Int = GameDifficulty.Standard.columns
    private var rows: Int = GameDifficulty.Standard.rows
    private var difficulty: GameDifficulty = GameDifficulty.Standard
    private val tiles = mutableListOf<TileState>()
    private var random = Random(1)
    private var totalHits = 0
    private var leftHits = 0
    private var rightHits = 0
    private var openedTiles = 0
    private var combo = 0
    private var maxCombo = 0
    private var lastHitAtMs = 0L
    private var lastOpened = emptySet<Int>()
    private var lastReward: TreasureReward? = null
    private var xpMultiplier = 1
    private val playerHits = mutableMapOf<String, Int>()

    fun start(
        mural: GeneratedMural,
        difficulty: GameDifficulty = GameDifficulty.Standard,
    ): GameSnapshot {
        this.difficulty = difficulty
        columns = difficulty.columns
        rows = difficulty.rows
        random = Random(mural.seed)
        totalHits = 0
        leftHits = 0
        rightHits = 0
        openedTiles = 0
        combo = 0
        maxCombo = 0
        lastHitAtMs = 0L
        lastOpened = emptySet()
        lastReward = null
        xpMultiplier = 1
        playerHits.clear()
        tiles.clear()
        repeat(rows) { row ->
            repeat(columns) { col ->
                val index = row * columns + col
                val kind = kindFor(row, col)
                val hp =
                    when (kind) {
                        TileKind.Edge -> 1
                        TileKind.Normal -> if (random.nextFloat() < 0.18f) 2 else 1
                        TileKind.Core -> 3
                        TileKind.Bonus -> 2
                        TileKind.Locked -> 3
                    }
                tiles += TileState(index, row, col, hp, hp, kind)
            }
        }
        return snapshot()
    }

    fun registerHit(
        hit: HitEvent,
        playerId: String? = null,
    ): GameSnapshot {
        if (tiles.isEmpty() || openedTiles >= tiles.size) {
            return snapshot()
        }
        totalHits += 1
        playerId?.let { playerHits[it] = (playerHits[it] ?: 0) + 1 }
        when (hit.hand) {
            GloveHand.Left -> leftHits += 1
            GloveHand.Right -> rightHits += 1
            GloveHand.Unknown -> Unit
        }
        combo = if (lastHitAtMs > 0L && hit.timestampMs - lastHitAtMs <= COMBO_WINDOW_MS) combo + 1 else 1
        maxCombo = maxOf(maxCombo, combo)
        lastHitAtMs = hit.timestampMs

        lastReward = null
        val openedNow = linkedSetOf<Int>()
        hitTile(selectTarget(hit), hit.hand, playerId, openedNow, hit)
        if (hit.intensity >= STRONG_HIT_POWER || combo >= 8) {
            openSplashAround(openedNow.lastOrNull(), hit.hand, playerId, openedNow, radius = if (combo >= 10) 2 else 1)
        } else if (combo == 5 || combo == 6) {
            openRandomLightTile(hit.hand, playerId, openedNow)
        }
        lastOpened = openedNow
        return snapshot()
    }

    fun snapshot(): GameSnapshot =
        GameSnapshot(
            totalHits = totalHits,
            leftHits = leftHits,
            rightHits = rightHits,
            openedTiles = openedTiles,
            totalTiles = tiles.size,
            combo = combo.coerceAtLeast(1),
            maxCombo = maxCombo.coerceAtLeast(combo),
            calories = totalHits * 0.08f + openedTiles * 0.015f,
            completed = openedTiles >= tiles.size && tiles.isNotEmpty(),
            progressPercent = if (tiles.isEmpty()) 0f else openedTiles * 100f / tiles.size,
            tiles = tiles.map { it.toSnapshot() },
            lastOpenedIndexes = lastOpened,
            columns = columns,
            rows = rows,
            difficulty = difficulty,
            lastReward = lastReward,
            xpMultiplier = xpMultiplier,
            playerHits = playerHits.toMap(),
        )

    private fun selectTarget(hit: HitEvent): TileState {
        val closed = tiles.filterNot { it.opened }
        if (hit.intensity >= STRONG_HIT_POWER) {
            return closed.maxWith(compareBy<TileState> { it.hp }.thenBy { centerScore(it.row, it.col) })
        }
        if (combo >= 4) {
            return closed.minBy { abs(it.row - rows / 2) + abs(it.col - columns / 2) + random.nextInt(0, 4) }
        }
        return closed[random.nextInt(closed.size)]
    }

    private fun hitTile(
        tile: TileState,
        hand: GloveHand,
        playerId: String?,
        openedNow: MutableSet<Int>,
        hit: HitEvent,
    ) {
        if (tile.opened) return
        if (tile.kind == TileKind.Locked && hit.intensity < STRONG_HIT_POWER && combo < 3) return
        val damage =
            when (tile.kind) {
                TileKind.Bonus -> 2
                TileKind.Locked -> if (hit.intensity >= STRONG_HIT_POWER) 2 else 1
                else -> 1
            }
        tile.hp = (tile.hp - damage).coerceAtLeast(0)
        if (tile.hp == 0) {
            openTile(tile, hand, playerId, openedNow)
            if (tile.kind == TileKind.Bonus) {
                triggerTreasure(hand, playerId, openedNow)
            }
        }
    }

    private fun triggerTreasure(
        hand: GloveHand,
        playerId: String?,
        openedNow: MutableSet<Int>,
    ) {
        lastReward = TreasureReward.entries[random.nextInt(TreasureReward.entries.size)]
        when (lastReward) {
            TreasureReward.Splash -> openSplashAround(openedNow.lastOrNull(), hand, playerId, openedNow, radius = 2)
            TreasureReward.DoubleXp -> xpMultiplier = 2
            TreasureReward.FastBreak -> repeat(4) { openRandomLightTile(hand, playerId, openedNow) }
            null -> Unit
        }
        if (lastReward != TreasureReward.FastBreak) {
            openRandomLightTile(hand, playerId, openedNow)
            openRandomLightTile(hand, playerId, openedNow)
        }
    }

    private fun openTile(
        tile: TileState,
        hand: GloveHand,
        playerId: String?,
        openedNow: MutableSet<Int>,
    ) {
        if (tile.opened) return
        tile.opened = true
        tile.owner = hand
        tile.ownerId = playerId
        openedTiles += 1
        openedNow += tile.index
    }

    private fun openRandomLightTile(
        hand: GloveHand,
        playerId: String?,
        openedNow: MutableSet<Int>,
    ) {
        val target = tiles.filter { !it.opened && it.hp <= 1 }.randomOrNull(random) ?: return
        openTile(target, hand, playerId, openedNow)
    }

    private fun openSplashAround(
        sourceIndex: Int?,
        hand: GloveHand,
        playerId: String?,
        openedNow: MutableSet<Int>,
        radius: Int,
    ) {
        val source = sourceIndex?.let { tiles.getOrNull(it) } ?: return
        tiles
            .filter { !it.opened && abs(it.row - source.row) <= radius && abs(it.col - source.col) <= radius }
            .shuffled(random)
            .take(if (radius >= 2) 3 else 1)
            .forEach { openTile(it, hand, playerId, openedNow) }
    }

    private fun kindFor(
        row: Int,
        col: Int,
    ): TileKind {
        val edge = row == 0 || col == 0 || row == rows - 1 || col == columns - 1
        if (edge) return TileKind.Edge
        val centerDistance = centerScore(row, col)
        if (difficulty == GameDifficulty.Challenge && (centerDistance < 1.5f || centerDistance < 2.7f && random.nextFloat() < difficulty.lockedChance)) {
            return TileKind.Locked
        }
        if (centerDistance < 2.7f) return TileKind.Core
        if (random.nextFloat() < difficulty.bonusChance) return TileKind.Bonus
        return TileKind.Normal
    }

    private fun centerScore(
        row: Int,
        col: Int,
    ): Float = hypot((row - (rows - 1) / 2f).toDouble(), (col - (columns - 1) / 2f).toDouble()).toFloat()

    private data class TileState(
        val index: Int,
        val row: Int,
        val col: Int,
        val maxHp: Int,
        var hp: Int,
        val kind: TileKind,
        var opened: Boolean = false,
        var owner: GloveHand? = null,
        var ownerId: String? = null,
    ) {
        fun toSnapshot(): TileSnapshot =
            TileSnapshot(
                index = index,
                row = row,
                col = col,
                maxHp = maxHp,
                hp = hp,
                kind = kind,
                opened = opened,
                owner = owner,
                ownerId = ownerId,
            )
    }

    private companion object {
        const val COMBO_WINDOW_MS = 1_200L
        const val STRONG_HIT_POWER = 190
    }
}
