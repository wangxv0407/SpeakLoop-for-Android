package com.speakloop.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 全部视觉参数只在这里定义一次，列表页和详情页共用。
 *
 * 不用 Material 库：这个 App 的界面元素就卡片、按钮、图标三种，
 * 自己用 GradientDrawable + RippleDrawable 拼出来的效果和 Material3 差别不大，
 * 但少一个依赖就少一份构建风险，也少 1MB 体积。
 */
object Ui {

    // ---------- 颜色 ----------

    /** 页面底色，比卡片更深，让卡片浮起来。 */
    val BG = Color.parseColor("#0B0F14")

    /** 卡片 / 对话框。 */
    val SURFACE = Color.parseColor("#141C26")

    /** 卡片上的次级块：输入框、图标按钮、标签。 */
    val SURFACE_2 = Color.parseColor("#1C2632")

    /** 情景对话里 B 说话人的气泡，带一点蓝。 */
    val BUBBLE = Color.parseColor("#1B2C48")

    /** 1dp 描边，深色界面里靠它分层，比阴影可靠。 */
    val STROKE = Color.parseColor("#253140")

    val FG = Color.parseColor("#E9EFF6")
    val DIM = Color.parseColor("#94A3B4")
    val MUTED = Color.parseColor("#5F6E7E")

    val ACCENT = Color.parseColor("#5B8CFF")

    /** 强调色上的文字，深色才有对比度。 */
    val ON_ACCENT = Color.parseColor("#07101F")

    val GOOD = Color.parseColor("#34D399")
    val BAD = Color.parseColor("#FF7A7A")
    val WARN = Color.parseColor("#FBBF24")

    private const val RIPPLE_SOFT = 0x30FFFFFF
    private const val RIPPLE_ACCENT = 0x50000000

    // ---------- 尺寸 ----------

    fun dp(c: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics
    ).toInt()

    // ---------- 背景 ----------

    fun shape(
        c: Context,
        radiusDp: Int,
        fill: Int,
        strokeDp: Int = 0,
        strokeColor: Int = STROKE
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(c, radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(c, strokeDp), strokeColor)
    }

    /** 带触摸水波纹的圆角背景，水纹跟着圆角裁切。 */
    fun ripple(
        c: Context,
        radiusDp: Int,
        fill: Int,
        strokeDp: Int = 1,
        strokeColor: Int = STROKE,
        rippleColor: Int = RIPPLE_SOFT
    ): Drawable {
        val content = shape(c, radiusDp, fill, strokeDp, strokeColor)
        val mask = GradientDrawable().apply {
            cornerRadius = dp(c, radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    // ---------- 文本与图标 ----------

    fun text(c: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextColor(color)
            textSize = size
            // 关掉默认的字体上下留白，标题才不会看起来偏高
            includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** 小圆角标签，用来显示「播过 3 次」「4 句对话」这类元信息。 */
    fun chip(c: Context, label: String, fg: Int = MUTED, fill: Int = SURFACE_2): TextView =
        text(c, label, 11f, fg).apply {
            setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4))
            background = shape(c, 9, fill)
        }

    fun icon(c: Context, res: Int, tint: Int, sizeDp: Int): ImageView =
        ImageView(c).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        }

    /** 圆形图标按钮。 */
    fun iconButton(
        c: Context,
        res: Int,
        tint: Int = FG,
        sizeDp: Int = 46,
        fill: Int = SURFACE_2,
        onClick: () -> Unit
    ): ImageView = ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(c, if (sizeDp >= 46) 13 else 11)
        setPadding(pad, pad, pad, pad)
        background = ripple(c, sizeDp / 2, fill, 1, STROKE, RIPPLE_SOFT)
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 胶囊按钮：primary 是实心强调色，否则是描边卡片色。 */
    fun button(
        c: Context,
        label: String,
        iconRes: Int?,
        primary: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val fill = if (primary) ACCENT else SURFACE_2
        val fg = if (primary) ON_ACCENT else FG
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13))
            background = ripple(
                c, 24, fill,
                if (primary) 0 else 1,
                STROKE,
                if (primary) RIPPLE_ACCENT else RIPPLE_SOFT
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (iconRes != null) {
            row.addView(icon(c, iconRes, fg, 18).apply {
                layoutParams = LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)).apply {
                    rightMargin = dp(c, 8)
                }
            })
        }
        row.addView(text(c, label, 15f, fg, true))
        return row
    }

    /** 小节标题：左侧一条强调色短竖线 + 疏排小字。 */
    fun sectionLabel(c: Context, label: String): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(c).apply {
            background = shape(c, 2, ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(c, 3), dp(c, 13)).apply {
                rightMargin = dp(c, 9)
            }
        })
        row.addView(text(c, label, 12f, DIM, true).apply { letterSpacing = 0.16f })
        return row
    }

    /** 把对话框默认的方块灰底换成本项目的圆角深色卡片。 */
    fun roundDialog(dialog: AlertDialog, c: Context, radiusDp: Int = 26) {
        dialog.window?.setBackgroundDrawable(shape(c, radiusDp, SURFACE, 1, STROKE))
    }
}
