package com.speakloop.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.concurrent.thread

/**
 * 列表页：短语库 + 开始复习。
 *
 * 界面全部用代码搭。短语量级是几十条，直接用 LinearLayout 列表就够，
 * 不引 RecyclerView 也就少一个依赖。点一条进入 [DetailActivity]。
 */
class MainActivity : AppCompatActivity() {

    /** 日期分组的一个桶。 */
    private class Bucket(
        val key: String,
        val title: String,
        val subtitle: String,
        val items: List<Phrase>
    )

    private lateinit var prefs: SharedPreferences

    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: View

    private lateinit var statLabel: TextView
    private lateinit var dateChip: TextView
    private lateinit var priorityChip: TextView
    private lateinit var expandChip: TextView
    private lateinit var pauseBtn: ImageView

    /** true = 按日期分组折叠；false = 按复习优先级平铺。 */
    private var sortByDate = true

    /** 记住哪些日期桶是收起的，否则从详情页返回会被重建回全展开。 */
    private val collapsed = mutableSetOf("week", "older")

    /** 当前有哪些日期桶，供「全部展开」用。 */
    private var bucketKeys: List<String> = emptyList()

    /** 语音输入回来后要填哪个输入框 */
    private var speechTarget: EditText? = null

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val said = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            val target = speechTarget
            if (!said.isNullOrBlank() && target != null) {
                target.setText(said)
                target.setSelection(said.length)
            }
        }
        speechTarget = null
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("speakloop", MODE_PRIVATE)
        sortByDate = prefs.getBoolean("sortByDate", true)
        setContentView(buildRoot())

        val db = Db(this)
        try {
            db.seedIfEmpty()
        } finally {
            db.close()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** 从详情页返回时要刷新（可能删了一条），顺带同步播放条的图标状态。 */
    override fun onResume() {
        super.onResume()
        refresh()
        syncPlayerBar()
    }

    // ---------- 界面骨架 ----------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(buildHeader())

        val body = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白给悬浮按钮，最后一张卡片才不会被挡住
            setPadding(dp(18), dp(2), dp(18), dp(120))
        }
        scroll.addView(listBox)
        body.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        emptyView = buildEmpty()
        body.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        body.addView(
            buildFab(),
            FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20)
                bottomMargin = dp(24)
            }
        )

        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(buildPlayerBar())
        return root
    }

    /**
     * 顶栏压成三行小字：标题一行、统计一行、排序切换一行。
     * 之前标题是 30sp 独占一大块，划走半屏才见到第一条短语。
     */
    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(16), dp(2))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            Ui.text(this, "短语库", 21f, Ui.FG, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(Ui.iconButton(this, R.drawable.ic_tune, Ui.DIM, 38) {
            showSettingsDialog()
        })
        box.addView(topRow)

        statLabel = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        box.addView(statLabel)

        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dateChip = toggleChip("按日期") { setSort(byDate = true) }
        priorityChip = toggleChip("按优先级") { setSort(byDate = false) }
        sortRow.addView(dateChip)
        sortRow.addView(
            priorityChip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
        )
        sortRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        expandChip = toggleChip("全部展开") { toggleAll() }
        sortRow.addView(expandChip)

        box.addView(
            sortRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
        return box
    }

    private fun toggleChip(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 12f, Ui.DIM, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun paintChip(chip: TextView, active: Boolean) {
        if (active) {
            chip.background = Ui.ripple(this, 12, Ui.ACCENT, 0, Ui.STROKE, 0x50000000)
            chip.setTextColor(Ui.ON_ACCENT)
        } else {
            chip.background = Ui.ripple(this, 12, Ui.SURFACE_2, 1, Ui.STROKE)
            chip.setTextColor(Ui.DIM)
        }
    }

    private fun syncChips() {
        if (!::dateChip.isInitialized) return
        paintChip(dateChip, sortByDate)
        paintChip(priorityChip, !sortByDate)

        expandChip.visibility = if (sortByDate) View.VISIBLE else View.GONE
        expandChip.text = if (collapsed.isEmpty()) "全部收起" else "全部展开"
        expandChip.background = Ui.ripple(this, 12, Ui.SURFACE_2, 1, Ui.STROKE)
        expandChip.setTextColor(Ui.DIM)
    }

    private fun buildEmpty(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(44), 0, dp(44), dp(90))
            visibility = View.GONE
        }
        box.addView(Ui.icon(this, R.drawable.ic_wave, Ui.STROKE, 64))
        box.addView(
            Ui.text(this, "还没有短语", 17f, Ui.FG, true).apply {
                setPadding(0, dp(18), 0, 0)
                gravity = Gravity.CENTER
            }
        )
        box.addView(
            Ui.text(this, "点右下角 ＋ 加一条\nAI 会顺手补上释义、例句和情景对话", 13f, Ui.MUTED)
                .apply {
                    setPadding(0, dp(8), 0, 0)
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(5).toFloat(), 1f)
                }
        )
        return box
    }

    private fun buildFab(): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_add)
        imageTintList = ColorStateList.valueOf(Ui.ON_ACCENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = Ui.ripple(this@MainActivity, 29, Ui.ACCENT, 0, Ui.STROKE, 0x50000000)
        elevation = dp(10).toFloat()
        isClickable = true
        setOnClickListener { showAddDialog() }
    }

    private fun buildPlayerBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(22))
        }
        bar.addView(
            Ui.button(this, "开始复习", R.drawable.ic_play, true) { startReview() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        pauseBtn = Ui.iconButton(this, R.drawable.ic_pause, Ui.FG, 50) { togglePause() }
        bar.addView(
            pauseBtn,
            LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(10) }
        )
        bar.addView(
            Ui.iconButton(this, R.drawable.ic_stop, Ui.DIM, 50) {
                sendService(ReviewService.ACTION_STOP)
                pauseBtn.postDelayed({ syncPlayerBar() }, 150)
            },
            LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(10) }
        )
        return bar
    }

    // ---------- 列表 ----------

    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun refresh() {
        val keepY = if (::scroll.isInitialized) scroll.scrollY else 0

        val db = Db(this)
        val phrases: List<Phrase>
        try {
            phrases = db.all()
        } finally {
            db.close()
        }

        listBox.removeAllViews()
        emptyView.visibility = if (phrases.isEmpty()) View.VISIBLE else View.GONE

        if (phrases.isEmpty()) {
            bucketKeys = emptyList()
            statLabel.text = "还没有记录"
        } else {
            if (sortByDate) renderByDate(phrases) else renderByPriority(phrases)
            listBox.addView(footerHint(), lp(top = 10))
            updateStats(phrases)
        }
        syncChips()

        // 重建列表会回到顶部，把原来的滚动位置还回去
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
    }

    /** 日期模式：今天 / 昨天 / 本周更早 / 更早 四个桶，可单独收起。 */
    private fun renderByDate(phrases: List<Phrase>) {
        val buckets = buildBuckets(phrases)
        bucketKeys = buckets.map { it.key }
        buckets.forEachIndexed { i, b ->
            val isCollapsed = collapsed.contains(b.key)
            listBox.addView(groupHeader(b, isCollapsed), lp(top = if (i == 0) 12 else 16))
            if (!isCollapsed) {
                for (p in b.items) listBox.addView(phraseCard(p), lp(top = 8))
            }
        }
    }

    /** 优先级模式：和以前一样，播得少的在前。 */
    private fun renderByPriority(phrases: List<Phrase>) {
        bucketKeys = emptyList()
        listBox.addView(
            Ui.text(this, "播得少的排在前面，方便先练生的", 11f, Ui.MUTED),
            lp(top = 12, bottom = 2)
        )
        for (p in phrases) listBox.addView(phraseCard(p), lp(top = 8))
    }

    private fun groupHeader(b: Bucket, isCollapsed: Boolean): View {
        if (isCollapsed) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(11), dp(13))
                background = Ui.ripple(this@MainActivity, 14, Ui.SURFACE, 1, Ui.STROKE)
                isClickable = true
                setOnClickListener { toggleGroup(b.key) }
            }
            row.addView(
                Ui.icon(this, R.drawable.ic_chevron, Ui.MUTED, 12),
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(9) }
            )
            row.addView(Ui.text(this, b.title, 13f, Ui.FG, true))
            row.addView(
                Ui.text(this, b.subtitle, 11f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) }
            )
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(Ui.chip(this, "${b.items.size} 条", Ui.DIM, Ui.SURFACE_2))
            return row
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            isClickable = true
            setOnClickListener { toggleGroup(b.key) }
        }
        row.addView(Ui.text(this, b.title, 13f, Ui.FG, true))
        row.addView(
            Ui.text(this, b.subtitle, 11f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) }
        )
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(this, "${b.items.size} 条", 11f, Ui.MUTED))
        row.addView(
            Ui.icon(this, R.drawable.ic_chevron_down, Ui.MUTED, 12),
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { leftMargin = dp(4) }
        )
        return row
    }

    private fun phraseCard(p: Phrase): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(14), dp(16))
            background = Ui.ripple(this@MainActivity, 20, Ui.SURFACE, 1, Ui.STROKE)
            isClickable = true
            setOnClickListener { openDetail(p.id) }
            setOnLongClickListener {
                confirmDelete(p)
                true
            }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, p.phrase, 18f, Ui.FG, true))
        if (p.meaning.isNotBlank()) {
            col.addView(
                Ui.text(this, p.meaning, 13f, Ui.DIM).apply {
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
            )
        }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(11), 0, 0)
        }
        chips.addView(Ui.chip(this, "播过 ${p.playCount} 次"))
        if (p.examples.isNotEmpty()) {
            chips.addView(Ui.chip(this, "${p.examples.size} 句例句"), chipParams())
        }
        if (p.dialogue.isNotEmpty()) {
            chips.addView(Ui.chip(this, "${p.dialogue.size} 句对话"), chipParams())
        }
        col.addView(chips)

        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            Ui.icon(this, R.drawable.ic_chevron, Ui.MUTED, 20),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(6) }
        )
        return card
    }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }

    private fun footerHint(): View =
        Ui.text(this, "点开可跟读 · 长按可删除", 11f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

    // ---------- 日期与统计 ----------

    private fun dateOf(p: Phrase): LocalDate =
        Instant.ofEpochMilli(if (p.createdAt > 0L) p.createdAt else 0L)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    private fun dateText(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

    private fun rangeText(a: LocalDate, b: LocalDate): String = when {
        a.isEqual(b) -> dateText(a)
        a.monthValue == b.monthValue -> "${a.monthValue}月${a.dayOfMonth}–${b.dayOfMonth}日"
        else -> "${dateText(a)}–${dateText(b)}"
    }

    /**
     * 分桶时只比日期，不比时间戳：跨时区、夏令时都交给 java.time 处理。
     * 用 SQL 的 date(...,'unixepoch','localtime') 在边界上会偏一天。
     */
    private fun buildBuckets(phrases: List<Phrase>): List<Bucket> {
        val today = LocalDate.now(ZoneId.systemDefault())
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
                "today" -> dateText(today)
                "yesterday" -> dateText(yesterday)
                "week" -> rangeText(sorted.first(), sorted.last())
                else -> "${dateText(sorted.first())}之前"
            }
            out.add(Bucket(key, title, sub, items))
        }
        return out
    }

    /** 本周新增 / 连续记录 / 总条数，拼成一行小字放在标题下面。 */
    private fun updateStats(phrases: List<Phrase>) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val all = phrases.map { dateOf(it) }
        val distinct = all.toSet()

        val weekCount = all.count { !it.isBefore(weekStart) && !it.isAfter(today) }

        // 今天还没加就从昨天往回数，免得下午打开时连续天数先归零
        var cursor = if (distinct.contains(today)) today else today.minusDays(1)
        var streak = 0
        while (distinct.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val sb = SpannableStringBuilder()
        appendStat(sb, "本周 ", if (weekCount > 0) "+$weekCount" else "0")
        sb.append("   ·   ")
        appendStat(sb, "连续 ", "$streak 天")
        sb.append("   ·   ")
        appendStat(sb, "共 ", "${phrases.size} 条")
        statLabel.text = sb
    }

    private fun appendStat(sb: SpannableStringBuilder, label: String, value: String) {
        sb.append(label)
        val start = sb.length
        sb.append(value)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(ForegroundColorSpan(Ui.FG), start, sb.length, flag)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, flag)
    }

    // ---------- 排序与折叠 ----------

    private fun setSort(byDate: Boolean) {
        if (sortByDate == byDate) return
        sortByDate = byDate
        prefs.edit().putBoolean("sortByDate", byDate).apply()
        refresh()
    }

    private fun toggleGroup(key: String) {
        if (!collapsed.remove(key)) collapsed.add(key)
        refresh()
    }

    private fun toggleAll() {
        if (collapsed.isEmpty()) {
            collapsed.addAll(bucketKeys)
        } else {
            collapsed.clear()
        }
        refresh()
    }

    // ---------- 交互 ----------

    private fun openDetail(id: Long) {
        startActivity(
            Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, id)
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun confirmDelete(p: Phrase) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除「${p.phrase}」？")
            .setMessage("删掉就找不回来了。")
            .setPositiveButton("删除") { _, _ ->
                val db = Db(this)
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
        Ui.roundDialog(dialog, this)
    }

    private fun togglePause() {
        if (!ReviewService.running) {
            Toast.makeText(this, "还没开始复习", Toast.LENGTH_SHORT).show()
            return
        }
        // 服务是异步处理的，这里先在切换前读一次状态，提示才说得准
        val wasPaused = ReviewService.paused
        sendService(ReviewService.ACTION_TOGGLE)
        Toast.makeText(this, if (wasPaused) "继续播放" else "已暂停", Toast.LENGTH_SHORT).show()
        pauseBtn.postDelayed({ syncPlayerBar() }, 200)
    }

    /** 暂停键的图标跟着真实播放状态走，没在播就变灰。 */
    private fun syncPlayerBar() {
        if (!::pauseBtn.isInitialized) return
        when {
            !ReviewService.running -> {
                pauseBtn.setImageResource(R.drawable.ic_pause)
                pauseBtn.imageTintList = ColorStateList.valueOf(Ui.MUTED)
            }

            ReviewService.paused -> {
                pauseBtn.setImageResource(R.drawable.ic_play)
                pauseBtn.imageTintList = ColorStateList.valueOf(Ui.FG)
            }

            else -> {
                pauseBtn.setImageResource(R.drawable.ic_pause)
                pauseBtn.imageTintList = ColorStateList.valueOf(Ui.FG)
            }
        }
    }

    private fun startReview() {
        val intent = Intent(this, ReviewService::class.java).setAction(ReviewService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "开始复习，锁屏也会继续", Toast.LENGTH_SHORT).show()
        pauseBtn.postDelayed({ syncPlayerBar() }, 300)
    }

    private fun sendService(action: String) {
        startService(Intent(this, ReviewService::class.java).setAction(action))
    }

    // ---------- 新增短语 ----------

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(2), dp(22), dp(2))
        }

        val input = inputField("例如 Forgive me for", "")
        val hasKey = prefs.getString("apiKey", "").orEmpty().isNotBlank()
        val autoFill = CheckBox(this).apply {
            text = "用 AI 补全释义和例句"
            isChecked = hasKey
            isEnabled = hasKey
            setTextColor(if (hasKey) Ui.DIM else Ui.MUTED)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(0, dp(12), 0, 0)
        }

        box.addView(input)
        box.addView(
            Ui.button(this, "说一遍", R.drawable.ic_mic, false) {
                speechTarget = input
                startVoiceInput()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        box.addView(autoFill)

        val dialog = AlertDialog.Builder(this)
            .setTitle("添加短语")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    input.error = "先写一个短语"
                    return@setOnClickListener
                }
                dialog.dismiss()
                savePhrase(text, autoFill.isChecked)
            }
        }
        dialog.show()
        Ui.roundDialog(dialog, this)
    }

    private fun savePhrase(text: String, useAi: Boolean) {
        if (!useAi) {
            val db = Db(this)
            val id = try {
                db.insert(Phrase(phrase = text))
            } finally {
                db.close()
            }
            refresh()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            openDetail(id)
            return
        }

        Toast.makeText(this, "正在让 AI 补全……", Toast.LENGTH_SHORT).show()
        thread {
            var enriched: Llm.Enriched? = null
            var error: String? = null
            try {
                enriched = Llm.enrich(
                    prefs.getString("baseUrl", "")!!,
                    prefs.getString("apiKey", "")!!,
                    prefs.getString("model", "")!!,
                    text
                )
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                // 先落到本地变量，避免闭包里对可变的 enriched 做智能转换
                val got = enriched
                val inner = Db(this)
                val newId = try {
                    if (got == null) {
                        // AI 失败也把短语存下来，别让用户白输入
                        inner.insert(Phrase(phrase = text))
                    } else {
                        inner.insert(
                            Phrase(
                                phrase = text,
                                meaning = got.meaning,
                                examples = got.examples,
                                dialogue = got.dialogue
                            )
                        )
                    }
                } finally {
                    inner.close()
                }

                Toast.makeText(
                    this,
                    if (got == null) "AI 没成功（${error ?: "未知原因"}），短语已保存" else "已保存并补全",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
                openDetail(newId)
            }
        }
    }

    // ---------- 设置 ----------

    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(0), dp(22), dp(4))
        }

        val baseUrl =
            inputField("https://apihub.agnes-ai.com/v1", prefs.getString("baseUrl", "")!!)
        val apiKey = inputField("sk-...", prefs.getString("apiKey", "")!!)
        val model = inputField("agnes-2.5-flash", prefs.getString("model", "agnes-2.5-flash")!!)

        val testResult = Ui.text(this, "", 13f, Ui.MUTED).apply {
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        val loopBox = CheckBox(this).apply {
            text = "循环播放（骑车时建议开）"
            isChecked = prefs.getBoolean("loop", true)
            setTextColor(Ui.DIM)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(0, dp(14), 0, 0)
        }

        box.addView(fieldLabel("Base URL（只填到 /v1）"))
        box.addView(baseUrl)
        box.addView(fieldLabel("API Key"))
        box.addView(apiKey)
        box.addView(fieldLabel("模型"))
        box.addView(model)
        box.addView(
            Ui.button(this, "测试连接", null, true) {
                val bu = baseUrl.text.toString()
                val k = apiKey.text.toString()
                val m = model.text.toString()
                if (bu.isBlank() || k.isBlank() || m.isBlank()) {
                    testResult.setTextColor(Ui.BAD)
                    testResult.text = "Base URL、API Key、模型三项都要填"
                    return@button
                }
                testResult.setTextColor(Ui.MUTED)
                testResult.text = "测试中……（推理模型可能要几秒）"
                thread {
                    // Kotlin 不允许同一个 val 在 try 和 catch 里各赋一次，这里必须用 var
                    var ok = true
                    var msg = ""
                    try {
                        val r = Llm.test(bu, k.trim(), m.trim())
                        msg = buildString {
                            append("✅ 连接成功\n")
                            append("请求地址：").append(r.endpoint).append('\n')
                            append("模型：").append(m.trim()).append('\n')
                            append("用时：").append(r.millis).append(" ms\n")
                            append("回复：").append(r.reply.ifBlank { "(空)" })
                        }
                    } catch (e: Exception) {
                        ok = false
                        msg = "❌ " + (e.message ?: e.javaClass.simpleName)
                    }
                    runOnUiThread {
                        testResult.setTextColor(if (ok) Ui.GOOD else Ui.BAD)
                        testResult.text = msg
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )
        box.addView(testResult)
        box.addView(loopBox)

        val dialog = AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString(
                        "baseUrl",
                        baseUrl.text.toString().trim()
                            .ifBlank { "https://apihub.agnes-ai.com/v1" }
                    )
                    .putString("apiKey", apiKey.text.toString().trim())
                    .putString(
                        "model",
                        model.text.toString().trim().ifBlank { "agnes-2.5-flash" }
                    )
                    .putBoolean("loop", loopBox.isChecked)
                    .apply()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, this)
    }

    private fun fieldLabel(s: String): TextView =
        Ui.text(this, s, 11f, Ui.MUTED, true).apply {
            letterSpacing = 0.1f
            setPadding(0, dp(16), 0, dp(7))
        }

    private fun inputField(hintText: String, value: String): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(Ui.FG)
            setHintTextColor(Ui.MUTED)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            includeFontPadding = false
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = Ui.shape(this@MainActivity, 14, Ui.SURFACE_2, 1, Ui.STROKE)
        }

    // ---------- 语音输入 ----------

    /** 调系统语音输入界面。不用自己申请麦克风权限，识别工作由系统界面完成。 */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说一遍短语")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "这台手机没有语音识别服务，手打一下吧", Toast.LENGTH_LONG).show()
        }
    }
}
