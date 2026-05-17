package com.zclei.lightbreaker.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zclei.lightbreaker.ble.GloveHand
import com.zclei.lightbreaker.mural.GeneratedMural
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class LightBreakerGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tileRect = RectF()
    private val boardRect = RectF()
    private val imageSrc = Rect()
    private var mural: GeneratedMural? = null
    private var snapshot: GameSnapshot? = null
    private var muralBitmap: Bitmap? = null
    private var loadedImageUrl: String? = null
    private var loadingImageUrl: String? = null

    fun setGameState(
        mural: GeneratedMural?,
        snapshot: GameSnapshot?,
    ) {
        this.mural = mural
        this.snapshot = snapshot
        loadMuralBitmapIfNeeded(mural?.imageUrl)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#070A18")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val snap = snapshot ?: return drawEmpty(canvas)
        val columns = 16
        val rows = 10
        val boardW = width - paddingLeft - paddingRight - 24f
        val boardH = height - paddingTop - paddingBottom - 24f
        val byWidth = boardW
        val byHeight = boardH * columns / rows
        val finalW = min(byWidth, byHeight)
        val finalH = finalW * rows / columns
        val left = (width - finalW) / 2f
        val top = (height - finalH) / 2f
        boardRect.set(left, top, left + finalW, top + finalH)

        canvas.save()
        canvas.clipRect(boardRect)
        drawMural(canvas, boardRect, mural, snap.progressPercent)
        drawTiles(canvas, snap, columns, rows)
        canvas.restore()

        strokePaint.color = Color.parseColor("#38557A")
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(boardRect, 18f, 18f, strokePaint)
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.color = Color.parseColor("#94A3B8")
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        canvas.drawText("生成画作后开始破壁", width / 2f, height / 2f, paint)
    }

    private fun drawMural(
        canvas: Canvas,
        rect: RectF,
        mural: GeneratedMural?,
        progress: Float,
    ) {
        val bitmap = muralBitmap
        if (bitmap != null) {
            drawBitmapCenterCrop(canvas, bitmap, rect)
            if (progress >= 50f) {
                drawLightSweep(canvas, rect, progress)
            }
            return
        }

        val seed = mural?.seed ?: 8
        val colors = palette(seed)
        paint.shader =
            LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                colors[0],
                colors[1],
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(rect, paint)
        paint.shader = null

        val random = Random(seed)
        repeat(18) { i ->
            paint.color = colors[(i + 2) % colors.size]
            paint.alpha = 110
            val cx = rect.left + random.nextFloat() * rect.width()
            val cy = rect.top + random.nextFloat() * rect.height()
            val radius = rect.width() * (0.04f + random.nextFloat() * 0.08f)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.alpha = 255

        drawMuralLandmarks(canvas, rect, seed, colors)
        if (progress >= 50f) {
            drawLightSweep(canvas, rect, progress)
        }
    }

    private fun drawBitmapCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
    ) {
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val srcW = (rect.width() / scale).toInt().coerceAtMost(bitmap.width)
        val srcH = (rect.height() / scale).toInt().coerceAtMost(bitmap.height)
        val srcLeft = ((bitmap.width - srcW) / 2).coerceAtLeast(0)
        val srcTop = ((bitmap.height - srcH) / 2).coerceAtLeast(0)
        imageSrc.set(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH)
        canvas.drawBitmap(bitmap, imageSrc, rect, bitmapPaint)
    }

    private fun loadMuralBitmapIfNeeded(imageUrl: String?) {
        if (imageUrl.isNullOrBlank()) {
            muralBitmap = null
            loadedImageUrl = null
            loadingImageUrl = null
            return
        }
        if (imageUrl == loadedImageUrl || imageUrl == loadingImageUrl) return
        muralBitmap = null
        loadingImageUrl = imageUrl
        Thread {
            val bitmap =
                runCatching {
                    val connection = URL(imageUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "LightBreakerAndroid/1.0")
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            post {
                if (loadingImageUrl == imageUrl) {
                    muralBitmap = bitmap
                    loadedImageUrl = if (bitmap != null) imageUrl else null
                    loadingImageUrl = null
                    invalidate()
                }
            }
        }.start()
    }

    private fun drawMuralLandmarks(
        canvas: Canvas,
        rect: RectF,
        seed: Int,
        colors: IntArray,
    ) {
        val random = Random(seed * 31)
        val path = Path()
        path.moveTo(rect.left, rect.bottom)
        val steps = 7
        repeat(steps + 1) { i ->
            val x = rect.left + rect.width() * i / steps
            val y = rect.top + rect.height() * (0.35f + random.nextFloat() * 0.35f)
            path.lineTo(x, y)
        }
        path.lineTo(rect.right, rect.bottom)
        path.close()
        paint.color = colors[2]
        paint.alpha = 210
        canvas.drawPath(path, paint)
        paint.alpha = 255

        repeat(9) { i ->
            val towerW = rect.width() * (0.035f + random.nextFloat() * 0.04f)
            val towerH = rect.height() * (0.14f + random.nextFloat() * 0.26f)
            val left = rect.left + rect.width() * i / 9f + random.nextFloat() * 18f
            paint.color = colors[(i + 3) % colors.size]
            canvas.drawRect(left, rect.bottom - towerH, left + towerW, rect.bottom, paint)
        }
    }

    private fun drawLightSweep(
        canvas: Canvas,
        rect: RectF,
        progress: Float,
    ) {
        val x = rect.left + rect.width() * ((progress % 100f) / 100f)
        paint.shader =
            LinearGradient(
                x - 80f,
                rect.top,
                x + 80f,
                rect.bottom,
                Color.TRANSPARENT,
                Color.argb(120, 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawTiles(
        canvas: Canvas,
        snap: GameSnapshot,
        columns: Int,
        rows: Int,
    ) {
        val tileW = boardRect.width() / columns
        val tileH = boardRect.height() / rows
        snap.tiles.forEach { tile ->
            val left = boardRect.left + tile.col * tileW
            val top = boardRect.top + tile.row * tileH
            tileRect.set(left + 1f, top + 1f, left + tileW - 1f, top + tileH - 1f)
            if (!tile.opened) {
                drawClosedTile(canvas, tile)
            } else {
                drawOwnerGlow(canvas, tile)
            }
        }
    }

    private fun drawClosedTile(
        canvas: Canvas,
        tile: TileSnapshot,
    ) {
        val base =
            when (tile.kind) {
                TileKind.Edge -> "#263348"
                TileKind.Normal -> "#334155"
                TileKind.Core -> "#4C3F5F"
                TileKind.Bonus -> "#715A1F"
            }
        paint.color = Color.parseColor(base)
        canvas.drawRoundRect(tileRect, 7f, 7f, paint)
        paint.color = Color.argb(55, 255, 255, 255)
        canvas.drawRect(tileRect.left, tileRect.top, tileRect.right, tileRect.top + tileRect.height() * 0.28f, paint)
        strokePaint.color = Color.argb(130, 7, 10, 24)
        strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(tileRect, 7f, 7f, strokePaint)
        if (tile.hp > 1) {
            paint.color = Color.argb(180, 255, 255, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 18f
            canvas.drawText(tile.hp.toString(), tileRect.centerX(), tileRect.centerY() + 6f, paint)
        }
    }

    private fun drawOwnerGlow(
        canvas: Canvas,
        tile: TileSnapshot,
    ) {
        val color =
            when (tile.owner) {
                GloveHand.Left -> Color.parseColor("#3B82F6")
                GloveHand.Right -> Color.parseColor("#F97316")
                else -> Color.parseColor("#22C55E")
            }
        if (snapshot?.lastOpenedIndexes?.contains(tile.index) == true) {
            paint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(tileRect, paint)
        }
        strokePaint.color = Color.argb(160, Color.red(color), Color.green(color), Color.blue(color))
        strokePaint.strokeWidth = if (snapshot?.lastOpenedIndexes?.contains(tile.index) == true) 3f else 1.4f
        canvas.drawRect(tileRect, strokePaint)
    }

    private fun palette(seed: Int): IntArray {
        val palettes =
            arrayOf(
                intArrayOf(0xFF09111F.toInt(), 0xFF1D4ED8.toInt(), 0xFF0F766E.toInt(), 0xFFF59E0B.toInt(), 0xFFE879F9.toInt()),
                intArrayOf(0xFF102A43.toInt(), 0xFF38BDF8.toInt(), 0xFF14532D.toInt(), 0xFFFACC15.toInt(), 0xFFFB7185.toInt()),
                intArrayOf(0xFF111827.toInt(), 0xFF7C3AED.toInt(), 0xFF0EA5E9.toInt(), 0xFF22C55E.toInt(), 0xFFF97316.toInt()),
                intArrayOf(0xFF06281F.toInt(), 0xFF14B8A6.toInt(), 0xFF2563EB.toInt(), 0xFFFDE047.toInt(), 0xFFF43F5E.toInt()),
            )
        return palettes[seed % palettes.size]
    }
}
