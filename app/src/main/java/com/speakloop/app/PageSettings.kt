package com.speakloop.app

import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File
import kotlin.concurrent.thread

/**
 * 第四屏：设置。
 *
 * 版式对齐设计稿：每一项是「左标题 + 右当前值」的 52dp 行，行与行之间一条 1dp 细线，
 * 一组装进一张卡片。值是对外展示的状态，点整行才弹出选择 —— 不把五枚胶囊堆在行下面，
 * 那样一屏塞不下、层级也糊。
 *
 * 除了 AI 那三项必须输入框，其余都是「选完立刻生效」，没有「保存」按钮。
 */
class PageSettings(private val host: MainActivity) {

    private val prefs: SharedPreferences get() = host.prefs

    val view: View

    /** build() 会整页重建，必须自己记住滚动位置，否则改任何一项都跳回顶部 */
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var testResult: TextView

    private fun dp(v: Int) = host.dp(v)

    /** App 版本。拿不到就返回空串，标题只显示 "SpeakLoop"，不编一个假版本号。 */
    private fun appVersion(): String = try {
        host.packageManager.getPackageInfo(host.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    init {
        scroll = ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(body)
        view = scroll
        build()
    }

    private fun build() {
        // 重建页面会丢输入框里的内容，所以先把已经敲进去的 AI 配置存下来
        if (::baseUrl.isInitialized) saveAiQuietly()
        val keepY = if (::scroll.isInitialized) scroll.scrollY else 0

        body.removeAllViews()
        body.addView(header())

        body.addView(section("复习"))
        body.addView(reviewCard(), W.margin(host, top = 6))

        body.addView(section("播放"))
        body.addView(playCard(), W.margin(host, top = 6))

        body.addView(section("外观"))
        body.addView(themeCard(), W.margin(host, top = 6))

        body.addView(section("AI 补全"))
        body.addView(aiCard(), W.margin(host, top = 6))

        body.addView(section("数据"))
        body.addView(dataCard(), W.margin(host, top = 6))

        body.addView(
            Ui.text(
                host,
                "短语和录音都存在这台手机上，删 App 就没了。\n" +
                    "语音用的是系统自带的 TTS 引擎，换引擎音色会变。",
                Ui.T.CAP,
                Ui.MUTED
            ).apply { setLineSpacing(dp(4).toFloat(), 1f) },
            W.margin(host, top = 22, left = 4)
        )

        scroll.post {
            val max = (body.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, keepY.coerceAtMost(max))
        }
    }

    /** 标题一行：「设置」+ 右上角构建号，跟设计稿一致，不再竖排两行 */
    private fun header(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        row.addView(
            Ui.text(host, "设置", Ui.T.H1, Ui.FG, true).apply { letterSpacing = -0.01f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val v = appVersion()
        if (v.isNotBlank()) {
            row.addView(
                Ui.text(host, "v$v", Ui.T.CAP, Ui.MUTED).apply { setPadding(0, 0, 0, dp(6)) }
            )
        }
        return row
    }

    private fun section(t: String): TextView =
        Ui.text(host, t, Ui.T.CAP, Ui.MUTED, true).apply {
            setPadding(dp(4), dp(18), 0, 0)
            letterSpacing = 0.06f
        }

    /** 原型里卡片就是 `padding: 2px 14px`：上下几乎不留白，高度全靠行的 52dp 撑 */
    private fun card(): LinearLayout = W.card(host).apply {
        setPadding(dp(14), dp(2), dp(14), dp(2))
    }

    /**
     * 一行设置：左标题、右当前值，行高 52dp。
     *
     * highlight = true 时值用绿色（「开」这类正向状态，和播放态同一个语义）。
     * 传了 onClick 就补一个 ›，点整行弹选择。
     */
    private fun setRow(
        label: String,
        value: String,
        highlight: Boolean = false,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(2), 0, dp(2), 0)
        }
        row.addView(
            Ui.text(host, label, Ui.T.BODY, Ui.FG),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(Ui.text(host, value, Ui.T.SUB, if (highlight) Ui.GOOD else Ui.MUTED, highlight))
        if (onClick != null) {
            row.addView(
                Ui.text(host, "›", Ui.T.SUB, Ui.MUTED).apply { setPadding(dp(7), 0, 0, 0) }
            )
            row.background = Ui.ripple(
                host, 10, Color.TRANSPARENT, 0, Ui.STROKE,
                if (Ui.isDark) 0x1AFFFFFF else 0x12000000
            )
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private fun line() = View(host).apply {
        setBackgroundColor(Ui.STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    /** 选项弹窗：选中即生效，不再需要「保存」 */
    private fun pick(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val dialog = AlertDialog.Builder(host)
            .setTitle(title)
            .setSingleChoiceItems(Ui.dialogItems(labels), current) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }

    private fun toggle(key: String, v: Boolean) {
        prefs.edit().putBoolean(key, v).apply()
        build()
    }

    // ---------- 复习 ----------

    private fun reviewCard(): View {
        val c = card()

        val goals = listOf(3, 5, 8)
        val goal = prefs.getInt("dailyGoal", 5)
        c.addView(
            setRow("每天练几条", "$goal 条") {
                pick("每天练几条", goals.map { "$it 条" }, goals.indexOf(goal).coerceAtLeast(0)) { i ->
                    prefs.edit().putInt("dailyGoal", goals[i]).apply()
                    build()
                }
            }
        )
        c.addView(line())

        val limits = listOf(10, 15, 25, 0)
        val limit = prefs.getInt("queueLimit", 15)
        c.addView(
            setRow("一轮最多几条", if (limit == 0) "不限" else "$limit 条") {
                val labels = limits.map { if (it == 0) "不限" else "$it 条" }
                pick("一轮最多几条", labels, limits.indexOf(limit).coerceAtLeast(0)) { i ->
                    prefs.edit().putInt("queueLimit", limits[i]).apply()
                    build()
                }
            }
        )
        c.addView(line())

        val due = prefs.getBoolean("includeDue", true)
        c.addView(
            setRow("补上从没练过的", if (due) "开" else "关", due) {
                pick("补上从没练过的", listOf("开", "关"), if (due) 0 else 1) { i ->
                    toggle("includeDue", i == 0)
                }
            }
        )
        return c
    }

    // ---------- 播放 ----------

    private fun playCard(): View {
        val c = card()

        val rates = listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f)
        val rate = prefs.getFloat("rate", 1f)
        val ri = rates.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f }.coerceAtLeast(0)
        c.addView(
            setRow("朗读语速", "×" + trim(rate)) {
                pick("朗读语速", rates.map { "×" + trim(it) }, ri) { i ->
                    prefs.edit().putFloat("rate", rates[i]).apply()
                    build()
                    host.toast(
                        if (ReviewBus.state.running) "下一轮复习生效" else "已设为 ×${trim(rates[i])}"
                    )
                }
            }
        )
        c.addView(line())

        val follow = prefs.getBoolean("followGap", true)
        c.addView(
            setRow("跟读留白", if (follow) "8 秒" else "0.5 秒", follow) {
                pick("跟读留白", listOf("8 秒（跟读）", "0.5 秒（只听）"), if (follow) 0 else 1) { i ->
                    toggle("followGap", i == 0)
                }
            }
        )
        c.addView(line())

        val loop = prefs.getBoolean("loop", false)
        c.addView(
            setRow("循环播放", if (loop) "开" else "关", loop) {
                pick("循环播放", listOf("关", "开"), if (loop) 1 else 0) { i ->
                    toggle("loop", i == 1)
                }
            }
        )
        c.addView(line())

        c.addView(
            Ui.text(
                host,
                if (follow) {
                    "跟读开着：留白 8 秒并弹出跟读面板，说完静音 1.2 秒自动进下一句。" +
                        "只想听不想读就选 0.5 秒，整轮能快一倍。"
                } else {
                    "当前是只听模式：留白 0.5 秒，跟读面板不会出现。"
                },
                Ui.T.CAP,
                Ui.MUTED
            ).apply {
                setPadding(dp(2), dp(10), dp(2), dp(10))
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        )
        return c
    }

    private fun trim(v: Float): String =
        String.format("%.2f", v).trimEnd('0').trimEnd('.')

    // ---------- 外观 ----------

    private fun themeCard(): View {
        val c = card()
        val mode = prefs.getString("theme", "system") ?: "system"
        val labels = listOf("跟随系统", "浅色", "深色")
        val keys = listOf("system", "light", "dark")
        val idx = keys.indexOf(mode).coerceAtLeast(0)
        c.addView(
            setRow("主题", labels[idx]) {
                pick("主题", labels, idx) { i ->
                    prefs.edit().putString("theme", keys[i]).apply()
                    Ui.applyTheme(host)
                    host.recreate()
                }
            }
        )
        return c
    }

    // ---------- AI ----------

    private fun aiCard(): View {
        val c = W.card(host).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }

        c.addView(fieldLabel("服务地址（只填到 /v1）"))
        baseUrl = W.input(
            host,
            Llm.DEFAULT_BASE,
            prefs.getString("baseUrl", "").orEmpty().ifBlank { Llm.DEFAULT_BASE }
        )
        c.addView(baseUrl)

        c.addView(fieldLabel("API Key"))
        apiKey = W.input(host, "sk-...", prefs.getString("apiKey", "")!!)
        c.addView(apiKey)

        c.addView(fieldLabel("模型"))
        model = W.input(host, "agnes-2.5-flash", prefs.getString("model", "agnes-2.5-flash")!!)
        c.addView(model)

        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(13), 0, 0)
        }
        actions.addView(
            Ui.button(host, "保存", null, true) { saveAi() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            Ui.button(host, "测试连接", null, false) { testAi() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
        )
        c.addView(actions)

        testResult = Ui.text(host, "", Ui.T.CAP, Ui.MUTED).apply {
            setPadding(0, dp(11), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        c.addView(testResult)

        c.addView(
            Ui.text(
                host,
                "只用于新增短语时补全释义、例句和对话；不填也能用，只是要自己写中文。",
                Ui.T.CAP,
                Ui.MUTED
            ).apply { setPadding(0, dp(10), 0, 0) }
        )
        return c
    }

    private fun fieldLabel(t: String): TextView =
        Ui.text(host, t, Ui.T.CAP, Ui.MUTED).apply { setPadding(dp(2), 0, 0, dp(6)) }

    // ---------- 数据 ----------

    private fun dataCard(): View {
        val c = card()

        val db = Db(host)
        val total = try {
            db.count()
        } finally {
            db.close()
        }
        c.addView(setRow("短语总数", "$total 条"))
        c.addView(line())

        val (files, bytes) = followUsage()
        c.addView(
            setRow("跟读录音", if (files == 0) "无" else "$files 段 · ${size(bytes)}") {
                if (files == 0) host.toast("还没有跟读录音") else confirmClearFollow(files, bytes)
            }
        )
        return c
    }

    /** 跟读录音占用：每条短语一个 wav，覆盖式保存，文件数不会超过短语数 */
    private fun followUsage(): Pair<Int, Long> {
        val list = File(host.filesDir, "follow").listFiles() ?: return 0 to 0L
        return list.size to list.sumOf { it.length() }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%d KB", bytes / 1024)
        else -> "$bytes B"
    }

    private fun confirmClearFollow(files: Int, bytes: Long) {
        val dialog = AlertDialog.Builder(host)
            .setTitle("清除跟读录音？")
            .setMessage("$files 段录音共 ${size(bytes)}，全部删掉，短语本身不受影响。")
            .setPositiveButton("清除") { _, _ ->
                File(host.filesDir, "follow").listFiles()?.forEach { it.delete() }
                host.toast("录音已清除")
                build()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }

    // ---------- AI 存取 ----------

    private fun saveAiQuietly() {
        prefs.edit()
            .putString("baseUrl", baseUrl.text.toString().trim().ifBlank { Llm.DEFAULT_BASE })
            .putString("apiKey", apiKey.text.toString().trim())
            .putString("model", model.text.toString().trim().ifBlank { "agnes-2.5-flash" })
            .apply()
    }

    private fun saveAi() {
        saveAiQuietly()
        host.toast("已保存")
    }

    private fun testAi() {
        val bu = baseUrl.text.toString().trim().ifBlank { Llm.DEFAULT_BASE }
        val k = apiKey.text.toString().trim()
        val m = model.text.toString().trim()
        if (bu.isBlank() || k.isBlank() || m.isBlank()) {
            testResult.setTextColor(Ui.BAD)
            testResult.text = "三项都要填"
            return
        }
        testResult.setTextColor(Ui.MUTED)
        testResult.text = "测试中……（推理模型可能要几秒）"
        thread {
            var ok = true
            var msg = ""
            try {
                val r = Llm.test(bu, k, m)
                msg = buildString {
                    append("连接成功\n")
                    append("地址：").append(r.endpoint).append('\n')
                    append("模型：").append(m).append('\n')
                    append("用时：").append(r.millis).append(" ms\n")
                    append("回复：").append(r.reply.ifBlank { "(空)" })
                }
            } catch (e: Exception) {
                ok = false
                msg = "失败：" + (e.message ?: e.javaClass.simpleName)
            }
            host.runOnUiThread {
                if (!::testResult.isInitialized) return@runOnUiThread
                testResult.setTextColor(if (ok) Ui.GOOD else Ui.BAD)
                testResult.text = msg
            }
        }
    }
}
