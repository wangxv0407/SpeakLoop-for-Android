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
            body.addView(
                buildLibraryLabel(total),
                W.margin(host, top = 18)
            )
            renderGroups(phrases)
            body.addView(
                Ui.text(host, "点开可跟读 · 长按可删除", 11f, Ui.MUTED).apply {
                    gravity = Gravity.CENTER
                },
                W.margin(host, top = 14)
            )
        }
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
    }

    // ---------- 顶部 ----------

    private fun buildHeader(): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            Ui.text(host, Fmt.textWithWeek(Fmt.today()), 12f, Ui.DIM)
        )
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.text(host, "短语库", 25f, Ui.FG, true).apply { letterSpacing = -0.01f },
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

    private fun buildProgressCard(todayCount: Int, goal: Int, phrases: List<Phrase>): View {
        val card = W.card(host).apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }

        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            Ui.text(host, "今日已练", 12f, Ui.DIM),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(Ui.text(host, "目标 $goal 条", 11f, Ui.MUTED))
        card.addView(top)

        val middle = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        middle.addView(bigCount(todayCount, goal))

        val ring = RingView(host).apply {
            thicknessDp = 7f
            labelSizeDp = 12f
            labelText = "${(todayCount * 100 / goal.coerceAtLeast(1)).coerceAtMost(100)}%"
            progress = todayCount.toFloat() / goal.coerceAtLeast(1)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(16) }
        }
        middle.addView(ring)

        val tip = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        val left = goal - todayCount
        tip.addView(
            Ui.text(
                host,
                if (left > 0) "再练 $left 条" else "今天已达成",
                11f,
                if (left > 0) Ui.DIM else Ui.GOOD
            )
        )
        tip.addView(
            Ui.text(host, if (left > 0) "就达成今天" else "干得不错", 11f, Ui.MUTED)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        middle.addView(tip)
        card.addView(middle, W.margin(host, top = 6))

        card.addView(View(host).apply {
            setBackgroundColor(Ui.STROKE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(13)
        })

        val stats = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val today = Fmt.today()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = phrases.count {
            val d = dateOf(it)
            !d.isBefore(weekStart) && !d.isAfter(today)
        }
        stats.addView(statCell("本周", "+$thisWeek 条"), cellParams(1f))
        stats.addView(statCell("连续", "${streakOf(phrases)} 天"), cellParams(1f))
        stats.addView(statCell("总计", "${phrases.size} 条"), cellParams(1f))
        card.addView(stats, W.margin(host, top = 10))
        return card
    }

    private fun cellParams(weight: Float) = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, weight
    )

    private fun statCell(k: String, v: String): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(host, k, 11f, Ui.MUTED))
        col.addView(
            Ui.text(host, v, 14f, Ui.FG, true).apply { setPadding(0, dp(2), 0, 0) }
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
        return Ui.text(host, sb, 30f, Ui.FG, true)
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
            Ui.text(host, "短语库", 13f, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(Ui.text(host, "$total 条", 11f, Ui.MUTED))
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
        row.addView(Ui.text(host, b.title, 13f, Ui.FG, true))
        row.addView(
            Ui.text(host, b.subtitle, 11f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) }
        )
        row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(host, "${b.items.size} 条", 11f, Ui.MUTED))
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
                confirmDelete(p)
                true
            }
        }

        row.addView(badge(p))
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        col.addView(Ui.text(host, p.phrase, 14f, Ui.FG, true))
        if (p.meaning.isNotBlank()) {
            col.addView(
                Ui.text(host, p.meaning, 11f, Ui.DIM).apply {
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
            Ui.text(host, "还没有短语", 16f, Ui.FG, true).apply { gravity = Gravity.CENTER }
        )
        col.addView(
            Ui.text(host, "点右上角 ＋ 加一条\nAI 会顺手补上释义、例句和情景对话", 12f, Ui.MUTED)
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
