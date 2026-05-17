package com.zclei.lightbreaker.multiplayer

import com.zclei.lightbreaker.game.GameDifficulty
import com.zclei.lightbreaker.mural.GeneratedMural

data class MultiplayerPlayer(
    val playerId: String,
    val nickname: String,
    val color: String,
    val connected: Boolean,
    val totalHits: Int,
    val openedTiles: Int,
    val maxCombo: Int,
)

data class MultiplayerRoomState(
    val roomCode: String,
    val status: String,
    val hostPlayerId: String,
    val difficulty: GameDifficulty,
    val mural: GeneratedMural,
    val seq: Int,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    val totalHits: Int,
    val openedTiles: Int,
    val totalTiles: Int,
    val maxCombo: Int,
    val completed: Boolean,
    val players: List<MultiplayerPlayer>,
)

data class MultiplayerRoomSession(
    val state: MultiplayerRoomState,
    val playerId: String,
    val playerToken: String,
) {
    val isHost: Boolean get() = state.hostPlayerId == playerId
}

data class RemoteHitEvent(
    val seq: Int,
    val playerId: String,
    val nickname: String,
    val color: String,
    val hand: String,
    val timestampMs: Long,
    val intensity: Int,
    val sourceCount: Int,
)

sealed interface RoomSocketEvent {
    data class Snapshot(val state: MultiplayerRoomState) : RoomSocketEvent
    data class HitAccepted(val hit: RemoteHitEvent) : RoomSocketEvent
    data class SessionFinished(val state: MultiplayerRoomState?, val scoreboard: List<MultiplayerPlayer>) : RoomSocketEvent
    data class Notice(val message: String) : RoomSocketEvent
}
