package com.zclei.lightbreaker

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zclei.lightbreaker.ble.BleGloveManager
import com.zclei.lightbreaker.ble.ConnectionPhase
import com.zclei.lightbreaker.ble.GloveConnectionState
import com.zclei.lightbreaker.ble.GloveDevice
import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.data.GalleryItemEntity
import com.zclei.lightbreaker.data.LightBreakerRepository
import com.zclei.lightbreaker.data.ProgressStats
import com.zclei.lightbreaker.game.GameDifficulty
import com.zclei.lightbreaker.game.GameSnapshot
import com.zclei.lightbreaker.game.GameSoundPlayer
import com.zclei.lightbreaker.game.LightBreakerGameEngine
import com.zclei.lightbreaker.game.LightBreakerGameView
import com.zclei.lightbreaker.hit.HitEvent
import com.zclei.lightbreaker.mural.CloudMuralGenerationApi
import com.zclei.lightbreaker.mural.GeneratedMural
import com.zclei.lightbreaker.multiplayer.MultiplayerApi
import com.zclei.lightbreaker.multiplayer.MultiplayerPlayer
import com.zclei.lightbreaker.multiplayer.MultiplayerRoomSession
import com.zclei.lightbreaker.multiplayer.MultiplayerRoomState
import com.zclei.lightbreaker.multiplayer.RemoteHitEvent
import com.zclei.lightbreaker.multiplayer.RoomSocketClient
import com.zclei.lightbreaker.multiplayer.RoomSocketEvent
import com.zclei.lightbreaker.network.ServerConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var repository: LightBreakerRepository
    private lateinit var bleManager: BleGloveManager

    private val muralApi = CloudMuralGenerationApi()
    private val multiplayerApi = MultiplayerApi()
    private val roomSocket = RoomSocketClient()
    private val engine = LightBreakerGameEngine()
    private val soundPlayer = GameSoundPlayer()

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout

    private var currentMural: GeneratedMural? = null
    private var currentSnapshot: GameSnapshot? = null
    private var currentDifficulty = GameDifficulty.Standard
    private var playMode = PlayMode.Single
    private var trainingActive = false
    private var trainingStartedAtMs = 0L
    private var simulationCount = 0

    private var multiplayerSession: MultiplayerRoomSession? = null
    private var multiplayerState: MultiplayerRoomState? = null
    private var multiplayerStatusText: TextView? = null
    private var multiplayerLogText: TextView? = null
    private val multiplayerLines = ArrayDeque<String>()

    private var progressStats: ProgressStats = ProgressStats(0, 1, null, null)
    private var latestStates: Map<GloveHand, GloveConnectionState> = emptyMap()
    private var latestDevices: List<GloveDevice> = emptyList()
    private val debugLines = ArrayDeque<String>()

    private var homeProgressText: TextView? = null
    private var homeDeviceText: TextView? = null
    private var homeMuralText: TextView? = null
    private var homeDeviceList: LinearLayout? = null
    private var debugStatusText: TextView? = null
    private var debugDeviceList: LinearLayout? = null
    private var debugLogText: TextView? = null
    private var galleryList: LinearLayout? = null
    private var gameView: LightBreakerGameView? = null
    private var gameStatsText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as LightBreakerApplication).repository
        bleManager = BleGloveManager(this, lifecycleScope)
        requestRuntimePermissions()
        buildShell()
        bindFlows()
        showHome()
        lifecycleScope.launch { generateMural("击碎压力，露出治愈光影", "自然风光", silent = true) }
    }

    override fun onDestroy() {
        roomSocket.close()
        bleManager.disconnectAll()
        soundPlayer.release()
        super.onDestroy()
    }

    private fun buildShell() {
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#070A18"))
                layoutParams = LinearLayout.LayoutParams(match, match)
            }
        root.addView(buildTopBar())
        content = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(match, 0, 1f) }
        root.addView(content)
        setContentView(root)
    }

    private fun buildTopBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = solid("#0D1528")
            addView(
                TextView(context).apply {
                    text = ServerConfig.APP_NAME
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 22f
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(tabButton("首页") { showHome() })
                    addView(tabButton("多人") { showMultiplayer() })
                    addView(tabButton("蓝牙") { showDebug() })
                    addView(tabButton("画廊") { showGallery() })
                },
            )
        }

    private fun tabButton(
        text: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded("#17233A", "#2C3E60", 14)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }

    private fun showHome() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playMode = PlayMode.Single
        content.removeAllViews()
        val promptInput = input("输入想击碎的压力或画作关键词", minLines = 2)
        val themeInput = input("图片类型：自然风光、名画再现、城市建筑、抽象艺术")
        content.addView(
            scroll {
                addView(sectionTitle("训练入口"))
                homeProgressText = infoCard("").also { addView(it) }
                homeDeviceText = infoCard("").also { addView(it) }
                homeMuralText = infoCard("").also { addView(it) }
                addView(label("云端图片类型"))
                addView(promptInput, LinearLayout.LayoutParams(match, wrap).withBottom(dp(8)))
                addView(themeInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(10)))
                addCategoryButtons(themeInput)
                addDifficultyButtons()
                addView(row(
                    actionButton("生成画作", "#2563EB") {
                        lifecycleScope.launch { generateMural(promptInput.text.toString(), themeInput.text.toString()) }
                    },
                    actionButton("开始训练", "#16A34A") { startSingleTraining() },
                ))
                addView(row(
                    actionButton("扫描手套", "#0F766E") { bleManager.startScan() },
                    actionButton("停止扫描", "#475569") { bleManager.stopScan() },
                ))
                addView(sectionTitle("发现的手套"))
                homeDeviceList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(homeDeviceList)
            },
        )
        refreshHome()
        renderDeviceList(homeDeviceList)
    }

    private fun showMultiplayer() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playMode = PlayMode.Multiplayer
        content.removeAllViews()
        val nicknameInput = input("昵称").apply { setText("玩家") }
        val roomCodeInput = input("输入 6 位房间码")
        val categoryInput = input("图片类型").apply { setText("自然风光") }
        content.addView(
            scroll {
                addView(sectionTitle("多人房间"))
                multiplayerStatusText = infoCard("").also { addView(it) }
                addView(label("昵称"))
                addView(nicknameInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(8)))
                addView(label("房间码"))
                addView(roomCodeInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(8)))
                addView(label("图片类型与难度"))
                addCategoryButtons(categoryInput)
                addDifficultyButtons()
                addView(row(
                    actionButton("创建房间", "#2563EB") {
                        lifecycleScope.launch { createMultiplayerRoom(nicknameInput.text.toString(), categoryInput.text.toString()) }
                    },
                    actionButton("加入房间", "#0F766E") {
                        lifecycleScope.launch { joinMultiplayerRoom(roomCodeInput.text.toString(), nicknameInput.text.toString()) }
                    },
                ))
                addView(row(
                    actionButton("开始协作", "#16A34A") { lifecycleScope.launch { startMultiplayerRoom() } },
                    actionButton("离开房间", "#475569") { leaveMultiplayerRoom() },
                ))
                multiplayerLogText = infoCard("").also { addView(it) }
            },
        )
        refreshMultiplayer()
    }

    private fun showDebug() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.removeAllViews()
        content.addView(
            scroll {
                addView(sectionTitle("蓝牙调试"))
                addView(infoCard("服务器 ${ServerConfig.MULTIPLAYER_API_BASE_URL}\n数据库 ${ServerConfig.DATABASE_NAME}"))
                debugStatusText = infoCard("").also { addView(it) }
                addView(row(
                    actionButton("扫描", "#2563EB") { bleManager.startScan() },
                    actionButton("断开全部", "#B45309") { bleManager.disconnectAll() },
                ))
                addView(sectionTitle("设备列表"))
                debugDeviceList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(debugDeviceList)
                addView(sectionTitle("原始通知日志"))
                debugLogText = infoCard("").also { addView(it) }
            },
        )
        refreshDebug()
        renderDeviceList(debugDeviceList)
    }

    private fun showGallery() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.removeAllViews()
        content.addView(
            scroll {
                addView(sectionTitle("我的画廊"))
                galleryList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(galleryList)
            },
        )
        lifecycleScope.launch { renderGallery(repository.gallery()) }
    }

    private suspend fun createMultiplayerRoom(
        nickname: String,
        category: String,
    ) {
        toast("正在创建多人房间")
        val mural = muralApi.generate("多人协作拆盲盒", category.ifBlank { "自然风光" })
        val session = multiplayerApi.createRoom(installId(), nickname.ifBlank { "玩家" }, mural, currentDifficulty)
        multiplayerSession = session
        multiplayerState = session.state
        currentMural = session.state.mural
        currentDifficulty = session.state.difficulty
        roomSocket.connect(session)
        addMultiplayerLine("房间 ${session.state.roomCode} 已创建，等待成员加入")
        refreshMultiplayer()
    }

    private suspend fun joinMultiplayerRoom(
        roomCode: String,
        nickname: String,
    ) {
        if (roomCode.isBlank()) {
            toast("请输入房间码")
            return
        }
        val session = multiplayerApi.joinRoom(roomCode, installId(), nickname.ifBlank { "玩家" })
        multiplayerSession = session
        multiplayerState = session.state
        currentMural = session.state.mural
        currentDifficulty = session.state.difficulty
        roomSocket.connect(session)
        addMultiplayerLine("已加入房间 ${session.state.roomCode}")
        refreshMultiplayer()
    }

    private suspend fun startMultiplayerRoom() {
        val session = multiplayerSession ?: return toast("请先创建或加入房间")
        if (!session.isHost) return toast("只有房主可以开始")
        val started = multiplayerApi.startRoom(session)
        multiplayerSession = started
        multiplayerState = started.state
        startMultiplayerTraining(started.state)
    }

    private fun leaveMultiplayerRoom() {
        roomSocket.close()
        multiplayerSession = null
        multiplayerState = null
        trainingActive = false
        addMultiplayerLine("已离开多人房间")
        refreshMultiplayer()
    }

    private fun startSingleTraining() {
        val mural = currentMural ?: return toast("请先生成一幅画作")
        playMode = PlayMode.Single
        startTrainingScreen(mural, currentDifficulty, title = "单人训练")
    }

    private fun startMultiplayerTraining(state: MultiplayerRoomState) {
        playMode = PlayMode.Multiplayer
        currentMural = state.mural
        currentDifficulty = state.difficulty
        startTrainingScreen(state.mural, state.difficulty, title = "多人房间 ${state.roomCode}")
    }

    private fun startTrainingScreen(
        mural: GeneratedMural,
        difficulty: GameDifficulty,
        title: String,
    ) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        trainingActive = true
        trainingStartedAtMs = System.currentTimeMillis()
        simulationCount = 0
        currentSnapshot = engine.start(mural, difficulty)
        content.removeAllViews()
        val screen =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                setBackgroundColor(Color.parseColor("#070A18"))
            }
        gameStatsText = infoCard(title)
        screen.addView(gameStatsText)
        gameView =
            LightBreakerGameView(this).apply {
                setPadding(0, dp(6), 0, dp(6))
                setGameState(mural, currentSnapshot)
            }
        screen.addView(gameView, LinearLayout.LayoutParams(match, 0, 1f))
        screen.addView(row(
            actionButton("模拟左拳", "#2563EB") { simulateHit(GloveHand.Left) },
            actionButton("模拟右拳", "#EA580C") { simulateHit(GloveHand.Right) },
            actionButton("结算", "#16A34A") { finishTraining(manual = true) },
        ))
        content.addView(screen)
        refreshGameStats()
    }

    private fun applyHit(
        hit: HitEvent,
        playerId: String? = null,
        acceptedRemote: Boolean = false,
    ) {
        if (!trainingActive) return
        if (playMode == PlayMode.Multiplayer && !acceptedRemote) {
            roomSocket.sendHit(hit, currentSnapshot?.combo ?: 0)
            return
        }
        currentSnapshot = engine.registerHit(hit, playerId)
        val snapshot = currentSnapshot ?: return
        gameView?.setGameState(currentMural, snapshot)
        refreshGameStats()
        if (snapshot.lastReward != null) soundPlayer.treasure() else soundPlayer.hit(hit.intensity)
        if (playMode == PlayMode.Multiplayer) {
            roomSocket.sendSnapshot(snapshot.totalHits, snapshot.openedTiles, snapshot.totalTiles, snapshot.maxCombo, snapshot.completed)
        }
        if (snapshot.completed) {
            soundPlayer.complete()
            finishTraining(manual = false)
        }
    }

    private fun applyRemoteHit(remote: RemoteHitEvent) {
        if (playMode != PlayMode.Multiplayer && multiplayerState?.status == "running") {
            multiplayerState?.let { startMultiplayerTraining(it) }
        }
        applyHit(
            HitEvent(
                hand = remote.hand.toGloveHand(),
                timestampMs = remote.timestampMs,
                intensity = remote.intensity,
                sourceCount = remote.sourceCount,
            ),
            playerId = remote.playerId,
            acceptedRemote = true,
        )
    }

    private fun simulateHit(hand: GloveHand) {
        simulationCount = (simulationCount + 1) and 0xFF
        applyHit(
            HitEvent(
                hand = hand,
                timestampMs = System.currentTimeMillis(),
                intensity = if (simulationCount % 7 == 0) 220 else 150,
                sourceCount = simulationCount,
            ),
        )
    }

    private fun finishTraining(manual: Boolean) {
        val mural = currentMural ?: return
        val snapshot = currentSnapshot ?: return
        trainingActive = false
        val endedAt = System.currentTimeMillis()
        if (playMode == PlayMode.Multiplayer) {
            finishMultiplayer(snapshot, manual, endedAt)
        } else {
            lifecycleScope.launch {
                val result = repository.saveSession(mural, snapshot, trainingStartedAtMs, endedAt)
                showResult(snapshot, result.xpGain, result.gallerySaved, manual, "单人训练")
            }
        }
    }

    private fun finishMultiplayer(
        snapshot: GameSnapshot,
        manual: Boolean,
        endedAt: Long,
    ) {
        val session = multiplayerSession
        val mural = currentMural ?: return
        val durationSeconds = ((endedAt - trainingStartedAtMs) / 1000L).coerceAtLeast(1L).toInt()
        lifecycleScope.launch {
            if (session != null) {
                runCatching {
                    multiplayerApi.finishRoom(session, snapshot, durationSeconds, multiplayerState?.players.orEmpty())
                }.onFailure { addMultiplayerLine("多人结算上传失败：${it.message}") }
            }
            val result =
                repository.saveSession(
                    mural.copy(title = "多人 ${mural.title}", prompt = "${mural.prompt} 房间 ${session?.state?.roomCode ?: "--"}"),
                    snapshot,
                    trainingStartedAtMs,
                    endedAt,
                )
            showResult(snapshot, result.xpGain, result.gallerySaved, manual, "多人协作 ${session?.state?.roomCode ?: ""}")
        }
    }

    private fun showResult(
        snapshot: GameSnapshot,
        xpGain: Int,
        saved: Boolean,
        manual: Boolean,
        title: String,
    ) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.removeAllViews()
        content.addView(
            scroll {
                addView(sectionTitle(if (snapshot.completed) "作品完整揭开" else "本轮训练结算"))
                addView(
                    infoCard(
                        buildString {
                            appendLine(title)
                            appendLine(currentMural?.title.orEmpty())
                            appendLine("难度 ${snapshot.difficulty.title} · 进度 ${snapshot.progressPercent.roundToInt()}% · ${snapshot.openedTiles}/${snapshot.totalTiles} 块")
                            appendLine("总出拳 ${snapshot.totalHits} · 左手 ${snapshot.leftHits} · 右手 ${snapshot.rightHits}")
                            appendLine("最高连击 x${snapshot.maxCombo} · ${"%.1f".format(snapshot.calories)} kcal · XP +$xpGain")
                            snapshot.lastReward?.let { appendLine("宝箱奖励：${it.label}") }
                            if (playMode == PlayMode.Multiplayer) appendLine(multiplayerScoreText(snapshot))
                            append(if (saved) "已进入我的画廊" else "训练记录已保存，未完成作品不进入画廊")
                            if (manual) append(" · 手动结算")
                        },
                    ),
                )
                addView(row(
                    actionButton("再来一幅", "#2563EB") {
                        lifecycleScope.launch { generateMural("新的破壁挑战", "自然风光") }
                        showHome()
                    },
                    actionButton("查看画廊", "#16A34A") { showGallery() },
                ))
            },
        )
    }

    private suspend fun generateMural(
        prompt: String,
        theme: String,
        silent: Boolean = false,
    ) {
        if (!silent) toast("正在从云端图片库选择画作")
        currentMural = muralApi.generate(prompt, theme)
        currentSnapshot = null
        refreshHome()
        if (!silent) toast("画作已生成：${currentMural?.title}")
    }

    private fun bindFlows() {
        lifecycleScope.launch {
            repository.progressStats.collect {
                progressStats = it
                refreshHome()
            }
        }
        lifecycleScope.launch {
            bleManager.devices.collect {
                latestDevices = it
                renderDeviceList(homeDeviceList)
                renderDeviceList(debugDeviceList)
            }
        }
        lifecycleScope.launch {
            bleManager.states.collect {
                latestStates = it
                refreshHome()
                refreshDebug()
                val left = it[GloveHand.Left]?.deviceName
                val right = it[GloveHand.Right]?.deviceName
                if (left != null || right != null) repository.rememberDevice(left, right)
            }
        }
        lifecycleScope.launch {
            bleManager.packets.collect { packet ->
                debugLines.addFirst("${time()} ${packet.hand.displayName} ${packet.rawHex} · ${packet.batteryText}")
                while (debugLines.size > 24) debugLines.removeLast()
                refreshDebug()
            }
        }
        lifecycleScope.launch {
            bleManager.hits.collect { applyHit(it) }
        }
        lifecycleScope.launch {
            roomSocket.events.collect { event ->
                when (event) {
                    is RoomSocketEvent.Snapshot -> {
                        multiplayerState = event.state
                        currentMural = event.state.mural
                        currentDifficulty = event.state.difficulty
                        addMultiplayerLine("房间状态：${event.state.status} · 成员 ${event.state.players.size}")
                        if (event.state.status == "running" && (!trainingActive || playMode != PlayMode.Multiplayer)) {
                            startMultiplayerTraining(event.state)
                        }
                        refreshMultiplayer()
                    }
                    is RoomSocketEvent.HitAccepted -> applyRemoteHit(event.hit)
                    is RoomSocketEvent.SessionFinished -> {
                        event.state?.let { multiplayerState = it }
                        addMultiplayerLine("多人房间已结算")
                        refreshMultiplayer()
                    }
                    is RoomSocketEvent.Notice -> {
                        addMultiplayerLine(event.message)
                        refreshMultiplayer()
                    }
                }
            }
        }
    }

    private fun refreshHome() {
        homeProgressText?.text = "等级 Lv.${progressStats.level} · XP ${progressStats.xp}\n最近设备：左 ${progressStats.lastLeftDevice ?: "--"} · 右 ${progressStats.lastRightDevice ?: "--"}"
        homeDeviceText?.text = buildDeviceStatusText()
        homeMuralText?.text =
            currentMural?.let {
                "当前画作：${it.title}\n类型：${it.theme} · ${it.categoryId}\n难度：${currentDifficulty.title} · ${currentDifficulty.columns * currentDifficulty.rows} 块\n图片：${it.imageUrl ?: "程序绘制底图"}\n提示词：${it.prompt}"
            } ?: "当前画作：尚未生成"
    }

    private fun refreshMultiplayer() {
        val session = multiplayerSession
        val state = multiplayerState
        multiplayerStatusText?.text =
            if (session == null || state == null) {
                "未加入房间。\n选择图片类型和难度后创建房间，或输入房间码加入。"
            } else {
                buildString {
                    appendLine("房间 ${state.roomCode} · ${state.status} · ${state.difficulty.title}")
                    appendLine("身份：${if (session.isHost) "房主" else "成员"} · 成员 ${state.players.size}/4")
                    appendLine("画作：${state.mural.title}")
                    append(state.players.joinToString("\n") { "${it.nickname} · 出拳 ${it.totalHits} · ${if (it.connected) "在线" else "离线"}" })
                }
            }
        multiplayerLogText?.text = multiplayerLines.joinToString("\n").ifBlank { "暂无多人事件。" }
    }

    private fun refreshDebug() {
        debugStatusText?.text = buildDeviceStatusText()
        debugLogText?.text = debugLines.joinToString("\n").ifBlank { "暂无通知包。连接手套并开启陀螺仪后，这里会显示 D5 5D 03 数据包。" }
    }

    private fun refreshGameStats() {
        val snap = currentSnapshot ?: return
        gameStatsText?.text =
            buildString {
                appendLine("${if (playMode == PlayMode.Multiplayer) "多人协作" else "单人训练"} · ${snap.difficulty.title} · ${snap.columns}x${snap.rows}")
                appendLine("进度 ${snap.progressPercent.roundToInt()}% · ${snap.openedTiles}/${snap.totalTiles} 块")
                appendLine("总出拳 ${snap.totalHits} · 左 ${snap.leftHits} · 右 ${snap.rightHits} · 连击 x${snap.combo} · 最高 x${snap.maxCombo}")
                append("热量 ${"%.1f".format(snap.calories)} kcal")
                snap.lastReward?.let { append(" · 宝箱 ${it.label}") }
                if (playMode == PlayMode.Multiplayer) append("\n${multiplayerScoreText(snap)}")
            }
    }

    private fun multiplayerScoreText(snapshot: GameSnapshot): String {
        val players = multiplayerState?.players.orEmpty()
        if (players.isEmpty()) return "团队贡献：暂无"
        return players.joinToString(" · ", prefix = "团队贡献：") {
            "${it.nickname} ${snapshot.playerHits[it.playerId] ?: it.totalHits}"
        }
    }

    private fun buildDeviceStatusText(): String {
        val left = latestStates[GloveHand.Left] ?: GloveConnectionState(GloveHand.Left)
        val right = latestStates[GloveHand.Right] ?: GloveConnectionState(GloveHand.Right)
        return listOf(left, right).joinToString("\n") { state ->
            "${state.hand.displayName}: ${state.phase.label()} · ${state.deviceName ?: "--"} · 电量 ${state.batteryText} · 次数 ${state.gyroCount} · 力度 ${state.gyroPower}\n${state.message}"
        }
    }

    private fun renderDeviceList(container: LinearLayout?) {
        container ?: return
        container.removeAllViews()
        if (latestDevices.isEmpty()) {
            container.addView(infoCard("未发现手套。请确认设备名为 BOXING#PL... 或 BOXING#PR...，并点击扫描。"))
            return
        }
        latestDevices.forEach { device ->
            container.addView(
                actionButton("${device.hand.displayName} · ${device.name} · RSSI ${device.rssi}", "#1E3A8A") {
                    bleManager.connect(device)
                },
                LinearLayout.LayoutParams(match, dp(50)).withBottom(dp(8)),
            )
        }
    }

    private fun renderGallery(items: List<GalleryItemEntity>) {
        val container = galleryList ?: return
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(infoCard("还没有完成作品。完成 100% 揭开后，作品会自动进入这里。"))
            return
        }
        items.forEach { item ->
            container.addView(
                infoCard(
                    "${item.title}\n" +
                        "${date(item.finishedAtMs)} · ${item.theme}\n" +
                        "进度 ${item.openedTiles}/${item.totalTiles} · 出拳 ${item.totalHits} · 最高连击 x${item.maxCombo} · ${"%.1f".format(item.calories)} kcal\n" +
                        "提示词：${item.prompt}",
                ),
                LinearLayout.LayoutParams(match, wrap).withBottom(dp(10)),
            )
        }
    }

    private fun LinearLayout.addCategoryButtons(input: EditText) {
        addView(row(
            actionButton("自然风光", "#2563EB") { input.setText("自然风光") },
            actionButton("名画再现", "#7C3AED") { input.setText("名画再现") },
        ))
        addView(row(
            actionButton("城市建筑", "#0F766E") { input.setText("城市建筑") },
            actionButton("抽象艺术", "#EA580C") { input.setText("抽象艺术") },
        ))
    }

    private fun LinearLayout.addDifficultyButtons() {
        addView(row(
            actionButton("简单 150", "#0F766E") {
                currentDifficulty = GameDifficulty.Easy
                refreshHome()
            },
            actionButton("标准 300", "#2563EB") {
                currentDifficulty = GameDifficulty.Standard
                refreshHome()
            },
            actionButton("挑战 500", "#B45309") {
                currentDifficulty = GameDifficulty.Challenge
                refreshHome()
            },
        ))
    }

    private fun input(
        hintText: String,
        minLines: Int = 1,
    ): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#687899"))
            setTextColor(Color.WHITE)
            setSingleLine(minLines == 1)
            this.minLines = minLines
            background = rounded("#101A2E", "#263B5F", 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

    private fun requestRuntimePermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun scroll(build: LinearLayout.() -> Unit): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(28))
                    build()
                },
                ViewGroup.LayoutParams(match, wrap),
            )
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(8))
        }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#9FB2D0"))
            setPadding(0, dp(10), 0, dp(6))
        }

    private fun infoCard(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#E5EEF8"))
            setLineSpacing(2f, 1.05f)
            background = rounded("#101A2E", "#24385A", 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(match, wrap).withBottom(dp(10))
        }

    private fun actionButton(
        text: String,
        color: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            background = rounded(color, lighten(color), 14)
            setOnClickListener { action() }
        }

    private fun row(vararg children: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            children.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index < children.lastIndex) marginEnd = dp(8)
                })
            }
            layoutParams = LinearLayout.LayoutParams(match, wrap).withBottom(dp(10))
        }

    private fun solid(color: String): GradientDrawable = GradientDrawable().apply { setColor(Color.parseColor(color)) }

    private fun rounded(
        fill: String,
        stroke: String,
        radiusDp: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun lighten(color: String): String =
        when (color.uppercase(Locale.US)) {
            "#2563EB" -> "#60A5FA"
            "#7C3AED" -> "#A78BFA"
            "#16A34A" -> "#4ADE80"
            "#0F766E" -> "#2DD4BF"
            "#B45309" -> "#F59E0B"
            "#EA580C" -> "#FB923C"
            "#475569" -> "#64748B"
            else -> "#3B82F6"
        }

    private fun ConnectionPhase.label(): String =
        when (this) {
            ConnectionPhase.Idle -> "空闲"
            ConnectionPhase.Scanning -> "扫描中"
            ConnectionPhase.Connecting -> "连接中"
            ConnectionPhase.Connected -> "已连接"
            ConnectionPhase.Ready -> "就绪"
            ConnectionPhase.Disconnected -> "已断开"
            ConnectionPhase.Error -> "异常"
        }

    private fun String.toGloveHand(): GloveHand =
        when (lowercase(Locale.US)) {
            "left" -> GloveHand.Left
            "right" -> GloveHand.Right
            else -> GloveHand.Unknown
        }

    private fun addMultiplayerLine(line: String) {
        multiplayerLines.addFirst("${time()} $line")
        while (multiplayerLines.size > 20) multiplayerLines.removeLast()
    }

    private fun installId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android-${Build.MODEL.hashCode()}"

    private fun LinearLayout.LayoutParams.withBottom(bottom: Int): LinearLayout.LayoutParams =
        apply { bottomMargin = bottom }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    private enum class PlayMode {
        Single,
        Multiplayer,
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1102
        const val match = ViewGroup.LayoutParams.MATCH_PARENT
        const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
