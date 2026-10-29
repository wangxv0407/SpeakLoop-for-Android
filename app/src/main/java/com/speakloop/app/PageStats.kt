package com.speakloop.app

import android.graphics.drawable.GradientDrawable
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
            Ui.text(host, "练习概览", Ui.T.SUB, Ui.DIM)
        )
        body.addView(Ui.text(host, "统计", Ui.T.H1, Ui.FG, true).apply { letterSpacing = -0.01f })

        body.addView(
            buildSummary(practicedDays, elapsed, monthCount, streak, totalTimes, totalPhrases, byDay),
            W.margin(host, top = 14)
        )
        body.addView(buildMonthHeatmap(byDay), W.margin(host, top = 12))
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
        totalPhrases: Int,
        byDay: Map<String, Int>
    ): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

        // 两个 KPI 并排：连续打卡用绿色，和播放态一个语义 —— 都表示「这件事在持续」
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            kpi("本月已练", "$monthCount 条", Ui.FG),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            kpi("连续打卡", "$streak 天", Ui.GOOD),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(row)

        card.addView(View(host).apply { setBackgroundColor(Ui.STROKE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(14)
            })

        // 8 周趋势 + 一行明细小字，原 RingView 占 70dp 高只表达一个百分比，性价比太低
        card.addView(
            Ui.text(host, "近 8 周趋势", Ui.T.SUB, Ui.FG, true),
            W.margin(host, top = 13)
        )
        card.addView(buildTrend(byDay), W.margin(host, top = 4))
        card.addView(
            Ui.text(
                host,
                "本月共 $totalTimes 次 · 平均每天 ${
                    String.format("%.1f", if (elapsed > 0) monthCount.toFloat() / elapsed else 0f)
                } 条 · 打卡 $practicedDays/$elapsed 天",
                Ui.T.CAP, Ui.MUTED
            ),
            W.margin(host, top = 10)
        )

        if (totalPhrases == 0) {
            card.addView(
                Ui.text(host, "短语库还是空的，先去加一条", Ui.T.CAP, Ui.MUTED)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
        }
        return card
    }

    private fun kpi(label: String, value: String, color: Int): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(host, label, Ui.T.CAP, Ui.MUTED))
        col.addView(
            Ui.text(host, value, Ui.T.TITLE, color, true).apply { setPadding(0, dp(4), 0, 0) }
        )
        return col
    }

    /**
     * 近 8 周的练习次数柱状图。每周高度 = 该周 play_log 行数 / 最高周。
     * 最后一周没过完，只算到今天，避免「这周突然变矮」的错觉。
     */
    private fun buildTrend(byDay: Map<String, Int>): View {
        val today = Fmt.today()
        val thisMonday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val weeks = (7 downTo 0).map { w ->
            val end = thisMonday.minusWeeks(w.toLong())
            val realEnd = if (end.isAfter(today)) today else end
            val start = end.minusDays(6)
            var sum = 0
            var d = start
            while (!d.isAfter(realEnd)) {
                sum += byDay[d.toString()] ?: 0
                d = d.plusDays(1)
            }
            sum
        }
        val maxV = weeks.maxOrNull()?.coerceAtLeast(1) ?: 1

        val barH = dp(52)
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        weeks.forEachIndexed { i, v ->
            val col = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
            }
            val h = if (v > 0) {
                ((v.toFloat() / maxV) * barH).toInt().coerceAtLeast(dp(3))
            } else dp(2)
            col.addView(View(host).apply {
                setBackgroundColor(
                    if (i == weeks.size - 1) Ui.ACCENT
                    else blend(Ui.SURFACE_2, Ui.ACCENT, 0.45f)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h
                )
            })
            row.addView(
                col,
                LinearLayout.LayoutParams(0, barH, 1f).apply {
                    if (i < weeks.size - 1) marginEnd = dp(6)
                }
            )
        }
        return row
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

    /**
     * 本月日历视图：每天一格、格上带日期数字，按当天练习次数着色。
     * 之前是 91 天无标注矩阵，用户不知道每个方块是哪天 —— 日历一眼就能对上号。
     */
    private fun buildMonthHeatmap(byDay: Map<String, Int>): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        val today = Fmt.today()
        val monthStart = today.withDayOfMonth(1)
        val daysInMonth = monthStart.lengthOfMonth()

        val data = HashMap<LocalDate, Int>()
        for ((k, v) in byDay) {
            runCatching { LocalDate.parse(k) }.getOrNull()?.let { data[it] = v }
        }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            Ui.text(host, "本月练习", Ui.T.SUB, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(Ui.text(host, "${today.monthValue} 月 · 按次数着色", Ui.T.CAP, Ui.MUTED))
        card.addView(head)

        // 周一 → 周日 的表头
        val wk = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        for (label in listOf("一", "二", "三", "四", "五", "六", "日")) {
            wk.addView(
                Ui.text(host, label, Ui.T.CAP, Ui.MUTED).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        card.addView(wk, W.margin(host, top = 12))

        // 日期网格：1 号前置空位，每格 38dp 高、7 等分宽
        val grid = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        var day = 1 - (monthStart.dayOfWeek.value - 1) // 周一=1 → 前置 0 个空位
        while (day <= daysInMonth) {
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 7) {
                val d = day + c
                val p = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (c < 6) marginEnd = dp(3)
                }
                if (d < 1 || d > daysInMonth) {
                    row.addView(View(host), p)
                    continue
                }
                val date = monthStart.plusDays((d - 1).toLong())
                val n = data[date] ?: 0
                val (bg, fg) = when {
                    n == 0 -> Ui.SURFACE_2 to Ui.MUTED
                    n <= 2 -> blend(Ui.SURFACE_2, Ui.GOOD, 0.30f) to Ui.FG
                    n <= 4 -> blend(Ui.SURFACE_2, Ui.GOOD, 0.55f) to Ui.FG
                    else -> Ui.GOOD to Ui.ON_PLAY
                }
                val cell = Ui.text(host, "$d", Ui.T.CAP, fg, n > 0).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(bg)
                        cornerRadius = dp(9).toFloat()
                        if (date == today) setStroke(dp(2), Ui.ACCENT)
                    }
                }
                row.addView(cell, p)
            }
            day += 7
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            )
        }
        card.addView(grid, W.margin(host, top = 3))
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
