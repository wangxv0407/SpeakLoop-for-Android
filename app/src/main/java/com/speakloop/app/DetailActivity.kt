package com.speakloop.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 短语详情页。从列表点进来，单独占一屏，而不是弹窗——
 * 因为跟读要连续试几次、还会冒出一条结果，塞进弹窗里既看不清也容易误触关掉。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "phraseId"
    }

    private lateinit var phrase: Phrase
    private lateinit var resultView: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 跟读用的识别器，同一时间只允许一个 */
    private var recognizer: SpeechRecognizer? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginListening()
        } else {
            resultView.setTextColor(Ui.WARN)
            resultView.text = "没有麦克风权限，跟读用不了。\n可以去系统设置里给 SpeakLoop 打开录音权限。"
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val db = Db(this)
        val loaded = try {
            db.byId(id)
        } finally {
            db.close()
        }
        if (loaded == null) {
            Toast.makeText(this, "这条短语已经不在了", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        phrase = loaded

        setContentView(buildRoot())

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    /** 返回和系统返回键都走这里，保证退场动画一致。 */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    // ---------- 界面 ----------

    private fun marginTop(px: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(36))
        }
        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        content.addView(buildHero())
        content.addView(buildActions(), marginTop(dp(12)))
        content.addView(buildResultCard(), marginTop(dp(14)))

        if (phrase.examples.isNotEmpty()) {
            content.addView(Ui.sectionLabel(this, "例句"), marginTop(dp(26)))
            for (s in phrase.examples) content.addView(exampleCard(s), marginTop(dp(10)))
        }
        if (phrase.dialogue.isNotEmpty()) {
            content.addView(Ui.sectionLabel(this, "情景对话"), marginTop(dp(26)))
            for (line in phrase.dialogue) content.addView(bubble(line), marginTop(dp(8)))
        }
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
        }
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, Ui.FG, 44) { finish() })
        bar.addView(
            Ui.text(this, "短语详情", 14f, Ui.MUTED, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(Ui.iconButton(this, R.drawable.ic_delete, Ui.BAD, 44) { confirmDelete() })
        return bar
    }

    private fun buildHero(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = Ui.shape(this@DetailActivity, 24, Ui.SURFACE, 1, Ui.STROKE)
        }
        card.addView(Ui.text(this, "短语", 11f, Ui.ACCENT, true).apply { letterSpacing = 0.3f })
        card.addView(
            Ui.text(this, phrase.phrase, 26f, Ui.FG, true).apply {
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(dp(5).toFloat(), 1f)
            }
        )
        if (phrase.meaning.isNotBlank()) {
            card.addView(
                Ui.text(this, phrase.meaning, 14f, Ui.DIM).apply {
                    setPadding(0, dp(10), 0, 0)
                    setLineSpacing(dp(4).toFloat(), 1f)
                }
            )
        }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        chips.addView(Ui.chip(this, "播过 ${phrase.playCount} 次"))
        if (phrase.createdAt > 0L) {
            chips.addView(
                Ui.chip(this, dayOf(phrase.createdAt)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            )
        }
        card.addView(chips)
        return card
    }

    private fun buildActions(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            Ui.button(this, "朗读", R.drawable.ic_volume, true) { speak(phrase.phrase) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            Ui.button(this, "跟读", R.drawable.ic_mic, false) { onMicClick() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            }
        )
        return row
    }

    private fun buildResultCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = Ui.shape(this@DetailActivity, 20, Ui.SURFACE, 1, Ui.STROKE)
        }
        card.addView(Ui.sectionLabel(this, "跟读结果"))
        resultView = Ui.text(
            this,
            "点上面的「跟读」，说完停一下。\n漏读的标红加删除线，多读的标黄。",
            15f,
            Ui.MUTED
        ).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        card.addView(resultView)
        return card
    }

    private fun exampleCard(s: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = Ui.ripple(this@DetailActivity, 16, Ui.SURFACE, 1, Ui.STROKE)
            isClickable = true
            setOnClickListener { speak(s) }
        }
        card.addView(
            Ui.text(this, s, 15f, Ui.FG).apply { setLineSpacing(dp(4).toFloat(), 1f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(
            Ui.icon(this, R.drawable.ic_volume, Ui.MUTED, 16),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(10) }
        )
        return card
    }

    /** 对话做成聊天气泡：A 靠左，B 靠右并带一点蓝色，一眼能分清谁在说。 */
    private fun bubble(line: Line): View {
        val isB = line.speaker == "B"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isB) Gravity.END else Gravity.START
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = Ui.ripple(
                this@DetailActivity,
                18,
                if (isB) Ui.BUBBLE else Ui.SURFACE_2,
                1,
                if (isB) Ui.BUBBLE else Ui.STROKE
            )
            isClickable = true
            setOnClickListener { speak(line.text) }
        }
        box.addView(
            Ui.text(this, line.speaker, 10f, if (isB) Ui.ACCENT else Ui.MUTED, true)
                .apply { letterSpacing = 0.2f }
        )
        box.addView(
            Ui.text(this, line.text, 14f, Ui.FG).apply {
                setPadding(0, dp(5), 0, 0)
                setLineSpacing(dp(4).toFloat(), 1f)
            }
        )
        row.addView(box)
        return row
    }

    private fun dayOf(ms: Long): String =
        SimpleDateFormat("M月d日添加", Locale.CHINA).format(Date(ms))

    // ---------- 朗读 ----------

    private fun speak(text: String) {
        if (!ttsReady) {
            Toast.makeText(this, "语音引擎还没就绪，稍等一下再点", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.setSpeechRate(0.9f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "detail")
    }

    // ---------- 跟读 ----------

    private fun onMicClick() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginListening()
    }

    private fun beginListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            resultView.setTextColor(Ui.WARN)
            resultView.text = "这台手机没有可用的语音识别服务"
            return
        }
        // 先停掉朗读，不然麦克风会把喇叭里自己的声音听进去
        tts?.stop()
        recognizer?.destroy()

        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                resultView.setTextColor(Ui.DIM)
                resultView.text = "在听……说完停一下"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                resultView.text = "识别中……"
            }

            override fun onError(error: Int) {
                resultView.setTextColor(Ui.WARN)
                resultView.text = "没听清（错误码 $error），再点一次「跟读」"
                r.destroy()
                if (recognizer === r) recognizer = null
            }

            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (heard.isBlank()) {
                    resultView.setTextColor(Ui.WARN)
                    resultView.text = "这次没听到内容，再点一次「跟读」"
                } else {
                    resultView.setTextColor(Ui.FG)
                    resultView.text = renderDiff(phrase.phrase, heard)
                }
                r.destroy()
                if (recognizer === r) recognizer = null
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
        r.startListening(intent)
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
                    sb.setSpan(ForegroundColorSpan(Ui.BAD), start, end, flag)
                    sb.setSpan(StrikethroughSpan(), start, end, flag)
                }

                Diff.EXTRA -> sb.setSpan(ForegroundColorSpan(Ui.WARN), start, end, flag)
                else -> sb.setSpan(ForegroundColorSpan(Ui.FG), start, end, flag)
            }
        }

        val acc = Diff.accuracy(tokens)
        sb.append("\n\n")
        val mark = sb.length
        sb.append("命中率 ").append(acc.toString()).append("%")
        sb.setSpan(
            ForegroundColorSpan(
                when {
                    acc >= 80 -> Ui.GOOD
                    acc >= 50 -> Ui.WARN
                    else -> Ui.BAD
                }
            ), mark, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(StyleSpan(Typeface.BOLD), mark, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.25f), mark, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    // ---------- 删除 ----------

    private fun confirmDelete() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除「${phrase.phrase}」？")
            .setMessage("删掉就找不回来了。")
            .setPositiveButton("删除") { _, _ ->
                val db = Db(this)
                try {
                    db.delete(phrase.id)
                } finally {
                    db.close()
                }
                finish()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, this)
    }
}
