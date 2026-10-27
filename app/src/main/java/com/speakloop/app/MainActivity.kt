package com.speakloop.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    /** 语音输入回来后要填哪个输入框 */
    private var speechTarget: EditText? = null

    /** 跟读用的识别器，同一时间只允许一个 */
    private var shadow: SpeechRecognizer? = null

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
        shadow?.destroy()
        shadow = null
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
        hint.text = "共 ${phrases.size} 条 · 点一条跟读 · 长按删除"

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
            setOnClickListener { openDetail(p) }
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
        box.addView(
            pill("🎤 说一遍", cCard).apply {
                setOnClickListener {
                    speechTarget = input
                    startVoiceInput()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        )
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
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val baseUrl =
            field("Base URL", prefs.getString("baseUrl", "https://apihub.agnes-ai.com/v1")!!)
        val apiKey = field("API Key", prefs.getString("apiKey", "")!!)
        val model = field("模型", prefs.getString("model", "agnes-2.5-flash")!!)
        val loopBox = CheckBox(this).apply {
            text = "循环播放（骑车时建议开）"
            isChecked = prefs.getBoolean("loop", true)
            setTextColor(cDim)
        }

        val testResult = TextView(this).apply {
            text = "点「测试连接」，验证地址 / 密钥 / 模型三项是否都正确"
            setTextColor(cDim)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }

        val testBtn = pill("🔍 测试连接", cAccent).apply {
            setOnClickListener {
                val bu = baseUrl.text.toString()
                val k = apiKey.text.toString()
                val m = model.text.toString()
                if (bu.isBlank() || k.isBlank() || m.isBlank()) {
                    testResult.setTextColor(Color.parseColor("#FF6B6B"))
                    testResult.text = "Base URL、API Key、模型三项都要填"
                    return@setOnClickListener
                }
                testResult.setTextColor(cDim)
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
                        testResult.setTextColor(
                            if (ok) Color.parseColor("#6BCB77") else Color.parseColor("#FF6B6B")
                        )
                        testResult.text = msg
                    }
                }
            }
        }

        box.addView(label("Base URL（只填到 /v1，不要带 /chat/completions）"))
        box.addView(baseUrl)
        box.addView(label("API Key"))
        box.addView(apiKey)
        box.addView(label("模型"))
        box.addView(model)
        box.addView(
            testBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )
        box.addView(testResult)
        box.addView(loopBox)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(ScrollView(this).apply { addView(box) })
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

    // ---------- 语音输入 & 跟读 ----------

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

    private fun openDetail(p: Phrase) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        box.addView(TextView(this).apply {
            text = p.phrase
            setTextColor(cFg)
            textSize = 20f
        })
        if (p.meaning.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = p.meaning
                setTextColor(cDim)
                textSize = 14f
                setPadding(0, dp(4), 0, 0)
            })
        }

        if (p.examples.isNotEmpty()) {
            box.addView(sectionTitle("例句"))
            for (s in p.examples) box.addView(lineView("· $s"))
        }
        if (p.dialogue.isNotEmpty()) {
            box.addView(sectionTitle("情景对话"))
            for (l in p.dialogue) box.addView(lineView("${l.speaker}: ${l.text}"))
        }

        box.addView(sectionTitle("跟读对比"))
        val result = TextView(this).apply {
            text = "点「开始跟读」，说完停一下\n红字加删除线＝漏读，黄字＝多读"
            setTextColor(cDim)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(10))
        }
        box.addView(result)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(pill("🔊 朗读", cCard).apply {
            setOnClickListener { speak(p.phrase) }
        })
        actions.addView(
            pill("🎤 开始跟读", cAccent).apply {
                setOnClickListener { startShadow(p, result) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        )
        box.addView(actions)

        AlertDialog.Builder(this)
            .setTitle("短语详情")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(cAccent)
        textSize = 12f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun lineView(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(cFg)
        textSize = 14f
        setPadding(0, dp(3), 0, 0)
    }

    /** 在 App 内录音识别，需要 RECORD_AUDIO 权限。 */
    private fun startShadow(p: Phrase, result: TextView) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            result.text = "先允许录音权限，然后再点一次「开始跟读」"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            result.text = "这台手机没有可用的语音识别服务"
            return
        }

        shadow?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        shadow = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                result.text = "在听……说完停一下"
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                result.text = "识别中……"
            }

            override fun onError(error: Int) {
                result.text = "没听清（错误码 $error），再试一次"
                recognizer.destroy()
                if (shadow === recognizer) shadow = null
            }

            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                result.text = if (heard.isBlank()) {
                    "这次没听到内容，再试一次"
                } else {
                    renderDiff(p.phrase, heard)
                }
                recognizer.destroy()
                if (shadow === recognizer) shadow = null
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    private fun renderDiff(ref: String, heard: String): CharSequence {
        val tokens = Diff.align(ref, heard)
        val sb = SpannableStringBuilder()
        for (t in tokens) {
            val start = sb.length
            sb.append(t.text).append(' ')
            val end = start + t.text.length
            val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when (t.status) {
                Diff.MISSING -> {
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#FF6B6B")), start, end, flag)
                    sb.setSpan(StrikethroughSpan(), start, end, flag)
                }

                Diff.EXTRA -> sb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#FFC857")), start, end, flag
                )

                else -> sb.setSpan(ForegroundColorSpan(cFg), start, end, flag)
            }
        }
        sb.append("\n\n命中率 ${Diff.accuracy(tokens)}%")
        return sb
    }

    private fun startReview() {        val intent = Intent(this, ReviewService::class.java).setAction(ReviewService.ACTION_START)
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
