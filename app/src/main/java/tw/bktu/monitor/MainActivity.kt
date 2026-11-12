package tw.bktu.monitor

import android.annotation.SuppressLint
import android.app.AlertDialog
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
    private lateinit var barClock: TextView
    private lateinit var clockTile: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN)

    private val tiles = LinkedHashMap<String, Tile>()
    private var order = mutableListOf<String>()
    private var layoutMode = "focus"
    private var fullId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastW = 0
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
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
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
                if (fullId != null) setFull(null) else finish()
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

        layoutBtn = barButton("均分") {
            layoutMode = if (layoutMode == "focus") "even" else "focus"
            config.layout = layoutMode
            relayout()
        }
        b.addView(layoutBtn)
        b.addView(barButton("全部靜音") { tiles.values.forEach { it.setAudio(it.volume, true) } })
        b.addView(barButton("重新連線") { tiles.values.forEach { connect(it) } })
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
    }

    // ============================ 畫面格 ============================

    private fun buildTiles() {
        grid.removeAllViews()
        tiles.clear()
        val cams = config.cams
        val ids = cams.map { it.id }
        order = config.order.filter { it in ids }.toMutableList()
        ids.filter { it !in order }.forEach { order.add(it) }
        layoutMode = config.layout
        fullId = null

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

        init {
            view.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.parseColor("#222222"))
                cornerRadius = dp(6).toFloat()
            }
            view.clipToOutline = true

            playerView.useController = false
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView.setShutterBackgroundColor(Color.BLACK)
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
            })
            touch.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
            view.addView(touch, FrameLayout.LayoutParams(-1, -1))

            // 名稱列
            label.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
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
            muteBtn.apply {
                textSize = 16f; gravity = Gravity.CENTER
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
            view.addView(label, FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP))

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
            val p = ExoPlayer.Builder(this@MainActivity).setLoadControl(load).build()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> setPlaying(true)
                        Player.STATE_ENDED -> scheduleRetry(this@Tile, "串流中斷，重新連線…")
                        else -> {}
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    scheduleRetry(this@Tile, "連線失敗，5 秒後重試…")
                }
            })
            playerView.player = p
            player = p
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

    private fun connect(t: Tile) {
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
        p.playWhenReady = true
        t.setStatus("連線中…")
        t.lastPos = -1L
        t.lastProgress = SystemClock.elapsedRealtime()
    }

    private fun scheduleRetry(t: Tile, msg: String) {
        if (t.retry != null) return
        t.player?.stop()
        t.setStatus(msg)
        val r = Runnable { t.retry = null; connect(t) }
        t.retry = r
        handler.postDelayed(r, 5000)
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
                if (t.retry != null) return@forEach
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

    private fun isHd(id: String): Boolean =
        fullId == id || (fullId == null && layoutMode == "focus" && order.firstOrNull() == id)

    private fun relayout() {
        val w = grid.width
        val h = grid.height
        if (w == 0 || h == 0 || tiles.isEmpty()) return
        val gap = dp(4)
        val landscape = w >= h
        layoutBtn.text = if (layoutMode == "focus") "均分" else "1 大 ${tiles.size - 1} 小"

        val rects = HashMap<String, IntArray>()   // x, y, w, h
        var clockRect: IntArray? = null
        val n = order.size
        val full = fullId

        if (full != null) {
            rects[full] = intArrayOf(0, 0, w, h)
        } else if (layoutMode == "focus" && n > 1) {
            val others = order.drop(1)
            if (landscape) {
                val mainW = (w - gap) * 3 / 4
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
            order.forEachIndexed { i, id ->
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
            } else {
                t.view.visibility = View.VISIBLE
                val lp = FrameLayout.LayoutParams(r[2], r[3])
                lp.leftMargin = r[0]
                lp.topMargin = r[1]
                t.view.layoutParams = lp
            }
            t.numTv.text = "[${i + 1}]"
            val main = full == null && layoutMode == "focus" && i == 0
            (t.view.background as GradientDrawable).setStroke(
                dp(if (main) 2 else 1), Color.parseColor(if (main) "#2F6FED" else "#222222")
            )
            val wantHd = isHd(id)
            if (t.hd != wantHd) {
                t.hd = wantHd
                connect(t)
            }
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
        val to = order.indexOf(toId)
        order.remove(fromId)
        order.add(to, fromId)
        config.order = order
        relayout()
    }

    private fun setFull(id: String?) {
        fullId = id
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (id != null) {
            bar.visibility = View.GONE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bar.visibility = View.VISIBLE
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        grid.post { relayout() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
