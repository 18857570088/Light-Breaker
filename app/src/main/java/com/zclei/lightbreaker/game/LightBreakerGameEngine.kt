package com.zclei.lightbreaker.game

import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.hit.HitEvent
import com.zclei.lightbreaker.mural.GeneratedMural
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class LightBreakerGameEngine(
    private val columns: Int = 16,
    private val rows: Int = 10,
) {
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

    fun start(mural: GeneratedMural): GameSnapshot {
        random = Random(mural.seed)
        totalHits = 0
        leftHits = 0
        rightHits = 0
        openedTiles = 0
        combo = 0
        maxCombo = 0
        lastHitAtMs = 0L
        lastOpened = emptySet()
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
                    }
                tiles += TileState(index, row, col, hp, hp, kind)
            }
        }
        return snapshot()
    }

    fun registerHit(hit: HitEvent): GameSnapshot {
        if (tiles.isEmpty() || openedTiles >= tiles.size) {
            return snapshot()
        }
        totalHits += 1
        when (hit.hand) {
            GloveHand.Left -> leftHits += 1
            GloveHand.Right -> rightHits += 1
            GloveHand.Unknown -> Unit
        }
        combo = if (lastHitAtMs > 0L && hit.timestampMs - lastHitAtMs <= COMBO_WINDOW_MS) combo + 1 else 1
        maxCombo = maxOf(maxCombo, combo)
        lastHitAtMs = hit.timestampMs

        val openedNow = linkedSetOf<Int>()
        hitTile(selectTarget(hit), hit.hand, openedNow)
        if (hit.intensity >= STRONG_HIT_POWER || combo >= 8) {
            openSplashAround(openedNow.lastOrNull(), hit.hand, openedNow, radius = if (combo >= 10) 2 else 1)
        } else if (combo == 5 || combo == 6) {
            openRandomLightTile(hit.hand, openedNow)
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
        openedNow: MutableSet<Int>,
    ) {
        if (tile.opened) return
        val damage = if (tile.kind == TileKind.Bonus) 2 else 1
        tile.hp = (tile.hp - damage).coerceAtLeast(0)
        if (tile.hp == 0) {
            openTile(tile, hand, openedNow)
            if (tile.kind == TileKind.Bonus) {
                openRandomLightTile(hand, openedNow)
                openRandomLightTile(hand, openedNow)
            }
        }
    }

    private fun openTile(
        tile: TileState,
        hand: GloveHand,
        openedNow: MutableSet<Int>,
    ) {
        if (tile.opened) return
        tile.opened = true
        tile.owner = hand
        openedTiles += 1
        openedNow += tile.index
    }

    private fun openRandomLightTile(
        hand: GloveHand,
        openedNow: MutableSet<Int>,
    ) {
        val target = tiles.filter { !it.opened && it.hp <= 1 }.randomOrNull(random) ?: return
        openTile(target, hand, openedNow)
    }

    private fun openSplashAround(
        sourceIndex: Int?,
        hand: GloveHand,
        openedNow: MutableSet<Int>,
        radius: Int,
    ) {
        val source = sourceIndex?.let { tiles.getOrNull(it) } ?: return
        tiles
            .filter { !it.opened && abs(it.row - source.row) <= radius && abs(it.col - source.col) <= radius }
            .shuffled(random)
            .take(if (radius >= 2) 3 else 1)
            .forEach { openTile(it, hand, openedNow) }
    }

    private fun kindFor(
        row: Int,
        col: Int,
    ): TileKind {
        val edge = row == 0 || col == 0 || row == rows - 1 || col == columns - 1
        if (edge) return TileKind.Edge
        val centerDistance = centerScore(row, col)
        if (centerDistance < 2.7f) return TileKind.Core
        if (random.nextFloat() < 0.04f) return TileKind.Bonus
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
            )
    }

    private companion object {
        const val COMBO_WINDOW_MS = 1_200L
        const val STRONG_HIT_POWER = 190
    }
}
