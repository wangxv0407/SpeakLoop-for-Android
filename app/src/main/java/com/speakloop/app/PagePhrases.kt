package com.speakloop.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 第一屏：短语库。
 *
 * 顺序是「今天练得怎么样 → 有哪些短语」，所以顶部先放今日进度卡，
 * 下面才按日期分组。分组的意义是回看「我哪天加了什么」，
 * 所以默认只收「本周更早」和「更早」，今天和昨天不用点开就能看到。
 */
class PagePhrases(private val host: MainActivity) {

    private class Bucket(
        val key: String,
        val title: String,
        val subtitle: String,
        val items: List<Phrase>
    )

    val view: View

    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private val collapsed = mutableSetOf("week", "older")

    /** 列表筛选：0 全部 / 1 没练过 / 2 已练 */
    private var filter = 0

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
        val keepY = if (::body.isInitialized) scroll.scrollY else 0
        val db = Db(host)
        val phrases: List<Phrase>
        val todayCount: Int
        val goal: Int
        val total: Int
        try {
            phrases = db.all()
            todayCount = db.playedCountOn(Fmt.key(Fmt.today()))
            goal = host.prefs.getInt("dailyGoal", 5)
            total = phrases.size
        } finally {
            db.close()
        }

        body.removeAllViews()
        body.addView(buildHeader())
        body.addView(buildProgressCard(todayCount, goal, phrases), W.margin(host, top = 14))

