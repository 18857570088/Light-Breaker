import json
import os
import random
import secrets
import string
import time
from dataclasses import dataclass, field
from typing import Any

import pymysql
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


DB_CONFIG = {
    "host": os.getenv("LIGHTBREAKER_DB_HOST", "127.0.0.1"),
    "user": os.getenv("LIGHTBREAKER_DB_USER", "wm"),
    "password": os.getenv("LIGHTBREAKER_DB_PASSWORD", ""),
    "database": os.getenv("LIGHTBREAKER_DB_NAME", "LightBreaker"),
    "port": int(os.getenv("LIGHTBREAKER_DB_PORT", "3306")),
    "charset": "utf8mb4",
    "autocommit": True,
    "cursorclass": pymysql.cursors.DictCursor,
}

ROOM_ALPHABET = string.ascii_uppercase + string.digits
MAX_PLAYERS = 4
ROOM_TTL_MS = 2 * 60 * 60 * 1000
PLAYER_COLORS = ["#3B82F6", "#F97316", "#22C55E", "#A855F7"]

app = FastAPI(title="LightBreaker Multiplayer API", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class MuralPayload(BaseModel):
    id: str = "cloud"
    title: str = "LightBreaker"
    theme: str = "自然风光"
    prompt: str = ""
    seed: int = 1
    categoryId: str = "nature"
    imageUrl: str | None = None
    sourceUrl: str | None = None
    license: str | None = None


class CreateRoomRequest(BaseModel):
    installId: str = Field(min_length=1)
    nickname: str = "Player"
    difficulty: str = "standard"
    mural: MuralPayload


class JoinRoomRequest(BaseModel):
    installId: str = Field(min_length=1)
    nickname: str = "Player"


class StartRoomRequest(BaseModel):
    playerId: str
    playerToken: str


class FinishRoomRequest(BaseModel):
    playerId: str
    playerToken: str
    totalHits: int = 0
    openedTiles: int = 0
    totalTiles: int = 0
    maxCombo: int = 0
    completed: bool = False
    durationSeconds: int = 0
    scoreboard: list[dict[str, Any]] = Field(default_factory=list)


@dataclass
class PlayerState:
    player_id: str
    install_id: str
    nickname: str
    color: str
    token: str
    joined_at_ms: int
    last_seen_at_ms: int
    total_hits: int = 0
    opened_tiles: int = 0
    max_combo: int = 0
    connected: bool = False

    def public(self) -> dict[str, Any]:
        return {
            "playerId": self.player_id,
            "nickname": self.nickname,
            "color": self.color,
            "joinedAtMs": self.joined_at_ms,
            "lastSeenAtMs": self.last_seen_at_ms,
            "totalHits": self.total_hits,
            "openedTiles": self.opened_tiles,
            "maxCombo": self.max_combo,
            "connected": self.connected,
        }


@dataclass
class RoomState:
    room_code: str
    host_player_id: str
    mural: dict[str, Any]
    difficulty: str
    created_at_ms: int
    status: str = "waiting"
    started_at_ms: int | None = None
    finished_at_ms: int | None = None
    seq: int = 0
    total_hits: int = 0
    opened_tiles: int = 0
    total_tiles: int = 0
    max_combo: int = 0
    completed: bool = False
    players: dict[str, PlayerState] = field(default_factory=dict)
    sockets: dict[str, WebSocket] = field(default_factory=dict)
    events: list[dict[str, Any]] = field(default_factory=list)

    def snapshot(self, include_tokens_for: str | None = None) -> dict[str, Any]:
        players = [player.public() for player in self.players.values()]
        payload = {
            "type": "room_snapshot",
            "roomCode": self.room_code,
            "status": self.status,
            "hostPlayerId": self.host_player_id,
            "difficulty": self.difficulty,
            "mural": self.mural,
            "seq": self.seq,
            "createdAtMs": self.created_at_ms,
            "startedAtMs": self.started_at_ms,
            "finishedAtMs": self.finished_at_ms,
            "totalHits": self.total_hits,
            "openedTiles": self.opened_tiles,
            "totalTiles": self.total_tiles,
            "maxCombo": self.max_combo,
            "completed": self.completed,
            "players": players,
        }
        if include_tokens_for and include_tokens_for in self.players:
            player = self.players[include_tokens_for]
            payload["playerId"] = player.player_id
            payload["playerToken"] = player.token
        return payload


rooms: dict[str, RoomState] = {}


def now_ms() -> int:
    return int(time.time() * 1000)


def db():
    if not DB_CONFIG["password"]:
        raise RuntimeError("LIGHTBREAKER_DB_PASSWORD is required")
    return pymysql.connect(**DB_CONFIG)


def execute(sql: str, params: tuple[Any, ...] = ()) -> None:
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)


