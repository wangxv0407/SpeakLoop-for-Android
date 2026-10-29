package com.speakloop.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * 四个 tab 的外壳：短语 / 统计 / 复习 / 设置。
 *
 * 四个页面在启动时一次性建好，切 tab 只改可见性，不进 Fragment 也不重建 View。
 * 原因很直接：页面之间没有独立的返回栈需求，而重建列表会丢滚动位置、
 * 重建复习页会丢倒数状态。几十条短语的量级，四个 View 常驻内存毫无压力。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        val TABS = listOf(
            Triple("短语", R.drawable.ic_list, 0),
            Triple("统计", R.drawable.ic_stats, 1),
            Triple("复习", R.drawable.ic_play_circle, 2),
            Triple("设置", R.drawable.ic_settings, 3)
        )
    }

    lateinit var prefs: SharedPreferences
        private set

    private lateinit var container: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var phrases: PagePhrases
    private lateinit var stats: PageStats
    private lateinit var review: PageReview
    private lateinit var settings: PageSettings

    private val navBoxes = ArrayList<LinearLayout>()
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()

    private var current = 0

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

    fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("speakloop", MODE_PRIVATE)
        Ui.applyTheme(this)
        setContentView(buildRoot())
        Ui.decorateWindow(this)

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

    /** 从详情页回来可能删过东西，当前这页要重算。 */
    override fun onResume() {
        super.onResume()
        when (current) {
            0 -> phrases.refresh()
            1 -> stats.refresh()
            2 -> review.refresh()
        }
    }

    override fun onDestroy() {
        review.release()
        review.detach()
        super.onDestroy()
    }

    /** 跟读面板要麦克风权限，结果得转给复习页里的面板 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::review.isInitialized) review.onPermissionResult(requestCode, grantResults)
    }

    // ---------- 骨架 ----------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        container = FrameLayout(this)
        phrases = PagePhrases(this)
        stats = PageStats(this)
        review = PageReview(this)
        settings = PageSettings(this)
        pages = listOf(phrases.view, stats.view, review.view, settings.view)
        pages.forEachIndexed { i, v ->
            container.addView(
                v,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            v.visibility = if (i == 0) View.VISIBLE else View.GONE
        }

        root.addView(
            container,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(buildNav())
        return root
    }

    private fun buildNav(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply {
            setBackgroundColor(Ui.STROKE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(8), dp(7), dp(8), dp(9))
        }

        TABS.forEach { (label, iconRes, index) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { goTab(index) }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(5), dp(13), dp(5))
            }
            val icon = Ui.icon(this, iconRes, Ui.MUTED, 18).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            }
            box.addView(icon)
            val text = Ui.text(this, label, 10f, Ui.MUTED).apply {
                setPadding(0, dp(3), 0, 0)
                gravity = Gravity.CENTER
            }
            item.addView(box)
            item.addView(text)

            navBoxes.add(box)
            navIcons.add(icon)
            navLabels.add(text)
            bar.addView(item, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }

        wrap.addView(bar)
        paintNav()
        return wrap
    }

    private fun paintNav() {
        for (i in navIcons.indices) {
            val on = i == current
            navIcons[i].imageTintList =
                ColorStateList.valueOf(if (on) Ui.ACCENT else Ui.MUTED)
            navLabels[i].setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
            navBoxes[i].background =
                if (on) Ui.shape(this, 11, Ui.ACCENT_SOFT) else null
        }
    }

    fun goTab(index: Int) {
        if (index == current) return
        // 离开复习页就暂停：一边翻短语库一边念，注意力是散的，后台空转也费电
        if (current == 2 && index != 2 &&
            ReviewBus.state.running && !ReviewBus.state.paused
        ) {
            ReviewService.command(this, ReviewService.ACTION_TOGGLE)
            toast("复习已暂停，回到复习页点「继续」")
        }
        pages[current].visibility = View.GONE
        current = index
        pages[index].visibility = View.VISIBLE
        paintNav()
        when (index) {
            0 -> phrases.refresh()
            1 -> stats.refresh()
            2 -> review.refresh()
        }
    }

    // ---------- 给页面用的小工具 ----------

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun openDetail(id: Long) {
        startActivity(
            Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, id)
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    // ---------- 新增短语 ----------

    /**
     * 新增短语：底部弹层，不再是居中 AlertDialog。
     *
     * 原来的居中弹窗有两个实际痛点：键盘一弹就被顶到屏幕上半部，输入框离拇指很远；
     * 「保存」在右上角，单手根本够不到。底部弹层贴着键盘上沿生长，
     * 输入框和保存键始终落在拇指热区里。
     */
    fun showAddDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val r = dp(20).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE)
                // 只圆上面两角，底部贴屏幕边缘
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        box.addView(
            View(this).apply { setBackgroundColor(Ui.STROKE) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        box.addView(
            Ui.text(this, "添加短语", Ui.T.TITLE, Ui.FG, true),
            W.margin(this, top = 14)
        )

        val input = W.input(this, "例如 Forgive me for", "")
        input.background = Ui.shape(this, 12, Ui.SURFACE_2, 1, Ui.ACCENT)
        input.setPadding(dp(14), dp(14), dp(14), dp(14))
        box.addView(input, W.margin(this, top = 12))

        box.addView(
            Ui.button(this, "说一遍", R.drawable.ic_mic, false) {
                speechTarget = input
                startVoiceInput()
            },
            W.margin(this, top = 12)
        )

        val hasKey = prefs.getString("apiKey", "").orEmpty().isNotBlank()
        val autoFill = CheckBox(this).apply {
            text = "用 AI 补全释义和例句"
            isChecked = hasKey
            isEnabled = hasKey
            setTextColor(if (hasKey) Ui.DIM else Ui.MUTED)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(autoFill)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = Ui.button(this, "取消", null, false) { dialog.dismiss() }
        val save = Ui.button(this, "保存", null, true) {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = "先写一个短语"
            } else {
                dialog.dismiss()
                savePhrase(text, autoFill.isChecked)
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        box.addView(actions, W.margin(this, top = 16))

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            // 左右留 12dp，让弹层和屏幕边缘之间能透出一点底色
            val w = resources.displayMetrics.widthPixels - dp(24)
            setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun savePhrase(text: String, useAi: Boolean) {
        if (!useAi) {
            val db = Db(this)
            val id = try {
                db.insert(Phrase(phrase = text))
            } finally {
                db.close()
            }
            phrases.refresh()
            toast("已保存")
            openDetail(id)
            return
        }

        toast("正在让 AI 补全……")
        thread {
            var enriched: Llm.Enriched? = null
            var error: String? = null
            try {
                enriched = Llm.enrich(
                    prefs.getString("baseUrl", "").orEmpty().ifBlank { Llm.DEFAULT_BASE },
                    prefs.getString("apiKey", "").orEmpty(),
                    prefs.getString("model", "").orEmpty().ifBlank { "agnes-2.5-flash" },
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

                toast(
                    if (got == null) "AI 没成功（${error ?: "未知原因"}），短语已保存"
                    else "已保存并补全"
                )
                phrases.refresh()
                openDetail(newId)
            }
        }
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
            toast("这台手机没有语音识别服务，手打一下吧")
        }
    }
}
