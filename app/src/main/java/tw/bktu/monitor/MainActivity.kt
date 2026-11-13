package tw.bktu.monitor

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var bar: LinearLayout
    private lateinit var grid: FrameLayout
    private lateinit var layoutBtn: TextView
    private val layoutButtons = LinkedHashMap<String, TextView>()
    private lateinit var barClock: TextView
    private lateinit var clockTile: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN)

    private val tiles = LinkedHashMap<String, Tile>()
    private var order = mutableListOf<String>()
    private var quadOrder = mutableListOf<String>()   // 四格有自己的排序，不受 1 大 4 小影響
    private var evenOrder = mutableListOf<String>()   // 均分也有自己的排序
    private var layoutMode = "focus"
    private var fullId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastW = 0

    // ---- 遙控器（Google TV）----
    private val isTv: Boolean by lazy {
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    private var focusId: String? = null      // 遙控器目前選到的畫面
    private var showFocus = false           // 按過方向鍵後才顯示選取框（手機觸控時不顯示）
    private var barIndex = -1               // >=0 代表選到上方工具列的第幾個按鈕
    private val barButtons = ArrayList<TextView>()
    private var okDownTime = 0L
    private var longOkFired = false
    private val longOk = Runnable { longOkFired = true; onOk(true) }
    private var lastAudioId: String? = null
    /** 遙控器 5 秒沒操作就收起選取框 */
    private val focusIdle = Runnable { showFocus = false; barIndex = -1; refreshFocusUi() }
    private fun bumpIdle() { handler.removeCallbacks(focusIdle); handler.postDelayed(focusIdle, 5000) }
    private val softSmall: Boolean get() = config.softSmall ?: isTv
    private var lastH = 0

    // ============================ 生命週期 ============================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        config = Config(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0F12"))
        }
        bar = buildBar()
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isTv) 56 else 46)))
        grid = FrameLayout(this)
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        clockTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#13161A"))
                setStroke(dp(1), Color.parseColor("#222222"))
                cornerRadius = dp(6).toFloat()
            }
            visibility = View.GONE
        }
        clockTime = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E6E6")); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        clockDate = TextView(this).apply {
            setTextColor(Color.parseColor("#8B949E")); gravity = Gravity.CENTER
        }
        clockTile.addView(clockTime)
        clockTile.addView(clockDate)
        setContentView(root)

        grid.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != lastW || h != lastH) {
                lastW = w; lastH = h
                grid.post { relayout() }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullId != null -> setFull(null)
                    layoutMode == "quad" -> setLayoutMode("focus")   // 四格按返回 → 1 大 4 小
                    else -> finish()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
        if (!config.isReady) {
            startActivity(Intent(this, SettingsActivity::class.java))
            Toast.makeText(this, "請先設定監視器帳號密碼", Toast.LENGTH_LONG).show()
            return
        }
        buildTiles()
        handler.postDelayed(watchdog, 3000)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        tiles.values.forEach { it.release() }
        tiles.clear()
        grid.removeAllViews()
    }

    // ============================ 上方工具列 ============================

    private fun buildBar(): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#16191E"))
            setPadding(dp(10), 0, dp(6), 0)
        }
        barClock = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E6E6"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        b.addView(barClock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 版面按鈕分開：目前的版面用藍色標示；「四格」放第一個，從畫面按「上」直接選到
        for ((mode, label) in listOf("quad" to "四格", "focus" to "1大4小", "even" to "均分")) {
            val btn = barButton(label) { setLayoutMode(mode) }
            layoutButtons[mode] = btn
            b.addView(btn)
        }
        layoutBtn = layoutButtons["focus"]!!
        b.addView(barButton(if (isTv) "全部靜音" else "靜音") { tiles.values.forEach { it.setAudio(it.volume, true) } })
        b.addView(barButton(if (isTv) "重新連線" else "重連") { tiles.values.forEach { connect(it) } })
        b.addView(barButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        return b
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#DDDDDD"))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#262A31"))
            cornerRadius = dp(6).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
        isFocusable = false
        if (isTv) textSize = 16f
        barButtons.add(this)
    }

    // ============================ 畫面格 ============================

    private fun buildTiles() {
        grid.removeAllViews()
        tiles.clear()
        val cams = config.cams
        val ids = cams.map { it.id }
        order = config.order.filter { it in ids }.toMutableList()
        ids.filter { it !in order }.forEach { order.add(it) }
        quadOrder = config.quadOrder.filter { it in ids }.distinct().toMutableList()
        for (id in order) if (quadOrder.size < minOf(4, ids.size) && id !in quadOrder) quadOrder.add(id)
        evenOrder = config.evenOrder.filter { it in ids }.distinct().toMutableList()
        for (id in order) if (id !in evenOrder) evenOrder.add(id)
        // 開啟時的版面：設定裡選（預設 1 大 4 小；也可以選「上次使用」）
        layoutMode = when (val st = config.startLayout) {
            "last" -> config.layout.takeIf { it in listOf("focus", "even", "quad") } ?: "focus"
            else -> st
        }
        lastAudioId = null
        fullId = null
        if (focusId == null || focusId !in ids) focusId = order.firstOrNull()
        showFocus = isTv
        if (isTv) bumpIdle()
        updateBar()

        cams.forEach { cam ->
            val t = Tile(cam)
            tiles[cam.id] = t
            grid.addView(t.view, FrameLayout.LayoutParams(1, 1))
        }
        grid.addView(clockTile, FrameLayout.LayoutParams(1, 1))
        relayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class Tile(val cam: Cam) {
        val view = FrameLayout(this@MainActivity)
        val playerView = PlayerView(this@MainActivity)
        val label = LinearLayout(this@MainActivity)
        val numTv = TextView(this@MainActivity)
        val dot = View(this@MainActivity)
        val muteBtn = TextView(this@MainActivity)
        val statusTv = TextView(this@MainActivity)
        val dropOverlay = TextView(this@MainActivity)

        var player: ExoPlayer? = null
        var hd: Boolean? = null
        var volume = config.volume(cam.id)
        var muted = config.muted(cam.id)
        var lastPos = -1L
        var lastProgress = SystemClock.elapsedRealtime()
        var retry: Runnable? = null
        var started = false          // 播放器是否已經連過線（換畫質時要重建）
        var paused = false           // 四格模式中被隱藏、暫停播放

        init {
            view.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(6).toFloat()
            }
            // 邊框畫在最上層（foreground），影像填滿時才不會蓋住選取框
            view.foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#222222"))
                cornerRadius = dp(6).toFloat()
            }
            view.clipToOutline = true

            playerView.useController = false
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView.setShutterBackgroundColor(Color.BLACK)
            playerView.setKeepContentOnPlayerReset(true)   // 換畫質時保留最後一格畫面，不黑屏
            view.addView(playerView, FrameLayout.LayoutParams(-1, -1))

            statusTv.apply {
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 13f
                gravity = Gravity.CENTER
                text = "連線中…"
            }
            view.addView(statusTv, FrameLayout.LayoutParams(-1, -1))

            // 透明觸控層：雙擊全螢幕
            val touch = View(this@MainActivity)
            val detector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    setFull(if (fullId == cam.id) null else cam.id); return true
                }
                // 名稱列關閉時，直接在畫面上操作
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (fullId != null) return true
                    if (layoutMode != "quad") makeMain(cam.id)
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    if (fullId != null) return
                    if (layoutMode == "quad" || layoutMode == "even") { pickCamera(cam.id); return }
                    val data = ClipData.newPlainText("cam", cam.id)
                    view.startDragAndDrop(data, View.DragShadowBuilder(view), cam.id, 0)
                    view.alpha = 0.4f
                }
            })
            touch.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
            view.addView(touch, FrameLayout.LayoutParams(-1, -1))

            // 名稱列
            label.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                // 名稱列放在下方，避免遮到監視器左上角的時間戳記
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
                )
            }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E5484D"))
            }
            label.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            val nameTv = TextView(this@MainActivity).apply {
                text = cam.name; setTextColor(Color.WHITE); textSize = 13f
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
                maxLines = 1
            }
            label.addView(nameTv)
            numTv.apply { setTextColor(Color.parseColor("#8B949E")); textSize = 12f }
            label.addView(numTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            if (isTv) { nameTv.textSize = 17f; numTv.textSize = 15f }
            muteBtn.apply {
                textSize = 16f; gravity = Gravity.CENTER
                isFocusable = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    setAudio(if (volume <= 0f) 0.8f else volume, !muted)
                }
                setOnLongClickListener { showVolumeDialog(this@Tile); true }
            }
            label.addView(muteBtn)
            val fsBtn = TextView(this@MainActivity).apply {
                text = "⛶"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { setFull(if (fullId == cam.id) null else cam.id) }
            }
            label.addView(fsBtn)
            label.setOnClickListener { makeMain(cam.id) }
            label.setOnLongClickListener {
                // 長按名稱列開始拖曳排序
                val data = ClipData.newPlainText("cam", cam.id)
                label.startDragAndDrop(data, View.DragShadowBuilder(label), cam.id, 0)
                view.alpha = 0.4f
                true
            }
            view.addView(label, FrameLayout.LayoutParams(-1, dp(if (isTv) 44 else 38), Gravity.BOTTOM))
            // 名稱（燈號、名稱、編號、全螢幕鈕）與音量圖示可分開開關
            val names = config.showLabels
            val vol = config.showVolume
            label.visibility = if (names || vol) View.VISIBLE else View.GONE
            val nv = if (names) View.VISIBLE else View.GONE
            dot.visibility = nv; nameTv.visibility = nv; numTv.visibility = nv; fsBtn.visibility = nv
            if (!names) {
                // 只剩音量圖示時，把它推到右邊
                numTv.visibility = View.INVISIBLE
                numTv.layoutParams = (numTv.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
            }
            muteBtn.visibility = if (vol) View.VISIBLE else View.GONE
            if (!names) label.background = null

            // 拖曳目標提示
            dropOverlay.apply {
                text = "放到這裡"
                setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(90, 47, 111, 237))
                visibility = View.GONE
            }
            view.addView(dropOverlay, FrameLayout.LayoutParams(-1, -1))

            view.setOnDragListener { _, ev ->
                val from = ev.localState as? String
                when (ev.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (from != null && from != cam.id) dropOverlay.visibility = View.VISIBLE; true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> { dropOverlay.visibility = View.GONE; true }
                    DragEvent.ACTION_DROP -> {
                        dropOverlay.visibility = View.GONE
                        if (from != null && from != cam.id) moveTo(from, cam.id)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        dropOverlay.visibility = View.GONE; view.alpha = 1f; true
                    }
                    else -> true
                }
            }

            createPlayer()
            applyAudio()
        }

        fun createPlayer() {
            val load = DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 250, 500)   // 低延遲
                .build()
            // 小畫面優先用軟體解碼，把硬體解碼器留給大畫面（電視的硬體解碼器數量很少）
            val selector = MediaCodecSelector { mime, secure, tunneling ->
                val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (mime.startsWith("video/") && hd == false && softSmall) list.sortedBy { if (it.softwareOnly) 0 else 1 }
                else list
            }
            val rf = DefaultRenderersFactory(this@MainActivity)
                .setEnableDecoderFallback(true)      // 硬體解碼器不夠時自動改用其他解碼器
                .setMediaCodecSelector(selector)
            val p = ExoPlayer.Builder(this@MainActivity, rf).setLoadControl(load).build()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> setPlaying(true)
                        Player.STATE_ENDED -> scheduleRetry(this@Tile, "串流中斷，重新連線…")
                        else -> {}
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val why = error.errorCodeName.removePrefix("ERROR_CODE_")
                    scheduleRetry(this@Tile, "連線失敗（$why）\n5 秒後重試…")
                }
            })
            playerView.player = p
            player = p
        }

        /** 換畫質時重建播放器：避免硬體／軟體解碼器切換時殘留狀態而出錯 */
        fun recreatePlayer() {
            retry?.let { handler.removeCallbacks(it) }
            retry = null
            playerView.player = null
            player?.release()
            createPlayer()
            applyAudio()
            started = false
        }

        fun setPlaying(on: Boolean) {
            statusTv.visibility = if (on) View.GONE else View.VISIBLE
            (dot.background as GradientDrawable).setColor(
                Color.parseColor(if (on) "#30A46C" else "#E5484D")
            )
        }

        fun setStatus(text: String) {
            statusTv.text = text
            setPlaying(false)
        }

        fun applyAudio() {
            player?.volume = if (muted) 0f else volume
            muteBtn.text = when {
                muted || volume <= 0f -> "🔇"
                volume < 0.5f -> "🔉"
                else -> "🔊"
            }
        }

        fun setAudio(vol: Float, mute: Boolean) {
            volume = vol
            muted = mute || vol <= 0f
            config.setAudio(cam.id, volume, muted)
            applyAudio()
        }

        fun release() {
            retry?.let { handler.removeCallbacks(it) }
            playerView.player = null
            player?.release()
            player = null
        }
    }

    private fun showVolumeDialog(t: Tile) {
        val seek = SeekBar(this).apply {
            max = 100
            progress = (t.volume * 100).toInt()
            setPadding(dp(24), dp(24), dp(24), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) t.setAudio(value / 100f, false)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        AlertDialog.Builder(this)
            .setTitle("${t.cam.name} 音量")
            .setView(seek)
            .setPositiveButton("完成", null)
            .show()
    }

    // ============================ 連線 ============================

    /** quiet = true：換畫質用，不顯示「連線中」，畫面停在最後一格直到新畫面出來 */
    private fun connect(t: Tile, quiet: Boolean = false) {
        t.retry?.let { handler.removeCallbacks(it) }
        t.retry = null
        val p = t.player ?: return
        val hd = t.hd ?: false
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)      // 用 TCP，比 UDP 穩定
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(config.rtspUrl(t.cam.ip, hd)))
        p.setMediaSource(source)
        p.prepare()
        t.started = true
        p.playWhenReady = true
        if (!quiet) t.setStatus("連線中…")
        t.lastPos = -1L
        t.lastProgress = SystemClock.elapsedRealtime()
    }

    private fun scheduleRetry(t: Tile, msg: String) {
        if (t.retry != null || t.paused) return
        t.player?.stop()
        t.setStatus(msg)
        val r = Runnable { t.retry = null; connect(t) }
        t.retry = r
        handler.postDelayed(r, 5000L + (0..2000).random())
    }

    /** 每秒更新時鐘 */
    private val ticker = object : Runnable {
        override fun run() {
            val now = Date()
            val time = timeFmt.format(now)
            val date = dateFmt.format(now)
            val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
            barClock.text = if (wide) "●  $date  $time" else "●  $time"
            clockTime.text = time
            clockDate.text = date
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    /** 看門狗：畫面 15 秒沒有前進就重新連線 */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            tiles.values.forEach { t ->
                val p = t.player ?: return@forEach
                if (t.retry != null || t.paused) return@forEach
                val pos = p.currentPosition
                if (pos != t.lastPos) {
                    t.lastPos = pos; t.lastProgress = now
                } else if (now - t.lastProgress > 15000) {
                    t.lastProgress = now
                    scheduleRetry(t, "畫面停住，重新連線…")
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    // ============================ 排版 ============================

    /** 只有全螢幕用高畫質；大畫面、小畫面都用低畫質，切換時不必重新連線 */
    private fun isHd(id: String): Boolean = fullId == id

    private fun relayout() {
        val w = grid.width
        val h = grid.height
        if (w == 0 || h == 0 || tiles.isEmpty()) return
        val quad = layoutMode == "quad" && fullId == null
        val gap = if (quad) 0 else dp(4)   // 四格：不留間隔
        val landscape = w >= h
        layoutButtons["focus"]?.text = "1大${maxOf(0, tiles.size - 1)}小"
        refreshFocusUi()

        val rects = HashMap<String, IntArray>()   // x, y, w, h
        val downgrades = ArrayList<Tile>()   // 要改成低畫質的（先做，釋放硬體解碼器）
        val upgrades = ArrayList<Tile>()     // 要改成高畫質的（等前面釋放後再做）
        var clockRect: IntArray? = null
        val n = order.size
        val full = fullId

        if (full != null) {
            rects[full] = intArrayOf(0, 0, w, h)
        } else if (layoutMode == "quad") {
            // 四格：2×2 填滿螢幕，排序前四支；其餘暫停
            val cellW = (w - gap) / 2
            val cellH = (h - gap) / 2
            quadOrder.forEachIndexed { i, id ->
                rects[id] = intArrayOf((i % 2) * (cellW + gap), (i / 2) * (cellH + gap), cellW, cellH)
            }
        } else if (layoutMode == "focus" && n > 1) {
            val others = order.drop(1)
            if (landscape) {
                // 小畫面欄寬 = 16:9 影像剛好的寬度：不裁切、不留黑邊（高度固定是 1/4，這已是最大）
                val smallH = (h - gap * (others.size - 1)) / others.size
                val colTarget = smallH * 16 / 9
                val mainW = (w - gap - colTarget).coerceIn(w / 2, w * 4 / 5)
                rects[order[0]] = intArrayOf(0, 0, mainW, h)
                val colX = mainW + gap
                val colW = w - colX
                val cellH = (h - gap * (others.size - 1)) / others.size
                others.forEachIndexed { i, id ->
                    rects[id] = intArrayOf(colX, i * (cellH + gap), colW, cellH)
                }
            } else {
                val mainH = min(w * 9 / 16, h * 55 / 100)
                rects[order[0]] = intArrayOf(0, 0, w, mainH)
                val cols = 2
                val rows = ceil(others.size / cols.toDouble()).toInt()
                val top = mainH + gap
                val cellW = (w - gap) / cols
                val cellH = (h - top - gap * (rows - 1)) / rows
                others.forEachIndexed { i, id ->
                    val c = i % cols
                    val r = i / cols
                    val x = if (others.size % 2 == 1 && i == others.size - 1) 0 else c * (cellW + gap)
                    val cw = if (others.size % 2 == 1 && i == others.size - 1) w else cellW
                    rects[id] = intArrayOf(x, top + r * (cellH + gap), cw, cellH)
                }
            }
        } else {
            val cols = if (landscape) (if (n <= 4) 2 else 3) else (if (n <= 2) 1 else 2)
            val rows = ceil(n / cols.toDouble()).toInt()
            val cellW = (w - gap * (cols - 1)) / cols
            val cellH = (h - gap * (rows - 1)) / rows
            evenOrder.forEachIndexed { i, id ->
                rects[id] = intArrayOf((i % cols) * (cellW + gap), (i / cols) * (cellH + gap), cellW, cellH)
            }
            // 最後一列有空位 → 放時鐘
            val empty = rows * cols - n
            if (empty > 0) {
                val x = (n % cols) * (cellW + gap)
                clockRect = intArrayOf(x, (rows - 1) * (cellH + gap), w - x, cellH)
            }
        }

        val cr = clockRect
        if (cr != null) {
            clockTile.visibility = View.VISIBLE
            clockTile.layoutParams = FrameLayout.LayoutParams(cr[2], cr[3]).apply {
                leftMargin = cr[0]; topMargin = cr[1]
            }
            val size = min(cr[3] * 0.24f, cr[2] * 0.16f)
            clockTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            clockDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.32f)
        } else {
            clockTile.visibility = View.GONE
        }

        order.forEachIndexed { i, id ->
            val t = tiles[id] ?: return@forEachIndexed
            val r = rects[id]
            if (r == null) {
                t.view.visibility = View.GONE
                if (full == null && !t.paused) {
                    // 四格模式沒顯示的那支：暫停播放，不佔解碼器
                    t.paused = true
                    t.retry?.let { handler.removeCallbacks(it) }
                    t.retry = null
                    t.player?.stop()
                    t.hd = null
                }
                return@forEachIndexed
            } else {
                if (t.paused) { t.paused = false; t.hd = null }
                t.view.visibility = View.VISIBLE
                val lp = FrameLayout.LayoutParams(r[2], r[3])
                lp.leftMargin = r[0]
                lp.topMargin = r[1]
                t.view.layoutParams = lp
            }
            t.numTv.text = "[${(slotList()?.indexOf(id)?.takeIf { it >= 0 } ?: i) + 1}]"
            applyStroke(t, i)
            val wantHd = isHd(id)
            if (t.hd != wantHd) {
                t.hd = wantHd
                if (wantHd) upgrades.add(t) else downgrades.add(t)
            }
        }

        // 依序切換畫質：先降、後升，每支錯開，避免同時搶解碼器
        var delay = 0L
        for (t in downgrades) {
            val id = t.cam.id
            handler.postDelayed({
                if (tiles[id] === t) { val q = t.started; if (q) t.recreatePlayer(); connect(t, quiet = q) }
            }, delay)
            delay += 300L
        }
        if (upgrades.isNotEmpty()) {
            delay += if (downgrades.any { it.started }) 800L else 0L
            for (t in upgrades) {
                val id = t.cam.id
                handler.postDelayed({
                    if (tiles[id] === t) {
                        // 進全螢幕：不保留舊畫面，直接顯示「載入高畫質」，接上後才出現
                        t.playerView.setKeepContentOnPlayerReset(false)
                        if (t.started) t.recreatePlayer()
                        connect(t)
                        t.setStatus("載入高畫質…")
                        t.playerView.setKeepContentOnPlayerReset(true)
                    }
                }, delay)
                delay += 300L
            }
        }

        // 聲音跟著大畫面：大畫面（或全螢幕那支）開聲音，其他靜音
        val audioId = fullId ?: if (layoutMode == "focus") order.firstOrNull() else null
        if (audioId != null && audioId != lastAudioId) {
            tiles.values.forEach { tt ->
                tt.setAudio(if (tt.volume <= 0f) 0.8f else tt.volume, tt.cam.id != audioId)
            }
        }
        lastAudioId = audioId

        if (focusId != null && rects[focusId!!] == null && fullId == null) focusId = order.firstOrNull()
    }

    /** 邊框：遙控器選到的用黃色粗框，大畫面用藍框 */
    private fun applyStroke(t: Tile, i: Int = order.indexOf(t.cam.id)) {
        val main = false   // 大畫面不另外加框，避免兩種框混在一起
        val focused = showFocus && barIndex < 0 && focusId == t.cam.id && fullId == null
        val quad = layoutMode == "quad" && fullId == null
        val (w, c) = when {
            focused -> (if (quad) dp(2) else dp(3)) to "#3D8BFF"     // 藍色細框（跟 App 主色一致）
            main -> dp(2) to "#2F6FED"
            quad -> 0 to "#00000000"           // 四格：沒選到就不畫框
            else -> dp(1) to "#222222"
        }
        val radius = if (quad) 0f else dp(6).toFloat()
        (t.view.foreground as GradientDrawable).apply {
            setStroke(w, Color.parseColor(c)); cornerRadius = radius
        }
        (t.view.background as GradientDrawable).cornerRadius = radius
        // 四格：影像放大填滿整格（不留黑邊）；其他版面：完整顯示
        // 四格、1 大 4 小的小畫面：放大填滿整格（不留黑邊）；其他：完整顯示
        val mode = if (quad) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        if (t.playerView.resizeMode != mode) t.playerView.resizeMode = mode
    }

    private fun setLayoutMode(mode: String) {
        if (layoutMode == mode) return
        layoutMode = mode
        config.layout = mode
        barPeek = false
        barIndex = -1
        updateBar()
        grid.post { relayout() }
    }

    private fun refreshFocusUi() {
        tiles.values.forEach { applyStroke(it) }
        barButtons.forEachIndexed { i, b ->
            val current = (showFocus || !isTv) && layoutButtons[layoutMode] === b
            val bg = when { i == barIndex -> "#3D8BFF"; current -> "#1F3F7A"; else -> "#262A31" }
            (b.background as GradientDrawable).setColor(Color.parseColor(bg))
            b.setTextColor(Color.parseColor(if (i == barIndex || current) "#FFFFFF" else "#DDDDDD"))
        }
    }

    // ============================ 遙控器 ============================

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val ours = isOk(code) || code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 || when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> true
            else -> false
        }
        if (!ours || tiles.isEmpty()) return super.dispatchKeyEvent(e)

        if (isOk(code)) {
            // 長按：按住 0.6 秒就直接觸發，不用等放開
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                okDownTime = e.eventTime
                longOkFired = false
                bumpIdle()
                handler.removeCallbacks(longOk)
                handler.postDelayed(longOk, 300)
            }
            if (e.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longOk)
                if (!longOkFired) onOk(false)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val wasHidden = !showFocus
        showFocus = true
        bumpIdle()
        // 選取框隱藏時，第一下方向鍵只把它叫出來，不移動
        // 選取框隱藏時按「上」：直接選到工具列第一個按鈕（四格）
        if (wasHidden && code == KeyEvent.KEYCODE_DPAD_UP && fullId == null && bar.visibility == View.VISIBLE) {
            barIndex = 0
            refreshFocusUi(); return true
        }
        if (wasHidden && code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            refreshFocusUi(); return true
        }
        if (barPeek) { handler.removeCallbacks(hideBarLater); handler.postDelayed(hideBarLater, 6000) }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(0, 1)
            KeyEvent.KEYCODE_MENU -> focusedTile()?.let { it.setAudio(if (it.volume <= 0f) 0.8f else it.volume, !it.muted) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                setFull(if (fullId != null) null else focusId)
            else -> {
                val n = code - KeyEvent.KEYCODE_1
                val sl = slotList()
                if (sl != null && fullId == null) {
                    if (n in sl.indices) focusId = sl[n]
                } else if (n in order.indices) {
                    focusId = order[n]
                    if (fullId != null) setFull(focusId) else makeMain(order[n])
                }
            }
        }
        refreshFocusUi()
        return true
    }

    private fun focusedTile(): Tile? = focusId?.let { tiles[it] }

    /** OK：選到大畫面時全螢幕；選到其他格時換到大畫面；長按：切換靜音 */
    private fun onOk(long: Boolean) {
        if (barIndex >= 0) { barButtons.getOrNull(barIndex)?.performClick(); refreshFocusUi(); return }
        val t = focusedTile() ?: return
        if (long) {
            if ((layoutMode == "quad" || layoutMode == "even") && fullId == null) { pickCamera(t.cam.id); return }
            t.setAudio(if (t.volume <= 0f) 0.8f else t.volume, !t.muted)
            Toast.makeText(this, t.cam.name + if (t.muted) " 靜音" else " 開啟聲音", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            fullId != null -> setFull(null)
            layoutMode == "focus" && order.firstOrNull() != t.cam.id -> makeMain(t.cam.id)
            else -> setFull(t.cam.id)
        }
        refreshFocusUi()
    }

    /** 方向鍵：找該方向上最近的畫面；往上沒有畫面時進入工具列 */
    private fun moveFocus(dx: Int, dy: Int) {
        if (barIndex >= 0) {
            when {
                dy > 0 -> { barIndex = -1; peekBar(false) }
                dx != 0 -> barIndex = (barIndex + dx).coerceIn(0, barButtons.size - 1)
            }
            return
        }
        if (fullId != null) {
            // 全螢幕時左右切換監視器
            if (dx != 0) {
                val i = order.indexOf(fullId!!)
                val next = order[(i + dx + order.size) % order.size]
                focusId = next
                setFull(next)
            }
            return
        }
        val cur = focusedTile() ?: run { focusId = order.firstOrNull(); return }
        // 1 大 4 小：從大畫面進入小畫面那一排時，固定從第一支開始
        if (layoutMode == "focus" && order.size > 1 && cur.cam.id == order[0]) {
            val landscape = grid.width >= grid.height
            if ((landscape && dx > 0) || (!landscape && dy > 0)) { focusId = order[1]; return }
        }
        val cx = cur.view.left + cur.view.width / 2f
        val cy = cur.view.top + cur.view.height / 2f
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        for ((id, t) in tiles) {
            if (id == cur.cam.id || t.view.visibility != View.VISIBLE) continue
            val tx = t.view.left + t.view.width / 2f
            val ty = t.view.top + t.view.height / 2f
            val ddx = tx - cx
            val ddy = ty - cy
            val along = if (dx != 0) ddx * dx else ddy * dy
            if (along <= 1f) continue
            val across = if (dx != 0) kotlin.math.abs(ddy) else kotlin.math.abs(ddx)
            val score = along + across * 2f
            if (score < bestScore) { bestScore = score; best = id }
        }
        if (best != null) focusId = best
        else if (dy < 0) {
            if (layoutMode == "quad") peekBar(true)
            if (bar.visibility == View.VISIBLE) barIndex = 0
        }
    }

    private fun makeMain(id: String) {
        val i = order.indexOf(id)
        if (i <= 0) return
        val first = order[0]
        order[0] = id
        order[i] = first
        config.order = order
        relayout()
    }

    private fun moveTo(fromId: String, toId: String) {
        slotList()?.let { list ->
            val a = list.indexOf(fromId)
            val b = list.indexOf(toId)
            if (a >= 0 && b >= 0) { list[a] = toId; list[b] = fromId; saveSlots(); relayout() }
            return
        }
        val to = order.indexOf(toId)
        order.remove(fromId)
        order.add(to, fromId)
        config.order = order
        relayout()
    }

    private fun setFull(id: String?) {
        fullId = id
        barPeek = false
        updateBar()
        grid.post { relayout() }
    }

    // ---- 工具列：全螢幕與四格模式時隱藏，可暫時叫出 ----
    private var barPeek = false
    private val hideBarLater = Runnable { if (barPeek) { barPeek = false; barIndex = -1; updateBar(); refreshFocusUi() } }

    private fun updateBar() {
        val hide = fullId != null || layoutMode == "quad"
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            bar.visibility = View.GONE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bar.visibility = View.VISIBLE
            if (layoutMode != "quad") ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 暫時顯示工具列，6 秒沒操作自動收起 */
    private fun peekBar(show: Boolean) {
        handler.removeCallbacks(hideBarLater)
        if (true) return   // 四格模式不顯示工具列（按返回鍵回到 1 大 4 小）
        barPeek = show
        if (show) handler.postDelayed(hideBarLater, 6000) else barIndex = -1
        updateBar()
    }

    /** 選擇某一格要顯示哪一支監視器（四格模式） */
    /** 目前版面自己的排序清單（四格／均分），1 大 4 小則為 null */
    private fun slotList(): MutableList<String>? = when (layoutMode) {
        "quad" -> quadOrder
        "even" -> evenOrder
        else -> null
    }

    private fun saveSlots() {
        if (layoutMode == "quad") config.quadOrder = quadOrder
        if (layoutMode == "even") config.evenOrder = evenOrder
    }

    private fun pickCamera(slotId: String) {
        val list = slotList() ?: return
        val cams = config.cams
        val names = cams.map { c ->
            val pos = list.indexOf(c.id)
            c.name + if (pos >= 0) "（第 ${pos + 1} 格）" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("這一格要顯示")
            .setItems(names) { _, which ->
                val chosen = cams[which].id
                val a = list.indexOf(slotId)
                val b = list.indexOf(chosen)
                if (a >= 0 && a != b) {
                    if (b >= 0) list[b] = slotId   // 已在其他格 → 兩格互換
                    list[a] = chosen
                    saveSlots()
                    focusId = chosen
                    relayout()
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
