package com.speakloop.app

import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** 日期格式化就这几个，别处不要再手拼字符串，免得到处格式不一致。 */
object Fmt {

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone())

    /** "10月28日" */
    fun text(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

    /** "周三" */
    fun weekday(d: LocalDate): String = when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        else -> "周日"
    }

    /** "10月28日 · 周三" */
    fun textWithWeek(d: LocalDate): String = "${text(d)} · ${weekday(d)}"

    /** "今天/昨天/10月26日" —— 列表分组和日期选择器都用这套叫法。 */
    fun friendly(d: LocalDate): String {
        val today = today()
        return when {
            d.isEqual(today) -> "今天"
            d.isEqual(today.minusDays(1)) -> "昨天"
            else -> text(d)
        }
    }

    fun range(a: LocalDate, b: LocalDate): String = when {
        a.isEqual(b) -> text(a)
        a.monthValue == b.monthValue -> "${a.monthValue}月${a.dayOfMonth}–${b.dayOfMonth}日"
        else -> "${text(a)}–${text(b)}"
    }

    /** 存进 play_log.day 的格式，必须和 LocalDate.parse 对得上 */
    fun key(d: LocalDate): String = d.toString()
}

/**
 * 完成度圆环。自己画而不是塞个 ProgressBar：
 * 要的是「粗圆环 + 正中一个百分比」这种版式，用 ProgressBar 拧不出来。
 */
class RingView(c: Context) : View(c) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    /** 0f ~ 1f */
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var labelText: String = ""
        set(v) {
            field = v
            invalidate()
        }

    /** 环宽，dp */
    var thicknessDp: Float = 8f
        set(v) {
            field = v
            invalidate()
        }

    /** 环中间的字号，dp 换算成 px 在 onDraw 里定 */
    var labelSizeDp: Float = 15f

    private fun px(v: Float) = v * density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val half = minOf(w, h) / 2f
        val stroke = px(thicknessDp)
        val pad = stroke / 2f + px(1f)
        rect.set(pad, pad, w - pad, h - pad)

        track.color = Ui.GOOD_SOFT
        track.strokeWidth = stroke
        canvas.drawCircle(w / 2f, h / 2f, half - pad, track)

        if (progress > 0f) {
            arc.color = Ui.GOOD
            arc.strokeWidth = stroke
            canvas.drawArc(rect, -90f, 360f * progress, false, arc)
        }

        if (labelText.isNotEmpty()) {
            label.color = Ui.FG
            label.textSize = px(labelSizeDp)
            canvas.drawText(labelText, w / 2f, h / 2f + label.textSize * 0.36f, label)
        }
    }
}

/**
 * 练习热力图：每列一周、每行一天，最后一行是周日。
 * 98 个格子用一个 View 画，比铺 98 个 TextView 省内存也省布局时间。
 */
class HeatmapView(c: Context) : View(c) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** day → 那天练了几条 */
    private var counts: Map<LocalDate, Int> = emptyMap()
    private var start: LocalDate = Fmt.today().minusDays(97)

    private val gap = 3f * density
    private var cell = 12f * density

    fun setData(data: Map<LocalDate, Int>, days: Int) {
        counts = data
        val today = Fmt.today()
        val raw = today.minusDays((days - 1).toLong())
        // 往回对齐到周一，第一列才是完整的一周
        val shift = (raw.dayOfWeek.value - 1)
        start = raw.minusDays(shift.toLong())
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = weekCount()
        cell = if (cols > 0) (w - gap * (cols - 1)) / cols else 12f * density
        val h = (cell * 7 + gap * 6).toInt() + 1
        setMeasuredDimension(w, h)
    }

    private fun weekCount(): Int {
        val today = Fmt.today()
        var days = 0
        var d = start
        while (!d.isAfter(today)) {
            days++
            d = d.plusDays(1)
        }
        return (days + 6) / 7
    }

    private fun level(n: Int): Int = when {
        n <= 0 -> Ui.SURFACE_2
        n <= 1 -> blend(Ui.SURFACE_2, Ui.GOOD, 0.28f)
        n <= 3 -> blend(Ui.SURFACE_2, Ui.GOOD, 0.58f)
        else -> Ui.GOOD
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        val today = Fmt.today()
        var d = start
        var col = 0
        var row = d.dayOfWeek.value - 1
        while (!d.isAfter(today)) {
            val left = col * (cell + gap)
            val top = row * (cell + gap)
            val r = cell * 0.24f
            paint.color = level(counts[d] ?: 0)
            canvas.drawRoundRect(left, top, left + cell, top + cell, r, r, paint)
            row++
            if (row == 7) {
                row = 0
                col++
            }
            d = d.plusDays(1)
        }
    }
}

/** 几个页面共用的小控件，省得每处都重写一遍 EditText 的样式。 */
object W {

    fun label(c: Context, s: String): TextView =
        Ui.text(c, s, 11f, Ui.MUTED, true).apply { setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6)) }

    fun input(c: Context, hint: String, value: String): EditText = EditText(c).apply {
        this.hint = hint
        setText(value)
        setTextColor(Ui.FG)
        setHintTextColor(Ui.MUTED)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        includeFontPadding = false
        setPadding(Ui.dp(c, 13), Ui.dp(c, 12), Ui.dp(c, 13), Ui.dp(c, 12))
        background = Ui.shape(c, 12, Ui.SURFACE_2, 1, Ui.STROKE)
    }

    /**
     * 改短语原文和释义。例句、对话各有自己的长按编辑，这一处专管顶上那两行。
     *
     * 保存走 [Db.updateContent] 的可选 text 参数，id 和练习记录原样保留 ——
     * 这也是「改」和「删了重加」的唯一区别，值得多这十几个字。
     */
    fun editPhraseDialog(c: Context, p: Phrase, onSaved: () -> Unit) {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 20), Ui.dp(c, 2), Ui.dp(c, 20), Ui.dp(c, 2))
        }
        val en = input(c, "英文原文", p.phrase)
        val cn = input(c, "中文释义，可留空", p.meaning)
        box.addView(en)
        box.addView(
            cn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(c, 8) }
        )

        val dialog = AlertDialog.Builder(c)
            .setTitle("改短语")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        // 原文空着不关窗：关掉之后用户得重新点进来才能补上
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = en.text.toString().trim()
                if (text.isEmpty()) {
                    en.error = "原文不能为空"
                    return@setOnClickListener
                }
                val db = Db(c)
                try {
                    db.updateContent(
                        p.id, cn.text.toString().trim(), p.examples, p.dialogue, text
                    )
                } finally {
                    db.close()
                }
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
        Ui.roundDialog(dialog, c)
    }

    /** 白卡片容器，内容自己往里加。 */    fun card(c: Context, radiusDp: Int = 20): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.card(c, radiusDp)
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun margin(c: Context, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0):
        LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = Ui.dp(c, top)
        bottomMargin = Ui.dp(c, bottom)
        leftMargin = Ui.dp(c, left)
        rightMargin = Ui.dp(c, right)
    }

    fun wrap(c: Context, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(c, left) }
}