        if (phrases.isEmpty()) {
            body.addView(buildEmpty(), W.margin(host, top = 60))
        } else {
            body.addView(buildFilterRow(), W.margin(host, top = 14))
            val shown = when (filter) {
                1 -> phrases.filter { it.playCount == 0 }
                2 -> phrases.filter { it.playCount > 0 }
                else -> phrases
            }
            if (shown.isEmpty()) {
                body.addView(
                    Ui.text(
                        host,
                        if (filter == 1) "没有没练过的了，去「复习」走一轮" else "还没有练过的",
                        Ui.T.SUB, Ui.MUTED
                    ).apply { gravity = Gravity.CENTER },
                    W.margin(host, top = 24)
                )
            } else {
                body.addView(
                    buildLibraryLabel(shown.size),
                    W.margin(host, top = 18)
                )
                renderGroups(shown)
                body.addView(
                    Ui.text(host, "点开可跟读 · 长按可改可删", Ui.T.CAP, Ui.MUTED).apply {
                        gravity = Gravity.CENTER
                    },
                    W.margin(host, top = 14)
                )
            }
        }
        // 恢复滚动位置时必须按新内容高度夹一下：页面切走期间复习会改 play_count，
        // 列表按次数重排、总高度变了，旧的 scrollY 可能已经落在内容之外 —— 那一片就是空白。
        scroll.post {
            val max = (body.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, if (keepY > 0) keepY.coerceAtMost(max) else 0)
        }
    }

    // ---------- 顶部 ----------

    private fun buildHeader(): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            Ui.text(host, Fmt.textWithWeek(Fmt.today()), Ui.T.SUB, Ui.DIM)
        )
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.text(host, "短语库", Ui.T.H1, Ui.FG, true).apply { letterSpacing = -0.01f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(addButton())
        col.addView(row, W.margin(host, top = 2))
        return col
    }

    /** 右上角白圆 + 加号，和参考图一致。比右下角悬浮按钮更不挡内容。 */
    private fun addButton(): View = TextView(host).apply {
        text = "+"
        textSize = 22f
        setTextColor(Ui.FG)
        includeFontPadding = false
        gravity = Gravity.CENTER
        background = Ui.ripple(host, 19, Ui.SURFACE, 1, Ui.STROKE)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        isClickable = true
        setOnClickListener { host.showAddDialog() }
    }

    // ---------- 今日进度卡 ----------

    /**
     * 今日目标卡：三行 —— 标签+数值 / 进度条 / 提示+去复习。
     * 原来的大数字 + 圆环 + 三格统计占了大半屏，列表被挤到第二屏；
     * 换成这条窄卡之后首屏能多露一条短语，关键信息一条不少。
     */
    private fun buildProgressCard(todayCount: Int, goal: Int, phrases: List<Phrase>): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }

        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            Ui.text(host, "今日目标", Ui.T.SUB, Ui.DIM),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(Ui.text(host, "$todayCount / $goal 条", Ui.T.SUB, Ui.FG, true))
        card.addView(top)

        // 进度条：达成了就整条变绿，跟「播放/跟读」的语义一致
        val frac = (todayCount.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            background = Ui.shape(host, 3, Ui.SURFACE_2)
        }
        if (frac > 0f) {
            bar.addView(View(host).apply {
                setBackgroundColor(if (frac >= 1f) Ui.GOOD else Ui.ACCENT)
                layoutParams = LinearLayout.LayoutParams(0, dp(6), frac)
            })
        }
        if (frac < 1f) {
            bar.addView(View(host).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f - frac)
            })
        }
        card.addView(bar)

        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val left = (goal - todayCount).coerceAtLeast(0)
        bottom.addView(
            Ui.text(
                host,
                if (left > 0) "还有 $left 条就达成" else "今天已达成",
                Ui.T.SUB,
                if (left > 0) Ui.DIM else Ui.GOOD
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bottom.addView(
            Ui.text(host, "去复习 →", Ui.T.SUB, Ui.ACCENT, true).apply {
                isClickable = true
                setOnClickListener { host.goTab(2) }
            }
        )
        card.addView(bottom)

        // 原来三格统计的信息合并成一行小字，不丢数据也不占地
        val today = Fmt.today()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = phrases.count {
            val d = dateOf(it)
            !d.isBefore(weekStart) && !d.isAfter(today)
        }
        card.addView(View(host).apply {
            setBackgroundColor(Ui.STROKE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(13)
        })
        card.addView(
            Ui.text(
                host,
                "本周 +$thisWeek 条 · 连续 ${streakOf(phrases)} 天 · 共 ${phrases.size} 条",
                Ui.T.CAP, Ui.MUTED
            ),
            W.margin(host, top = 10)
        )
        return card
    }

    /** 筛选胶囊：0 全部 / 1 没练过 / 2 已练。短语过百之后靠它找人。 */
    private fun buildFilterRow(): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("全部", "没练过", "已练").forEachIndexed { i, label ->
            val pill = Ui.pill(host, label, filter == i) {
                if (filter != i) {
                    filter = i
                    refresh()
                }
            }
            if (i > 0) row.addView(pill, W.margin(host, left = 8)) else row.addView(pill)
        }
        return row
    }

    private fun cellParams(weight: Float) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, weight
    )

    private fun statCell(k: String, v: String): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(host, k, Ui.T.CAP, Ui.MUTED))
        col.addView(
            Ui.text(host, v, Ui.T.BODY, Ui.FG, true).apply { setPadding(0, dp(2), 0, 0) }
        )
        return col
    }

    /** "2 / 5"：分子大、分母小，一眼看出还差多少。 */
    private fun bigCount(done: Int, goal: Int): TextView {
        val sb = SpannableStringBuilder()
        sb.append(done.toString())
        sb.setSpan(StyleSpan(Typeface.NORMAL), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append("/").append(goal.toString())
        sb.setSpan(RelativeSizeSpan(0.5f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(Ui.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return Ui.text(host, sb, 34f, Ui.FG, true)
    }

    /** 连续记录天数按「加短语的日期」算，和统计页按练习算的口径不同。 */
    private fun streakOf(phrases: List<Phrase>): Int {
        val days = phrases.map { dateOf(it) }.toSet()
        if (days.isEmpty()) return 0
        val today = Fmt.today()
        var cursor = if (days.contains(today)) today else today.minusDays(1)
        var n = 0
        while (days.contains(cursor)) {
            n++
            cursor = cursor.minusDays(1)
        }
        return n
    }

    // ---------- 分组 ----------

    private fun buildLibraryLabel(total: Int): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.text(host, "短语库", Ui.T.SUB, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(Ui.text(host, "$total 条", Ui.T.CAP, Ui.MUTED))
        return row
    }

    private fun renderGroups(phrases: List<Phrase>) {
        val buckets = buildBuckets(phrases)
        buckets.forEachIndexed { i, b ->
            val open = !collapsed.contains(b.key)
            body.addView(groupHeader(b, open), W.margin(host, top = if (i == 0) 12 else 16))
            if (!open) return@forEachIndexed

            val card = W.card(host)
            b.items.forEachIndexed { idx, p ->
                if (idx > 0) card.addView(Ui.divider(host, 66))
                card.addView(phraseRow(p))
            }
            body.addView(card, W.margin(host, top = 8))
        }
    }

    private fun groupHeader(b: Bucket, open: Boolean): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
            setOnClickListener {
                if (!collapsed.remove(b.key)) collapsed.add(b.key)
                refresh()
            }
        }
        row.addView(Ui.text(host, b.title, Ui.T.SUB, Ui.FG, true))
        row.addView(
            Ui.text(host, b.subtitle, Ui.T.CAP, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) }
        )
        row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(host, "${b.items.size} 条", Ui.T.CAP, Ui.MUTED))
        row.addView(
            Ui.icon(
                host,
                if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron,
                Ui.MUTED,
                12
            ),
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { leftMargin = dp(5) }
        )
        return row
    }

    private fun phraseRow(p: Phrase): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(12), dp(11))
            background = Ui.ripple(host, 20, Ui.SURFACE, 0, Ui.STROKE)
            isClickable = true
            setOnClickListener { host.openDetail(p.id) }
            setOnLongClickListener {
                showRowMenu(p)
                true
            }
        }

        row.addView(badge(p))
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        col.addView(Ui.text(host, p.phrase, Ui.T.BODY, Ui.FG, true))
        if (p.meaning.isNotBlank()) {
            col.addView(
                Ui.text(host, p.meaning, Ui.T.SUB, Ui.DIM).apply {
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (p.playCount > 0) {
            row.addView(Ui.chip(host, "${p.playCount} 次", Ui.GOOD, Ui.GOOD_SOFT))
        } else {
            row.addView(Ui.chip(host, "没练过", Ui.MUTED, Ui.SURFACE_2))
        }
        row.addView(
            Ui.text(host, "›", 16f, Ui.MUTED),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
        )
        return row
    }

    private fun badge(p: Phrase): View {
        val letter = p.phrase.trim().take(1).uppercase().ifBlank { "?" }
        return TextView(host).apply {
            text = letter
            textSize = 15f
            setTextColor(Ui.ON_BADGE)
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = Ui.shape(host, 13, Ui.BADGE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
    }

    private fun buildEmpty(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(
            Ui.text(host, "还没有短语", Ui.T.TITLE, Ui.FG, true).apply { gravity = Gravity.CENTER }
        )
        col.addView(
            Ui.text(host, "点右上角 ＋ 加一条\nAI 会顺手补上释义、例句和情景对话", Ui.T.SUB, Ui.MUTED)
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                    setLineSpacing(dp(5).toFloat(), 1f)
                }
        )
        return col
    }

    // ---------- 分组计算 ----------

    private fun dateOf(p: Phrase): LocalDate =
        Instant.ofEpochMilli(if (p.createdAt > 0L) p.createdAt else 0L)
            .atZone(Fmt.zone())
            .toLocalDate()

    /**
     * 分桶只比日期，不比时间戳：跨时区、夏令时交给 java.time 处理。
     * 用 SQL 的 date(...,'unixepoch','localtime') 在边界上会偏一天。
     */
    private fun buildBuckets(phrases: List<Phrase>): List<Bucket> {
        val today = Fmt.today()
        val yesterday = today.minusDays(1)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val groups = LinkedHashMap<String, MutableList<Phrase>>()
        val days = HashMap<String, MutableList<LocalDate>>()
        for (p in phrases.sortedByDescending { it.createdAt }) {
            val d = dateOf(p)
            val key = when {
                d.isEqual(today) -> "today"
                d.isEqual(yesterday) -> "yesterday"
                !d.isBefore(weekStart) -> "week"
                else -> "older"
            }
            groups.getOrPut(key) { ArrayList() }.add(p)
            days.getOrPut(key) { ArrayList() }.add(d)
        }

        val out = ArrayList<Bucket>()
        for (key in listOf("today", "yesterday", "week", "older")) {
            val items = groups[key] ?: continue
            val ds = days[key] ?: continue
            val sorted = ds.sorted()
            val title = when (key) {
                "today" -> "今天"
                "yesterday" -> "昨天"
                "week" -> "本周更早"
                else -> "更早"
            }
            val sub = when (key) {
                "today" -> Fmt.text(today)
                "yesterday" -> Fmt.text(yesterday)
                "week" -> Fmt.range(sorted.first(), sorted.last())
                else -> "${Fmt.text(sorted.first())}之前"
            }
            out.add(Bucket(key, title, sub, items))
        }
        return out
    }

    /**
     * 长按列表项：改 / 删。
     * 原来是长按直接问「删除吗」，输错一个字只有重加一条路，练习记录也跟着丢。
     */
    private fun showRowMenu(p: Phrase) {
        val dialog = AlertDialog.Builder(host)
            .setTitle(p.phrase)
            .setItems(arrayOf("改原文和释义", "删除")) { _, which ->
                if (which == 0) {
                    W.editPhraseDialog(host, p) { refresh() }
                } else {
                    confirmDelete(p)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }

    private fun confirmDelete(p: Phrase) {
        val dialog = AlertDialog.Builder(host)
            .setTitle("删除「${p.phrase}」？")
            .setMessage("删掉就找不回来了，练习记录也会一起清掉。")
            .setPositiveButton("删除") { _, _ ->
                val db = Db(host)
                try {
                    db.delete(p.id)
                } finally {
                    db.close()
                }
                refresh()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }
}
