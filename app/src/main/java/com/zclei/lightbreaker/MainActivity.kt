package com.zclei.lightbreaker

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import kotlin.math.max
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
    private var headerBleStatusView: BluetoothStatusIndicatorView? = null
    private var headerBleLeftBatteryView: BatteryStatusIndicatorView? = null
    private var headerBleRightBatteryView: BatteryStatusIndicatorView? = null

    private var currentMural: GeneratedMural? = null
    private var currentSnapshot: GameSnapshot? = null
    private var currentDifficulty = GameDifficulty.Standard
    private var playMode = PlayMode.Single
    private var trainingActive = false
    private var trainingStartedAtMs = 0L
    private var simulationCount = 0
    private var currentTheme = "自然风光"

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
    private var homeSettingsText: TextView? = null
    private var homeBleStatusRow: LinearLayout? = null
    private var homeMuralText: TextView? = null
    private var settingsSummaryText: TextView? = null
    private var settingsBleStatusText: TextView? = null
    private var settingsBleConnectionRow: LinearLayout? = null
    private var settingsSelectedDeviceText: TextView? = null
    private var settingsDeviceRadioGroup: RadioGroup? = null
    private var settingsSelectedDeviceAddress: String? = null
    private var settingsConnectButton: Button? = null
    private var settingsDisconnectButton: Button? = null
    private var settingsDeviceList: LinearLayout? = null
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
                setBackgroundColor(Color.parseColor(NIGHT_BG))
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
            background = rounded(NIGHT_PANEL, GOLD_LINE, 0)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        ImageView(context).apply {
                            setImageResource(R.mipmap.ic_launcher)
                            contentDescription = "LightBreaker"
                        },
                        LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) },
                    )
                    addView(
                        TextView(context).apply {
                            text = ServerConfig.APP_NAME
                            setTextColor(Color.parseColor(GOLD))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            textSize = 22f
                        },
                        LinearLayout.LayoutParams(0, wrap, 1f),
                    )
                    addView(buildHeaderBlePanel())
                    addView(iconButton("⚙") { showSettings() })
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(tabButton("首页") { showHome() })
                    addView(tabButton("多人") { showMultiplayer() })
                    addView(tabButton("画廊") { showGallery() })
                },
            )
        }

    private fun buildHeaderBlePanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(8), dp(4))
            headerBleStatusView =
                BluetoothStatusIndicatorView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                    contentDescription = "蓝牙未连接"
                    setConnected(false)
                }
            headerBleLeftBatteryView =
                BatteryStatusIndicatorView(this@MainActivity, "左").apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(30)).apply { leftMargin = dp(5) }
                    setBattery(null)
                }
            headerBleRightBatteryView =
                BatteryStatusIndicatorView(this@MainActivity, "右").apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(30)).apply { leftMargin = dp(4) }
                    setBattery(null)
                }
            addView(headerBleStatusView)
            addView(headerBleLeftBatteryView)
            addView(headerBleRightBatteryView)
        }

    private fun iconButton(
        text: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.parseColor(GOLD))
            background = rounded("#1C1830", GOLD_LINE, 14)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(42))
        }

    private fun tabButton(
        text: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor(GOLD))
            background = rounded("#1C1830", GOLD_LINE, 14)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        }

    private fun showHome() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playMode = PlayMode.Single
        content.removeAllViews()
        val promptInput = input("输入想击碎的压力或画作关键词", minLines = 2)
        content.addView(
            scroll {
                addView(sectionTitle("训练入口"))
                homeProgressText = infoCard("").also { addView(it) }
                homeSettingsText = infoCard("").also { addView(it) }
                addView(sectionTitle("手套状态"))
                homeBleStatusRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                addView(homeBleStatusRow, LinearLayout.LayoutParams(match, wrap).withBottom(dp(8)))
                homeMuralText = infoCard("").also { addView(it) }
                addView(promptInput, LinearLayout.LayoutParams(match, wrap).withBottom(dp(8)))
                addView(row(
                    actionButton("生成画作", GOLD) {
                        lifecycleScope.launch { generateMural(promptInput.text.toString(), currentTheme) }
                    },
                    actionButton("开始训练", ORANGE) { startSingleTraining() },
                ))
                addView(row(
                    actionButton("设置", "#475569") { showSettings() },
                    actionButton("刷新画作", "#A04800") {
                        lifecycleScope.launch { generateMural(promptInput.text.toString(), currentTheme) }
                    },
                ))
            },
        )
        refreshHome()
    }

    private fun showMultiplayer() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playMode = PlayMode.Multiplayer
        content.removeAllViews()
        val nicknameInput = input("昵称").apply { setText("玩家") }
        val roomCodeInput = input("输入 6 位房间码")
        content.addView(
            scroll {
                addView(sectionTitle("多人房间"))
                multiplayerStatusText = infoCard("").also { addView(it) }
                addView(label("昵称"))
                addView(nicknameInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(8)))
                addView(label("房间码"))
                addView(roomCodeInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(8)))
                addView(infoCard("当前设置：$currentTheme · ${currentDifficulty.title}\n点击右上角齿轮可修改图片类型、难度和蓝牙连接。"))
                addView(row(
                    actionButton("创建房间", GOLD) {
                        lifecycleScope.launch { createMultiplayerRoom(nicknameInput.text.toString(), currentTheme) }
                    },
                    actionButton("加入房间", "#A04800") {
                        lifecycleScope.launch { joinMultiplayerRoom(roomCodeInput.text.toString(), nicknameInput.text.toString()) }
                    },
                ))
                addView(row(
                    actionButton("开始协作", ORANGE) { lifecycleScope.launch { startMultiplayerRoom() } },
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
                    actionButton("扫描", GOLD) { bleManager.startScan() },
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

    private fun showSettings() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.removeAllViews()
        settingsDeviceList = null
        val themeInput = input("图片类型").apply { setText(currentTheme) }
        content.addView(
            scroll {
                addView(sectionTitle("设置"))
                settingsSummaryText = infoCard("").also { addView(it) }
                addView(label("图片类型"))
                addView(themeInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(10)))
                addCategoryButtons(themeInput) {
                    currentTheme = themeInput.text.toString()
                    refreshSettings()
                    refreshHome()
                }
                addView(label("训练难度"))
                addDifficultyButtons {
                    refreshSettings()
                    refreshHome()
                }
                addView(sectionTitle("蓝牙设备"))
                settingsBleStatusText = infoCard("").also { addView(it) }
                settingsSelectedDeviceText = infoCard("").also { addView(it) }
                settingsBleConnectionRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                addView(settingsBleConnectionRow, LinearLayout.LayoutParams(match, wrap).withBottom(dp(8)))
                settingsDeviceRadioGroup =
                    RadioGroup(this@MainActivity).apply {
                        orientation = RadioGroup.VERTICAL
                        setPadding(0, 0, 0, dp(6))
                    }
                addView(settingsDeviceRadioGroup)
                addView(row(
                    actionButton("扫描", "#174154") { bleManager.startScan() },
                    actionButton("连接", "#E07010") {
                        val selected = selectedSettingsDevice()
                        if (selected == null) {
                            toast("请先扫描并选择 BOXING 手套")
                        } else {
                            bleManager.connect(selected)
                        }
                    }.also { settingsConnectButton = it },
                    actionButton("断开", "#A73A54") { bleManager.disconnectAll() }.also { settingsDisconnectButton = it },
                ))
                addView(sectionTitle("蓝牙调试信息"))
                debugLogText = infoCard("").also { addView(it) }
            },
        )
        refreshSettings()
        refreshDebug()
        renderSettingsBleDeviceSelection()
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
                setBackgroundColor(Color.parseColor(NIGHT_BG))
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
            actionButton("模拟左拳", GOLD) { simulateHit(GloveHand.Left) },
            actionButton("模拟右拳", "#EA580C") { simulateHit(GloveHand.Right) },
            actionButton("结算", ORANGE) { finishTraining(manual = true) },
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
                    actionButton("再来一幅", GOLD) {
                        lifecycleScope.launch { generateMural("新的破壁挑战", "自然风光") }
                        showHome()
                    },
                    actionButton("查看画廊", ORANGE) { showGallery() },
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
                renderDeviceList(settingsDeviceList)
                renderDeviceList(debugDeviceList)
                renderSettingsBleDeviceSelection()
                renderHomeBleStatus()
                renderSettingsBleConnections()
            }
        }
        lifecycleScope.launch {
            bleManager.states.collect {
                latestStates = it
                refreshHome()
                refreshSettings()
                refreshDebug()
                refreshHeaderBleStatus()
                renderSettingsBleDeviceSelection()
                renderHomeBleStatus()
                renderSettingsBleConnections()
                val left = it[GloveHand.Left]?.deviceName
                val right = it[GloveHand.Right]?.deviceName
                if (left != null || right != null) repository.rememberDevice(left, right)
            }
        }
        lifecycleScope.launch {
            bleManager.packets.collect { packet ->
                debugLines.addFirst("${time()} ${packet.hand.displayName} ${packet.rawHex} · ${packet.batteryText}")
                while (debugLines.size > 24) debugLines.removeLast()
                refreshSettings()
                refreshDebug()
                refreshHeaderBleStatus()
                renderHomeBleStatus()
                renderSettingsBleConnections()
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
        homeSettingsText?.text = "当前设置：$currentTheme · ${currentDifficulty.title} · ${currentDifficulty.columns * currentDifficulty.rows} 块\n点击右上角齿轮可切换图片类型、训练难度和蓝牙手套。"
        refreshHeaderBleStatus()
        renderHomeBleStatus()
        homeMuralText?.text =
            currentMural?.let {
                "当前画作：${it.title}\n类型：${it.theme} · ${it.categoryId}\n难度：${currentDifficulty.title} · ${currentDifficulty.columns * currentDifficulty.rows} 块\n图片：${it.imageUrl ?: "程序绘制底图"}\n提示词：${it.prompt}"
            } ?: "当前画作：尚未生成"
    }

    private fun refreshSettings() {
        settingsSummaryText?.text = "图片类型：$currentTheme\n难度：${currentDifficulty.title} · ${currentDifficulty.columns * currentDifficulty.rows} 块"
        settingsBleStatusText?.text = buildBluetoothSettingsStatus()
        settingsSelectedDeviceText?.text = buildSelectedDeviceText()
        updateSettingsBleButtons()
        renderSettingsBleDeviceSelection()
        renderSettingsBleConnections()
        debugLogText?.text = debugLines.joinToString("\n").ifBlank { "暂无通知包。连接手套并开启陀螺仪后，这里会显示 D5 5D 03 数据包。" }
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

    private fun buildBluetoothSettingsStatus(): String {
        val connectedCount =
            listOf(GloveHand.Left, GloveHand.Right).count { hand ->
                latestStates[hand]?.phase in listOf(ConnectionPhase.Connected, ConnectionPhase.Ready)
            }
        val foundText = if (latestDevices.isEmpty()) "未发现设备" else "已发现 ${latestDevices.size} 个设备"
        return when {
            connectedCount > 0 -> "蓝牙已连接 · $connectedCount/2 只手套在线 · $foundText"
            latestDevices.isNotEmpty() -> "请选择设备后点击连接 · $foundText"
            else -> "蓝牙未连接 · 点击扫描查找 BOXING 手套"
        }
    }

    private fun buildSelectedDeviceText(): String {
        val connected = connectedStates()
        if (connected.isNotEmpty()) {
            return "已连接 ${connected.size} 个设备：\n" +
                connected.joinToString("\n") { formatConnectedState(it) }
        }
        val selected = selectedSettingsDevice()
        return if (selected == null) {
            "未选择设备\n设备名规则：BOXING#L + 6 位字母/数字，BOXING#R + 6 位字母/数字。"
        } else {
            "已选择：${formatGloveDevice(selected, showAddress = false)}"
        }
    }

    private fun renderSettingsBleDeviceSelection() {
        val group = settingsDeviceRadioGroup ?: return
        group.removeAllViews()
        val devices = visibleSettingsDevices()
        if (settingsSelectedDeviceAddress == null && devices.isNotEmpty()) {
            settingsSelectedDeviceAddress = devices.first().address
        } else if (settingsSelectedDeviceAddress != null && devices.none { it.address == settingsSelectedDeviceAddress }) {
            settingsSelectedDeviceAddress = devices.firstOrNull()?.address
        }
        if (devices.isEmpty()) {
            group.addView(
                TextView(this).apply {
                    text = "未发现 BOXING 设备"
                    setTextColor(Color.parseColor("#D6E9F8"))
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(8))
                },
            )
        } else {
            val connectedNames = connectedStates().mapNotNull { it.deviceName }.toSet()
            devices.forEach { device ->
                val row =
                    RadioButton(this).apply {
                        text = formatGloveDevice(device, showAddress = true)
                        setTextColor(Color.parseColor("#D6E9F8"))
                        textSize = 13f
                        buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD060"))
                        isChecked = device.address == settingsSelectedDeviceAddress || connectedNames.contains(device.name)
                        setPadding(0, dp(4), 0, dp(4))
                        setOnClickListener {
                            settingsSelectedDeviceAddress = device.address
                            renderSettingsBleDeviceSelection()
                            refreshSettings()
                        }
                    }
                group.addView(row)
            }
        }
        settingsSelectedDeviceText?.text = buildSelectedDeviceText()
        updateSettingsBleButtons()
    }

    private fun updateSettingsBleButtons() {
        val connected = connectedStates().isNotEmpty()
        val selected = selectedSettingsDevice()
        val selectedPhase = selected?.let { latestStates[it.hand]?.phase }
        val selectedOnline = selectedPhase in listOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Ready)
        settingsConnectButton?.let {
            it.isEnabled = selected != null && !selectedOnline
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
        settingsDisconnectButton?.let {
            it.isEnabled = connected
            it.alpha = if (connected) 1f else 0.45f
        }
    }

    private fun selectedSettingsDevice(): GloveDevice? =
        latestDevices.firstOrNull { it.address == settingsSelectedDeviceAddress }

    private fun visibleSettingsDevices(): List<GloveDevice> =
        latestDevices.sortedWith(compareBy<GloveDevice>({ it.name.takeLast(6) }, { it.hand.ordinal }, { it.name }, { it.address }))

    private fun connectedStates(): List<GloveConnectionState> =
        listOf(GloveHand.Right, GloveHand.Left).mapNotNull { hand ->
            latestStates[hand]?.takeIf { it.phase in listOf(ConnectionPhase.Connected, ConnectionPhase.Ready) }
        }

    private fun formatConnectedState(state: GloveConnectionState): String =
        "${state.hand.displayName}  ${state.deviceName ?: "--"}  电量 ${state.batteryText}  力度 ${state.gyroPower}"

    private fun formatGloveDevice(
        device: GloveDevice,
        showAddress: Boolean,
    ): String {
        val pairId = device.name.takeLast(6)
        val base = "编号 $pairId  ${device.hand.displayName}  ${device.name}  RSSI ${device.rssi} dBm"
        return if (showAddress) "$base\n${device.address}" else base
    }

    private fun refreshHeaderBleStatus() {
        val connected = connectedStates().isNotEmpty()
        headerBleStatusView?.setConnected(connected)
        headerBleStatusView?.contentDescription = if (connected) "蓝牙已连接" else "蓝牙未连接"
        headerBleLeftBatteryView?.setBattery(
            latestStates[GloveHand.Left]
                ?.takeIf { it.phase in listOf(ConnectionPhase.Connected, ConnectionPhase.Ready) }
                ?.batteryText
                ?.toBatteryPercent(),
        )
        headerBleRightBatteryView?.setBattery(
            latestStates[GloveHand.Right]
                ?.takeIf { it.phase in listOf(ConnectionPhase.Connected, ConnectionPhase.Ready) }
                ?.batteryText
                ?.toBatteryPercent(),
        )
    }

    private fun renderHomeBleStatus() {
        val container = homeBleStatusRow ?: return
        container.removeAllViews()
        listOf(GloveHand.Left, GloveHand.Right).forEachIndexed { index, hand ->
            val state = latestStates[hand] ?: GloveConnectionState(hand)
            container.addView(
                gloveStatusCard(state, compact = true),
                LinearLayout.LayoutParams(0, wrap, 1f).apply {
                    if (index == 0) marginEnd = dp(8)
                },
            )
        }
    }

    private fun renderSettingsBleConnections() {
        val container = settingsBleConnectionRow ?: return
        container.removeAllViews()
        listOf(GloveHand.Left, GloveHand.Right).forEachIndexed { index, hand ->
            val state = latestStates[hand] ?: GloveConnectionState(hand)
            container.addView(
                gloveStatusCard(state, compact = false),
                LinearLayout.LayoutParams(0, wrap, 1f).apply {
                    if (index == 0) marginEnd = dp(8)
                },
            )
        }
    }

    private fun gloveStatusCard(
        state: GloveConnectionState,
        compact: Boolean,
    ): TextView {
        val online = state.phase == ConnectionPhase.Connected || state.phase == ConnectionPhase.Ready
        val scanning = state.phase == ConnectionPhase.Scanning || state.phase == ConnectionPhase.Connecting
        val fill =
            when {
                online -> "#342858"
                scanning -> "#1C1830"
                state.phase == ConnectionPhase.Error -> "#3B1111"
                else -> NIGHT_CARD
            }
        val stroke =
            when {
                online -> GOLD
                scanning -> ORANGE
                state.phase == ConnectionPhase.Error -> "#FB7185"
                else -> GOLD_LINE
            }
        val title = "${state.hand.displayName}手套"
        val device = state.deviceName ?: "未选择设备"
        val cardText =
            if (compact) {
                "$title  ${state.phase.label()}\n电量 ${state.batteryText} · 力度 ${state.gyroPower}"
            } else {
                "$title  ${state.phase.label()}\n$device\n电量 ${state.batteryText} · 次数 ${state.gyroCount} · 力度 ${state.gyroPower}"
            }
        return TextView(this).apply {
            text = cardText
            setTextColor(Color.WHITE)
            textSize = if (compact) 13f else 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(fill, stroke, 16)
            includeFontPadding = true
        }
    }

    private fun renderDeviceList(container: LinearLayout?) {
        container ?: return
        container.removeAllViews()
        if (latestDevices.isEmpty()) {
            container.addView(infoCard("未发现手套。点击“扫描手套”后，请确认设备名为 BOXING#L 加 6 位字母/数字，或 BOXING#R 加 6 位字母/数字。"))
            return
        }
        latestDevices.forEach { device ->
            val state = latestStates[device.hand]
            val selected =
                state?.deviceName == device.name &&
                    state.phase in listOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Ready)
            val label =
                if (selected) {
                    "已选择 ${device.hand.displayName} · ${device.name}\nRSSI ${device.rssi} · ${state?.phase?.label() ?: "--"} · 点按可重连"
                } else {
                    "连接${device.hand.displayName} · ${device.name}\nRSSI ${device.rssi} · ${device.address}"
                }
            container.addView(
                actionButton(label, if (selected) "#6A2800" else "#342858") {
                    bleManager.connect(device)
                },
                LinearLayout.LayoutParams(match, dp(64)).withBottom(dp(8)),
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

    private fun LinearLayout.addCategoryButtons(
        input: EditText,
        onChanged: () -> Unit = {},
    ) {
        addView(row(
            actionButton("自然风光", GOLD) {
                input.setText("自然风光")
                onChanged()
            },
            actionButton("名画再现", "#7C3AED") {
                input.setText("名画再现")
                onChanged()
            },
        ))
        addView(row(
            actionButton("城市建筑", "#A04800") {
                input.setText("城市建筑")
                onChanged()
            },
            actionButton("抽象艺术", "#EA580C") {
                input.setText("抽象艺术")
                onChanged()
            },
        ))
        addView(row(
            actionButton("萌宠", "#DB2777") {
                input.setText("萌宠")
                onChanged()
            },
            actionButton("科幻", "#0891B2") {
                input.setText("科幻")
                onChanged()
            },
        ))
    }

    private fun LinearLayout.addDifficultyButtons(onChanged: () -> Unit = {}) {
        addView(row(
            actionButton("简单 150", "#A04800") {
                currentDifficulty = GameDifficulty.Easy
                refreshHome()
                onChanged()
            },
            actionButton("标准 300", GOLD) {
                currentDifficulty = GameDifficulty.Standard
                refreshHome()
                onChanged()
            },
            actionButton("挑战 500", "#B45309") {
                currentDifficulty = GameDifficulty.Challenge
                refreshHome()
                onChanged()
            },
        ))
    }

    private fun input(
        hintText: String,
        minLines: Int = 1,
    ): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor(MUTED_TEXT))
            setTextColor(Color.WHITE)
            setSingleLine(minLines == 1)
            this.minLines = minLines
            background = rounded(NIGHT_CARD, GOLD_LINE, 14)
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
            setTextColor(Color.parseColor(GOLD))
            setPadding(0, dp(12), 0, dp(8))
        }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor(MUTED_TEXT))
            setPadding(0, dp(10), 0, dp(6))
        }

    private fun infoCard(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#FFF8C0"))
            setLineSpacing(2f, 1.05f)
            background = rounded(NIGHT_CARD, GOLD_LINE, 14)
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
            setTextColor(Color.parseColor(if (color.uppercase(Locale.US) in setOf(GOLD, "#FFD060", "#FFE080", "#FFCC00")) NIGHT_BG else "#FFFFFF"))
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
            GOLD -> "#FFE080"
            "#7C3AED" -> "#A78BFA"
            ORANGE -> "#FFC840"
            "#A04800" -> "#E08800"
            "#B45309" -> "#F59E0B"
            "#EA580C" -> "#FB923C"
            "#DB2777" -> "#F472B6"
            "#0891B2" -> "#22D3EE"
            "#475569" -> "#64748B"
            "#342858" -> "#FFD060"
            "#6A2800" -> "#FF8C00"
            "#174154" -> "#2E7491"
            "#E07010" -> "#F6A03D"
            "#A73A54" -> "#E35F78"
            else -> ORANGE
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
        const val NIGHT_BG = "#0A0810"
        const val NIGHT_PANEL = "#12101E"
        const val NIGHT_CARD = "#1C1830"
        const val GOLD = "#FFD060"
        const val ORANGE = "#FF8C00"
        const val GOLD_LINE = "#2C2848"
        const val MUTED_TEXT = "#8880AA"
    }
}

