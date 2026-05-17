package com.zclei.lightbreaker.multiplayer

import com.zclei.lightbreaker.game.GameDifficulty
import com.zclei.lightbreaker.mural.GeneratedMural
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal fun JSONObject.toRoomState(): MultiplayerRoomState {
    val muralJson = getJSONObject("mural")
    val playersJson = optJSONArray("players") ?: JSONArray()
    val players =
        List(playersJson.length()) { index ->
            playersJson.getJSONObject(index).toPlayer()
        }
    return MultiplayerRoomState(
        roomCode = getString("roomCode"),
        status = optString("status", "waiting"),
        hostPlayerId = optString("hostPlayerId"),
        difficulty = GameDifficulty.fromId(optString("difficulty", "standard")),
        mural = muralJson.toMural(),
        seq = optInt("seq", 0),
        startedAtMs = optLongOrNull("startedAtMs"),
        finishedAtMs = optLongOrNull("finishedAtMs"),
        totalHits = optInt("totalHits", 0),
        openedTiles = optInt("openedTiles", 0),
        totalTiles = optInt("totalTiles", 0),
        maxCombo = optInt("maxCombo", 0),
        completed = optBoolean("completed", false),
        players = players,
    )
}

internal fun JSONObject.toPlayer(): MultiplayerPlayer =
    MultiplayerPlayer(
        playerId = optString("playerId"),
        nickname = optString("nickname", "Player"),
        color = optString("color", "#22C55E"),
        connected = optBoolean("connected", false),
        totalHits = optInt("totalHits", 0),
        openedTiles = optInt("openedTiles", 0),
        maxCombo = optInt("maxCombo", 0),
    )

internal fun JSONObject.toMural(): GeneratedMural =
    GeneratedMural(
        id = optString("id", "cloud-${abs(toString().hashCode())}"),
        title = optString("title", "LightBreaker"),
        theme = optString("theme", "自然风光"),
        prompt = optString("prompt", ""),
        seed = optInt("seed", abs(toString().hashCode())),
        categoryId = optString("categoryId", "nature"),
        imageUrl = optString("imageUrl").takeIf { it.isNotBlank() },
        sourceUrl = optString("sourceUrl").takeIf { it.isNotBlank() },
        license = optString("license").takeIf { it.isNotBlank() },
    )

internal fun GeneratedMural.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("theme", theme)
        .put("prompt", prompt)
        .put("seed", seed)
        .put("categoryId", categoryId)
        .put("imageUrl", imageUrl)
        .put("sourceUrl", sourceUrl)
        .put("license", license)

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
