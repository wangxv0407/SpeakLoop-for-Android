package com.speakloop.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
 * 全部视觉参数只在这里定义一次，所有页面共用。
 *
 * 颜色是「两套调色板 + 一个全局开关」：Activity 启动时先调 [applyTheme]，
 * 之后 Ui.FG / Ui.SURFACE 这些读到的就是当前主题下的值。这样调用点完全不用关心主题，
 * 也不用把每个页面都改成传 Context 进去取色。
 *
 * 不用 Material 库：界面元素就卡片、按钮、图标三种，
 * 自己用 GradientDrawable + RippleDrawable 拼出来够用，少一个依赖少一份构建风险。
 */
object Ui {

    private const val PREF = "speakloop"
    private const val KEY_THEME = "theme"

    /** 当前是否深色。由 [applyTheme] 决定，默认深色。 */
    @Volatile
    private var dark = true

    val isDark: Boolean get() = dark

    /** 每个 Activity 在 setContentView 之前调一次。 */
    fun applyTheme(c: Context) {
        val mode = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "system") ?: "system"
        dark = when (mode) {
            "light" -> false
            "dark" -> true
            else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 状态栏、导航栏跟着主题走。
     * 不写在 themes.xml 里，因为那一份在运行时不知道用户选了浅色还是深色。
     */
    fun decorateWindow(a: Activity) {
        val w = a.window
        w.statusBarColor = BG
        w.navigationBarColor = BG
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (!dark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = flags
        w.decorView.setBackgroundColor(BG)
    }

    // ---------- 调色板 ----------

    private fun pick(lightColor: String, darkColor: String): Int =
        Color.parseColor(if (dark) darkColor else lightColor)

    /** 页面底色：比卡片更深一档，卡片靠这层差浮起来（浅色下尤其靠它）。 */
    val BG: Int get() = pick("#F1F3F6", "#0B0F14")

    /** 卡片 / 对话框。 */
    val SURFACE: Int get() = pick("#FFFFFF", "#141C26")

    /** 卡片上的次级块：输入框、图标按钮、标签。 */
    val SURFACE_2: Int get() = pick("#F3F5F8", "#1C2632")

    /** 情景对话里 B 说话人的气泡。 */
    val BUBBLE: Int get() = pick("#EEF4FF", "#1B2C48")

    /** 1dp 描边。浅色下没有它卡片会糊在底色里。 */
    val STROKE: Int get() = pick("#E8EBEF", "#253140")

    val FG: Int get() = pick("#0F1115", "#E9EFF6")
    val DIM: Int get() = pick("#8A8F98", "#94A3B4")
    val MUTED: Int get() = pick("#A8AEB8", "#5F6E7E")

    /** 强调色：浅色下用紫，深色下用蓝，都是「选中 / 可点」的意思。 */
    val ACCENT: Int get() = pick("#5B54D6", "#5B8CFF")

    /** 强调色底上的文字。 */
    val ON_ACCENT: Int get() = pick("#FFFFFF", "#07101F")

    /** 强调色的浅底，用于选中态胶囊（浅色 #EDEBFF / 深色半透明蓝）。 */
    val ACCENT_SOFT: Int get() = pick("#EDEBFF", "#1B2C48")

    /** 实心主按钮（朗读那个大圆）：浅色下是近黑，深色下是近白。 */
    val SOLID: Int get() = pick("#16181D", "#E9EFF6")
    val ON_SOLID: Int get() = pick("#FFFFFF", "#0B0F14")

    /** 播放到哪句的高亮色，和强调色分开，免得和「可点」混淆。 */
    val HILITE: Int get() = pick("#2563EB", "#8AB4FF")

    val GOOD: Int get() = pick("#12B76A", "#34D399")
    val BAD: Int get() = pick("#E24B4A", "#FF7A7A")
    val WARN: Int get() = pick("#BA7517", "#FBBF24")

    /** 绿色浅底，用于「已练 / 可用」这类状态标签。 */
    val GOOD_SOFT: Int get() = pick("#E9F8F1", "#123527")

    /** 列表左首字母方块的底色。 */
    val BADGE: Int get() = pick("#EEF2FF", "#1D2A44")
    val ON_BADGE: Int get() = pick("#4F63E8", "#8AB4FF")

    private val RIPPLE_SOFT: Int get() = if (dark) 0x30FFFFFF else 0x12000000
    private val RIPPLE_ON_SOLID: Int get() = if (dark) 0x50000000 else 0x33FFFFFF

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
        strokeDp: Int = 0,
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

    /** 白底卡片：圆角 + 描边，不加阴影。 */
    fun card(c: Context, radiusDp: Int = 20, fill: Int = SURFACE): GradientDrawable =
        shape(c, radiusDp, fill, 1, STROKE)

    // ---------- 文本与图标 ----------

    /**
     * 中等字重是 Medium(500)，不是 Bold(700)。深色界面用 Bold 会糊成一团，
     * 而浅色卡片风靠字重只区分「标题 / 正文」两档，靠字号拉层级。
     */
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun text(c: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextColor(color)
            textSize = size
            // 关掉默认的字体上下留白，标题才不会看起来偏高
            includeFontPadding = false
            if (bold) setTypeface(medium)
        }

    /** 小圆角标签，用来显示「播过 3 次」「4 句对话」这类元信息。 */
    fun chip(c: Context, label: String, fg: Int = MUTED, fill: Int = SURFACE_2): TextView =
        text(c, label, 10f, fg).apply {
            setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4))
            background = shape(c, 9, fill)
        }

