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
        review.detach()
        super.onDestroy()
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

    fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(2))
        }

        val input = W.input(this, "例如 Forgive me for", "")
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