def fetch_all(sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return list(cur.fetchall())


def room_code() -> str:
    for _ in range(100):
        code = "".join(random.choice(ROOM_ALPHABET) for _ in range(6))
        if code not in rooms:
            return code
    raise HTTPException(status_code=503, detail="Cannot allocate room code")


def sanitize_nickname(name: str) -> str:
    cleaned = " ".join((name or "Player").strip().split())
    return cleaned[:20] or "Player"


def create_player(room: RoomState, install_id: str, nickname: str) -> PlayerState:
    existing = next((p for p in room.players.values() if p.install_id == install_id), None)
    if existing:
        existing.nickname = sanitize_nickname(nickname)
        existing.last_seen_at_ms = now_ms()
        existing.token = secrets.token_urlsafe(20)
        return existing
    if len(room.players) >= MAX_PLAYERS:
        raise HTTPException(status_code=409, detail="Room is full")
    player_id = secrets.token_hex(8)
    color = PLAYER_COLORS[len(room.players) % len(PLAYER_COLORS)]
    player = PlayerState(
        player_id=player_id,
        install_id=install_id,
        nickname=sanitize_nickname(nickname),
        color=color,
        token=secrets.token_urlsafe(20),
        joined_at_ms=now_ms(),
        last_seen_at_ms=now_ms(),
    )
    room.players[player_id] = player
    return player


def get_room(code: str) -> RoomState:
    room = rooms.get(code.upper())
    if not room:
        raise HTTPException(status_code=404, detail="Room not found")
    if room.status != "finished" and now_ms() - room.created_at_ms > ROOM_TTL_MS:
        room.status = "expired"
    return room


def validate_player(room: RoomState, player_id: str, token: str) -> PlayerState:
    player = room.players.get(player_id)
    if not player or player.token != token:
        raise HTTPException(status_code=403, detail="Invalid player token")
    player.last_seen_at_ms = now_ms()
    return player


async def broadcast(room: RoomState, payload: dict[str, Any]) -> None:
    closed = []
    for player_id, socket in room.sockets.items():
        try:
            await socket.send_text(json.dumps(payload, ensure_ascii=False))
        except Exception:
            closed.append(player_id)
    for player_id in closed:
        room.sockets.pop(player_id, None)
        if player_id in room.players:
            room.players[player_id].connected = False


def persist_room(room: RoomState) -> None:
    execute(
        """
        INSERT INTO multiplayer_rooms
            (room_code, status, host_player_id, mural_id, title, theme, prompt, image_url,
             category_id, difficulty, seed, max_players, created_at_ms, started_at_ms,
             finished_at_ms, total_hits, opened_tiles, total_tiles, max_combo, completed,
             result_json)
        VALUES
            (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            status=VALUES(status), started_at_ms=VALUES(started_at_ms),
            finished_at_ms=VALUES(finished_at_ms), total_hits=VALUES(total_hits),
            opened_tiles=VALUES(opened_tiles), total_tiles=VALUES(total_tiles),
            max_combo=VALUES(max_combo), completed=VALUES(completed),
            result_json=VALUES(result_json)
        """,
        (
            room.room_code,
            room.status,
            room.host_player_id,
            room.mural.get("id", ""),
            room.mural.get("title", ""),
            room.mural.get("theme", ""),
            room.mural.get("prompt", ""),
            room.mural.get("imageUrl"),
            room.mural.get("categoryId", ""),
            room.difficulty,
            int(room.mural.get("seed") or 0),
            MAX_PLAYERS,
            room.created_at_ms,
            room.started_at_ms,
            room.finished_at_ms,
            room.total_hits,
            room.opened_tiles,
            room.total_tiles,
            room.max_combo,
            room.completed,
            json.dumps(room.snapshot(), ensure_ascii=False),
        ),
    )


def persist_player(room_code: str, player: PlayerState) -> None:
    execute(
        """
        INSERT INTO multiplayer_players
            (room_code, player_id, install_id, nickname, color, token, joined_at_ms,
             last_seen_at_ms, connected, total_hits, opened_tiles, max_combo)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            nickname=VALUES(nickname), token=VALUES(token), last_seen_at_ms=VALUES(last_seen_at_ms),
            connected=VALUES(connected), total_hits=VALUES(total_hits),
            opened_tiles=VALUES(opened_tiles), max_combo=VALUES(max_combo)
        """,
        (
            room_code,
            player.player_id,
            player.install_id,
            player.nickname,
            player.color,
            player.token,
            player.joined_at_ms,
            player.last_seen_at_ms,
            player.connected,
            player.total_hits,
            player.opened_tiles,
            player.max_combo,
        ),
    )


def persist_event(room: RoomState, event: dict[str, Any]) -> None:
    execute(
        """
        INSERT INTO multiplayer_events
            (room_code, seq, player_id, hand, timestamp_ms, intensity, source_count, payload_json)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
        """,
        (
            room.room_code,
            event["seq"],
            event["playerId"],
            event["hand"],
            event["timestampMs"],
            event["intensity"],
            event["sourceCount"],
            json.dumps(event, ensure_ascii=False),
        ),
    )


def persist_result(room: RoomState, finish: FinishRoomRequest) -> None:
    scoreboard_json = json.dumps(finish.scoreboard, ensure_ascii=False)
    mvp = max(finish.scoreboard, key=lambda item: item.get("totalHits", 0), default={})
    execute(
        """
        INSERT INTO multiplayer_results
            (room_code, total_hits, opened_tiles, total_tiles, duration_seconds,
             max_combo, completed, mvp_player_id, scoreboard_json, created_at_ms)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON DUPLICATE KEY UPDATE
            total_hits=VALUES(total_hits), opened_tiles=VALUES(opened_tiles),
            total_tiles=VALUES(total_tiles), duration_seconds=VALUES(duration_seconds),
            max_combo=VALUES(max_combo), completed=VALUES(completed),
            mvp_player_id=VALUES(mvp_player_id), scoreboard_json=VALUES(scoreboard_json)
        """,
        (
            room.room_code,
            finish.totalHits,
            finish.openedTiles,
            finish.totalTiles,
            finish.durationSeconds,
            finish.maxCombo,
            finish.completed,
            mvp.get("playerId"),
            scoreboard_json,
            now_ms(),
        ),
    )
    execute(
        """
        INSERT INTO team_leaderboards
            (room_code, title, difficulty, duration_seconds, total_hits, opened_tiles,
             total_tiles, player_count, mvp_nickname, completed, created_at_ms)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        """,
        (
            room.room_code,
            room.mural.get("title", ""),
            room.difficulty,
            finish.durationSeconds,
            finish.totalHits,
            finish.openedTiles,
            finish.totalTiles,
            len(room.players),
            mvp.get("nickname"),
            finish.completed,
            now_ms(),
        ),
    )


@app.get("/lightbreaker/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "rooms": len(rooms), "timeMs": now_ms()}


@app.post("/lightbreaker/api/rooms")
async def create_room(request: CreateRoomRequest) -> dict[str, Any]:
    code = room_code()
    host = PlayerState(
        player_id=secrets.token_hex(8),
        install_id=request.installId,
        nickname=sanitize_nickname(request.nickname),
        color=PLAYER_COLORS[0],
        token=secrets.token_urlsafe(20),
        joined_at_ms=now_ms(),
        last_seen_at_ms=now_ms(),
    )
    room = RoomState(
        room_code=code,
        host_player_id=host.player_id,
        mural=request.mural.model_dump(),
        difficulty=request.difficulty.lower(),
        created_at_ms=now_ms(),
    )
    room.players[host.player_id] = host
    rooms[code] = room
    persist_room(room)
    persist_player(code, host)
    return room.snapshot(include_tokens_for=host.player_id)


@app.post("/lightbreaker/api/rooms/{room_code}/join")
async def join_room(room_code: str, request: JoinRoomRequest) -> dict[str, Any]:
    room = get_room(room_code)
    if room.status not in {"waiting", "running"}:
        raise HTTPException(status_code=409, detail="Room is not joinable")
    player = create_player(room, request.installId, request.nickname)
    persist_player(room.room_code, player)
    persist_room(room)
    await broadcast(room, {"type": "player_joined", "room": room.snapshot()})
    return room.snapshot(include_tokens_for=player.player_id)


@app.post("/lightbreaker/api/rooms/{room_code}/start")
async def start_room(room_code: str, request: StartRoomRequest) -> dict[str, Any]:
    room = get_room(room_code)
    validate_player(room, request.playerId, request.playerToken)
    if room.host_player_id != request.playerId:
        raise HTTPException(status_code=403, detail="Only host can start")
    room.status = "running"
    room.started_at_ms = room.started_at_ms or now_ms()
    persist_room(room)
    await broadcast(room, room.snapshot())
    return room.snapshot(include_tokens_for=request.playerId)


@app.post("/lightbreaker/api/rooms/{room_code}/finish")
async def finish_room(room_code: str, request: FinishRoomRequest) -> dict[str, Any]:
    room = get_room(room_code)
    validate_player(room, request.playerId, request.playerToken)
    room.status = "finished"
    room.finished_at_ms = now_ms()
    room.total_hits = request.totalHits
    room.opened_tiles = request.openedTiles
    room.total_tiles = request.totalTiles
    room.max_combo = request.maxCombo
    room.completed = request.completed
    persist_result(room, request)
    persist_room(room)
    payload = {"type": "session_finished", "room": room.snapshot(), "scoreboard": request.scoreboard}
    await broadcast(room, payload)
    return payload


@app.get("/lightbreaker/api/leaderboards/team")
def team_leaderboard(limit: int = 20) -> dict[str, Any]:
    rows = fetch_all(
        """
        SELECT room_code, title, difficulty, duration_seconds, total_hits, opened_tiles,
               total_tiles, player_count, mvp_nickname, completed, created_at_ms
        FROM team_leaderboards
        ORDER BY completed DESC, duration_seconds ASC, opened_tiles DESC, created_at_ms DESC
        LIMIT %s
        """,
        (max(1, min(limit, 100)),),
    )
    return {"items": rows}


@app.websocket("/lightbreaker/ws/rooms/{room_code}")
async def room_socket(websocket: WebSocket, room_code: str) -> None:
    await websocket.accept()
    player_id = websocket.query_params.get("playerId") or ""
    token = websocket.query_params.get("token") or ""
    try:
        room = get_room(room_code)
        player = validate_player(room, player_id, token)
    except HTTPException as exc:
        await websocket.send_text(json.dumps({"type": "error", "message": exc.detail}, ensure_ascii=False))
        await websocket.close()
        return
    room.sockets[player_id] = websocket
    player.connected = True
    persist_player(room.room_code, player)
    await websocket.send_text(json.dumps(room.snapshot(include_tokens_for=player_id), ensure_ascii=False))
    if room.events:
        await websocket.send_text(json.dumps({"type": "event_history", "events": room.events}, ensure_ascii=False))
    await broadcast(room, {"type": "player_joined", "room": room.snapshot()})
    try:
        while True:
            raw = await websocket.receive_text()
            message = json.loads(raw)
            kind = message.get("type")
            player.last_seen_at_ms = now_ms()
            if kind == "ping":
                await websocket.send_text(json.dumps({"type": "pong", "timeMs": now_ms()}))
            elif kind == "join_room":
                await websocket.send_text(json.dumps(room.snapshot(include_tokens_for=player_id), ensure_ascii=False))
            elif kind == "snapshot_submit":
                room.total_hits = int(message.get("totalHits", room.total_hits) or 0)
                room.opened_tiles = int(message.get("openedTiles", room.opened_tiles) or 0)
                room.total_tiles = int(message.get("totalTiles", room.total_tiles) or 0)
                room.max_combo = int(message.get("maxCombo", room.max_combo) or 0)
                room.completed = bool(message.get("completed", room.completed))
                persist_room(room)
            elif kind == "finish_room":
                room.status = "finished"
                room.finished_at_ms = now_ms()
                persist_room(room)
                await broadcast(room, {"type": "session_finished", "room": room.snapshot()})
            elif kind == "hit_submit":
                if room.status != "running":
                    await websocket.send_text(json.dumps({"type": "error", "message": "Room is not running"}))
                    continue
                room.seq += 1
                room.total_hits += 1
                player.total_hits += 1
                player.max_combo = max(player.max_combo, int(message.get("combo", 0) or 0))
                event = {
                    "type": "hit_accepted",
                    "roomCode": room.room_code,
                    "seq": room.seq,
                    "playerId": player_id,
                    "nickname": player.nickname,
                    "color": player.color,
                    "hand": message.get("hand", "unknown"),
                    "timestampMs": int(message.get("timestampMs", now_ms()) or now_ms()),
                    "intensity": int(message.get("intensity", 120) or 120),
                    "sourceCount": int(message.get("sourceCount", 0) or 0),
                }
                persist_event(room, event)
                room.events.append(event)
                persist_player(room.room_code, player)
                persist_room(room)
                await broadcast(room, event)
            else:
                await websocket.send_text(json.dumps({"type": "error", "message": f"Unknown type: {kind}"}))
    except WebSocketDisconnect:
        pass
    finally:
        room.sockets.pop(player_id, None)
        player.connected = False
        persist_player(room.room_code, player)
        await broadcast(room, {"type": "player_left", "room": room.snapshot(), "playerId": player_id})
