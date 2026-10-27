package com.speakloop.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 单页界面，全部用代码搭。短语量级是几十条，直接用 LinearLayout 列表就够，
 * 不引 RecyclerView 也就少一个依赖。
 */
class MainActivity : AppCompatActivity() {

    private val cBg = Color.parseColor("#0E1116")
    private val cCard = Color.parseColor("#1B2430")
    private val cFg = Color.parseColor("#E6EDF3")
    private val cDim = Color.parseColor("#8B98A5")
    private val cAccent = Color.parseColor("#4C9AFF")

    private lateinit var prefs: SharedPreferences
    private lateinit var listBox: LinearLayout
    private lateinit var hint: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        refresh()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------- 界面 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // 顶栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        header.addView(
            TextView(this).apply {
                text = "SpeakLoop"
                setTextColor(cFg)
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(
            pill("＋ 短语", cAccent).apply { setOnClickListener { showAddDialog() } }
        )
        header.addView(
            pill("设置", cCard).apply {
                setOnClickListener { showSettingsDialog() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        )
        root.addView(header)

        // 列表
        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        hint = TextView(this).apply {
            setTextColor(cDim)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        root.addView(hint)

        // 底部控制条
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(16))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            pill("▶ 开始复习", cAccent).apply {
                setOnClickListener { startReview() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            pill("⏸ 暂停", cCard).apply {
                setOnClickListener {
                    sendService(ReviewService.ACTION_TOGGLE)
                    // 服务是异步处理的，这里读不到即时状态，所以只给中性提示
                    Toast.makeText(this@MainActivity, "已切换暂停/继续", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) }
        )
        bar.addView(
            pill("⏹ 停止", cCard).apply {
                setOnClickListener { sendService(ReviewService.ACTION_STOP) }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) }
        )
        root.addView(bar)

        return root
    }

    private fun pill(label: String, bg: Int): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (bg == cAccent) Color.parseColor("#08121F") else cFg)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(bg)
            }
        }
    }

    private fun refresh() {
        val db = Db(this)
        val phrases: List<Phrase>
        try {
            phrases = db.all()
        } finally {
            db.close()
        }

        listBox.removeAllViews()
        if (phrases.isEmpty()) {
            hint.text = "还没有短语。点右上角「＋ 短语」加一条。"
            return
        }
        hint.text = "共 ${phrases.size} 条 · 长按可删除 · 点一下听发音"

        for (p in phrases) {
            listBox.addView(card(p))
        }
    }

    private fun card(p: Phrase): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(cCard)
            }
            setOnClickListener { speak(p.phrase) }
            setOnLongClickListener {
                confirmDelete(p)
                true
            }
        }

        box.addView(
            TextView(this).apply {
                text = p.phrase
                setTextColor(cFg)
                textSize = 17f
            }
        )

        if (p.meaning.isNotBlank()) {
            box.addView(
                TextView(this).apply {
                    text = p.meaning
                    setTextColor(cDim)
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        val meta = buildString {
            append("播过 ${p.playCount} 次")
            if (p.examples.isNotEmpty()) append(" · ${p.examples.size} 句例句")
            if (p.dialogue.isNotEmpty()) append(" · ${p.dialogue.size} 句对话")
        }
        box.addView(
            TextView(this).apply {
                text = meta
                setTextColor(Color.parseColor("#5C6875"))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
        )

        return box.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    // ---------- 交互 ----------

    private fun speak(text: String) {
        if (!ttsReady) {
            Toast.makeText(this, "语音引擎还没就绪", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.setSpeechRate(0.9f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ui")
    }

    private fun confirmDelete(p: Phrase) {
        AlertDialog.Builder(this)
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
            .show()
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(this).apply {
            hint = "例如 Forgive me for"
            setTextColor(cFg)
            setHintTextColor(cDim)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val autoFill = CheckBox(this).apply {
            text = "用 AI 补全释义和例句"
            isChecked = prefs.getString("apiKey", "").orEmpty().isNotBlank()
            isEnabled = prefs.getString("apiKey", "").orEmpty().isNotBlank()
            setTextColor(cDim)
        }
        box.addView(input)
        box.addView(autoFill)

        AlertDialog.Builder(this)
            .setTitle("添加短语")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
            .also { dialog ->
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
            }
            .show()
    }

    private fun savePhrase(text: String, useAi: Boolean) {
        val db = Db(this)
        try {
            if (!useAi) {
                db.insert(Phrase(phrase = text))
                refresh()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                return
            }
        } finally {
            db.close()
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
                val inner = Db(this)
                try {
                    if (enriched == null) {
                        // AI 失败也把短语存下来，别让用户白输入
                        inner.insert(Phrase(phrase = text))
                        Toast.makeText(
                            this, "AI 没成功（$error），短语已保存", Toast.LENGTH_LONG
                        ).show()
                    } else {
                        inner.insert(
                            Phrase(
                                phrase = text,
                                meaning = enriched.meaning,
                                examples = enriched.examples,
                                dialogue = enriched.dialogue
                            )
                        )
                        Toast.makeText(this, "已保存并补全", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    inner.close()
                }
                refresh()
            }
        }
    }

    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val baseUrl = field("Base URL", prefs.getString("baseUrl", "https://apihub.agnes-ai.com/v1")!!)
        val apiKey = field("API Key", prefs.getString("apiKey", "")!!)
        val model = field("模型", prefs.getString("model", "agnes-2.5-flash")!!)
        val loopBox = CheckBox(this).apply {
            text = "循环播放（骑车时建议开）"
            isChecked = prefs.getBoolean("loop", true)
            setTextColor(cDim)
        }

        box.addView(label("Base URL"))
        box.addView(baseUrl)
        box.addView(label("API Key"))
        box.addView(apiKey)
        box.addView(label("模型"))
        box.addView(model)
        box.addView(loopBox)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("baseUrl", baseUrl.text.toString().trim().ifBlank { "https://apihub.agnes-ai.com/v1" })
                    .putString("apiKey", apiKey.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifBlank { "agnes-2.5-flash" })
                    .putBoolean("loop", loopBox.isChecked)
                    .apply()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cDim)
        textSize = 12f
        setPadding(0, dp(12), 0, 0)
    }

    private fun field(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(cFg)
        setHintTextColor(cDim)
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun startReview() {
        val intent = Intent(this, ReviewService::class.java).setAction(ReviewService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "开始复习，锁屏也会继续", Toast.LENGTH_SHORT).show()
    }

    private fun sendService(action: String) {
        startService(Intent(this, ReviewService::class.java).setAction(action))
    }
}
