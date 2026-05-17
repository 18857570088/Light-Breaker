package com.zclei.lightbreaker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
import com.zclei.lightbreaker.game.GameSnapshot
import com.zclei.lightbreaker.game.LightBreakerGameEngine
import com.zclei.lightbreaker.game.LightBreakerGameView
import com.zclei.lightbreaker.hit.HitEvent
import com.zclei.lightbreaker.mural.GeneratedMural
import com.zclei.lightbreaker.mural.MockMuralGenerationApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var repository: LightBreakerRepository
    private lateinit var bleManager: BleGloveManager
    private val muralApi = MockMuralGenerationApi()
    private val engine = LightBreakerGameEngine()

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private var currentPage = Page.Home

    private var currentMural: GeneratedMural? = null
    private var currentSnapshot: GameSnapshot? = null
    private var trainingActive = false
    private var trainingStartedAtMs = 0L
    private var simulationCount = 0

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
        lifecycleScope.launch { generateMural(prompt = "击碎压力，露出治愈光影", theme = "治愈光影", silent = true) }
    }

    override fun onDestroy() {
        bleManager.disconnectAll()
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
                    text = "光影破壁者"
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
                    addView(tabButton("蓝牙调试") { showDebug() })
                    addView(tabButton("我的画廊") { showGallery() })
                },
            )
        }

    private fun tabButton(
        text: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded("#17233A", "#2C3E60", 16)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        }

    private fun showHome() {
        currentPage = Page.Home
        content.removeAllViews()
        val promptInput =
            EditText(this).apply {
                hint = "输入想击碎的压力或画作关键词"
                setHintTextColor(Color.parseColor("#687899"))
                setTextColor(Color.WHITE)
                setSingleLine(false)
                minLines = 2
                background = rounded("#101A2E", "#263B5F", 14)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
        val themeInput =
            EditText(this).apply {
                hint = "主题，例如：护士节、城市夜景、森林萤火"
                setHintTextColor(Color.parseColor("#687899"))
                setTextColor(Color.WHITE)
                setSingleLine(true)
                background = rounded("#101A2E", "#263B5F", 14)
                setPadding(dp(12), 0, dp(12), 0)
            }
        content.addView(
            scroll {
                addView(sectionTitle("训练入口"))
                val progressCard = infoCard("")
                homeProgressText = progressCard
                addView(progressCard)
                val deviceCard = infoCard("")
                homeDeviceText = deviceCard
                addView(deviceCard)
                val muralCard = infoCard("")
                homeMuralText = muralCard
                addView(muralCard)
                addView(label("Mock 云端画作"))
                addView(promptInput, LinearLayout.LayoutParams(match, wrap).withBottom(dp(8)))
                addView(themeInput, LinearLayout.LayoutParams(match, dp(48)).withBottom(dp(10)))
                addView(row(
                    actionButton("生成画作", "#2563EB") {
                        lifecycleScope.launch {
                            generateMural(promptInput.text.toString(), themeInput.text.toString())
                        }
                    },
                    actionButton("开始训练", "#16A34A") { startTraining() },
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

    private fun showDebug() {
        currentPage = Page.Debug
        content.removeAllViews()
        content.addView(
            scroll {
                addView(sectionTitle("蓝牙调试"))
                val statusCard = infoCard("")
                debugStatusText = statusCard
                addView(statusCard)
                addView(row(
                    actionButton("扫描", "#2563EB") { bleManager.startScan() },
                    actionButton("断开全部", "#B45309") { bleManager.disconnectAll() },
                ))
                addView(sectionTitle("设备列表"))
                debugDeviceList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                addView(debugDeviceList)
                addView(sectionTitle("原始通知日志"))
                val logCard = infoCard("")
                debugLogText = logCard
                addView(logCard)
            },
        )
        refreshDebug()
        renderDeviceList(debugDeviceList)
    }

    private fun showGallery() {
        currentPage = Page.Gallery
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

    private fun startTraining() {
        val mural = currentMural
        if (mural == null) {
            toast("请先生成一幅画作")
            return
        }
        trainingActive = true
        trainingStartedAtMs = System.currentTimeMillis()
        simulationCount = 0
        currentSnapshot = engine.start(mural)

        content.removeAllViews()
        val screen =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                setBackgroundColor(Color.parseColor("#070A18"))
            }
        gameStatsText = infoCard("")
        screen.addView(gameStatsText)
        gameView = LightBreakerGameView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setGameState(mural, currentSnapshot)
        }
        screen.addView(gameView, LinearLayout.LayoutParams(match, 0, 1f))
        screen.addView(row(
            actionButton("模拟左拳", "#2563EB") { simulateHit(GloveHand.Left) },
            actionButton("模拟右拳", "#EA580C") { simulateHit(GloveHand.Right) },
        ))
        screen.addView(row(
            actionButton("完成/结算", "#16A34A") { finishTraining(manual = true) },
            actionButton("返回首页", "#475569") {
                trainingActive = false
                showHome()
            },
        ))
        content.addView(screen)
        refreshGameStats()
    }

    private fun applyHit(hit: HitEvent) {
        if (!trainingActive) return
        currentSnapshot = engine.registerHit(hit)
        gameView?.setGameState(currentMural, currentSnapshot)
        refreshGameStats()
        if (currentSnapshot?.completed == true) {
            finishTraining(manual = false)
        }
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
        lifecycleScope.launch {
            val result = repository.saveSession(mural, snapshot, trainingStartedAtMs, endedAt)
            showResult(snapshot, result.xpGain, saved = result.gallerySaved, manual = manual)
        }
    }

    private fun showResult(
        snapshot: GameSnapshot,
        xpGain: Int,
        saved: Boolean,
        manual: Boolean,
    ) {
        content.removeAllViews()
        content.addView(
            scroll {
                addView(sectionTitle(if (snapshot.completed) "画作完整揭开" else "本轮训练结算"))
                addView(
                    infoCard(
                        buildString {
                            appendLine(currentMural?.title.orEmpty())
                            appendLine("进度 ${snapshot.progressPercent.roundToInt()}% · ${snapshot.openedTiles}/${snapshot.totalTiles} 块")
                            appendLine("总出拳 ${snapshot.totalHits} · 左手 ${snapshot.leftHits} · 右手 ${snapshot.rightHits}")
                            appendLine("最高连击 x${snapshot.maxCombo} · ${"%.1f".format(snapshot.calories)} kcal")
                            append(if (saved) "已进入我的画廊 · XP +$xpGain" else "训练记录已保存 · 未完成作品不进入画廊 · XP +$xpGain")
                            if (manual) append(" · 手动结算")
                        },
                    ),
                )
                addView(row(
                    actionButton("再来一幅", "#2563EB") {
                        lifecycleScope.launch { generateMural("新的破壁挑战", "治愈光影") }
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
        if (!silent) toast("正在生成 Mock 云端画作")
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
                if (left != null || right != null) {
                    repository.rememberDevice(left, right)
                }
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
            bleManager.hits.collect { hit ->
                applyHit(hit)
            }
        }
    }

    private fun refreshHome() {
        homeProgressText?.text = "等级 Lv.${progressStats.level} · XP ${progressStats.xp}\n最近设备：左 ${progressStats.lastLeftDevice ?: "--"} · 右 ${progressStats.lastRightDevice ?: "--"}"
        homeDeviceText?.text = buildDeviceStatusText()
        homeMuralText?.text =
            currentMural?.let { "当前画作：${it.title}\n主题：${it.theme}\n提示词：${it.prompt}" }
                ?: "当前画作：尚未生成"
    }

    private fun refreshDebug() {
        debugStatusText?.text = buildDeviceStatusText()
        debugLogText?.text = debugLines.joinToString("\n").ifBlank { "暂无通知包。连接手套并开启陀螺仪后，这里会显示 D5 5D 03 数据包。" }
    }

    private fun refreshGameStats() {
        val snap = currentSnapshot ?: return
        gameStatsText?.text =
            "进度 ${snap.progressPercent.roundToInt()}% · ${snap.openedTiles}/${snap.totalTiles} 块\n" +
                "总出拳 ${snap.totalHits} · 左 ${snap.leftHits} · 右 ${snap.rightHits} · 连击 x${snap.combo} · 最高 x${snap.maxCombo}\n" +
                "热量 ${"%.1f".format(snap.calories)} kcal"
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

    private fun requestRuntimePermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
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
            textSize = 14f
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

    private fun LinearLayout.LayoutParams.withBottom(bottom: Int): LinearLayout.LayoutParams =
        apply { bottomMargin = bottom }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

    private enum class Page {
        Home,
        Debug,
        Gallery,
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1102
        const val match = ViewGroup.LayoutParams.MATCH_PARENT
        const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
