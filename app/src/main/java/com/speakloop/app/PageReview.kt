package com.speakloop.app

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.time.LocalDate

/**
 * 第三屏：复习。
 *
 * 这一屏是给骑车准备的，所以信息量故意压到最低：
 * 大卡片里只放「正在读的这句」，下面一个大按钮，别的都是次要的。
 *
 * 状态全部来自 [ReviewBus]，它是前台服务发的快照；这一页自己不持有播放逻辑，
 * 只负责把快照画出来、把点击转成服务指令。这样息屏、切走、回来都不会丢状态。
 */
class PageReview(private val host: MainActivity) : ReviewBus.Listener {

    val view: View

    private lateinit var counter: TextView
    private lateinit var scope: TextView
    private lateinit var fill: View
    private lateinit var track: View

    private lateinit var phraseText: TextView
    private lateinit var meaningText: TextView

    /** 当前短语要读的全部行；每行留一组引用，切行时只改颜色不重建 */
    private class RowRefs(val bar: View, val tag: TextView, val en: TextView, val cnTv: TextView)

    private lateinit var lineBox: LinearLayout
    private lateinit var stateLine: TextView
    private val rows = ArrayList<RowRefs>()
    private var lastLines: List<ReviewBus.Line> = emptyList()

    private lateinit var playCircle: LinearLayout
    private lateinit var playIcon: ImageView
    private lateinit var playLabel: TextView
    private lateinit var stopBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var hint: TextView

    private lateinit var dayChip: TextView
    private lateinit var chipsRow: LinearLayout

    private var selectedDay: LocalDate = Fmt.today()

    private fun dp(v: Int) = host.dp(v)

    init {
        view = build()
        ReviewBus.add(this)
    }

    /** 页面离开时由宿主调用，避免 Activity 销毁后还收到状态回调。 */
    fun detach() = ReviewBus.remove(this)

    fun refresh() {
        renderChips()
        onReviewState(ReviewBus.state)
    }

    // ---------- 骨架 ----------

