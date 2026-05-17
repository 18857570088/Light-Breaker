package com.zclei.lightbreaker.multiplayer

import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.hit.HitEvent
import com.zclei.lightbreaker.network.ServerConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class RoomSocketClient(
    private val client: OkHttpClient = MultiplayerApi.sharedClient,
    private val wsBaseUrl: String = ServerConfig.MULTIPLAYER_WS_BASE_URL,
) {
    val events = MutableSharedFlow<RoomSocketEvent>(extraBufferCapacity = 128)
    private var socket: WebSocket? = null

    fun connect(session: MultiplayerRoomSession) {
        close()
        val url =
            "${wsBaseUrl.trimEnd('/')}/rooms/${session.state.roomCode}" +
                "?playerId=${session.playerId}&token=${session.playerToken}"
        val request = Request.Builder().url(url).build()
        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(JSONObject().put("type", "join_room").toString())
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        handleMessage(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        events.tryEmit(RoomSocketEvent.Notice("多人连接异常：${t.message ?: "unknown"}"))
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        events.tryEmit(RoomSocketEvent.Notice("多人连接已断开"))
                    }
                },
            )
    }

    fun sendHit(
        hit: HitEvent,
        combo: Int,
    ) {
        socket?.send(
            JSONObject()
                .put("type", "hit_submit")
                .put("hand", hit.hand.toWireName())
                .put("timestampMs", hit.timestampMs)
                .put("intensity", hit.intensity)
                .put("sourceCount", hit.sourceCount)
                .put("combo", combo)
                .toString(),
        )
    }

    fun sendSnapshot(
        totalHits: Int,
        openedTiles: Int,
        totalTiles: Int,
        maxCombo: Int,
        completed: Boolean,
    ) {
        socket?.send(
            JSONObject()
                .put("type", "snapshot_submit")
                .put("totalHits", totalHits)
                .put("openedTiles", openedTiles)
                .put("totalTiles", totalTiles)
                .put("maxCombo", maxCombo)
                .put("completed", completed)
                .toString(),
        )
    }

    fun close() {
        socket?.close(1000, "leaving")
        socket = null
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "room_snapshot" -> events.tryEmit(RoomSocketEvent.Snapshot(json.toRoomState()))
            "player_joined", "player_left" -> {
                json.optJSONObject("room")?.let { events.tryEmit(RoomSocketEvent.Snapshot(it.toRoomState())) }
            }
            "hit_accepted" ->
                events.tryEmit(
                    RoomSocketEvent.HitAccepted(
                        json.toRemoteHit(),
                    ),
                )
            "event_history" -> {
                val history = json.optJSONArray("events") ?: return
                for (index in 0 until history.length()) {
                    events.tryEmit(RoomSocketEvent.HitAccepted(history.getJSONObject(index).toRemoteHit()))
                }
            }
            "session_finished" ->
                events.tryEmit(
                    RoomSocketEvent.SessionFinished(
                        state = json.optJSONObject("room")?.toRoomState(),
                        scoreboard = emptyList(),
                    ),
                )
            "error" -> events.tryEmit(RoomSocketEvent.Notice(json.optString("message", "多人服务错误")))
        }
    }

    private fun GloveHand.toWireName(): String =
        when (this) {
            GloveHand.Left -> "left"
            GloveHand.Right -> "right"
            GloveHand.Unknown -> "unknown"
        }

    private fun JSONObject.toRemoteHit(): RemoteHitEvent =
        RemoteHitEvent(
            seq = optInt("seq"),
            playerId = optString("playerId"),
            nickname = optString("nickname"),
            color = optString("color"),
            hand = optString("hand", "unknown"),
            timestampMs = optLong("timestampMs"),
            intensity = optInt("intensity", 120),
            sourceCount = optInt("sourceCount", 0),
        )
}
