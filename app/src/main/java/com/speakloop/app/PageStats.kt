package com.speakloop.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate

/**
 * 第二屏：统计。
 *
 * 数据全部来自 play_log，口径有三条，页面上不混着说：
 * - 「已练 N 条」= 这个月练过的**不同短语**数
 * - 「打卡率」= 这个月有练习的天数 / 已过去的天数
 * - 「练得最多」= 按 play_log 的行数排，同一短语一天只算一行
 *
 * 注意这里算的都不是「加了几条」——加了多少看第一屏的日期分组。
 */
class PageStats(private val host: MainActivity) {

    private val heatDays = 91

    val view: View
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout

    private fun dp(v: Int) = host.dp(v)

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
        refresh()
    }

    fun refresh() {
        val keepY = if (::scroll.isInitialized) scroll.scrollY else 0
        val today = Fmt.today()
        val monthStart = today.withDayOfMonth(1)
        val from = monthStart.toString()
        val to = today.toString()

        val db = Db(host)
        val monthCount: Int
        val streak: Int
        val byDay: Map<String, Int>
        val top: List<Pair<String, Int>>
        val totalPhrases: Int
        try {
            monthCount = db.distinctPlayedBetween(from, to)
            streak = db.streakDays(today)
            byDay = db.playCountByDay(today.minusDays((heatDays - 1).toLong()).toString(), to)
            top = db.topPlayed(from, to, 3)
            totalPhrases = db.count()
        } finally {
            db.close()
        }

        val elapsed = today.dayOfMonth
        val practicedDays = byDay.keys.count { d ->
            val parsed = runCatching { LocalDate.parse(d) }.getOrNull()
            parsed != null && !parsed.isBefore(monthStart) && !parsed.isAfter(today)
        }
        val totalTimes = byDay.values.sum()

        body.removeAllViews()
        body.addView(
            Ui.text(host, "近 $heatDays 天", Ui.T.SUB, Ui.DIM)
        )
        body.addView(Ui.text(host, "统计", Ui.T.H1, Ui.FG, true).apply { letterSpacing = -0.01f })

        body.addView(
            buildSummary(practicedDays, elapsed, monthCount, streak, totalTimes, totalPhrases),
            W.margin(host, top = 14)
        )
        body.addView(buildHeatmap(byDay), W.margin(host, top = 12))
        body.addView(buildTop(top), W.margin(host, top = 16))

        // 同短语页：旧 scrollY 可能超出新内容高度，越界就是一片空白
        scroll.post {
            val max = (body.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, keepY.coerceAtMost(max))
        }
    }

    private fun buildSummary(
        practicedDays: Int,
        elapsed: Int,
        monthCount: Int,
        streak: Int,
        totalTimes: Int,
        totalPhrases: Int
    ): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

        val rate = if (elapsed > 0) practicedDays.toFloat() / elapsed else 0f
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(RingView(host).apply {
            thicknessDp = 8f
            labelSizeDp = 15f
            progress = rate
            labelText = "${(rate * 100).toInt()}%"
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(70))
        })

        val right = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        right.addView(Ui.text(host, "本月已练", Ui.T.CAP, Ui.DIM))
        right.addView(
            Ui.text(host, "$monthCount 条", 20f, Ui.FG, true).apply { setPadding(0, dp(2), 0, 0) }
        )
        right.addView(
            Ui.text(host, "$elapsed 天里打卡 $practicedDays 天", Ui.T.CAP, Ui.MUTED)
                .apply { setPadding(0, dp(3), 0, 0) }
        )
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        card.addView(View(host).apply { setBackgroundColor(Ui.STROKE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(14)
            })

        val stats = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(cell("连续天数", "$streak 天"), weight())
        stats.addView(
            cell("平均每天", String.format("%.1f 条", if (elapsed > 0) monthCount.toFloat() / elapsed else 0f)),
            weight()
        )
        stats.addView(cell("练习次数", "$totalTimes 次"), weight())
        card.addView(stats, W.margin(host, top = 11))

        if (totalPhrases == 0) {
            card.addView(
                Ui.text(host, "短语库还是空的，先去加一条", Ui.T.CAP, Ui.MUTED)
                    .apply { setPadding(0, dp(10), 0, 0) }
            )
        }
        return card
    }

    private fun weight() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    )

    private fun cell(k: String, v: String): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(host, k, Ui.T.CAP, Ui.MUTED))
        col.addView(Ui.text(host, v, Ui.T.BODY, Ui.FG, true).apply { setPadding(0, dp(2), 0, 0) })
        return col
    }

    private fun buildHeatmap(byDay: Map<String, Int>): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(15), dp(16), dp(13)) }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            Ui.text(host, "练习记录", Ui.T.SUB, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(Ui.text(host, "颜色越深练得越多", Ui.T.CAP, Ui.MUTED))
        card.addView(head)

        val data = HashMap<LocalDate, Int>()
        for ((k, v) in byDay) {
            runCatching { LocalDate.parse(k) }.getOrNull()?.let { data[it] = v }
        }
        card.addView(
            HeatmapView(host).apply {
                setData(data, heatDays)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
        )

        val legend = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        legend.addView(
            Ui.text(host, "少", Ui.T.CAP, Ui.MUTED).apply {
                setPadding(0, 0, dp(5), 0)
            }
        )
        val soft = floatArrayOf(0.0f, 0.28f, 0.58f, 1.0f)
        for (t in soft) {
            legend.addView(View(host).apply {
                setBackgroundColor(blend(Ui.SURFACE_2, Ui.GOOD, t))
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    rightMargin = dp(4)
                }
            })
        }
        legend.addView(Ui.text(host, "多", Ui.T.CAP, Ui.MUTED))
        card.addView(legend, W.margin(host, top = 10))
        return card
    }

    private fun blend(a: Int, b: Int, t: Float): Int = android.graphics.Color.argb(
        (android.graphics.Color.alpha(a) +
            (android.graphics.Color.alpha(b) - android.graphics.Color.alpha(a)) * t).toInt(),
        (android.graphics.Color.red(a) +
            (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * t).toInt(),
        (android.graphics.Color.green(a) +
            (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * t).toInt(),
        (android.graphics.Color.blue(a) +
            (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * t).toInt()
    )

    private fun buildTop(top: List<Pair<String, Int>>): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            Ui.text(host, "练得最多", Ui.T.SUB, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(Ui.text(host, "本月", Ui.T.CAP, Ui.MUTED))
        col.addView(head)

        if (top.isEmpty()) {
            col.addView(
                Ui.text(host, "这个月还没练过，去「复习」走一轮", Ui.T.CAP, Ui.MUTED)
                    .apply { setPadding(0, dp(10), 0, 0) }
            )
            return col
        }

        val card = W.card(host)
        top.forEachIndexed { i, (text, n) ->
            if (i > 0) card.addView(Ui.divider(host, 40))
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(11), dp(15), dp(11))
            }
            row.addView(
                Ui.text(host, "${i + 1}", Ui.T.CAP, Ui.MUTED)
                    .apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            row.addView(
                Ui.text(host, text, 15f, Ui.FG).apply {
                    setPadding(dp(8), 0, 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Ui.text(host, "$n 次", Ui.T.CAP, Ui.DIM))
            card.addView(row)
        }
        col.addView(card, W.margin(host, top = 8))
        return col
    }
}
