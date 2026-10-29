package com.speakloop.app

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 短语详情页。从列表点进来，单独占一屏。
 *
 * 跟读走本地 [FollowPanel]：AudioRecord 采集 + 实时波形 + 计时 + 结果，
 * 说完静音 1.2 秒自动收尾。不依赖系统语音识别（华为上假可用）、不依赖网络。
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

    private val exampleAnchors = ArrayList<TextView>()
    private val dialogueAnchors = ArrayList<TextView>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0
    private var playing = false
    private var activeAnchor: TextView? = null

    /** 跟读面板：本地录音 + 波形 + 结果，详情页自己一个实例 */
    private lateinit var followPanel: FollowPanel

    private val advance = Runnable {
        if (!playing) return@Runnable
        stepIndex++
        playStep()
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
        if (::followPanel.isInitialized) followPanel.release()
        playing = false
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    /** 跟读面板要麦克风权限，回执转到面板 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::followPanel.isInitialized) {
            followPanel.onPermissionResult(
                requestCode,
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )
        }
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

        // 跟读面板：录音 + 波形 + 计时 + 结果，全部本地，不依赖系统识别服务
        followPanel = FollowPanel(this)
        col.addView(followPanel.view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // 先说清楚「跟读到底干什么」，别让用户点完猜发生了什么
        voiceHint = Ui.text(this, voiceModeText(), 11f, Ui.MUTED).apply {
            setPadding(dp(2), dp(10), dp(2), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        col.addView(voiceHint)
        return col
    }

    /** 跟读只要麦克风，不需要联网、不需要系统语音识别 —— 华为上也能用。 */
    private fun voiceModeText(): String =
        "跟读会录下这一句并实时显示波形和时长，说完自动停；" +
            "结果里给发声占比和语速，点「回放对比」就能和原声比。录音只存在手机里。"

    private fun buildResultCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = Ui.shape(this@DetailActivity, 20, Ui.SURFACE, 1, Ui.STROKE)
        }
        card.addView(Ui.sectionLabel(this, "跟读结果"))
        resultView = Ui.text(
            this,
            "点上面的「跟读」说一遍：会边录边显示波形和时长，说完自动停，\n" +
                "结果给发声占比和语速，并可以回放和原声对比。",
            15f,
            Ui.MUTED
        ).apply {
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        card.addView(resultView)
        return card
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
            Ui.text(this, line.speaker, Ui.T.CAP, if (isB) Ui.ACCENT else Ui.MUTED, true)
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

    /**
     * 跟读入口。
     *
     * v2.2 起统一走本地 [FollowPanel]：录音 + 波形 + 计时 + 结果。
     * 原来的三级降级（系统语音输入界面 / 应用内 SpeechRecognizer / 录音对比）在华为上
     * 前两级要么没有、要么「能 startListening 但零回调」，只剩下 3.5 秒的干等，
     * 所以直接弃用。下面那些旧方法保留到下一版清理，现在不再从任何路径调用。
     */
    private fun onMicClick() {
        stopPlay()
        tts?.stop()
        followPanel.show(phrase.id, phrase.phrase)
        resultView.setTextColor(Ui.MUTED)
        resultView.text = "正在录音，说完会自动停。结果出来后点「回放对比」和原声比。"
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
