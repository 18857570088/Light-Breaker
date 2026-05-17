package com.zclei.lightbreaker.multiplayer

import com.zclei.lightbreaker.game.GameDifficulty
import com.zclei.lightbreaker.game.GameSnapshot
import com.zclei.lightbreaker.mural.GeneratedMural
import com.zclei.lightbreaker.network.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MultiplayerApi(
    private val baseUrl: String = ServerConfig.MULTIPLAYER_API_BASE_URL,
    private val client: OkHttpClient = sharedClient,
) {
    suspend fun createRoom(
        installId: String,
        nickname: String,
        mural: GeneratedMural,
        difficulty: GameDifficulty,
    ): MultiplayerRoomSession =
        withContext(Dispatchers.IO) {
            post(
                "rooms",
                JSONObject()
                    .put("installId", installId)
                    .put("nickname", nickname)
                    .put("difficulty", difficulty.id)
                    .put("mural", mural.toJson()),
            ).toSession()
        }

    suspend fun joinRoom(
        roomCode: String,
        installId: String,
        nickname: String,
    ): MultiplayerRoomSession =
        withContext(Dispatchers.IO) {
            post(
                "rooms/${roomCode.trim().uppercase()}/join",
                JSONObject().put("installId", installId).put("nickname", nickname),
            ).toSession()
        }

    suspend fun startRoom(session: MultiplayerRoomSession): MultiplayerRoomSession =
        withContext(Dispatchers.IO) {
            post(
                "rooms/${session.state.roomCode}/start",
                JSONObject().put("playerId", session.playerId).put("playerToken", session.playerToken),
            ).toSession()
        }

    suspend fun finishRoom(
        session: MultiplayerRoomSession,
        snapshot: GameSnapshot,
        durationSeconds: Int,
        scoreboard: List<MultiplayerPlayer>,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val scoreArray = JSONArray()
            scoreboard.forEach { player ->
                scoreArray.put(
                    JSONObject()
                        .put("playerId", player.playerId)
                        .put("nickname", player.nickname)
                        .put("totalHits", snapshot.playerHits[player.playerId] ?: player.totalHits)
                        .put("openedTiles", player.openedTiles)
                        .put("maxCombo", player.maxCombo),
                )
            }
            post(
                "rooms/${session.state.roomCode}/finish",
                JSONObject()
                    .put("playerId", session.playerId)
                    .put("playerToken", session.playerToken)
                    .put("totalHits", snapshot.totalHits)
                    .put("openedTiles", snapshot.openedTiles)
                    .put("totalTiles", snapshot.totalTiles)
                    .put("durationSeconds", durationSeconds)
                    .put("maxCombo", snapshot.maxCombo)
                    .put("completed", snapshot.completed)
                    .put("scoreboard", scoreArray),
            )
        }

    private fun JSONObject.toSession(): MultiplayerRoomSession =
        MultiplayerRoomSession(
            state = toRoomState(),
            playerId = getString("playerId"),
            playerToken = getString("playerToken"),
        )

    private fun post(
        path: String,
        body: JSONObject,
    ): JSONObject {
        val request =
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/$path")
                .post(body.toString().toRequestBody(JSON))
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $text")
            return JSONObject(text)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val sharedClient: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
    }
}
