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
import android.text.InputType
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
import kotlin.concurrent.thread

/**
 * 列表页：短语库 + 开始复习。
 *
 * 界面全部用代码搭。短语量级是几十条，直接用 LinearLayout 列表就够，
 * 不引 RecyclerView 也就少一个依赖。点一条进入 [DetailActivity]。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: View
    private lateinit var countLabel: TextView
    private lateinit var pauseBtn: ImageView

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

    // ---------- 界面 ----------

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
            setPadding(dp(18), dp(4), dp(18), dp(120))
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

    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(18), dp(8))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            Ui.text(this, "SPEAKLOOP", 11f, Ui.ACCENT, true).apply { letterSpacing = 0.24f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(Ui.iconButton(this, R.drawable.ic_tune, Ui.DIM, 40) {
            showSettingsDialog()
        })
        box.addView(topRow)

        box.addView(
            Ui.text(this, "短语库", 30f, Ui.FG, true).apply { setPadding(0, dp(8), 0, 0) }
        )
        countLabel = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(12)) }
        box.addView(countLabel)
        return box
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
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return card
    }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }

    private fun refresh() {
        val keepY = scroll.scrollY
        val db = Db(this)
        val phrases: List<Phrase>
        try {
            phrases = db.all()
        } finally {
            db.close()
        }

        listBox.removeAllViews()
        countLabel.text = if (phrases.isEmpty()) {
            "按复习优先级排序 · 播得少的在前"
        } else {
            "共 ${phrases.size} 条 · 点开跟读 · 长按删除"
        }
        emptyView.visibility = if (phrases.isEmpty()) View.VISIBLE else View.GONE
        for (p in phrases) listBox.addView(phraseCard(p))

        // 重建列表会回到顶部，把原来的滚动位置还回去
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
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