    private fun build(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

        root.addView(buildTop())
        root.addView(buildCard(), W.margin(host, top = 12))
        root.addView(buildLineList(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(10) })

        stateLine = Ui.text(host, "", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(stateLine)
        root.addView(buildPlayButton(), W.margin(host, top = 10))
        hint = Ui.text(host, "点开始，锁屏也会继续读", 10f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }
        root.addView(hint, W.margin(host, top = 9))
        chipsRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(chipsRow, W.margin(host, top = 12))
        renderChips()
        return root
    }

    private fun buildTop(): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        counter = Ui.text(host, "准备就绪", 12f, Ui.DIM)
        scope = Ui.text(host, "", 11f, Ui.MUTED)

        val left = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(counter)
        left.addView(scope, W.wrap(host, left = 8))
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buildDayChip())
        col.addView(row)

        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
            background = Ui.shape(host, 2, Ui.SURFACE_2)
        }
        fill = View(host).apply {
            setBackgroundColor(Ui.ACCENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 0f)
        }
        track = View(host).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
        }
        bar.addView(fill)
        bar.addView(track)
        col.addView(bar)
        return col
    }

    private fun buildDayChip(): TextView {
        dayChip = Ui.text(host, "", 11f, Ui.FG, true)
        dayChip.setPadding(dp(11), dp(6), dp(11), dp(6))
        dayChip.background = Ui.ripple(host, 11, Ui.SURFACE, 1, Ui.STROKE)
        dayChip.isClickable = true
        dayChip.setOnClickListener { pickDay() }
        return dayChip
    }

    private fun buildCard(): View {
        val card = W.card(host, 22).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }

        card.addView(Ui.text(host, "短语", 10f, Ui.ACCENT, true).apply { letterSpacing = 0.02f })
        phraseText = Ui.text(host, "还没开始", 24f, Ui.FG, true).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
            letterSpacing = -0.01f
        }
        card.addView(phraseText)
        meaningText = Ui.text(host, "", 13f, Ui.DIM).apply { setPadding(0, dp(5), 0, 0) }
        card.addView(meaningText)
        return card
    }

    /**
     * 下方摊开这条短语要读的全部内容（短语两遍 / 例句 / 对话 / 收尾）。
     * 骑车时能一眼看到「还有几句」，留白时高亮的那行就是该你跟读的。
     */
    private fun buildLineList(): View {
        val scroll = ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        lineBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        scroll.addView(lineBox)
        return scroll
    }

    private fun addLineRow(line: ReviewBus.Line): RowRefs {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val bar = View(host).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val tag = Ui.text(host, line.tag, 9f, Ui.MUTED, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        val en = Ui.text(host, line.text, 14f, Ui.FG).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        col.addView(en)
        val cnTv = Ui.text(host, line.cn, 11f, Ui.MUTED).apply {
            setPadding(0, dp(3), 0, 0)
            visibility = if (line.cn.isBlank()) View.GONE else View.VISIBLE
        }
        col.addView(cnTv)

        row.addView(bar)
        row.addView(tag)
        row.addView(
            col,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        lineBox.addView(row)
        return RowRefs(bar, tag, en, cnTv)
    }

    private fun buildPlayButton(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // 播放中才出现的三个小按钮：跳句 + 结束。内边距压到 12dp，
        // 360dp 宽的屏上也塞得下，不会把中间的暂停键挤成一条缝。
        fun sideBtn(label: String, color: Int, onClick: () -> Unit) = Ui.text(host, label, 13f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = Ui.ripple(host, 18, Ui.SURFACE, 1, Ui.STROKE)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { onClick() }
        }

        prevBtn = sideBtn("上一句", Ui.DIM) {
            ReviewService.command(host, ReviewService.ACTION_PREV)
        }
        nextBtn = sideBtn("下一句", Ui.DIM) {
            ReviewService.command(host, ReviewService.ACTION_NEXT)
        }

        playCircle = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Ui.ripple(host, 18, Ui.SOLID, 0, Ui.STROKE)
            isClickable = true
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { onPlayClick() }
        }
        playIcon = Ui.icon(host, R.drawable.ic_play, Ui.ON_SOLID, 18)
        playLabel = Ui.text(host, "开始复习", 15f, Ui.ON_SOLID, true)
        playCircle.addView(playIcon)
        playCircle.addView(playLabel, W.wrap(host, left = 8))

        row.addView(prevBtn, sideParams(0))
        row.addView(
            playCircle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        )
        row.addView(nextBtn, sideParams(8))

        // 播放中只给「暂停」会让人没法退出来，所以旁边常备一个「结束」
        stopBtn = sideBtn("结束", Ui.BAD) {
            ReviewService.command(host, ReviewService.ACTION_STOP)
        }
        row.addView(stopBtn, sideParams(8))
        return row
    }

    private fun sideParams(leftDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(leftDp) }

    private fun renderChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        val phrasesOnly = host.prefs.getBoolean("phrasesOnly", false)
        val loop = host.prefs.getBoolean("loop", false)
        chipsRow.addView(
            Ui.pill(host, "只读短语", phrasesOnly) { togglePref("phrasesOnly") },
            W.wrap(host)
        )
        chipsRow.addView(
            Ui.pill(host, "循环", loop) { togglePref("loop") },
            W.wrap(host, left = 6)
        )
        chipsRow.addView(
            Ui.pill(host, "语速 ${rateText()}", false) { pickRate() },
            W.wrap(host, left = 6)
        )
    }

    private fun rateText(): String = String.format("%.2f", host.prefs.getFloat("rate", 1f))
        .trimEnd('0').trimEnd('.')

    private fun togglePref(key: String) {
        val v = !host.prefs.getBoolean(key, false)
        host.prefs.edit().putBoolean(key, v).apply()
        renderChips()
        if (key == "loop") Unit
    }

    // ---------- 点击 ----------

    private fun onPlayClick() {
        val s = ReviewBus.state
        if (s.running) {
            ReviewService.command(host, ReviewService.ACTION_TOGGLE)
            host.toast(if (s.paused) "继续" else "已暂停")
            return
        }
        startRound()
    }

    private fun startRound() {
        val i = Intent(host, ReviewService::class.java)
            .putExtra(ReviewService.EXTRA_DAY, selectedDay.toString())
            .putExtra(
                ReviewService.EXTRA_INCLUDE_DUE,
                host.prefs.getBoolean("includeDue", true)
            )
            .putExtra(
                ReviewService.EXTRA_PHRASES_ONLY,
                host.prefs.getBoolean("phrasesOnly", false)
            )
            .putExtra(ReviewService.EXTRA_LIMIT, host.prefs.getInt("queueLimit", 15))
        ReviewService.start(host, i)
    }

    /** 日期选择：只列「有短语的那几天」，不给一个空日历让人瞎点。 */
    private fun pickDay() {
        val db = Db(host)
        val days: List<DayCount>
        try {
            days = db.daysWithPhrases()
        } finally {
            db.close()
        }
        if (days.isEmpty()) {
            host.toast("还没有短语")
            return
        }

        val labels = ArrayList<String>()
        val dates = ArrayList<LocalDate>()
        for (d in days) {
            labels.add("${Fmt.friendly(d.date)}   ${Fmt.text(d.date)} · ${d.count} 条")
            dates.add(d.date)
        }
        val checked = dates.indexOfFirst { it.isEqual(selectedDay) }

        val dialog = AlertDialog.Builder(host)
            .setTitle("复习哪一天加的短语")
            .setSingleChoiceItems(Ui.dialogItems(labels), checked) { d, which ->
                selectedDay = dates[which]
                updateDayChip()
                d.dismiss()
                // 换范围要按新范围重开一轮，否则用户会以为换了却还在读旧的
                if (ReviewBus.state.running) ReviewService.command(host, ReviewService.ACTION_STOP)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }

    private fun pickRate() {
        val values = arrayOf(0.7f, 0.85f, 1f, 1.15f, 1.3f)
        // 标签写死，不用 String.format：既躲开 locale 差异，也保证一定是非空字符串
        val labels = listOf("0.7×  慢", "0.85×", "1.0×  标准", "1.15×", "1.3×  快")
        val cur = values.indexOfFirst { kotlin.math.abs(it - host.prefs.getFloat("rate", 1f)) < 0.01f }
        val dialog = AlertDialog.Builder(host)
            .setTitle("朗读语速")
            .setSingleChoiceItems(Ui.dialogItems(labels), cur) { d, which ->
                host.prefs.edit().putFloat("rate", values[which]).apply()
                d.dismiss()
                renderChips()
                host.toast(
                    if (ReviewBus.state.running) "语速改了，下一轮复习生效"
                    else "已设为 ${labels[which]}"
                )
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, host)
    }

    private fun updateDayChip() {
        if (!::dayChip.isInitialized) return
        val label = if (selectedDay.isEqual(Fmt.today())) {
            "今天"
        } else {
            Fmt.friendly(selectedDay)
        }
        dayChip.text = "$label ▾"
    }

    // ---------- 状态渲染 ----------

    override fun onReviewState(s: ReviewBus.State) {
        if (!::counter.isInitialized) return

        // 顶栏
        if (s.running && s.phraseTotal > 0) {
            counter.text = "第 ${s.phraseIndex} / ${s.phraseTotal} 条"
            counter.setTextColor(Ui.DIM)
        } else {
            counter.text = "准备就绪"
            counter.setTextColor(Ui.DIM)
        }
        scope.text = s.scopeLabel.ifBlank { dueHint() }
        updateDayChip()

        val frac = if (s.stepTotal > 0) {
            (s.stepIndex.toFloat() / s.stepTotal).coerceIn(0f, 1f)
        } else 0f
        (fill.layoutParams as LinearLayout.LayoutParams).let {
            it.weight = if (frac <= 0f) 0f else frac
            fill.layoutParams = it
        }
        (track.layoutParams as LinearLayout.LayoutParams).let {
            it.weight = 1f - frac
            track.layoutParams = it
        }

        // 卡片：只负责「在练哪一条」。释义取第一行（短语本身）的中文
        val meaning = s.lines.firstOrNull()?.cn.orEmpty()
        if (s.phrase.isNotBlank()) {
            phraseText.text = s.phrase
            meaningText.text = meaning
            meaningText.visibility = if (meaning.isBlank()) View.GONE else View.VISIBLE
        } else {
            phraseText.text = "还没开始"
            meaningText.text = ""
            meaningText.visibility = View.GONE
        }

        // 下方的整条内容清单 + 当前行高亮
        renderLines(s)

        stateLine.text = when {
            !s.running -> dayTitle()
            s.paused -> "已暂停 · 点下面「继续」"
            s.gap -> "该你了 · ${(s.remainMs + 999) / 1000} 秒"
            else -> "正在朗读…"
        }
        stateLine.setTextColor(if (s.gap) Ui.GOOD else Ui.MUTED)
        stateLine.visibility = if (stateLine.text.isBlank()) View.GONE else View.VISIBLE

        // 大按钮
        if (s.running) {
            playIcon.setImageResource(
                if (s.paused) R.drawable.ic_play else R.drawable.ic_pause
            )
            playLabel.text = if (s.paused) "继续" else "暂停"
        } else {
            playIcon.setImageResource(R.drawable.ic_play)
            playLabel.text = "开始复习"
        }
        // 跳句和结束只在播放中出现：还没开始时它们没有意义，只会让首页显得吵
        val side = if (s.running) View.VISIBLE else View.GONE
        if (::stopBtn.isInitialized) stopBtn.visibility = side
        if (::prevBtn.isInitialized) prevBtn.visibility = side
        if (::nextBtn.isInitialized) nextBtn.visibility = side

        hint.text = if (s.running) {
            "第 ${s.stepIndex} / ${s.stepTotal} 句 · 耳机线控可暂停"
        } else {
            "点开始，锁屏也会继续读"
        }
    }

    /**
     * 只在「换短语 / 换行」时重建行视图，倒数每 100ms 只改颜色和一行状态文字，
     * 否则每个 tick 都重建 7 个 View，界面会抖。
     */
    private fun renderLines(s: ReviewBus.State) {
        if (!::lineBox.isInitialized) return
        if (s.lines !== lastLines || rows.size != s.lines.size) {
            lastLines = s.lines
            rows.clear()
            lineBox.removeAllViews()
            s.lines.forEach { rows.add(addLineRow(it)) }
        }
        rows.forEachIndexed { i, r ->
            val current = i == s.cursor
            val active = if (s.gap) Ui.GOOD else Ui.HILITE
            r.bar.setBackgroundColor(if (current) active else android.graphics.Color.TRANSPARENT)
            r.en.setTextColor(if (current) active else Ui.FG)
            r.cnTv.setTextColor(if (current) Ui.DIM else Ui.MUTED)
            r.tag.setTextColor(if (current) active else Ui.MUTED)
        }
    }

    private fun dayTitle(): String {
        val d = selectedDay
        if (d.isEqual(Fmt.today())) return "今天还没开始"
        return "${Fmt.friendly(d)}的 ${countOn(d)} 条"
    }

    private fun countOn(d: LocalDate): Int {
        val db = Db(host)
        return try {
            db.addedOn(d2ms(d), d2ms(d.plusDays(1))).size
        } finally {
            db.close()
        }
    }

    private fun d2ms(d: LocalDate): Long =
        d.atStartOfDay(Fmt.zone()).toInstant().toEpochMilli()

    private fun dueHint(): String {
        val due = if (host.prefs.getBoolean("includeDue", true)) " + 待复习" else ""
        return "${
            if (selectedDay.isEqual(Fmt.today())) "今日" else Fmt.text(selectedDay)
        }$due"
    }
}
