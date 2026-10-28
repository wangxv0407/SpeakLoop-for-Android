package com.speakloop.app

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 第四屏：设置。
 *
 * 除了 AI 那三项需要输入框，其余都是「点一下就生效」的分段选择，
 * 不再有「改完还要按保存」这一步——唯一例外是 AI 配置，
 * 因为改一半的 key 存进去会把下一次建短语搞坏。
 */
class PageSettings(private val host: MainActivity) {

    private val prefs: SharedPreferences get() = host.prefs

    val view: View
    private lateinit var body: LinearLayout

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var testResult: TextView

    private fun dp(v: Int) = host.dp(v)

    init {
        val scroll = ScrollView(host).apply {
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
        body.removeAllViews()
        body.addView(Ui.text(host, "SpeakLoop 1.6", 12f, Ui.DIM))
        body.addView(Ui.text(host, "设置", 25f, Ui.FG, true).apply { letterSpacing = -0.01f })

        body.addView(section("复习"))
        body.addView(reviewCard(), W.margin(host, top = 7))

        body.addView(section("播放"))
        body.addView(playCard(), W.margin(host, top = 7))

        body.addView(section("外观"))
        body.addView(themeCard(), W.margin(host, top = 7))

        body.addView(section("AI 补全"))
        body.addView(aiCard(), W.margin(host, top = 7))

        body.addView(
            Ui.text(
                host,
                "短语存在这台手机上，删 App 就没了。\n" +
                    "语音用的是系统自带的 TTS 引擎，换引擎音色会变。",
                10f,
                Ui.MUTED
            ).apply { setLineSpacing(dp(4).toFloat(), 1f) },
            W.margin(host, top = 22, left = 4)
        )
    }

    private fun section(t: String): TextView =
        Ui.text(host, t, 11f, Ui.MUTED, true).apply {
            setPadding(dp(4), dp(14), 0, 0)
            letterSpacing = 0.06f
        }

    private fun card(): LinearLayout = W.card(host).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }

    private fun rowTitle(k: String, v: String): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.text(host, k, 13f, Ui.FG),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(Ui.text(host, v, 12f, Ui.DIM))
        return row
    }

    /** 一组分段选项，选中项是强调色浅底。 */
    private fun choices(labels: List<String>, current: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        labels.forEachIndexed { i, label ->
            val pill = Ui.pill(host, label, i == current) { onPick(i) }
            row.addView(pill, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { if (i > 0) leftMargin = dp(5) })
        }
        return row
    }

    private fun divider() = View(host).apply {
        setBackgroundColor(Ui.STROKE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = dp(13) }
    }

    // ---------- 复习 ----------

    private fun reviewCard(): View {
        val c = card()

        c.addView(rowTitle("每天练几条", "${prefs.getInt("dailyGoal", 5)} 条"))
        val goals = listOf(3, 5, 8)
        val gi = goals.indexOf(prefs.getInt("dailyGoal", 5)).coerceAtLeast(0)
        c.addView(choices(goals.map { "$it 条" }, gi) { i ->
            prefs.edit().putInt("dailyGoal", goals[i]).apply()
            build()
        })

        c.addView(divider())
        c.addView(rowTitle("一轮最多几条", "${prefs.getInt("queueLimit", 15)} 条"))
        val limits = listOf(10, 15, 25, 0)
        val li = limits.indexOf(prefs.getInt("queueLimit", 15)).coerceAtLeast(0)
        c.addView(choices(limits.map { if (it == 0) "不限" else "$it 条" }, li) { i ->
            prefs.edit().putInt("queueLimit", limits[i]).apply()
            build()
        })

        c.addView(divider())
        val due = prefs.getBoolean("includeDue", true)
        c.addView(rowTitle("补上从没练过的", if (due) "开" else "关"))
        c.addView(choices(listOf("开", "关"), if (due) 0 else 1) { i ->
            prefs.edit().putBoolean("includeDue", i == 0).apply()
            build()
        })
        c.addView(
            Ui.text(host, "开着的话，以前只听过一遍、后来再没碰过的短语也会被排进来", 10f, Ui.MUTED)
                .apply {
                    setPadding(0, dp(7), 0, 0)
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
        )
        return c
    }

    // ---------- 播放 ----------

    private fun playCard(): View {
        val c = card()

        val rate = prefs.getFloat("rate", 1f)
        c.addView(rowTitle("朗读语速", "×" + trim(rate)))
        val rates = listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f)
        val ri = rates.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f }.coerceAtLeast(0)
        c.addView(choices(rates.map { "×" + trim(it) }, ri) { i ->
            prefs.edit().putFloat("rate", rates[i]).apply()
            build()
        })

        c.addView(divider())
        val follow = prefs.getBoolean("followGap", true)
        c.addView(rowTitle("例句后留白", if (follow) "4 秒" else "0.5 秒"))
        c.addView(choices(listOf("4 秒（跟读）", "0.5 秒"), if (follow) 0 else 1) { i ->
            prefs.edit().putBoolean("followGap", i == 0).apply()
            build()
        })
        c.addView(
            Ui.text(host, "关掉留白整轮能快一倍，适合只想听、不想跟读的时候", 10f, Ui.MUTED)
                .apply { setPadding(0, dp(7), 0, 0) }
        )

        c.addView(divider())
        val loop = prefs.getBoolean("loop", false)
        c.addView(rowTitle("循环播放", if (loop) "开" else "关"))
        c.addView(choices(listOf("关", "开"), if (loop) 1 else 0) { i ->
            prefs.edit().putBoolean("loop", i == 1).apply()
            build()
        })
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
        c.addView(rowTitle("主题", labels[keys.indexOf(mode).coerceAtLeast(0)]))
        c.addView(choices(labels, keys.indexOf(mode).coerceAtLeast(0)) { i ->
            prefs.edit().putString("theme", keys[i]).apply()
            Ui.applyTheme(host)
            host.recreate()
        })
        return c
    }

    // ---------- AI ----------

    private fun aiCard(): View {
        val c = card()

        c.addView(W.label(host, "Base URL（只填到 /v1）").apply { setPadding(0, 0, 0, dp(6)) })
        baseUrl = W.input(host, "https://apihub.agnes-ai.com/v1", prefs.getString("baseUrl", "")!!)
        c.addView(baseUrl)

        c.addView(W.label(host, "API Key"))
        apiKey = W.input(host, "sk-...", prefs.getString("apiKey", "")!!)
        c.addView(apiKey)

        c.addView(W.label(host, "模型"))
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

        testResult = Ui.text(host, "", 11f, Ui.MUTED).apply {
            setPadding(0, dp(11), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        c.addView(testResult)
        return c
    }

    private fun saveAiQuietly() {
        prefs.edit()
            .putString(
                "baseUrl",
                baseUrl.text.toString().trim().ifBlank { "https://apihub.agnes-ai.com/v1" }
            )
            .putString("apiKey", apiKey.text.toString().trim())
            .putString("model", model.text.toString().trim().ifBlank { "agnes-2.5-flash" })
            .apply()
    }

    private fun saveAi() {
        prefs.edit()
            .putString(
                "baseUrl",
                baseUrl.text.toString().trim().ifBlank { "https://apihub.agnes-ai.com/v1" }
            )
            .putString("apiKey", apiKey.text.toString().trim())
            .putString("model", model.text.toString().trim().ifBlank { "agnes-2.5-flash" })
            .apply()
        host.toast("已保存")
    }

    private fun testAi() {
        val bu = baseUrl.text.toString().trim()
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
