package tw.bktu.monitor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Cam(val id: String, var name: String, var ip: String)

/** 所有設定都存在手機的 SharedPreferences，不會出現在程式碼裡 */
class Config(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE)

    var user: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v).apply()

    var pass: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    var cams: List<Cam>
        get() {
            val raw = prefs.getString("cams", null) ?: return DEFAULT_CAMS
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Cam(o.getString("id"), o.getString("name"), o.getString("ip"))
                }
            } catch (e: Exception) {
                DEFAULT_CAMS
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ip", it.ip)) }
            prefs.edit().putString("cams", arr.toString()).apply()
        }

    var order: List<String>
        get() = (prefs.getString("order", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("order", v.joinToString(",")).apply()

    /** 四格模式的四支（獨立於 1 大 4 小的排序） */
    var quadOrder: List<String>
        get() = (prefs.getString("quadOrder", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("quadOrder", v.joinToString(",")).apply()

    /** 均分模式的排序（獨立） */
    var evenOrder: List<String>
        get() = (prefs.getString("evenOrder", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("evenOrder", v.joinToString(",")).apply()

    var layout: String
        get() = prefs.getString("layout", "focus") ?: "focus"
        set(v) = prefs.edit().putString("layout", v).apply()

    /** 每格下方是否顯示名稱列（名稱、燈號、喇叭、全螢幕按鈕） */
    var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", false)
        set(v) = prefs.edit().putBoolean("showLabels", v).apply()

    /** 開啟 App 時的版面：focus / even / quad / last（上次使用） */
    var startLayout: String
        get() = prefs.getString("startLayout", "focus") ?: "focus"
        set(v) = prefs.edit().putString("startLayout", v).apply()

    /** 每格是否顯示音量圖示 */
    var showVolume: Boolean
        get() = prefs.getBoolean("showVolume", true)
        set(v) = prefs.edit().putBoolean("showVolume", v).apply()

    /** 小畫面用軟體解碼：null = 自動（電視開、手機關） */
    var softSmall: Boolean?
        get() = when (prefs.getInt("softSmall", -1)) { 1 -> true; 0 -> false; else -> null }
        set(v) = prefs.edit().putInt("softSmall", when (v) { true -> 1; false -> 0; null -> -1 }).apply()

    fun volume(id: String): Float = prefs.getFloat("vol_$id", 0.8f)
    fun muted(id: String): Boolean = prefs.getBoolean("muted_$id", true)
    fun setAudio(id: String, vol: Float, muted: Boolean) {
        prefs.edit().putFloat("vol_$id", vol).putBoolean("muted_$id", muted).apply()
    }

    val isReady: Boolean get() = user.isNotBlank() && pass.isNotBlank() && cams.isNotEmpty()

    fun rtspUrl(ip: String, hd: Boolean): String {
        val u = Uri.encode(user)
        val p = Uri.encode(pass)
        val path = if (hd) "stream1" else "stream2"
        return "rtsp://$u:$p@${ip.trim()}:554/$path"
    }

    companion object {
        val DEFAULT_CAMS = listOf(
            Cam("c1", "監視器 1", "192.168.68.53"),
            Cam("c2", "監視器 2", "192.168.68.59"),
            Cam("c3", "監視器 3", "192.168.68.58"),
            Cam("c4", "監視器 4", "192.168.68.103"),
            Cam("c5", "監視器 5", "192.168.68.50"),
        )
    }
}
