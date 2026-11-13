package tw.bktu.monitor

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var userEt: EditText
    private lateinit var passEt: EditText
    private lateinit var list: LinearLayout
    private val rows = mutableListOf<Row>()

    private class Row(val id: String, val view: LinearLayout, val name: EditText, val ip: EditText)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D0F12")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(col)

        col.addView(title("監視器設定", 22f))
        col.addView(hint("帳號密碼是在 Tapo App →「進階設定 → 攝影機帳戶」建立的那組，只會存在這支手機上。"))

        col.addView(title("攝影機帳戶", 16f))
        userEt = input("帳號", config.user)
        col.addView(userEt)
        passEt = input("密碼", config.pass).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(passEt)

        col.addView(title("監視器（名稱 / IP）", 16f))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        config.cams.forEach { addRow(it.id, it.name, it.ip) }

        col.addView(button("＋ 新增監視器", "#262A31") {
            addRow("c" + System.currentTimeMillis(), "監視器 ${rows.size + 1}", "192.168.68.")
        })
        // 顯示
        col.addView(title("顯示", 16f))
        val labelBtn = button("", "#262A31") {}
        fun labelText() = if (config.showLabels) "監視器名稱列：顯示" else "監視器名稱列：隱藏"
        labelBtn.text = labelText()
        labelBtn.setOnClickListener {
            config.showLabels = !config.showLabels
            labelBtn.text = labelText()
        }
        col.addView(labelBtn)
        col.addView(hint("隱藏時：點一下畫面換到大畫面、點兩下全螢幕、長按拖曳排序。"))

        // 進階：解碼方式
        col.addView(title("進階", 16f))
        val modes = listOf<Boolean?>(null, true, false)
        val names = listOf("自動（電視開）", "小畫面軟體解碼：開", "小畫面軟體解碼：關")
        var idx = modes.indexOf(config.softSmall)
        val softBtn = button(names[idx], "#262A31") {}
        softBtn.setOnClickListener {
            idx = (idx + 1) % modes.size
            config.softSmall = modes[idx]
            softBtn.text = names[idx]
        }
        col.addView(softBtn)
        col.addView(hint(decoderInfo()))

        col.addView(button("儲存", "#2F6FED") { save() })

        setContentView(scroll)
    }

    private fun addRow(id: String, name: String, ip: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameEt = input("名稱", name)
        val ipEt = input("IP", ip).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        row.addView(nameEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ipEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
            marginStart = dp(8)
        })
        val r = Row(id, row, nameEt, ipEt)
        val del = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E5484D"))
            textSize = 18f
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener {
                list.removeView(row)
                rows.remove(r)
            }
        }
        row.addView(del)
        rows.add(r)
        list.addView(row)
    }

    private fun save() {
        val user = userEt.text.toString().trim()
        val pass = passEt.text.toString()
        val cams = rows.map { Cam(it.id, it.name.text.toString().trim(), it.ip.text.toString().trim()) }
            .filter { it.ip.isNotBlank() }
        if (user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "請輸入帳號和密碼", Toast.LENGTH_SHORT).show(); return
        }
        if (cams.isEmpty()) {
            Toast.makeText(this, "至少要有一支監視器", Toast.LENGTH_SHORT).show(); return
        }
        config.user = user
        config.pass = pass
        config.cams = cams.map { if (it.name.isBlank()) it.copy(name = it.ip) else it }
        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- 小工具 ----------

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#E6E6E6"))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#8B949E"))
    }

    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#6B7380"))
    }

    private fun button(text: String, color: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, dp(12), 0, dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color)); cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    /** 顯示這台裝置的 H.264 解碼器（硬體／軟體、同時可用數量） */
    private fun decoderInfo(): String {
        return try {
            val sb = StringBuilder("H.264 解碼器：\n")
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder || info.supportedTypes.none { it.equals("video/avc", true) }) continue
                val caps = info.getCapabilitiesForType("video/avc")
                val max = if (android.os.Build.VERSION.SDK_INT >= 23) caps.maxSupportedInstances else -1
                val sw = info.name.startsWith("OMX.google") || info.name.startsWith("c2.android")
                sb.append("・").append(info.name).append(if (sw) "（軟體）" else "（硬體）")
                    .append(" 最多 ").append(max).append(" 路\n")
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            "無法讀取解碼器資訊"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