private fun String.toBatteryPercent(): Int? =
    when {
        this == "已充满" -> 100
        endsWith("%") -> removeSuffix("%").toIntOrNull()?.coerceIn(0, 100)
        else -> null
    }

private class BluetoothStatusIndicatorView(
    context: android.content.Context,
) : View(context) {
    private var connected: Boolean = false
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val path = Path()

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val top = (height - size) / 2f + size * 0.17f
        val bottom = (height + size) / 2f - size * 0.17f
        val mid = height / 2f
        val left = cx - size * 0.22f
        val right = cx + size * 0.21f
        paint.color = if (connected) Color.parseColor("#FFD060") else Color.parseColor("#FF4B55")
        paint.strokeWidth = max(2f, size * 0.08f)
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx, bottom)
        path.moveTo(cx, top)
        path.lineTo(right, mid - size * 0.12f)
        path.lineTo(left, mid)
        path.lineTo(right, mid + size * 0.12f)
        path.lineTo(cx, bottom)
        canvas.drawPath(path, paint)
    }
}

private class BatteryStatusIndicatorView(
    context: android.content.Context,
    private var handLabel: String,
) : View(context) {
    private var percent: Int? = null
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    private val bodyRect = RectF()
    private val fillRect = RectF()
    private val terminalRect = RectF()

    fun setBattery(value: Int?) {
        percent = value?.coerceIn(0, 100)
        contentDescription = "$handLabel ${percent?.let { "$it%" } ?: "--"}"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bodyLeft = width * 0.05f
        val bodyTop = height * 0.18f
        val bodyRight = width * 0.88f
        val bodyBottom = height * 0.82f
        bodyRect.set(bodyLeft, bodyTop, bodyRight, bodyBottom)
        terminalRect.set(bodyRight + width * 0.015f, height * 0.36f, width * 0.98f, height * 0.64f)

        val level = percent
        val outlineColor =
            when {
                level == null -> Color.parseColor("#8A97A3")
                level <= 20 -> Color.parseColor("#FF4B55")
                level <= 45 -> Color.parseColor("#FFD060")
                else -> Color.parseColor("#FFD060")
            }
        strokePaint.color = outlineColor
        fillPaint.color = Color.argb(44, Color.red(outlineColor), Color.green(outlineColor), Color.blue(outlineColor))
        canvas.drawRoundRect(bodyRect, height * 0.11f, height * 0.11f, fillPaint)
        canvas.drawRoundRect(bodyRect, height * 0.11f, height * 0.11f, strokePaint)
        fillPaint.color = outlineColor
        canvas.drawRoundRect(terminalRect, height * 0.04f, height * 0.04f, fillPaint)

        if (level != null) {
            val innerPadding = height * 0.14f
            val fillWidth = (bodyRect.width() - innerPadding * 2f) * (level / 100f)
            fillRect.set(
                bodyRect.left + innerPadding,
                bodyRect.top + innerPadding,
                bodyRect.left + innerPadding + fillWidth,
                bodyRect.bottom - innerPadding,
            )
            fillPaint.color = Color.argb(92, Color.red(outlineColor), Color.green(outlineColor), Color.blue(outlineColor))
            canvas.drawRoundRect(fillRect, height * 0.06f, height * 0.06f, fillPaint)
        }

        textPaint.textSize = height * 0.36f
        val numberText = level?.toString() ?: "--"
        val display = "$handLabel $numberText"
        val baseline = bodyRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(display, bodyRect.centerX(), baseline, textPaint)
    }
}
