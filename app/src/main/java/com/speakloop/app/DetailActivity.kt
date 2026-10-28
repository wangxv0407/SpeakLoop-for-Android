package com.speakloop.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import kotlin.concurrent.thread

/**
 * 短语详情页。从列表点进来，单独占一屏。
 *
 * 跟读按三级降级，任何一级都不会让按钮「点了没反应」：
 * 1. 系统语音输入界面（ACTION_RECOGNIZE_SPEECH）：不申请权限、不依赖 Google 服务；
 * 2. 应用内 SpeechRecognizer：两条路都走不通时的次选，华为等机型常在这里假可用——
 *    能创建、能 startListening，但一个回调都不来，所以外面套了个 2.2 秒的看门狗；
 * 3. 录音对比：识别彻底不可用时直接开录音，用户自己回放和原声对比。
 *
 * 界面反馈统一走 [showStatus]，改文案的同时把结果卡滚进视野——只在下面悄悄改字，
 * 手机上一屏看不到，表现出来就是「点了没反应」。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "phraseId"

        /**
         * 本次进程里已经自动尝试过翻译的短语。
         * recreate() 会造新的 Activity 实例，普通成员变量挡不住重复触发，
         * 模型又一直返回空串时就会无限「补全 → 重建 → 再补全」。
         */
        private val autoTried = java.util.Collections.synchronizedSet(HashSet<Long>())
    }

    /** 顺序朗读的一步：读什么、多快、读完留多久、读的时候高亮哪个控件。 */
    private data class Step(
        val text: String,
        val rate: Float,
        val pauseAfter: Long,
        val anchor: TextView?
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var phrase: Phrase

    private lateinit var scroll: ScrollView
    private lateinit var resultCard: View
    private lateinit var resultView: TextView
    private lateinit var voiceHint: TextView
    private lateinit var translatePillView: TextView
    private lateinit var phraseAnchor: TextView
    private lateinit var playIcon: ImageView
    private lateinit var playLabel: TextView

    /** 识别服务假可用时（华为等机型）用来兜底：等这么久没有任何回调就换方案 */
    private var voiceWatchdog: Runnable? = null
    private var voiceResponsive = false

    private val exampleAnchors = ArrayList<TextView>()
    private val dialogueAnchors = ArrayList<TextView>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 应用内识别，只在系统没有语音输入界面时兜底用 */
    private var recognizer: SpeechRecognizer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0
    private var playing = false
    private var activeAnchor: TextView? = null

    private var memo: VoiceMemo? = null
    private lateinit var memoRecord: TextView
    private lateinit var memoPlay: TextView

    private val advance = Runnable {
        if (!playing) return@Runnable
        stepIndex++
        playStep()
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listenInApp()
        } else {
            showStatus(
                "没有麦克风权限，识别和录音都用不了。\n" +
                    "到「设置 → 应用 → SpeakLoop → 权限」里打开麦克风再试。",
                Ui.WARN
            )
        }
    }

    private val recordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else toast("没有麦克风权限，录不了")
    }

    /** 系统语音输入界面的返回结果 */
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == RESULT_OK && !heard.isNullOrBlank()) {
            showDiff(heard)
        } else {
            showStatus(
                "系统语音输入没有返回识别内容（可能被取消了，或这台机器没装识别引擎）。\n" +
                    "可以直接点下面的「录一遍」，录完自己回放对比。",
                Ui.WARN
            )
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("speakloop", MODE_PRIVATE)
        Ui.applyTheme(this)

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
        Ui.decorateWindow(this)
        memo = VoiceMemo(this)
        refreshMemo()
        maybeAutoTranslate()

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (!playing) return
                        val gap = steps.getOrNull(stepIndex)?.pauseAfter ?: 600L
                        handler.postDelayed(advance, gap)
                    }

                    override fun onError(utteranceId: String?) {
                        handler.post {
                            if (playing) {
                                stopPlay()
                                toast("语音引擎读不下去了，换个语音引擎或重开 App 试试")
                            }
                        }
                    }
                })
            }
        }
    }

    override fun onDestroy() {
        cancelVoiceWatchdog()
        memo?.release()
        memo = null
        playing = false
        handler.removeCallbacksAndMessages(null)
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
        this.scroll = scroll
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
        val res = buildResultCard()
        resultCard = res
        content.addView(res, marginTop(dp(14)))

        if (phrase.examples.isNotEmpty()) {
            content.addView(Ui.sectionLabel(this, "例句"), marginTop(dp(26)))
            phrase.examples.forEachIndexed { i, ex ->
                content.addView(exampleCard(ex, i), marginTop(dp(10)))
            }
        }
        if (phrase.dialogue.isNotEmpty()) {
            content.addView(Ui.sectionLabel(this, "情景对话"), marginTop(dp(26)))
            phrase.dialogue.forEachIndexed { i, line ->
                content.addView(bubble(line, i), marginTop(dp(8)))
            }
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

        phraseAnchor = Ui.text(this, phrase.phrase, 26f, Ui.FG, true).apply {
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        card.addView(phraseAnchor)

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

        if (needsTranslation()) {
            card.addView(
                translatePill(),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            )
        }
        return card
    }

    /**
     * 「朗读全部」按钮自己搭而不是用 Ui.button：播放和停止之间要换图标换文字，
     * 得留着两个子控件的引用。
     */
    private fun buildPlayButton(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = Ui.ripple(this@DetailActivity, 16, Ui.SOLID, 0, Ui.STROKE, 0x33FFFFFF)
            isClickable = true
            setOnClickListener { togglePlay() }
        }
        playIcon = Ui.icon(this, R.drawable.ic_play, Ui.ON_SOLID, 18).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
        }
        playLabel = Ui.text(this, "朗读全部", 14f, Ui.ON_SOLID, true)
        row.addView(playIcon)
        row.addView(playLabel)
        return row
    }

    private fun buildActions(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            buildPlayButton(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            Ui.button(this, "跟读", R.drawable.ic_mic, false) { onMicClick() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            }
        )
        col.addView(row)

        // 跟读到底会走哪条路，先写在按钮下面，别让用户点完猜发生了什么
        voiceHint = Ui.text(this, voiceModeText(), 11f, Ui.MUTED).apply {
            setPadding(dp(2), dp(10), dp(2), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        col.addView(voiceHint)
        return col
    }

    private fun voiceModeText(): String = when {
        hasVoiceUi() -> "跟读会打开系统语音输入界面，识别完自动逐词比对打分"
        SpeechRecognizer.isRecognitionAvailable(this) ->
            "跟读会用系统识别服务听一遍，识别完自动逐词比对打分"

        else -> "这台手机没有语音识别服务，跟读＝录一遍，再点「听录音」自己对比"
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
            "点上面的「跟读」说一遍，识别完这里会逐词标出漏读和多读。\n" +
                "手机没有语音识别时，跟读会改成录音，「听录音」和原声对比。",
            15f,
            Ui.MUTED
        ).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        card.addView(resultView)
        card.addView(memoRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return card
    }

    /** 录音只留最近一遍，所以这里只有「录」和「听」两个动作，没有历史列表。 */
    private fun memoRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        memoRecord = pillText("录一遍") { toggleRecord() }
        memoPlay = pillText("听录音") {
            if (memo?.play(phrase.id) != true) toast("还没有录音")
        }
        row.addView(memoRecord)
        row.addView(memoPlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        return row
    }

    private fun pillText(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 12f, Ui.FG, true).apply {
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = Ui.ripple(this@DetailActivity, 11, Ui.SURFACE_2, 1, Ui.STROKE)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toggleRecord() {
        if (memo?.recording == true) {
            memo?.stopRecording()
            memoRecord.text = "录一遍"
            memoRecord.setTextColor(Ui.FG)
            toast("已保存，点「听录音」回放")
            refreshMemo()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        stopPlay()
        tts?.stop()
        if (memo?.start(phrase.id) == true) {
            memoRecord.text = "停止录音"
            memoRecord.setTextColor(Ui.BAD)
            toast("正在录音，读完点「停止录音」")
        } else {
            toast("录音启动失败，可能有别的应用占着麦克风")
        }
    }

    private fun refreshMemo() {
        if (!::memoPlay.isInitialized) return
        val has = memo?.has(phrase.id) ?: false
        memoPlay.alpha = if (has) 1f else 0.45f
    }

    // ---------- 改例句 / 改对话 ----------

    private fun editExample(index: Int) {
        val ex = phrase.examples.getOrNull(index) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(2))
        }
        val en = W.input(this, "英文", ex.text)
        val cn = W.input(this, "中文", ex.cn)
        box.addView(en)
        box.addView(cn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("改这句例句")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val list = phrase.examples.toMutableList()
                list[index] = Example(en.text.toString().trim(), cn.text.toString().trim())
                saveContent(phrase.meaning, list, phrase.dialogue)
            }
            .setNeutralButton("删掉") { _, _ ->
                val list = phrase.examples.toMutableList()
                list.removeAt(index)
                saveContent(phrase.meaning, list, phrase.dialogue)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, this)
    }

    private fun editLine(index: Int) {
        val line = phrase.dialogue.getOrNull(index) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(2))
        }
        val en = W.input(this, "英文", line.text)
        val cn = W.input(this, "中文", line.cn)
        box.addView(en)
        box.addView(cn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("改这句对话（${line.speaker}）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val list = phrase.dialogue.toMutableList()
                list[index] = Line(line.speaker, en.text.toString().trim(), cn.text.toString().trim())
                saveContent(phrase.meaning, phrase.examples, list)
            }
            .setNeutralButton("删掉") { _, _ ->
                val list = phrase.dialogue.toMutableList()
                list.removeAt(index)
                saveContent(phrase.meaning, phrase.examples, list)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        Ui.roundDialog(dialog, this)
    }

    private fun saveContent(meaning: String, examples: List<Example>, dialogue: List<Line>) {
        val db = Db(this)
        try {
            db.updateContent(phrase.id, meaning, examples, dialogue)
        } finally {
            db.close()
        }
        recreate()
    }

    private fun exampleCard(ex: Example, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = Ui.ripple(this@DetailActivity, 16, Ui.SURFACE, 1, Ui.STROKE)
            isClickable = true
            setOnClickListener { speakOne(ex.text) }
            setOnLongClickListener {
                editExample(index)
                true
            }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val en = Ui.text(this, ex.text, 15f, Ui.FG).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        col.addView(en)
        if (ex.cn.isNotBlank()) {
            col.addView(
                Ui.text(this, ex.cn, 13f, Ui.DIM).apply {
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
            )
        }
        if (index >= exampleAnchors.size) exampleAnchors.add(en)

        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            Ui.icon(this, R.drawable.ic_volume, Ui.MUTED, 16),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(10) }
        )
        return card
    }

    /** 对话做成聊天气泡：A 靠左，B 靠右并带一点蓝色，一眼能分清谁在说。 */
    private fun bubble(line: Line, index: Int): View {
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
            setOnClickListener { speakOne(line.text) }
            setOnLongClickListener {
                editLine(index)
                true
            }
        }
        box.addView(
            Ui.text(this, line.speaker, 10f, if (isB) Ui.ACCENT else Ui.MUTED, true)
                .apply { letterSpacing = 0.2f }
        )
        val en = Ui.text(this, line.text, 14f, Ui.FG).apply {
            setPadding(0, dp(5), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        box.addView(en)
        if (line.cn.isNotBlank()) {
            box.addView(
                Ui.text(this, line.cn, 12f, Ui.DIM).apply {
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
            )
        }
        if (index >= dialogueAnchors.size) dialogueAnchors.add(en)

        row.addView(box)
        return row
    }

    /**
     * 例句 / 对话缺中文时的入口。
     * 没配 AI 也要显示出来——否则用户只看到一堆英文，根本不知道还能补。
     */
    private fun translatePill(): TextView {
        val label = if (hasApiKey()) "＋ 补全中文释义" else "例句还缺中文 · 点这里看怎么开"
        val color = if (hasApiKey()) Ui.ACCENT else Ui.WARN
        translatePillView = Ui.text(this, label, 12f, color, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.ripple(this@DetailActivity, 12, Ui.SURFACE_2, 1, Ui.STROKE)
            isClickable = true
            setOnClickListener { onTranslateClick() }
        }
        return translatePillView
    }

    private fun onTranslateClick() {
        if (!hasApiKey()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("中文释义要靠 AI 翻译")
                .setMessage(
                    "例句和情景对话的中文是调用 AI 现翻的，需要先填一个 API Key。\n\n" +
                        "底部「设置」→「AI 补全」：\n" +
                        "· Base URL：${Llm.DEFAULT_BASE}\n" +
                        "· API Key：你的 sk- 开头密钥\n" +
                        "· 模型：agnes-2.5-flash\n\n" +
                        "填完点「测试连接」确认通了，再回到这一页就会自动补上中文。"
                )
                .setPositiveButton("知道了", null)
                .create()
            dialog.show()
            Ui.roundDialog(dialog, this)
            return
        }
        translateNow(auto = false)
    }

    private fun dayOf(ms: Long): String =
        SimpleDateFormat("M月d日添加", Locale.CHINA).format(Date(ms))

    // ---------- 顺序朗读 ----------

    private fun buildSteps(): List<Step> {
        val out = ArrayList<Step>()
        out.add(Step(phrase.phrase, 0.8f, 600L, phraseAnchor))
        out.add(Step(phrase.phrase, 1.0f, 900L, phraseAnchor))
        phrase.examples.forEachIndexed { i, ex ->
            out.add(Step(ex.text, 0.9f, 1500L, exampleAnchors.getOrNull(i)))
        }
        phrase.dialogue.forEachIndexed { i, line ->
            val rate = if (line.speaker == "B") 0.9f else 0.95f
            out.add(Step(line.text, rate, 700L, dialogueAnchors.getOrNull(i)))
        }
        return out
    }

    private fun togglePlay() {
        if (playing) {
            stopPlay()
            return
        }
        if (!ttsReady) {
            toast("语音引擎还没就绪，稍等一下再点")
            return
        }
        steps = buildSteps()
        if (steps.isEmpty()) return
        stepIndex = 0
        playing = true
        updatePlayButton()
        playStep()
    }

    private fun playStep() {
        if (!playing) return
        val step = steps.getOrNull(stepIndex)
        if (step == null) {
            stopPlay()
            return
        }
        highlight(step.anchor)
        tts?.setSpeechRate(step.rate * rateScale())
        tts?.speak(step.text, TextToSpeech.QUEUE_FLUSH, null, "s$stepIndex")
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        highlight(null)
        updatePlayButton()
    }

    private fun updatePlayButton() {
        if (!::playIcon.isInitialized) return
        playIcon.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        playLabel.text = if (playing) "停止" else "朗读全部"
    }

    /** 播到哪句就把哪句染成强调色，其余复原成正文色。 */
    private fun highlight(v: TextView?) {
        activeAnchor?.let { if (it !== v) it.setTextColor(Ui.FG) }
        activeAnchor = v
        v?.setTextColor(Ui.HILITE)
    }

    /** 点某一句单独听。会先停掉整条朗读，避免两路声音打架。 */
    private fun speakOne(text: String) {
        stopPlay()
        if (!ttsReady) {
            toast("语音引擎还没就绪，稍等一下再点")
            return
        }
        tts?.setSpeechRate(0.9f * rateScale())
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "one")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 设置里那个全局语速，乘在每句自己的基准倍速上 */
    private fun rateScale(): Float = prefs.getFloat("rate", 1f)

    // ---------- 跟读 ----------

    private fun voiceIntent(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun hasVoiceUi(): Boolean =
        packageManager.queryIntentActivities(voiceIntent(""), 0).isNotEmpty()

    /**
     * 所有跟读反馈都走这里：改文案 + 把结果卡滚进视野。
     *
     * 结果卡在按钮下方的折叠区域里，之前只改文字不滚动，手机上一屏看不到，
     * 表现出来就是「点了没反应」。
     */
    private fun showStatus(text: CharSequence, color: Int) {
        if (!::resultView.isInitialized) return
        resultView.setTextColor(color)
        resultView.text = text
        if (!::resultCard.isInitialized) return
        resultCard.post {
            if (!::scroll.isInitialized) return@post
            scroll.smoothScrollTo(0, (resultCard.top - dp(8)).coerceAtLeast(0))
        }
    }

    private fun onMicClick() {
        stopPlay()
        tts?.stop()

        // 首选系统语音输入界面：不申请权限、不依赖 Google 服务
        if (hasVoiceUi()) {
            showStatus("正在打开系统语音输入界面，在里面说一遍……", Ui.DIM)
            try {
                voiceLauncher.launch(voiceIntent("跟读：${phrase.phrase}"))
                return
            } catch (e: ActivityNotFoundException) {
                // 系统说能处理、实际却没界面，继续往下试
                showStatus("系统语音输入打不开，改用应用内识别……", Ui.WARN)
            } catch (e: Exception) {
                showStatus(
                    "系统语音输入打不开（${e.javaClass.simpleName}），改用应用内识别……",
                    Ui.WARN
                )
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                showStatus("跟读需要麦克风权限，请在弹窗里点「允许」", Ui.DIM)
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                listenInApp()
            }
            return
        }

        // 两条路都没有：直接进录音对比，绝不让按钮点了没反应
        showVoiceDiagnostic("这台手机没有语音识别服务")
        beginRecordFallback()
    }

    /** 识别不可用时的兜底：录一遍，让用户自己和原声对比。 */
    private fun beginRecordFallback() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    /**
     * 华为等机型上 `isRecognitionAvailable()` 会返回 true，但 startListening 之后
     * 一个回调都不来（既没有 onReadyForSpeech，也没有 onError）。
     * 之前的版本就卡死在这里：按钮点了、界面一点变化都没有。
     * 这里加一个看门狗，等不到任何回应就判定不可用，直接换成录音对比。
     */
    private fun armVoiceWatchdog() {
        cancelVoiceWatchdog()
        val r = Runnable {
            if (voiceResponsive) return@Runnable
            recognizer?.destroy()
            recognizer = null
            showStatus(
                "系统语音识别 2 秒没有任何响应（华为等机型常见），已改成「录音对比」：\n" +
                    "读完点停止，再点下面的「听录音」和原声对比。",
                Ui.WARN
            )
            beginRecordFallback()
        }
        voiceWatchdog = r
        handler.postDelayed(r, 2200)
    }

    private fun cancelVoiceWatchdog() {
        voiceWatchdog?.let { handler.removeCallbacks(it) }
        voiceWatchdog = null
    }

    private fun listenInApp() {
        tts?.stop()
        recognizer?.destroy()

        voiceResponsive = false
        showStatus("正在启动语音识别……（2 秒没反应会自动改成录音对比）", Ui.DIM)
        armVoiceWatchdog()

        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceResponsive = true
                cancelVoiceWatchdog()
                resultView.setTextColor(Ui.DIM)
                resultView.text = "在听……说完停一下"
            }

            override fun onBeginningOfSpeech() {
                voiceResponsive = true
                cancelVoiceWatchdog()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                voiceResponsive = true
                cancelVoiceWatchdog()
                resultView.text = "识别中……"
            }

            override fun onError(error: Int) {
                voiceResponsive = true
                cancelVoiceWatchdog()
                resultView.setTextColor(Ui.WARN)
                resultView.text = buildString {
                    append("应用内识别失败（错误码 ").append(error).append("）\n")
                    append(errorHint(error))
                    append("\n再点一次「跟读」重试，或点下面「录一遍」自己对比。")
                }
                r.destroy()
                if (recognizer === r) recognizer = null
            }

            override fun onResults(results: Bundle?) {
                voiceResponsive = true
                cancelVoiceWatchdog()
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (heard.isBlank()) {
                    resultView.setTextColor(Ui.WARN)
                    resultView.text = "这次没听到内容，再点一次「跟读」"
                } else {
                    showDiff(heard)
                }
                r.destroy()
                if (recognizer === r) recognizer = null
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = voiceIntent("说一遍这句话").apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            cancelVoiceWatchdog()
            showStatus(
                "识别服务启动失败（${e.javaClass.simpleName}），已改成「录音对比」。",
                Ui.WARN
            )
            beginRecordFallback()
        }
    }

    private fun errorHint(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，可能麦克风被别的应用占着。"
        SpeechRecognizer.ERROR_CLIENT -> "客户端异常，通常是识别服务没启动或版本不匹配。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足，检查系统设置里的录音权限。"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "识别服务需要联网，检查网络。"

        SpeechRecognizer.ERROR_NO_MATCH -> "没匹配到词，可能是环境太吵或说得太轻。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，等一下再试。"
        SpeechRecognizer.ERROR_SERVER -> "识别服务端出错。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "一直没听到声音，检查麦克风。"
        else -> "未知错误。"
    }

    /** 实在不行就把现场情况摊开给人看，比只说「失败」有用。 */
    private fun showVoiceDiagnostic(reason: String) {
        showStatus(
            buildString {
                append(reason).append('\n')
                append("系统语音输入界面：").append(if (hasVoiceUi()) "有" else "没有").append('\n')
                append("应用内识别服务：")
                append(
                    if (SpeechRecognizer.isRecognitionAvailable(this@DetailActivity)) "有（但没响应）"
                    else "没有"
                )
                append("\n已切成「录音对比」：读完点停止，再点「听录音」和原声对比。")
            },
            Ui.WARN
        )
    }

    private fun showDiff(heard: String) {
        showStatus(renderDiff(phrase.phrase, heard), Ui.FG)
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

    // ---------- 中文补全 ----------

    private fun hasApiKey(): Boolean = prefs.getString("apiKey", "").orEmpty().isNotBlank()

    private fun needsTranslation(): Boolean =
        phrase.examples.any { it.cn.isBlank() } || phrase.dialogue.any { it.cn.isBlank() }

    /**
     * 进页面就自动补一次中文，不再让用户自己找那个小胶囊。
     * 只在配了 Key 时才自动跑；同一条短语在本进程内只试一次，防止失败后反复重建。
     */
    private fun maybeAutoTranslate() {
        if (!needsTranslation() || !hasApiKey()) return
        if (!autoTried.add(phrase.id)) return
        translateNow(auto = true)
    }

    /**
     * 只补空着的中文，不重新生成句子。
     *
     * 早先这里走的是 Llm.enrich，会把整段例句 / 对话换掉——用户手改过的内容会被覆盖。
     * 现在按顺序把缺中文的句子送出去翻译，再按原顺序填回空位，其余一个字不动。
     *
     * @param auto 进页面时自动跑的那次：失败不弹 toast，只把入口改成「点这里重试」
     */
    private fun translateNow(auto: Boolean) {
        val pending = ArrayList<String>()
        phrase.examples.forEach { if (it.cn.isBlank()) pending.add(it.text) }
        phrase.dialogue.forEach { if (it.cn.isBlank()) pending.add(it.text) }
        if (pending.isEmpty()) return

        if (::translatePillView.isInitialized) {
            translatePillView.text = "正在补全中文…"
            translatePillView.setTextColor(Ui.DIM)
        }
        if (!auto) toast("正在让 AI 翻译这 ${pending.size} 句……")

        thread {
            var out: List<String>? = null
            var error: String? = null
            try {
                out = Llm.translateLines(
                    prefs.getString("baseUrl", "").orEmpty().ifBlank { Llm.DEFAULT_BASE },
                    prefs.getString("apiKey", "").orEmpty(),
                    prefs.getString("model", "").orEmpty().ifBlank { "agnes-2.5-flash" },
                    pending
                )
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                val got = out
                if (got == null) {
                    if (::translatePillView.isInitialized) {
                        translatePillView.text = "补全失败 · 点这里重试"
                        translatePillView.setTextColor(Ui.BAD)
                    }
                    if (!auto) toast("补全失败：${error ?: "未知原因"}")
                    return@runOnUiThread
                }

                var k = 0
                val newExamples = phrase.examples.map { e ->
                    if (e.cn.isBlank()) e.copy(cn = got.getOrNull(k++).orEmpty()) else e
                }
                val newDialogue = phrase.dialogue.map { l ->
                    if (l.cn.isBlank()) l.copy(cn = got.getOrNull(k++).orEmpty()) else l
                }
                val changed = newExamples != phrase.examples || newDialogue != phrase.dialogue
                if (!changed) {
                    // 模型什么都没给，别再 recreate 一遍，否则会无限循环
                    if (::translatePillView.isInitialized) {
                        translatePillView.text = "模型没返回中文 · 点这里重试"
                        translatePillView.setTextColor(Ui.BAD)
                    }
                    toast("模型没返回中文，可能换了别的模型字段，点一下可以重试")
                    return@runOnUiThread
                }

                val db = Db(this)
                try {
                    db.updateContent(phrase.id, phrase.meaning, newExamples, newDialogue)
                } finally {
                    db.close()
                }
                if (!auto) toast("已补全中文")
                recreate()
            }
        }
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