    /** 可点的胶囊标签，选中时换成强调色浅底 + 强调色文字。 */
    fun pill(
        c: Context,
        label: String,
        on: Boolean,
        onClick: (() -> Unit)? = null
    ): TextView = text(c, label, 11f, if (on) ACCENT else DIM, on).apply {
        setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7))
        background = ripple(c, 11, if (on) ACCENT_SOFT else SURFACE_2, 0, STROKE)
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
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
        sizeDp: Int = 44,
        fill: Int = SURFACE,
        onClick: () -> Unit
    ): ImageView = ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(c, if (sizeDp >= 46) 12 else 10)
        setPadding(pad, pad, pad, pad)
        background = ripple(c, sizeDp / 2, fill, 1, STROKE, RIPPLE_SOFT)
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 胶囊按钮：primary 是实心强调色，否则是白底描边。 */
    fun button(
        c: Context,
        label: String,
        iconRes: Int?,
        primary: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val fill = if (primary) ACCENT else SURFACE
        val fg = if (primary) ON_ACCENT else FG
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13))
            background = ripple(
                c, 16, fill,
                if (primary) 0 else 1,
                STROKE,
                if (primary) RIPPLE_ON_SOLID else RIPPLE_SOFT
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (iconRes != null) {
            row.addView(icon(c, iconRes, fg, 16).apply {
                layoutParams = LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)).apply {
                    rightMargin = dp(c, 7)
                }
            })
        }
        row.addView(text(c, label, 14f, fg, true))
        return row
    }

    /** 小节标题：一行小字，左边不留竖线（浅色卡片风里竖线显得重）。 */
    fun sectionLabel(c: Context, label: String): TextView =
        text(c, label, 13f, FG, true)

    /** 卡片里的一行「左标题 + 右值」，用于设置页。 */
    fun rowKV(c: Context, k: String, v: String): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
            addView(
                text(c, k, 13f, FG),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(text(c, v, 12f, DIM))
        }

    /** 1dp 分隔线，左边缩进对齐文字起始位置。 */
    fun divider(c: Context, insetDp: Int): View = View(c).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { leftMargin = dp(c, insetDp) }
    }

    /** 把对话框默认的方块灰底换成本项目的圆角卡片。 */
    fun roundDialog(dialog: AlertDialog, c: Context, radiusDp: Int = 24) {
        dialog.window?.setBackgroundDrawable(shape(c, radiusDp, SURFACE))
    }
}
